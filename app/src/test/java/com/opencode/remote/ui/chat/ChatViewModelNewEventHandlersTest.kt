package com.opencode.remote.ui.chat

import android.content.Context
import com.opencode.remote.data.api.dto.*
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sessionstore.ActiveSessionStore
import com.opencode.remote.data.sse.SseEventBus
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method

/**
 * Tests for new SSE event type handlers in [ChatViewModel.handleEvent].
 *
 * Covers: session.created, session.deleted, permission.replied,
 * question.replied, question.rejected, project.updated, vcs.branch.updated.
 *
 * session.updated already existed and is not tested here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatViewModelNewEventHandlersTest {

    private lateinit var eventBus: SseEventBus
    private lateinit var repository: OConnectorRepository
    private lateinit var connectionPreferences: ConnectionPreferences
    private lateinit var context: Context
    private lateinit var viewModel: ChatViewModel
    private lateinit var handleEvent: Method
    private lateinit var stateFlow: kotlinx.coroutines.flow.MutableStateFlow<ChatUiState>

    @Before
    fun setUp() {
        eventBus = SseEventBus()
        repository = mockk(relaxed = true)
        connectionPreferences = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { repository.currentGeneration } returns 1L
        coEvery { repository.getMessages(any(), any()) } returns emptyList()
        every { repository.activeSessionId } returns ""
        every { repository.activeSessionDirectory } returns null

        viewModel = ChatViewModel(repository, eventBus, connectionPreferences, mockk(relaxed = true), mockk(relaxed = true), context)
        // Access private handleEvent via reflection
        handleEvent = ChatViewModel::class.java.getDeclaredMethod("handleEvent", ServerEvent::class.java)
        handleEvent.isAccessible = true

        // Access _uiState for direct state manipulation in tests
        val uiStateField = ChatViewModel::class.java.getDeclaredField("_uiState")
        uiStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        stateFlow = uiStateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ChatUiState>
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun makeEvent(
        type: String,
        sessionId: String? = "test-session-123",
        properties: EventProperties = EventProperties(sessionID = sessionId),
    ) = ServerEvent(
        payload = EventPayload(type = type, properties = properties)
    )

    private fun setBlockingState(
        permission: PermissionRequestData? = null,
        question: QuestionRequestData? = null,
    ) {
        stateFlow.value = stateFlow.value.copy(
            sessionMeta = stateFlow.value.sessionMeta.copy(sessionId = "test-session-123"),
            chatDisplay = stateFlow.value.chatDisplay.copy(
                pendingPermission = permission,
                pendingQuestion = question,
                isBlocked = (permission != null || question != null),
            ),
        )
    }

    // ── session.created ──

    @Test
    fun `session created does not crash and logs`() {
        val event = makeEvent("session.created")
        handleEvent.invoke(viewModel, event)
        assertNull(viewModel.uiState.value.pendingPermission)
        assertNull(viewModel.uiState.value.pendingQuestion)
    }

    // ── session.deleted ──

    @Test
    fun `session deleted does not crash and logs`() {
        val event = makeEvent("session.deleted")
        handleEvent.invoke(viewModel, event)
        assertNull(viewModel.uiState.value.pendingPermission)
        assertNull(viewModel.uiState.value.pendingQuestion)
    }

    // ── permission.replied ──

    @Test
    fun `permission replied clears pending permission`() {
        val permData = PermissionRequestData(
            id = "perm-1",
            sessionID = "test-session-123",
            permission = "edit",
            patterns = listOf("src/*.kt"),
        )
        setBlockingState(permission = permData)
        // Verify setup
        assertNotNull(stateFlow.value.pendingPermission)
        assertTrue(stateFlow.value.isBlocked)

        val event = makeEvent("permission.replied", properties = EventProperties(
            sessionID = "test-session-123",
            id = "perm-1",
            reply = "once",
        ))
        handleEvent.invoke(viewModel, event)

        assertNull(stateFlow.value.pendingPermission)
        assertFalse(stateFlow.value.isBlocked)
        verify { repository.clearBlockingState(any()) }
    }

    @Test
    fun `permission replied with pending question keeps isBlocked true`() {
        val permData = PermissionRequestData(
            id = "perm-1",
            sessionID = "test-session-123",
            permission = "edit",
        )
        val questionData = QuestionRequestData(
            id = "q-1",
            sessionID = "test-session-123",
            questions = listOf(QuestionInfoDto(question = "Continue?")),
        )
        setBlockingState(permission = permData, question = questionData)

        val event = makeEvent("permission.replied", properties = EventProperties(
            sessionID = "test-session-123",
            id = "perm-1",
            reply = "once",
        ))
        handleEvent.invoke(viewModel, event)

        assertNull(stateFlow.value.pendingPermission)
        assertNotNull(stateFlow.value.pendingQuestion)
        assertTrue(stateFlow.value.isBlocked)
    }

    @Test
    fun `permission replied with no pending permission is no-op`() {
        // Set session ID so event passes filter
        stateFlow.value = stateFlow.value.copy(
            sessionMeta = stateFlow.value.sessionMeta.copy(sessionId = "test-session-123")
        )
        val event = makeEvent("permission.replied", properties = EventProperties(
            sessionID = "test-session-123",
            id = "perm-1",
        ))
        handleEvent.invoke(viewModel, event)
        assertNull(stateFlow.value.pendingPermission)
        assertFalse(stateFlow.value.isBlocked)
    }

    // ── question.replied ──

    @Test
    fun `question replied clears pending question`() {
        val questionData = QuestionRequestData(
            id = "q-1",
            sessionID = "test-session-123",
            questions = listOf(QuestionInfoDto(question = "Continue?")),
        )
        setBlockingState(question = questionData)
        assertNotNull(stateFlow.value.pendingQuestion)

        val event = makeEvent("question.replied", properties = EventProperties(
            sessionID = "test-session-123",
            id = "q-1",
        ))
        handleEvent.invoke(viewModel, event)

        assertNull(stateFlow.value.pendingQuestion)
        assertFalse(stateFlow.value.isBlocked)
        verify { repository.clearBlockingState(any()) }
    }

    // ── question.rejected ──

    @Test
    fun `question rejected clears pending question`() {
        val questionData = QuestionRequestData(
            id = "q-1",
            sessionID = "test-session-123",
            questions = listOf(QuestionInfoDto(question = "Continue?")),
        )
        setBlockingState(question = questionData)
        assertNotNull(stateFlow.value.pendingQuestion)

        val event = makeEvent("question.rejected", properties = EventProperties(
            sessionID = "test-session-123",
            id = "q-1",
        ))
        handleEvent.invoke(viewModel, event)

        assertNull(stateFlow.value.pendingQuestion)
        assertFalse(stateFlow.value.isBlocked)
        verify { repository.clearBlockingState(any()) }
    }

    @Test
    fun `question rejected with pending permission keeps isBlocked true`() {
        val permData = PermissionRequestData(
            id = "perm-1",
            sessionID = "test-session-123",
            permission = "edit",
        )
        val questionData = QuestionRequestData(
            id = "q-1",
            sessionID = "test-session-123",
            questions = listOf(QuestionInfoDto(question = "Continue?")),
        )
        setBlockingState(permission = permData, question = questionData)

        val event = makeEvent("question.rejected", properties = EventProperties(
            sessionID = "test-session-123",
            id = "q-1",
        ))
        handleEvent.invoke(viewModel, event)

        assertNull(stateFlow.value.pendingQuestion)
        assertNotNull(stateFlow.value.pendingPermission)
        assertTrue(stateFlow.value.isBlocked)
    }

    // ── project.updated ──

    @Test
    fun `project updated does not crash and logs`() {
        val event = makeEvent("project.updated", properties = EventProperties(
            sessionID = null,
            name = "my-project",
            path = "/home/user/projects/my-project",
        ))
        handleEvent.invoke(viewModel, event)
        assertNull(stateFlow.value.pendingPermission)
    }

    // ── vcs.branch.updated ──

    @Test
    fun `vcs branch updated does not crash and logs`() {
        val event = makeEvent("vcs.branch.updated", properties = EventProperties(
            sessionID = null,
            branch = "feature/new-ui",
            previousBranch = "main",
        ))
        handleEvent.invoke(viewModel, event)
        assertNull(stateFlow.value.pendingPermission)
    }

    // ── EventDtos: new fields are correctly parsed ──

    @Test
    fun `EventProperties new fields default to null`() {
        val props = EventProperties()
        assertNull(props.reply)
        assertNull(props.name)
        assertNull(props.path)
        assertNull(props.branch)
        assertNull(props.previousBranch)
    }

    @Test
    fun `EventProperties new fields can be set`() {
        val props = EventProperties(
            reply = "once",
            name = "test-project",
            path = "/path/to/project",
            branch = "feature-x",
            previousBranch = "main",
        )
        assertEquals("once", props.reply)
        assertEquals("test-project", props.name)
        assertEquals("/path/to/project", props.path)
        assertEquals("feature-x", props.branch)
        assertEquals("main", props.previousBranch)
    }
}

