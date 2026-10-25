package org.mlm.mages.push

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.mlm.mages.shared.R
import org.mlm.mages.activities.CallActivity
import org.koin.core.context.GlobalContext
import org.mlm.mages.calls.CallManager

class CallForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_END_CALL) {
            scope.launch {
                val callManager = runCatching {
                    GlobalContext.getOrNull()?.get<CallManager>()
                }.getOrNull()
                if (callManager == null) {
                    Logger.w("Cannot end call: call manager unavailable")
                } else {
                    callManager.endCall()
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val roomName = intent?.getStringExtra(EXTRA_ROOM_NAME) ?: getString(R.string.notif_ongoing_call)
        val roomId = intent?.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        val notification = buildNotification(roomName, roomId)
        val type = callForegroundServiceType()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: RuntimeException) {
            Logger.w("Could not start call foreground service: ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun callForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(roomName: String, roomId: String): Notification {
        val openIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPendingIntent = PendingIntent.getService(
            this, 1, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AppNotificationChannels.CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_notif_status_bar)
            .setContentTitle(getString(R.string.notif_ongoing_call))
            .setContentText(roomName)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                R.drawable.ic_notif_status_bar, getString(R.string.notif_end_call),
                endPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        val NOTIFICATION_ID = "call_foreground_service".hashCode()

        private const val ACTION_STOP = "org.mlm.mages.push.CallForegroundService.STOP"
        const val ACTION_END_CALL = "org.mlm.mages.push.CallForegroundService.END_CALL"
        private const val EXTRA_ROOM_NAME = "room_name"
        private const val EXTRA_ROOM_ID = "room_id"

        fun start(context: Context, roomName: String, roomId: String = "") {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Logger.w("Cannot start call foreground service without microphone permission")
                return
            }
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_ROOM_NAME, roomName)
                putExtra(EXTRA_ROOM_ID, roomId)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
