# UI / UX 设计

## 1. 导航体系

### 1.1 路由表

| 路由 | 页面 | 底栏 | 说明 |
|---|---|---|---|
| `serverList` | 服务器列表 | ✅ tab1 | 首页 |
| `recent` | 最近会话 | ✅ tab2 | |
| `settings` | 设置 | ✅ tab3 | |
| `addServer` | 添加服务器 | ❌ push | |
| `editServer/{serverId}` | 编辑服务器 | ❌ push | |
| `sessions` | 项目列表 | ❌ push | 连接成功后落地页 |
| `project/{directory}` | 项目内会话 | ❌ push | URL 编码目录 |
| `chat/{sessionId}?directory=` | 聊天 | ❌ push | |
| `help` | 帮助 | ❌ push | |

### 1.2 底栏三 tab

- **Servers**（Home）/ **Recent**（History）/ **Settings**（Settings），选中态 Filled / 未选中 Outlined
- Tab 切换：`popUpTo(startDestination) { saveState=true } + launchSingleTop + restoreState` → **每个 tab 状态独立保留**

### 1.3 栈管理规则

| 场景 | 跳转 |
|---|---|
| 卡片连接成功 | `navigate(SESSIONS)` 不清理栈 |
| 添加服务器成功 | `navigate(SESSIONS) { popUpTo(SERVER_LIST) }` 丢弃表单页 |
| 编辑保存 | `popBackStack()` |
| 断连 | `navigate(SERVER_LIST) { popUpTo(0) { inclusive=true } }` 清空整个栈 |
| 通知深链 | 清栈 → ServerList 自动连接 → LaunchedEffect 消费 pendingDeepLink → 跳 chat |

**动画**：常规路由 slide-in 300ms；Chat 路由 Up/Down 350ms。

## 2. 页面设计

### 2.1 服务器列表（首页 tab）
- TopAppBar（标题 + 添加按钮）
- `ServerCard`：状态图标（连接中 Dns 主色 / 未连接 Cloud 灰）+ 名称（空名回退 `host:port`）+ 状态徽章 + host:port + 编辑/删除 + ChevronRight
- 长按 → DropdownMenu（编辑/删除）；删除 AlertDialog 确认
- 空状态：Computer 图标 + 引导文案 + 使用提示卡
- 首连后请求 POST_NOTIFICATIONS 权限（TIRAMISU+）
- `animateItemPlacement(tween(300))` 列表增删动画

### 2.2 最近会话（tab）
- TopAppBar + "全部项目"按钮（ViewList）
- `RecentSessionRow`：History 图标 + 标题（单行省略）+ 副文本 `directory · 相对时间`
- 长按删除（AlertDialog）；空状态引导
- 数据源 RecentSessionStore（10 条，按当前服务器过滤）

### 2.3 项目列表（SESSIONS）
- TopAppBar：项目名 + 服务器名副标题 + 密度切换 + 刷新 + 断连
- `ProjectCard`：Folder 图标 + 目录名 + busy 绿色呼吸灯（800ms 脉冲）+ monospace 全路径 + 会话计数
- **stickyHeader 时间分组**：TODAY / YESTERDAY / THIS_WEEK / OLDER
- `LifecycleResumeEffect` 控制轮询开关

### 2.4 项目内会话（PROJECT_SESSIONS，复用同一 ViewModel）
- 搜索栏（300ms 防抖）
- **子会话树**：父会话展开箭头 → 子卡缩进 24dp + 细边框；`excludeChildrenOfExpandedParents` 防重复
- `SessionCard`：StatusDot（BUSY 绿呼吸灯 800ms / IDLE 灰）+ 标题 + 相对时间 + `+N/-M/Ff` 三色统计 + COMPLETED/ACTIVE 标签 + Fork/删除菜单
- ExtendedFAB 新建会话 → 成功自动跳聊天
- **备忘录侧滑面板**：左滑 40dp 阈值开 280dp 面板（350ms FastOutSlowIn）

### 2.5 聊天页（最复杂模块）

**消息渲染**：
- 用户气泡：primaryContainer + "我"标签
- AI 响应：agent 名标题 + 流式分段列表
- **流式分段三型**：
  - `thinking`：Psychology 图标 + tertiaryContainer 可折叠卡（流式时自动展开，结束保留并显示时长）
  - `tool`：Build 图标 + ToolSummarizer 摘要标签（secondaryContainer）
  - `text`：MarkdownText（自研 MarkdownRenderer）
  - `file`：AttachFile 卡片
- 流式光标块 530ms 闪动

