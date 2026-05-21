package com.opencode.remote.data.sessionstore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SessionStatus {
    BUSY,
    IDLE,
}

@Singleton
class ActiveSessionStore @Inject constructor() {

    private val _statusMap = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    val statusMap: StateFlow<Map<String, SessionStatus>> = _statusMap.asStateFlow()

    fun updateStatus(sessionId: String, status: SessionStatus) {
        _statusMap.value = _statusMap.value + (sessionId to status)
    }

    fun updateFromStatusEndpoint(map: Map<String, String>) {
        _statusMap.value = map.mapValues { (_, v) ->
            when (v.lowercase()) {
                "busy" -> SessionStatus.BUSY
                else -> SessionStatus.IDLE
            }
        }
    }

    fun clear() {
        _statusMap.value = emptyMap()
    }
}
