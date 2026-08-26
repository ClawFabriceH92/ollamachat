package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.update.UpdateChecker
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
    fun `pickApk ignores non-apk assets and empty releases`() {
        val assets = JSONArray("""[{"name":"notes.txt","browser_download_url":"https://x/n.txt"}]""")
        assertNull(UpdateChecker.pickApk(assets, "1.3.0"))
        assertNull(UpdateChecker.pickApk(JSONArray("[]"), "1.3.0"))
    }
}
