# 播放器仓库审计执行记录 · 2026-11-07

> 对应计划：`docs/AUDIT_PLAN-2026-11-07.md`；报告：`docs/AUDIT_REPORT-2026-11-07.md`
> 基线：`eceacb5`（审计前 `git fetch origin` 确认 0 个落后提交，未做任何仓库更新）

## 一、执行轨迹

| 步 | 动作 | 命令/手段 | 结果 |
|---|---|---|---|
| 0 | 远端同步核查 | `git fetch origin` + `rev-list --count HEAD..origin/v2` | 0 落后，无需更新 |
| 1 | 定制 15 条逐条定位（H2） | 6 组 `grep`，锚点写回报告 | 15/15 可定位，无虚报 |
| 2 | 契约双向差集（A3/A4） | python 正则提取 `@ReactMethod` 方法名与 `TrackPlayer.x(` 调用 | 36 ↔ 36 零差集；事件 20/20 包含 |
| 3 | 拼接与按钮映射（B1–B7） | 读 `TrackMetadata.toMediaMetadata` / `MetadataManager` 250–300 | 标题/艺术家/缺项/映射/去重全部正确 |
| 4 | 安全面（C1–C4） | `AndroidManifest.xml` + `onStartCommand` + `PendingIntent` grep | 发现 R1（contentIntent 漏 IMMUTABLE）、R2（导出服务 + action 无校验） |
| 5 | 音频焦点（D1–D8） | `MusicManager` 48–182 全读 + 开关读取链 | 幂等/abandon/鸭子音量均正确；发现 R7（守卫条件与意图相反）、R6（耳机路径不复位） |
| 6 | 状态与判空（E2/E4/E8） | `ExoPlayback.getState` 映射 + `waitForConnection` + `getPlayback()` grep | 状态映射正确（3=STATE_PLAYING）；定制 setter 有判空 |
| 7 | 生命周期（F1/F3/F6） | `onCreate/onDestroy/onTaskRemoved` + 继承关系 | 清理完整；R10 为升级前瞻项 |
| 8 | 资源（G1/G2/G8） | PIL 逐像素 alpha + b77 APK 解包 + `resources.arsc` 字符串表 | 20 张齐 / alpha 102 / 资源确实进包 |
| 9 | 文档镜像（H1/H3/H4/H5/H9） | grep + 文件矩阵比对 | 4 处文档问题（R3/R4/R5） |
| 10 | 可测性（I1–I3） | 目录清点 | 仓库零测试，纯逻辑可抽 |

## 二、覆盖率

76 维：**支持 56 / 部分 17 / blocked 3**。blocked = F5（僵尸通知）、G5（空仓库装 sha）、I4（真机四项）。

## 三、放弃与反证（防止假阳性）

| 候选 | 反证 | 处理 |
|---|---|---|
| 「通知按钮走 `PendingIntent.getService`，Android 8+ 后台启动服务受限 → 按钮点了没反应」 | 用户日常使用通知栏按钮长期正常；`PendingIntent` 由系统代表 App 发起，不在后台服务限制的典型拦截面 | **剔除**，不写进报告 |
| 「APK 里没有 `notify_mode_*` 资源 → 定制图标没进包」 | 文件名 0 命中是假阴性：release 构建把资源改成短名（`res/-0.png`），资源名只在 `resources.arsc` 字符串表里 | **剔除**，改写为 G8 通过 + 方法论注意 |
| 「`getState() == 3` 注释写着 STATE_PLAYING，但 ExoPlayer 的 `STATE_PLAYING` 是常量 3？」 | `ExoPlayback.getState()` 返回的是 `PlaybackStateCompat.STATE_*`，`STATE_PLAYING` 正是 3 | 注释正确，仅 R11 语义细节保留 |
| 「App 传了 `autoResumeAfterInterrupt` 但 Java 不读 → 开关无效」 | `MusicManager.java:216-217` 明确读取三个键；App 侧 `plugins/player/index.ts:37-40` 也真实传参 | **剔除** |
| 「TS 声明了 `remote-like` 等事件 Android 不发 → 功能缺失」 | 已核 App 没有监听这三个播放器事件（App 里的 dislike 属同步模块数据） | 降为 R9 信息项 |
| 「`MusicService` action 分支不调 `onStartForeground` 会触发 `did not then call startForeground`」 | 走的是 `startService`（非 `startForegroundService`），且 `onCreate` 已 `startForeground`，契约成立 | **剔除** |

## 四、未做（保留为下一轮输入）

- R2 的导出收窄、R6 的耳机复位：都需真机验证媒体键与拔耳机行为，本轮只记录方案。
- `npm pack --dry-run`（A7）、全量日志 grep（C5）：本轮未跑，报告中如实标 partial。
- 相对官方上游的全量 diff：仓库无 upstream remote 与完整 tag 基线，H6 降为定性。

## 六、修复轮（同日追加）

依据 `docs/FIX_PLAN-2026-11-07.md` 落地 7 项修复，证据见该文件「验收结果」表。

| 步 | 动作 | 结果 |
|---|---|---|
| FX1/R7 | 新增 `userPausedDuringInterrupt` 三态机 + `markUserPaused()`/`pauseByUser()` + 三条入口接线 | 静态通过；全仓无 `getPlayback().pause()` 直调 |
| FX2/R1 | `contentIntent` 补 `FLAG_IMMUTABLE` | 三处写法一致 |
| FX3/R2 | Manifest 加 `signature` 权限 | 真机待验 |
| FX4/R3 | 三文件补归口注释 | 5 → 8 文件 |
| FX5/R6 | 拔耳机复位鸭子音量 | 通过 |
| FX6/R4 | FORK_NOTES 口径更新 | 通过 |
| FX7/R5 | README / CHANGELOG 补 fork 标识 | 通过 |
| V-结构 | 6 文件括号配平 | 通过 |
| V-TS | `tsc -p /tmp/rntpbuild` | `exit=0`，产物含 `quality?:` |
| V-Java | 无 `javac` / `ANDROID_HOME` | **blocked**，待 push 触发 CI `build-android` |

## 七、上游核查（同日）

- 本 fork：`git fetch origin --prune` → `origin/v2` 仍 `eceacb5`，**落后 0 / 领先 0**，无需更新。
- 官方上游：`git ls-remote` 显示 **`v2` 分支已不存在**，只有 `main`（`d4294f2`）；最新 tag 为 `v5`（此前 `v4.1.2`、`v4.1.1`）。
- 判断：官方已把 Android 侧重写为 Kotlin（v5），与本 fork 的 Java 定制层（15 条）不兼容，**不做上游跟进**；该结论已写入 `FORK_NOTES.md` 与 `README.md`。

## 五、入库状态

- 代码修复：独立提交（`fix(player): 审计修复 7 项…`），涉及 10 个文件
- 四份文档（本文件、`AUDIT_PLAN`、`AUDIT_REPORT`、`FIX_PLAN`）：独立提交
- 两者一并推送到 `origin/v2`。**fork 仓库的 Actions 需 owner 手动访问 `/actions/enable` 才会跑**：实测推送后 `workflow_runs.total_count` 恒为 0（API 的 `actions/permissions.enabled=true` 不代表已授权），因此 Java 编译验证改由 App 仓库的 release CI 承担，见下一节。
