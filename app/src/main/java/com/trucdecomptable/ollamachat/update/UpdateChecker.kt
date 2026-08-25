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
                val tag = release.optString("tag_name", "")
                val assets = release.optJSONArray("assets") ?: org.json.JSONArray()
                var apkUrl: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.optString("name", "").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl == null) continue
                val version = tag.removePrefix("v")
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
