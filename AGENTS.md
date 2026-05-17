# OConnector — PROJECT KNOWLEDGE BASE

**Generated:** 2026-05-18
**Commit:** 159e723
**Branch:** main

## OVERVIEW

Android client for OpenCode server. Kotlin + Jetpack Compose (Material3) + Ktor SSE + Hilt DI. Connects to a local OpenCode instance via SSE for real-time AI chat sessions with streaming, permissions, and todo management.

## STRUCTURE

```
app/src/main/java/com/opencode/remote/
├── data/           # Network + persistence layer (see data/AGENTS.md)
│   ├── api/        # REST + SSE clients (Ktor/OkHttp), DTOs
│   ├── datastore/  # ServerManager, ConnectionPreferences (DataStore + EncryptedSharedPreferences)
│   ├── download/   # APK download + install helpers
│   ├── github/     # GitHub release checker (in-app updates)
│   ├── network/    # NetworkMonitor (ConnectivityManager.NetworkCallback)
│   ├── repository/ # OConnectorRepository interface + impl (central API facade)
│   └── sse/        # SseEventBus (SharedFlow event bus with generation filtering)
├── di/             # AppModule — sole Hilt @Module @InstallIn(SingletonComponent)
├── service/        # SseForegroundService — keeps SSE alive in background
├── ui/             # All screens (see ui/AGENTS.md)
│   ├── chat/       # Core chat feature (see ui/chat/AGENTS.md)
│   ├── components/ # Shared composables (ErrorSnackbar)
│   ├── connection/ # Add/edit server form
│   ├── help/       # Help + update check screen
│   ├── serverlist/ # Server list + auto-connect
│   ├── sessions/   # Session list (all projects + per-project)
│   ├── strings/    # Strings.kt — i18n via Kotlin sealed class (NOT strings.xml)
│   └── theme/      # Material3 theme, Color, Typography
├── OpenCodeRemoteApp.kt  # @HiltAndroidApp — creates notification channel
└── ui/MainActivity.kt    # Single @AndroidEntryPoint Activity, setContent → NavHost
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Add new SSE event type | `data/api/dto/EventDtos.kt` → `data/sse/SseEventBus.kt` → `ui/chat/ChatViewModel.kt` handleEvent() | Add field to EventProperties, add when branch |
| Add new REST endpoint | `data/api/OpenCodeApiClient.kt` → `data/repository/OpenCodeRepository.kt` interface + impl | Configured per-connection, not at init |
| Add new screen | `ui/<feature>/` → `ui/AppNavigation.kt` Routes + NavHost entry | Use @HiltViewModel + hiltViewModel() |
| Add DI binding | `di/AppModule.kt` | @Provides @Singleton or @Binds for interfaces |
| Modify streaming behavior | `ui/chat/ChatViewModel.kt` handleEvent() lines 468-801 | See ui/chat/AGENTS.md for event flow |
| Change server persistence | `data/datastore/ServerManager.kt` | DataStore + encrypted passwords |
| Fix SSE connection issues | `data/api/OpenCodeSseClient.kt` + `service/SseForegroundService.kt` | Tag: "OpenCodeSSE" in logcat |
| Update i18n strings | `ui/strings/Strings.kt` | NOT res/values/strings.xml — uses Kotlin sealed class |

## CONVENTIONS

- **Code style**: Kotlin Official (IDE-enforced: `KOTLIN_OFFICIAL` in `.idea/codeStyles/`, `kotlin.code.style=official` in gradle.properties). No ktlint/detekt.
- **State management**: `MutableStateFlow<XxxUiState>` + `_uiState.update { it.copy(...) }` in all ViewModels. Single state object per ViewModel with nested data classes.
- **Exception handling**: `catch (Exception)` — NOT `catch (Throwable)`. Deliberate convention from code audit (17 replacements).
- **DI**: Everything is `@Singleton` in `SingletonComponent`. No `ActivityComponent`/`ViewModelComponent` scopes.
- **Repository pattern**: Interface extraction — `OConnectorRepository` interface + `OConnectorRepositoryImpl`, bound via `@Binds`.
- **Visibility**: `internal` for cross-file composables within the same module. Never `private` on top-level composables.
- **SSE pipeline**: Do NOT add `.buffer()` or `.flowOn()` to SSE flows — causes freeze (documented in `.sisyphus/notepads/sse-freeze-fix/learnings.md`).
- **Strings**: Defined in `ui/strings/Strings.kt` as Kotlin sealed class, NOT in `res/values/strings.xml`.
- **Testing**: JUnit 4 + MockK 1.13.9 + Turbine 1.0.0 + Robolectric 4.11.1. TDD approach.

## ANTI-PATTERNS (THIS PROJECT)

- **DO NOT** suppress type errors: `as any`, `@Suppress("UNCHECKED_CAST")` in production code forbidden. Only in tests.
- **DO NOT** add `.buffer()` or `.flowOn()` to SSE SharedFlow pipeline — documented freeze bug.
- **DO NOT** clear streaming state in `message.updated` handler — causes "response disappears" flash.
- **DO NOT** clear blocking state during reconnection — it must persist across reconnect.
- **DO NOT** guess agent via `mode=="primary"` — use server-provided agent list only.
- **DO NOT** use `isLoading` as scroll trigger — race condition with Compose layout frames.
- **DO NOT** commit `node_modules/`, `.sisyphus/`, `release/`, `*.jks` — all in `.gitignore`.

## UNIQUE STYLES

- **Connection Generation**: AtomicLong counter increments on every connect(). Events wrapped in `EventEnvelope(generation)` — filtered at both EventBus and ViewModel layers. "Upward-following" strategy: gen >= subscribed passes.
- **Triple safety net for streaming**: (1) Fallback polling every 5s when SSE stalls >15s, (2) Watchdog force-clear after 120s no-SSE, (3) Recovery heuristic checking message completion timestamps.
- **Process death resilience**: Streaming state + blocking state cached to SharedPreferences — survives ViewModel recreation and process kill.
- **SSE decoupled from UI**: `SseForegroundService` owns SSE subscription, routes through `SseEventBus`. ViewModels never touch raw SSE client.

## COMMANDS

```bash
# Build (Windows, requires JAVA_HOME)
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat compileDebugKotlin

# Test
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest

# Git push (requires proxy)
git config http.proxy "http://127.0.0.1:7890"

# Clean build cache (Windows cache corruption fix)
Remove-Item -Recurse -Force app\build
```

## KEY DEPENDENCY VERSIONS

| Dep | Version |
|-----|---------|
| Kotlin | 1.9.22 |
| AGP | 8.2.2 |
| Compose BOM | 2024.02.00 |
| Compose Compiler | 1.5.8 |
| Hilt | 2.50 |
| Ktor | 2.3.7 |
| kotlinx-serialization | 1.6.2 |
| minSdk / targetSdk | 26 / 34 |

## NOTES

- Gradle daemon disabled (`org.gradle.daemon=false`). Every build cold-starts.
- Release builds have minify OFF (`isMinifyEnabled = false`). ProGuard rules exist but unused.
- `rg` (ripgrep) not in PATH — use PowerShell `Select-String` for code search.
- `gh` CLI not available — use GitHub REST API with `$env:HTTPS_PROXY` for releases.
- `node_modules/` + `package.json` exist for `sql.js` (SQLite WASM) — not part of Android build.
- `OpenCodeRemoteApp.kt` filename ≠ class name `OConnectorApp` — confusing but not a bug.
- 1 `@Suppress("DEPRECATION")` in `ServerListScreen.kt:69` for `Icons.Default.HelpOutline` — no migration plan.
