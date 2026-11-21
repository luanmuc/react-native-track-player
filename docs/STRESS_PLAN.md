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
| P7 | 集成落地 | 打 App APK + 解包 | 1 次 | APK 内含 `notify_mode_*` 4 态 × 6 密度 + `setNotificationRepeatMode` 等方法 | L3 |

**当前实测（2026-11-20）**：P1 **0 错**（5854 条类路径缺失属预期）；P2 **67/0**；P4 **4 条全一致**；P3/P5/P7 见 §4 记录。

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

| # | 变异 | 期望 | 依据 |
|---|---|---|---|
| M1 | 删掉 `MusicManager.cancelPendingResume()` 的调用（`dispatch()` 的 `USER_PAUSE` 分支） | RT-01/RT-04 断言**变红** | 续播窗口可撤销是本次修复的核心 |
| M2 | 把 `LOSS_TRANSIENT` 的 `userPaused` 改回无条件 `false` | RT-02 核心 2 条**变红**（实测修复前 `12 passed / 2 failed`） | 已验证过的红-绿路径 |
| M3 | `resetFocus()` 去掉 `setVolume(getVolumeBeforeDuck())` | RT-05 的 `volumeBeforeDuck` / 恢复动作断言**变红** | DUCKED 期间 reset 音量卡 25% 的原缺陷 |

**纪律**：每个变异跑完必须 `git checkout -- <file>` 还原，并复跑 P2 确认回到 **67/0**；还原后不复跑等于没做。

---

## 4. 实测记录

| 日期 | 基线 | P1 | P2 | P4 | 备注 |
|---|---|---|---|---|---|
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
