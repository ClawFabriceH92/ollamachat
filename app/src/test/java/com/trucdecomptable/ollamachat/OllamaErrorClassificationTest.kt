package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.ollama.ChatErrorCode
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A tunnel that routes outbound traffic but has no return path produces a
 * connect timeout, not a slow answer. Reporting it as "the server is taking
 * too long" points the user at the model and its context size, which is the
 * one place the fault is not.
 */
class OllamaErrorClassificationTest {

    private val client = OllamaClient()
    private val target = "http://192.168.0.162:11434"

    @Test
    fun `a timeout before any response names the address it could not reach`() {
        val error = client.classifyCode(
            SocketTimeoutException("failed to connect after 10000ms"),
            target = target,
            responseStarted = false,
        )
        assertEquals(ChatErrorCode.CONNECT_TIMEOUT, error.code)
        assertEquals(target, error.detail)
    }

    @Test
    fun `a timeout once the server has answered is a slow server`() {
        val error = client.classifyCode(
            SocketTimeoutException("timeout"),
            target = target,
            responseStarted = true,
        )
        assertEquals(ChatErrorCode.TIMEOUT, error.code)
    }

    @Test
    fun `a refused connection and an unknown host stay connection errors`() {
        assertEquals(
            ChatErrorCode.CONNECTION,
            client.classifyCode(ConnectException("refused"), target, responseStarted = false).code,
        )
        assertEquals(
            ChatErrorCode.CONNECTION,
            client.classifyCode(UnknownHostException("nope"), target, responseStarted = false).code,
        )
    }

    @Test
    fun `anything else keeps its own message`() {
        val error = client.classifyCode(IOException("broken pipe"), target, responseStarted = true)
        assertEquals(ChatErrorCode.UNKNOWN, error.code)
        assertEquals("broken pipe", error.detail)
    }

    @Test
    fun `the default assumes the response started, so nothing is mislabelled`() {
        // Callers that cannot tell must not claim a connect failure they did
        // not observe; the vaguer code is the honest one.
        val error = client.classifyCode(SocketTimeoutException())
        assertEquals(ChatErrorCode.TIMEOUT, error.code)
        assertNull(error.detail)
    }
}
