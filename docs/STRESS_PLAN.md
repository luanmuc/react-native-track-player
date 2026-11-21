# 播放器仓库（fork）压力测试计划 · 2026-11-20

> 对象：`luanmuc/react-native-track-player`（本地 `rntp/`），分支 `v2`，基线 **`3f8546d`**（音频焦点 5 条 P1 收口 RT-01~RT-05；App 侧锁定同一 sha）。
>
> **本文件是 rntp 的第一份压测计划**（此前只有 `AUDIT_PLAN-2026-11-07.md` 与 `AUDIT_REPORT`，没有压测口径）。定位与兄弟仓一致：用**可重复、带退出码**的用例，证明「不崩、不越界、不静默失效、不假绿」，而不是截图式的一堆绿字。

---

## 0. 目标与边界

**目标**：在无 Android SDK / 无真机的环境里，把能压的部分压到极限并留证据：

1. **Java 静态全量**：18 个 `.java` / 4,204 行逐文件 `javac` 语法检查
2. **焦点策略行为**：67 条场景断言（含短打断分级与通用性补修 30 条）+ 红-绿双向验证（换成修复前策略类必须变红）
3. **TS 契约层**：4 文件 / 748 行 `tsc` 编译与 `lib/` 产物一致性
4. **静态不变量**：队列 ID 单调、状态比较无裸数字、`postDelayed` 具名常量、AGP/SDK 自洽
5. **集成落地**：产物打进 App 后 APK 内确实带自定义 drawable 与新增方法（L3）

**边界（blocked，不写成通过）**：

| 项 | 原因 |
|---|---|
| 真机通知栏渲染 / 点击回调 / 焦点抢占 / 僵尸通知 / 图标观感 | 无 adb / 无 KVM / 无设备 |
| Android 运行时并发压测（多线程 dispatch、焦点抢占风暴） | 无法起 instrumentation / 无模拟器 |
| iOS / macOS / Windows 编译与运行 | App 只发 Android；Swift 仅静态影响评估 |
| 上游全量 diff 压测 | 仓库无 upstream remote 与完整 tag 基线 |

---

## 1. 压测分组（一表到底）

| # | 套件 | 命令 | 规模 | 通过标准 | 层级 |
|---|---|---|---|---:|---|
| P1 | Java 语法全量 | `node scripts/check-java-syntax.js` | 18 文件 / 4,204 行 | **0 条语法错误**；类路径缺失（无 SDK）按预期不判红 | L0 |
| P2 | 音频焦点策略行为 | `node scripts/check_audio_focus_policy.js` | **67 条**场景断言 | **67/0**；且换回修复前策略类必须 **≥2 红** | L2 |
| P3 | TS 契约层编译 | `npx tsc -p .`（App 侧 typescript） | 4 文件 / 748 行 | rc=0，`lib/*.d.ts` 含 `quality` / `subSource` | L1 |
| P4 | 静态不变量 | `node scripts/check_truth_gates.js` | 4 条（G1~G4） | 全一致 | L0 |
| P5 | 文档镜像 | `node scripts/docs-audit.js --dry-run` | 全部 `.md` | 0 待处理 / 0 硬失败 | L0 |
| P6 | 变异注入（红证） | 手工 3 处注入后跑 P2 | 3 个变异体 | **3/3 被抓**（详见 §3） | L2 |
| P7 | 集成落地 | App CI 构建后跑 `scripts/verify_apk_integration.js`（解包 APK 读 `resources.arsc` 与 `classes*.dex` 字符串池） | 每次构建 | **9 条全绿**（b191 实测）：`notify_mode_loop/order/random/single` 四个资源名 + `setNotificationRepeatMode` + `setPitch` + `resources.arsc`/`classes.dex` 存在性 | L3 → **已执行** |

**当前实测（2026-11-21）**：变异测试 **4/4 被抓、0 存活**（已进 CI）；P1 **0 错**（5854 条类路径缺失属预期）；P2 **67/0**；P4 **4 条全一致**；**P7 已执行（9/0，b191 包实测）**；P3/P5 见 §4 记录。

**CI 执行点（2026-11-20 新增）**：`.github/workflows/quality-gates.yml`（job `gates`，`push`/`pull_request` 触发）——`actions/setup-java@4` 装 Temurin 17 后跑 P1/P2/P4/P3，并把 App 仓 checkout 到 `.app` 经 `LX_APP_ROOT` 注入，让 `docs-audit` 的「App 锁定 sha」跨仓断言在云端也能真校验（原来只有本地跑）。此前 `platform-validation.yml` 只做三平台 build，四个门禁在 CI 里零执行点。

---

## 2. 执行顺序（按依赖）

```bash
# 1) 静态与语法（最快，先排除"根本编不过"）
node scripts/check-java-syntax.js          # 18 文件 / 0 错
node scripts/check_truth_gates.js         # 4 条不变量
node scripts/docs-audit.js --dry-run      # 文档镜像

# 2) 行为层（焦点策略 67 条）
node scripts/check_audio_focus_policy.js  # 67/0

# 3) 契约层（App 侧 typescript 编 rntp src）
npx tsc -p .                              # rc=0

# 4) 变异注入（改一处 → 跑 P2 → 必须红 → 还原）
#    不可与其它脚本并发，避免 javac 抢占导致假红

# 5) 集成（打进 App 出 APK 后解包核验 drawable / 方法）
```

