package com.trucdecomptable.ollamachat.update

import com.trucdecomptable.ollamachat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK. Public repo required for
 * unauthenticated asset downloads.
 */
object UpdateChecker {

    const val REPO = "ClawFabriceH92/ollamachat"

    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val notes: String = "",
    )

    /** Returns the newest release with an .apk asset, or null if none / up-to-date. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.github.com/repos/$REPO/releases").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "OllamaChat")
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            val releases = org.json.JSONArray(body)
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft")) continue
                // The rolling release is tagged "latest", so the version has to
                // come from the title; reading it off the tag made every check
                // compare "latest" to a real version and never find an update.
                val version = extractVersion(release.optString("tag_name", ""))
                    ?: extractVersion(release.optString("name", ""))
                    ?: continue
                val assets = release.optJSONArray("assets") ?: org.json.JSONArray()
                val apkUrl = pickApk(assets, version) ?: continue
                if (compareVersions(version, BuildConfig.VERSION_NAME) > 0) {
                    return@withContext UpdateInfo(
                        version = version,
                        apkUrl = apkUrl,
                        notes = release.optString("body", ""),
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** First dotted number in [text] ("OllamaChat v1.3.0" -> "1.3.0"), or null. */
    fun extractVersion(text: String): String? =
        Regex("""\d+(?:\.\d+)+""").find(text)?.value

    /**
     * Picks the APK to install. A rolling release can still carry assets from
     * an older build, so an asset naming the new version wins, then the
     * canonical name, and only then the first one found.
     */
    fun pickApk(assets: org.json.JSONArray, version: String): String? {
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
