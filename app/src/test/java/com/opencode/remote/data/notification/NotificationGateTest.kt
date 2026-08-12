package com.opencode.remote.data.notification

import com.opencode.remote.data.datastore.ConnectionPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGateTest {

    private fun gateWith(enabled: Boolean): Pair<NotificationGate, MutableStateFlow<Boolean>> {
        val prefs = mockk<ConnectionPreferences>(relaxed = true)
        val enabledFlow = MutableStateFlow(enabled)
        every { prefs.notificationsEnabled } returns enabledFlow
        return NotificationGate(prefs) to enabledFlow
    }

    @Test
    fun `shouldNotify returns false when notifications disabled`() = runTest {
        val (gate, _) = gateWith(false)
        gate.setForeground(false)
        assertFalse(gate.shouldNotify("s1"))
    }

    @Test
    fun `shouldNotify true when background`() = runTest {
        val (gate, _) = gateWith(true)
        gate.setForeground(false)
        gate.setCurrentSessionId("s1")
        // Even the open session notifies while backgrounded
        assertTrue(gate.shouldNotify("s1"))
    }

    @Test
    fun `shouldNotify false when foreground and viewing the same session`() = runTest {
        val (gate, _) = gateWith(true)
        gate.setForeground(true)
        gate.setCurrentSessionId("s1")
        assertFalse(gate.shouldNotify("s1"))
    }

    @Test
    fun `shouldNotify true when foreground but a different session is open`() = runTest {
        val (gate, _) = gateWith(true)
        gate.setForeground(true)
        gate.setCurrentSessionId("s1")
        assertTrue(gate.shouldNotify("s2"))
    }

    @Test
    fun `shouldNotify true when foreground and no session open`() = runTest {
        val (gate, _) = gateWith(true)
        gate.setForeground(true)
        gate.setCurrentSessionId(null)
        assertTrue(gate.shouldNotify("s1"))
    }

    @Test
    fun `toggle off suppresses notifications even when previously enabled`() = runTest {
        val (gate, enabledFlow) = gateWith(true)
        enabledFlow.value = false
        gate.setForeground(false)
        assertFalse(gate.shouldNotify("s1"))
    }

    @Test
    fun `foreground and session state are tracked`() {
        val (gate, _) = gateWith(true)
        assertEquals(true, gate.isForeground.value)
        gate.setForeground(false)
        assertEquals(false, gate.isForeground.value)
        gate.setCurrentSessionId("abc")
        assertEquals("abc", gate.currentSessionId.value)
    }
}