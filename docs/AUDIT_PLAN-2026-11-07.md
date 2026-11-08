# 播放器仓库（fork）全覆盖审计计划 · 2026-11-07

> 对象：`luanmuc/react-native-track-player`（本地 `rntp/`），分支 `v2`，基线 commit **`eceacb5`**
> 目的：对「我们自己的定制」做一次分维度、可判定、可复现的全覆盖审计，不是对上游代码做泛读
> 方法继承自 App（89 维）与音源（96 维）两轮：**不采信任何历史表述**，逐条回代码定位；能跑的就构造真实运行场景，跑不了的标 `blocked` 并写清原因

---

## 一、范围与基线

| 项 | 数值 | 说明 |
|---|---|---|
| TypeScript 源码 | 4 文件 / 730 行（`src/interfaces.ts` 264 行、`src/trackPlayer.ts` 321 行、`src/hooks.ts` 139 行、`src/index.ts` 6 行） | App 直接 import 的契约层 |
| Android Java | 17 文件 / 3,644 行 | 全部定制落点 |
| iOS Swift | 9 文件 / 1,125 行 | App 不发布 iOS，仅做静态影响评估 |
| 图标资源 | 20 张（4 状态 × 5 密度） | 模式按钮专用 |
| 构建产物 | `lib/`（不入库，`prepare` 时生成） | 影响 App 侧 tsc 结果 |
| 文档 | `FORK_NOTES.md`、`README.md`、`CHANGELOG.md`、`docs/`（上游原文） | 定制事实的唯一文字来源 |

**定制事实来源**：`FORK_NOTES.md`（音频焦点 3 项 / 通知栏参数 2 项 / 歌词按钮 3 项 / 标题艺术家行 2 项 / 模式按钮 4 项 / PendingIntent 1 项）。本计划把这 15 条定制全部列为**待验项**，不允许"文档说改了"就当已改。

## 二、证据分级（与两轮前例一致）

| 级别 | 含义 | 本仓库可行性 |
|---|---|---|
| L0 | 静态：读代码 + 交叉比对 + 资源清单核对 | ✅ 全覆盖 |
| L1 | 编译：`tsc` 产出 `lib/`、Java 语法/依赖可解析 | ✅ `tsc -p` 已实测可跑（App 的 typescript） |
| L2 | 行为：纯函数/状态机级 mock 或最小 Java 桩验证 | ⚠️ 部分可行（拼接、映射、去重可抽函数验；Android 运行时不可起） |
| L3 | 集成：装进 App 后构建 APK 成功、包内产物核对 | ✅ 可做（打 APK + 解包验证，本仓库已跑通一次） |
| L4 | 真机：通知栏实际渲染、点击回调、焦点抢占、图标观感 | ⛔ 无真机 → `blocked` |

`blocked` 不等于"没问题"，只表示本环境无法取证；报告中必须与"通过"分开列。

## 三、维度矩阵（9 组 / 72 维）

优先级：**P0**=定制核心功能，坏了用户直接可见；**P1**=边界与一致性，易静默失效；**P2**=可维护性/文档。

### A. 类型与接口契约（10 维）

