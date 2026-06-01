# Changelog

All notable changes to OConnector will be documented in this file.

## [1.4.0] - 2026-05-23

### Added

- **Unified chat settings dialog** — the middle piano key in the chat input bar is now a Settings button (⚙). Tapping it opens a dialog where you can configure Agent, Model, and Variant for the current session. Changes are committed on Confirm and reverted on Cancel (committed/draft separation). Selections are persisted per-session and restored on re-entry.
- **Variant selection** — choose a specific model variant (e.g. different quantization levels) when the server provides variants. The selected variant is sent in the prompt request so the server uses the exact configuration you picked.
- **Provider/Model API** — new `GET /provider` endpoint support with `ProviderList`, `ProviderInfo`, and `ProviderModelInfo` DTOs. Models are listed with their provider name, model name, and available variants.
- **Selection persistence** — Agent, Model, and Variant choices are saved per-session to `ConnectionPreferences` and automatically restored when re-entering a chat.
- **Time-based session grouping** — sessions are grouped by time: Today, Yesterday, This Week, Older. Sticky group headers with `surfaceVariant` background. Powered by `SessionGrouper` utility with relative time display.
- **Relative time display** — session timestamps show "just now", "5m ago", "2h ago" instead of absolute dates for recent sessions.
- **Session search** — search bar at the top of the project sessions screen with debounced filtering (300ms). Filters sessions by title.
- **Active session status dots** — green pulsing dot (StatusDot) indicates active/busy sessions. `ActiveSessionStore` tracks session status from SSE events.
- **Project busy indicator** — animated dot on project cards when any session within that project is actively running.
- **Child session tree** — expand/collapse child sessions under their parent session. Indented tree layout (24dp indent) with chevron toggle. `ChildSessionStore` manages parent-child relationships via `GET /session/children` API.
- **Compact density mode** — optional compact display density for the sessions list, persisted in `ConnectionPreferences`.
- **Session summary stats** — each session card shows quick stats: number of additions (+N), deletions (-N), and file changes (Ff) derived from tool calls.
- **Agent load failure retry** — when the agent list fails to load, a retry button appears in the chat screen instead of silently failing.
- **New SSE event handlers** — `session.status` and `session.children` events processed in ChatViewModel for real-time status and hierarchy updates.
- **New MessagePart types** — support for `file`, `agent`, `snapshot`, `patch`, `retry`, `compaction`, `subtask` part types (previously only `text`, `tool-call`, `thinking`).
- **Session API endpoints** — `GET /session/status` and `GET /session/children` for session status polling and child session discovery.
- **`session.deleted` SSE handler** — deleted sessions are immediately removed from the list without waiting for a manual refresh.

### Changed

- **Chat input bar middle key** — changed from model name display to Settings trigger (⚙ icon). Tapping opens the unified settings dialog instead of a model dropdown.
- **TopAppBar AgentPicker removed** — the agent picker button previously in the top bar has been deleted. Agent selection is now exclusively in the Settings dialog.
- **Deleted dead code** — removed `ModelSelector.kt`, `ContextUsageDisplay.kt`, `AgentPickerButton.kt`, and `showAgentPicker` state — all superseded by the unified settings dialog.
- **SessionInfo DTO expanded** — added `archived`, `partID`, and `SessionPermission` pattern fields.
- **SessionsScreen redesigned** — full rewrite with time grouping, search, status dots, child trees, and summary stats. Now uses `SessionsUiState` with search/grouping/agentError fields.
- **Removed eye button from chat top bar** — child session visibility toggle moved to project session list.
- **Updated `availableModelsError` state handling** in ChatViewModel.
- **Added diagnostic SSE event logging** (`SSE DIAG:` prefix) for future debugging.

### Fixed

