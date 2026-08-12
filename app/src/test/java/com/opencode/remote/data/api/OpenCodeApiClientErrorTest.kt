package com.opencode.remote.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Server negative path: the opencode server rejects a send with 4xx and a
 * human-readable body (e.g. quota gate "Free usage exceeded, subscribe to Go").
 * The API client must surface that message instead of a silent fire-and-forget.
 */
class OpenCodeApiClientErrorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun apiClient(engine: MockEngine) = OConnectorApiClient(json, engine)

    @Test
    fun `sendMessage throws with server error body on 429`() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = """{"error":"Free usage exceeded, subscribe to Go"}""",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = apiClient(engine)
        client.configure("http://192.168.1.1:4096", "user")

        val e = runCatching { client.sendMessage("ses_1", "hello") }.exceptionOrNull()
        assertTrue("expected IOException", e is IOException)
        assertTrue("expect server body in message", e!!.message!!.contains("Free usage exceeded"))
    }

    @Test
    fun `sendMessage throws on 403 with plain text body`() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = "Forbidden: usage limit reached",
                status = HttpStatusCode.Forbidden,
            )
        }
        val client = apiClient(engine)
        client.configure("http://192.168.1.1:4096", "user")

        val e = runCatching { client.sendMessage("ses_1", "hello") }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.message!!.contains("usage limit reached"))
    }

    @Test
    fun `sendMessage succeeds on 204`() = runTest {
        val engine = MockEngine { request ->
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val client = apiClient(engine)
        client.configure("http://192.168.1.1:4096", "user")

        // Must not throw
        client.sendMessage("ses_1", "hello")
    }
}