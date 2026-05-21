package com.opencode.remote.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(checkImmediately())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    @Volatile
    var onNetworkAvailable: (() -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available: $network")
            _isConnected.value = true
            onNetworkAvailable?.invoke()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: $network")
            // Don't immediately set false — another network might be available
            _isConnected.value = checkImmediately()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (hasInternet && !_isConnected.value) {
                Log.d(TAG, "Network capabilities changed: internet=$hasInternet")
                _isConnected.value = true
                onNetworkAvailable?.invoke()
            }
        }
    }

    private var isStarted = false

    fun start() {
        if (isStarted) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing ACCESS_NETWORK_STATE permission", e)
            return
        }
        isStarted = true
        Log.d(TAG, "NetworkMonitor started")
    }

    fun stop() {
        if (!isStarted) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback", e)
        }
        isStarted = false
        Log.d(TAG, "NetworkMonitor stopped")
    }

    private fun checkImmediately(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "NetworkMonitor"
    }
}
