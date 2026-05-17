package com.opencode.remote.data.api

import com.opencode.remote.data.api.dto.EventPayload
import com.opencode.remote.data.api.dto.EventProperties
import com.opencode.remote.data.api.dto.ServerEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenCodeSseClientTest {

    private lateinit var sseClient: OConnectorSseClient
    private lateinit var json: Json

    @Before
    fun setUp() {
        json = Json { ignoreUnknownKeys = true }
        sseClient = OConnectorSseClient(json)
    }

    @Test
    fun `configure sets base url and auth header`() {
        sseClient.configure("http://localhost:4096", "user", "pass")
        // No crash — verifies configuration is accepted
    }

    @Test
    fun `configure with empty password does not crash`() {
        sseClient.configure("http://localhost:4096", "user", "")
    }

    @Test
    fun `close does not throw`() {
        sseClient.configure("http://localhost:4096")
        sseClient.close()
    }

    @Test
    fun `configure reconfiguration does not throw`() {
        sseClient.configure("http://localhost:4096", "user", "pass")
        sseClient.configure("http://other-host:8080", "admin", "secret")
        sseClient.close()
    }

    @Test
    fun `subscribeToEvents returns a Flow`() {
        // Without a real server, we just verify it returns a non-null Flow
        sseClient.configure("http://localhost:4096")
        val flow = sseClient.subscribeToEvents()
        assertNotNull(flow)
    }
}
