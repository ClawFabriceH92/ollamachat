package com.trucdecomptable.ollamachat.service

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
import com.trucdecomptable.ollamachat.MainActivity
import com.trucdecomptable.ollamachat.R

/**
 * Keeps the process alive while the model is generating.
 *
 * Without it, backgrounding the app during a long answer lets Android reclaim
 * the process and the answer is lost halfway through — the coroutine scope
 * survives configuration changes, not process death.
 */
class GenerationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        createChannel(this)
        val notification = build(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Foreground start refused (background restrictions): the answer
            // still streams, it just is not protected from process death.
            stopSelf()
        }
    }

    private fun build(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_generating_title))
            .setContentText(context.getString(R.string.notification_generating_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, GenerationService::class.java))
            } catch (_: Exception) {
                // Never let a service start crash a send.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, GenerationService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_generation),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }
}
