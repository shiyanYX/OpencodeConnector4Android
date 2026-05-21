package com.opencode.remote.ui.sessions

import app.cash.turbine.test
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SessionsViewModelAgentErrorTest {

    private lateinit var repository: OConnectorRepository
    private lateinit var prefs: ConnectionPreferences
    private lateinit var sseEventBus: SseEventBus
    private lateinit var memoManager: MemoManager
    private lateinit var activeSessionStore: ActiveSessionStore

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        sseEventBus = SseEventBus()
        memoManager = mockk(relaxed = true)
        activeSessionStore = ActiveSessionStore()

        // Default stubs for init-block coroutines
        every { prefs.darkMode } returns flowOf(false)
        every { prefs.hideChildSessions } returns flowOf(false)
        coEvery { repository.listAllSessions() } returns emptyList()
        coEvery { repository.getCurrentProject() } returns mockk(relaxed = true)
        every { repository.getCurrentServerName() } returns null
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `availableAgentsError is true when listAgents throws`() = runTest {
        // Make listAgents throw
        coEvery { repository.listAgents() } throws RuntimeException("network error")

        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore)

        viewModel.uiState.test {
            // Skip initial state
            awaitItem()

            // Trigger agent loading
            viewModel.loadAgents()

            // Advance until we find a state with availableAgentsError = true
            val errorState = awaitItem()
            assertTrue(
                "Expected availableAgentsError to be true but was ${errorState.availableAgentsError}",
                errorState.availableAgentsError
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `availableAgentsError stays false when listAgents succeeds`() = runTest {
        coEvery { repository.listAgents() } returns emptyList()

        val viewModel = SessionsViewModel(repository, prefs, sseEventBus, memoManager, activeSessionStore)

        // The default state already has availableAgentsError = false
        // and a successful loadAgents() sets it to false again (no state change emitted).
        // So we verify the value directly from the StateFlow after loading.
        viewModel.loadAgents()

        // Give the coroutine time to complete
        advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertTrue(
            "Expected availableAgentsError to remain false but was ${finalState.availableAgentsError}",
            !finalState.availableAgentsError
        )
    }
}
