package com.opencode.remote.data.sessionstore

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ActiveSessionStoreTest {

    private lateinit var store: ActiveSessionStore

    @Before
    fun setUp() {
        store = ActiveSessionStore()
    }

    @Test
    fun `updateStatus emits updated map with session status`() = runTest {
        store.statusMap.test {
            // Initial state is empty
            assertEquals(emptyMap<String, SessionStatus>(), awaitItem())

            // Update session to BUSY
            store.updateStatus("ses_1", SessionStatus.BUSY)
            assertEquals(mapOf("ses_1" to SessionStatus.BUSY), awaitItem())

            // Update another session to IDLE
            store.updateStatus("ses_2", SessionStatus.IDLE)
            val map = awaitItem()
            assertEquals(SessionStatus.BUSY, map["ses_1"])
            assertEquals(SessionStatus.IDLE, map["ses_2"])
        }
    }

    @Test
    fun `updateStatus overwrites existing status for same session`() = runTest {
        store.statusMap.test {
            // Skip initial empty state
            awaitItem()

            store.updateStatus("ses_1", SessionStatus.BUSY)
            assertEquals(mapOf("ses_1" to SessionStatus.BUSY), awaitItem())

            // Overwrite same session
            store.updateStatus("ses_1", SessionStatus.IDLE)
            assertEquals(mapOf("ses_1" to SessionStatus.IDLE), awaitItem())
        }
    }

    @Test
    fun `updateFromStatusEndpoint parses string map and emits correct status`() = runTest {
        store.statusMap.test {
            // Skip initial empty state
            awaitItem()

            store.updateFromStatusEndpoint(
                mapOf(
                    "ses_1" to "busy",
                    "ses_2" to "idle",
                    "ses_3" to "unknown",
                )
            )
            val map = awaitItem()
            assertEquals(SessionStatus.BUSY, map["ses_1"])
            assertEquals(SessionStatus.IDLE, map["ses_2"])
            assertEquals(SessionStatus.IDLE, map["ses_3"])
            assertEquals(3, map.size)
        }
    }

    @Test
    fun `updateFromStatusEndpoint keeps sessions absent from the endpoint`() = runTest {
        store.statusMap.test {
            awaitItem()

            // SSE-fed status first
            store.updateStatus("ses_sse", SessionStatus.BUSY)
            awaitItem()

            // Endpoint omits ses_sse entirely (as the live server does)
            store.updateFromStatusEndpoint(mapOf("ses_other" to "idle"))
            val map = awaitItem()
            assertEquals(SessionStatus.BUSY, map["ses_sse"])
            assertEquals(SessionStatus.IDLE, map["ses_other"])
        }
    }

    @Test
    fun `updateFromStatusEndpoint with empty map keeps existing statuses`() = runTest {
        store.statusMap.test {
            awaitItem()

            store.updateStatus("ses_1", SessionStatus.BUSY)
            awaitItem()

            // Empty response (live server returns {}) must not wipe SSE-derived status
            store.updateFromStatusEndpoint(emptyMap())
            assertTrue(store.statusMap.value.containsKey("ses_1"))
            assertEquals(SessionStatus.BUSY, store.statusMap.value["ses_1"])
        }
    }

@Test
    fun `clear resets to empty map`() = runTest {
        store.statusMap.test {
            // Skip initial empty state
            awaitItem()

            store.updateStatus("ses_1", SessionStatus.BUSY)
            assertEquals(mapOf("ses_1" to SessionStatus.BUSY), awaitItem())

            store.clear()
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `removeSession deletes only the given session`() = runTest {
        store.statusMap.test {
            // Skip initial empty state
            awaitItem()

            store.updateStatus("ses_1", SessionStatus.BUSY)
            store.updateStatus("ses_2", SessionStatus.IDLE)
            awaitItem()
            awaitItem()

            store.removeSession("ses_1")
            val map = awaitItem()
            assertEquals(1, map.size)
            assertEquals(SessionStatus.IDLE, map["ses_2"])
        }
    }

    @Test
    fun `removeSession on unknown id leaves map unchanged`() = runTest {
        store.statusMap.test {
            awaitItem()

            store.updateStatus("ses_1", SessionStatus.BUSY)
            awaitItem()

            store.removeSession("ses_missing")
            assertEquals(1, store.statusMap.value.size)
        }
    }
}
