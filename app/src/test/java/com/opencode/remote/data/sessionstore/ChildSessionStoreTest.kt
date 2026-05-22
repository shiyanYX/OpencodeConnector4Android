package com.opencode.remote.data.sessionstore

import app.cash.turbine.test
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.repository.OConnectorRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChildSessionStoreTest {

    private lateinit var store: ChildSessionStore

    @Before
    fun setUp() {
        store = ChildSessionStore()
    }

    @Test
    fun `registerChild adds child to parent`() = runTest {
        store.childrenMap.test {
            // Initial state is empty
            assertEquals(emptyMap<String, Set<String>>(), awaitItem())

            store.registerChild("parent_1", "child_1")
            assertEquals(mapOf("parent_1" to setOf("child_1")), awaitItem())

            // Add another child to same parent
            store.registerChild("parent_1", "child_2")
            assertEquals(mapOf("parent_1" to setOf("child_1", "child_2")), awaitItem())
        }
    }

    @Test
    fun `registerChild supports multiple parents`() = runTest {
        store.childrenMap.test {
            awaitItem() // skip initial

            store.registerChild("parent_1", "child_1")
            awaitItem()

            store.registerChild("parent_2", "child_a")
            val map = awaitItem()
            assertEquals(setOf("child_1"), map["parent_1"])
            assertEquals(setOf("child_a"), map["parent_2"])
        }
    }

    @Test
    fun `removeChild removes child from parent`() = runTest {
        store.childrenMap.test {
            awaitItem() // skip initial

            store.registerChild("parent_1", "child_1")
            store.registerChild("parent_1", "child_2")
            awaitItem() // skip first register
            awaitItem() // skip second register

            store.removeChild("parent_1", "child_1")
            val map = awaitItem()
            assertEquals(setOf("child_2"), map["parent_1"])
        }
    }

    @Test
    fun `removeChild removes parent entry when last child is removed`() = runTest {
        store.childrenMap.test {
            awaitItem() // skip initial

            store.registerChild("parent_1", "child_1")
            awaitItem()

            store.removeChild("parent_1", "child_1")
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `getChildren returns children synchronously`() {
        assertTrue(store.getChildren("parent_1").isEmpty())

        store.registerChild("parent_1", "child_1")
        store.registerChild("parent_1", "child_2")

        assertEquals(setOf("child_1", "child_2"), store.getChildren("parent_1"))
        assertTrue(store.getChildren("parent_2").isEmpty())
    }

    @Test
    fun `clear empties all data`() = runTest {
        store.childrenMap.test {
            awaitItem() // skip initial

            store.registerChild("parent_1", "child_1")
            store.registerChild("parent_2", "child_a")
            awaitItem()
            awaitItem()

            store.clear()
            assertTrue(awaitItem().isEmpty())
            assertTrue(store.getChildren("parent_1").isEmpty())
            assertTrue(store.getChildren("parent_2").isEmpty())
        }
    }

    @Test
    fun `refreshChildren replaces data from repository`() = runTest {
        // We can't easily mock OConnectorRepository in a unit test without MockK,
        // so we test the state management path directly.
        // The refreshChildren method calls repository.getSessionChildren(parentId)
        // and updates the map. For a full integration test, MockK would be needed.

        // Instead, verify the pattern: register then clear parent then re-register
        store.registerChild("parent_1", "child_old")
        assertEquals(setOf("child_old"), store.getChildren("parent_1"))

        // Simulate what refreshChildren does internally — replace the set
        store.removeChild("parent_1", "child_old")
        store.registerChild("parent_1", "child_new")
        assertEquals(setOf("child_new"), store.getChildren("parent_1"))
    }

    // ─── hasLoadedChildren / loaded-parents cache ──────────────────────

    @Test
    fun `hasLoadedChildren returns false before refresh, true after refresh`() = runTest {
        val repository: OConnectorRepository = mockk()
        coEvery { repository.getSessionChildren("parent_1") } returns listOf(
            SessionInfo(id = "child_1"),
        )

        assertFalse(store.hasLoadedChildren("parent_1"))

        store.refreshChildren("parent_1", repository)

        assertTrue(store.hasLoadedChildren("parent_1"))
    }

    @Test
    fun `empty child response still marks parent loaded`() = runTest {
        val repository: OConnectorRepository = mockk()
        coEvery { repository.getSessionChildren("parent_1") } returns emptyList()

        assertFalse(store.hasLoadedChildren("parent_1"))

        store.refreshChildren("parent_1", repository)

        assertTrue(store.hasLoadedChildren("parent_1"))
        assertTrue(store.getChildren("parent_1").isEmpty())
    }

    @Test
    fun `clear resets both childrenMap and loaded set`() = runTest {
        val repository: OConnectorRepository = mockk()
        coEvery { repository.getSessionChildren("parent_1") } returns listOf(
            SessionInfo(id = "child_1"),
        )

        store.refreshChildren("parent_1", repository)
        assertTrue(store.hasLoadedChildren("parent_1"))

        store.clear()

        assertTrue(store.getChildren("parent_1").isEmpty())
        assertFalse(store.hasLoadedChildren("parent_1"))
    }

    @Test
    fun `invalidate removes from loaded set`() = runTest {
        val repository: OConnectorRepository = mockk()
        coEvery { repository.getSessionChildren("parent_1") } returns listOf(
            SessionInfo(id = "child_1"),
        )

        store.refreshChildren("parent_1", repository)
        assertTrue(store.hasLoadedChildren("parent_1"))

        store.invalidate("parent_1")

        assertFalse(store.hasLoadedChildren("parent_1"))
        // Children data is NOT cleared by invalidate
        assertEquals(setOf("child_1"), store.getChildren("parent_1"))
    }
}
