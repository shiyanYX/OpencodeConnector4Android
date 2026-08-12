package com.opencode.remote.data.api

import android.util.Log
import android.util.Base64
import com.opencode.remote.data.api.dto.*
import io.ktor.client.*
import io.ktor.client.engine.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.ExperimentalSerializationApi
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import java.net.URLEncoder
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManager

/**
 * REST API client for OpenCode server v1.14.x
 *
 * All routes match actual server paths (verified via curl):
 *   GET  /session?list              → List<SessionInfo>
 *   POST /session                   → CreateSessionResponse
 *   GET  /session/{id}              → SessionInfo
 *   DELETE /session/{id}            → 200 OK
 *   POST /session/{id}/fork         → CreateSessionResponse
 *   POST /session/{id}/abort        → 200 OK
 *   GET  /session/{id}/message      → List<MessageInfo>
 *   POST /session/{id}/prompt_async → 204 No Content (async, AI output via SSE)
 *   GET  /session/{id}/todo         → List<TodoItem>
 *   GET  /project/current           → ProjectInfo
 */
class OConnectorApiClient @Inject constructor(
    private val json: Json,
) {

    /** Test seam: inject a custom engine (e.g. Ktor MockEngine). */
    internal constructor(json: Json, testEngine: HttpClientEngine) : this(json) {
        this.testEngine = testEngine
        client = createClient()
    }

    private var testEngine: HttpClientEngine? = null
    private var authHeader: String? = null
    private var insecureTrust: Boolean = false

    @OptIn(ExperimentalSerializationApi::class)
    private var client: HttpClient = createClient()

    private fun createClient(insecureTrust: Boolean = false, baseUrl: String = ""): HttpClient {
        val test = testEngine
        if (test != null) {
            return HttpClient(test) {
                install(ContentNegotiation) { json(json) }
                defaultRequest {
                    if (baseUrl.isNotBlank()) url(baseUrl)
                    contentType(ContentType.Application.Json)
                    authHeader?.let { header(HttpHeaders.Authorization, it) }
                }
            }
        }
        return HttpClient(OkHttp) {
                install(ContentNegotiation) { json(json) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                }
                defaultRequest {
                    if (baseUrl.isNotBlank()) url(baseUrl)
                    contentType(ContentType.Application.Json)
                    authHeader?.let { header(HttpHeaders.Authorization, it) }
                }
                engine {
                    if (insecureTrust) {
                        val trustManager = object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        }
                        val sslContext = SSLContext.getInstance("TLS")
                        sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
                        config {
                            sslSocketFactory(sslContext.socketFactory, trustManager)
                            hostnameVerifier { _, _ -> true }
                        }
                    }
                }
            }
    }

    companion object {
        private const val TAG = "OConnectorApiClient"
        /** Maximum number of messages to load from server. Prevents OOM on long sessions. */
        private const val MAX_MESSAGES = 50
    }

    /**
     * Configure (or reconfigure) the client with connection parameters.
     * Called by the repository when a new connection is established.
     */
    fun configure(baseUrl: String, username: String = "", password: String = "", insecureTrust: Boolean = false) {
        close()
        this.insecureTrust = insecureTrust
        authHeader = if (password.isNotEmpty()) {
            "Basic " + Base64.encodeToString(
                "${username.ifEmpty { "opencode" }}:$password".toByteArray(),
                Base64.NO_WRAP
            )
        } else null
        client = createClient(insecureTrust, baseUrl)
    }

    /** Encode directory path for HTTP header (RFC 7230: headers are ASCII-only). */
    private fun encDir(path: String): String =
        URLEncoder.encode(path, "UTF-8")

    // ─── Sessions ──────────────────────────────────────────────────────

    /** GET /session?list → returns array directly. Optional directory/scope filter. */
    suspend fun listSessions(directory: String? = null, scope: String? = null): List<SessionInfo> {
        val sessions = client.get("/session") {
            parameter("list", "")
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
            scope?.let { parameter("scope", it) }
        }.body<List<SessionInfo>>()
        Log.d(TAG, "Loaded ${sessions.size} sessions for dir=$directory scope=$scope")
        return sessions
    }

    /** POST /session with empty body → returns new session. Optional directory to set project. */
    suspend fun createSession(directory: String? = null): CreateSessionResponse =
        client.post("/session") {
            setBody("{}")
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<CreateSessionResponse>()

    /** GET /session/{id} */
    suspend fun getSession(id: String, directory: String? = null): SessionInfo =
        client.get("/session/$id") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<SessionInfo>()

    /** DELETE /session/{id} */
    suspend fun deleteSession(id: String, directory: String? = null) {
        client.delete("/session/$id") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
    }

    /** POST /session/{id}/fork with empty body */
    suspend fun forkSession(id: String, directory: String? = null): CreateSessionResponse =
        client.post("/session/$id/fork") {
            setBody("{}")
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<CreateSessionResponse>()

    /** POST /session/{id}/abort */
    suspend fun abortSession(id: String, directory: String? = null) {
        client.post("/session/$id/abort") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
    }

    /** POST /session/{id}/revert — undo last user message (soft-hide, file rollback on server) */
    suspend fun revertSession(id: String, messageID: String, directory: String? = null): SessionInfo =
        client.post("/session/$id/revert") {
            setBody(RevertRequest(messageID = messageID))
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<SessionInfo>()

    /** POST /session/{id}/unrevert — redo (restore reverted messages + file snapshot) */
    suspend fun unrevertSession(id: String, directory: String? = null): SessionInfo =
        client.post("/session/$id/unrevert") {
            setBody("{}")
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<SessionInfo>()

    // ─── Messages ──────────────────────────────────────────────────────

    /**
     * GET /session/{id}/message → returns array directly.
     *
     * Memory optimization:
     * 1. Server-side: passes ?limit=N to only fetch recent messages (prevents huge response)
     * 2. Client-side: caps response to the requested limit as safety net
     *
     * The OpenCode server supports ?limit=N (cursor-based pagination: returns the most
     * recent N messages). Without it, ALL messages including huge tool outputs are
     * returned → OOM. Larger limit → older history is included (used by pagination).
     */
    suspend fun getMessages(id: String, directory: String? = null, limit: Int? = null): List<MessageInfo> {
        val requested = limit ?: MAX_MESSAGES
        val messages = client.get("/session/$id/message") {
            parameter("limit", requested)
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<List<MessageInfo>>()

        // Safety net: if server ignores requested limit and returns more
        return if (messages.size > requested) {
            Log.w(TAG, "Server returned ${messages.size} messages despite limit=$requested, truncating")
            messages.takeLast(requested)
        } else {
            messages
        }
    }

    /**
     * POST /session/{id}/prompt_async — 异步发送（HTTP 204, 立即返回）
     * Body: {"parts":[{"type":"text","text":"user message"}],"agent":"optional"}
     * AI 生成通过 SSE 事件流实时推送（message.part.delta, message.completed)
     */
    suspend fun sendMessage(sessionId: String, text: String, agent: String? = null, providerID: String? = null, modelID: String? = null, variant: String? = null, directory: String? = null) {
        val modelRef = if (providerID != null || modelID != null) ModelRef(providerID, modelID) else null
        val response = client.post("/session/$sessionId/prompt_async") {
            setBody(SendMessageRequest(parts = listOf(SendMessagePart(text = text)), agent = agent, model = modelRef, variant = variant))
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
        if (response.status.value !in 200..299) {
            // Server-side rejection (e.g. "Free usage exceeded, subscribe to Go" from
            // the usage gate). Surface the server's own message so the chat UI can
            // show exactly what went wrong instead of a silent fire-and-forget send.
            val errorBody = try { response.bodyAsText().take(2000) } catch (_: Exception) { "" }
            throw IOException("HTTP ${response.status.value}${if (errorBody.isNotBlank()) ": $errorBody" else ""}")
        }
    }

    // ─── Permission / Question Replies ──────────────────────────────────

    /** POST /permission/:requestId/reply — respond to a permission.asked event */
    suspend fun replyPermission(requestId: String, reply: String, message: String? = null, directory: String? = null) {
        client.post("/permission/$requestId/reply") {
            setBody(PermissionReplyPayload(reply = reply, message = message))
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
        Log.d(TAG, "Permission reply: $reply for request=$requestId")
    }

    /** POST /question/:requestId/reply — respond to a question.asked event */
    suspend fun replyQuestion(requestId: String, answers: List<List<String>>, directory: String? = null) {
        client.post("/question/$requestId/reply") {
            setBody(QuestionReplyPayload(answers = answers))
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
        Log.d(TAG, "Question reply: ${answers.size} answers for request=$requestId")
    }

    /** POST /question/:requestId/reject — dismiss a question.asked event */
    suspend fun rejectQuestion(requestId: String, directory: String? = null) {
        client.post("/question/$requestId/reject") {
            setBody("{}")
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
        Log.d(TAG, "Question rejected for request=$requestId")
    }

    // ─── Todo ──────────────────────────────────────────────────────────

    /** GET /session/{id}/todo → returns array directly */
    suspend fun getTodoList(id: String, directory: String? = null): List<TodoItem> =
        client.get("/session/$id/todo") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<List<TodoItem>>()

    // ─── Session Status ────────────────────────────────────────────────

    /** GET /session/status → returns Map<String, String> (sessionID → "busy"/"idle") */
    suspend fun getSessionStatus(): Map<String, String> {
        val statusMap = client.get("/session/status").body<Map<String, String>>()
        Log.d(TAG, "Loaded status for ${statusMap.size} sessions")
        return statusMap
    }

    /** GET /session/{id}/children → returns list of child sessions */
    suspend fun getSessionChildren(sessionId: String): List<SessionInfo> {
        val children = client.get("/session/$sessionId/children").body<List<SessionInfo>>()
        Log.d(TAG, "Loaded ${children.size} children for session=$sessionId")
        return children
    }

    // ─── Project ───────────────────────────────────────────────────────

    /** GET /project/current → flat ProjectInfo (no wrapper) */
    suspend fun getCurrentProject(): ProjectInfo =
        client.get("/project/current").body<ProjectInfo>()

    /** GET /project → list all known projects */
    suspend fun listProjects(): List<ProjectInfo> =
        client.get("/project").body<List<ProjectInfo>>()

    /**
     * Fetch sessions from ALL known projects.
     *
     * OpenCode scopes sessions by project (determined by directory).
     * A single server call only returns sessions for one project.
     *
     * Strategy:
     *   1. GET /project → discover all known projects
     *   2. For normal projects (real worktree): GET /session?list&directory=<worktree>
     *   3. For global project (worktree="/"): GET /session?list&directory=/&scope=project
     *      — scope=project skips directory matching, returns ALL sessions for that project_id
     *   4. Merge and deduplicate all results
     *
     * Falls back to a single unfiltered request if project list fails.
     */
    suspend fun listAllSessions(): List<SessionInfo> {
        val allSessions = mutableListOf<SessionInfo>()
        val seenIds = mutableSetOf<String>()

        try {
            val projects = listProjects()
            Log.d(TAG, "Discovered ${projects.size} projects: ${projects.map { "${it.id}=${it.worktree}" }}")

            // Query sessions for each project in parallel
            val results = coroutineScope {
                projects.map { project ->
                    async {
                        try {
                            val isGlobal = project.worktree == "/" || project.worktree == null
                            if (isGlobal) {
                                // scope=project skips directory filter, returns all sessions for this project_id
                                listSessions(directory = project.worktree ?: "/", scope = "project")
                            } else {
                                listSessions(directory = project.worktree)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load sessions for project ${project.id} (${project.worktree}): ${e.message}")
                            emptyList<SessionInfo>()
                        }
                    }
                }.awaitAll()
            }

            for (sessions in results) {
                for (session in sessions) {
                    if (seenIds.add(session.id)) {
                        allSessions.add(session)
                    }
                }
            }

            Log.d(TAG, "Merged ${allSessions.size} unique sessions from ${projects.size} projects")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list projects, falling back to single query: ${e.message}")
            return listSessions(null)
        }

        return allSessions
    }

    /** Test connectivity by hitting a lightweight endpoint */
    suspend fun testConnection(): Boolean = try {
        getCurrentProject()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Test connection failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    // ─── Agents ─────────────────────────────────────────────────────────

    /** GET /agent → returns array of available agents */
    suspend fun listAgents(): List<AgentInfo> =
        client.get("/agent").body<List<AgentInfo>>()

    // ─── Files ──────────────────────────────────────────────────────────

    /** GET /file?path=... → returns array of file/directory nodes */
    suspend fun listFiles(path: String, directory: String? = null): List<FileNode> =
        client.get("/file") {
            parameter("path", path)
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<List<FileNode>>()

    /** GET /file/content?path=... → returns file content as FileContent */
    suspend fun readFileContent(path: String, directory: String? = null): FileContent =
        client.get("/file/content") {
            parameter("path", path)
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<FileContent>()

    // ─── Config / Providers ─────────────────────────────────────────────

    /** GET /provider → returns provider list with models and connected status */
    suspend fun listProviders(): ProviderList =
        client.get("/provider").body<ProviderList>()

    // ─── Commands / Skills ──────────────────────────────────────────────

    /** GET /command → returns registered slash commands (and skills). */
    suspend fun listCommands(directory: String? = null): List<CommandInfo> =
        client.get("/command") {
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }.body<List<CommandInfo>>()

    /** POST /session/{id}/command → runs a slash command in a session, returns the created message. */
    suspend fun runCommand(
        sessionId: String,
        command: String,
        arguments: String = "",
        agent: String? = null,
        providerID: String? = null,
        modelID: String? = null,
        directory: String? = null,
    ) {
        client.post("/session/$sessionId/command") {
            setBody(
                RunCommandRequest(
                    command = command,
                    arguments = arguments,
                    agent = agent,
                    model = if (providerID != null || modelID != null) {
                        CommandModelRef(providerID, modelID)
                    } else null,
                )
            )
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", encDir(it))
            }
        }
        Log.d(TAG, "Command executed: /$command in session=$sessionId")
    }

    fun close() {
        try { client.close() } catch (_: Exception) {}
    }
}
