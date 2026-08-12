package com.opencode.remote.ui.chat

import android.content.Context
import com.opencode.remote.data.api.dto.MessageInfo
import com.opencode.remote.data.api.dto.MessageInfoData
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sse.SseEventBus
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Same-session re-entry must NOT reload messages — the activity-scoped
 * ViewModel keeps state (and its SSE subscription) alive while the chat
 * screen is closed, so initialize() short-circuits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatViewModelReentryTest {

    private lateinit var repository: OConnectorRepository
    private lateinit var connectionPreferences: ConnectionPreferences
    private lateinit var context: Context

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        connectionPreferences = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { repository.currentGeneration } returns 1L
        every { repository.activeSessionId } returns ""
        coEvery { repository.getBlockingState(any()) } returns null
        coEvery { repository.getSession(any(), any()) } returns com.opencode.remote.data.api.dto.SessionInfo(
            id = "session-1",
            title = "session-1",
        )
        coEvery { repository.getMessages(any(), any(), any()) } returns listOf(
            MessageInfo(info = MessageInfoData(id = "m1", role = "user")),
            MessageInfo(
                info = MessageInfoData(
                    id = "m2",
                    role = "assistant",
                    time = com.opencode.remote.data.api.dto.MessageTime(completed = 1L),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun TestScope.newChatViewModel(): ChatViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return ChatViewModel(repository, SseEventBus(), connectionPreferences, mockk(relaxed = true), mockk(relaxed = true), context)
    }

    @Test
    fun `same-session re-initialize keeps messages and skips reload`() = runTest {
        val viewModel = newChatViewModel()

        viewModel.initialize("session-1", null)
        advanceUntilIdle()

        assertEquals("session-1", viewModel.uiState.value.sessionId)
        assertEquals(2, viewModel.uiState.value.messages.size)
        coVerify(exactly = 1) { repository.getMessages(any(), any(), any()) }

        // Re-enter the same session — state must be kept, no reload.
        viewModel.initialize("session-1", null)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)
        coVerify(exactly = 1) { repository.getMessages(any(), any(), any()) }
    }

    @Test
    fun `different-session initialize still reloads messages`() = runTest {
        val viewModel = newChatViewModel()

        viewModel.initialize("session-1", null)
        advanceUntilIdle()
        viewModel.initialize("session-2", null)
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getMessages(any(), any(), any()) }
        assertEquals("session-2", viewModel.uiState.value.sessionId)
    }
}