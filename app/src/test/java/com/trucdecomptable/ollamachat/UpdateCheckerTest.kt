package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.update.UpdateChecker
import com.trucdecomptable.ollamachat.update.UpdateSchedule
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `compareVersions handles simple bumps`() {
        assertTrue(UpdateChecker.compareVersions("1.1", "1.0") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0", "1.1") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0"))
    }

    @Test
    fun `compareVersions handles multi-segment`() {
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(UpdateChecker.compareVersions("0.10.0", "0.9.9") > 0)
    }

    @Test
    fun `compareVersions handles missing segments`() {
        assertTrue(UpdateChecker.compareVersions("1.1", "1.0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0", "1") == 0)
    }
}

class UpdateCheckerVersionTest {

    @Test
    fun `extractVersion reads the version from a release title`() {
        assertEquals("1.3.0", UpdateChecker.extractVersion("OllamaChat v1.3.0"))
        assertEquals("1.3.0", UpdateChecker.extractVersion("v1.3.0"))
        assertEquals("2.0", UpdateChecker.extractVersion("Build 2.0"))
        // "latest" is the rolling tag: no version in it, so the title is used.
        assertNull(UpdateChecker.extractVersion("latest"))
        assertNull(UpdateChecker.extractVersion(""))
    }

    @Test
    fun `pickApk prefers the asset naming the new version`() {
        val assets = JSONArray(
            """
            [
              {"name":"ollamachat-v1.2.2.apk","browser_download_url":"https://x/old.apk"},
              {"name":"app-release.apk","browser_download_url":"https://x/new.apk"}
            ]
            """
        )
        assertEquals("https://x/new.apk", UpdateChecker.pickApk(assets, "1.3.0"))
        assertEquals("https://x/old.apk", UpdateChecker.pickApk(assets, "1.2.2"))
    }

    @Test
    fun `a newer release is only newer than what is installed`() {
        // The rolling tag is not a version; the title is.
        val version = UpdateChecker.extractVersion("OllamaChat v1.6.0")!!
        assertTrue(UpdateChecker.compareVersions(version, "1.5.0") > 0)
        assertTrue(UpdateChecker.compareVersions(version, "1.6.0") == 0)
        assertTrue(UpdateChecker.compareVersions(version, "1.7.0") < 0)
    }

    @Test
    fun `pickApk ignores non-apk assets and empty releases`() {
        val assets = JSONArray("""[{"name":"notes.txt","browser_download_url":"https://x/n.txt"}]""")
        assertNull(UpdateChecker.pickApk(assets, "1.3.0"))
        assertNull(UpdateChecker.pickApk(JSONArray("[]"), "1.3.0"))
    }
}

/**
 * The distinction that was missing: a check that could not be made is not a
 * check that found nothing. Folding the two together is what made the app
 * answer "you are already up to date" while GitHub was unreachable.
 */
class UpdateCheckerOutcomeTest {

    private val manifest = """{"version":"1.6.0","notes":"des notes","apk":"https://x/new.apk"}"""

    @Test
    fun `a newer manifest is an available update`() {
        val result = UpdateChecker.parseManifest(manifest, installed = "1.5.2")
        assertTrue(result is UpdateChecker.CheckResult.Available)
        val info = (result as UpdateChecker.CheckResult.Available).info
        assertEquals("1.6.0", info.version)
        assertEquals("https://x/new.apk", info.apkUrl)
        assertEquals("des notes", info.notes)
    }

    @Test
    fun `the same or an older manifest is up to date`() {
        assertEquals(
            UpdateChecker.CheckResult.UpToDate,
            UpdateChecker.parseManifest(manifest, installed = "1.6.0"),
        )
        assertEquals(
            UpdateChecker.CheckResult.UpToDate,
            UpdateChecker.parseManifest(manifest, installed = "1.7.0"),
        )
    }

    @Test
    fun `a manifest without an apk url falls back to the canonical asset`() {
        val result = UpdateChecker.parseManifest("""{"version":"1.6.0"}""", installed = "1.5.2")
        assertEquals(
            UpdateChecker.APK_URL,
            (result as UpdateChecker.CheckResult.Available).info.apkUrl,
        )
    }

