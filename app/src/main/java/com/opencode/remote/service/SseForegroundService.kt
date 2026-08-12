package com.opencode.remote.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.opencode.remote.OConnectorApp
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.notification.InterventionNotifier
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sse.SseEventBus
import com.opencode.remote.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class SseForegroundService : Service() {

    @Inject lateinit var sseClient: OConnectorSseClient
    @Inject lateinit var eventBus: SseEventBus
    @Inject lateinit var repository: OConnectorRepository
    @Inject @Named("applicationScope") lateinit var appScope: CoroutineScope
    @Inject lateinit var interventionNotifier: InterventionNotifier
    @Inject lateinit var selfHealConnection: SelfHealConnection
    @Inject lateinit var preferences: com.opencode.remote.data.datastore.ConnectionPreferences

    private var sseJob: Job? = null

    /** Generation currently being collected — duplicate starts with the same gen are no-ops. */
    @Volatile
    private var activeGeneration: Long = -1L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guard: if the user turned keep-alive OFF, never let this service run
        // (covers Android-restarted instances from the previous START_STICKY run).
        val keepAlive = try {
            kotlinx.coroutines.runBlocking { preferences.keepAlive.first() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read keep-alive preference", e)
            true
        }
        if (!keepAlive) {
            Log.d(TAG, "Keep-alive disabled — stopping foreground service")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Android restarted the service (e.g., after memory kill).
        // Recover WITHOUT any UI: reconnect to the last known server from
        // persisted preferences, then resume SSE collection.
        if (intent == null) {
            Log.w(TAG, "onStartCommand with null intent (service restart), recovering")
            sseJob?.cancel()
            sseJob = appScope.launch {
                try {
                    val healed = selfHealConnection.heal()
                    // Refresh the persistent notification: on recovery the server
                    // name is only known AFTER heal(), so the one created at the top
                    // of onStartCommand still shows the generic "running" text.
                    if (healed) updateForegroundNotification()
                    val recoveredGen = repository.currentGeneration
                    eventBus.activateGeneration(recoveredGen)
                    sseClient.subscribeToEvents().collect { event ->
                        eventBus.emit(event, recoveredGen)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE collection stopped: ${e.message}")
                    emitSseError(e)
                }
            }
            interventionNotifier.start()
            return START_STICKY
        }

        val generation = intent.getLongExtra("generation", 0L)

        // Reconnect/debounce: don't tear down and re-collect when the service is
        // already running for this generation (multiple startForegroundService
        // callers — connect helpers, network recovery, deep links — may re-fire).
        if (activeGeneration == generation && sseJob?.isActive == true) {
            return START_STICKY
        }
        activeGeneration = generation

        // Activate generation on event bus — filters stale events at source
        eventBus.activateGeneration(generation)

        // Cancel existing SSE collection if any (handles server switch)
        sseJob?.cancel()

        sseJob = appScope.launch {
            try {
                sseClient.subscribeToEvents().collect { event ->
                    eventBus.emit(event, generation)
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSE collection stopped: ${e.message}")
                emitSseError(e)
            }
        }
        interventionNotifier.start()

        return START_STICKY
    }

    override fun onDestroy() {
        interventionNotifier.stop()
        sseJob?.cancel()
        sseJob = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Surface a fatal SSE error (quota/auth/HTTP rejection) to the UI as a session.error event. */
    private fun emitSseError(e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) return  // normal stop — not an error
        val message = e.message ?: e.javaClass.simpleName
        val payload = com.opencode.remote.data.api.dto.EventPayload(
            type = "session.error",
            properties = com.opencode.remote.data.api.dto.EventProperties(error = message),
        )
        eventBus.emit(com.opencode.remote.data.api.dto.ServerEvent(payload = payload), repository.currentGeneration)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            repository.activeSessionId?.let { putExtra("sessionId", it) }
            repository.activeSessionDirectory?.let { putExtra("directory", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val serverName = repository.getCurrentServerName()
        val s = com.opencode.remote.ui.strings.AppLocale.strings
        val contentText = if (serverName.isNullOrBlank()) {
            s.notificationRunning
        } else {
            s.connectedTo.replace("%s", serverName)
        }
        val notification = Notification.Builder(this, OConnectorApp.FOREGROUND_CHANNEL_ID)
            .setContentTitle("OConnector")
            .setContentText(contentText)
            .setSmallIcon(com.opencode.remote.R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        // guarantee no-clear semantics beyond ongoing() — some OEM shells clear
        // ongoing notifications with "clear all" unless NO_CLEAR is explicit
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
        return notification
    }

    /** Refresh the persistent notification in place (server name may change after recovery). */
    private fun updateForegroundNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update foreground notification", e)
        }
    }

    companion object {
        private const val TAG = "SseForegroundService"
        const val NOTIFICATION_ID = 1001

        @Volatile
        private var lastRestartTime: Long = 0L
        private const val RESTART_DEBOUNCE_MS = 3000L

        fun start(context: Context, generation: Long = 0L) {
            try {
                val intent = Intent(context, SseForegroundService::class.java)
                intent.putExtra("generation", generation)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SSE foreground service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SseForegroundService::class.java)
            context.stopService(intent)
        }

        fun restart(context: Context, generation: Long = 0L) {
            synchronized(this) {
                val now = System.currentTimeMillis()
                if (now - lastRestartTime < RESTART_DEBOUNCE_MS) {
                    Log.d(TAG, "Restart debounced (${now - lastRestartTime}ms since last)")
                    return
                }
                lastRestartTime = now
            }
            stop(context)
            try {
                start(context, generation)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart SSE foreground service", e)
            }
        }
    }
}
