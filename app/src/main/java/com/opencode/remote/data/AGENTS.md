# Data Layer — OConnector

**Scope:** `data/` subtree. REST/SSE networking, persistence, event bus, in-app updates.

## STRUCTURE

```
data/
├── api/
│   ├── OpenCodeApiClient.kt      # Ktor/OkHttp REST client — 40+ methods
│   ├── OpenCodeSseClient.kt      # Raw SSE client (Ktor ByteReadChannel, NOT plugin)
│   └── dto/                      # kotlinx-serialization DTOs
│       ├── EventDtos.kt          # ServerEvent / EventPayload / EventProperties
│       ├── SessionDtos.kt        # SessionInfo, MessageInfo, MessagePart, TodoItem
│       ├── CommonDtos.kt         # ProjectInfo, AgentInfo, FileNode, ProviderList
│       ├── ServerDtos.kt         # ServerConfig wire models
│       └── SidePanelDtos.kt      # Side panel payload models
├── datastore/
│   ├── ServerManager.kt          # DataStore + EncryptedSharedPreferences for servers
│   ├── ConnectionPreferences.kt  # SharedPreferences cache for streaming/blocking state
│   └── MemoManager.kt            # Per-session memo storage
├── download/
│   ├── ApkDownloader.kt          # Progress-tracked APK download (OkHttp streaming)
│   └── ApkInstaller.kt           # Intent-based APK install helper
├── github/
│   ├── GitHubApi.kt              # Ktor client for GitHub REST releases API
│   ├── GitHubReleaseService.kt   # Fetch + parse latest release
│   ├── UpdateRepository.kt       # Update check logic, version comparison
│   └── ReleaseInfo.kt            # Release DTOs
├── network/
│   └── NetworkMonitor.kt         # ConnectivityManager.NetworkCallback + @Volatile onNetworkAvailable
├── repository/
│   └── OpenCodeRepository.kt     # OConnectorRepository interface + OConnectorRepositoryImpl
└── sse/
    └── SseEventBus.kt            # SharedFlow event bus with EventEnvelope generation filtering
```

## DATA FLOW

```
OpenCodeSseClient          (raw Ktor ByteReadChannel, manual line parsing)
       │
       ▼
SseForegroundService       (owns SSE subscription, survives background)
       │
       ▼
SseEventBus                (SharedFlow<EventEnvelope>, generation-filtered)
       │
       ▼
ViewModels                 (collect EventBus, never touch raw SSE client)

REST path:
ViewModel → OConnectorRepository → OpenCodeApiClient (Ktor/OkHttp + ContentNegotiation)
```

## WHERE TO LOOK

| Task | File(s) | Notes |
|------|---------|-------|
| Add SSE event type | `dto/EventDtos.kt` → `SseEventBus.kt` → ViewModel handleEvent() | Add field to EventProperties, add when branch |
| Add REST endpoint | `api/OpenCodeApiClient.kt` → `repository/OpenCodeRepository.kt` | Client configured per-connection, not at init |
| Change server persistence | `datastore/ServerManager.kt` | DataStore for list, EncryptedSharedPreferences for passwords |
| Fix reconnect behavior | `api/OpenCodeSseClient.kt` | Exponential backoff 5s×2^retry, cap 30s, max 5 retries |
| Add process-death cache | `datastore/ConnectionPreferences.kt` | Streaming + blocking state survive ViewModel recreation |
| Modify generation filtering | `sse/SseEventBus.kt` + `repository/OpenCodeRepository.kt` | AtomicLong counter in Repository, EventEnvelope wraps events |
| Add in-app update logic | `github/UpdateRepository.kt` + `github/GitHubReleaseService.kt` | Version comparison, APK download via `download/ApkDownloader.kt` |
| Change TLS trust | `api/OpenCodeApiClient.kt` + `api/OpenCodeSseClient.kt` | Custom X509TrustManager, dev/local only |
| Network state changes | `network/NetworkMonitor.kt` | @Volatile onNetworkAvailable callback, NetworkCallback lifecycle |
| Multi-project session list | `api/OpenCodeApiClient.kt` listAllSessions() | GET /project → parallel async/awaitAll per project |

## CONVENTIONS

- **Repository pattern**: `OConnectorRepository` interface + `OConnectorRepositoryImpl`, bound via `@Binds` in AppModule.
- **SSE client**: Raw `HttpClient.prepareGet()` + `ByteReadChannel` readUTF8Line loop. NOT the ktor-client-sse plugin.
- **Heartbeat**: 45s timeout tracked per SSE connection, monitored every 5s.
- **Reconnect**: New `HttpClient` created per attempt. Exponential backoff, 5 retry cap.
- **REST client**: Ktor HttpClient(OkHttp engine), timeouts: request=30s, connect=10s, socket=30s.
- **Caching**: Agent/model lists have 30-second TTL in-memory cache.
- **Process death resilience**: Streaming + blocking state cached to `ConnectionPreferences` (SharedPreferences).
- **TLS**: Custom `X509TrustManager` trusts all certs. Local/dev server use only.
- **DI scope**: All `@Singleton` in `SingletonComponent`. No finer-grained scopes.
- **DTOs**: `@Serializable` data classes with `@SerialName` for JSON field mapping.

## ANTI-PATTERNS

- **DO NOT** add `.buffer()` or `.flowOn()` to SSE SharedFlow pipeline. Causes UI freeze. Documented in `.sisyphus/notepads/sse-freeze-fix/`.
- **DO NOT** clear streaming state in `message.updated` handler. Causes "response disappears" flash.
- **DO NOT** clear blocking state during reconnection. Must persist across reconnect.
- `catch (Exception)` only. Never `catch (Throwable)`. Project-wide convention.
- `@Suppress("UNCHECKED_CAST")` in test code only. Never in production.
- **DO NOT** create SSE subscriptions from ViewModels. SSE owned exclusively by `SseForegroundService`.
- **DO NOT** reuse `HttpClient` across reconnect attempts. New client per attempt.
