package com.opencode.remote.ui.serverlist

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.api.dto.ServerInfo
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.ServerManager
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.repository.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverManager: ServerManager,
    private val repository: OConnectorRepository,
    private val preferences: ConnectionPreferences,
) : ViewModel() {

    @Immutable
    data class ServerListUiState(
        val servers: List<ServerInfo> = emptyList(),
        val isConnecting: Boolean = false,
        val error: String? = null,
        /** Set once after the first successful connection — UI should request POST_NOTIFICATIONS. */
        val requestNotificationPermission: Boolean = false,
    )

    private val _uiState = MutableStateFlow(ServerListUiState())
    val uiState: StateFlow<ServerListUiState> = _uiState.asStateFlow()

    /**
     * Set once after a *user-initiated* connect succeeds — the navigation layer uses it to
     * land on the Projects screen. Startup auto-connect (notification deep links) does NOT
     * set it, so launching the app stays on the server list instead of skipping home.
     */
    private val _navigateToProjects = MutableStateFlow(false)
    val navigateToProjects: StateFlow<Boolean> = _navigateToProjects.asStateFlow()

    fun consumeNavigateToProjects() {
        _navigateToProjects.value = false
    }

    /** Connection lifecycle from the repository — single source of truth, survives ViewModel recreation. */
    val connectionState: StateFlow<ConnectionStatus> = repository.connectionState

    fun notificationPermissionRequestHandled() {
        _uiState.update { it.copy(requestNotificationPermission = false) }
    }

    companion object {
        private const val TAG = "ServerListViewModel"
    }

    init {
        viewModelScope.launch {
            serverManager.migrateIfNeeded()
        }
        viewModelScope.launch {
            serverManager.servers.collect { list ->
                _uiState.update { it.copy(servers = list) }
            }
        }
        // Auto-connect to last active server at startup (for notification deep links).
        // navigateOnSuccess=false — the app should land on the server list, not skip home.
        viewModelScope.launch {
            val lastId = serverManager.lastActiveServerId.first()
            if (lastId != null && !repository.isConnected) {
                connect(lastId, navigateOnSuccess = false)
            }
        }
    }

    fun connectToServer(serverId: String) = connect(serverId, navigateOnSuccess = true)

    private fun connect(serverId: String, navigateOnSuccess: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, error = null) }

            val server = _uiState.value.servers.find { it.id == serverId }
            if (server == null) {
                _uiState.update {
                    it.copy(isConnecting = false, error = "Server not found")
                }
                return@launch
            }

            // Read password on IO thread — EncryptedSharedPreferences init involves Keystore I/O
            val password = withContext(Dispatchers.IO) {
                serverManager.getPassword(serverId) ?: ""
            }

            val config = ConnectionConfig(
                serverId = server.id,
                host = server.host,
                port = server.port,
                username = server.username,
                password = password,
                useTls = server.useTls,
                insecureTrust = server.insecureTrust,
            )

            // Single pipeline (connect → test → start SSE) with one error path
            val ok = repository.connectAndVerify(config, server.name)
            if (ok) {
                serverManager.saveLastActiveServerId(serverId)
                _uiState.update { it.copy(isConnecting = false, requestNotificationPermission = true) }
                if (navigateOnSuccess) _navigateToProjects.value = true
            } else {
                _uiState.update { it.copy(isConnecting = false, error = "Connection test failed") }
            }
        }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch {
            serverManager.deleteServer(id)
        }
    }
}
