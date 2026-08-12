package com.opencode.remote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.opencode.remote.data.notification.NotificationGate
import com.opencode.remote.service.KeepAliveLifecycleObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OConnectorApp : Application() {

    @Inject
    lateinit var notificationGate: NotificationGate

    @Inject
    lateinit var keepAliveLifecycleObserver: KeepAliveLifecycleObserver

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        keepAliveLifecycleObserver.startObserving()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        // Minimal always-on service notification (foreground service): silent,
        // one-line, and on its own channel so it never stacks with event
        // notifications.
        val foregroundChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "OConnector Service",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps connection to server alive"
        }
        manager.createNotificationChannel(foregroundChannel)

        val interventionChannel = NotificationChannel(
            INTERVENTION_CHANNEL_ID,
            "AI Needs Attention",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifies when AI needs your action"
        }
        manager.createNotificationChannel(interventionChannel)
    }

    companion object {
        const val FOREGROUND_CHANNEL_ID = "foreground_channel"
        const val INTERVENTION_CHANNEL_ID = "intervention_channel"
    }
}
