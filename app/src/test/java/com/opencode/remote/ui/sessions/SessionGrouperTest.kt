package com.opencode.remote.ui.sessions

import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.api.dto.SessionTime
import com.opencode.remote.ui.util.TimeGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SessionGrouperTest {

    // ── Helpers ──────────────────────────────────────────────────────

    /** Create a SessionInfo with a specific updated timestamp. */
    private fun session(id: String, updatedMs: Long): SessionInfo {
        return SessionInfo(
            id = id,
            time = SessionTime(updated = updatedMs),
        )
    }

    /** Epoch millis for the start of a given calendar day offset from today. */
    private fun dayOffsetMs(daysAgo: Long): Long {
        return LocalDate.now(ZoneId.systemDefault())
            .minusDays(daysAgo)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    // ── Tests ───────────────────────────────────────────────────────

    @Test
    fun `empty list returns empty map`() {
        val result = groupSessionsByTime(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single session today produces one TODAY group`() {
        val now = System.currentTimeMillis()
        val s = session("s1", now)

        val result = groupSessionsByTime(listOf(s))

        assertEquals(1, result.size)
        assertEquals(listOf(s), result[TimeGroup.TODAY])
    }

    @Test
    fun `sessions today and yesterday produce two groups`() {
        val todayMs = dayOffsetMs(0) + 12 * 3600_000L  // noon today
        val yesterdayMs = dayOffsetMs(1) + 12 * 3600_000L

        val today1 = session("t1", todayMs)
        val today2 = session("t2", todayMs - 3600_000L)
        val yesterday1 = session("y1", yesterdayMs)

        val result = groupSessionsByTime(listOf(today1, today2, yesterday1))

        assertEquals(2, result.size)
        assertEquals(listOf(today1, today2), result[TimeGroup.TODAY])
        assertEquals(listOf(yesterday1), result[TimeGroup.YESTERDAY])
    }

    @Test
    fun `five sessions across three time groups`() {
        val todayMs = dayOffsetMs(0) + 12 * 3600_000L
        val yesterdayMs = dayOffsetMs(1) + 12 * 3600_000L
        val olderMs = dayOffsetMs(8) + 12 * 3600_000L  // 8 days ago → OLDER

        val today1 = session("t1", todayMs)
        val today2 = session("t2", todayMs - 3600_000L)
        val yesterday1 = session("y1", yesterdayMs)
        val yesterday2 = session("y2", yesterdayMs - 3600_000L)
        val older1 = session("o1", olderMs)

        val result = groupSessionsByTime(
            listOf(today1, today2, yesterday1, yesterday2, older1)
        )

        assertEquals(3, result.size)
        assertEquals(listOf(today1, today2), result[TimeGroup.TODAY])
        assertEquals(listOf(yesterday1, yesterday2), result[TimeGroup.YESTERDAY])
        assertEquals(listOf(older1), result[TimeGroup.OLDER])
    }

    @Test
    fun `sessions within each group sorted by updated descending`() {
        val todayMs = dayOffsetMs(0) + 12 * 3600_000L

        val older = session("older", todayMs - 1000L)
        val newer = session("newer", todayMs)
        val oldest = session("oldest", todayMs - 5000L)

        val result = groupSessionsByTime(listOf(oldest, newer, older))

        val todayGroup = result[TimeGroup.TODAY]!!
        assertEquals("newer", todayGroup[0].id)
        assertEquals("older", todayGroup[1].id)
        assertEquals("oldest", todayGroup[2].id)
    }

    @Test
    fun `all sessions same day produce single TODAY group`() {
        val todayMs = dayOffsetMs(0) + 12 * 3600_000L

        val s1 = session("s1", todayMs)
        val s2 = session("s2", todayMs - 1000L)
        val s3 = session("s3", todayMs - 2000L)

        val result = groupSessionsByTime(listOf(s1, s2, s3))

        assertEquals(1, result.size)
        assertTrue(result.containsKey(TimeGroup.TODAY))
        assertEquals(3, result[TimeGroup.TODAY]!!.size)
    }

    @Test
    fun `session with null updated timestamp treated as epoch zero classified as OLDER`() {
        val s = SessionInfo(id = "null-time", time = null)

        val result = groupSessionsByTime(listOf(s))

        assertEquals(1, result.size)
        assertEquals(listOf(s), result[TimeGroup.OLDER])
    }

    @Test
    fun `session with zero updated timestamp classified as OLDER`() {
        val s = session("zero-time", 0L)

        val result = groupSessionsByTime(listOf(s))

        assertEquals(1, result.size)
        assertEquals(listOf(s), result[TimeGroup.OLDER])
    }

    @Test
    fun `sessions across all four time groups`() {
        val todayMs = dayOffsetMs(0) + 12 * 3600_000L
        val yesterdayMs = dayOffsetMs(1) + 12 * 3600_000L
        val thisWeekMs = dayOffsetMs(4) + 12 * 3600_000L   // 4 days ago → THIS_WEEK
        val olderMs = dayOffsetMs(10) + 12 * 3600_000L     // 10 days ago → OLDER

        val today = session("today", todayMs)
        val yesterday = session("yesterday", yesterdayMs)
        val thisWeek = session("thisWeek", thisWeekMs)
        val older = session("older", olderMs)

        val result = groupSessionsByTime(listOf(today, yesterday, thisWeek, older))

        assertEquals(4, result.size)
        assertEquals(listOf(today), result[TimeGroup.TODAY])
        assertEquals(listOf(yesterday), result[TimeGroup.YESTERDAY])
        assertEquals(listOf(thisWeek), result[TimeGroup.THIS_WEEK])
        assertEquals(listOf(older), result[TimeGroup.OLDER])
    }
}
