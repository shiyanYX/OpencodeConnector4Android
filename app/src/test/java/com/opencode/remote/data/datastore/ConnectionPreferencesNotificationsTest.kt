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
class ConnectionPreferencesNotificationsTest {

    private lateinit var prefs: ConnectionPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = ConnectionPreferences(context, ServerManager(context, Json { ignoreUnknownKeys = true }))
        // The DataStore instance is shared per test class — start each test clean
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    @Test
    fun `notifications enabled defaults to true`() = runTest {
        assertEquals(true, prefs.notificationsEnabled.first())
    }

    @Test
    fun `save notifications disabled then reads false`() = runTest {
        prefs.saveNotificationsEnabled(false)
        assertEquals(false, prefs.notificationsEnabled.first())
    }

    @Test
    fun `save notifications enabled then reads true`() = runTest {
        prefs.saveNotificationsEnabled(false)
        prefs.saveNotificationsEnabled(true)
        assertEquals(true, prefs.notificationsEnabled.first())
    }

    @Test
    fun `toggling does not affect other settings`() = runTest {
        prefs.saveDarkMode(true)
        prefs.saveNotificationsEnabled(false)
        assertEquals(true, prefs.darkMode.first())
        assertEquals(false, prefs.notificationsEnabled.first())
    }
}