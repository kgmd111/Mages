package org.mlm.mages.calls

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.CallIntent
import org.mlm.mages.matrix.CallWidgetObserver
import org.mlm.mages.platform.CallWebViewController
import kotlin.random.Random
import kotlin.time.Clock

data class GlobalCallState(
    val roomId: String,
    val roomName: String,
    val sessionId: ULong,
    val widgetUrl: String,
    val widgetBaseUrl: String?,
    val parentUrl: String?,
    val minimized: Boolean = false,
    val startedAtMs: Long = 0L,
    val pipX: Float = 24f,
    val pipY: Float = 120f,
    val pipW: Float = 220f,
    val pipH: Float = 140f,

)

private const val HANGUP_GRACE_MS = 1500L
private const val WIDGET_ENDED_GRACE_MS = 250L
private const val WIDGET_HANGUP_ACTION = "im.vector.hangup"

class CallManager(
    private val service: MatrixService
) {
    private val _call = MutableStateFlow<GlobalCallState?>(null)
    val call: StateFlow<GlobalCallState?> = _call.asStateFlow()

    private val _externalHost = MutableStateFlow(false)
    val externalHost: StateFlow<Boolean> = _externalHost.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun setExternalHost(hosted: Boolean) {
        _externalHost.value = hosted
    }

    private var controller: CallWebViewController? = null
    private val pendingToWidget = ArrayDeque<String>()
    private var hangupRequested = false
    private var hangupSignal: CompletableDeferred<Unit>? = null

    var onCallStateChanged: ((isActive: Boolean, roomName: String?) -> Unit)? = null

    fun isInCall(): Boolean = _call.value != null
    fun isInCall(roomId: String): Boolean = _call.value?.roomId == roomId

    fun setMinimized(min: Boolean) {
        _call.value = _call.value?.copy(minimized = min)
    }

    fun movePip(x: Float, y: Float) {
        _call.value = _call.value?.copy(pipX = x, pipY = y)
    }

    fun resizePip(wDp: Float, hDp: Float) {
        _call.value = _call.value?.copy(pipW = wDp, pipH = hDp)
    }

    fun attachController(c: CallWebViewController?) {
        controller = c
        if (c != null) {
            val drained = pendingToWidget.toList()
            pendingToWidget.clear()
            drained.forEach { c.sendToWidget(it) }
        }
    }

    suspend fun onMessageFromWidget(message: String) {
        val s = _call.value ?: return
        val port = service.portOrNull ?: return
        port.callWidgetFromWebview(s.sessionId, message)
    }

    fun onToWidgetFromSdk(message: String) {
        val c = controller
        if (c != null) c.sendToWidget(message) else pendingToWidget.addLast(message)
    }

    suspend fun startOrJoinCall(
        roomId: String,
        roomName: String,
        intent: CallIntent,
        elementCallUrl: String?,
        parentUrl: String?,
        languageTag: String?,
        theme: String?,
    ): Boolean {
        val port = service.portOrNull ?: return false

        val current = _call.value
        if (current != null) {
            if (current.roomId == roomId) return true
            val signal = CompletableDeferred<Unit>()
            hangupSignal = signal
            hangupRequested = true
            val requested = requestWidgetHangup(current)
            if (requested) withTimeoutOrNull(HANGUP_GRACE_MS) { signal.await() }
            teardown(current)
        }

        val session = port.startElementCall(
            roomId = roomId,
            intent = intent,
            elementCallUrl = elementCallUrl,
            parentUrl = parentUrl,
            languageTag = languageTag,
            theme = theme,
            observer = object : CallWidgetObserver {
                override fun onToWidget(message: String) {
                    onToWidgetFromSdk(message)
                }
            }
        ) ?: return false

        _call.value = GlobalCallState(
            roomId = roomId,
            roomName = roomName,
            sessionId = session.sessionId,
            widgetUrl = session.widgetUrl,
            widgetBaseUrl = session.widgetBaseUrl,
            parentUrl = session.parentUrl,
            minimized = false,
            startedAtMs = Clock.System.now().toEpochMilliseconds()
        )
        onCallStateChanged?.invoke(true, roomName)
        return true
    }

    /**
     * Host-initiated end: the Element Call widget owns the call session, so it has to be
     * asked to leave first, otherwise the room keeps the call alive for everyone else.
     */
    fun endCall() {
        val s = _call.value ?: return
        if (hangupRequested) return
        hangupRequested = true
        val signal = CompletableDeferred<Unit>()
        hangupSignal = signal
        if (!requestWidgetHangup(s)) {
            hangupSignal = null
            teardown(s)
            return
        }
        scope.launch {
            withTimeoutOrNull(HANGUP_GRACE_MS) { signal.await() }
            teardown(s)
        }
    }

    /**
     * The call view went away. [widgetEnded] is true when the widget reported the end itself
     * (it already left the call server-side), false when the host closed the view.
     */
    fun onCallEnded(sessionId: ULong, widgetEnded: Boolean) {
        val s = _call.value ?: return
        if (s.sessionId != sessionId) return
        val signal = hangupSignal
        if (signal != null) {
            signal.complete(Unit)
            return
        }
        if (widgetEnded) {
            scope.launch {
                delay(WIDGET_ENDED_GRACE_MS)
                teardown(s)
            }
        } else {
            endCall()
        }
    }

    private fun requestWidgetHangup(s: GlobalCallState): Boolean {
        val c = controller ?: return false
        val requestId = "${Random.nextLong().toULong().toString(16)}-${Clock.System.now().toEpochMilliseconds()}"
        val message = "{\"api\":\"toWidget\",\"widgetId\":\"mages-ecall-${s.sessionId}\"," +
            "\"requestId\":\"$requestId\",\"action\":\"$WIDGET_HANGUP_ACTION\",\"data\":{}}"
        c.sendToWidget(message)
        return true
    }

    private fun teardown(s: GlobalCallState) {
        runCatching { service.portOrNull?.stopElementCall(s.sessionId) }
            .onFailure { Logger.w("Failed to stop element call session ${s.sessionId}: ${it.message}") }
        if (_call.value?.sessionId != s.sessionId) return
        pendingToWidget.clear()
        controller = null
        _call.value = null
        hangupRequested = false
        hangupSignal = null
        onCallStateChanged?.invoke(false, null)
    }
}
