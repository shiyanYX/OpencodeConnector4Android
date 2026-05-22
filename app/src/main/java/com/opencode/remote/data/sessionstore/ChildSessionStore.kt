package com.opencode.remote.data.sessionstore

import android.util.Log
import com.opencode.remote.data.repository.OConnectorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks parent-child session relationships.
 * Key = parent session ID, Value = set of child session IDs.
 * Updated from SSE events (`session.created` / `session.deleted`) and REST refresh.
 */
@Singleton
class ChildSessionStore @Inject constructor() {

    companion object {
        private const val TAG = "ChildSessionStore"
    }

    private val _childrenMap = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val childrenMap: StateFlow<Map<String, Set<String>>> = _childrenMap.asStateFlow()

    /** Tracks which parents have been loaded via refreshChildren(). */
    private val _loadedParents = mutableSetOf<String>()

    /** Register a child session under a parent (from SSE `session.created`). */
    fun registerChild(parentId: String, childId: String) {
        _childrenMap.value = _childrenMap.value.toMutableMap().apply {
            val children = getOrPut(parentId) { emptySet() }
            put(parentId, children + childId)
        }
    }

    /** Remove a child session (from SSE `session.deleted`). */
    fun removeChild(parentId: String, childId: String) {
        _childrenMap.value = _childrenMap.value.toMutableMap().apply {
            val children = get(parentId) ?: return@apply
            val updated = children - childId
            if (updated.isEmpty()) {
                remove(parentId)
            } else {
                put(parentId, updated)
            }
        }
    }

    /** Synchronous lookup of children for a parent session. */
    fun getChildren(parentId: String): Set<String> =
        _childrenMap.value[parentId] ?: emptySet()

    /** Check whether children for a parent have been loaded via refreshChildren(). */
    fun hasLoadedChildren(parentId: String): Boolean =
        parentId in _loadedParents

    /** Remove a parent from the loaded set, forcing a reload on next access. */
    fun invalidate(parentId: String) {
        _loadedParents.remove(parentId)
    }

    /** Refresh children for a parent by calling the REST API. Replaces the stored set. */
    suspend fun refreshChildren(parentId: String, repository: OConnectorRepository) {
        try {
            val children = repository.getSessionChildren(parentId)
            val childIds = children.map { it.id }.toSet()
            _childrenMap.value = _childrenMap.value.toMutableMap().apply {
                if (childIds.isEmpty()) {
                    remove(parentId)
                } else {
                    put(parentId, childIds)
                }
            }
            _loadedParents.add(parentId)
            Log.d(TAG, "Refreshed children for $parentId: ${childIds.size} children")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh children for $parentId", e)
        }
    }

    /** Clear all parent-child mappings (e.g. on disconnect). */
    fun clear() {
        _childrenMap.value = emptyMap()
        _loadedParents.clear()
    }
}
