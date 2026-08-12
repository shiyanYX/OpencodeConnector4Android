package com.opencode.remote.data.notification

import android.content.Context
import android.app.NotificationManager
import android.os.Looper
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Watchdog delivery-coupling: the intervention notification is only auto-
 * dismissed AFTER it was actually delivered (Doze may delay delivery past
 * the watchdog period, which previously erased notifications users never saw).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class InterventionNotifierWatchdogTest {

    private lateinit var context: Context
    private lateinit var eventBus: SseEventBus
    private lateinit var repository: OConnectorRepository
    private lateinit var prefs: ConnectionPreferences
    private lateinit var gate: NotificationGate
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadow: org.robolectric.shadows.ShadowNotificationManager

    private lateinit var mainScope: CoroutineScope
    private lateinit var notifier: InterventionNotifier

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        eventBus = SseEventBus()
        repository = mockk(relaxed = true)
        prefs = mockk<ConnectionPreferences>(relaxed = true)
        every { prefs.notificationsEnabled } returns MutableStateFlow(true)
        gate = NotificationGate(prefs)
        gate.setForeground(false) // background by default
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        shadow = Shadows.shadowOf(notificationManager)
// Main dispatcher: watchdog delay() then runs on the shadow main looper,
        // whose clock we advance manually (Robolectric PAUSED looper mode).
        // Dispatchers.Main is already bound to the Android main looper here.
        mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        notifier = InterventionNotifier(context, eventBus, repository, gate, mainScope)
    }

@After
    fun tearDown() {
        notifier.stop()
        mainScope.cancel()
        notificationManager.cancelAll()
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

    /** Drain all queued main-looper work. */
    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /** Advance the shadow main-looper clock by the given duration. */
    private fun idleFor(ms: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
        // Cancel/notify chats happen in the same looper task — give them a beat.
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun postAndSettle() {
        notifier.start()
        // The collector coroutine is launched on Dispatchers.Main — flush the
        // launch through the main looper before waiting for the subscription.
        idle()
        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                eventBus.subscriberCount.first { it > 0 }
            }
        }
        eventBus.activateGeneration(1)
        eventBus.emit(event("permission.asked", "s1", "p1"), 1)
        idle()
        // The collector is launched on the main dispatcher — poll with idle until shown.
        var attempts = 0
        while (shadow.getNotification("action-p1", 2001) == null && attempts < 200) {
            idle()
            Thread.sleep(10)
            attempts++
        }
    }

    @Test
    fun `delivered notification is cancelled after one watchdog period`() {
        postAndSettle()
        assertNotNull(shadow.getNotification("action-p1", 2001))

        // Watchdog loop: delay(120s) -> delivered=true -> cancelAction + break.
        idleFor(120_000 + 1_000)

        assertNull(shadow.getNotification("action-p1", 2001))
    }

    @Test
    fun `undelivered notification is kept until it finally reaches the user`() {
        postAndSettle()
        val delivered = shadow.getNotification("action-p1", 2001)!!
        assertNotNull(delivered)

        // Simulate the notification being dropped from the delivered set while
        // still queued (Doze window): the watchdog must NOT cancel it.
        notificationManager.cancelAll()
        idleFor(120_000 + 1_000)

        // The notification is gone from the visible set (dropped by the system),
        // but our watchdog kept waiting — repost it as if the Doze window opened.
        notificationManager.notify("action-p1", 2001, delivered)
        idleFor(120_000 + 1_000)

        // Now that it is actually delivered, the watchdog dismisses it.
        assertNull(shadow.getNotification("action-p1", 2001))
    }
}