- **ModelRef construction robustness** — `buildModelOptions()` now uses `model.id.ifBlank { modelKey }` instead of raw map key, preventing mismatch when the server returns a different model ID than the map key.
- **Variants sorted** — variant list is now alphabetically sorted (`model.variants.keys.sorted()`) for stable UI display order.
- **Bidirectional normalize** — `normalizeSelectionState()` now validates both committed and draft selections (previously only draft), preventing stale references after `restoreSelection`.
- **modelName 3-tier fallback** — display label falls back through `model.name → model.id → mapKey` instead of just `model.name → mapKey`.
- **Child session dedup** — fixed duplicate child sessions appearing in the list. Added dedup helper and unfiltered child lookup.
- **Removed hide gate** — child sessions previously required a "show hidden" toggle to appear; now always visible in the tree.
- **Session refresh guard** — added guard to prevent concurrent session list refreshes from corrupting the UI state.
- **Startup crash when server unreachable** — deferred SSE service start until after a successful connection is verified, preventing crash on app launch when the saved server is offline.
- **SSE generation mismatch (critical)** — root cause of streaming not working. `ChatViewModel.subscribeToEvents()` read `repository.currentGeneration` which could be higher than the SSE service's actual generation (e.g. after network recovery callback incremented it). The strict `gen < subscribedGeneration` filter discarded ALL events including `message.part.delta` and `session.idle`. Text appeared all at once (via fallback polling) and spinner persisted until 120s watchdog timeout. Fix: adopt the service's generation when mismatch detected instead of discarding events.
- **SseForegroundService null-intent recovery** — when Android restarted the foreground service (e.g. after memory pressure), the null intent guard called `stopSelf()`, permanently killing SSE. Now recovers using `repository.currentGeneration` to resume the SSE subscription.
- **session.idle streaming state cleanup** — force-clear all streaming state (`isStreaming`, `isSending`, `streamingSegments`, `pendingAssistantMessageId`) in `session.idle` handler's no-content guard path, instead of leaving spinner visible indefinitely.
- **Model list display** — fixed `ProviderList` DTO to correctly parse server's `{all: [...]}` provider format with `@SerialName("all")`. Created `ModelLimitInfo` data class to handle server's `limit` field (object with context/input/output keys instead of plain integer).

## [1.3.1] - 2026-05-18

### Added

- **SSE Connection Generation** — every `connect()` now increments an `AtomicLong` generation counter. SSE events are wrapped in `EventEnvelope(generation)` and filtered at two layers: `SseEventBus` drops stale events (gen < activeGeneration), ViewModels apply "upward-following" strategy (gen >= subscribedGeneration passes). Prevents stale events from disconnected servers from polluting the UI after rapid server switching or reconnect.
- **Network state monitoring** — new `NetworkMonitor` class using `ConnectivityManager.NetworkCallback`. When the device regains network connectivity after a loss, it automatically triggers `SseForegroundService.restart()` to re-establish the SSE connection. Recovery is debounced at 3 seconds to prevent reconnect storms.
- **SSE restart with debounce** — `SseForegroundService.restart()` uses `synchronized` + `@Volatile lastRestartTime` with a 3-second debounce threshold. Prevents multiple rapid restarts from concurrent network state callbacks.
- **Idempotent connect/disconnect** — `connect()` now calls `disconnect()` first if already connected (idempotent guard). `disconnect()` wraps each cleanup step in individual try-catch to prevent partial cleanup failures from blocking the rest.
- **Comprehensive test coverage** — new tests for `SseEventBus` (generation filtering, activation), `OConnectorRepository` (generation monotonic counter, connect lifecycle), `NetworkMonitor` (start/stop guards, callback invocation), `OConnectorSseClient` (generation passed to events), `ChatViewModel` (subscribed generation filtering, upward-following), `SseForegroundService` (restart debounce, generation pass-through).

### Fixed

- **Agent picker button missing** — custom agents from the server may return `"hidden": null` instead of `false`. Since `AgentInfo.hidden` was declared as non-nullable `Boolean`, kotlinx.serialization threw `SerializationException` and the entire agent list failed to parse. Changed `hidden` to `Boolean?` and updated the filter to `it.hidden != true`. The agent picker button now appears correctly when custom agents are configured.
- **Recovery bubble false positive** — when the AI is actively working in TUI, the mobile app incorrectly showed a "Session Interrupted" recovery bubble because the last assistant message had no `completed` timestamp. Clicking "Check Status" re-triggered the same heuristic, causing an infinite loop. Fixed by checking session completion status: recovery is only triggered when the session itself is completed but the last assistant message isn't — confirming a true interruption, not active work.

## [1.3.0] - 2026-05-16

### Added

