# 本 fork 的定制说明

本仓库是 `react-native-track-player` 的定制 fork，供 `lx-music-mobile`（落雪音乐）使用。

**合并上游时必须保留以下补丁**，否则 App 通知栏的相关功能会静默失效（不报错、只是不显示/不切换）。

## 分支与基线

- 开发分支：`v2`
- App 侧当前锁定的 commit：`a0b2a87`（b78 起从 `eceacb5` 升上来，取的是本 fork 2026-11-07 全覆盖审计的修复：焦点续播不覆盖用户暂停、`contentIntent` 补 `FLAG_IMMUTABLE`、服务挂 `PLAYER_CONTROL` 权限、耳机拔出复位音量、归口注释补齐。`eceacb5` 是更早的 b75 基线，本身只追加了 `quality` / `subSource` 两个可选类型字段、未改定制逻辑）
- 本文件里其余各条后面的短 sha 是**该条定制落地时的历史提交**，不是当前基线，改动时以本条为准

> 上游官方已不再维护 `v2` 分支（最新为 v5，Android 侧已重写为 Kotlin）。
> 因此本 fork **不做上游跟进**：任何上游变更都要按本文件的定制清单逐条重放，无法直接 merge。

## 定制清单

### 音频焦点（B54 基线 `01f34b15`）

- `requestAudioFocus` 加幂等：原实现每次播放都重复请求，焦点栈累积会触发系统下发 `LOSS`，`stop()` 把刚开始的播放打断，表现为点播放一直停在缓冲态
- `AUDIOFOCUS_LOSS` 由 `stop()` 改为 `pause()`，不再把进度归零，同时复位鸭子音量
- 关闭 media3 内置的 AudioFocusManager，避免与自实现的双重持有、互发 `LOSS`
- 打断结束的自动续播**不得覆盖用户的主动暂停**（`userPausedDuringInterrupt`）：只看 `getState()` 区分不出「系统打断暂停」和「用户按了暂停」，两者都是 `STATE_PAUSED(2)`，旧写法会让用户刚按暂停、通话一结束音乐又响起来。用户暂停统一走 `MusicManager.pauseByUser()`
- 拔耳机（`ACTION_AUDIO_BECOMING_NOISY`）除了暂停，还要**先复位鸭子音量**，否则拔线前若正在压低，音量会一直停在 20%；同时按「用户主动暂停」记录，避免打断结束时从扬声器外放

### 通知栏基础参数（`01f34b15`）

- `quality` / `subSource` 在 track bundle 中被读取并注入通知栏，此前 Java 侧恒为 `null`
- `displayTitle` / `displayArtist` 拼接前判空，避免出现 `"null [FLAC]"` / `"null · xxx"`

### 歌词按钮（`01f34b15`）

- 新增「词」按钮，Action 走 `ACTION_TOGGLE_DESKTOP_LYRIC`，点击后 emit `remote-toggle-desktop-lyric`
- `setLyricButtonActive` 控制图标白/灰：`lyric`（纯白 alpha=255）/ `lyric_inactive`（纯白 alpha=102）

### 标题与艺术家行（`e9585c96`）

- `TrackMetadata.toMediaMetadata()`：标题只放歌名，不再挂音质后缀
- 艺术家行用 `StringBuilder` 依次追加 `" · " + subSource`、`" · " + quality`，缺项自动跳过
- 效果：标题「黄昏」/ 艺术家「周传雄 · 树音 · FLAC」

### 播放模式按钮（`ca72e89d`）

- 在「词」按钮之后追加一个模式按钮，requestCode 201，Action 走 `ACTION_CYCLE_REPEAT`，点击后 emit `remote-cycle-repeat`
- 新增 `setNotificationRepeatMode(int)`：`0`=顺序 `1`=列表循环 `2`=单曲循环 `3`=随机
- 图标 4 套 × 5 密度，统一白色 alpha=102（与「词」关闭态一致，不做白灰二态）
- 原生侧 `if (repeatMode == mode) return` 去重，防止连点重复刷通知

### PendingIntent 加固（`87bf38ba`）

- 两处 `PendingIntent.getService`（requestCode 200 / 201）补 `FLAG_IMMUTABLE`
- 点通知打开 App 的 `PendingIntent.getActivity`（requestCode 0）同样补上 `FLAG_IMMUTABLE`——这处原先漏了，targetSdk 升 31+ 时创建即抛 `IllegalArgumentException`，建通知直接崩
- minSdk 21，用 `Build.VERSION.SDK_INT >= 23` 判断兼容；targetSdk 升到 31+ 前必须保留

### 服务导出面加固（审计 `eceacb5` 补）

