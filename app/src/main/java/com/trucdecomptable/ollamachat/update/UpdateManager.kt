package com.trucdecomptable.ollamachat.update

import android.content.Context
import android.os.Build
import com.trucdecomptable.ollamachat.BuildConfig
import com.trucdecomptable.ollamachat.util.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Update flow, driven from inside the app.
 *
 * It used to hand the download to DownloadManager and wait for a
 * DOWNLOAD_COMPLETE broadcast on a receiver declared `exported="false"` — a
 * system broadcast a non-exported receiver never receives, so the install step
 * simply never fired. Downloading here removes the receiver, the broadcast and
 * the whole class of problem, and lets the dialog show real progress.
 */
object UpdateManager {

    sealed interface State {
        data object Idle : State
        data class Available(val version: String, val notes: String, val apkUrl: String) : State
        data class Downloading(val version: String, val progress: Float) : State
        data class ReadyToInstall(val version: String, val file: File) : State
        data class UpToDate(val version: String) : State
        data class Failed(val detail: String?) : State
    }

    private const val DIR = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Checks at launch. [skippedVersion] is what the user already dismissed —
     * offering it again on every start is nagging, not helping.
     */
    fun checkAtLaunch(skippedVersion: String) {
        if (BuildConfig.DEBUG) return
        check(skippedVersion = skippedVersion, manual = false)
    }

    /** Manual check from the settings; ignores the skip and reports "up to date". */
    fun checkNow() = check(skippedVersion = "", manual = true)

    private fun check(skippedVersion: String, manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            val info = UpdateChecker.checkForUpdate()
            _state.value = when {
                info == null -> if (manual) State.UpToDate(BuildConfig.VERSION_NAME) else State.Idle
                info.version == skippedVersion -> State.Idle
                else -> State.Available(info.version, info.notes, info.apkUrl)
            }
        }
    }

    /** Downloads the APK, reporting progress into [state]. */
    fun download(context: Context) {
        val available = _state.value as? State.Available ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Downloading(available.version, 0f)
            val result = runCatching { fetch(context, available.version, available.apkUrl) }
            _state.value = result.fold(
                onSuccess = { State.ReadyToInstall(available.version, it) },
                onFailure = {
                    DiagnosticLog.record("update", it)
                    State.Failed(it.message)
                },
            )
        }
    }

    private suspend fun fetch(context: Context, version: String, url: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            // One update at a time: stale APKs are dead weight.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "ollamachat-v$version.apk")

            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Corps vide")
                val total = body.contentLength()
                var written = 0L

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = input.read(buffer)
                        while (read != -1) {
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                _state.value = State.Downloading(
                                    version,
                                    (written.toFloat() / total).coerceIn(0f, 1f),
                                )
                            }
                            read = input.read(buffer)
                        }
                    }
                }
            }
            if (target.length() == 0L) error("Téléchargement vide")
            target
        }

    /** Hands the APK to the system installer. */
    fun install(context: Context) {
        val ready = _state.value as? State.ReadyToInstall ?: return
        AutoUpdater.install(context, ready.file)
    }

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    fun openInstallSettings(context: Context) = AutoUpdater.openInstallSettings(context)

    /** Dismisses the current prompt; the caller persists the skipped version. */
    fun dismiss() {
        _state.value = State.Idle
    }
}
