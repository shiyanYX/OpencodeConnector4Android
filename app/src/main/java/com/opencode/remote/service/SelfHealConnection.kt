package com.opencode.remote.service

import android.util.Log
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.data.datastore.ServerManager
import com.opencode.remote.data.repository.OConnectorRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconnects the repository to the last active server using only persisted
 * data (no UI), so the SSE foreground service can recover from a process
 * death / Android service restart on its own.
 */
@Singleton
class SelfHealConnection @Inject constructor(
    private val serverManager: ServerManager,
    private val repository: OConnectorRepository,
) {
    companion object {
        private const val TAG = "SelfHealConnection"
    }

    /**
     * Returns true when the repository is now configured for a server.
     * No-op (and returns true) if already connected.
     */
    suspend fun heal(): Boolean {
        if (repository.isConnected) return true
        return try {
            val serverId = withContext(Dispatchers.IO) { serverManager.lastActiveServerId.first() }
                ?: return false
            val server = withContext(Dispatchers.IO) { serverManager.servers.first() }
                .find { it.id == serverId } ?: return false
            val password = serverManager.getPassword(serverId) ?: ""
            val config = ConnectionConfig(
                serverId = server.id,
                host = server.host,
                port = server.port,
                username = server.username,
                password = password,
                useTls = server.useTls,
                insecureTrust = server.insecureTrust,
            )
            withContext(Dispatchers.IO) { repository.connect(config) }
            repository.setServerName(server.name)
            Log.i(TAG, "Self-healed connection to ${server.host}:${server.port}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Self-heal connection failed", e)
            false
        }
    }
}