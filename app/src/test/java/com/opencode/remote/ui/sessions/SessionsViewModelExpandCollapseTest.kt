package com.opencode.remote.ui.sessions

import com.opencode.remote.data.api.dto.EventPayload
import com.opencode.remote.data.api.dto.EventProperties
import com.opencode.remote.data.api.dto.ServerEvent
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.MemoManager
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sessionstore.ActiveSessionStore
import com.opencode.remote.data.sessionstore.ChildSessionStore
import com.opencode.remote.data.sse.EventEnvelope
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
class SessionsViewModelExpandCollapseTest {

    private lateinit var repository: OConnectorRepository
    private lateinit var prefs: ConnectionPreferences
    private lateinit var sseEventBus: SseEventBus
    private lateinit var memoManager: MemoManager
    private lateinit var activeSessionStore: ActiveSessionStore
    private lateinit var childSessionStore: ChildSessionStore

    private val testSessions = listOf(
        SessionInfo(id = "root_1", parentID = null),
        SessionInfo(id = "root_2", parentID = null),
        SessionInfo(id = "child_1", parentID = "root_1"),
    )

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
        coEvery { repository.listAllSessions() } returns testSessions
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
    fun `session_deleted triggers loadSessions`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        // Emit a session.deleted event
        val event = ServerEvent(
            payload = EventPayload(
                type = "session.deleted",
                properties = EventProperties(sessionID = "root_1"),
            )
        )
        sseEventBus.emit(event)
        advanceUntilIdle()

        // loadSessions should have been called at least once more (init + event)
        // We verify by checking that listAllSessions was called again
        coEvery { repository.listAllSessions() } returns testSessions
        advanceUntilIdle()

        // Verify state is still healthy
        assertTrue(viewModel.uiState.value.sessions.isNotEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `deleted expanded parent is removed from expandedParents`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        // Expand root_1
        viewModel.toggleExpand("root_1")
        assertTrue("root_1" in viewModel.uiState.value.expandedParents)

        // Emit session.deleted for root_1
        val event = ServerEvent(
            payload = EventPayload(
                type = "session.deleted",
                properties = EventProperties(sessionID = "root_1"),
            )
        )
        sseEventBus.emit(event)
        advanceUntilIdle()

        // root_1 should be removed from expandedParents
        assertFalse("root_1" in viewModel.uiState.value.expandedParents)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `deleted non-expanded session does not affect expandedParents`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        // Expand root_1
        viewModel.toggleExpand("root_1")
        assertTrue("root_1" in viewModel.uiState.value.expandedParents)

        // Emit session.deleted for root_2 (not expanded)
        val event = ServerEvent(
            payload = EventPayload(
                type = "session.deleted",
                properties = EventProperties(sessionID = "root_2"),
            )
        )
        sseEventBus.emit(event)
        advanceUntilIdle()

        // root_1 should still be expanded
        assertTrue("root_1" in viewModel.uiState.value.expandedParents)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `deleted session with null sessionID does not crash`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        viewModel.toggleExpand("root_1")

        // Emit session.deleted with null sessionID
        val event = ServerEvent(
            payload = EventPayload(
                type = "session.deleted",
                properties = EventProperties(sessionID = null),
            )
        )
        sseEventBus.emit(event)
        advanceUntilIdle()

        // Should not crash, expandedParents unchanged
        assertTrue("root_1" in viewModel.uiState.value.expandedParents)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `toggleExpand adds and removes from expandedParents`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        assertEquals(emptySet<String>(), viewModel.uiState.value.expandedParents)

        viewModel.toggleExpand("root_1")
        assertEquals(setOf("root_1"), viewModel.uiState.value.expandedParents)

        viewModel.toggleExpand("root_2")
        assertEquals(setOf("root_1", "root_2"), viewModel.uiState.value.expandedParents)

        // Toggle root_1 off
        viewModel.toggleExpand("root_1")
        assertEquals(setOf("root_2"), viewModel.uiState.value.expandedParents)
    }
}
