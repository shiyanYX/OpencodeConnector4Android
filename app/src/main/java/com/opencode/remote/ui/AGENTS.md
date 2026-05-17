# UI Layer — AGENTS.md

**Generated:** 2026-05-18

## OVERVIEW

Single Activity + NavHost driving 7 Compose screens. All state flows through `MutableStateFlow<UiState>` collected via `collectAsStateWithLifecycle()`. No XML layouts, no LiveData.

## STRUCTURE

```
ui/
├── chat/            # Core chat — see chat/AGENTS.md, do NOT duplicate here
├── components/      # ErrorSnackbar.kt — shared 5s auto-dismiss error display
├── connection/      # ConnectionScreen + ConnectionViewModel — add/edit server form
├── help/            # HelpScreen — uses UpdateViewModel from update/
├── serverlist/      # ServerListScreen + ServerListViewModel — card grid + auto-connect
├── sessions/        # SessionsScreen, ProjectSessionsScreen, SessionsViewModel, SessionCard
├── strings/         # Strings.kt — Kotlin sealed class i18n (zh + en), NOT strings.xml
├── theme/           # Theme.kt, Color.kt, Typography.kt — Material3 + dark mode via AppLocale
├── update/          # UpdateViewModel + UpdateScreen — in-app update flow
├── AppNavigation.kt # Routes object, NavHost, OConnectorApp composable, deep link handling
└── MainActivity.kt  # Single @AndroidEntryPoint Activity, setContent → NavHost
```

## ROUTES

| Route | Path | Screen | ViewModel |
|-------|------|--------|-----------|
| SERVER_LIST | `"serverList"` | ServerListScreen | ServerListViewModel |
| ADD_SERVER | `"addServer"` | ConnectionScreen(mode=ADD) | ConnectionViewModel |
| CONNECTION | `"connection"` | ConnectionScreen (legacy) | ConnectionViewModel |
| SESSIONS | `"sessions"` | SessionsScreen | SessionsViewModel |
| PROJECT_SESSIONS | `"project/{directory}"` | ProjectSessionsScreen | SessionsViewModel (shared) |
| CHAT | `"chat/{sessionId}?directory={directory}"` | ChatScreen | ChatViewModel |
| HELP | `"help"` | HelpScreen | UpdateViewModel |

Start destination: `SERVER_LIST`. Notification deep links stored in `pendingDeepLink` state, redirected after auto-connect completes.

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Add a new screen | `ui/<feature>/` → `AppNavigation.kt` Routes + NavHost entry | @HiltViewModel + hiltViewModel() |
| Change navigation flow | `AppNavigation.kt` | Deep link logic lives here, not in screens |
| Add/modify user-facing text | `strings/Strings.kt` | Kotlin sealed class, NOT res/values/strings.xml |
| Toggle dark mode | `theme/Theme.kt` reads `AppLocale.darkMode` | Dynamic color scheme via Material3 |
| Fix error display behavior | `components/ErrorSnackbar.kt` | 5s LaunchedEffect auto-dismiss |
| Add server form field | `connection/ConnectionScreen.kt` + `ConnectionViewModel.kt` | Validation in ViewModel |
| Change session list behavior | `sessions/SessionsViewModel.kt` | 30s polling + SseEventBus subscription |
| Auto-connect logic | `serverlist/ServerListViewModel.kt` | Handles pendingDeepLink redirect |
| Update check flow | `update/UpdateViewModel.kt` → `help/HelpScreen.kt` | HelpScreen is the UI host |

## CONVENTIONS

- **State**: Every ViewModel has a single `XxxUiState` data class (nested sub-states OK). Exposed as `StateFlow`, updated via `_uiState.update { it.copy(...) }`.
- **Composables**: Stateless functions receiving state + callback lambdas. No business logic in @Composable.
- **Visibility**: `internal` for cross-file composables within the module. Never `private` on top-level composables.
- **DI**: All ViewModels are `@HiltViewModel + @Inject constructor`. No `ActivityComponent` or `ViewModelComponent` scopes.
- **Error handling**: `uiState.error` string fed to `ErrorSnackbar`. Errors cleared by user dismiss or 5s timeout.
- **No LiveData**: `StateFlow` exclusively. `collectAsStateWithLifecycle()` in screens.
- **i18n**: All strings in `strings/Strings.kt` as Kotlin sealed class with `zh`/`en` branches via `AppLocale`. Never touch `res/values/strings.xml`.
- **Chat module**: See `chat/AGENTS.md` for chat internals. This file does not repeat them.
