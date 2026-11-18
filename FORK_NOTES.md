# 本 fork 的定制说明

本仓库是 `react-native-track-player` 的定制 fork，供 `lx-music-mobile`（落雪音乐）使用。

**合并上游时必须保留以下补丁**，否则 App 通知栏的相关功能会静默失效（不报错、只是不显示/不切换）。

## 分支与基线

- 开发分支：`v2`
- App 侧当前锁定的 commit：`b9ecdab2`（b158 起从 `82e4e21` 升上来；`b9ecdab2` 含音频焦点 B 方案状态机。音高桥方法在 `536521c`，App 侧尚未锁到它——`setPlaybackPitch` 对旧 sha 有存在性守卫，装 `b9ecdab2` 时静默跳过、不崩；锁定 `536521c` 后自动生效。更早的 `82e4e21`（b78 起）从 `eceacb5` 升上来，取的是本 fork 2026-11-07 全覆盖审计的修复：焦点续播不覆盖用户暂停、`contentIntent` 补 `FLAG_IMMUTABLE`、服务挂 `PLAYER_CONTROL` 权限、耳机拔出复位音量、归口注释补齐。`eceacb5` 是更早的 b75 基线，本身只追加了 `quality` / `subSource` 两个可选类型字段、未改定制逻辑）
- 本文件里其余各条后面的短 sha 是**该条定制落地时的历史提交**，不是当前基线，改动时以本条为准

> 上游官方已不再维护 `v2` 分支（最新为 v5，Android 侧已重写为 Kotlin）。
> 因此本 fork **不做上游跟进**：任何上游变更都要按本文件的定制清单逐条重放，无法直接 merge。

## 定制清单

### audioSessionId 桥方法（b161 需求，`4e2cb01` 之后新增）

- `ExoPlayback.getAudioSessionId()`：media3 的 `Player` 接口没有此方法，只有 `ExoPlayer` 有 → `instanceof` 判断后调用；未建立会话返回 `C.AUDIO_SESSION_ID_UNSET`（0）。
- `MusicModule.getAudioSessionId` 走 `waitForConnection`。
- 用途：App 侧系统均衡器（`android.media.audiofx.Equalizer`）必须挂在这个会话上才生效。
- **验证方式**：用真实 media3 1.8.0 aar + android.jar 35 对方法体逐字复刻做 javac 编译探针（RC=0），不是「看着像对」。


### 音高桥方法（b160 需求，`b9ecdab2` 之后新增）

- `ExoPlayback` 新增 `getPitch()` / `setPitch(float)`：`PlaybackParameters` 的 speed 与 pitch 是两个独立维度，`setPitch` 必须沿用当前 speed、`setRate` 必须沿用当前 pitch，否则单纯调音高会把语速设置抹掉（反之亦然）。
- `MusicModule` 新增 `@ReactMethod setPitch` / `getPitch`，走 `waitForConnection`（连接前调用会被排队，不会写空内核）。
- JS 层 `src/trackPlayer.ts` 新增 `setPitch` / `getPitch`，与 `setRate` 同构（`isSetupedPlayer` 未就绪时早退）。
- 上游 npm 版（2.1.2）没有这几个方法，App 侧 `setPlaybackPitch` 对 `setPitch` 做了**存在性守卫**：装旧包时静默跳过、不崩；装本 fork 后自动生效。


### 审计包修复（2026-11-16）

- `Track.queueId`：由 `System.currentTimeMillis()` 改为进程内 `AtomicLong` 自增 —— 批量 add 同一毫秒会产生重复 `queueId`，通知栏/队列项 的 id 会撞。
- `MusicService.onStartCommand`：`manager` / `handler` 改为判空复用 —— 原实现每次 start 都 `new MusicManager`，旧实例要到 `onDestroy` 才释放，多次 start 会泄漏（音符/播放器各持一份）。
- `MusicManager` 音频焦点恢复条件：`getState() != 3` → `getState() == PlaybackStateCompat.STATE_PAUSED` —— 两套常量同名不同值（`PlaybackStateCompat.PLAYING=3` vs media3 `Player.STATE_READY=3`），原写法在 `BUFFERING/IDLE/ENDED` 时也会误续播；只在「确实还在暂停」时恢复，与注释意图一致。
- `MetadataManager.setLyricButtonActive` / `setRepeatMode` 的 `getState() == 3` 同样换成 `PlaybackStateCompat.STATE_PLAYING`（同一类魔法数，三处一起清）。
- `ExoPlayback.lastKnownPosition`：`C.INDEX_UNSET`（int -1）→ `-1L` —— 明确 long 类型意图，避免 `-1` 被当合法位置的歧义。
- `package.json` 的 `postversion` 不再自动 `git push && git push --tags`，改为提示人工确认（`npm version` 曾会把版本提交直接推到远端）。
- `android/build.gradle`：`track-player.json` 机制恢复（原被整段注释，文档却还在教用户配）；默认仍 `dash/hls/smoothstreaming=false`，只有配置文件存在才覆盖。
- `Track` 的 dash/hls/smoothstreaming 分支加 `Class.forName` 可用性判断，缺失时降级 progressive（此前 `compileOnly` 依赖在关闭态下会 `NoClassDefFoundError`）。
- `android/build.gradle` 的 AGP 由 `7.0.4` 对齐到消费方 App 的 `8.6.1`（7.0.4 不支持 `namespace` DSL，独立构建会直接失败）。
- `package.json` 版本改为 `2.1.2-lx.N`（不再恒等 2.1.2），仍以 commit sha 作为 App 侧锁定依据。

> 以上均为 Java 侧改动，本机无 Android SDK，**只做静态复查，未编译**。可编译环境需补跑 `./gradlew assembleRelease`。

