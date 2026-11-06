# 本 fork 的定制说明

本仓库是 `react-native-track-player` 的定制 fork，供 `lx-music-mobile`（落雪音乐）使用。

**合并上游时必须保留以下补丁**，否则 App 通知栏的相关功能会静默失效（不报错、只是不显示/不切换）。

## 分支与基线

- 开发分支：`v2`
- App 侧当前锁定的 commit：`87bf38ba`

## 定制清单

### 音频焦点（B54 基线 `01f34b15`）

- `requestAudioFocus` 加幂等：原实现每次播放都重复请求，焦点栈累积会触发系统下发 `LOSS`，`stop()` 把刚开始的播放打断，表现为点播放一直停在缓冲态
- `AUDIOFOCUS_LOSS` 由 `stop()` 改为 `pause()`，不再把进度归零，同时复位鸭子音量
- 关闭 media3 内置的 AudioFocusManager，避免与自实现的双重持有、互发 `LOSS`

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
- minSdk 21，用 `Build.VERSION.SDK_INT >= 23` 判断兼容；targetSdk 升到 31+ 前必须保留

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
4. requestCode 200 与 201 已被占用，新增按钮别复用
5. 模式图标的 alpha 必须与 `lyric_inactive` 一致（102），否则在通知栏里深浅不一