- **Multi-server management** — new Server List home screen. Add, connect, and manage multiple OpenCode servers. Each server stores its own name, host, port, credentials, and TLS settings. The app no longer boots directly into a single-server connection page.
- **Scroll-to-bottom button** — the previously unused left piano key in the chat input bar is now a "scroll to bottom" button. Taps re-enable auto-scroll and jump to the latest message.
- **Keyboard-aware auto-scroll** — when the soft keyboard opens, the chat automatically scrolls to keep the latest messages visible above the input bar.
- **Per-project memo panel** — swipe left on the project session list to open a slide-in memo panel (280dp, 350ms animation, same pattern as the chat screen file browser). Create, edit, and delete memos scoped to each project. Collapsed memos show a checkbox for marking done; expanded memos have editable title and content fields. Long-press to delete with confirmation dialog. Memos are persisted locally via DataStore and survive app restarts.

### Changed

- **Language toggle & help button moved to Server List** — removed from the Add Server / Connection screen to avoid duplication. Now only available on the Server List home screen.
- **Tips card moved to Server List** — connection tips are now shown at the bottom of the Server List screen instead of the Add Server form.
- **Update check moved to Server List** — the download button (appears when a new version is available) now shows on the Server List home screen on app launch, instead of only when visiting the Add Server page.

### Fixed

- **Auto-scroll root cause (frame race)** — initial scroll used `isLoading` (Composition phase) as trigger, but `LazyColumn.totalItemsCount` only updates in Layout phase. This frame gap caused `scrollToItem(0)` (top) instead of bottom. Replaced with `snapshotFlow { layoutCount to dataCount }` that only fires when layout catches up to data.
- **AI output scroll stuck at agent name** — `scrollToItem(N-1)` anchors viewport at the top of the `__active_assistant__` item (which contains agent name + thinking + text). Added forward `scrollBy(100_000f)` after `scrollToItem` to ensure the absolute bottom is visible.
- **Streaming output auto-scroll failure** — re-designed the entire scroll pipeline: (1) initial scroll via `snapshotFlow`, (2) user scroll tracking only after initial scroll completes, (3) content-change auto-scroll with forward correction, (4) re-enable on new message send.
- **ExpandableSegment default collapsed** — all thinking/tool/code bubbles now default to collapsed (`mutableStateOf(false)`). Previously set to expanded as a workaround for a message disappearance bug; the ViewModel retry logic now prevents that bug from recurring.
- **APK download proxied for China** — `browser_download_url` from GitHub API points to `github.com` (blocked in China). APK downloads are now automatically routed through `gh-proxy.com` mirror so users on domestic networks can update without a VPN.
- **Question/Permission bubble lost after app kill** — Q/P blocking state was stored only in memory (`@Singleton` Repository), so process death cleared it. Now persisted to `SharedPreferences` (disk) alongside memory cache. Every `save`/`clear` does dual-write; every `get` checks memory first then falls back to disk. Survives app kill, device restart, and force stop.
- **Streaming output lost after app kill** — same fix: streaming segments, pending message ID, and agent name are now persisted to disk. Re-entering a session after process death restores the AI output progress and continues streaming.
- **RecoveryBubble "Check Status" button not working** — the button called `initialize()` which was guarded by `!isBlocked` and `!recoveryPending`, so it always skipped the actual re-check. Added dedicated `recheckBlockingState()` method that clears heuristic state first, tries cache (memory→disk), then falls back to a fresh server message query.
- **Connection form scroll & password visibility** — Add Server form now scrolls when keyboard or TLS options push the Connect button off-screen. Password field has a visibility toggle.

## [1.2.0] - 2026-05-11

### Added

