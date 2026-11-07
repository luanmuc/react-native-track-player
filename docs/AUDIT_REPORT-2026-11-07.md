# 播放器仓库（fork）全覆盖审计报告 · 2026-11-07

> 对象：`luanmuc/react-native-track-player`（本地 `rntp/`），分支 `v2`，基线 commit **`eceacb5`**（与远端一致，审计前已确认 0 个落后提交）
> 计划：`docs/AUDIT_PLAN-2026-11-07.md`（9 组 76 维：A 契约 / B 通知栏 / C 安全 / D 焦点 / E 状态 / F 生命周期 / G 资源 / H 文档 / I 可测性）
> 方法：不采信任何文档表述，逐条回代码；能跑构造真实场景，跑不了标 `blocked`

## 零、结论摘要

- **覆盖率**：76 维中 **支持 56 / 部分 17 / blocked 3**。blocked 全部集中在真机可感项（通知栏渲染、点击回调、焦点抢占、僵尸通知），与本环境无设备有关，不代表"没问题"。
- **实质发现 10 处**：中 4（R1/R2/R3/R7）、低 4（R4/R5/R6/R8）、信息 2（R9/R10），另 2 条为设计取舍说明（R11/R12）。
- **最有价值的一处**：`AUDIOFOCUS_GAIN` 的"自动续播"守卫条件与实际意图**相反**（R7），用户手动暂停会被 500ms 后的自动播放覆盖。
- **最容易被忽略的一处**：同文件三处 `PendingIntent`，另两处都加了 `FLAG_IMMUTABLE`，唯独点通知打开 App 的那处没加（R1）——升级 targetSdk 31 时会崩。

## 〇·补 修复状态（2026-11-07 晚，按 `docs/FIX_PLAN-2026-11-07.md` 执行）

| 编号 | 状态 | 落点 |
|---|---|---|
| R7 | **已修** | `userPausedDuringInterrupt` + `MusicManager.markUserPaused()`/`pauseByUser()`，`MusicBinder` 转发，`MusicModule.pause` 改走 `pauseByUser`，`ButtonEvents.onPause` 提前标记 |
| R1 | **已修** | `MetadataManager:106` 补 `FLAG_IMMUTABLE`，与 200/201 两处同构 |
| R2 | **已修** | Manifest 增 `signature` 级 `PLAYER_CONTROL` 权限 + `uses-permission`，`MusicService` 挂 `android:permission`；真机媒体键待验 |
| R3 | **已修** | `TrackMetadata`/`MusicModule`/`MusicBinder` 补注释，`grep` 覆盖 5 → **8** |
| R4 | **已修** | `FORK_NOTES` 锁定 sha 改 `eceacb5` + 补 b75 升级性质 + 上游已无 v2 的说明 |
| R5 | **已修** | README 顶部加 fork 警示块；`CHANGELOG` 顶部加本 fork 变更记录并保留上游原文 |
| R6 | **已修** | `noisyReceiver` 先复位鸭子音量再暂停，并按用户主动暂停记录 |
| R8/R9/R10/R11/R12 | 不改 | 理由见 `FIX_PLAN` 的「不做」 |

**Java 编译（审计当时未跑，已补验）**：审计时本机无 `javac`/`ANDROID_HOME`，仓库原生入口 `platform-validation.yml` 在 fork 仓库下不自动运行（需 owner `/actions/enable`，实测 `workflow_runs` 恒 0）。已改由 **App 仓库 release CI 补验**：其 gradle 直接编译 `node_modules/react-native-track-player/android` 的 Java 源码，`v1.9.2-b78` 的 Android / Release 两个 job 全绿；包内实证 Manifest 含新权限 `PLAYER_CONTROL`（b77 命中 0 / b78 命中 1）、`resources.arsc` 含 4 个 `notify_mode_*` 与 `lyric_inactive`/`default_artwork`、Hermes bundle 含两个定制事件常量。TS 侧本地重跑 `tsc`（`exit=0`）。

### R13（中·审计后补）`MusicBinder.getPlayback()` 会懒创建一个「默认配置」的播放器实例

锚点：`MusicBinder.java:35-45`

```java
public ExoPlayback getPlayback() {
    ExoPlayback playback = manager.getPlayback();
    if (playback == null) {
        playback = manager.createLocalPlayback(new Bundle());   // 空 Bundle = 全部默认值
        manager.switchPlayback(playback);
    }
    return playback;
}
```

