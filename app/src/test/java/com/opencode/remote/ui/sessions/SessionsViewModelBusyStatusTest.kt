package com.opencode.remote.ui.sessions

import com.opencode.remote.data.api.dto.EventPayload
import com.opencode.remote.data.api.dto.EventProperties
import com.opencode.remote.data.api.dto.ServerEvent
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.api.dto.StatusData
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.MemoManager
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sessionstore.ActiveSessionStore
import com.opencode.remote.data.sessionstore.ChildSessionStore
import com.opencode.remote.data.sessionstore.SessionStatus
import com.opencode.remote.data.sse.SseEventBus
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
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
class SessionsViewModelBusyStatusTest {

    private lateinit var repository: OConnectorRepository
    private lateinit var prefs: ConnectionPreferences
    private lateinit var sseEventBus: SseEventBus
    private lateinit var memoManager: MemoManager
    private lateinit var activeSessionStore: ActiveSessionStore
    private lateinit var childSessionStore: ChildSessionStore

    private val testSessions = listOf(
        SessionInfo(id = "ses_1", parentID = null),
        SessionInfo(id = "ses_2", parentID = null),
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
    fun `session_status busy sets BUSY dot for session`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        sseEventBus.emit(ServerEvent(
            payload = EventPayload(
                type = "session.status",
                properties = EventProperties(sessionID = "root_1", status = StatusData(type = "busy")),
            )
        ))
        advanceUntilIdle()

        assertEquals(SessionStatus.BUSY, viewModel.sessionStatusMap.value["root_1"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session_status idle sets store to idle`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        // Pre-populate busy via the status event, then flip to idle.
        sseEventBus.emit(ServerEvent(
            payload = EventPayload(
                type = "session.status",
                properties = EventProperties(sessionID = "root_1", status = StatusData(type = "busy")),
            )
        ))
        advanceUntilIdle()
        assertEquals(SessionStatus.BUSY, activeSessionStore.statusMap.value["root_1"])

        sseEventBus.emit(ServerEvent(
            payload = EventPayload(
                type = "session.status",
                properties = EventProperties(sessionID = "root_1", status = StatusData(type = "idle")),
            )
        ))
        advanceUntilIdle()
        assertEquals(SessionStatus.IDLE, activeSessionStore.statusMap.value["root_1"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session_idle event marks session idle`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        activeSessionStore.updateStatus("root_1", SessionStatus.BUSY)

        sseEventBus.emit(ServerEvent(
            payload = EventPayload(
                type = "session.idle",
                properties = EventProperties(sessionID = "root_1"),
            )
        ))
        advanceUntilIdle()

        assertEquals(SessionStatus.IDLE, activeSessionStore.statusMap.value["root_1"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session status event with unknown type falls back to idle`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        sseEventBus.emit(ServerEvent(
            payload = EventPayload(
                type = "session.status",
                properties = EventProperties(sessionID = "root_2", status = StatusData(type = "weird")),
            )
        ))
        advanceUntilIdle()

        assertEquals(SessionStatus.IDLE, activeSessionStore.statusMap.value["root_2"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session_execution_started sets store to busy`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        sseEventBus.emit(ServerEvent(
            payload = EventPayload(
                type = "session.execution.started",
                properties = EventProperties(sessionID = "root_1"),
            )
        ))
        advanceUntilIdle()

        assertEquals(SessionStatus.BUSY, activeSessionStore.statusMap.value["root_1"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session_execution_succeeded sets store IDLE`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        activeSessionStore.updateStatus("root_1", SessionStatus.BUSY)

        sseEventBus.emit(ServerEvent(
            payload = EventPayload(
                type = "session.execution.succeeded",
                properties = EventProperties(sessionID = "root_1"),
            )
        ))
        advanceUntilIdle()

        assertEquals(SessionStatus.IDLE, activeSessionStore.statusMap.value["root_1"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session_execution_interrupted sets store IDLE`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        activeSessionStore.updateStatus("root_2", SessionStatus.BUSY)

        sseEventBus.emit(ServerEvent(
            payload = EventPayload(
                type = "session.execution.interrupted",
                properties = EventProperties(sessionID = "root_2"),
            )
        ))
        advanceUntilIdle()

        assertEquals(SessionStatus.IDLE, activeSessionStore.statusMap.value["root_2"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `session_deleted removes status entry from store`() = runTest {
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        advanceUntilIdle()

        activeSessionStore.updateStatus("root_1", SessionStatus.BUSY)
        activeSessionStore.updateStatus("root_2", SessionStatus.IDLE)

        sseEventBus.emit(ServerEvent(
            payload = EventPayload(
                type = "session.deleted",
                properties = EventProperties(sessionID = "root_1"),
            )
        ))
        advanceUntilIdle()

        val map = activeSessionStore.statusMap.value
        assertFalse(map.containsKey("root_1"))
        assertEquals(SessionStatus.IDLE, map["root_2"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `polling first pass refreshes statuses from repository`() = runTest {
        coEvery { repository.getSessionStatus() } returns mapOf("root_1" to "busy", "root_2" to "idle")
        val viewModel = SessionsViewModel(
            repository, prefs, sseEventBus, memoManager, activeSessionStore, childSessionStore
        )
        // Kicking off polling performs its first refresh pass immediately.
        viewModel.setPollingActive(true)
        advanceTimeBy(1_000)
        advanceUntilIdle()

        assertEquals(SessionStatus.BUSY, activeSessionStore.statusMap.value["root_1"])
        assertEquals(SessionStatus.IDLE, activeSessionStore.statusMap.value["root_2"])

        viewModel.setPollingActive(false)
        advanceUntilIdle()
    }
}