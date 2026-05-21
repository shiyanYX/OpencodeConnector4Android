package com.opencode.remote.ui.sessions

import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.api.dto.SessionTime
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.MemoManager
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sessionstore.ActiveSessionStore
import com.opencode.remote.data.sse.SseEventBus
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SessionsViewModelSearchTest {

    private lateinit var repository: OConnectorRepository
    private lateinit var prefs: ConnectionPreferences
    private lateinit var sseEventBus: SseEventBus
    private lateinit var memoManager: MemoManager
    private lateinit var activeSessionStore: ActiveSessionStore

    private val testSessions = listOf(
        SessionInfo(id = "1", title = "Fix login bug", time = SessionTime(updated = 1L)),
        SessionInfo(id = "2", title = "Add search feature", time = SessionTime(updated = 2L)),
        SessionInfo(id = "3", title = "Update README", time = SessionTime(updated = 3L)),
        SessionInfo(id = "4", title = "Refactor login flow", time = SessionTime(updated = 4L)),
        SessionInfo(id = "5", title = null, time = SessionTime(updated = 5L)),
    )

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        sseEventBus = SseEventBus()
        memoManager = mockk(relaxed = true)
        activeSessionStore = ActiveSessionStore()

        every { prefs.darkMode } returns flowOf(false)
        every { prefs.hideChildSessions } returns flowOf(false)
        coEvery { repository.listAllSessions() } returns testSessions
        coEvery { repository.getCurrentProject() } returns mockk(relaxed = true)
        every { repository.getCurrentServerName() } returns null

        // Disable debounce for synchronous test execution
        SessionsViewModel.searchDebounceMs = 0L
    }

    @After
    fun tearDown() {
        SessionsViewModel.searchDebounceMs = 300L
        clearAllMocks()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `debounce - rapid queries only last filter applied`() = runTest {
        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore)
        advanceUntilIdle()

        // Fire 5 rapid queries — each cancels previous, only last filter applies
        viewModel.setSearchQuery("fix")
        viewModel.setSearchQuery("login")
        viewModel.setSearchQuery("search")
        viewModel.setSearchQuery("update")
        viewModel.setSearchQuery("readme")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("readme", state.searchQuery)
        assertTrue(state.isSearching)
        assertEquals(1, state.sessions.size)
        assertEquals("Update README", state.sessions[0].title)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `debounce - intermediate queries cancelled before filter`() = runTest {
        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore)
        advanceUntilIdle()

        viewModel.setSearchQuery("login")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.sessions.size) // "Fix login bug" + "Refactor login flow"

        viewModel.setSearchQuery("fix")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("fix", state.searchQuery)
        assertTrue(state.isSearching)
        assertEquals(1, state.sessions.size)
        assertEquals("Fix login bug", state.sessions[0].title)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `clear query restores all sessions`() = runTest {
        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore)
        advanceUntilIdle()

        viewModel.setSearchQuery("login")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.sessions.size)

        viewModel.setSearchQuery("")
        val state = viewModel.uiState.value
        assertFalse(state.isSearching)
        assertEquals("", state.searchQuery)
        assertEquals(5, state.sessions.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `case-insensitive search`() = runTest {
        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore)
        advanceUntilIdle()

        viewModel.setSearchQuery("LOGIN")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSearching)
        assertEquals(2, state.sessions.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `search with no matches returns empty list`() = runTest {
        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore)
        advanceUntilIdle()

        viewModel.setSearchQuery("zzz_nonexistent")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSearching)
        assertEquals(0, state.sessions.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `query with only whitespace treated as cleared`() = runTest {
        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore)
        advanceUntilIdle()

        viewModel.setSearchQuery("login")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.sessions.size)

        viewModel.setSearchQuery("   ")
        val state = viewModel.uiState.value
        assertFalse(state.isSearching)
        assertEquals(5, state.sessions.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `isSearching set immediately on non-blank query`() = runTest {
        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore)
        advanceUntilIdle()

        // Before search
        assertFalse(viewModel.uiState.value.isSearching)

        viewModel.setSearchQuery("test")
        // isSearching should be true immediately (synchronous state update)
        assertTrue(viewModel.uiState.value.isSearching)
        assertEquals("test", viewModel.uiState.value.searchQuery)
    }
}
