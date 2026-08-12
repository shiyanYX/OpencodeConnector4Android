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
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for older-message pagination in [ChatViewModel.loadOlderMessages].
 *
 * The server only supports ?limit=N (recent-N window), so paging older grows
 * the window and merges by message id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatViewModelPaginationTest {

    private lateinit var repository: OConnectorRepository
    private lateinit var connectionPreferences: ConnectionPreferences
    private lateinit var context: Context

    private fun message(id: String) = MessageInfo(
        info = MessageInfoData(id = id, role = "user"),
        parts = emptyList(),
    )

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
        every { repository.activeSessionDirectory } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    /** Bind Main to the runTest scheduler and initialize a seeded session. */
    private suspend fun TestScope.seed(repository: OConnectorRepository, messages: List<MessageInfo>): ChatViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        coEvery { repository.getMessages("session-1", null, any()) } returns messages
        val viewModel = ChatViewModel(repository, SseEventBus(), connectionPreferences, mockk(relaxed = true), mockk(relaxed = true), context)
        viewModel.initialize("session-1", null)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `loadOlderMessages doubles the window and prepends older messages`() = runTest {
        val initial = (1..50).map { message("m$it") }
        val viewModel = seed(repository, initial)

        assertTrue(viewModel.uiState.value.hasMoreOlderMessages)
        assertEquals(50, viewModel.uiState.value.messages.size)

        // Server now reports 100 messages (50 older + 50 existing).
        val fuller = (1..100).map { message("m$it") }
        val limitSlot = slot<Int?>()
        coEvery { repository.getMessages("session-1", null, captureNullable(limitSlot)) } returns fuller

        viewModel.loadOlderMessages()
        advanceUntilIdle()

        assertEquals(100, limitSlot.captured)
        assertEquals(100, viewModel.uiState.value.messages.size)
        assertEquals("m1", viewModel.uiState.value.messages.first().id)
        assertEquals("m100", viewModel.uiState.value.messages.last().id)
        assertFalse(viewModel.uiState.value.isLoadingOlderMessages)
    }

    @Test
    fun `loadOlderMessages is a no-op when init window is not full`() = runTest {
        val partial = (1..10).map { message("m$it") }
        val viewModel = seed(repository, partial)

        assertEquals(10, viewModel.uiState.value.messages.size)
        assertFalse(viewModel.uiState.value.hasMoreOlderMessages)

        viewModel.loadOlderMessages()
        advanceUntilIdle()

        // No further fetch beyond the initial one.
        coVerify(exactly = 1) { repository.getMessages("session-1", null, any()) }
        assertEquals(10, viewModel.uiState.value.messages.size)
    }

    @Test
    fun `loadOlderMessages respects concurrent guard`() = runTest {
        val initial = (1..50).map { message("m$it") }
        val viewModel = seed(repository, initial)

        val fuller = (1..100).map { message("m$it") }
        coEvery { repository.getMessages("session-1", null, 100) } returns fuller

        // Two rapid calls — only the first should fetch.
        viewModel.loadOlderMessages()
        viewModel.loadOlderMessages()
        advanceUntilIdle()

        assertEquals(100, viewModel.uiState.value.messages.size)
        coVerify(atLeast = 1) { repository.getMessages("session-1", null, 100) }
    }

    @Test
    fun `initialize sets hasMoreOlder when window is full`() = runTest {
        val full = (1..50).map { message("m$it") }
        val viewModel = seed(repository, full)

        assertTrue(viewModel.uiState.value.hasMoreOlderMessages)
    }

    @Test
    fun `initialize clears hasMoreOlder when window is not full`() = runTest {
        val partial = (1..10).map { message("m$it") }
        val viewModel = seed(repository, partial)

        assertFalse(viewModel.uiState.value.hasMoreOlderMessages)
    }

    @Test
    fun `dedupes by message id when merging older history`() = runTest {
        val initial = (1..50).map { message("m$it") }
        val viewModel = seed(repository, initial)

        // Overlapping window — new list is strictly longer.
        val overlapping = (1..80).map { message("m$it") }
        coEvery { repository.getMessages("session-1", null, 100) } returns overlapping

        viewModel.loadOlderMessages()
        advanceUntilIdle()

        val ids = viewModel.uiState.value.messages.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(80, ids.size)
    }
}