| 编号 | 检查点 | 判据 | 级别 |
|---|---|---|---|
| A1 | `interfaces.ts` 扩展字段 `quality` / `subSource` 是否可选、是否与 Java 侧读取键一致 | 字段名逐字匹配 `TrackMetadata`/`bundle` | P1 / L0+L1 |
| A2 | `NowPlayingMetadata` 是否真实透出到 JS（App 端消费 `locationUpdateTime` 等） | 桥接 map 逐字段核对，无静默丢弃 | P1 / L0 |
| A3 | `MusicModule` 的 `@ReactMethod` 与 `src/trackPlayer.ts` 导出是否一一对应 | 双向差集必须为空（无孤儿方法、无缺失实现） | P0 / L0 |
| A4 | 事件名常量：`BUTTON_TOGGLE_DESKTOP_LYRIC` / `BUTTON_CYCLE_REPEAT` 与 TS 侧 `RemoteCycleRepeat` 等声明一致 | 字符串逐字比对，含大小写 | P0 / L0 |
| A5 | 可选字段引入后是否产生 `undefined` 泄漏（Java 侧 `getString` 返回 null → JS 侧 undefined） | 消费方是否 `??` 兜底 | P1 / L0 |
| A6 | `types` 产物（`lib/interfaces.d.ts`）是否随 `src` 同步，`prepare` 链是否可靠 | 编译后 grep 新字段必须命中 | P0 / L1 |
| A7 | `package.json` `files` 白名单是否覆盖 `lib/**`、`android/**`、`ios/**`、`*.podspec` | 打包后 `npm pack --dry-run` 清单 | P2 / L1 |
| A8 | `version` 恒为 `2.1.2`（靠 sha 区分）是否会在 App 侧造成缓存/升级歧义 | lock 中 resolved 是否带 sha | P2 / L0 |
| A9 | `strict` 是否开启、`skipLibCheck` 是否会掩盖真实类型错误 | `tsconfig.json` 逐项 | P1 / L1 |
| A10 | TS 侧是否残留 `as any` / `@ts-ignore` 掩盖桥接契约差异 | 全量 grep | P1 / L0 |

### B. 通知栏与媒体元数据（12 维，定制主战场）

| 编号 | 检查点 | 判据 | 级别 |
|---|---|---|---|
| B1 | `toMediaMetadata()` 标题只放歌名、不挂音质后缀 | 传 `quality=FLAC` 时标题不得含 `[FLAC]` | P0 / L0+L2 |
| B2 | 艺术家行 ` · subSource · quality` 拼接顺序与缺项跳过 | 四组合（都缺/缺一/都有）逐项验 | P0 / L0+L2 |
| B3 | 拼接前判空：不得出现 `"null [FLAC]"` / `"null · xxx"` | 空串、null、true 三种入参 | P0 / L0+L2 |
| B4 | 「词」按钮图标两态（`lyric` alpha=255 / `lyric_inactive` alpha=102）与 `setLyricButtonActive` 状态同步 | 状态机互斥，切歌不残留 | P1 / L0 |
| B5 | 模式按钮 4 态图标映射：`0→order`、`1→loop`、`2→single`、`3→random`，非法值兜底 | 逐分支核对 + 越界值 | P0 / L0+L2 |
| B6 | `setRepeatMode(int)` 去重（`repeatMode == mode` return）是否覆盖全部入口 | 检查 MusicBinder / MusicModule / onStartCommand 三条路径 | P1 / L0 |
| B7 | 去重后通知是否仍会刷新（去重 vs 切歌 meta 更新的交互） | 连点同模式不刷、换模式必刷 | P1 / L0 |
| B8 | `addAction` 顺序：自定义两钮是否挤占系统钮、compact view 是否溢出 | 与 `compact` 位图宽度/序号核对 | P1 / L0 |
| B9 | MediaStyle `setShowCancelButton(false)` 逻辑在 playing/paused 两态是否正确 | 两态分支 | P2 / L0 |
| B10 | 通知刷新时机：`updateOptions` / 队列变更 / metadata 变更是否都触发重绘 | 三条触发路径对 `refreshNotification` 的调用 | P0 / L0 |
| B11 | Android Go / 旧华为分支（`MANUFACTURER contains huawei && SDK < M`）是否仍走老路径且不崩 | 分支逻辑 | P2 / L0 |
| B12 | 大图/小图（`largeIcon`）与歌词按钮共存的资源占用 | 是否有 Bitmap 未回收 | P2 / L0 |

### C. PendingIntent / 安全 / 权限（6 维）

