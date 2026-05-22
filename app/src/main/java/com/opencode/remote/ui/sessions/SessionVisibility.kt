package com.opencode.remote.ui.sessions

import com.opencode.remote.data.api.dto.SessionInfo

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
