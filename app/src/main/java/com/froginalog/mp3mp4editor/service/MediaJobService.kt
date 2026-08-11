package com.froginalog.mp3mp4editor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.froginalog.mp3mp4editor.EditorApp
import com.froginalog.mp3mp4editor.MainActivity
import com.froginalog.mp3mp4editor.R
import com.froginalog.mp3mp4editor.media.MediaJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps downloads and exports alive while the app is in the background, and mirrors queue state
 * into the ongoing notification.
 */
class MediaJobService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            (application as EditorApp).container.jobManager.jobs.collectLatest { jobs ->
                val active = jobs.filter { it.isActive }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateNotification(active)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            startForegroundCompat(buildNotification("Preparing…", null, 0f))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(active: List<MediaJob>) {
        val head = active.first()
        val title = if (active.size > 1) {
            "${head.title} (+${active.size - 1} more)"
        } else {
            head.title
        }
        val notification = buildNotification(title, head.status, head.progress)
        startForegroundCompat(notification)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun buildNotification(title: String, text: String?, progress: Float?): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_job)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (progress == null) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
                }
            }
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_jobs_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_jobs_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "media_jobs"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, MediaJobService::class.java)
            runCatching { context.startForegroundService(intent) }
        }
    }
}
