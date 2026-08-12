package com.opencode.remote.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.ui.strings.AppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ConnectionPreferences,
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val LOG_TAIL_LINES = 5000
    }

    val language: StateFlow<String> = preferences.language
        .stateIn(viewModelScope, SharingStarted.Eagerly, "zh")

    val darkMode: StateFlow<Boolean> = preferences.darkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val notificationsEnabled: StateFlow<Boolean> = preferences.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val keepAlive: StateFlow<Boolean> = preferences.keepAlive
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setLanguage(lang: String) {
        AppLocale.language = lang
        viewModelScope.launch { preferences.saveLanguage(lang) }
    }

    fun toggleDarkMode() {
        val newValue = !AppLocale.darkMode
        AppLocale.darkMode = newValue
        viewModelScope.launch { preferences.saveDarkMode(newValue) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.saveNotificationsEnabled(enabled) }
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        // Persist + push to the repository immediately (the lifecycle observer
        // also watches the preference and stops the foreground service).
        viewModelScope.launch { preferences.saveKeepAlive(enabled) }
    }

    /**
     * Dump the last [LOG_TAIL_LINES] logcat lines into a private cache file and
     * return a shareable FileProvider URI. Returns null when nothing was captured.
     */
    suspend fun exportLogs(): Uri? = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", LOG_TAIL_LINES.toString())
            )
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) {
                Log.w(TAG, "logcat produced no output")
                return@withContext null
            }

            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencode_$stamp.txt")
            file.writeText(text)
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            null
        }
    }
}