**悬浮面板**：
- Todo 面板：BadgedBox 徽标 + 280dp 卡（三态图标 CheckCircle/PlayCircle/RadioButtonUnchecked）
- 文件面板：右滑 40dp 开 280dp，路径面包屑 + FileTreeView（目录进入/`..`返回/.md.txt 内联预览）

**交互组件**：
- 权限气泡：Allow Once / Always Allow / Reject + 可选拒绝原因
- 问题气泡：多步向导（进度条 + FilterChip + 自定义输入 + 返回/下一步/提交/忽略）
- 恢复气泡：会话中断检测后显示（Check Status / Dismiss）
- 命令选择器：`/` 触发，加载/错误重试/空态
- 选择对话框：Agent/Model/Variant 三下拉（draft/committed 分离）
- 输入栏：钢琴键条（滚底 ↓ / 模型名 / `/` / context 用量 "12K"）+ OutlinedTextField + 发送键

**顶栏**：Undo（有 user 消息时）/ Redo（revertMessageId）/ Stop（流式中，error 色）/ 刷新

**滚动系统**（设计要点）：
- 打开即底部；流式自动跟随；用户上滑解锁；滚回底部重新锁定
- 竞态修复：不用 `isLoading` 触发滚动，用 `snapshotFlow(totalItemsCount >= 数据数)` 等布局完成
- 滚顶触发分页（窗口 50→500 翻倍合并）

### 2.6 连接/编辑表单页
- Computer 图标 + 表单：服务器名 / 地址 / 端口（纯数字键盘）/ 用户名 / 密码（可见性切换）/ TLS Switch + 条件出现"允许不受信任证书"Switch
- 三种模式：ADD（保存并连接）/ EDIT（保存）/ 旧式直连
- 校验：空 host、端口 1-65535
- 底部固定 52dp 主按钮（connecting 转圈）
- EDIT 模式空白密码保留旧密码；编辑当前连接服务器 → 自动断开

### 2.7 设置页
| 卡片 | 交互 |
|---|---|
| 语言 | 中文/English 两行，选中 primaryContainer + Check |
| 外观 | 深色模式 Switch |
| 通知 | 描述 + Switch |
| 保持连接（keepAlive） | 描述 + Switch |
| 电池优化 | 只读状态行（已豁免/未豁免）→ 系统请求页；恢复前台重查 |
| 关于 | 更新检查（Checking/Available/UpToDate/Error 四态）+ 导出日志（logcat -t 5000 → FileProvider → 分享）+ 帮助入口 |
| 版本 + 使用提示卡 | 只读 |

## 3. 主题

- **Material 3 自定义**：手写 Light/Dark ColorScheme，primary #1A73E8
- **Android 12+ 动态取色**（dynamicLight/Dark），低版本回退自定义
- `AppLocale.darkMode` 驱动；DisposableEffect 同步 statusBar/navigationBar
- Typography 13 样式（bodyLarge 16sp / titleMedium 16sp Medium / titleSmall 14sp / labelSmall 11sp / headlineLarge 32sp Bold）
- Shapes：8/12/16dp
- 状态色：StatusGreen #4CAF50（busy 呼吸灯）

## 4. i18n（AppLocale）

- **非资源文件方案**：Kotlin 单文件 `Strings.kt`，`AppStrings` data class 238 个 String 字段
- `AppLocale.language: mutableStateOf("zh")` → `strings` getter 返回 zh/en 实例 → 切换自动重组
- 格式化用 `%s`/`%d` + `.replace()`（JVM 255 参数上限已接近（238），新增字段谨慎，移植时建议换资源文件方案）
- 持久化：DataStore `app_language` / `app_dark_mode`

## 5. 公共组件

- **ErrorSnackbar**（唯一跨页组件）：errorContainer + ErrorOutline 图标 + 关闭 action + **5s 自动消失**；被 Sessions（L1/L2）+ Chat 复用
- 设计准则：**任何错误必须可见**（服务端错误体原样透传，见 architecture §5 决定性错误）

## 6. 交互设计准则（移植时保留）

1. 列表增删有动画（300ms）；状态变化有呼吸灯（800ms）
2. 侧滑面板统一 40dp 阈值 / 280dp 宽度 / 350ms FastOutSlowIn
3. 流式 delta 16ms 帧对齐批量渲染（60fps）
4. 所有长时间操作有 loading 态（转圈/按钮内联）
5. 破坏性操作（删除）必须确认对话框
6. 键盘/面板弹出不影响自动滚动状态
7. 空状态必须可行动（引导按钮而非裸提示）