这解释了为什么 `MusicModule` 里十几处 `binder.getPlayback().xxx()` 不判空也从不崩（E8 那一条的真实原因不是"调用约定"，而是**这个 getter 永不返回 null**）。

但它同时意味着：任何在 `setupPlayer` 之前到达的 `add`/`remove`/`skip`/`play` 调用，都会先造出一个用**默认选项**初始化的播放器——`handleAudioFocus`、`autoResumeAfterInterrupt`、`duckOnTransientDuck`、buffer、cache 全部回落默认值，并向系统请求一次音频焦点，随后 `setupPlayer` 再 `switchPlayback` 覆盖它。

当前 App 的调用顺序是先 `setupPlayer` 再加载队列，所以是条件性风险而非现存故障。本轮已把 `pause` 从这条路径上摘掉（改走 `manager.pauseByUser()`），其余入口保持上游原样，未扩大改动面。

## 一、分级发现

### R7（中）「打断后自动续播」的守卫条件写反，会覆盖用户的主动暂停

锚点：`MusicManager.java:78-88`（`AUDIOFOCUS_GAIN` 分支）

```java
if (wasPlayingBeforeTransientLoss) {
    wasPlayingBeforeTransientLoss = false;
    if (autoResumeAfterInterrupt) {
        new Handler().postDelayed(() -> {
            // 注释写：只在「仍然暂停」时才恢复，用户这 500ms 内手动点过播放/暂停就不插手
            if (playback != null && hasAudioFocus && playback.getState() != 3) playback.play();
        }, 500);
    }
}
```

`getState() != 3` 的本意是"仍处于暂停（2）才恢复、已在播放（3）就不插手"。但用户在这 500ms 内**手动暂停**后，状态正是 `STATE_PAUSED(2)`——于是条件成立，`play()` 被调用，用户刚按的暂停被撤销。

触发链（真实场景）：来电/其他 App 抢占 → 音乐暂停 → 用户嫌烦点了通知栏暂停 → 通话结束 → 500ms 后音乐自己响起来。

**反证检查**：`wasPlayingBeforeTransientLoss` 只在 `LOSS_TRANSIENT` 分支置位、`GAIN`/`LOSS` 复位；用户手动暂停不会复位它，所以该标志确实还活着。判据 `!= 3` 同时放行了"缓冲中(6)"与"暂停(2)"两种状态，语义不精确。

### R1（中）通知点击的 PendingIntent 漏了 `FLAG_IMMUTABLE`

锚点：`MetadataManager.java:106`

```java
builder.setContentIntent(PendingIntent.getActivity(context, 0, openApp, PendingIntent.FLAG_CANCEL_CURRENT));
```

同文件 `:360`（词按钮）与 `:378`（模式按钮）都是
`FLAG_UPDATE_CURRENT | (SDK_INT >= 23 ? FLAG_IMMUTABLE : 0)`，只有这处（requestCode 0，点通知打开 App）没有。

影响：当前 App `targetSdkVersion = 29`（已核），Android 12 的强制要求是 targetSdk ≥ 31；一旦升上去，未声明 mutability 的 `PendingIntent` 会在**创建时**抛 `IllegalArgumentException` → 建通知即崩。属"埋着的升级地雷"，而非现存故障。

`FORK_NOTES.md` 只写了"两处 `getService` 补 `FLAG_IMMUTABLE`"，这处 `getActivity` 从未在文档里出现。

### R2（中）`MusicService` 对外导出，自定义 action 不校验调用方

锚点：`android/src/main/AndroidManifest.xml`（`android:exported="true"`，无 `android:permission`）+ `MusicService.java:115-123`

任意第三方 App 只要：

```java
Intent i = new Intent().setClassName("com.luanmuc.lxmusic",
    "com.guichaguri.trackplayer.service.MusicService");
i.setAction("com.guichaguri.trackplayer.cycle_repeat"); // 或 toggle_desktop_lyric
startService(i);
```

就能让本机音乐 App 切换播放模式、开关桌面歌词。事件本身走 `LocalBroadcastManager`（进程内），**不会泄露数据**，也拿不到播放内容；但"外部能操控 UI 状态"这件事成立。

