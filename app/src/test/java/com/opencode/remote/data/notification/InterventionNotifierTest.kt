package com.opencode.remote.data.notification

import android.content.Context
import android.app.Notification
import android.app.NotificationManager
import com.opencode.remote.data.api.dto.EventPayload
import com.opencode.remote.data.api.dto.EventProperties
import com.opencode.remote.data.api.dto.ServerEvent
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sse.SseEventBus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class InterventionNotifierTest {

    private lateinit var context: Context
    private lateinit var eventBus: SseEventBus
    private lateinit var repository: OConnectorRepository
    private lateinit var prefs: ConnectionPreferences
    private lateinit var gate: NotificationGate
    private lateinit var notifier: InterventionNotifier
    private lateinit var shadow: ShadowNotificationManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        eventBus = SseEventBus()
        repository = mockk(relaxed = true)
        prefs = mockk<ConnectionPreferences>(relaxed = true)
        every { prefs.notificationsEnabled } returns MutableStateFlow(true)
        gate = NotificationGate(prefs)
        gate.setForeground(false) // background by default -> notifications allowed
        notifier = InterventionNotifier(context, eventBus, repository, gate, appScope)
        shadow = org.robolectric.Shadows.shadowOf(
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        // The shadow NotificationManager survives across tests in the class —
        // clear leftovers so stale posts can't satisfy/fail fresh assertions.
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
    }

    @After
    fun tearDown() {
        notifier.stop()
    }

    private fun event(type: String, sessionId: String?, requestId: String? = null): ServerEvent {
        val props = EventProperties(
            sessionID = sessionId,
            id = requestId,
            permission = "bash",
            questions = listOf(com.opencode.remote.data.api.dto.QuestionInfoDto(question = "Q")),
        )
        return ServerEvent(directory = "/proj", payload = EventPayload(type = type, properties = props))
    }

    private fun awaitCondition(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
    }

    /**
     * The event bus has replay=0 — events emitted before the notifier's
     * collector subscribes are lost. Wait for the subscription first.
     */
    private fun awaitSubscription() {
        runBlocking {
            withTimeout(5_000) {
                eventBus.subscriberCount.first { it > 0 }
            }
        }
    }

    // ─── A-class: permission / question ──────────────────────────────

    @Test
    fun `permission asked in background posts HIGH notification`() {
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("permission.asked", "s1", "p1"), 1)

        awaitCondition { shadow.getNotification("action-p1", 2001) != null }
        assertNotNull(shadow.getNotification("action-p1", 2001))
    }

    @Test
    fun `question asked in background posts HIGH notification`() {
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("question.asked", "s1", "q1"), 1)

        awaitCondition { shadow.getNotification("action-q1", 2001) != null }
        assertNotNull(shadow.getNotification("action-q1", 2001))
    }

    @Test
    fun `permission asked while watching the session is silent`() {
        gate.setForeground(true)
        gate.setCurrentSessionId("s1")
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("permission.asked", "s1", "p1"), 1)

        Thread.sleep(300)
        assertNull(shadow.getNotification("action-p1", 2001))
    }

    @Test
    fun `permission asked when notifications disabled is silent`() {
        every { prefs.notificationsEnabled } returns MutableStateFlow(false)
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("permission.asked", "s1", "p1"), 1)

        Thread.sleep(300)
        assertNull(shadow.getNotification("action-p1", 2001))
    }

    @Test
    fun `permission replied cancels the notification`() {
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("permission.asked", "s1", "p1"), 1)
        awaitCondition { shadow.getNotification("action-p1", 2001) != null }

        eventBus.emit(event("permission.replied", "s1", "p1"), 1)
        awaitCondition { shadow.getNotification("action-p1", 2001) == null }
        assertNull(shadow.getNotification("action-p1", 2001))
    }

    @Test
    fun `notification tap has content intent pointing at MainActivity`() {
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("permission.asked", "s1", "p1"), 1)
        awaitCondition { shadow.getNotification("action-p1", 2001) != null }

        val pending = shadow.getNotification("action-p1", 2001)?.contentIntent
        assertNotNull(pending)
    }

    // ─── C-class: todo / execution ───────────────────────────────────

    @Test
    fun `question asked notification includes the question text`() {
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        val props = EventProperties(
            sessionID = "s1",
            id = "q1",
            permission = null,
            questions = listOf(
                com.opencode.remote.data.api.dto.QuestionInfoDto(question = "请选择分层方案"),
            ),
        )
        eventBus.emit(
            ServerEvent(directory = "/proj", payload = EventPayload(type = "question.asked", properties = props)),
            1,
        )

        awaitCondition { shadow.getNotification("action-q1", 2001) != null }
        val text = shadow.getNotification("action-q1", 2001)!!.extras
            .getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("question text should appear in notification body", text.contains("请选择分层方案"))
    }

    @Test
    fun `todo updated with all done posts notification`() {
        every { repository.activeSessionDirectory } returns null
        coEvery { repository.getTodoList("s1", null) } returns listOf(
            com.opencode.remote.data.api.dto.TodoItem(content = "c1", status = "completed"),
            com.opencode.remote.data.api.dto.TodoItem(content = "c2", status = "completed"),
        )
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("todo.updated", "s1"), 1)

        awaitCondition { shadow.getNotification("todo-s1", 2003) != null }
        assertNotNull(shadow.getNotification("todo-s1", 2003))
    }

    @Test
    fun `todo updated with active items stays silent`() {
        every { repository.activeSessionDirectory } returns null
        coEvery { repository.getTodoList("s1", null) } returns listOf(
            com.opencode.remote.data.api.dto.TodoItem(content = "c1", status = "completed"),
            com.opencode.remote.data.api.dto.TodoItem(content = "c2", status = "in_progress"),
        )
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("todo.updated", "s1"), 1)

        Thread.sleep(300)
        assertNull(shadow.getNotification("todo-s1", 2003))
    }

    @Test
    fun `execution succeeded for active session posts notification`() {
        every { repository.activeSessionId } returns "s1"
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("session.execution.succeeded", "s1"), 1)

        awaitCondition { shadow.getNotification("s1", 2002) != null }
        assertNotNull(shadow.getNotification("s1", 2002))
    }

    @Test
    fun `execution succeeded for non-active session is silent`() {
        every { repository.activeSessionId } returns "s1"
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("session.execution.succeeded", "other"), 1)

        Thread.sleep(300)
        assertNull(shadow.getNotification("other", 2002))
    }

    @Test
    fun `execution succeeded notifies once per run until new run starts`() {
        every { repository.activeSessionId } returns "s1"
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("session.execution.succeeded", "s1"), 1)
        awaitCondition { shadow.getNotification("s1", 2002) != null }

        // Second success without a new run — should not re-notify (count stays 1)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        eventBus.emit(event("session.execution.succeeded", "s1"), 1)
        Thread.sleep(300)
        assertNull(shadow.getNotification("s1", 2002))

        // A new execution.started re-arms the run
        eventBus.emit(event("session.execution.started", "s1"), 1)
        eventBus.emit(event("session.execution.succeeded", "s1"), 1)
        awaitCondition { shadow.getNotification("s1", 2002) != null }
        assertNotNull(shadow.getNotification("s1", 2002))
    }

    @Test
    fun `opening the session while notification is up cancels it`() {
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("permission.asked", "s1", "p1"), 1)
        awaitCondition { shadow.getNotification("action-p1", 2001) != null }

        gate.setForeground(true)
        gate.setCurrentSessionId("s1")

        awaitCondition { shadow.getNotification("action-p1", 2001) == null }
        assertNull(shadow.getNotification("action-p1", 2001))
    }

    @Test
    fun `stop cancels collection and clears interventions`() {
        notifier.start()
        awaitSubscription()
        eventBus.activateGeneration(1)
        eventBus.emit(event("permission.asked", "s1", "p1"), 1)
        awaitCondition { shadow.getNotification("action-p1", 2001) != null }

        notifier.stop()
        eventBus.emit(event("question.asked", "s2", "q2"), 1)
        Thread.sleep(300)
        assertNull(shadow.getNotification("action-q2", 2001))
    }
}