package com.opencode.remote.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.datastore.RecentSessionEntry
import com.opencode.remote.data.datastore.RecentSessionStore
import com.opencode.remote.data.repository.OConnectorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecentViewModel @Inject constructor(
    repository: OConnectorRepository,
    private val recentSessionStore: RecentSessionStore,
) : ViewModel() {

    /**
     * The app keeps a single active connection — the Recent page only shows
     * entries of the currently connected server (Q7-A). If the server
     * disconnects the navigation stack is reset to the server list, so
     * capturing the serverId at construction time is sufficient.
     */
    val entries: StateFlow<List<RecentSessionEntry>> = recentSessionStore
        .observe(repository.getActiveServerId() ?: "")
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun remove(serverId: String, sessionId: String) {
        viewModelScope.launch { recentSessionStore.remove(serverId, sessionId) }
    }
}