- **Side panel** — swipe left anywhere on screen to open a file browser and model info panel. Navigate project directories, view model/provider details, and track context window usage.
- **File preview** — tap any `.md` or `.txt` file in the file browser to preview its content inline without leaving the chat. Uses `GET /file/content?path=` API.
- **Todo task panel** — overlay panel shows active AI task progress. Badge count in toolbar. Auto-dismisses and sends notification when all tasks complete.
- **Ask/Confirm bubbles** — when the AI needs permission (tool confirmation) or asks a question, inline bubbles appear between messages and input bar. Permission bubble has Allow Once / Always Allow / Reject. Question bubble has selectable options, custom text input, Submit/Dismiss. Input is blocked while AI waits for response.
- **Sequential question answering** — when the AI asks multiple questions at once, they are presented one at a time with Back/Next navigation. Progress bar shows current step. All answers collected and submitted together.
- **Sequential permission confirmation** — multiple permission requests are queued and presented one at a time. After confirming/rejecting one, the next appears automatically.
- **Multi-select questions** — questions with `multiple: true` allow selecting several options at once.
- **Undo/Redo** — undo the last user message (soft-hides messages + rolls back file changes on server). Redo restores reverted messages. Toolbar buttons appear when applicable. Undone text is restored to the input box for easy re-editing.
- **Per-prompt model selection** — choose a different AI model for each message using a nested `{ providerID, modelID }` selector. Sends `ModelRef` in the prompt request so the server uses the exact model you picked.
- **Context usage display** — shows the current context token consumption (e.g. "32K") alongside the model selector in the input bar, sourced from the latest assistant message's `input` tokens.
- **Manual update check button** — a refresh button on the connection page lets you manually trigger an update check at any time, in addition to the automatic startup check.
- **Update check mirror sources** — GitHub + `gh-proxy.com` CDN mirrors checked concurrently with Channel racing. If any source responds, the update is detected — no longer blocked by GitHub connectivity issues.
- **Version filtering** — update check skips pre-release versions (e.g. `v1_pr1`); only stable `vX.Y.Z` releases are considered.
- **Help page expansion** — added "Model Selection", "Context Usage", "Undo/Redo", and "Update Check" sections (EN/ZH).
- **Notification deep link** — tapping a notification now navigates directly to the active chat session.
- **Child session filtering** — optional toggle to hide sub-sessions from the project list when you only want root conversations.
- **Comprehensive API documentation** — `OPENCODE_API.md` documents all REST endpoints, SSE events, and data models.

### Fixed

- **Streaming message flicker/disappear** — AI response would briefly vanish then reappear after completion. Root cause: placeholder item and final message had different LazyColumn keys, causing destroy+recreate. Replaced with a unified placeholder that uses the real message ID, so LazyColumn performs an in-place content update with zero visual discontinuity.
- **Streaming stalls and blocks** — added watchdog timer to detect and recover from stalled SSE streams. `sendMessage()` now aborts stale state on re-entry, and `initialize()` detects incomplete assistant messages on app restart.
- **Model selector not persisting** — server expects nested `{ "model": { "providerID": "...", "modelID": "..." } }` format. Fixed `SendMessageRequest` to wrap model selection in `ModelRef` DTO.
- **Context usage inaccurate** — previous implementation summed all historical tokens (double-counting). Now extracts only the latest assistant message's `input` tokens via reverse scan, and resets properly on session switch.
- **Session.idle race condition** — `session.idle` was clearing state before messages finished loading. Added delayed state cleanup with retry-based message fetch and assistant message existence verification before committing the idle transition.
- **Panel swipe conflict** — gesture detection competed with LazyColumn scroll. Replaced with cumulative drag threshold (40dp) that works from anywhere on screen.
- **Right panel overlaps status bar** — added `WindowInsets.statusBars` padding.
- **Update check fails in China** — added concurrent mirror sources with Channel racing so at least one source is reachable.
- **Gradle daemon hang** — disabled daemon in `gradle.properties`.

### Changed

- **Chat input bar redesign** — three-section piano-key style bar: model selector segment | context usage indicator | expandable text input + send button. Compact when collapsed, full-featured when expanded.
- **SSE pipeline optimization** — EventBus buffer 256 (DROP_OLDEST). Delta events 16ms coalescing window. Heartbeat timeout 45s. Incremental `message.updated` processing for all roles.
- **Dual-channel refresh** — 3s message polling alongside SSE as safety net. Sessions page: SSE + 30s polling. Fallback polling only activates after 15s SSE silence.
- **Memory optimization** — message list capped at 150 messages. Streaming text truncated at 10K characters.
- **Smart auto-scroll** — only scrolls to bottom when user is already near the bottom. Scrolling up to read history no longer interrupted.
- **ExpandableSegment default** — code/text segments default to expanded, preventing "content disappeared" feeling.
- **Panel animation** — `FastOutSlowInEasing(350ms)` for smoother slide.
- Internal refactoring: `ChatUiState` split into `SessionMetaState`, `StreamingDisplayState`, `ChatDisplayState`.