| 编号 | 检查点 | 判据 | 级别 |
|---|---|---|---|
| C1 | 两处 `getService` 是否带 `FLAG_IMMUTABLE`（含 `SDK_INT >= 23` 判断） | 面 SDK 31+ 时必须非 mutable | P0 / L0 |
| C2 | `requestCode` 200/201 全局唯一，不与系统钮或 App 侧冲突 | 全仓 grep `getService(` / `getActivity(` | P0 / L0 |
| C3 | `MusicService` 是否被导出（`exported`）及是否有权限保护 | Manifest 核对 | P1 / L0 |
| C4 | `ACTION_*` 自定义 action 字符串是否可被外部伪造触发按钮行为 | onStartCommand 分支是否校验调用方 | P1 / L0 |
| C5 | 日志是否输出 URL / 账号 / token / 歌词全文 | 全仓 grep `Log.` | P2 / L0 |
| C6 | `FLAG_UPDATE_CURRENT` 与 IMMUTABLE 组合在 API 23 以下的降级行为 | SDK<23 分支 | P2 / L0 |

### D. 音频焦点（8 维，历史崩溃区）

| 编号 | 检查点 | 判据 | 级别 |
|---|---|---|---|
| D1 | `requestAudioFocus` 幂等：重复请求不累积焦点栈 | 连续 3 次 play 只发 1 次请求（L2 桩验证） | P0 / L0+L2 |
| D2 | `AUDIOFOCUS_LOSS` 走 `pause()` 而非 `stop()`，进度不归零 | 分支核对 | P0 / L0 |
| D3 | 鸭子音量：`LOSS_GAIN` / `DUCK` / `LOSS_TRANSIENT` 三路径音量还原 | 三路径逐项，含异常退出 | P0 / L0+L2 |
| D4 | media3 内置 `AudioFocusManager` 是否确实关闭，无双重持有 | 初始化代码 | P0 / L0 |
| D5 | 焦点丢失后 `isPlaying` / 通知栏按钮状态是否同步 | 与 PlaybackState 一致性 | P1 / L0 |
| D6 | 电话/闹钟等瞬时中断恢复后是否自动续播（与 App「自动续播」开关配合） | 开关两态 | P1 / L0 |
| D7 | 焦点请求失败（`AUDIOFOCUS_REQUEST_FAILED`）处理 | 是否静默继续播 | P1 / L0 |
| D8 | 服务销毁时是否 `abandonAudioFocus` | onDestroy 路径 | P1 / L0 |

### E. 播放态与事件同步（10 维）

| 编号 | 检查点 | 判据 | 级别 |
|---|---|---|---|
| E1 | `PlaybackState` → 通知栏 → JS 事件三方一致性（`getState` 轮询 vs 推送） | 三处取值来源比对 | P0 / L0 |
| E2 | `repeatMode` 状态来源单一性：原生字段 / JS `store` / 通知图标 | 是否可能三方不同步 | P0 / L1+L3 |
| E3 | 模式按钮点击 → JS `remote-cycle-repeat` → App 更新 → 回调 `setNotificationRepeatMode` 的异步竞态（连点） | 乱序到达时图标是否回跳 | P1 / L0 |
| E4 | 队列变更（`add`/`remove`/`move`）时索引与 `playIndex` 越界 | 空队列 / 仅 1 首 / 删当前 | P0 / L0 |
| E5 | 切歌时旧 `PendingIntent` 是否复用错误（`FLAG_UPDATE_CURRENT` 覆盖） | requestCode 相同是否导致 extra 串味 | P1 / L0 |
| E6 | `updateMetadataForTrack` 对不存在索引的处理 | 越界不崩 | P1 / L0 |
| E7 | 事件派发是否在主线程，JS 批量事件是否丢帧/合并 | MusicEvents 实现 | P1 / L0 |
| E8 | `null` 播放器（初始化前）收到指令时的防御（b68 关闭开关打不开的根因类） | `getPlayback()` 判空覆盖点清点 | P0 / L0 |
| E9 | 缓冲态 / 错误态下按钮是否被错误置灰或可点 | 状态机全态遍历 | P1 / L0 |
| E10 | 耳机拔出 / 媒体键（headset hook）与自定义按钮共存 | 按键分发顺序 | P1 / L0 |

