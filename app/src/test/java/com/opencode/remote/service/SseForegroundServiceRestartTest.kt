package com.opencode.remote.service

import android.content.Context
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SseForegroundServiceRestartTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        // Mock the companion object to prevent actual service start/stop
        mockkObject(SseForegroundService)
    }

    @After
    fun tearDown() {
        unmockkObject(SseForegroundService)
        clearAllMocks()
        // Reset debounce timer via reflection
        val field = SseForegroundService::class.java.getDeclaredField("lastRestartTime")
        field.isAccessible = true
        field.set(null, 0L)
    }

    @Test
    fun `restart with interval greater than 3s executes`() {
        // Set lastRestartTime to well in the past
        val field = SseForegroundService::class.java.getDeclaredField("lastRestartTime")
        field.isAccessible = true
        field.set(null, System.currentTimeMillis() - 5000)

        every { SseForegroundService.stop(any()) } returns Unit
        every { SseForegroundService.start(any(), any()) } returns Unit

        SseForegroundService.restart(context, 1)

        verify { SseForegroundService.stop(context) }
        verify { SseForegroundService.start(context, 1) }
    }

    @Test
    fun `restart with interval less than 3s is debounced`() {
        // Set lastRestartTime to very recent
        val field = SseForegroundService::class.java.getDeclaredField("lastRestartTime")
        field.isAccessible = true
        field.set(null, System.currentTimeMillis() - 1000) // 1 second ago

        every { SseForegroundService.stop(any()) } returns Unit
        every { SseForegroundService.start(any(), any()) } returns Unit

        SseForegroundService.restart(context, 2)

        // Should NOT have called stop or start because it's debounced
        verify(exactly = 0) { SseForegroundService.stop(any()) }
        verify(exactly = 0) { SseForegroundService.start(any(), any()) }
    }

    @Test
    fun `restart updates lastRestartTime on execution`() {
        val field = SseForegroundService::class.java.getDeclaredField("lastRestartTime")
        field.isAccessible = true
        field.set(null, 0L)

        every { SseForegroundService.stop(any()) } returns Unit
        every { SseForegroundService.start(any(), any()) } returns Unit

        val before = System.currentTimeMillis()
        SseForegroundService.restart(context, 1)

        val lastRestart = field.getLong(null)
        assert(lastRestart >= before)
    }

    @Test
    fun `start passes generation in intent`() {
        // Verify that start accepts a generation parameter without error
        every { context.startForegroundService(any()) } returns null

        SseForegroundService.start(context, 42)

        verify { context.startForegroundService(any()) }
    }
}