### 音频焦点（B54 基线 `01f34b15`）

- `requestAudioFocus` 加幂等：原实现每次播放都重复请求，焦点栈累积会触发系统下发 `LOSS`，`stop()` 把刚开始的播放打断，表现为点播放一直停在缓冲态
- `AUDIOFOCUS_LOSS` 由 `stop()` 改为 `pause()`，不再把进度归零，同时复位鸭子音量
- 关闭 media3 内置的 AudioFocusManager，避免与自实现的双重持有、互发 `LOSS`
- 打断结束的自动续播**不得覆盖用户的主动暂停**（`userPausedDuringInterrupt`）：只看 `getState()` 区分不出「系统打断暂停」和「用户按了暂停」，两者都是 `STATE_PAUSED(2)`，旧写法会让用户刚按暂停、通话一结束音乐又响起来。用户暂停统一走 `MusicManager.pauseByUser()`
- 拔耳机（`ACTION_AUDIO_BECOMING_NOISY`）除了暂停，还要**先复位鸭子音量**，否则拔线前若正在压低，音量会一直停在 20%；同时按「用户主动暂停」记录，避免打断结束时从扬声器外放

### 音频焦点状态机（B 方案，2026-11-18）

把「打断前是什么样」从两个布尔升级成显式状态机，原因是单布尔表达不了交叉态：缓冲中被打断、连续两次打断、打断期间用户改音量、通话中拔耳机——这些在旧实现里分别表现为"不续播/续播丢失/音量被旧值覆盖/挂断外放"。

- 新增 `service/focus/AudioFocusPolicy.java`：**零 Android 依赖**的纯逻辑类，输入「事件 + 播放器快照」，输出**动作列表**；4 个焦点回调、用户暂停/播放/改音量、拔线、销毁全部走它。
- `MusicManager` 退化为采集与执行：`dispatch(event)` → `execute(actions)`，不再自己判断要不要续播。
- 修掉的 6 条：
  - **R1** `onPlay` 不再"焦点申请失败也照播"（`requestAudioFocus` 返回 boolean，失败走 `FOCUS_DENIED` → 暂停 + 告警日志）；
  - **R2** 续播欠账改**累积**（`resumePending |= 意图在播`），连续两次打断不再丢；
  - **R3** "意图在播"包含 `BUFFERING/CONNECTING` 与 `playWhenReady`，缓冲中被打断也能续播；
  - **R4** 永久丢失的动作顺序改为**先 PAUSE 再 ABANDON**；
  - **R5** `destroy()` 补 `SERVICE_DESTROY`（释放焦点 + 复位状态），原来这段是注释掉的；
  - **R6** 用户改音量走 `MusicManager.setUserVolume()` → 策略清掉"鸭子待恢复"，GAIN 不再用旧值覆盖。
- 另外两条策略级修正：`CAN_DUCK` 只在**真正在播**时才改音量（暂停态收到 CAN_DUCK 不再把音量改成 20% 卡住）；鸭子目标改**比例**（`音量×0.25`）而非绝对 `0.2`。
- `onPause()` 不再注销 `noisyReceiver`（注销统一放 `onStop`/`destroy`），否则通话中拔耳机没人拦，挂断续播会从扬声器外放。
- 门禁：`scripts/check_audio_focus_policy.js` —— javac 编译「策略类 + 场景 runner」后跑断言，覆盖主路径、R1~R6、连续打断、鸭子中被打断、拔线、销毁复位、两个子开关，共 **28 条**；静态断言禁止策略类引入 `android.*` 与裸数字状态比较。

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

### PendingIntent 加固（`87bf38ba` 起，审计 `a0b2a87` 补 requestCode 0）

- 两处 `PendingIntent.getService`（requestCode 200 / 201）补 `FLAG_IMMUTABLE`
- 点通知打开 App 的 `PendingIntent.getActivity`（requestCode 0）同样补上 `FLAG_IMMUTABLE`——这处原先漏了，targetSdk 升 31+ 时创建即抛 `IllegalArgumentException`，建通知直接崩
- minSdk 21，用 `Build.VERSION.SDK_INT >= 23` 判断兼容；targetSdk 升到 31+ 前必须保留

### 服务导出面加固（审计 `a0b2a87` 新增）

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

## 文档一致性闸门（本 fork 唯一自动机制）

本 fork 不启用自带 CI，因此文档同步靠两层，都在本地：

| 层 | 命令 | 覆盖 |
|---|---|---|
| 可计算项（4 条） | `node scripts/docs-audit.js` | 模式图标张数 / App 侧锁定 sha（跨仓读 App `package.json`）/ `PLAYER_CONTROL` 三处声明 / 4 个定制符号存在性 |
| 行为语义 | `docs/DOC_SEMANTIC_CHECKLIST.md` 逐项人工过 | 模式映射含义、暂停语义、音质透传、上游合并注意事项 |
| 推送前闸门 | `scripts/hooks/pre-push` | 本仓 `docs-audit` 出现 🔴 时拒绝 `git push` |

安装（每个 clone 只需一次）：

```bash
git config core.hooksPath scripts/hooks   # 提交时自动打印红/黄项
```

跨仓检查需要兄弟仓可读：`docs-audit.js` 会去读 `../lx-music-mobile/package.json`，本地无该目录时该条显示 ⚪ 跳过。

### 本地语法检查

`scripts/check-java-syntax.js`（`npm run check:java`）：只判语法，把 Android/media3 依赖缺失的报错归为噪声。
目的是把「少分号 / 括号不配对」这类错误从 CI 的 15 分钟反馈里挪到本地 5 秒，不替代 CI 编译。
