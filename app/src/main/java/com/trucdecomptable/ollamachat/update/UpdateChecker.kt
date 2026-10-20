package com.trucdecomptable.ollamachat.update

import com.trucdecomptable.ollamachat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Checks GitHub Releases for a newer APK. Public repo required for
 * unauthenticated asset downloads.
 */
object UpdateChecker {

    const val REPO = "ClawFabriceH92/ollamachat"

    /** A tiny manifest CI publishes next to the APK, read before the API. */
    const val MANIFEST_URL = "https://github.com/$REPO/releases/download/latest/version.json"
    const val API_URL = "https://api.github.com/repos/$REPO/releases"
    const val APK_URL = "https://github.com/$REPO/releases/download/latest/app-release.apk"

    private const val TIMEOUT_MS = 10_000

    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val notes: String = "",
    )

    /**
     * Three outcomes, not two.
     *
     * Folding "could not ask" into "nothing newer" is what made a blocked
     * check answer "you are already up to date" — the most misleading thing
     * the app could say, because it closes the question instead of naming it.
     */
    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        /**
         * [answered]: something at GitHub replied, even with a refusal or a
         * missing file. The network works then, and blaming a tunnel for it
         * would be wrong.
         */
        data class Unreachable(val reason: String, val answered: Boolean = false) : CheckResult
    }

    /** GitHub answered with a status that is not a success. */
    class HttpStatusException(val code: Int, reason: String) : IOException(reason)

    /** Looks for a release newer than [installed]. */
    suspend fun check(installed: String = BuildConfig.VERSION_NAME): CheckResult =
        withContext(Dispatchers.IO) {
            // The manifest comes first because it is a plain asset download,
            // so it escapes the unauthenticated API quota of 60 requests per
            // hour and per source address — a quota strangers have routinely
            // already spent when the phone leaves through a shared VPN exit.
            val manifest = runCatching { fetch(MANIFEST_URL, github = false) }
            manifest.getOrNull()
                ?.let { parseManifest(it, installed) }
                ?.let { return@withContext it }

            // Releases published before the manifest existed still answer here.
            val api = runCatching { fetch(API_URL, github = true) }
            api.getOrNull()
                ?.let { parseApi(it, installed) }
                ?.let { return@withContext it }

            failureVerdict(manifest, api)
        }

    /**
     * Why neither source gave an answer, and whether anything answered at all.
     *
     * A network failure explains everything. Failing that, the API's answer
     * (a quota, a refusal) says more than the manifest's 404, which used to
     * win and hide the quota message entirely.
     */
    internal fun failureVerdict(manifest: Result<String>, api: Result<String>): CheckResult.Unreachable {
        val failures = listOfNotNull(manifest.exceptionOrNull(), api.exceptionOrNull())
        val network = failures.firstOrNull { it !is HttpStatusException }
        val cause = network ?: api.exceptionOrNull() ?: manifest.exceptionOrNull()
        val answered = manifest.isSuccess || api.isSuccess || failures.any { it is HttpStatusException }
        return CheckResult.Unreachable(cause?.let { describe(it) } ?: "réponse illisible", answered)
    }

    private fun fetch(url: String, github: Boolean): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "OllamaChat/${BuildConfig.VERSION_NAME}")
            if (github) conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw HttpStatusException(code, httpReason(code, conn.getHeaderField("x-ratelimit-remaining")))
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Turns an HTTP code into something worth reading. A 403 with the quota at
     * zero is the exact shape a shared VPN exit address produces, so it says so
     * rather than leaving the user with a bare number.
     */
    internal fun httpReason(code: Int, rateLimitRemaining: String?): String = when {
        code == HttpURLConnection.HTTP_FORBIDDEN && rateLimitRemaining == "0" ->
            "quota GitHub épuisé pour cette adresse IP (fréquent derrière un VPN à sortie partagée)"
        code == HttpURLConnection.HTTP_FORBIDDEN -> "accès refusé par GitHub (HTTP 403)"
        code == HttpURLConnection.HTTP_NOT_FOUND -> "ressource absente (HTTP 404)"
        else -> "HTTP $code"
    }

    /** A failure the user can act on, instead of a stack trace class name. */
    internal fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "nom d'hôte introuvable, DNS injoignable (${e.message.orEmpty()})"
        is SocketTimeoutException -> "délai d'attente dépassé"
        is NoRouteToHostException -> "aucune route vers l'hôte"
        is ConnectException -> "connexion impossible"
        is SSLException -> "échec TLS (${e.message.orEmpty()})"
        is IOException -> e.message.orEmpty().ifBlank { "erreur réseau" }
        else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
    }

    /** Reads the release manifest. Null when the body is not one. */
    internal fun parseManifest(body: String, installed: String): CheckResult? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val version = extractVersion(json.optString("version", "")) ?: return null
        val apkUrl = json.optString("apk", "").ifBlank { APK_URL }
        return if (compareVersions(version, installed) > 0) {
            CheckResult.Available(UpdateInfo(version, apkUrl, json.optString("notes", "")))
        } else {
            CheckResult.UpToDate
        }
    }

    /** Reads the releases API. Null when the body is not a release list. */
    internal fun parseApi(body: String, installed: String): CheckResult? {
        val releases = runCatching { JSONArray(body) }.getOrNull() ?: return null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            // The rolling release is tagged "latest", so the version has to
            // come from the title; reading it off the tag made every check
            // compare "latest" to a real version and never find an update.
            val version = extractVersion(release.optString("tag_name", ""))
                ?: extractVersion(release.optString("name", ""))
                ?: continue
            val assets = release.optJSONArray("assets") ?: JSONArray()
            val apkUrl = pickApk(assets, version) ?: continue
            if (compareVersions(version, installed) > 0) {
                return CheckResult.Available(
                    UpdateInfo(version, apkUrl, release.optString("body", ""))
                )
            }
        }
        return CheckResult.UpToDate
    }

    /** First dotted number in [text] ("OllamaChat v1.3.0" -> "1.3.0"), or null. */
    fun extractVersion(text: String): String? =
        Regex("""\d+(?:\.\d+)+""").find(text)?.value

    /**
     * Picks the APK to install. A rolling release can still carry assets from
     * an older build, so an asset naming the new version wins, then the
     * canonical name, and only then the first one found.
     */
    fun pickApk(assets: JSONArray, version: String): String? {
        val apks = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name", "").endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val byVersion = apks.firstOrNull { it.optString("name").contains(version) }
        val canonical = apks.firstOrNull { it.optString("name") == "app-release.apk" }
        return (byVersion ?: canonical ?: apks.first()).optString("browser_download_url")
            .ifBlank { null }
    }

    /** 1 if a > b, -1 if a < b, 0 if equal. Segment-by-segment numeric compare. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va > vb) return 1
            if (va < vb) return -1
        }
        return 0
    }
}
