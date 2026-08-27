package com.trucdecomptable.ollamachat.data.ollama

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/** What the connection dot shows. */
enum class ConnectionStatus {
    /** Nothing probed yet — the very first check is still running. */
    UNKNOWN,
    ONLINE,
    OFFLINE,
}

/**
 * Keeps a live answer to "is the Ollama server reachable?".
 *
 * Probing is driven by whoever collects [status]: nothing is polled while no
 * screen is watching the dot, so a backgrounded app does not ping the server
 * every few seconds for nobody.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitor(
    private val baseUrl: Flow<String>,
    private val client: OllamaClient,
    scope: CoroutineScope,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    /** A tap on the dot must not wait for the next tick. */
    private val pokes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Verdicts from real traffic, which beat the next scheduled probe. */
    private val reports = MutableSharedFlow<ConnectionStatus>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val status: StateFlow<ConnectionStatus> =
        merge(baseUrl.flatMapLatest { url -> probes(url) }, reports)
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConnectionStatus.UNKNOWN)

    /** Probes now instead of waiting for the next tick. */
    fun refresh() {
        pokes.tryEmit(Unit)
    }

    /**
     * Records what actual traffic just observed. A send that could not reach
     * the server is a stronger signal than the next probe, and it lands sooner.
     */
    fun report(reachable: Boolean) {
        reports.tryEmit(if (reachable) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE)
    }

    private fun probes(url: String): Flow<ConnectionStatus> = flow {
        // No address configured is not a network problem, but from the dot's
        // point of view the server is just as unreachable.
        if (url.isBlank()) {
            emit(ConnectionStatus.OFFLINE)
            return@flow
        }
        while (true) {
            emit(
                if (client.testConnection(url).isSuccess) ConnectionStatus.ONLINE
                else ConnectionStatus.OFFLINE
            )
            // Wakes early when the user taps the dot.
            withTimeoutOrNull(pollIntervalMs) { pokes.first() }
        }
    }

    companion object {
        /** Often enough to notice a server coming back, rare enough to ignore. */
        const val POLL_INTERVAL_MS = 20_000L

        /** Survives a rotation without restarting the probe loop. */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
