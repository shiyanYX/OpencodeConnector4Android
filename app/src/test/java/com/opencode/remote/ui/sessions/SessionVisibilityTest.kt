package com.opencode.remote.ui.sessions

import com.opencode.remote.data.api.dto.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionVisibilityTest {

    @Test
    fun `returns all sessions when child filtering disabled`() {
        val sessions = listOf(
            SessionInfo(id = "root", parentID = null),
            SessionInfo(id = "child", parentID = "root"),
        )

        assertEquals(sessions, filterVisibleSessions(sessions, hideChildSessions = false))
    }

    @Test
    fun `filters child sessions when child filtering enabled`() {
        val root = SessionInfo(id = "root", parentID = null)
        val blankParent = SessionInfo(id = "root-blank", parentID = "   ")
        val child = SessionInfo(id = "child", parentID = "root")

        assertEquals(
            listOf(root, blankParent),
            filterVisibleSessions(listOf(root, blankParent, child), hideChildSessions = true),
        )
    }

    // ─── excludeChildrenOfExpandedParents ──────────────────────────────

    @Test
    fun `expanded parent removes its children from flat list`() {
        val root = SessionInfo(id = "root", parentID = null)
        val child1 = SessionInfo(id = "child_1", parentID = "root")
        val child2 = SessionInfo(id = "child_2", parentID = "root")

        val result = excludeChildrenOfExpandedParents(
            sessions = listOf(root, child1, child2),
            childrenMap = mapOf("root" to setOf("child_1", "child_2")),
            expandedParents = setOf("root"),
        )
        assertEquals(listOf(root), result)
    }

    @Test
    fun `collapsed parent keeps its children in flat list`() {
        val root = SessionInfo(id = "root", parentID = null)
        val child = SessionInfo(id = "child_1", parentID = "root")

        val result = excludeChildrenOfExpandedParents(
            sessions = listOf(root, child),
            childrenMap = mapOf("root" to setOf("child_1")),
            expandedParents = emptySet(),
        )
        assertEquals(listOf(root, child), result)
    }

    @Test
    fun `multiple expanded parents remove all their children`() {
        val rootA = SessionInfo(id = "root_a", parentID = null)
        val rootB = SessionInfo(id = "root_b", parentID = null)
        val childA = SessionInfo(id = "child_a", parentID = "root_a")
        val childB = SessionInfo(id = "child_b", parentID = "root_b")
        val orphan = SessionInfo(id = "orphan", parentID = null)

        val result = excludeChildrenOfExpandedParents(
            sessions = listOf(rootA, rootB, childA, childB, orphan),
            childrenMap = mapOf(
                "root_a" to setOf("child_a"),
                "root_b" to setOf("child_b"),
            ),
            expandedParents = setOf("root_a", "root_b"),
        )
        assertEquals(listOf(rootA, rootB, orphan), result)
    }

    @Test
    fun `child with no parent entry in childrenMap stays`() {
        val root = SessionInfo(id = "root", parentID = null)
        val child = SessionInfo(id = "child_1", parentID = "root")

        // child_1 has parentID="root" but childrenMap doesn't list root_a as expanded
        val result = excludeChildrenOfExpandedParents(
            sessions = listOf(root, child),
            childrenMap = emptyMap(),
            expandedParents = setOf("root"),
        )
        // No children to exclude because childrenMap has no entry for "root"
        assertEquals(listOf(root, child), result)
    }
}
