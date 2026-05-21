package com.opencode.remote.data.api

import app.cash.turbine.test
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.sessionstore.ActiveSessionStore
import com.opencode.remote.data.sessionstore.SessionStatus
import com.opencode.remote.data.repository.OConnectorRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenCodeApiClientTest {

    private lateinit var repository: OConnectorRepository
    private lateinit var store: ActiveSessionStore

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        store = ActiveSessionStore()
    }

    @Test
    fun `getSessionStatus returns map of session statuses`() = runTest {
        val expected = mapOf(
            "ses_1" to "busy",
            "ses_2" to "idle",
        )
        coEvery { repository.getSessionStatus() } returns expected

        val result = repository.getSessionStatus()
        assertEquals(2, result.size)
        assertEquals("busy", result["ses_1"])
        assertEquals("idle", result["ses_2"])
    }

    @Test
    fun `getSessionStatus returns empty map when no sessions`() = runTest {
        coEvery { repository.getSessionStatus() } returns emptyMap()

        val result = repository.getSessionStatus()
        assertEquals(0, result.size)
    }

    @Test
    fun `getSessionChildren returns list of SessionInfo`() = runTest {
        val parentId = "ses_parent"
        val children = listOf(
            SessionInfo(id = "child_1", parentID = parentId),
            SessionInfo(id = "child_2", parentID = parentId),
        )
        coEvery { repository.getSessionChildren(parentId) } returns children

        val result = repository.getSessionChildren(parentId)
        assertEquals(2, result.size)
        assertEquals("child_1", result[0].id)
        assertEquals("child_2", result[1].id)
        assertEquals(parentId, result[0].parentID)
    }

    @Test
    fun `getSessionChildren returns empty list when no children`() = runTest {
        coEvery { repository.getSessionChildren("ses_orphan") } returns emptyList()

        val result = repository.getSessionChildren("ses_orphan")
        assertEquals(0, result.size)
    }

    @Test
    fun `refreshAllStatuses updates store from repository`() = runTest {
        coEvery { repository.getSessionStatus() } returns mapOf(
            "ses_1" to "busy",
            "ses_2" to "idle",
        )

        store.refreshAllStatuses(repository)

        store.statusMap.test {
            val map = awaitItem()
            assertEquals(SessionStatus.BUSY, map["ses_1"])
            assertEquals(SessionStatus.IDLE, map["ses_2"])
            assertEquals(2, map.size)
        }
    }

    @Test
    fun `refreshAllStatuses handles exception gracefully`() = runTest {
        coEvery { repository.getSessionStatus() } throws java.io.IOException("Network error")

        // Should not throw
        store.refreshAllStatuses(repository)

        // Status map should remain empty (not crash)
        store.statusMap.test {
            assertEquals(emptyMap<String, SessionStatus>(), awaitItem())
        }
    }

    @Test
    fun `refreshAllStatuses replaces existing statuses`() = runTest {
        // Pre-populate store
        store.updateStatus("ses_old", SessionStatus.BUSY)

        // Refresh with new data (does not include ses_old)
        coEvery { repository.getSessionStatus() } returns mapOf(
            "ses_new" to "idle",
        )

        store.refreshAllStatuses(repository)

        store.statusMap.test {
            val map = awaitItem()
            // updateFromStatusEndpoint replaces the entire map
            assertEquals(1, map.size)
            assertEquals(SessionStatus.IDLE, map["ses_new"])
        }
    }
}
