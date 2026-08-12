package com.opencode.remote.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RecentSessionEntry(
    val serverId: String = "",
    val sessionId: String = "",
    val title: String = "",
    val directory: String = "",
    val openedAt: Long = 0L,
)

/**
 * Local "recent sessions" history. Written when a chat session is opened,
 * read by the Recent landing page (filtered to the currently connected server).
 * Kept in the same DataStore as [ConnectionPreferences]; capped at [MAX_ENTRIES]
 * with oldest entries rolled off.
 */
@Singleton
class RecentSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "RecentSessionStore"
        private const val MAX_ENTRIES = 10
        private val KEY = stringPreferencesKey("recent_sessions")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String?): List<RecentSessionEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<RecentSessionEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun encode(entries: List<RecentSessionEntry>): String = json.encodeToString(entries)

    /** All entries across servers, newest first, capped. */
    val entries: Flow<List<RecentSessionEntry>> = context.dataStore.data
        .map { prefs -> decode(prefs[KEY]) }
        .catch { e ->
            Log.e(TAG, "Failed to read recent sessions", e)
            emit(emptyList())
        }

    /** Entries for a single server, newest first. */
    fun observe(serverId: String): Flow<List<RecentSessionEntry>> =
        entries.map { list -> list.filter { it.serverId == serverId } }

    /**
     * Record a session open. Re-entries of the same session move it to the top.
     */
    suspend fun record(serverId: String, sessionId: String, title: String, directory: String?) {
        val entry = RecentSessionEntry(
            serverId = serverId,
            sessionId = sessionId,
            title = title,
            directory = directory ?: "",
            openedAt = System.currentTimeMillis(),
        )
        context.dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val updated = (listOf(entry) + current)
                .distinctBy { it.sessionId }
                .sortedByDescending { it.openedAt }
                .take(MAX_ENTRIES)
            prefs[KEY] = encode(updated)
        }
    }

    /** Remove one entry (pure local operation — the server session is untouched). */
    suspend fun remove(serverId: String, sessionId: String) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs[KEY])
            val updated = current.filterNot { it.serverId == serverId && it.sessionId == sessionId }
            prefs[KEY] = encode(updated)
        }
    }
}
