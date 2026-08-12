package com.opencode.remote.data.datastore

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RecentSessionStoreTest {

    private lateinit var store: RecentSessionStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = RecentSessionStore(context)
        // The DataStore instance is shared per test class — start each test clean
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    private fun entry(
        serverId: String,
        sessionId: String,
        title: String = "Session $sessionId",
        openedAt: Long = 0L,
    ) = RecentSessionEntry(
        serverId = serverId,
        sessionId = sessionId,
        title = title,
        directory = "/proj",
        openedAt = openedAt,
    )

    @Test
    fun `empty store returns empty list`() = runTest {
        assertEquals(emptyList<RecentSessionEntry>(), store.entries.first())
        assertEquals(emptyList<RecentSessionEntry>(), store.observe("srv").first())
    }

    @Test
    fun `record then read returns the entry`() = runTest {
        store.record("srv", "s1", "Title", "/proj")
        val list = store.entries.first()
        assertEquals(1, list.size)
        assertEquals("s1", list[0].sessionId)
        assertEquals("Title", list[0].title)
        assertEquals("srv", list[0].serverId)
    }

    @Test
    fun `observe filters to the requested server`() = runTest {
        store.record("srv-a", "s1", "A", "/a")
        store.record("srv-b", "s2", "B", "/b")
        val forA = store.observe("srv-a").first()
        val forB = store.observe("srv-b").first()
        assertEquals(listOf("s1"), forA.map { it.sessionId })
        assertEquals(listOf("s2"), forB.map { it.sessionId })
    }

    @Test
    fun `re-opening the same session moves it to the top and keeps one entry`() = runTest {
        store.record("srv", "s1", "One", "/proj")
        store.record("srv", "s2", "Two", "/proj")
        // Re-open s1 — it must move to the top, not duplicate
        store.record("srv", "s1", "One", "/proj")
        val list = store.entries.first()
        assertEquals(2, list.size)
        assertEquals(listOf("s1", "s2"), list.map { it.sessionId })
    }

    @Test
    fun `list is capped at 10 newest entries`() = runTest {
        repeat(15) { i ->
            store.record("srv", "s%02d".format(i), "Session $i", "/proj")
        }
        val list = store.entries.first()
        assertEquals(10, list.size)
        // s14 recorded last -> newest first
        assertEquals("s14", list.first().sessionId)
        assertTrue(list.none { it.sessionId == "s00" || it.sessionId == "s04" })
    }

    @Test
    fun `cap respects per-server entries`() = runTest {
        repeat(15) { i ->
            store.record("srv-$i", "s$i", "S$i", "/proj")
        }
        // 15 different servers — the cap still applies globally
        assertEquals(10, store.entries.first().size)
    }

    @Test
    fun `remove deletes only the matching entry`() = runTest {
        store.record("srv", "s1", "One", "/proj")
        store.record("srv", "s2", "Two", "/proj")
        store.remove("srv", "s1")
        val list = store.entries.first()
        assertEquals(listOf("s2"), list.map { it.sessionId })
    }

    @Test
    fun `remove with wrong server id keeps the entry`() = runTest {
        store.record("srv", "s1", "One", "/proj")
        store.remove("other", "s1")
        assertEquals(listOf("s1"), store.entries.first().map { it.sessionId })
    }

    @Test
    fun `remove on empty store does not throw`() = runTest {
        store.remove("srv", "missing")
        assertEquals(emptyList<RecentSessionEntry>(), store.entries.first())
    }
}