### F. 生命周期与服务（8 维）

| 编号 | 检查点 | 判据 | 级别 |
|---|---|---|---|
| F1 | `startForeground` 时机与 `FOREGROUND_SERVICE` 权限（Android 12+ 类型声明） | Manifest + 调用点 | P0 / L0 |
| F2 | 服务被系统回收后重建，`repeatMode` / 队列能否恢复 | 静态推定 + App 侧消费 | P1 / L0 |
| F3 | `HeadlessJsTaskService` 注册、超时与 task key 一致 | 与 App 侧注册名比对 | P1 / L0 |
| F4 | `MusicBinder` 绑定/解绑的生命周期泄漏 | 是否持有 Activity/Context | P2 / L0 |
| F5 | 播放中杀进程：通知是否残留（僵尸通知） | 静态推定，真机验 → blocked | P1 / L4 |
| F6 | `onTaskRemoved` / `onDestroy` 清理完整（计时器、焦点、通知） | 逐项清点 | P1 / L0 |
| F7 | 多实例防护（重复 `startService`） | 是否幂等 | P2 / L0 |
| F8 | 低内存 / Doze 下前台服务保活策略（与 App「音频卸载/后台播放」设置交互） | 设置项对照 | P2 / L0 |

### G. 资源与构建发布（8 维）

| 编号 | 检查点 | 判据 | 级别 |
|---|---|---|---|
| G1 | 20 张模式图标：4 状态 × 5 密度齐全，无缺漏 | 文件名矩阵比对 | P0 / L0 |
| G2 | 图标 alpha 统一 102（与「词」关闭态一致），观感一致 | 像素采样统计 | P1 / L0 |
| G3 | 图标命名与 `R.drawable.*` 引用一一对应（无引用不存在资源） | 编译期即暴露 → L1 | P0 / L1 |
| G4 | 图标体积（20 张总大小）对 APK 的影响 | du 统计 | P2 / L0 |
| G5 | `lib/` 不入库 + `prepare: build` 的安装链（离线可复现性） | 空仓库安装模拟 → blocked（沙箱 registry 缺 `flow-estree@0.339.0`，不是网络不可达） | P1 / L1+L3 |
| G6 | `tsconfig` 输出目录与 `package.json` `main`/`types` 路径一致 | 字段比对 | P1 / L1 |
| G7 | lint/format 是否会因定制代码报错（`yarn lint`） | 可跑则跑 | P2 / L1 |
| G8 | APK 内是否确实带上自定义 drawable（未被 shrinker 剔除） | 打 APK + 解包核对（已跑通方法） | P0 / L3 |

### H. 文档镜像与上游可维护性（10 维）

| 编号 | 检查点 | 判据 | 级别 |
|---|---|---|---|
| H1 | `FORK_NOTES.md` 记录的「App 锁定 commit」是否等于现值 | 当前 `eceacb5`，文档写的是旧 sha → **已发现不一致** | P1 / L0 |
| H2 | FORK_NOTES 15 条定制是否逐条可在代码中定位（无虚报、无漏记） | 逐条 grep 锚点 | P0 / L0 |
| H3 | FORK_NOTES 文件索引表与实际改动文件是否一致 | 差集 | P2 / L0 |
| H4 | `CHANGELOG.md` 是否记录自定义项（还是纯上游） | 抽查 | P2 / L0 |
| H5 | `README.md` 是否说明这是 fork 及定制范围 | 首屏 | P2 / L0 |
| H6 | 相对上游 diff 面：改动文件数 / 行数占比 | 需上游基线（`git` 内可能无 upstream remote） | P2 / L0 |
| H7 | 补丁可重放性：是否侵入过深导致上游升级必冲突 | 集中在 `MetadataManager`/`MusicService`/`TrackMetadata` | P2 / L0 |
| H8 | App 侧 `UPSTREAM_LIFECYCLE` / `KNOWN_ISSUES` K5 与 rntp 事实是否一致 | 三处文档交叉 | P1 / L0 |
| H9 | 定制是否有归口注释（`lx-music fork:`）便于后续 diff | 全仓 grep 覆盖率 | P2 / L0 |
| H10 | 升级流程文档是否写明「改 sha 后必须重装 + 验 tsc」 | 与 App `DEV_SETUP` 新增段对齐 | P1 / L0 |

