package com.opencode.remote.ui.sessions

import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.ui.util.TimeFormatter
import com.opencode.remote.ui.util.TimeGroup

/**
 * Groups sessions by time-based categories (TODAY, YESTERDAY, THIS_WEEK, OLDER).
 *
 * Each group is sorted by `time.updated` descending (newest first).
 * Empty groups are excluded from the result.
 */
internal fun groupSessionsByTime(
    sessions: List<SessionInfo>,
): Map<TimeGroup, List<SessionInfo>> {
    if (sessions.isEmpty()) return emptyMap()

    val buckets = mutableMapOf<TimeGroup, MutableList<SessionInfo>>()

    for (session in sessions) {
        val updatedMs = session.time?.updated ?: 0L
        val group = TimeFormatter.classifyTimeGroup(updatedMs)
        buckets.getOrPut(group) { mutableListOf() }.add(session)
    }

    // Sort each group by time.updated descending (newest first).
    // Null updated treated as 0 (oldest) via the elvis operator.
    buckets.forEach { (_, list) ->
        list.sortByDescending { it.time?.updated ?: 0L }
    }

    // Return immutable map, excluding empty groups (none should be empty at this point
    // since we only add to buckets when we encounter a session, but defensive).
    return buckets
        .filterValues { it.isNotEmpty() }
        .mapValues { it.value.toList() }
}