需要保留 `exported="true"` 的历史原因是媒体键（`Intent.ACTION_MEDIA_BUTTON` 与 `MediaButtonReceiver`）。收窄方案有两个方向：① 给 service 加 `android:permission`（自定义 signature 级）；② 自定义 action 改走 `getBroadcast` + 内部 receiver。两者都要真机验证媒体键是否仍响应 → 属需用户拍板的改动，本轮只记录不实施。

### R3（中）三处定制代码没有归口注释，后续升级定位靠猜

`FORK_NOTES.md` 的文件索引表列了 6 个 Java 文件，但全仓只有 5 个文件带 `lx-music fork` 注释：

| 文件 | FORK_NOTES 索引 | 有 fork 注释 |
|---|---|---|
| `service/metadata/MetadataManager.java` | ✅ | ✅ |
| `service/MusicService.java` | ✅ | ✅ |
| `service/MusicManager.java` | —（音频焦点） | ✅ |
| `module/MusicEvents.java` | ✅ | ✅ |
| `service/metadata/ButtonEvents.java` | — | ✅ |
| `service/models/TrackMetadata.java` | ✅ | ❌ |
| `module/MusicModule.java` | ✅ | ❌ |
| `service/MusicBinder.java` | ✅ | ❌ |

后果直接：下次上游升级时，`TrackMetadata.toMediaMetadata()` 与 `MusicModule.setNotificationRepeatMode/setLyricButtonActive` 是最容易被上游覆盖的地方，而没有注释就没人知道这里藏了定制。

### R4（低）`FORK_NOTES.md` 的锁定 sha 已过期

文档写 `App 侧当前锁定的 commit：87bf38ba`，实际已是 `eceacb5`（b75 起升级，只追加了 `quality`/`subSource` 两个可选类型字段）。同族问题在 App 侧刚修过（`KNOWN_ISSUES` K5），fork 侧自己这份没跟上。

### R5（低）仓库外部标识缺失：README 是上游原文，CHANGELOG 不是变更记录

- `README.md` 首屏仍是从上游原封不动的 logo + 徽章，**没有"这是定制 fork、定制见 FORK_NOTES"的任何提示**。
- `CHANGELOG.md` 内容实为：

```
# Installation
To install react-native-track-player see this guide…
```

即上游的安装说明被塞进了 CHANGELOG 文件名，**没有任何定制变更记录**。

影响：换人或换机器接手时，打开仓库无法判断这是定制版；只能靠翻 `FORK_NOTES.md` 才知道。

### R6（低·条件性）拔耳机路径与焦点路径状态不复位

锚点：`MusicManager.java:118-126`（`noisyReceiver`）

```java
if (playback != null) playback.pause();
service.emit(MusicEvents.BUTTON_PAUSE, null);
```

它只暂停 + 发事件，**不复位 `wasDucking` / `volumeBeforeDuck`**，也不清 `wasPlayingBeforeTransientLoss`。

两个可感后果（都依赖系统回调顺序，本环境无法取证 → 部分 blocked）：
1. 若拔耳机前正处于 ducking（音量已压到 0.2），暂停后音量仍是 0.2；若后续没走到 `LOSS`/`GAIN` 复位路径，下次播放音量偏小。
2. 若 `LOSS_TRANSIENT` 先于 becoming-noisy 到达（`wasPlayingBeforeTransientLoss=true`），随后 becoming-noisy 暂停、再 `GAIN` → 500ms 后自动恢复，可能**从扬声器外放**。

### R8（低）`@ts-ignore` 掩盖了无类型的 RN 内部模块

锚点：`src/trackPlayer.ts:2`

```ts
// @ts-ignore
import * as resolveAssetSource from 'react-native/Libraries/Image/resolveAssetSource'
```

上游遗留，非定制引入。风险是 RN 大版本升级后该路径改名会静默失效（封面 artwork 解析依赖它）。可用自建 `.d.ts` 声明替代，属清理项。

### R9（信息）TS 枚举 28 个值 Android 从不发出

比对结果：Java 的 20 个事件值 **全部**在 TS 枚举里（无遗漏）；反向有 28 个 TS 值 Android 不发，构成两类：
- 上游遗留按钮事件：`remote-like` / `remote-dislike` / `remote-bookmark`（Android 侧无对应按钮）
- iOS 音频会话类别：`ambient` / `playback` / `duckOthers` / `allowBluetooth`…（本就 iOS 专用）
- 媒体类型：`dash` / `hls` / `smoothstreaming`

