package com.opencode.remote.data.network

import android.content.Context
import android.net.ConnectivityManager
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NetworkMonitorTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkMonitor: NetworkMonitor

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        networkMonitor = NetworkMonitor(context)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `start does not throw`() {
        networkMonitor.start()
        // Verify no exception thrown
    }

    @Test
    fun `stop does not throw`() {
        networkMonitor.stop()
        // Verify no exception thrown
    }

    @Test
    fun `isConnected reflects initial state`() {
        every { connectivityManager.activeNetwork } returns null
        val monitor = NetworkMonitor(context)
        assertEquals(false, monitor.isConnected.value)
    }

    @Test
    fun `onNetworkAvailable callback fires`() {
        var callbackFired = false
        networkMonitor.onNetworkAvailable = {
            callbackFired = true
        }
        networkMonitor.onNetworkAvailable?.invoke()
        assertEquals(true, callbackFired)
    }

    @Test
    fun `onNetworkAvailable can be set and invoked`() {
        var result = ""
        networkMonitor.onNetworkAvailable = { result = "fired" }
        networkMonitor.onNetworkAvailable?.invoke()
        assertEquals("fired", result)
    }

    @Test
    fun `onNetworkAvailable null by default`() {
        assertEquals(null, networkMonitor.onNetworkAvailable)
    }
}
