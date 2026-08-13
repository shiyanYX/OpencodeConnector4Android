# 服务端协议（OpenCode v1.14.x）

> 移植新系统时，协议层是唯一与 opencode 服务端耦合的部分，可整体复用。

## 1. 认证与传输

- **Basic Auth**：`Authorization: Basic base64(username:password)`；密码空 → 匿名（不带头）
- 目录限定：query `directory` + header `x-opencode-directory`（URL 编码，RFC 7230 需 ASCII）
- 明文 http 默认支持（局域网）；https 可选不校验证书

## 2. REST 端点全表

| 方法 | 端点 | 说明 |
|---|---|---|
| GET | `/session?list&directory=&scope=` | 会话列表（可选目录/作用域过滤） |
| GET | `/session/{id}` | 会话详情（title/version/revert/时间戳） |
| POST | `/session` | 创建会话（body `{}`） |
| DELETE | `/session/{id}` | 删除 |
| POST | `/session/{id}/fork` | Fork |
| POST | `/session/{id}/abort` | 中止 |
| POST | `/session/{id}/revert` | Undo（body `{messageID}`） |
| POST | `/session/{id}/unrevert` | Redo |
| GET | `/session/{id}/message?limit=N` | 消息（cursor 分页，最近 N 条） |
| POST | `/session/{id}/prompt_async` | 发送消息（204 异步；body parts+agent+model+variant） |
| POST | `/session/{id}/command` | Slash 命令（body command+arguments+agent+model） |
| GET | `/session/{id}/todo` | Todo 列表 |
| GET | `/session/status` | 全部会话状态 Map（id→busy/idle） |
| GET | `/session/{id}/children` | 子会话列表 |
| POST | `/permission/{id}/reply` | 权限回复（once/always/reject + message） |
| POST | `/question/{id}/reply` | 问题回复（answers: List<List<String>>） |
| POST | `/question/{id}/reject` | 忽略问题 |
| GET | `/project/current` | 当前项目（连通性探测也用此端点） |
| GET | `/project` | 项目列表 |
| GET | `/agent` | Agent 列表（过滤 subagent/hidden） |
| GET | `/provider` | Provider 列表（含 models Map、variants） |
| GET | `/file?path=` | 目录列表 |
| GET | `/file/content?path=` | 文件内容 |
| GET | `/command` | Slash 命令 + skills |
| GET | `/global/event` | SSE 事件流（Accept: text/event-stream） |

## 3. SSE 事件全集

格式：`data: {"directory":..,"project":..,"payload":{"type":..,"properties":{..}}}`（每行一个完整 JSON）

| 事件 | 关键字段 | 消费方 | 用途 |
|---|---|---|---|
| `server.connected` | — | — | 握手 |
| `session.created` | sessionID | Sessions/ChildStore | 新会话 |
| `session.updated` | info | Sessions/Chat | 元数据变化 |
| `session.deleted` | sessionID | Sessions/ChildStore | 删除清理 |
| `session.status` | status.type(busy/idle) | ActiveSessionStore | busy 呼吸灯 |
| `session.idle` | — | Chat | **AI 回合完成主信号** |
| `session.error` | error | Chat | **错误展示**（含服务端拒绝原因） |
| `session.compacted` | — | Chat | 上下文压缩重载 |
| `session.execution.started` | sessionID | InterventionNotifier | 重置去重 |
| `session.execution.succeeded` | sessionID | InterventionNotifier | task-done 通知 |
| `message.updated` | info(role/agent/tokens) | Chat | 消息创建/更新；assistant 触发思考开始 |
| `message.completed` | messageID | Chat | 完成标记 |
| `message.part.updated` | part(全量 text/tool/state) | Chat | 分段全量替换 |
| `message.part.delta` | delta + field | Chat | **流式增量（APPEND）** |
| `message.part.completed` | partID | Chat | 单段结束 |
| `permission.asked` | id/permission/patterns/always | Chat/Notifier | 权限确认 |
| `permission.replied` | id | Notifier | 清通知 |
| `question.asked` | questions | Chat/Notifier | 问题向导 |
| `question.replied` / `rejected` | id | Notifier | 清通知 |
| `todo.updated` | items | Chat/Notifier | Todo 刷新/完成通知 |
| `project.updated` | name/path | — | 项目元数据 |
| `vcs.branch.updated` | branch/previousBranch | — | 分支变化（日志） |
| `sync` | — | — | 内部同步，忽略 |

## 4. 关键数据模型

### MessagePart.type 全集
`text / step-start / step-finish / reasoning / tool-call / file / agent / snapshot / patch / retry / compaction / subtask / tool`

### 消息结构
```
MessageInfo { info: {id, role(user/assistant), agent, mode, model, time, tokens, variant},
              parts: [MessagePart] }
Tokens.tokenTotal(): total 优先，否则 input+output+reasoning+cacheRead+cacheWrite 求和
```

### 会话结构
```
SessionInfo { id, slug, projectID, directory, path, title, version, summary,
              permission, parentID, time{created,updated,initialized,completed}, revert{messageID,...} }
```

### 其他
- `ProviderInfo { id, name, models: Map<id, ProviderModelInfo> }`（**models 是 Map 非数组**）
- `ModelInfo { id, name, providerID, status }`；`ModelLimitInfo { context, input?, output }`
- `TodoItem { content, status, priority }`（无 id，index 兜底）
- `FileNode { name, path, absolute, type(file/directory), ignored }`

## 5. 容错处理约定

- 解析失败（损坏 JSON/未知字段）：`ignoreUnknownKeys` 容忍，告警不中断
- 超大文本：DTO 层 5000 字符截断 + `"\n\n… [truncated]"`
- 非 2xx：读错误体（≤2000 字符）抛 `IOException("HTTP xxx: body")` —— **错误体即用户可见文案**
- 列表可能为空/含未知字段：全部安全网 `takeLast / distinctBy { id } / merge 并集`