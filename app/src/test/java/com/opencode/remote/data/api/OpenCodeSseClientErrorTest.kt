package com.opencode.remote.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * SSE negative path: when opencode rejects the event stream (quota/auth), the
 * client must surface the rejection with the server's message instead of
 * silently reading the error JSON body as SSE.
 */
class OpenCodeSseClientErrorTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.d(any(), any<String>()) } returns 0
    }

    @Test
    fun `subscribeToEvents surfaces HTTP rejection with server body`() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"Free usage exceeded, subscribe to Go"}""",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OConnectorSseClient(json, engine)
        client.configure("http://192.168.1.1:4096", "user", autoReconnect = false)

        var caught: Exception? = null
        try {
            client.subscribeToEvents().first()
        } catch (e: Exception) {
            caught = e
        }
        assertTrue("expected IOException, got $caught", caught is IOException)
        // NOTE: subscribeToEvents rejects BEFORE the retry loop, so no 5s+30s delays.
        assertTrue("expect server body in message", caught!!.message!!.contains("Free usage exceeded"))
    }
}