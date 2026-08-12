package com.opencode.remote.data.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.opencode.remote.OConnectorApp
import com.opencode.remote.data.api.dto.MessageInfo
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sse.EventEnvelope
import com.opencode.remote.data.sse.SseEventBus
import com.opencode.remote.ui.MainActivity
import com.opencode.remote.ui.strings.AppLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Raises notifications when the AI needs the user's action (permission /
 * question requests) or finishes long-running work (todo list completion,
 * successful executions), as long as the user is not watching that session.
 *
 * SSE events are the primary source; a 60s polling fallback applies the same
 * "last assistant incomplete + session completed" heuristic used by the chat
 * UI, so blocked sessions are still noticed if SSE was disconnected.
 */
@Singleton
class InterventionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: SseEventBus,
    private val repository: OConnectorRepository,
    private val gate: NotificationGate,
    @Named("applicationScope") private val appScope: CoroutineScope,
) {
    companion object {
        private const val TAG = "InterventionNotifier"

        private const val ID_ACTION = 2001
        private const val ID_TASK_DONE = 2002
        private const val ID_TODO_DONE = 2003
        private const val ID_BLOCKED = 2004
        private const val ACTION_WATCHDOG_MS = 120_000L
        private const val POLL_INTERVAL_MS = 60_000L

        private fun tagAction(requestId: String) = "action-$requestId"
        private fun tagBlocked(sessionId: String) = "blocked-$sessionId"
        private fun tagTodo(sessionId: String) = "todo-$sessionId"
    }

    private data class ActiveIntervention(val tag: String, val watchdog: Job?)

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var collectJob: Job? = null
    private var pollJob: Job? = null
    private var dismissJob: Job? = null

    /** sessionId -> tag of the currently shown intervention (A-class). */
    private val activeInterventions = mutableMapOf<String, ActiveIntervention>()

    /** Dedupe sets — each state transition notifies at most once. */
    private val notifiedBlocked = mutableSetOf<String>()
    private val notifiedTodoDone = mutableSetOf<String>()
    private val notifiedTaskDone = mutableSetOf<String>()

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        Log.d(TAG, "InterventionNotifier started")
        collectJob = appScope.launch {
            eventBus.events.collectLatest { handleEvent(it) }
        }
        pollJob = appScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                pollFallback()
            }
        }
        // Dismiss intervention notifications when the user opens the session
        // (app foreground + session visible).
        dismissJob = appScope.launch {
            combine(gate.isForeground, gate.currentSessionId) { fg, sid -> fg to sid }
                .collectLatest { (fg, sid) ->
                    if (fg && sid != null) {
                        activeInterventions.remove(sid)?.let { cancelTag(it.tag) }
                    }
                }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        Log.d(TAG, "InterventionNotifier stopped")
        collectJob?.cancel()
        pollJob?.cancel()
        dismissJob?.cancel()
        collectJob = null
        pollJob = null
        dismissJob = null
        activeInterventions.values.forEach { it.watchdog?.cancel() }
        activeInterventions.clear()
    }

    // ─── SSE event handling ─────────────────────────────────────────

    private suspend fun handleEvent(env: EventEnvelope) {
        val event = env.event
        val type = event.payload.type
        val props = event.payload.properties
        val s = AppLocale.strings
        when (type) {
            "permission.asked" -> {
                val requestId = props.id ?: return
                val sessionId = props.sessionID ?: return
                if (gate.shouldNotify(sessionId)) {
                    postAction(
                        sessionId = sessionId,
                        requestId = requestId,
                        directory = event.directory,
                        title = s.permissionRequired,
                        text = s.notificationPermissionBody.replace("%s", props.permission ?: "permission"),
                    )
                }
            }

            "question.asked" -> {
                val requestId = props.id ?: return
                val sessionId = props.sessionID ?: return
                if (gate.shouldNotify(sessionId)) {
                    val question = props.questions?.firstOrNull()?.question.orEmpty()
                    postAction(
                        sessionId = sessionId,
                        requestId = requestId,
                        directory = event.directory,
                        title = s.questionTitle,
                        text = s.notificationQuestionBody.replace("%s", question.take(60)),
                    )
                }
            }

            "permission.replied", "question.replied", "question.rejected" -> {
                val requestId = props.id ?: return
                cancelAction(requestId)
                props.sessionID?.let { notifiedBlocked.remove(it) }
            }

            "todo.updated" -> {
                checkTodoCompletion(props.sessionID)
            }

            "session.execution.started" -> {
                notifiedTaskDone.remove(props.sessionID)
            }

            "session.execution.succeeded" -> {
                val sessionId = props.sessionID ?: return
                if (sessionId != repository.activeSessionId) return
                if (notifiedTaskDone.contains(sessionId)) return
                if (!gate.shouldNotify(sessionId)) return
                notifiedTaskDone.add(sessionId)
                postTaskDone(sessionId, event.directory)
            }
        }
    }

    // ─── A-class notifications (interventions) ──────────────────────

    private fun postAction(sessionId: String, requestId: String, directory: String?, title: String, text: String) {
        // Replace an existing intervention for the same session (queued requests)
        activeInterventions.remove(sessionId)?.let { cancelTag(it.tag) }

        val tag = tagAction(requestId)
        val notification = buildNotification(
            channelId = OConnectorApp.INTERVENTION_CHANNEL_ID,
            title = title,
            text = text,
            sessionId = sessionId,
            directory = directory,
            subtext = AppLocale.strings.notificationSubtext,
        )
        try {
            notificationManager.notify(tag, ID_ACTION, notification)
            val watchdog = appScope.launch {
                // Only auto-dismiss AFTER the notification is actually delivered
                // (Doze may delay delivery past the alarm); while it stays queued
                // (not in activeNotifications) keep waiting.
                while (isActive) {
                    delay(ACTION_WATCHDOG_MS)
                    val delivered = try {
                        notificationManager.activeNotifications.any { it.tag == tag }
                    } catch (e: Exception) {
                        true
                    }
                    if (!delivered) continue
                    cancelAction(requestId)
                    break
                }
            }
            activeInterventions[sessionId] = ActiveIntervention(tag, watchdog)
            Log.d(TAG, "Post intervention notification: $tag (session ${sessionId.take(8)})")
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping intervention", e)
        }
    }

    private fun cancelAction(requestId: String) {
        val tag = tagAction(requestId)
        val entry = activeInterventions.entries.find { it.value.tag == tag }
        entry?.let {
            it.value.watchdog?.cancel()
            activeInterventions.remove(it.key)
        }
        cancelTag(tag)
    }

    private fun cancelTag(tag: String) {
        try {
            notificationManager.cancel(tag, ID_ACTION)
            notificationManager.cancel(tag, ID_BLOCKED)
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to cancel notification $tag", e)
        }
    }

    // ─── C-class notifications (task/todo completion) ────────────────

    private suspend fun postTaskDone(sessionId: String, directory: String?) {
        val s = AppLocale.strings
        val notification = buildNotification(
            channelId = OConnectorApp.INTERVENTION_CHANNEL_ID,
            title = s.notificationRunDoneTitle,
            text = s.notificationRunDoneBody.replace("%s", sessionDisplayName(sessionId, directory)),
            sessionId = sessionId,
            directory = directory,
        )
        try {
            notificationManager.notify(sessionId, ID_TASK_DONE, notification)
            Log.d(TAG, "Post task-done notification: session ${sessionId.take(8)}")
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping task-done", e)
        }
    }

    private suspend fun postBlocked(sessionId: String, directory: String?) {
        val s = AppLocale.strings
        val notification = buildNotification(
            channelId = OConnectorApp.INTERVENTION_CHANNEL_ID,
            title = s.notificationBlockedTitle,
            text = s.notificationBlockedBody,
            sessionId = sessionId,
            directory = directory,
        )
        try {
            notificationManager.notify(tagBlocked(sessionId), ID_BLOCKED, notification)
            activeInterventions[sessionId] = ActiveIntervention(tagBlocked(sessionId), null)
            Log.d(TAG, "Post blocked-fallback notification: session ${sessionId.take(8)}")
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping blocked", e)
        }
    }

    private suspend fun checkTodoCompletion(sessionId: String?) {
        if (sessionId == null) return
        try {
            val todos = repository.getTodoList(sessionId, repository.activeSessionDirectory)
            val allDone = todos.isNotEmpty() && todos.all { it.status == "completed" }
            if (allDone) {
                if (notifiedTodoDone.add(sessionId) && gate.shouldNotify(sessionId)) {
                    val s = AppLocale.strings
                    val notification = buildNotification(
                        channelId = OConnectorApp.INTERVENTION_CHANNEL_ID,
                        title = s.todoCompleted,
                        text = s.todoCompletedDesc.replace("%s", sessionDisplayName(sessionId, repository.activeSessionDirectory)),
                        sessionId = sessionId,
                        directory = repository.activeSessionDirectory,
                    )
                    try {
                        notificationManager.notify(tagTodo(sessionId), ID_TODO_DONE, notification)
                        Log.d(TAG, "Post todo-done notification: session ${sessionId.take(8)}")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping todo-done", e)
                    }
                }
            } else {
                // List has active items again — a later completion may re-notify
                notifiedTodoDone.remove(sessionId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkTodoCompletion failed: ${e.message}")
        }
    }

    // ─── Polling fallback (SSE gap) ─────────────────────────────────

    private suspend fun pollFallback() {
        val sessionId = repository.activeSessionId ?: return
        if (!repository.isConnected) return
        if (!gate.enabled.first()) return
        try {
            val messages = repository.getMessages(sessionId, repository.activeSessionDirectory, limit = 5)
            if (isSessionBlocked(sessionId, messages)) {
                if (notifiedBlocked.add(sessionId) && gate.shouldNotify(sessionId)) {
                    postBlocked(sessionId, repository.activeSessionDirectory)
                }
            } else {
                notifiedBlocked.remove(sessionId)
            }
            checkTodoCompletion(sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "Polling fallback failed: ${e.message}")
        }
    }

    private suspend fun isSessionBlocked(sessionId: String, messages: List<MessageInfo>): Boolean {
        if (messages.isEmpty()) return false
        val lastAssistant = messages.lastOrNull { it.info.role == "assistant" } ?: return false
        val completed = lastAssistant.info.time?.completed
        if (completed != null && completed > 0) return false
        return try {
            val session = repository.getSession(sessionId, repository.activeSessionDirectory)
            val sessionCompleted = session.time?.completed
            sessionCompleted != null && sessionCompleted > 0
        } catch (e: Exception) {
            false
        }
    }

    // ─── Shared helpers ─────────────────────────────────────────────

    /** Human-readable session name for notification bodies (title -> slug -> id tail). */
    private suspend fun sessionDisplayName(sessionId: String, directory: String?): String {
        return try {
            val session = repository.getSession(sessionId, directory)
            session?.title?.takeIf { it.isNotBlank() }
                ?: session?.slug?.takeIf { it.isNotBlank() }
                ?: sessionId.take(10)
        } catch (e: Exception) {
            sessionId.take(10)
        }
    }

    private fun buildNotification(channelId: String, title: String, text: String, sessionId: String, directory: String?, subtext: String? = null): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("sessionId", sessionId)
            if (!directory.isNullOrBlank()) putExtra("directory", directory)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, channelId)
            .setSmallIcon(com.opencode.remote.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subtext)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }
}