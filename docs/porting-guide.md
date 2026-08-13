# 新系统移植指南

> 目标：把 OpencodeConnector4Android 的设计移植到新系统（新平台/新框架/新架构）时，本文提供架构映射、可复用资产与风险清单。**协议层（protocol.md）可整体复用；UI 层与平台强耦合需重写。**

## 1. 移植决策树

### 1.1 先回答的问题
| 问题 | 影响 |
|---|---|
| 新系统是 Android/iOS/桌面/Web？ | UI 层 + 服务层（前台服务/进程守护）方案全变 |
| 是否保留单 Activity Compose？ | 导航/状态管理框架选择 |
| 是否必须支持"通知"与"后台常驻"？ | 决定保活/通知子系统工作量 |
| 局域网 http 明文？ | TLS 自签名支持是否保留 |

### 1.2 建议移植顺序（依赖拓扑）
1. **协议层**（零平台依赖）：DTO → REST 客户端 → SSE 客户端 → 协议测试
2. **核心状态层**：连接状态机 → SseEventBus（generation）→ 流式状态机 → 消息缓存
3. **业务门面**：Repository 接口（对照 architecture.md §4-7 逐一实现）
4. **持久化**：偏好/服务器/最近会话/备忘录（DataStore → 目标平台 KV 方案）
5. **UI 壳**：导航 + 页面骨架 → 聊天页（最复杂）→ 其余页面
6. **平台能力**：后台保活 / 通知 / 深链 / 文件导出（逐平台适配）

## 2. 可整体复用的资产

| 资产 | 文件 | 可复用性 |
|---|---|---|
| DTO 全量 | `data/api/dto/*.kt` | 100%（纯数据 + kotlinx-serialization） |
| 协议测试 | `test/data/api/*` | 100% |
| SSE 客户端 | `data/api/OpenCodeSseClient.kt` | 高（仅 HttpClient 换实现） |
| REST 客户端 | `data/api/OpenCodeApiClient.kt` | 高（仅 HttpClient 换实现） |
| 事件语义 | protocol.md 事件表 | 100% |
| 流式状态机 | ChatViewModel 核心逻辑 | 高（平台绑定剥离开） |
| 恢复/看门狗策略 | architecture.md §6-7 | 100%（数值策略） |

## 3. 必须重写的层

| 层 | 原因 |
|---|---|
| Compose UI（全部页面） | 平台绑定 |
| 前台服务 / ProcessLifecycleOwner | Android 专有（iOS 用 BGTask/Web 用 SW+推送） |
| NotificationGate + InterventionNotifier | 通知 API 平台异 |
| FileProvider / DownloadManager | Android 专有 |
| 加密（EncryptedSharedPreferences） | 换平台 Keychain/Keystore |
| Logcat 导出 | 换平台日志 API |

## 4. 架构映射表（Android → 新平台）

| Android 概念 | 新平台候选 | 移植注意 |
|---|---|---|
| StateFlow + collectAsStateWithLifecycle | Combine/观察者/流 | 生命周期感知（后台停 poll 必需）的一致性 |
| ViewModel（activity 级 owner） | 页面级状态容器 | Chat 跨路由存活是硬需求 |
| SseEventBus（SharedFlow 256) | 全局多播 | 保留 DROP_OLDEST + generation 过滤语义 |
| DataStore Preferences | NSUserDefaults / localStorage | 迁移 JSON 格式不变即可 |
| SharedPreferences 状态缓存 | 平台 KV | 500ms 防抖 + 5min TTL 策略保留 |
| CoroutineScope(SupervisorJob) | 等效协程/任务模型 | applicationScope 的独立性 |
| START_STICKY 自愈 | 平台后台唤醒策略 | 差异最大，需平台专项设计 |
| ProcessLifecycleOwner | 生命周期钩子 | keepAlive 关的后台断开策略依赖它 |

## 5. 必须保留的设计不变量（从 Android 剥离后的核心）

1. **connectionState 单一数据源** + 连接/测试/SSE 三阶段管道
2. **generation 单调递增** + 事件源头丢弃陈旧世代（否则换服/重连必串扰）
3. **session.idle 是完成唯一权威信号**，且需落库重试 3 次防"回复消失"
4. **16ms delta 合并 + partTypeMap 分类 + completedMessageIds 守卫**（否则流式闪烁/重复）
5. **stale-while-revalidate 消息缓存**（弱网体验底线）
6. **三套 watchdog**（流式 120s / 阻塞 120s / 通知 120s）+ 轮询兜底分层
7. **错误必须可见**：服务端错误体原样透传 UI（额度/auth 拒绝不静默、不无限重试）
8. **分页上限**：50 → 500 翻倍窗口 + 500 条内存/30 磁盘会话缓存上限
9. **表单/构造防御**：JSON 容错（ignoreUnknownKeys/coerceInputValues/截断安全网）
10. **通知去重**：notifiedBlocked/TodoDone/TaskDone 三集合 + 投递确认 watchdog

## 6. 风险与权衡记录

| 决策 | 代价 | 若重做 |
|---|---|---|
| SSE 事件经前台服务中转（而非 ViewModel 直连） | 多一跳；FGS 吞错需显式上抛 | 保留：进程级订阅是崩溃存活的关键 |
| 流式状态持久化 500ms 防抖 | 最多丢 500ms 尾部 | 保留（5min TTL 启发式兜底已覆盖） |
| 单文件 AppStrings 238 字段 | JVM 255 参数上限逼近（仅剩 17） | 新系统直接用资源文件（若平台支持） |
| listAllSessions 多项目并行 | 慢目录阻塞其他 | 保留 catch 回退单次列表 |
| 全局 avatar 不用动态取色 fallback 需自行配色 | 视觉一致性风险 | 保留动态取色降级 |
| keepAlive 关 = 退后台直接 disconnect | 回前台刷消息闪烁 | 权衡省电，保留 |

## 7. 移植验收清单（新系统交付前逐项过）

- [ ] SSE 流式打字 60fps 无闪烁无重复（16ms 合并 + dedup）
- [ ] 会话完成后"回复消失"概率 0（session.idle 重试确认）
- [ ] 换服/重连后无陈旧事件串扰（generation 验证）
- [ ] 进程被杀后重开：流式状态恢复 / 阻塞状态恢复（5min TTL）
- [ ] 弱网：缓存秒开 + 后台刷新静默
- [ ] 服务端 429/403：错误体展示且不无限重连
- [ ] 通知：权限/问答/任务完成/阻塞四类 + 前台抑制 + 去重
- [ ] 关闭保活：退后台断开、回前台自动重连
- [ ] 200+ 会话项目列表不卡顿（状态点 item 级重组）
- [ ] 长文本不 OOM（5000/10000 截断 + 上限窗口）