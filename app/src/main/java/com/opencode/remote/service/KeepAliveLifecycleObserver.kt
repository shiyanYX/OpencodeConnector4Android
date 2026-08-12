package com.opencode.remote.service

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.repository.OConnectorRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import android.content.Context

/**
 * Power-saving mode for keep-alive OFF: no foreground service, no persistent
 * notification. The connection is kept while the app is foregrounded and
 * dropped when it goes to the background; returning to the foreground
 * reconnects via [SelfHealConnection].
 *
 * Keep-alive ON keeps the legacy behaviour (foreground service owns the SSE
 * collection) and this observer stays passive.
 */
@Singleton
class KeepAliveLifecycleObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: OConnectorRepository,
    private val preferences: ConnectionPreferences,
    private val selfHealConnection: SelfHealConnection,
    @Named("applicationScope") private val appScope: CoroutineScope,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "KeepAliveLifecycleObserver"
    }

    private var active = false

    /** Call from Application.onCreate(). */
    fun startObserving() {
        if (active) return
        active = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        appScope.launch {
            preferences.keepAlive.collect { enabled ->
                Log.d(TAG, "Keep-alive preference changed to $enabled")
                repository.setKeepAliveEnabled(enabled)
                if (!enabled && repository.isConnected) {
                    // Dropping the foreground service now (the connection itself
                    // stays until we go to background).
                    try {
                        SseForegroundService.stop(context)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to stop foreground service on keep-alive OFF", e)
                    }
                }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        if (!isKeepAliveEnabled()) {
            Log.d(TAG, "App backgrounded in power-saving mode — disconnecting")
            try { repository.disconnect() } catch (e: Exception) {
                Log.w(TAG, "Disconnect on background failed", e)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        if (!isKeepAliveEnabled() && !repository.isConnected) {
            Log.d(TAG, "App foregrounded in power-saving mode — reconnecting")
            appScope.launch {
                try {
                    selfHealConnection.heal()
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect on foreground failed", e)
                }
            }
        }
    }

    private fun isKeepAliveEnabled(): Boolean {
        return try {
            kotlinx.coroutines.runBlocking { preferences.keepAlive.first() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read keep-alive preference", e)
            true
        }
    }
}