> 计时类断言不与 javac 并发；本仓断言全是**确定性断言**（无时间阈值），但 `javac` 在 CPU 满载下仍可能超时，串行执行。

---

## 3. 变异体设计（P6 红证，证明断言不是摆设）

**2026-11-21 起由 `scripts/mutation-test.js` 自动化执行，并接进 `quality-gates.yml` 常跑（单次约 9s）。**

| # | 变异 | 期望 | 依据 | 实测（2026-11-21） |
|---|---|---|---|---|
| M1 | 短打断分支 `if (!ducked)` 守卫改成恒真 | 二次压低断言**变红** | 连续两次 TRANSIENT 叠乘 `DUCK_RATIO`，音量掉到 6.25%（G3） | **65 / 2** ✅ |
| M2 | `FOCUS_RECHECK` 在 `phase==NORMAL` 直接 break | 复核收尾断言**变红** | USER_PLAY 清 phase 后音量永久卡 25%（G2） | **66 / 1** ✅ |
| M3 | `RECHECK_DENIED` 分支改成按欠账续播 | 长占用降级断言**变红** | 闹钟/长通话期间以低音量继续出声（G1） | **66 / 1** ✅ |
| M4 | `resumePending = resumePending \|\| …` 改成直接赋值 | RT-02 累计断言**变红** | 第二次打断抹掉第一次播放意愿 | **65 / 2** ✅ |

**旧版 M1~M3 已作废（2026-11-21 纠正）**：原表的三条变异点在 `MusicManager`（`dispatch()` 撤销续播、`resetFocus()` 恢复音量），而 67 条断言测的是 `AudioFocusPolicy` 纯逻辑类，不经过 `MusicManager`。实测：删掉 `dispatch()` 的 `cancelPendingResume()` → 门禁仍 **67/0**；删掉 `resetFocus()` 的 `setVolume` → 仍 **67/0**。这两条从来不是有效红证，只是文档写着「已验证」——属于「未执行留下的错」。真要覆盖 `MusicManager` 的行为，得另建一层带 Handler/Playback 桩的测试，不在纯策略门禁范围内，已记为 P3 遗留（见 §4 备注列）。

**纪律**（已内置进脚本，不靠人记）：
1. 每条变异必须「编译通过」但「断言变红」——编译失败说明变异体本身写错，不算抓住。
2. 任何一条变异「改完仍然全绿」即判红：说明该缺陷没有任何断言覆盖。
3. 还原用原文覆写（不是 `git checkout`，避免误伤工作区里未提交的改动），收尾必须复跑基线回到 **67/0**。

**历史红证**：M4 所对应的 RT-02，修复前实测 `12 passed / 2 failed`（37 条时代的记录），修复后 37/0。

## 4. 实测记录

| 日期 | 基线 | P1 | P2 | P4 | 备注 |
|---|---|---|---|---|---|
| 2026-11-21 | `c07727e` + 变异测试 | 18 文件 / **0 错** | **67/0** | 4 条全一致 | P6 自动化：4 条变异体全部被抓（65/2、66/1、66/1、65/2），0 存活；旧 M1~M3 作废（变异点在 MusicManager，纯策略断言覆盖不到，实测仍 67/0）；`check_audio_focus_policy` 改 `spawnSync` 后红时能报「65 通过 / 2 失败」而不是笼统的 runner 崩 |
| 2026-11-20 | 短打断分级与兜底复核 | 18 文件 / **0 错** | **67/0** | 4 条全一致 | 新增 30 条：`LOSS_TRANSIENT` 非通话改压低、`FOCUS_RECHECK` 兜底、`RECHECK_DENIED` 长占用降级暂停、`BECOMING_NOISY` 按设备在位判定、二次打断不重复压低 |
| 2026-11-20 | `3f8546d` | 18 文件 / **0 错** | **37/0** | 4 条全一致 | `check-java-syntax` 已带 `-sourcepath`，长期误报消失 |
| 2026-11-19 | `3f8546d` | — | 37/0（红证 `12/2`） | — | RT-01~RT-05 收口当轮记录 |
| 2026-11-07 | `eceacb5` | 17 文件 | 28/0 | — | 初版审计基线 |

---

## 5. 失败处置与外部阻塞

1. 任一脚本非 0 即记失败，**不以「局部通过」代替**；退出码必须取被测命令本身，不能取管道末端。
2. `javac` 报「类路径缺失」是**无 Android SDK 的预期**，不判红；但**语法错误必须为 0**。
3. `tsc` 失败先区分「rntp 自身类型问题」与「App 侧 typescript 版本差异」，后者记录版本号不擅自升依赖。
4. 真机 L4 全部 `blocked`，本计划只对「静态 + 纯逻辑 + 集成产物」负责。
5. 数值口径：Java 文件数 / 行数、断言条数一律 `find` / `grep -c` / 脚本输出实测，不抄历史文档。

---

## 6. 覆盖只增不减

后续任何版本（含上游合入、焦点策略重构）在本表**只能加行不能删行**：已下线用例标 `~~删除线~~` 并注明原因与替代项，保留编号连续性，避免「删掉一条断言而门禁仍显示全绿」。
