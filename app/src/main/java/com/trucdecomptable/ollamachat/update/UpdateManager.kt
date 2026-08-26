package com.trucdecomptable.ollamachat.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.trucdecomptable.ollamachat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Checks for updates at launch, notifies when a new version is available and
 * downloads+installs it when the "install unknown apps" permission is granted.
 */
object UpdateManager {

    private const val CHANNEL_ID = "updates"
    private const val NOTIF_ID = 42

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(context: Context) {
        if (BuildConfigHelper.isDebug()) return
        createChannel(context)
        scope.launch {
            val info = UpdateChecker.checkForUpdate() ?: return@launch
            if (canRequestInstalls(context)) {
                AutoUpdater.download(context, info.apkUrl, info.version)
                notify(context, context.getString(R.string.notification_update_downloading, info.version))
            } else {
                notify(
                    context,
                    context.getString(R.string.notification_update_available, info.version),
                    info.notes,
                    installSettingsPendingIntent(context),
                )
            }
        }
    }

    private fun canRequestInstalls(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    private fun createChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(channel)
    }

    private fun notify(
        context: Context,
        title: String,
        text: String = "",
        contentIntent: PendingIntent? = null,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, builder.build())
    }

    private fun installSettingsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

private object BuildConfigHelper {
    fun isDebug(): Boolean = com.trucdecomptable.ollamachat.BuildConfig.DEBUG
}
