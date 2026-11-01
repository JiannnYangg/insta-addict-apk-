package app.instaaddict.runner.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.instaaddict.runner.MainActivity
import app.instaaddict.runner.R
import app.instaaddict.runner.core.AppLog

/** Keeps the app alive (with a notification) while a task runs. */
class RunnerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "Task"
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Running tasks", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n: Notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("InstaAddict is running")
                .setContentText(title)
                .setOngoing(true)
                .setContentIntent(open)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            AppLog.w("Service", "Could not show the running notification: ${e.message}")
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "run"

        fun start(ctx: Context, title: String) {
            try {
                val i = Intent(ctx, RunnerService::class.java).putExtra("title", title)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                AppLog.w("Service", "Could not start the background service: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, RunnerService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
