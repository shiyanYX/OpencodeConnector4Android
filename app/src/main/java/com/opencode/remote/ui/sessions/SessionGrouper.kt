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

/**
 * Filter out child sessions entirely when the user enabled "hide child sessions".
 */
internal fun filterVisibleSessions(
    sessions: List<SessionInfo>,
    hideChildSessions: Boolean,
): List<SessionInfo> {
    if (!hideChildSessions) {
        return sessions
    }
    return sessions.filter { it.parentID.isNullOrBlank() }
}

/**
 * Remove child sessions whose parent is in [expandedParents].
 * When a parent is expanded, its children are shown in the tree —
 * they should not appear in the flat list.
 */
internal fun excludeChildrenOfExpandedParents(
    sessions: List<SessionInfo>,
    childrenMap: Map<String, Set<String>>,
    expandedParents: Set<String>,
): List<SessionInfo> {
    if (expandedParents.isEmpty()) return sessions

    val childIdsToExclude = mutableSetOf<String>()
    for (parentId in expandedParents) {
        childrenMap[parentId]?.let { childIdsToExclude.addAll(it) }
    }
    if (childIdsToExclude.isEmpty()) return sessions

    return sessions.filter { it.id !in childIdsToExclude }
}
