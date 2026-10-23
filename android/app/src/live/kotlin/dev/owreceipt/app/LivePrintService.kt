package dev.owreceipt.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import dev.owreceipt.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Keeps an explicitly started, potentially long receipt job alive while the user changes apps. */
class LivePrintService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val channel = "receipt_print"
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(channel, "小票打印", NotificationManager.IMPORTANCE_LOW))
        startForeground(9516, notification("正在准备小票打印", 0))
        scope.launch {
            var began = false
            val timeout = launch { delay(10_000); if (!began) stopSelf() }
            (application as ReceiptApplication).graph.printer.state
                .map { it.copy(progress = (it.progress * 100).toInt() / 100f) }.distinctUntilChanged().collect { state ->
                if (state.status.active) {
                    began = true; timeout.cancel()
                    if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                        notifications.notify(9516, notification(state.detail ?: "正在发送小票", (state.progress * 100).toInt()))
                } else if (began) stopSelf()
            }
        }
    }
    private fun notification(text: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LivePrintService::class.java).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_receipt).setContentTitle("战绩小票")
            .setContentText(text).setContentIntent(open).setOngoing(true).setProgress(100, progress, false)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") scope.launch {
            try { (application as ReceiptApplication).graph.printer.stop() } finally { stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
