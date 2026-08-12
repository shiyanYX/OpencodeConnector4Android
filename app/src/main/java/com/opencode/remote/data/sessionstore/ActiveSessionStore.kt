package com.opencode.remote.data.sessionstore

import android.util.Log
import com.opencode.remote.data.repository.OConnectorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class SessionStatus {
    BUSY,
    IDLE,
}

@Singleton
class ActiveSessionStore @Inject constructor() {

    companion object {
        private const val TAG = "ActiveSessionStore"
    }

    private val _statusMap = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    val statusMap: StateFlow<Map<String, SessionStatus>> = _statusMap.asStateFlow()

    fun updateStatus(sessionId: String, status: SessionStatus) {
        _statusMap.update { it + (sessionId to status) }
    }

    fun removeSession(sessionId: String) {
        _statusMap.update { it - sessionId }
    }

    /**
     * Merge statuses reported by the GET /session/status endpoint into the map.
     *
     * This is a merge, NOT a full replace: sessions absent from the endpoint
     * keep their SSE-derived status. The endpoint on the target server
     * (opencode serve) currently returns an empty map even while sessions are
     * generating, so replacing the whole map would wipe SSE-fed busy dots.
     */
    fun updateFromStatusEndpoint(map: Map<String, String>) {
        if (map.isEmpty()) return
        var merged = _statusMap.value
        map.forEach { (sessionId, value) ->
            val status = when (value.lowercase()) {
                "busy" -> SessionStatus.BUSY
                else -> SessionStatus.IDLE
            }
            merged = merged + (sessionId to status)
        }
        _statusMap.value = merged
    }

    suspend fun refreshAllStatuses(repository: OConnectorRepository) {
        try {
            val statusMap = repository.getSessionStatus()
            updateFromStatusEndpoint(statusMap)
            Log.d(TAG, "Refreshed status for ${statusMap.size} sessions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh session statuses", e)
        }
    }

    fun clear() {
        _statusMap.value = emptyMap()
    }
}
