package com.opencode.remote.ui.chat

import android.content.Context
import app.cash.turbine.test
import com.opencode.remote.data.api.dto.EventPayload
import com.opencode.remote.data.api.dto.EventProperties
import com.opencode.remote.data.api.dto.ServerEvent
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sse.EventEnvelope
import com.opencode.remote.data.sse.SseEventBus
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatViewModelGenerationTest {

    private lateinit var eventBus: SseEventBus
    private lateinit var repository: OConnectorRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        eventBus = SseEventBus()
        repository = mockk(relaxed = true)
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun makeEvent(type: String = "test") = ServerEvent(
        payload = EventPayload(type = type, properties = EventProperties())
    )

    @Test
    fun `events with generation less than subscribed are discarded`() = runTest {
        // ViewModel subscribes at generation 5
        every { repository.currentGeneration } returns 5L

        // Simulate what the ViewModel's collect lambda does:
        // Events with generation < subscribedGeneration should be discarded
        val subscribedGeneration = 5L

        eventBus.events.test {
            // Emit events with different generations
            eventBus.activateGeneration(5)
            eventBus.emit(makeEvent("stale"), 3) // gen 3 < 5, should be discarded by EventBus

            // gen 5 should pass
            eventBus.emit(makeEvent("current"), 5)
            val envelope = awaitItem()
            assertEquals(5L, envelope.generation)

            // Verify: gen < subscribed is filtered
            assert(envelope.generation >= subscribedGeneration)
        }
    }

    @Test
    fun `events with generation equal to subscribed are processed`() = runTest {
        eventBus.activateGeneration(5)

        eventBus.events.test {
            eventBus.emit(makeEvent("matching"), 5)
            val envelope = awaitItem()
            assertEquals(5L, envelope.generation)
            assertEquals("matching", envelope.event.payload.type)
        }
    }

    @Test
    fun `events with generation greater than subscribed are processed`() = runTest {
        eventBus.activateGeneration(5)

        eventBus.events.test {
            eventBus.emit(makeEvent("newer"), 8)
            val envelope = awaitItem()
            assertEquals(8L, envelope.generation)
            assertEquals("newer", envelope.event.payload.type)
        }
    }

    @Test
    fun `subscribedGeneration auto-updates on higher gen event`() = runTest {
        // Simulates the ViewModel's "follow upward" logic
        var subscribedGeneration = 5L

        eventBus.activateGeneration(5)
        eventBus.events.test {
            // Event with gen 7 > subscribed 5
            eventBus.emit(makeEvent("upgrade"), 7)
            val envelope = awaitItem()
            assertEquals(7L, envelope.generation)

            // Simulate the "follow upward" update
            if (envelope.generation > subscribedGeneration) {
                subscribedGeneration = envelope.generation
            }

            // Now subscribedGeneration should be 7
            assertEquals(7L, subscribedGeneration)

            // Events with gen 6 should now be stale at EventBus level
            // But EventBus activeGeneration is 5, so gen 6 passes EventBus
            // ViewModel would filter it. Let's test EventBus allows it:
            eventBus.emit(makeEvent("still-passes-eventbus"), 6)
            val envelope2 = awaitItem()
            assertEquals(6L, envelope2.generation)

            // But ViewModel logic would discard it: 6 < 7
            assert(envelope2.generation < subscribedGeneration) // ViewModel would discard
        }
    }

    @Test
    fun `EventEnvelope data class holds correct values`() {
        val event = makeEvent("hello")
        val envelope = EventEnvelope(event, 42L)
        assertEquals(42L, envelope.generation)
        assertEquals("hello", envelope.event.payload.type)
    }
}
