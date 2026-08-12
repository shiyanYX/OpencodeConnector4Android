package com.opencode.remote.data.repository

import android.content.Context
import com.opencode.remote.data.api.OConnectorApiClient
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.network.NetworkMonitor
import com.opencode.remote.service.SseForegroundService
import io.mockk.clearAllMocks
import io.mockk.mockkStatic
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Keep-alive gating: with the power-saving preference OFF the repository must
 * NOT start the foreground service or the network monitor; toggling back ON
 * restores them for a live connection.
 */
class KeepAliveGatingTest {

    private lateinit var apiClient: OConnectorApiClient
    private lateinit var sseClient: OConnectorSseClient
    private lateinit var context: Context
    private lateinit var json: Json
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var messageCache: com.opencode.remote.data.cache.MessageCache
    private lateinit var preferences: ConnectionPreferences
    private lateinit var repository: OConnectorRepositoryImpl

    @Before
    fun setUp() {
        apiClient = mockk(relaxed = true)
        sseClient = mockk(relaxed = true)
        context = mockk(relaxed = true)
        json = Json { ignoreUnknownKeys = true }
        networkMonitor = mockk(relaxed = true)
        messageCache = mockk(relaxed = true)
        preferences = mockk(relaxed = true)

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any(), any(), any<Throwable>()) } returns 0

        mockkObject(SseForegroundService)
        every { SseForegroundService.start(any(), any()) } returns Unit
        every { SseForegroundService.stop(any()) } returns Unit
        every { SseForegroundService.restart(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun connectedRepository(): OConnectorRepositoryImpl {
        repository = OConnectorRepositoryImpl(apiClient, sseClient, context, json, networkMonitor, messageCache, preferences)
        repository.connect(ConnectionConfig("192.168.1.1", 4096, "user", "pass"))
        return repository
    }

    @Test
    fun `keep alive on starts foreground service`() {
        every { preferences.keepAlive } returns flowOf(true)
        connectedRepository().startSseService()
        verify { SseForegroundService.start(any(), any()) }
    }

    @Test
    fun `keep alive off skips foreground service`() {
        every { preferences.keepAlive } returns flowOf(false)
        connectedRepository().startSseService()
        verify(exactly = 0) { SseForegroundService.start(any(), any()) }
    }

    @Test
    fun `toggling off stops the service and monitor`() {
        every { preferences.keepAlive } returns flowOf(true)
        val repo = connectedRepository()
        repo.setKeepAliveEnabled(false)
        verify { SseForegroundService.stop(any()) }
        verify { networkMonitor.stop() }
    }

    @Test
    fun `toggling back on restores service for a live connection`() {
        every { preferences.keepAlive } returns flowOf(true)
        val repo = connectedRepository()
        repo.setKeepAliveEnabled(false)
        clearAllMocks()
        every { SseForegroundService.start(any(), any()) } returns Unit
        every { SseForegroundService.stop(any()) } returns Unit
        repo.setKeepAliveEnabled(true)
        verify { SseForegroundService.start(any(), any()) }
    }
}
