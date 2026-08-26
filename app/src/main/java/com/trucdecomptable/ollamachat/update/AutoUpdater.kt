package com.trucdecomptable.ollamachat.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.trucdecomptable.ollamachat.util.DiagnosticLog
import java.io.File

/** The two system hand-offs the update flow needs. */
object AutoUpdater {

    /** Opens the system installer on [file]. */
    fun install(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            DiagnosticLog.record("update/install", e)
        }
    }

    /** Sends the user to the "install unknown apps" screen for this app. */
    fun openInstallSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            DiagnosticLog.record("update/settings", e)
        }
    }
}