### I. 测试与可验证性（4 维）

| 编号 | 检查点 | 判据 | 级别 |
|---|---|---|---|
| I1 | 仓库是否有任何测试目录（`__tests__`/`test`） | 已确认**无** | P2 / L0 |
| I2 | 可最小单测覆盖的纯函数：艺术家拼接、repeatMode→图标映射、去重 | 抽出后可 L2 | P1 / L2 |
| I3 | Java 层可 mock 程度（`MetadataManager` 依赖 `MusicManager` 实例） | 评估是否需重构才可测 | P2 / L0 |
| I4 | 真机可验清单（通知栏渲染/点击/焦点/图标观感/僵尸通知） | 明确 blocked 范围 | — / L4 |

## 四、计划阶段已先做的 4 项抽查（避免计划悬空）

| # | 抽查 | 结果 |
|---|---|---|
| 1 | 两处 `PendingIntent.getService` | ✅ 均带 `FLAG_UPDATE_CURRENT \| (SDK_INT>=23 ? FLAG_IMMUTABLE : 0)`，与 FORK_NOTES 一致 |
| 2 | `setRepeatMode` 去重 | ✅ 首行 `if (this.repeatMode == mode) return;` 存在，且注释说明初始化前 `getPlayback()` 判空 |
| 3 | 模式图标资源 | ✅ 20 张 = 4 状态 × 5 密度齐全（order/loop/single/random） |
| 4 | `FORK_NOTES.md` 锁定 sha | ❌ **过期**：文档写 App 锁定 `87bf38ba`，实际已是 `eceacb5`（H1 直接命中） |

## 五、不做与 blocked（先声明，避免报告虚报覆盖）

| 项 | 原因 |
|---|---|
| iOS / macOS / Windows 真机与编译 | App 只发 Android；Swift 仅做静态影响评估（`eceacb5` 是纯 TS 类型改动，不影响） |
| 真机通知栏渲染、点击回调、焦点抢占、图标观感 | 无设备 → L4 blocked |
| `npm install` 从 GitHub 安装该 sha | 沙箱 registry 缺 `flow-estree@0.339.0`，已改用「App 侧编译 rntp src 产出 lib」路线 |
| 上游全量 diff（相对官方 `react-native-track-player`） | 仓库内无 upstream remote 与完整 tag 基线，只能看定制集中度（H6 降级为定性） |

## 六、执行顺序（按依赖，不按编号）

1. **H2** 定制 15 条逐条定位（决定后续所有维度是否"审的是定制"）
2. **A3/A4/A6** 契约层：`@ReactMethod` ↔ TS 导出差集 + 事件名 + `lib` 产物
3. **B1–B6** 拼接与按钮映射（纯逻辑，可 L0+L2 立刻出结论）
4. **E8/E2/E4** 判空与状态一致性（历史上 b68 崩溃的同族问题）
5. **D1–D4** 音频焦点
6. **C1–C4** PendingIntent 与 action 伪造面
7. **F/G** 生命周期与资源（G8 打 APK 核验）
8. **H/I** 文档镜像与可测性收尾

## 七、产出物

