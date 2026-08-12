package com.opencode.remote.data.cache

import android.content.Context
import android.util.Log
import com.opencode.remote.data.api.dto.MessageInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-session message cache (stale-while-revalidate).
 *
 * Purpose: switching between two sessions must not show a full-screen spinner —
 * the cached copy renders instantly, then the repository re-fetches in the
 * background and the cache is refreshed.
 *
 * - Memory: access-ordered LRU map (fastest, hot sessions)
 * - Disk: one JSON file per session under cacheDir/message_cache (survives process death)
 * - Merging: fresh windows fetched by the repository are unioned with the existing
 *   cache by message id (newest last, matching server time ordering), capped at 500.
 *
 * The cache is populated by [com.opencode.remote.data.repository.OpenCodeRepository.getMessages],
 * which every message refresh path goes through (initialize, reload, pagination,
 * session.idle SSE reload, undo/redo) — so the cache stays fresh without hooks
 * in the ViewModel.
 */
@Singleton
class MessageCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "MessageCache"
        private const val MAX_MESSAGES = 500
        private const val MEMORY_MAX_SESSIONS = 20
        private const val DISK_MAX_SESSIONS = 30
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val memory = object : LinkedHashMap<String, List<MessageInfo>>(MEMORY_MAX_SESSIONS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MessageInfo>>): Boolean =
            size > MEMORY_MAX_SESSIONS
    }

    private val diskDir: File
        get() = File(context.cacheDir, "message_cache")

    /**
     * Load the cached messages for a session: memory first, then disk.
     * Returns null when nothing is cached.
     */
    suspend fun load(sessionId: String): List<MessageInfo>? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            memory[sessionId]?.let { return@withContext it }
        }
        val file = fileFor(sessionId)
        if (!file.exists()) return@withContext null
        val list = runCatching { json.decodeFromString<List<MessageInfo>>(file.readText()) }.getOrNull()
        if (list == null) {
            Log.w(TAG, "Corrupt cache file, deleting: ${file.name}")
            file.delete()
            return@withContext null
        }
        synchronized(lock) { memory[sessionId] = list }
        list
    }

    /**
     * Union the freshly fetched window into the cache (by message id, keeping
     * server time ordering, capped at [MAX_MESSAGES]), then persist.
     */
    suspend fun merge(sessionId: String, fresh: List<MessageInfo>) {
        if (fresh.isEmpty()) return
        withContext(Dispatchers.IO) {
            val existing = synchronized(lock) { memory[sessionId] }
                ?: runCatching {
                    val file = fileFor(sessionId)
                    if (file.exists()) json.decodeFromString<List<MessageInfo>>(file.readText()) else emptyList()
                }.getOrDefault(emptyList())

            val byId = LinkedHashMap<String, MessageInfo>()
            (existing + fresh).forEach { byId[it.id] = it }
            val merged = byId.values.toList().takeLast(MAX_MESSAGES)

            synchronized(lock) { memory[sessionId] = merged }
            writeDisk(sessionId, merged)
        }
    }

    /** Drop one session from memory and disk. */
    suspend fun remove(sessionId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) { memory.remove(sessionId) }
        fileFor(sessionId).delete()
    }

    /** Drop everything (e.g. on server switch). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        synchronized(lock) { memory.clear() }
        diskDir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(sessionId: String): File {
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(sessionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(diskDir, "$sha1.json")
    }

    private fun writeDisk(sessionId: String, messages: List<MessageInfo>) {
        runCatching {
            diskDir.mkdirs()
            fileFor(sessionId).writeText(json.encodeToString(messages))
            // LRU sweep: keep at most DISK_MAX_SESSIONS files (oldest mtime deleted)
            val files = diskDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            val overflow = files.size - DISK_MAX_SESSIONS
            if (overflow > 0) files.take(overflow).forEach { it.delete() }
        }.onFailure { e -> Log.w(TAG, "Failed to persist message cache", e) }
    }
}