**已核 App 未监听** `RemoteLike/RemoteDislike/RemoteBookmark`（App 里的 `handleRemoteDislikeAction` 属同步模块的点赞踩数据，与播放器事件无关）→ 无实际影响，仅类型声明与实现不对称。

### R10（信息·前瞻）前台服务用空通知 `startForeground`，且无 FGS 类型声明

锚点：`MusicService.java:132-136`（`onCreate` 里 `startForeground(1, 空 NotificationCompat.Builder().build())`）+ Manifest 仅有 `FOREGROUND_SERVICE` 权限。

targetSdk 29 下可用；升到 31+ 后必须补 `android:foregroundServiceType="mediaPlayback"`，且 Android 14 起 `startForeground` 的空通知会直接抛异常。与 R1 同属"升级 targetSdk 时必须一起处理"的一组。

### R11（信息）缓冲态传 `playing=false`，按钮语义偏差

`setLyricButtonActive` / `setRepeatMode` 用 `playback.getState() == 3` 作为 `playing` 传给 `updatePlayback`。缓冲态是 `STATE_BUFFERING(6)`，会被当成"未播放"→ 通知按钮显示"播放"图标，而此时点它应能暂停。属观感细节，非功能故障。

### R12（信息·设计取舍）artist 为空时音源与音质被整体丢弃

锚点：`TrackMetadata.java:92-99`

```java
if (artist != null && !artist.isEmpty()) { ...拼接 subSource/quality... }
```

即"无艺术家"的歌，即使有 `subSource`/`quality`，艺术家行也不会显示它们。这是为了不出现 `"· 树音 · FLAC"` 这种以分隔符开头的怪串而做的取舍，逻辑自洽；若要改成"无 artist 时以音源/音质填充"，需同时处理前导分隔符。记录供产品判断，不算缺陷。

## 二、通过项（逐条有实证，非"看着没问题"）

| 组 | 结论 | 证据 |
|---|---|---|
| A3 | `@ReactMethod` **36** ↔ TS 调用 **36**，双向差集均为空 | 正则提取方法名后做集合差，两个方向都打印为空 |
| A4 | Java 20 个事件值全部存在于 TS 枚举，定制事件 `remote-toggle-desktop-lyric` / `remote-cycle-repeat` 两侧逐字一致 | 值集合比对 |
| A6 | `lib/interfaces.d.ts:214` 含 `quality?: string`（App 侧编译产物已核） | App 内 `tsc -p` 产出后 grep |
| B1 | 标题不含音质后缀（只 `METADATA_KEY_TITLE = title`） | 源码 |
| B2 | 艺术家行顺序 `artist · subSource · quality`，缺项自动跳过，不产生连续分隔符 | 逐分支读 + 四组合推演 |
| B5 | 图标映射 `0→order / 2→single / 3→random / 其它→loop`，字段初值 `1` 正好落 loop | 源码 + 字段初始化行 |
| B6 | 去重 `if (repeatMode == mode) return` 在 `setRepeatMode` 首行，三条入口（Binder/Module/onStartCommand）最终都汇到它 | 转发链核对 |
| C1(部分) | 两处按钮 `PendingIntent` 的 `FLAG_IMMUTABLE` 与 `SDK_INT >= 23` 判断正确 | 源码 |
| C2 | `requestCode` 0 / 200 / 201 三名唯一，无冲突 | 全仓 grep |
| D1 | 焦点请求幂等（`if (hasAudioFocus) return`），重复播放不累积焦点栈 | 源码 |
| D2 | `AUDIOFOCUS_LOSS` 走 `pause()`，不再 `stop()`，进度不归零 | 源码 |
| D3 | 鸭子音量记住 `volumeBeforeDuck` 再还原，不写死 1.0；`LOSS` 与 `GAIN` 两路都复位 | 源码 |
| D4 | `setAudioAttributes(..., false)` 恒关内置 `AudioFocusManager`，避免双持有 | 源码 + 注释 |
| D5/D6/D7/D8 | 焦点丢失置 `wasPlayingBeforeTransientLoss`、请求失败不硬播、`abandonAudioFocus` 在 `MusicManager.destroy()` 与 `LOSS` 都调用 | 源码 |
| E2 | `repeatMode` 字段由 JS 同步写入、通知图标只读它，单一数据源 | 源码 |
| E4 | 队列索引有 `index < 0 \|\| index >= queue.size()` 防护 | 源码 |
| E8 | 定制新增的两个 setter 都做了 `manager.getPlayback() == null` 判空（b68 崩溃的直接修复） | 源码注释 + 分支 |
| F3 | `MusicService extends HeadlessJsTaskService`，是继承而非独立组件，无需在 Manifest 单独注册 | 源码 |
| F6 | `onDestroy` → `destroy()` + `stopForeground(true)`；`MusicManager.destroy()` 内 `abandonAudioFocus()` | 源码 |
| G1 | 20 张模式图标 4 状态 × 5 密度齐全，尺寸 24/36/48/72/96 | 文件矩阵 + PIL 读尺寸 |
| G2 | 全部 20 张 maxAlpha = **102**；`lyric` = **255**、`lyric_inactive` = **102** | PIL 逐像素统计 |
| G8 | 定制资源确实进包：`resources.arsc` 内 `notify_mode_order/loop/single/random`、`lyric_inactive`、`default_artwork` 各命中 1 次 | b77 APK 解包 |
| H1 | 15 条定制全部可在代码中定位（无虚报） | 逐条 grep 锚点 |
| H9 | 5 个文件覆盖 `lx-music fork` 注释（详见 R3 的缺口） | 全仓 grep |

