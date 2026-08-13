# 功能规格

## 1. 服务器管理

| 功能 | 说明 |
|---|---|
| 多服务器 | ServerManager（DataStore JSON），按 id / host+port+username 去重 |
| 连接 | connectAndVerify 单管道：连接 → 连通测试 → 启动 SSE |
| 密码加密 | EncryptedSharedPreferences AES256-GCM（keystore 失效优雅降级） |
| TLS | 支持 https + 自签名（不校验信任链 + hostnameVerifier 放行） |
| 自动连接 | 启动时读 lastActiveServerId 自动连接（静默，不导航） |
| 编辑 | 编辑当前连接服务器自动断开；空白密码保留旧密码 |
| 删除 | 卡片长按/删除键 + 确认对话框 |
| 迁移 | 单服务器旧配置（connection_prefs）自动迁移为 ServerInfo |

## 2. 会话与项目

| 功能 | 说明 |
|---|---|
| 项目列表 | listAllSessions：GET /project 发现项目 → 并行拉各目录会话 → 按 id 去重合并；失败回退单次列表 |
| 时间分组 | TODAY / YESTERDAY / THIS_WEEK / OLDER stickyHeader |
| busy 检测 | ActiveSessionStore（SSE + 30s 轮询 + REST /session/status 合并） |
| 子会话树 | 父会话展开箭头 + 缩进子卡；SSE 注册 + 首展 REST 刷新 |
| 搜索 | 300ms 防抖，匹配 title/slug/id |
| Fork / 删除 | SessionCard 菜单；删除确认 |
| 新建会话 | ExtendedFAB → POST /session → 自动跳聊天 |
| 备忘录 | MemoManager（DataStore），按项目目录作用域，侧滑面板管理 |
| 密度 | DEFAULT / COMPACT 切换（持久化） |

## 3. 聊天

### 3.1 消息流式
| 机制 | 说明 |
|---|---|
| 发送 | POST /prompt_async（204 异步）+ 乐观本地消息（local_ 前缀） |
| 流式管线 | message.part.delta → pendingDeltas 队列 → **16ms 帧合并** → 按 partTypeMap/field 分类 → 追加段（同 type+同 callID） |
| 全量替换 | message.part.updated（含 dedupMisclassifiedText 修正 delta 误分类） |
| 完成信号 | **session.idle 为主**：3 次重试确认 assistant 消息落库（防"回复消失"闪烁）→ 原子清流 |
| 未落库兜底 | 重试后仍无内容 → 强制清流（不显示永久转圈） |
| 完成守卫 | completedMessageIds（防迟到 message.updated 重触发） |
| 看门狗 | 流式 120s 无事件强制清理 |
| 崩溃恢复 | 流式状态 500ms 防抖落盘 + 5min TTL，重建后恢复 |

### 3.2 流式分段类型
| 类型 | 渲染 |
|---|---|
| thinking | 可折叠卡（Psychology 图标），流式自动展开，结束显示时长 |
| tool | ToolSummarizer 摘要（Build 图标） |
| text | Markdown 渲染 |
| file | AttachFile 卡片 |

### 3.3 权限与问题（阻塞交互）
| 功能 | 说明 |
|---|---|
| 权限气泡 | permission.asked → Allow Once / Always Allow / Reject + 拒绝原因 |
| 问题向导 | question.asked → 多步（选项/自定义/提交/忽略） |
| 队列 | permissionQueue 排队处理 |
| 看门狗 | 阻塞 120s 自动清除 + Toast |
| 恢复 | 缓存优先 → 启发式检测（最后 assistant 无 completed + 会话已完成） |

### 3.4 工具
| 功能 | 说明 |
|---|---|
| Todo | todo.updated 事件驱动刷新；完成通知（5s 超时去重） |
| 文件浏览 | /file 树 + .md/.txt 内联预览（200dp 等宽滚动） |
| undo/redo | revert/unrevert（软隐藏 + 文件回滚），乐观后刷新 |
| 命令 | GET /command（slash 命令 + skills），`/` 选择器，实际执行走 sendMessage 路由 |
| Agent/Model/Variant | GET /agent + /provider，draft/committed 分离，按会话持久化选择 |
| 上下文用量 | 最后带 token 的 assistant 消息聚合（total 优先，否则五项求和） |
| 分页 | 滚顶加载旧消息（50→500 翻倍窗口合并） |

## 4. 后台与保活

| 功能 | keepAlive 开 | keepAlive 关（省电） |
|---|---|---|
| 前台服务 | ✅（通知 ID 1001 静默） | ❌ |
| SSE 长连接 | ✅ 常驻 | 仅前台活跃 |
| 退后台 | 保持连接 | disconnect() |
| 回前台 | 保持 | SelfHealConnection.heal() 重连 |
| 网络恢复 | gen+1 + Service.restart（3s 防抖） | — |
| 进程被杀 | START_STICKY 自愈重连 | — |
| 通知 | 实时（SSE 事件驱动） | 60s 轮询兜底 |

## 5. 通知（InterventionNotifier）

| ID | 类型 | 触发 |
|---|---|---|
| 2001 | action | permission.asked / question.asked |
| 2002 | task-done | session.execution.succeeded（+ activeSessionId 匹配 + gate） |
| 2003 | todo-done | todo.updated 全部 completed（去重） |
| 2004 | blocked | 60s 轮询启发式判定阻塞 |

- **NotificationGate**：开关 + 前台抑制（前台且当前会话=通知会话则不打扰）
- **watchdog 120s**：投递确认后取消（Doze 兼容）
- 深链：点击通知 → MainActivity → 清栈 → 自动连接 → 直达聊天

## 6. 设置与更新

| 功能 | 说明 |
|---|---|
| 语言 | zh / en（AppLocale 内存态 + DataStore） |
| 深色模式 | AppLocale 驱动 + 动态取色 |
| 通知开关 | preferences.notifications_enabled |
| 保活开关 | preferences.keep_alive_enabled（见 §4） |
| 电池优化 | 检测 isIgnoringBatteryOptimizations → 系统请求页（恢复前台重查） |
| 更新检查 | GitHub Release（`^v\d+(\.\d+)*$` 纯版本 tag + VersionComparator） |
| 更新下载 | DownloadManager + FileProvider 安装；GitHub 代理 URL 竞速（gh-proxy.com） |
| 导出日志 | logcat -d -t 5000 → FileProvider → 分享 Intent |

## 7. 稳定性设计

1. **单点连接**：同一时刻一个服务器连接；换服清缓存（messageCache.clearAll + 活动会话清空）
2. **陈旧事件防护**：generation 单调递增，总线源头丢弃旧世代事件
3. **自愈三路径**：网络恢复 / 进程重启 / 前后台切换
4. **看门狗三件套**：流式 120s / 阻塞 120s / 通知 120s
5. **轮询兜底**：SSE 停滞 15s 后 5s 轮询（仅 RESUMED）+ 通知 60s 轮询（仅后台）
6. **内存防护**：消息 500 条上限 + DTO 5k / 流式 10k 截断 + 大堆 largeHeap
7. **错误可见**：服务端拒绝错误体原样展示，绝不静默
