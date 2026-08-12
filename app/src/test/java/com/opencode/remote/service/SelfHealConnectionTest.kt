package com.opencode.remote.service

import com.opencode.remote.data.api.dto.ServerInfo
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.data.datastore.ServerManager
import com.opencode.remote.data.repository.OConnectorRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SelfHealConnectionTest {

    private val serverManager = mockk<ServerManager>(relaxed = true)
    private val repository = mockk<OConnectorRepository>(relaxed = true)
    private val heal = SelfHealConnection(serverManager, repository)

    private val server = ServerInfo(
        id = "sv1",
        name = "Home",
        host = "192.168.1.9",
        port = 4096,
        username = "admin",
        useTls = true,
        insecureTrust = true,
    )

    @Test
    fun `no-op when already connected`() = runTest {
        every { repository.isConnected } returns true

        val result = heal.heal()

        assertTrue(result)
        verify(exactly = 0) { repository.connect(any()) }
    }

    @Test
    fun `heals from last active server with stored password`() = runTest {
        every { repository.isConnected } returns false
        every { serverManager.lastActiveServerId } returns flowOf("sv1")
        every { serverManager.servers } returns flowOf(listOf(server))
        every { serverManager.getPassword("sv1") } returns "secret"

        val result = heal.heal()

        assertTrue(result)
        val config = slot<ConnectionConfig>()
        verify { repository.connect(capture(config)) }
        assertEquals("192.168.1.9", config.captured.host)
        assertEquals(4096, config.captured.port)
        assertEquals("secret", config.captured.password)
        assertEquals(true, config.captured.useTls)
        assertEquals(true, config.captured.insecureTrust)
        assertEquals("sv1", config.captured.serverId)
        verify { repository.setServerName("Home") }
    }

    @Test
    fun `returns false when no last active server`() = runTest {
        every { repository.isConnected } returns false
        every { serverManager.lastActiveServerId } returns flowOf(null)

        val result = heal.heal()

        assertFalse(result)
        verify(exactly = 0) { repository.connect(any()) }
    }

    @Test
    fun `returns false when saved server no longer exists`() = runTest {
        every { repository.isConnected } returns false
        every { serverManager.lastActiveServerId } returns flowOf("sv1")
        every { serverManager.servers } returns flowOf(emptyList())

        val result = heal.heal()

        assertFalse(result)
        verify(exactly = 0) { repository.connect(any()) }
    }
}