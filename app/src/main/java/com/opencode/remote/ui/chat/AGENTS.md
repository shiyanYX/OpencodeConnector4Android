# Chat Module — AGENTS.md

**Generated:** 2026-05-18
**Commit:** 159e723

## OVERVIEW

Core chat feature. Streams AI responses via SSE, renders markdown/code bubbles, handles permissions and questions inline. ChatViewModel.kt (77KB, ~1600 lines) is the single biggest complexity hotspot in the entire project.

## FILES

| File | Size | Purpose |
|------|------|---------|
| `ChatViewModel.kt` | 77KB | SSE event handling, state management, streaming segments, blocking state, process death recovery |
| `ChatScreen.kt` | 30.8KB | Main composable. LazyColumn with auto-scroll, message list, streaming display |
| `ChatComponents.kt` | 23.6KB | Input bar, message bubbles, code blocks, tool call cards |
| `OverlayComponents.kt` | — | Todo panel overlay, agent picker overlay |
| `ChatUiState.kt` | — | UI state data classes (may be nested in ChatViewModel) |

## STATE CLASSES

ChatUiState is a composite. Sub-states delegate property reads for backward compatibility.

| Sub-state | Holds | Key fields |
|-----------|-------|------------|
| `SessionMetaState` | Session identity | sessionId, directory, title, status, revertMessageId |
| `StreamingDisplayState` | Streaming lifecycle | isStreaming, isSending, streamingSegments, pendingAssistantMessageId |
| `ChatDisplayState` | Rendered content | messages, inputText, error, todoItems, agents, models, files, blocking state |

## EVENT FLOW

```
SSE Server
  ↓
SseForegroundService
  ↓
SseEventBus (SharedFlow + generation filter)
  ↓
ChatViewModel.subscribeToEvents() — gen >= subscribedGeneration passes
  ↓
handleEvent() (lines 468-801)
  ├─ message.part.delta  → 16ms batch coalesce → appendToLastSegment()
  ├─ message.part.updated → putSegment() full-text replace (dedup)
  ├─ message.updated (assistant) → set pendingAssistantMessageId, start streaming
  ├─ message.updated (user) → reload messages
  ├─ session.idle → reload messages (3 retries), clear streaming
  ├─ permission.asked → set pendingPermission, start blocking watchdog, queue if busy
  ├─ question.asked → set pendingQuestion, start blocking watchdog
  ├─ session.error → set error, clear streaming
  ├─ todo.updated → refresh todo list
  ├─ session.compacted / session.diff → state sync
  ↓
_uiState.update { it.copy(...) }
  ↓
ChatScreen recomposition
```

## INITIALIZATION ORDER

1. `initialize(sessionId, directory)` loads messages + session info + agents + models + files + todos
2. `subscribeToEvents()` collects SseEventBus with generation filter
3. Load state FIRST, then subscribe. Wrong order causes flash between empty/streaming/idle states
4. Stale coroutine guard: checks sessionId hasn't changed mid-initialize

## SAFETY NETS

- **Fallback polling** (lines 1382-1414): Every 5s if no SSE events in 15s, polls REST for new messages
- **Streaming watchdog** (lines 1421-1472): Force-clears streaming state after 120s with no SSE activity
- **Recovery heuristic** (lines 1503-1540): Checks last assistant message completion timestamp to detect stalled streams

## BLOCKING STATE (Permissions + Questions)

- `pendingPermission` / `pendingQuestion` processed one at a time, queued if multiple arrive
- Blocking watchdog auto-clears after 120s timeout
- Cached to SharedPreferences keyed by sessionId
- Survives ViewModel recreation and process death

## AUTO-SCROLL

Uses `snapshotFlow { layoutCount to dataCount }`, NOT isLoading as trigger. Race condition: Compose layout frame vs data update timing. scrollToBottom fires only when new messages exist AND layout is stable.

## ANTI-PATTERNS (DO NOT VIOLATE)

- **DO NOT** clear streamingSegments or isStreaming in `message.updated` handler (line 548) — causes "response disappears" flash
- **DO NOT** clear streaming state in `session.idle` — keep segments visible until reload completes (line 598)
- **DO NOT** clear blocking state during reconnection — it must persist (line 692)
- **DO NOT** guess agent via `mode=="primary"` — use server-provided agent list only (line 893)
- **DO NOT** use isLoading as scroll trigger — use snapshotFlow pattern (ChatScreen line 77)
- **DO NOT** add `.buffer()` or `.flowOn()` to SSE flows — documented freeze bug

## NOTES

- Generation filtering uses "upward-following" strategy: subscribedGeneration auto-updates to higher gen values
- Process death recovery checks SharedPreferences cache for streaming + blocking state on ViewModel init
- StreamingSegment list accumulates text chunks; each segment has an ID for dedup/replace
- Delta events append to last segment (16ms batch window); part.updated replaces entire segment text
- ChatViewModel is the only file that mutates streaming state directly. All other consumers are read-only
