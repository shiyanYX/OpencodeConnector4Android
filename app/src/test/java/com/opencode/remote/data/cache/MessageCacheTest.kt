package com.opencode.remote.data.cache

import com.opencode.remote.data.api.dto.MessageInfo
import com.opencode.remote.data.api.dto.MessageInfoData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageCacheTest {

    private lateinit var cache: MessageCache

    @Before
    fun setUp() {
        cache = MessageCache(RuntimeEnvironment.getApplication())
        // Fresh state per test
        runTest { cache.clearAll() }
    }

    private fun msg(id: String, role: String = "user") = MessageInfo(
        info = MessageInfoData(id = id, role = role),
        parts = emptyList(),
    )

    @Test
    fun `load on empty cache returns null`() = runTest {
        assertNull(cache.load("s1"))
    }

    @Test
    fun `merge then load returns merged messages`() = runTest {
        cache.merge("s1", listOf(msg("a"), msg("b")))
        val loaded = cache.load("s1")
        assertEquals(listOf("a", "b"), loaded?.map { it.id })
    }

    @Test
    fun `merge dedupes by message id keeping newest`() = runTest {
        cache.merge("s1", listOf(msg("a"), msg("b")))
        cache.merge("s1", listOf(msg("b", role = "assistant"), msg("c")))
        val loaded = cache.load("s1")
        assertEquals(listOf("a", "b", "c"), loaded?.map { it.id })
        assertEquals("assistant", loaded?.get(1)?.role)
    }

    @Test
    fun `cache is capped at 500 messages`() = runTest {
        val first = (0 until 490).map { msg("m$it") }
        cache.merge("s1", first)
        val second = (490 until 520).map { msg("m$it") }
        cache.merge("s1", second)
        val loaded = cache.load("s1")
        assertEquals(500, loaded?.size)
        assertEquals("m20", loaded?.first()?.id) // oldest 20 rolled off
        assertEquals("m519", loaded?.last()?.id)
    }

    @Test
    fun `empty merge does not create a cache entry`() = runTest {
        cache.merge("s1", emptyList())
        assertNull(cache.load("s1"))
    }

    @Test
    fun `cache survives a new instance via disk`() = runTest {
        cache.merge("s1", listOf(msg("a"), msg("b")))
        val fresh = MessageCache(RuntimeEnvironment.getApplication())
        val loaded = fresh.load("s1")
        assertEquals(listOf("a", "b"), loaded?.map { it.id })
    }

    @Test
    fun `remove drops the session`() = runTest {
        cache.merge("s1", listOf(msg("a")))
        cache.merge("s2", listOf(msg("x")))
        cache.remove("s1")
        assertNull(cache.load("s1"))
        assertEquals(listOf("x"), cache.load("s2")?.map { it.id })
        // Disk copy gone too
        val fresh = MessageCache(RuntimeEnvironment.getApplication())
        assertNull(fresh.load("s1"))
    }

    @Test
    fun `clearAll drops everything`() = runTest {
        cache.merge("s1", listOf(msg("a")))
        cache.merge("s2", listOf(msg("x")))
        cache.clearAll()
        assertNull(cache.load("s1"))
        assertNull(cache.load("s2"))
    }

    @Test
    fun `sessions with special characters in id are isolated`() = runTest {
        cache.merge("proj/sess-1", listOf(msg("a")))
        cache.merge("proj/sess-2", listOf(msg("x")))
        assertEquals(listOf("a"), cache.load("proj/sess-1")?.map { it.id })
        assertEquals(listOf("x"), cache.load("proj/sess-2")?.map { it.id })
    }
}
