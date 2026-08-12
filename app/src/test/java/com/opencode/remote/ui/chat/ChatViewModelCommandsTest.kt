package com.opencode.remote.ui.chat

import android.content.Context
import com.opencode.remote.data.api.dto.CommandInfo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests for the slash-command / skill feature in [ChatViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatViewModelCommandsTest {

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
        coEvery { repository.getMessages(any(), any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    /** Bind Main to the runTest scheduler and create a fresh ViewModel. */
    private fun TestScope.newChatViewModel(): ChatViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return ChatViewModel(repository, SseEventBus(), connectionPreferences, mockk(relaxed = true), mockk(relaxed = true), context)
    }

    @Test
    fun `loadCommands populates available commands and clears loading`() = runTest {
        val commands = listOf(
            CommandInfo(name = "compact", description = "Compact history"),
            CommandInfo(name = "memory", description = "Summarize memory"),
        )
        coEvery { repository.listCommands(any()) } returns commands
        val viewModel = newChatViewModel()

        viewModel.loadCommands()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.availableCommands.size)
        assertEquals("compact", viewModel.uiState.value.availableCommands[0].name)
        assertFalse(viewModel.uiState.value.isLoadingCommands)
        assertFalse(viewModel.uiState.value.availableCommandsError)
    }

    @Test
    fun `loadCommands sets error flag on failure`() = runTest {
        coEvery { repository.listCommands(any()) } throws RuntimeException("boom")
        val viewModel = newChatViewModel()

        viewModel.loadCommands()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.availableCommandsError)
        assertFalse(viewModel.uiState.value.isLoadingCommands)
        assertEquals(0, viewModel.uiState.value.availableCommands.size)
    }

    @Test
    fun `runCommand executes the command with the current session`() = runTest {
        coEvery { repository.getSession(any(), any()) } returns com.opencode.remote.data.api.dto.SessionInfo(id = "session-1")
        val viewModel = newChatViewModel()
        // Establish an active session via the real initialize path.
        viewModel.initialize("session-1", null)
        advanceUntilIdle()

        coEvery { repository.runCommand(any(), any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { repository.getMessages(any(), any(), any()) } returns emptyList()

        viewModel.runCommand("compact")
        advanceUntilIdle()

        coVerify {
            repository.runCommand(
                sessionId = "session-1",
                command = "compact",
                arguments = "",
                agent = any(),
                providerID = any(),
                modelID = any(),
                directory = any(),
            )
        }
    }

    @Test
    fun `runCommand is a no-op when session is blank`() = runTest {
        val viewModel = newChatViewModel()
        viewModel.runCommand("compact")
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.runCommand(any(), any(), any(), any(), any(), any(), any()) }
    }

    /** Establish an active session with a known command list loaded. */
    private suspend fun TestScope.seedSessionWithCommands(names: List<String>): ChatViewModel {
        coEvery { repository.getSession(any(), any()) } returns com.opencode.remote.data.api.dto.SessionInfo(id = "session-1")
        coEvery { repository.getBlockingState(any()) } returns null
        coEvery { repository.getMessages(any(), any(), any()) } returns emptyList()
        coEvery { repository.listCommands(any()) } returns names.map { CommandInfo(name = it, description = "desc for $it") }
        val viewModel = newChatViewModel()
        viewModel.initialize("session-1", null)
        advanceUntilIdle()
        viewModel.loadCommands()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `fillCommandInInput fills slash command into the input box`() = runTest {
        val viewModel = newChatViewModel()
        viewModel.fillCommandInInput("compact")
        assertEquals("/compact ", viewModel.uiState.value.inputText)
    }

    @Test
    fun `sendMessage routes a recognized slash command to runCommand`() = runTest {
        val viewModel = seedSessionWithCommands(listOf("compact"))
        viewModel.onInputChange("/compact 5")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.runCommand(
                sessionId = "session-1",
                command = "compact",
                arguments = "5",
                agent = any(),
                providerID = any(),
                modelID = any(),
                directory = any(),
            )
        }
        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendMessage treats a start-slash non-command as an ordinary prompt`() = runTest {
        val viewModel = seedSessionWithCommands(listOf("compact"))
        // Force the ordinary prompt path to fail fast so the streaming watchdog
        // breaks out (isSending gets reset) and advanceUntilIdle can complete.
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("noop")
        viewModel.onInputChange("/unknown-file")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.runCommand(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendMessage with plain text does not trigger runCommand`() = runTest {
        val viewModel = seedSessionWithCommands(listOf("compact"))
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("noop")
        viewModel.onInputChange("hello world")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.runCommand(any(), any(), any(), any(), any(), any(), any()) }
    }
}