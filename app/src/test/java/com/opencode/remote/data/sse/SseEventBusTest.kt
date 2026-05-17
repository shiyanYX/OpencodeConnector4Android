package com.opencode.remote.data.sse

import app.cash.turbine.test
import com.opencode.remote.data.api.dto.EventPayload
import com.opencode.remote.data.api.dto.EventProperties
import com.opencode.remote.data.api.dto.ServerEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SseEventBusTest {

    private lateinit var eventBus: SseEventBus

    private fun makeEvent(type: String = "test") = ServerEvent(
        payload = EventPayload(type = type, properties = EventProperties())
    )

    @Before
    fun setUp() {
        eventBus = SseEventBus()
    }

    @Test
    fun `emit with generation less than activeGeneration discards event`() = runTest {
        // Activate generation 5
        eventBus.activateGeneration(5)

        // Emit with generation 3 (stale)
        eventBus.emit(makeEvent("stale"), 3)

        // Should be discarded — no events in the flow
        eventBus.events.test {
            // No items expected (stale was discarded)
            expectNoEvents()
        }
    }

    @Test
    fun `emit with generation equal to activeGeneration passes through`() = runTest {
        eventBus.activateGeneration(5)

        eventBus.events.test {
            eventBus.emit(makeEvent("current"), 5)
            val envelope = awaitItem()
            assertEquals(5L, envelope.generation)
            assertEquals("current", envelope.event.payload.type)
        }
    }

    @Test
    fun `emit with generation greater than activeGeneration passes through`() = runTest {
        eventBus.activateGeneration(5)

        eventBus.events.test {
            eventBus.emit(makeEvent("future"), 10)
            val envelope = awaitItem()
            assertEquals(10L, envelope.generation)
            assertEquals("future", envelope.event.payload.type)
        }
    }

    @Test
    fun `activateGeneration updates the filter threshold`() = runTest {
        // Initially activeGeneration = 0, so gen=1 passes
        eventBus.events.test {
            eventBus.emit(makeEvent("first"), 1)
            assertEquals(1L, awaitItem().generation)

            // Now activate generation 5
            eventBus.activateGeneration(5)

            // gen=3 is now stale
            eventBus.emit(makeEvent("stale"), 3)
            expectNoEvents()

            // gen=5 passes
            eventBus.emit(makeEvent("current"), 5)
            assertEquals(5L, awaitItem().generation)
        }
    }

    @Test
    fun `backward compatible emit uses active generation`() = runTest {
        eventBus.activateGeneration(7)

        eventBus.events.test {
            eventBus.emit(makeEvent("compat"))
            val envelope = awaitItem()
            assertEquals(7L, envelope.generation)
            assertEquals("compat", envelope.event.payload.type)
        }
    }

    @Test
    fun `backward compatible emit with default generation 0 passes`() = runTest {
        // activeGeneration starts at 0, so emit() with gen=0 should pass
        eventBus.events.test {
            eventBus.emit(makeEvent("default"))
            val envelope = awaitItem()
            assertEquals(0L, envelope.generation)
        }
    }
}
