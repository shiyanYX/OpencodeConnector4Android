package com.opencode.remote.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Time-based grouping for session display.
 */
enum class TimeGroup {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    OLDER
}

/**
 * Utility for formatting timestamps into human-readable relative time strings
 * and classifying them into time-based groups.
 *
 * Uses only java.time.* and java.util.concurrent.TimeUnit — no third-party libs.
 */
object TimeFormatter {

    /**
     * Formats a timestamp (epoch ms) as a human-readable relative time string.
     *
     * Rules:
     * - null → ""
     * - < 60s → "just now"
     * - < 60min → "N min ago"
     * - < 24h and same calendar day → "Nh ago"
     * - previous calendar day → "Yesterday"
     * - < 7 days → "Nd ago"
     * - < 30 days → "Nw ago"
     * - ≥ 30 days → "MMM d" (e.g., "Jan 15")
     */
    fun formatRelativeTime(timestampMs: Long?): String {
        if (timestampMs == null) return ""

        val now = System.currentTimeMillis()
        val diffMs = now - timestampMs
        val diffSec = TimeUnit.MILLISECONDS.toSeconds(diffMs)
        val diffMin = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val diffHour = TimeUnit.MILLISECONDS.toHours(diffMs)
        val diffDay = TimeUnit.MILLISECONDS.toDays(diffMs)

        return when {
            diffSec < 60 -> "just now"
            diffMin < 60 -> "${diffMin} min ago"
            diffHour < 24 && isSameDay(timestampMs, now) -> "${diffHour}h ago"
            isYesterday(timestampMs, now) -> "Yesterday"
            diffDay < 7 -> "${diffDay}d ago"
            diffDay < 30 -> "${diffDay / 7}w ago"
            else -> formatDate(timestampMs)
        }
    }

    /**
     * Classifies a timestamp into a [TimeGroup] based on the current calendar date.
     *
     * - Same calendar day → TODAY
     * - Previous calendar day → YESTERDAY
     * - Within last 7 calendar days → THIS_WEEK
     * - Older → OLDER
     */
    fun classifyTimeGroup(timestampMs: Long): TimeGroup {
        val now = LocalDate.now(ZoneId.systemDefault())
        val date = toLocalDate(timestampMs)

        return when {
            date == now -> TimeGroup.TODAY
            date == now.minusDays(1) -> TimeGroup.YESTERDAY
            date >= now.minusDays(6) -> TimeGroup.THIS_WEEK
            else -> TimeGroup.OLDER
        }
    }

    // ── Private helpers ──────────────────────────────────────────────

    private fun isSameDay(timestampMs: Long, nowMs: Long): Boolean {
        return toLocalDate(timestampMs) == toLocalDate(nowMs)
    }

    private fun isYesterday(timestampMs: Long, nowMs: Long): Boolean {
        val now = toLocalDate(nowMs)
        return toLocalDate(timestampMs) == now.minusDays(1)
    }

    private fun toLocalDate(timestampMs: Long): LocalDate {
        return Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }

    private fun formatDate(timestampMs: Long): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        return Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }
}