- 本计划：`docs/AUDIT_PLAN-2026-11-07.md`
- 执行后：`docs/AUDIT_REPORT-2026-11-07.md`（分级发现 + 每条的可复现命令/锚点 + blocked 清单）
- 过程：`docs/AUDIT_PROGRESS-2026-11-07.md`（逐维结论、放弃与反证记录）
- 修复若经确认：同步 `FORK_NOTES.md`（含过期 sha）与 App 侧 `KNOWN_ISSUES` / `UPSTREAM_LIFECYCLE`

### J. 语义契约与跨仓签名一致性（2026-11-08 新增）

> 来源：第二轮全覆盖审查。原生仓库没有 UI 文案，本组聚焦：
> **JS 暴露面 ↔ 原生 @ReactMethod 是否一一对应**，以及 **App 锁定的 sha 是否真的含所需方法**。

### 触发案例（2026-11-08）

听歌统计：侧边栏文案是「今日 {today} 次 · 时长 {time}」，实际传的是累计 `playTime`。
类型全对（都是 number）、40 条文档规则全绿、`check_ts_syntax` 0 错误——**功能是错的**。

### 根因：判据太宽，执行时被简化

原计划里的 i18n / 状态机 / 持久化 组，执行时被落成"键数对不对""字段有没有"，没有落到
"文案说的意思 **vs** 数据实际的意思"。这类缺陷在单文件纵向读时处处自洽，只有横向穿才看得见。

### 三条硬判据（改动 UI 展示 / 统计 / 配置时必须过）

1. **文案 ↔ 字段横向穿**：每个带参 `t()` 填五行表
   `文案键 → 占位符 → 传入表达式 → 字段定义 → 写入点`，任一处语义不符即缺陷。
   易错语义词：今日/累计/剩余/总数/上次/平均/速度/已下载/本次/总时长。
2. **类型正确不算通过**：`playTime` 与 `dayTime` 都是 number，编译器不会警告；
   必须人工确认"这个数字语义上是不是文案要的那一个"。
3. **"改过"不等于"审过"**：注释里的"已修正"只证明被碰过一次，不证明展示层被审查过。

### 三类假阳性（写报告前必须过滤，否则制造假缺陷）

| 假阳性 | 成因 | 过滤方法 |
|---|---|---|
| 文案占位符"缺参" | 脚本不认 ES6 属性简写 `{ name }` | 回读源码确认调用形态 |
| "设置项没人读" | 正则漏 `useSettingValue('...')` / 动态 key | 用短名全仓 grep 复核 |
| "导出零引用=没接线" | 可能是有意保留的上游死代码 | `git log -S '<符号>'` 回查引入提交再定性 |

### 一条跨仓新判据

**依赖版本"落后"不等于"有问题"**：App 锁定 fork sha 落后 9 个提交，需先看这 9 个提交的性质
（本例全为 docs/hooks，零代码变更 → 无风险），不能只比较 sha 差异就判缺陷。

### 本轮实跑结果（作为基线）

- JS 导出 vs Java `@ReactMethod` 机械比对：**一一对应**
  （JS 侧多 `registerPlaybackService`/`addEventListener`/类型导出，属 JS 层实现，非原生缺失）
- App 调用 `TrackPlayer.setNotificationRepeatMode`（走 `unknown as` 断言，绕过类型）：
  - rntp `MusicModule.java:335` 存在 `setNotificationRepeatMode(int, Promise)` ✅
  - App 锁定 sha `a0b2a87` **确实包含**该方法（`git grep` 命中 Java 2 处 / TS 3 处）
- App 锁定 `a0b2a87` 落后 fork HEAD 9 个提交 → 逐个查看：**全部为 docs/hooks，零代码变更** → 无功能风险

### 复核命令

```bash
git merge-base --is-ancestor ca72e89 a0b2a87 && echo "含播放模式按钮"
git grep -c "setNotificationRepeatMode" a0b2a87 -- android/ src/
git log --oneline a0b2a87..HEAD          # 落后提交必须逐个看性质
grep -rh -A1 "@ReactMethod" android/src/main/java --include=*.java | grep -oE "public (void|double|boolean|String) \w+"
```