    @Test
    fun `an unreadable manifest is not a verdict`() {
        // Null means "ask elsewhere", which is what lets the API stay a
        // fallback. Returning UpToDate here would be the old bug again.
        assertNull(UpdateChecker.parseManifest("<html>404</html>", installed = "1.5.2"))
        assertNull(UpdateChecker.parseManifest("", installed = "1.5.2"))
        assertNull(UpdateChecker.parseManifest("""{"version":"latest"}""", installed = "1.5.2"))
    }

    @Test
    fun `the api still answers for releases published before the manifest`() {
        val body = """
            [
              {"tag_name":"latest","name":"OllamaChat v1.6.0","body":"notes",
               "assets":[{"name":"app-release.apk","browser_download_url":"https://x/new.apk"}]}
            ]
        """.trimIndent()
        val result = UpdateChecker.parseApi(body, installed = "1.5.2")
        assertEquals("1.6.0", (result as UpdateChecker.CheckResult.Available).info.version)
        assertEquals(UpdateChecker.CheckResult.UpToDate, UpdateChecker.parseApi(body, "1.6.0"))
    }

    @Test
    fun `a draft release is never offered`() {
        val body = """
            [
              {"tag_name":"v9.0.0","draft":true,
               "assets":[{"name":"app-release.apk","browser_download_url":"https://x/d.apk"}]}
            ]
        """.trimIndent()
        assertEquals(UpdateChecker.CheckResult.UpToDate, UpdateChecker.parseApi(body, "1.5.2"))
    }

    @Test
    fun `an unreadable api body is not a verdict either`() {
        assertNull(UpdateChecker.parseApi("not json", installed = "1.5.2"))
    }
}

/**
 * The reasons the user reads. A bare "HTTP 403" sent them looking at the app
 * when the cause was the exit address they were sharing with strangers.
 */
class UpdateCheckerReasonTest {

    @Test
    fun `an exhausted quota names the shared exit address`() {
        val reason = UpdateChecker.httpReason(403, rateLimitRemaining = "0")
        assertTrue(reason, reason.contains("quota"))
        assertTrue(reason, reason.contains("VPN"))
    }

    @Test
    fun `other http codes stay plain`() {
        assertTrue(UpdateChecker.httpReason(403, "57").contains("403"))
        assertTrue(UpdateChecker.httpReason(404, null).contains("404"))
        assertTrue(UpdateChecker.httpReason(500, null).contains("500"))
    }

    @Test
    fun `network failures are described, not class-named`() {
        assertTrue(
            UpdateChecker.describe(java.net.UnknownHostException("api.github.com"))
                .contains("DNS")
        )
        assertTrue(
            UpdateChecker.describe(java.net.SocketTimeoutException()).contains("délai")
        )
        assertTrue(
            UpdateChecker.describe(java.net.ConnectException("refused")).contains("connexion")
        )
        assertTrue(
            UpdateChecker.describe(java.io.IOException("HTTP 502")).contains("502")
        )
        // An IOException with no message must still say something.
        assertTrue(UpdateChecker.describe(java.io.IOException()).isNotBlank())
    }
}

/** The hourly throttle must not be held by a check that never landed. */
class UpdateScheduleTest {

    private val hour = 60 * 60_000L

    @Test
    fun `a manual check is always due`() {
        assertTrue(UpdateSchedule.isDue(manual = true, lastCheckAt = 1_000L, now = 1_001L))
    }

    @Test
    fun `an automatic check waits an hour after a check that landed`() {
        assertTrue(UpdateSchedule.isDue(manual = false, lastCheckAt = 0L, now = hour))
        assertEquals(
            false,
            UpdateSchedule.isDue(manual = false, lastCheckAt = hour, now = hour + 1),
        )
        assertTrue(UpdateSchedule.isDue(manual = false, lastCheckAt = hour, now = 2 * hour))
    }

    @Test
    fun `a failed check releases the slot immediately`() {
        // The manager clears the timestamp when the check is Unreachable: the
        // tunnel that blocked it is often gone minutes later, and the next
        // launch has to be allowed to try instead of staying mute for an hour.
        // Asserted with a small clock on purpose — that is what caught the
        // release working only by accident of the epoch being far from zero.
        assertTrue(
            UpdateSchedule.isDue(manual = false, lastCheckAt = UpdateSchedule.NEVER, now = 1L)
        )
        // And with the wall clock the manager actually passes.
        assertTrue(
            UpdateSchedule.isDue(
                manual = false,
                lastCheckAt = UpdateSchedule.NEVER,
                now = 1_760_000_000_000L,
            )
        )
    }
}
