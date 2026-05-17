package com.opencode.remote.data.sse

import com.opencode.remote.data.api.dto.ServerEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class EventEnvelope(val event: ServerEvent, val generation: Long)

@Singleton
class SseEventBus @Inject constructor() {
    @Volatile private var activeGeneration: Long = 0L

    private val _events = MutableSharedFlow<EventEnvelope>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<EventEnvelope> = _events.asSharedFlow()

    fun emit(event: ServerEvent, generation: Long) {
        if (generation < activeGeneration) {
            android.util.Log.d("SseEventBus", "Discarding stale event gen=$generation (active=$activeGeneration)")
            return
        }
        _events.tryEmit(EventEnvelope(event, generation))
    }

    fun emit(event: ServerEvent) {
        // Backward-compatible overload — uses active generation
        _events.tryEmit(EventEnvelope(event, activeGeneration))
    }

    fun activateGeneration(generation: Long) {
        activeGeneration = generation
    }
}
