package org.mlm.mages.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import org.mlm.mages.push.CallAudioRouteBridge
import org.mlm.mages.push.CallAudioRouteHandler

object MagesTelecomCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val setupMutex = Mutex()
    private val sessions = mutableMapOf<String, CallControlScope?>()
    private val jobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    @Volatile private var registered = false

    @SuppressLint("MissingPermission")
    fun register(appContext: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || registered) return
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)) {
            Logger.w { "Telecom: FEATURE_TELECOM missing, skipping register" }
            return
        }
        try {
            val mgr = CallsManager(appContext)
            mgr.registerAppWithTelecom(
                CallsManager.CAPABILITY_BASELINE or
                    CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING
            )
            registered = true
            Logger.i { "Telecom: registered" }
        } catch (e: Exception) {
            Logger.w(e) { "Telecom: registerAppWithTelecom failed, fallback to FGS" }
        }
    }

    suspend fun addOngoingCallSync(
        appContext: Context,
        roomId: String,
        roomName: String,
        isVideo: Boolean = true,
        isIncoming: Boolean = false,
        onRemoteDisconnect: (() -> Unit)? = null,
    ) {
        setupMutex.withLock {
            for (key in (sessions.keys + jobs.keys).toSet()) {
                if (!key.startsWith("in:") && key != roomId) {
                    val swept = runCatching {
                        sessions.remove(key)?.disconnect(
                            android.telecom.DisconnectCause(
                                android.telecom.DisconnectCause.LOCAL
                            )
                        )
                    }
                    Logger.i { "Telecom: sweep-disconnect $key -> $swept" }
                    jobs.remove(key)?.cancel()
                }
            }
            if (jobs.containsKey(roomId)) return
            jobs[roomId] = scope.launch {
                runOngoingCall(appContext, roomId, roomName, isVideo, isIncoming, onRemoteDisconnect)
            }
        }
    }

    suspend fun removeCallSync(sessionKey: String) {
        val self = coroutineContext[Job]
        val child: Job? = setupMutex.withLock {
            val told = runCatching {
                sessions.remove(sessionKey)?.disconnect(
                    android.telecom.DisconnectCause(
                        android.telecom.DisconnectCause.LOCAL
                    )
                )
            }
            Logger.i { "Telecom: remove-disconnect $sessionKey -> $told" }
            jobs.remove(sessionKey)?.also { it.cancel() }
        }
        if (child != null && child !== self) {
            runCatching { withTimeoutOrNull(8_000) { child.join() } }
        }
    }

    private suspend fun runOngoingCall(
        appContext: Context,
        roomId: String,
        roomName: String,
        isVideo: Boolean,
        isIncoming: Boolean,
        onRemoteDisconnect: (() -> Unit)?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val mgr = try {
            CallsManager(appContext.applicationContext)
        } catch (e: Exception) {
            Logger.w(e) { "Telecom: CallsManager() failed" }
            return
        }
        val attrs = CallAttributesCompat(
            displayName = roomName,
            address = Uri.parse("mages:$roomId"),
            direction = if (isIncoming) CallAttributesCompat.DIRECTION_INCOMING
            else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = if (isVideo) CallAttributesCompat.CALL_TYPE_VIDEO_CALL
            else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        var routeHandler: CallAudioRouteHandler? = null
        var endpointsJob: Job? = null
        try {
            mgr.addCall(
                attrs,
                { _: Int -> /* onAnswer (already active) */ },
                {
                    Logger.i { "Telecom: remote disconnect $roomId" }
                    onRemoteDisconnect?.invoke()
                    sessions.remove(roomId)
                },
                { /* onActive */ },
                { /* onInactive (hold) */ },
            ) {
                val control = this
                sessions[roomId] = control
                val endpoints = AtomicReference<List<CallEndpointCompat>>(emptyList())
                val pendingRouteType = AtomicReference<Int?>(null)
                endpointsJob = launch {
                    control.availableEndpoints.collect { available ->
                        endpoints.set(available)
                        val pending = pendingRouteType.getAndSet(null) ?: return@collect
                        available.firstOrNull { it.type == pending }?.let { endpoint ->
                            control.requestEndpointChange(endpoint)
                        }
                    }
                }
                val handler = CallAudioRouteHandler { device ->
                    val type = device.type.toCallEndpointType()
                    if (type == CallEndpointCompat.TYPE_UNKNOWN) return@CallAudioRouteHandler false
                    val available = endpoints.get()
                    if (available.isEmpty()) {
                        pendingRouteType.set(type)
                        return@CallAudioRouteHandler false
                    }
                    val endpoint = available.firstOrNull { it.type == type }
                        ?: return@CallAudioRouteHandler false
                    scope.launch {
                        val result = control.requestEndpointChange(endpoint)
                        Logger.i { "Telecom: route ${device.type} -> $result" }
                    }
                    true
                }
                routeHandler = handler
                CallAudioRouteBridge.handler = handler
                scope.launch {
                    val setActiveResult = runCatching { control.setActive() }
                    Logger.i { "Telecom: setActive $roomId -> $setActiveResult" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Telecom: addCall(ongoing) failed, FGS fallback covers bg" }
        } finally {
            endpointsJob?.cancel()
            if (routeHandler != null && CallAudioRouteBridge.handler === routeHandler) {
                CallAudioRouteBridge.handler = null
            }
            setupMutex.withLock {
                sessions.remove(roomId)
                jobs.remove(roomId)
            }
        }
    }
}

@Suppress("InlinedApi")
private fun Int.toCallEndpointType(): Int = when (this) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallEndpointCompat.TYPE_EARPIECE
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallEndpointCompat.TYPE_SPEAKER
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET -> CallEndpointCompat.TYPE_WIRED_HEADSET
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> CallEndpointCompat.TYPE_BLUETOOTH
    else -> CallEndpointCompat.TYPE_UNKNOWN
}
