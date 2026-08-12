package com.opencode.remote.data.notification

import com.opencode.remote.data.datastore.ConnectionPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global gate for intervention notifications.
 *
 * Tracks whether the app is in the foreground and which session is currently
 * open in the UI, so notifications are only raised when the user is NOT
 * watching the session where the event happened (app in background, or a
 * different session open).
 */
@Singleton
class NotificationGate @Inject constructor(
    private val preferences: ConnectionPreferences,
) {
    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /** User toggle — notifications on/off (persisted in preferences). */
    val enabled: Flow<Boolean> = preferences.notificationsEnabled

    /** Whether a notification should be raised for an event in [sessionId]. */
    suspend fun shouldNotify(sessionId: String?): Boolean {
        if (!preferences.notificationsEnabled.first()) return false
        return !_isForeground.value || _currentSessionId.value != sessionId
    }

    fun setForeground(foreground: Boolean) {
        _isForeground.value = foreground
    }

    fun setCurrentSessionId(sessionId: String?) {
        _currentSessionId.value = sessionId
    }
}