# 架构设计

## 1. 分层架构

```
┌─────────────────────────────────────────────────────┐
│ UI 层（Compose）                                     │
│  ServerList / Recent / Sessions / Chat / Connection │
│  / Settings / Help / Update                          │
│  ViewModel ⇄ Repository 单向数据流（StateFlow）       │
└──────────────────────┬──────────────────────────────┘
                       │ 调用
┌──────────────────────▼──────────────────────────────┐
│ Repository 层（唯一业务门面）                         │
│  OConnectorRepositoryImpl（@Singleton）              │
│  · connectionState 单一数据源                        │
│  · connectAndVerify 统一连接管道                     │
│  · 流式/阻塞状态（内存 + 磁盘恢复）                   │
└───────┬──────────────────────────┬──────────────────┘
        │                          │
┌───────▼────────┐       ┌─────────▼─────────────────┐
│ Data 层        │       │ Service 层                 │
│ · REST/SSE     │       │ · SseForegroundService    │
│ · DataStore    │       │ · KeepAliveLifecycleObserver│
│ · Cache        │       │ · SelfHealConnection      │
│ · Notification │       │ · NetworkMonitor          │
│ · SessionStore │       └───────────────────────────┘
└────────────────┘
```

**核心原则**：
- UI 层不触网、不直接读写 DataStore（密码除外，经 ServerManager）
- Repository 是 REST/SSE 之外所有状态操作的唯一入口
- 前台服务持有 SSE 订阅（进程级生命周期，独立于任何 ViewModel），事件经 SseEventBus 广播
- Singleton 内存状态（ActiveSessionStore / ChildSessionStore / MessageCache）与 DataStore 持久化分离

## 2. DI 图（Hilt SingletonComponent）

```
OConnectorRepositoryImpl
 ├─ OConnectorApiClient(json)          # REST（30s 超时，TLS insecure 可配）
 ├─ OConnectorSseClient(json)          # SSE 长连接（45s 心跳，指数退避）
 ├─ Context(application)
 ├─ Json(ignoreUnknownKeys, isLenient, ...)
 ├─ NetworkMonitor(context)            # 网络恢复回调
 ├─ MessageCache(context)              # 消息两级缓存
 └─ ConnectionPreferences(context)     # DataStore + EncryptedSharedPreferences

其他 Singleton：
 ServerManager(context, json)          # 多服务器存储
 MemoManager(context, json)            # 备忘录
 SseEventBus                           # 事件总线
 NotificationGate / InterventionNotifier
 ActiveSessionStore / ChildSessionStore
 KeepAliveLifecycleObserver            # ProcessLifecycleOwner
 UpdateRepository / GitHubReleaseService
 @Named("applicationScope") CoroutineScope   # SupervisorJob + Dispatchers.Default
```

## 3. 状态管理全景

| 状态 | 载体 | 生命周期 | 说明 |
|---|---|---|---|
| 服务器列表 | ServerManager（DataStore Flow） | Singleton | `observe()` 全量，按 id 去重 |
| 连接状态 | `Repository.connectionState` | Singleton | sealed：Disconnected/Connecting/Connected(serverId)/Failed(msg) |
| 会话 busy/idle | `ActiveSessionStore.statusMap` | Singleton 内存 | SSE + 30s 轮询 + REST /session/status 合并 |
| 父子会话 | `ChildSessionStore.childrenMap` | Singleton 内存 | SSE + 首展 REST 刷新 |
| 最近会话 | `RecentSessionStore` | Singleton 持久化 | DataStore JSON，10 条上限 |
| 备忘录 | `MemoManager` | Singleton 持久化 | 独立 DataStore，按 directory 键控 |
| 消息缓存 | `MessageCache` | Singleton | 内存 LRU 20 + 磁盘 30 会话 |
| 流式/阻塞恢复 | Repository 内存字段 + 磁盘 | Singleton | 500ms 防抖 / commit 同步，5min TTL |
| SSE 事件 | `SseEventBus`（generation 信封） | Singleton | SharedFlow，256 缓冲 DROP_OLDEST |
| App 语言/深色 | `AppLocale`（mutableStateOf） | 进程级 | Compose 自动重组 |
| 页面状态 | 各 `XxxUiState`（MutableStateFlow） | ViewModel 级 | Chat 用 activity 级 owner 跨路由存活 |

## 4. 连接与自愈机制

### 4.1 connectAndVerify（统一连接管道）
1. `connectionState = Connecting`
2. `connect(config)`：拼 baseUrl（`useTls ? https : http`）→ configure API/SSE client → `connected = true` → **`connectionGeneration.incrementAndGet()`**
3. `testConnection()`（GET /project/current，catch 返回 false）
4. 成功 → 记录 activeServerId → `startSseService()` → `Connected(serverId)`
5. 失败 → `disconnect()` → `Failed("connection test failed")`

### 4.2 generation 机制（防事件串扰）
- `connectionGeneration: AtomicLong`，连接与网络恢复时自增
- SseForegroundService 启动/重启时 `eventBus.activateGeneration(gen)`
- `SseEventBus.emit(event, gen)` 丢弃 `gen < activeGeneration` 的事件
- 双重重连防护：SseEventBus 层 + service 层 `activeGeneration == generation` 去重 + restart 3s 防抖