- `MusicService` 必须 `exported="true"`（系统媒体键经 `MediaButtonReceiver` 转发进来），但两个自定义 action 走的是同一个服务，导出后任何第三方 App 都能 `startService` 伪造「切播放模式」「开关桌面歌词」
- 用 `signature` 级自定义权限 `com.guichaguri.trackplayer.permission.PLAYER_CONTROL` 收回：库 manifest 里同时声明 `<permission>` 与 `<uses-permission>`，缺后者连同签名的本 App 都调不进来

## 验证方式（2026-11-07 决定，勿反复纠结）

- **唯一验证入口：App 仓库的 release CI**（`luanmuc/mobile-app` 的 `.github/workflows/release.yml`）。它的 gradle 直接编译 `node_modules/react-native-track-player/android` 下的 Java 源码，产物就是用户装的包，是最贴近真实消费链路的验证。
- **本 fork 自带的 `platform-validation.yml` 刻意不启用**。fork 仓库需 owner 到 `/actions/enable` 手动确认后才允许跑 workflow（API 会显示 `enabled: true`，但 `workflow_runs` 恒为 0，这是 fork 特有的安全默认）。不启用的理由：
  1. 它验的是 fork 自带的 example 工程（`example/android ./gradlew assembleDebug`），跟 App 实际消费的链路不是一条，它绿了不代表 App 编得过；
  2. 它还带 iOS / Windows 两个 job，跑在 `macos-latest` + 老 simulator 配置上，大概率长期红，变成噪音；
  3. fork 的任何改动都要升 App 的 lock 才对用户生效，App 编译本来就是必经环节，多一套 CI 是重复建设。
- 所以：**推送本 fork 后看到 Actions 一个 run 都没有，是预期状态，不是故障**。要验证就走「升 App lock → push master → 看 Build workflow 的 Android job」。
- **唯一例外**：若将来需要在不碰 App 的情况下独立验本 fork，先把 workflow 精简成只编 Android library 的 AAR（仓库根 `android/` 下 `./gradlew assembleRelease`，不依赖 example 工程），再去 `/actions/enable` 启用；上游原版那个三平台 workflow 不要直接开。

## 文件索引

| 文件 | 改了什么 |
|---|---|
| `android/.../service/metadata/MetadataManager.java` | 词按钮、模式按钮、`repeatMode` 字段与 `setRepeatMode`、两处 PendingIntent |
| `android/.../service/models/TrackMetadata.java` | `toMediaMetadata()` 标题与艺术家行拼接 |
| `android/.../service/MusicService.java` | `ACTION_TOGGLE_DESKTOP_LYRIC` / `ACTION_CYCLE_REPEAT` 常量与 `onStartCommand` 分支、音频焦点幂等 |
| `android/.../module/MusicEvents.java` | `BUTTON_TOGGLE_DESKTOP_LYRIC` / `BUTTON_CYCLE_REPEAT` 事件名 |
| `android/.../module/MusicModule.java` | `@ReactMethod setNotificationRepeatMode` |
| `android/.../service/MusicBinder.java` | `setRepeatMode(int)` 转发 |
| `src/interfaces.ts` / `src/trackPlayer.ts` | `RemoteCycleRepeat` 事件类型、`setNotificationRepeatMode` 导出 |
| `android/src/main/res/drawable-*/notify_mode_{order,loop,single,random}.png` | 20 张模式图标 |

## 升级上游时注意

1. `MetadataManager.updatePlayback(boolean playing)` 里有两处自定义 `builder.mActions.add`，上游重构 action 构建时最容易丢
2. `MusicService.onStartCommand` 有三个定制分支（媒体键 / 词 / 模式），顺序敏感，上游插入新分支时逐个核对
3. 导出名必须是 `setNotificationRepeatMode`——上游自带 `setRepeatMode(mode: RepeatMode)`，重名会导致导出冲突
4. requestCode 200 与 201 已被占用，新增按钮别复用。另外 requestCode 0（点通知开 App）也要保持 `FLAG_IMMUTABLE`，三处一起看
5. 模式图标的 alpha 必须与 `lyric_inactive` 一致（102），否则在通知栏里深浅不一
6. **暂停只能走 `MusicManager.pauseByUser()`**（或 emit 前先 `markUserPaused()`）。上游若新增"直接 `playback.pause()`"的入口，会让焦点打断逻辑把这次用户暂停当成"等待恢复"，通话结束后自动续播
7. `MusicBinder.getPlayback()` 会**懒创建**一个空 Bundle 的播放器（全部默认选项）。别在 `setupPlayer` 之前调它，否则定制开关会被默认值覆盖
8. `MusicService` 上的 `PLAYER_CONTROL` 权限别删：删了服务就重新对外裸奔（第三方能伪造两个自定义 action）