**G8 的方法论注意**：直接对 APK 文件名 grep `notify_mode_` 是 **0 命中**——因为 release 构建把资源改成了短名（`res/-0.png`）。必须查 `resources.arsc` 的字符串表才准。这与"用 UTF-8 grep 查 Hermes 中文得到 0"是同一类假阴性陷阱。

## 三、blocked（不可在本环境取证）

| 编号 | 项 | 原因 |
|---|---|---|
| F5 | 进程被杀后通知是否残留（僵尸通知） | 无真机 |
| G5 | 空仓库 `npm install <sha>` 能否成功 | 沙箱 registry 缺 `flow-estree@0.339.0`（已改用"App 侧编译 rntp src"路线替代，L1+L3 已达成） |
| I4 | 通知栏真实渲染、点击回调、焦点抢占、图标观感、拔耳机外放 | 无真机 |

**部分（partial）17 维**的代表：A7（未跑 `npm pack --dry-run`）、C5（未全量 grep 日志输出）、E5（`FLAG_UPDATE_CURRENT` 覆写 extra 的行为需真机）、F2（服务回收后状态恢复为静态推定）、H6（仓库无 upstream remote，无法做官方全量 diff）。

## 四、与两轮前例的差异

App 与音源审计的缺陷多来自"单一真相被复制成两份"和"死键/死字段"，本仓库的问题形状不同：

1. **加固只加固了一半**（R1：三处 `PendingIntent` 补了两处）
2. **守卫条件与注释意图相反**（R7：`!= 3` 放行 PAUSED）
3. **定制面靠文档维护、代码无标记**（R3/R4/R5）
4. **对外暴露面随定制一起放大**（R2：自定义 action 进了导出服务）

## 五、处理建议（按风险收敛，不是按编号）

| 顺序 | 动作 | 影响面 | 真机需求 |
|---|---|---|---|
| 1 | R7 守卫条件改为"仅当被打断且用户未手动暂停时恢复"（新增 `userPausedDuringInterrupt` 标志，在 `pause()` 的公开入口置位） | 仅 `MusicManager` | 可静态验证 |
| 2 | R1 补 `FLAG_IMMUTABLE`（与另两处同一写法） | 1 行 | 无 |
| 3 | R3 补 3 处 `lx-music fork` 注释 + 同步 `FORK_NOTES` 索引表 | 纯注释 | 无 |
| 4 | R4/R5 更新 fork 文档：sha 改 `eceacb5`、README 首屏加 fork 标识、`CHANGELOG` 改为真正的定制变更记录 | 文档 | 无 |
| 5 | R2 收窄导出（签名级权限或 action 校验） | 涉及媒体键兼容 | **需真机验媒体键** |
| 6 | R6 在 `noisyReceiver` 里一并复位 `wasDucking`/`volumeBeforeDuck` | 几行 | 拔耳机行为需真机验 |
| 7 | R8 用 `.d.ts` 替掉 `@ts-ignore` | 低 | 无 |

R10 与 R1 应在"升级 targetSdk 到 31+"同一批次处理；R9/R11/R12 仅记录，不做改动。
