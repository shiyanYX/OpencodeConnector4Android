package com.opencode.remote.data.datastore

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ConnectionPreferencesKeepAliveTest {

    private lateinit var prefs: ConnectionPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = ConnectionPreferences(context, ServerManager(context, Json { ignoreUnknownKeys = true }))
        // The DataStore instance is shared per test class — start each test clean
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    @Test
    fun `keep alive defaults to true`() = runTest {
        assertEquals(true, prefs.keepAlive.first())
    }

    @Test
    fun `save keep alive disabled then reads false`() = runTest {
        prefs.saveKeepAlive(false)
        assertEquals(false, prefs.keepAlive.first())
    }

    @Test
    fun `save keep alive enabled then reads true`() = runTest {
        prefs.saveKeepAlive(false)
        prefs.saveKeepAlive(true)
        assertEquals(true, prefs.keepAlive.first())
    }

    @Test
    fun `toggling does not affect other settings`() = runTest {
        prefs.saveNotificationsEnabled(false)
        prefs.saveKeepAlive(false)
        assertEquals(false, prefs.notificationsEnabled.first())
        assertEquals(false, prefs.keepAlive.first())
    }
}
