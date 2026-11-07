# 本 fork 的变更记录（lx-music fork）

> 本文件上半部分是**本 fork 自己的变更**；下半部分（从「Upstream: Installation」起）是上游原文，与本 fork 无关。
> 详细定制理由见 [`FORK_NOTES.md`](./FORK_NOTES.md)；审计记录见 `docs/AUDIT_REPORT-2026-11-07.md`。

## 2026-11-07 · 审计修复（基线 `eceacb5`）

- 修复「打断后自动续播覆盖用户主动暂停」：新增 `userPausedDuringInterrupt`，用户暂停统一走 `MusicManager.pauseByUser()`
- 点通知打开 App 的 `PendingIntent` 补 `FLAG_IMMUTABLE`（原先只有两个按钮补了，这处漏）
- `MusicService` 加 `signature` 级自定义权限，堵住第三方 App 伪造「切播放模式 / 开关桌面歌词」
- 拔耳机时复位鸭子音量，并按用户主动暂停记录
- 补齐 `TrackMetadata` / `MusicModule` / `MusicBinder` 三处 `lx-music fork` 归口注释
- 文档：`FORK_NOTES` 锁定 sha 更新为 `eceacb5`、README 补 fork 标识、新增审计三件套（计划/报告/执行记录）

## 历史定制（按落地提交）

| 提交 | 内容 |
|---|---|
| `01f34b15` | 音频焦点三项、通知栏 `quality`/`subSource` 注入与判空、歌词按钮 |
| `e9585c96` | 标题只放歌名、艺术家行拼接音源与音质 |
| `ca72e89d` | 播放模式按钮（requestCode 201、4 套 × 5 密度图标 alpha=102、原生去重） |
| `87bf38ba` | 两处按钮 `PendingIntent` 补 `FLAG_IMMUTABLE` |
| `eceacb5` | 仅追加 `quality` / `subSource` 类型字段（b75 升级） |

---

# Upstream: Installation

To install `react-native-track-player` see this [guide](https://react-native-track-player.js.org/install/).

To install release candidates run `yarn add react-native-track-player@next`

## Main

##### Enhancements

* Add property `isLiveStream` to `Track` for correct display in iOS control center.
  [dcvz](https://github.com/dcvz)

##### Bug Fixes

* Make react-native-windows and optional peer dependency (#1324).
  [jspizziri](https://github.com/jspizziri)

## 2.1.2 (25.10.21)

##### Enhancements

* None.

##### Bug Fixes

* Update SwiftAudioEx - Fixes issues with flickering notifications + pause between loads

* Fix cyclic require warning regression
  [#1057](https://github.com/DoubleSymmetry/react-native-track-player/issues/1057)

* [ios] Fix `PlaybackQueueEnded` event to be called only when the track ends
  [#1243](https://github.com/DoubleSymmetry/react-native-track-player/issues/1243)

## 2.1.1 (25.09.21)

##### Enhancements

* [ios] Fix getCurrentTrack returns undefined instead of null
* [ios] Fix getTrack returning undefined instead of nil
* Fix an issue with next/previous in the control center stopping playing on iOS15

##### Bug Fixes

* None.

## 2.1.0 (16.09.21)

##### Enhancements

* None.

##### Bug Fixes

* Remove Support for iOS 10 & Support Xcode 13
  [dcvz](https://github.com/dcvz)
  [#1186](https://github.com/DoubleSymmetry/react-native-track-player/issues/1186)
  - **NOTE: Requires minimum deployment target to be updated to iOS 11.**

* Reset initialization on destroy
  [sreten-bild](https://github.com/sreten-bild)

* Fix `onTaskRemoved` NullPointerException
  [Markario](https://github.com/Markario)

## 2.0.3 (19.08.21)

##### Enhancements

* None.

##### Bug Fixes

* Fix `Event.PlaybackQueueEnded` firing on initialization on Android
  [dcvz](https://github.com/dcvz)
  [#1229](https://github.com/DoubleSymmetry/react-native-track-player/issues/1229)

* Make `useProgress` unmount aware.
  [lyswhut](https://github.com/lyswhut)

* Make `usePlaybackState` unmount aware.
  [dcvz](https://github.com/dcvz)

## 2.0.2 (15.08.21)

##### Enhancements

* Import SwiftAudioEx through podspec
  [dcvz](https://github.com/dcvz)

##### Bug Fixes

* `useProgress` hook should update while paused
  [dcvz](https://github.com/dcvz)


## 2.0.1 (11.08.21)

##### Enhancements

* None.  

##### Bug Fixes

* Add `startForeground` to `onCreate`.
  [Bang9](https://github.com/Bang9)
  [#620](https://github.com/DoubleSymmetry/react-native-track-player/issues/620)
  [#524](https://github.com/DoubleSymmetry/react-native-track-player/issues/524)
  [#473](https://github.com/DoubleSymmetry/react-native-track-player/issues/473)
  [#391](https://github.com/DoubleSymmetry/react-native-track-player/issues/391)

* Fix compilation of Windows module.
  [dcvz](https://github.com/dcvz)

* Fix regression in updating artwork on `updateMetadata` and `updateNowPlayingMetadata`
  [dcvz](https://github.com/dcvz)
  [#662](https://github.com/DoubleSymmetry/react-native-track-player/issues/662#issuecomment-896370375)
