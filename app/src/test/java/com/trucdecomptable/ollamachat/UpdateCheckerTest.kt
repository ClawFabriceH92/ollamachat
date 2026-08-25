package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.update.UpdateChecker
import org.junit.Assert.assertEquals
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
