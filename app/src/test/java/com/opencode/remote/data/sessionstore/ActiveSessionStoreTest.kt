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
}
