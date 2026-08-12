package com.opencode.remote.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.opencode.remote.data.notification.NotificationGate
import com.opencode.remote.ui.theme.OConnectorTheme
import com.opencode.remote.ui.strings.AppLocale
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val intentState: MutableState<Intent?> = mutableStateOf(null)

    @Inject
    lateinit var notificationGate: NotificationGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentState.value = intent
        enableEdgeToEdge()
        setContent {
            OConnectorTheme(darkTheme = AppLocale.darkMode) {
                OConnectorApp(initialIntent = intent, intentState = intentState)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        notificationGate.setForeground(true)
    }

    override fun onStop() {
        notificationGate.setForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent
    }
}