## [1.1.2] - 2026-05-07

### Added

- **Background SSE stability** — foreground service with `dataSync` type keeps the SSE connection alive when the app is in the background. A persistent notification ("OConnector is running") is displayed to comply with Android 14+ requirements.
- **In-app update check** — the app automatically checks GitHub Releases for new versions when you open the connection page. If an update is available, a download icon (⬇) appears next to the language button.
- **Update dialog** — tapping the download icon shows the changelog and a download button. The APK is downloaded via DownloadManager and installed through the system installer.
- **Help page: "Check for Updates"** section added (EN/ZH).

### Fixed

- **API 26-28 compatibility** — `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` requires API 29+. Added SDK version check to fall back to the 2-param `startForeground()` on older devices.
- **i18n for update UI** — UpdateDialog now uses localized strings from `AppLocale` instead of hardcoded English text.

### Changed

- Help page version number now uses `BuildConfig.VERSION_NAME` instead of a hardcoded string.
- APK no longer tracked in the git repository — distributed via GitHub Releases instead.
- `release/` directory added to `.gitignore`.

## [1.1.1] - 2026-05-07

### Added

- **HTTPS (TLS) support** — new "Use HTTPS (TLS)" toggle on the connection screen. Enable it when connecting through an HTTPS reverse proxy (e.g. Lucky, Nginx, Caddy). The app will use `https://` instead of `http://` for all API and SSE requests.
- **Self-signed certificate support** — when TLS is enabled, an additional "Allow untrusted certificates" toggle appears. Turn it on to connect to servers using self-signed certificates (common in home LAN setups). Both settings are persisted across app restarts.

### Fixed

- **Chinese/non-ASCII path encoding** — loading sessions from directories containing Chinese characters (e.g. `C:\Users\...\论文阅读`) would fail with `Unexpected char 0x8bba in x-opencode-directory`. The `x-opencode-directory` HTTP header now properly URL-encodes the path value, making it compatible with RFC 7230 (ASCII-only headers).

## [1.1.0] - 2026-05-05

### Added

- **Multi-project auto-discovery** — the app now queries all known OpenCode projects in parallel and merges sessions from every project directory. Works regardless of which directory `opencode serve` was started from.

### Fixed

- **Dark mode** — was completely non-functional (theme wrapper was never applied to the activity). Added a manual 🌙/☀️ toggle button in the top bar, persisted in DataStore.
- **Todo button visibility** — the Todo button was hidden when the list was empty. Now always visible (badge when items exist, plain icon when empty). Also added `message.part.completed` SSE handler for real-time Todo updates.
- **Tool call rendering** — sequential tool calls were overwriting each other because `putSegment()` only matched by type. Added `callID` tracking so each tool call gets its own segment.
- **Out-of-memory crash** — 108MB allocation crash when loading long sessions. Added `?limit=50` to the messages API. Removed the broken `truncateLargeJsonStrings()` which corrupted JSON escape sequences and caused messages/sessions to disappear silently.
- **Session sorting** — sessions were sorted by creation time. Now sorted by last updated time.
- **Status bar color** — status bar and navigation bar now match the surface color in both light and dark modes.
- **SSE streaming freeze** — streaming responses would randomly stall and require manual refresh. Root cause: `trySend()` in `channelFlow` silently dropped SSE events when the main thread collector was busy. Replaced with `send()` which provides proper backpressure. Also batched per-delta logging (every 50th event) to reduce JNI overhead on the main thread.

### Changed

- Server startup script no longer requires starting from a specific directory.
- `listSessions()` now accepts an optional `scope` parameter for server-side project filtering.

## [1.0.0] - 2026-04-30

### Added

- Initial release.
- Connect to OpenCode server via IP + port.
- Browse projects and sessions.
- Real-time AI chat with SSE streaming.
- Tool call rendering (thinking bubbles, tool summaries).
- Todo task panel overlay.
- Agent picker (switch between AI agents).
- Session fork / delete.
- EN/ZH bilingual toggle.
- Password encrypted storage.
- SSE auto-reconnection with exponential backoff.
