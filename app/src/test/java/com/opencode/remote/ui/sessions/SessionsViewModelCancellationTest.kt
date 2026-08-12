package com.opencode.remote.ui.sessions

import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.MemoManager
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sessionstore.ActiveSessionStore
import com.opencode.remote.data.sessionstore.ChildSessionStore
import com.opencode.remote.data.sse.SseEventBus
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SessionsViewModelCancellationTest {

    private lateinit var repository: OConnectorRepository
    private lateinit var prefs: ConnectionPreferences
    private lateinit var sseEventBus: SseEventBus
    private lateinit var memoManager: MemoManager
    private lateinit var activeSessionStore: ActiveSessionStore
    private lateinit var childSessionStore: ChildSessionStore

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        sseEventBus = SseEventBus()
        memoManager = mockk(relaxed = true)
        activeSessionStore = ActiveSessionStore()
        childSessionStore = ChildSessionStore()

        every { prefs.darkMode } returns flowOf(false)
        every { prefs.hideChildSessions } returns flowOf(false)
        coEvery { repository.getCurrentProject() } returns mockk(relaxed = true)
        every { repository.getCurrentServerName() } returns null

        SessionsViewModel.searchDebounceMs = 0L
    }

    @After
    fun tearDown() {
        SessionsViewModel.searchDebounceMs = 300L
        clearAllMocks()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelled in-flight loadSessions does not set error`() = runTest {
        // First call suspends indefinitely; second loadSessions cancels it.
        val gate = CompletableDeferred<List<com.opencode.remote.data.api.dto.SessionInfo>>()
        coEvery { repository.listAllSessions() } coAnswers {
            gate.await()
        }

        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore)
        advanceUntilIdle()

        // First load hangs in listAllSessions()
        viewModel.loadSessions()
        advanceTimeBy(100)

        // Second load cancels the first one mid-flight
        viewModel.loadSessions()
        advanceUntilIdle()

        // The cancelled (first) load must NOT surface an error
        assertNull("cancelled load should not set error", viewModel.uiState.value.error)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a real failure still surfaces error`() = runTest {
        coEvery { repository.listAllSessions() } throws RuntimeException("boom")

        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore)
        advanceUntilIdle()
        viewModel.loadSessions()
        advanceUntilIdle()

        val error = viewModel.uiState.value.error
        assertTrue("expected load error to be shown, was $error", error?.contains("boom") == true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a successful load that follows clears pending error`() = runTest {
        // First attempt fails, then a successful reload overwrites the error.
        var failFirst = true
        coEvery { repository.listAllSessions() } coAnswers {
            if (failFirst) {
                failFirst = false
                throw RuntimeException("boom")
            }
            emptyList()
        }

        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore)
        advanceUntilIdle()
        // The init load throws "boom" → error must be surfacing.
        assertTrue(viewModel.uiState.value.error?.contains("boom") == true)

        // A subsequent successful reload clears the pending error.
        viewModel.loadSessions()
        advanceUntilIdle()
        assertNull("successful reload should clear error", viewModel.uiState.value.error)
    }
}