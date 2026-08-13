# OpencodeConnector4Android 设计文档

> 本文档系统化描述 OpencodeConnector4Android 的设计、UI 与功能，是后续新系统移植的权威参考。
> 代码即真相，本文档与代码冲突时以代码为准；重大不一致请同步更新本文档。

## 项目定位

OpencodeConnector4Android 是 [OpenCode](https://github.com/sst/opencode)（v1.14.x）服务端的 Android 手机控制端。它不内嵌 AI 运行时，而是通过 REST + SSE 协议连接局域网/远端已运行的 opencode 服务，提供：

- 多服务器管理与一键连接（TLS / 自签名证书）
- 项目与会话浏览（两级：项目列表 → 项目内会话）
- 实时聊天：流式输出（thinking / text / tool 分段）、权限确认、问题问答、todo、undo/redo
- 后台保活：前台服务 + SSE 长连接 + 进程死亡自愈 + 网络恢复重连
- 干预通知：AI 需要授权/问答/任务完成时推送到手机
- 会话恢复：流式状态与阻塞状态跨进程重建存活（崩溃/杀进程后恢复）

## 技术栈

| 层 | 选型 | 版本 |
|---|---|---|
| UI | Jetpack Compose（Material 3，自定义 ColorScheme + Android 12 动态取色） | BOM 2024.02.00 |
| 导航 | navigation-compose（单 Activity 多路由 + 底栏三 tab） | 2.7.6 |
| DI | Hilt（kapt） | 2.50 |
| 网络 | Ktor 2.3.7（OkHttp 引擎），REST + SSE 长连接 | 2.3.7 |
| 序列化 | kotlinx-serialization-json | 1.6.2 |
| 异步 | kotlinx-coroutines | 1.7.3 |
| 持久化 | DataStore Preferences + EncryptedSharedPreferences（AES256-GCM 密码） | 1.0.0 / 1.1.0-alpha06 |
| 兼容 | minSdk 26 / targetSdk 34 / Java 17 | AGP 8.2.2, Kotlin 1.9.22 |

## 文档地图

| 文档 | 内容 |
|---|---|
| [architecture.md](architecture.md) | 分层架构、DI 图、状态管理、核心机制（连接/SSE/恢复/缓存） |
| [ui-design.md](ui-design.md) | 导航体系、页面设计、公共组件、主题、i18n |
| [features.md](features.md) | 功能规格：连接/会话/聊天/通知/设置/更新/备忘录 |
| [protocol.md](protocol.md) | 服务端协议：REST 端点全表 + SSE 事件全集 + 数据模型 |
| [porting-guide.md](porting-guide.md) | 新系统移植指南：架构映射、依赖清单、风险与权衡 |

## 源码地图（68 个 Kotlin 文件，约 1.4 万行）

```
app/src/main/java/com/opencode/remote/
├── data/                       # 数据层
│   ├── api/                    # Ktor REST 客户端 + SSE 客户端 + DTO
│   ├── cache/                  # MessageCache（消息两级缓存）
│   ├── datastore/              # ConnectionPreferences / ServerManager / RecentSessionStore / MemoManager
│   ├── download/               # APK 下载（DownloadManager）+ 安装（FileProvider）
│   ├── github/                 # GitHub Release 检查（多源竞速代理）
│   ├── network/                # NetworkMonitor（网络恢复回调）
│   ├── notification/           # InterventionNotifier + NotificationGate
│   ├── repository/             # OConnectorRepository（唯一业务门面）
│   ├── sessionstore/           # ActiveSessionStore / ChildSessionStore（内存状态）
│   └── sse/                    # SseEventBus（带 generation 的全局事件总线）
├── di/                         # Hilt 模块
├── service/                    # SseForegroundService / KeepAliveLifecycleObserver / SelfHealConnection
├── ui/                         # Compose UI
│   ├── chat/                   # 聊天页（最复杂，11 文件）
│   ├── serverlist/             # 服务器列表页
│   ├── recent/                 # 最近会话页
│   ├── sessions/               # 项目列表 + 项目内会话页
│   ├── connection/             # 添加/编辑服务器表单页
│   ├── settings/               # 设置页
│   ├── help/                   # 帮助页
│   ├── update/                 # 更新检查/对话框
│   ├── components/             # 公共组件（ErrorSnackbar）
│   ├── strings/                # i18n（AppLocale + AppStrings，中英双语，238 字段）
│   └── theme/                  # Material 3 主题
└── OpenCodeRemoteApp.kt / MainActivity.kt
```

## 关键设计决策摘要

1. **Repository 为唯一业务门面**：所有 REST/SSE/状态操作经 `OConnectorRepository`，UI 层不直接触网。
2. **connectionState 单一数据源**：连接状态是 Repository 级 Singleton StateFlow，UI 直接透传，避免 ViewModel 重建导致状态丢失。
3. **SseEventBus + generation**：SSE 事件全局广播；generation 单调递增（连接/网络恢复时 +1），旧世代事件在总线源头丢弃，杜绝跨连接串扰。
4. **流式状态进程级存活**：流式分段/阻塞状态落盘（500ms 防抖 + 5 分钟 TTL），崩溃后 ChatViewModel 重建可恢复。
5. **stale-while-revalidate 消息缓存**：缓存先渲染、网络后刷新，弱网/后台被杀仍能秒开会话。
6. **保活开关（keep-alive）**：开 = 前台服务 + 网络监控 + 后台断线自愈；关 = 省电模式，仅前台连接，通知靠 60s 轮询兜底。
7. **错误必须可见**：服务端拒绝（额度/auth/HTTP 4xx）的错误体原样上抛，经 ErrorSnackbar 展示，绝不静默吞错。
