package com.opencode.remote.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class TimeFormatterTest {

    // ── formatRelativeTime tests ─────────────────────────────────────

    @Test
    fun nullTimestamp_returnsEmptyString() {
        assertEquals("", TimeFormatter.formatRelativeTime(null))
    }

    @Test
    fun justNow_lessThan60s_returnsJustNow() {
        val now = System.currentTimeMillis()
        val tenSecAgo = now - TimeUnit.SECONDS.toMillis(10)
        assertEquals("just now", TimeFormatter.formatRelativeTime(tenSecAgo))
    }

    @Test
    fun minutesAgo_lessThan60min_returnsMinAgo() {
        val now = System.currentTimeMillis()
        val fiveMinAgo = now - TimeUnit.MINUTES.toMillis(5)
        assertEquals("5 min ago", TimeFormatter.formatRelativeTime(fiveMinAgo))
    }

    @Test
    fun hoursAgo_sameCalendarDay_returnsHAgo() {
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - TimeUnit.HOURS.toMillis(2)

        // Only assert "Nh ago" if still same calendar day (avoids midnight edge case)
        if (TimeFormatter.classifyTimeGroup(twoHoursAgo) == TimeGroup.TODAY) {
            assertEquals("2h ago", TimeFormatter.formatRelativeTime(twoHoursAgo))
        } else {
            // Near midnight fallback: should be "Yesterday"
            assertEquals("Yesterday", TimeFormatter.formatRelativeTime(twoHoursAgo))
        }
    }

    @Test
    fun yesterday_previousCalendarDay_returnsYesterday() {
        val now = System.currentTimeMillis()
        // 25 hours ago is reliably the previous calendar day
        val twentyFiveHoursAgo = now - TimeUnit.HOURS.toMillis(25)
        assertEquals("Yesterday", TimeFormatter.formatRelativeTime(twentyFiveHoursAgo))
    }

    @Test
    fun thisWeek_lessThan7Days_returnsDAgo() {
        val now = System.currentTimeMillis()
        val threeDaysAgo = now - TimeUnit.DAYS.toMillis(3)
        assertEquals("3d ago", TimeFormatter.formatRelativeTime(threeDaysAgo))
    }

    @Test
    fun weeksAgo_lessThan30Days_returnsWAgo() {
        val now = System.currentTimeMillis()
        val fourteenDaysAgo = now - TimeUnit.DAYS.toMillis(14)
        assertEquals("2w ago", TimeFormatter.formatRelativeTime(fourteenDaysAgo))
    }

    @Test
    fun olderDate_30PlusDays_returnsFormattedDate() {
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - TimeUnit.DAYS.toMillis(30)
        val result = TimeFormatter.formatRelativeTime(thirtyDaysAgo)
        // Should be a formatted date (not a relative time like "just now", "5d ago", etc.)
        val relativePatterns = listOf("just now", "ago", "Yesterday", "刚刚", "前", "昨天")
        assertTrue(
            "Expected formatted date but got relative time: $result",
            result.isNotBlank() && relativePatterns.none { result.contains(it) }
        )
        // Must contain at least one digit (day of month)
        assertTrue(
            "Expected date with day number but got: $result",
            result.any { it.isDigit() }
        )
    }

    // ── classifyTimeGroup tests ──────────────────────────────────────

    @Test
    fun classifyTimeGroup_today_returnsTODAY() {
        val now = System.currentTimeMillis()
        assertEquals(TimeGroup.TODAY, TimeFormatter.classifyTimeGroup(now))
    }

    @Test
    fun classifyTimeGroup_yesterday_returnsYESTERDAY() {
        val yesterday = LocalDate.now(ZoneId.systemDefault())
            .minusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(TimeGroup.YESTERDAY, TimeFormatter.classifyTimeGroup(yesterday))
    }

    @Test
    fun classifyTimeGroup_threeDaysAgo_returnsTHIS_WEEK() {
        val threeDaysAgo = LocalDate.now(ZoneId.systemDefault())
            .minusDays(3)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(TimeGroup.THIS_WEEK, TimeFormatter.classifyTimeGroup(threeDaysAgo))
    }

    @Test
    fun classifyTimeGroup_eightDaysAgo_returnsOLDER() {
        val eightDaysAgo = LocalDate.now(ZoneId.systemDefault())
            .minusDays(8)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(TimeGroup.OLDER, TimeFormatter.classifyTimeGroup(eightDaysAgo))
    }

    @Test
    fun classifyTimeGroup_sixDaysAgo_returnsTHIS_WEEK() {
        val sixDaysAgo = LocalDate.now(ZoneId.systemDefault())
            .minusDays(6)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(TimeGroup.THIS_WEEK, TimeFormatter.classifyTimeGroup(sixDaysAgo))
    }

    @Test
    fun classifyTimeGroup_sevenDaysAgo_returnsOLDER() {
        val sevenDaysAgo = LocalDate.now(ZoneId.systemDefault())
            .minusDays(7)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(TimeGroup.OLDER, TimeFormatter.classifyTimeGroup(sevenDaysAgo))
    }
}
