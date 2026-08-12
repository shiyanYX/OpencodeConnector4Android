package com.opencode.remote.data.api

import android.util.Log
import android.util.Base64
import com.opencode.remote.data.api.dto.*
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManager

/**
 * SSE client for OpenCode server v1.14.x real-time event streaming.
 *
 * Endpoint: GET /global/event (Content-Type: text/event-stream)
 *
 * Event format (observed from live server):
 *   data: {"directory":"...","project":"global","payload":{"type":"message.part.update","properties":{"sessionID":"ses_xxx","messageID":"msg_xxx","partID":"prt_xxx","delta":"..."}}}
 *   data: {"payload":{"type":"server.connected","properties":{}}}
 *
 * Each SSE "data:" line contains a complete JSON object.
 * Top-level fields (directory, project) are optional; payload is always present.
 */
class OConnectorSseClient @Inject constructor(
    private val json: Json,
) {

    /** Test seam: inject a custom engine (e.g. Ktor MockEngine). */
    internal constructor(json: Json, testEngine: HttpClientEngine) : this(json) {
        this.testEngine = testEngine
        sseClient = createSseClient()
    }

    private var testEngine: HttpClientEngine? = null
    private var baseUrl: String = ""
    private var authHeader: String? = null
    private var autoReconnect: Boolean = true
    private var insecureTrust: Boolean = false
    @Volatile
    private var sseClient: HttpClient = createSseClient()

    private fun createSseClient(insecureTrust: Boolean = false): HttpClient {
        val test = testEngine
        if (test != null) {
            return HttpClient(test) {
                install(HttpTimeout) {
                    requestTimeoutMillis = Long.MAX_VALUE // SSE is long-lived
                    socketTimeoutMillis = Long.MAX_VALUE
                }
            }
        }
        return HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = Long.MAX_VALUE // SSE is long-lived
                    socketTimeoutMillis = Long.MAX_VALUE
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
        private const val TAG = "OpenCodeSse"
        private const val MAX_RETRIES = 5
        private const val INITIAL_DELAY_MS = 5000L
        private const val MAX_DELAY_MS = 30000L
    }

    /**
     * Configure (or reconfigure) the SSE client with connection parameters.
     * Called by the repository when a new connection is established.
     */
    fun configure(baseUrl: String, username: String = "", password: String = "", autoReconnect: Boolean = true, insecureTrust: Boolean = false) {
        synchronized(this) {
            close()
            this.baseUrl = baseUrl
            this.autoReconnect = autoReconnect
            this.insecureTrust = insecureTrust
            this.authHeader = if (password.isNotEmpty()) {
                "Basic " + Base64.encodeToString(
                    "${username.ifEmpty { "opencode" }}:$password".toByteArray(),
                    Base64.NO_WRAP
                )
            } else null
            sseClient = createSseClient(insecureTrust)
        }
    }

    /**
     * Subscribe to server events via SSE.
     *
     * Uses channelFlow with retry loop: on connection failure the old HttpClient
     * is closed, a new one is created, and reconnection is attempted with
     * exponential backoff (5s × 2^retry, capped at 30s). Up to [MAX_RETRIES]
     * attempts are made; after that the flow terminates with an [IOException].
     *
     * Controlled by [autoReconnect] — when false, no retries are attempted.
     */
    fun subscribeToEvents(): Flow<ServerEvent> = channelFlow {
        var retryCount = 0
        var terminalError: IOException? = null

        while (isActive) {
            // Heartbeat timeout tracking — 45s without any SSE line triggers reconnect
            var lastEventTimeMs = System.currentTimeMillis()
            val sseChannelRef = AtomicReference<ByteReadChannel?>(null)
            val heartbeatJob = launch {
                while (isActive) {
                    delay(5000)
                    val elapsed = System.currentTimeMillis() - lastEventTimeMs
                    if (elapsed > 45_000) {
                        Log.w(TAG, "SSE heartbeat timeout: ${elapsed}ms since last event, reconnecting")
                        sseChannelRef.get()?.cancel()
                        return@launch
                    }
                }
            }

            try {
                val sseUrl = "$baseUrl/global/event"
                val client = synchronized(this) { sseClient }
                client.prepareGet(sseUrl) {
                    headers {
                        append(HttpHeaders.Accept, "text/event-stream")
                        append(HttpHeaders.CacheControl, "no-cache")
                        authHeader?.let { append(HttpHeaders.Authorization, it) }
                    }
                }.execute { response ->
                    if (response.status.value !in 200..299) {
                        // Server rejected the stream (e.g. 401/403/429 quota or auth error).
                        // Read the error body — opencode returns human-readable text like
                        // "Free usage exceeded, subscribe to Go" — so the UI can surface it.
                        val errorBody = try { response.bodyAsText().take(2000) } catch (_: Exception) { "" }
                        throw IOException("HTTP ${response.status.value}${if (errorBody.isNotBlank()) ": $errorBody" else ""}")
                    }
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    sseChannelRef.set(channel)

                    while (!channel.isClosedForRead) {
                        val line = try {
                            channel.readUTF8Line()
                        } catch (e: Exception) {
                            Log.w(TAG, "SSE read error: ${e.message}")
                            break
                        }

                        if (line == null) break

                        // Only process "data:" lines — each is a complete JSON event
                        if (line.startsWith("data:")) {
                            lastEventTimeMs = System.currentTimeMillis()
                            val jsonStr = line.removePrefix("data:").trim()
                            if (jsonStr.isNotEmpty()) {
                                try {
                                    val event = json.decodeFromString<ServerEvent>(jsonStr)
                                    send(event)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse SSE event: $jsonStr", e)
                                }
                            }
                        }
                        // Update heartbeat on any SSE comment line too (server sends ": ping")
                        if (line.startsWith(":")) {
                            lastEventTimeMs = System.currentTimeMillis()
                        }
                        // Empty lines are SSE event separators — ignore
                        // "event:" lines are optional — we parse type from JSON payload
                    }
                }

                // Connection ended (server closed or read error)
                if (!autoReconnect || !isActive) break
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "SSE stream error: ${e.message}")
                // A non-2xx rejection (auth/quota gate) is decisive — no amount of
                // reconnecting will fix it. Surface it to the caller immediately.
                if (e is IOException && (e.message?.startsWith("HTTP ") == true || e.message?.contains(": Free usage") == true)) {
                    terminalError = e
                    break
                }
                if (!autoReconnect || !isActive) break
            } finally {
                heartbeatJob.cancel()
                sseChannelRef.set(null)
            }

            // Shared reconnect logic for both normal disconnect and error
            retryCount++
            if (retryCount > MAX_RETRIES) {
                terminalError = IOException("SSE connection failed after $MAX_RETRIES retries")
                break
            }

            // Close old client and create new one to prevent resource leak
            synchronized(this) {
                try { sseClient.close() } catch (_: Exception) {}
                sseClient = createSseClient(insecureTrust)
            }

            val delayMs = minOf(INITIAL_DELAY_MS * (1L shl (retryCount - 1)), MAX_DELAY_MS)
            Log.w(TAG, "SSE reconnecting in ${delayMs}ms (attempt $retryCount/$MAX_RETRIES)")
            delay(delayMs)
        }

        terminalError?.let { throw it }
    }

    fun close() {
        synchronized(this) {
            try { sseClient.close() } catch (_: Exception) {}
        }
    }
}