### 4.3 三种恢复路径
| 场景 | 机制 |
|---|---|
| 网络断开后恢复 | NetworkMonitor.onNetworkAvailable → gen+1 → Service.restart（3s 防抖）→ 重新订阅 SSE |
| 进程被系统杀死 | START_STICKY → onStartCommand(null) → SelfHealConnection.heal()（纯持久化数据重连，无 UI）→ 重新订阅 |
| 保活关（省电） | onStop → disconnect；onStart → heal() 重连 |

### 4.4 keepAlive 门控
- `startSseService()`：keepAlive 关 → 不启动前台服务与网络监控（连接仅前台有效）
- `setKeepAliveEnabled(enabled)`：false → stop FGS + stop NetworkMonitor；true 且已连接 → `startKeepAliveComponents()`
- `isKeepAliveEnabled()`：内存缓存 `keepAliveCached`（首读 DataStore，异常默认 true）&& `keepAliveRunning`

## 5. SSE 长连接

```
GET {baseUrl}/global/event
Accept: text/event-stream · Cache-Control: no-cache · Basic Auth
```

- 逐行读 `data:` 前缀 JSON；`:` 注释行刷心跳；解析失败仅告警
- **心跳 45s**：5s 周期检查，无任何行则取消 channel 重连
- **重连退避**：5s×2^n 封顶 30s，最多 5 次（5/10/20/30/30），每次重建 HttpClient 防泄漏
- **决定性错误**：非 2xx（auth/额度门）读取错误体（≤2000 字符）抛 IOException，**不重试**，上抛给 UI 显示
- autoReconnect=false 时任何断连直接终止

## 6. 流式与阻塞状态恢复（崩溃存活）

### 流式状态
- 内存：`_streamingSessionId / _streamingBlocks / _streamingAgent / _streamingPendingMsgId`（主线程无锁）
- 磁盘：SharedPreferences `"opencode_state_cache"`，键 `stream_*` + 时间戳
- **500ms 防抖**：SSE ~60 次/秒的写合并为 tail-following 单次 apply()
- **5 分钟 TTL**：恢复时超时视为过期
- ChatViewModel.initialize 时序：加载消息 → 恢复流式状态 → 订阅 SSE → 恢复阻塞状态 → 启动兜底轮询

### 阻塞状态
- `saveBlockingState / getBlockingState / clearBlockingState`
- 内存 map + 磁盘 `block_$sessionId`（**commit() 同步写**）+ 时间戳，5min TTL
- 启发式检测：最后 assistant 消息无 completed 时间戳 + 会话已完成 = 中断，弹恢复气泡

## 7. 消息缓存（stale-while-revalidate）

- 内存：访问序 LRU LinkedHashMap，20 会话上限
- 磁盘：`cacheDir/message_cache/<sha1(sessionId)>.json`，30 会话按 mtime 清扫
- 每会话 500 条上限；`merge` 按 id 并集保序
- **唯一填充入口**：`repository.getMessages()`（初始化/重载/分页/session.idle/undo/redo 全走这里）
- 服务器切换 `switchToServer` → `messageCache.clearAll()` 防跨服务器泄漏

## 8. 通知系统

```
NotificationGate（前台抑制逻辑）
 └─ shouldNotify(sessionId) = 开关开 && (!前台 || 当前会话 != sessionId)
InterventionNotifier
 ├─ 4 类通知：ID 2001-2004（action/task-done/todo-done/blocked）
 ├─ watchdog 120s：投递确认后取消（兼容 Doze 延迟）
 ├─ 60s 轮询兜底：SSE 断开时用 getMessages(limit=5) 启发式判阻塞
 ├─ 去重集合：notifiedBlocked / notifiedTodoDone / notifiedTaskDone
 └─ 深链：PendingIntent → MainActivity(sessionId, directory)
```

## 9. 关键数值速查

| 项 | 值 |
|---|---|
| REST 超时 | request/socket 30s，connect 10s |
| SSE 心跳 / 重连 | 45s / 5s×2^n 封顶 30s，最多 5 次 |
| 消息默认 limit | 50（分页窗口 50→500 翻倍） |
| MessageCache | 内存 20 会话 + 磁盘 30 文件 + 每会话 500 条 |
| 流式持久化 | 500ms 防抖，5min TTL |
| 流式文本截断 | 10,000 字符 |
| DTO 文本截断 | 5,000 字符 |
| 流式 watchdog | 120s 无事件强制清流（15s 检查） |
| 阻塞 watchdog | 120s 自动清除 |
| 通知 watchdog | 120s |
| 轮询兜底 | 60s（limit=5） |
| 服务重启防抖 | 3000ms |
| delta 批量合并 | 16ms（帧对齐） |
| 最近会话上限 | 10 条 |
| session.idle 消息重试 | 3 次，300ms×attempts 递增 |
| agent 缓存 TTL | 30s |
