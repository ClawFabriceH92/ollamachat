package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.ollama.ConnectionMonitor
import com.trucdecomptable.ollamachat.data.ollama.ConnectionStatus
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The dot is only as good as what drives it: these cover the states it can
 * show and, above all, that nothing is probed while nobody is looking.
 *
 * Note for future edits: the probe loop never runs dry while a collector is
 * attached, so `advanceUntilIdle()` would spin forever here — advance a fixed
 * amount of virtual time, or run what is already due, instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitorTest {

    /** Answers whatever the test says, and counts how often it was asked. */
    private class FakeClient(private val reachable: Boolean = true) : OllamaClient() {
        val urls = mutableListOf<String>()
        val probes: Int get() = urls.size

        override suspend fun testConnection(baseUrl: String): Result<Unit> {
            urls.add(baseUrl)
            return if (reachable) Result.success(Unit) else Result.failure(IOException("boom"))
        }
    }

    @Test
    fun `reports online when the server answers`() = runTest {
        val client = FakeClient(reachable = true)
        val monitor = ConnectionMonitor(MutableStateFlow(URL), client, TestScope(testScheduler))

        val status = monitor.status.first { it != ConnectionStatus.UNKNOWN }

        assertEquals(ConnectionStatus.ONLINE, status)
        assertEquals(URL, client.urls.single())
    }

    @Test
    fun `reports offline when the server does not answer`() = runTest {
        val client = FakeClient(reachable = false)
        val monitor = ConnectionMonitor(MutableStateFlow(URL), client, TestScope(testScheduler))

        assertEquals(ConnectionStatus.OFFLINE, monitor.status.first { it != ConnectionStatus.UNKNOWN })
    }

    @Test
    fun `an unconfigured server counts as offline and is never probed`() = runTest {
        val client = FakeClient()
        val monitor = ConnectionMonitor(MutableStateFlow(""), client, TestScope(testScheduler))

        assertEquals(ConnectionStatus.OFFLINE, monitor.status.first { it != ConnectionStatus.UNKNOWN })
        assertEquals(0, client.probes)
    }

    @Test
    fun `nothing is probed while no screen is watching`() = runTest {
        val client = FakeClient()
        ConnectionMonitor(MutableStateFlow(URL), client, TestScope(testScheduler))

        advanceTimeBy(60_000)

        assertEquals(0, client.probes)
    }

    @Test
    fun `keeps polling while watched`() = runTest {
        val client = FakeClient()
        val monitor = ConnectionMonitor(
            MutableStateFlow(URL),
            client,
            TestScope(testScheduler),
            pollIntervalMs = 1_000,
        )

        val watcher = backgroundScope.launch { monitor.status.collect {} }
        advanceTimeBy(3_500)
        watcher.cancel()

        assertTrue("expected several probes, got ${client.probes}", client.probes >= 3)
    }

    @Test
    fun `a failed send reddens the dot before the next probe`() = runTest {
        val client = FakeClient(reachable = true)
        val monitor = ConnectionMonitor(
            MutableStateFlow(URL),
            client,
            TestScope(testScheduler),
            pollIntervalMs = 60_000,
        )

        val seen = mutableListOf<ConnectionStatus>()
        val watcher = backgroundScope.launch { monitor.status.collect { seen.add(it) } }
        runCurrent()
        monitor.report(reachable = false)
        runCurrent()
        watcher.cancel()

        assertEquals(ConnectionStatus.OFFLINE, seen.last())
        assertEquals("the report should not have cost an extra probe", 1, client.probes)
    }

    @Test
    fun `a new server address is probed right away`() = runTest {
        val client = FakeClient()
        val url = MutableStateFlow("http://first:11434")
        val monitor = ConnectionMonitor(url, client, TestScope(testScheduler), pollIntervalMs = 60_000)

        val watcher = backgroundScope.launch { monitor.status.collect {} }
        runCurrent()
        url.value = "http://second:11434"
        runCurrent()
        watcher.cancel()

        assertEquals(listOf("http://first:11434", "http://second:11434"), client.urls)
    }

    private companion object {
        const val URL = "http://host:11434"
    }
}
