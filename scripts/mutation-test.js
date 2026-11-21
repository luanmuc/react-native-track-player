#!/usr/bin/env node
/**
 * 变异测试 · mutation-test（P6 红证的自动化版本）
 *
 * 目的：证明两组断言不是摆设——把关键判据改坏，断言必须变红。
 * 覆盖两个目标：
 *  - AudioFocusPolicy（67 条assertion）：焦点决策逻辑
 *  - PendingSlots（24 条assertion）：撤销真的能让到点任务失效（RT-01/RT-04 的后半段）
 *
 * 做法：对每条变异体「原地改写源文件 → 跑对应的门禁脚本 → 原文覆写还原」，
 *       要求 ① 编译通过（变异体本身写错不算抓住）② 断言必须变红。
 *
 * 纪律：
 *  - 任何一条变异「改完仍然全绿」都算红：说明该缺陷没有任何断言覆盖。
 *  - 还原用原文覆写而不是 git checkout，避免用户工作区有未提交修改时被误伤。
 *  - 收尾必须复跑两组基线，必须回到 67/0 与 24/0。
 *
 * 运行方式：node scripts/mutation-test.js（需要 javac / java 在 PATH）
 */
const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')

const root = path.resolve(__dirname, '..')
const POLICY = path.join(root, 'android/src/main/java/com/guichaguri/trackplayer/service/focus/AudioFocusPolicy.java')
const SLOTS = path.join(root, 'android/src/main/java/com/guichaguri/trackplayer/service/focus/PendingSlots.java')
const CHECK_POLICY = path.join(root, 'scripts/check_audio_focus_policy.js')
const CHECK_SLOTS = path.join(root, 'scripts/check_pending_slots.js')

const red = []
const ok = (msg) => console.log('  ✅ ' + msg)
const bad = (msg) => {
  red.push(msg)
  console.log('  ❌ ' + msg)
}

/**
 * 每条都要能在对应门禁里被抓到：
 *  - 策略类变异改 AudioFocusPolicy，跑 check_audio_focus_policy
 *  - 代次类变异改 PendingSlots，跑 check_pending_slots
 * old 必须在原文里唯一出现，否则视为变异体失效（源码改了要同步更新）。
 */
const MUTANTS = [
  {
    id: 'M1',
    target: POLICY,
    check: CHECK_POLICY,
    name: '短打断二次压低去掉 !ducked 守卫',
    defect: '连续两次 TRANSIENT 叠乘 DUCK_RATIO，音量掉到 6.25%（G3）',
    old: '          if (!ducked) {\n            volumeBeforeDuck = snap.volume;',
    new: '          if (true) {\n            volumeBeforeDuck = snap.volume;'
  },
  {
    id: 'M2',
    target: POLICY,
    check: CHECK_POLICY,
    name: '兜底复核在 NORMAL 直接短路',
    defect: 'USER_PLAY 清 phase 后音量永久卡 25%（G2）',
    old: '        if (phase == Phase.LOST) break;\n        if (ducked) {\n          out.add(Action.volume(volumeBeforeDuck));\n          ducked = false;\n        }\n        // phase 已被 USER_PLAY',
    new: '        if (phase == Phase.LOST || phase == Phase.NORMAL) break;\n        if (ducked) {\n          out.add(Action.volume(volumeBeforeDuck));\n          ducked = false;\n        }\n        // phase 已被 USER_PLAY'
  },
  {
    id: 'M3',
    target: POLICY,
    check: CHECK_POLICY,
    name: '长占用复核被拒却仍然续播',
    defect: '闹钟/长通话期间以低音量继续出声（G1）',
    old: '        resumePending = resumePending || snap.intentPlaying();\n        phase = Phase.INTERRUPTED;\n        out.add(Action.of(A_PAUSE));',
    new: '        resumePending = resumePending || snap.intentPlaying();\n        phase = Phase.INTERRUPTED;\n        if (resumePending) out.add(Action.resume(RESUME_DELAY_MS)); else out.add(Action.of(A_PAUSE));'
  },
  {
    id: 'M4',
    target: POLICY,
    check: CHECK_POLICY,
    name: '打断欠账覆盖而不是累积',
    defect: '第二次打断抹掉第一次的播放意愿，GAIN 时该续播却不续播（RT-02 反例）',
    old: '        resumePending = resumePending || snap.intentPlaying();\n        // RT-02：userPaused',
    new: '        resumePending = snap.intentPlaying();\n        // RT-02：userPaused'
  },
  {
    id: 'M5',
    target: SLOTS,
    check: CHECK_SLOTS,
    name: '撤销失效：isResumeCurrent 恒真',
    defect: '用户暂停后到点的 play() 照样执行（RT-01/RT-04 退回原缺陷）',
    old: '    return token != NONE && token == resume;',
    new: '    return true;'
  },
  {
    id: 'M6',
    target: SLOTS,
    check: CHECK_SLOTS,
    name: '二次排队代次不递增',
    defect: '新旧任务拿到同一个代次号，撤销旧的会连带把新的也废掉',
    old: '    return resume = ++seq;',
    new: '    return resume = seq;'
  },
  {
    id: 'M7',
    target: SLOTS,
    check: CHECK_SLOTS,
    name: 'disarm 不清代次',
    defect: '撤销后旧任务仍被认为有效，removeCallbacks 失败时会被执行',
    old: '    resume = NONE;\n    return token;',
    new: '    return token;'
  }
]

function runCheck(checkPath) {
  const r = spawnSync('node', [checkPath], { cwd: root, encoding: 'utf8' })
  const out = (r.stdout || '') + (r.stderr || '')
  const m = out.match(/结果：(\d+) 通过 \/ (\d+) 失败/)
  return { rc: (r.status === null ? 1 : r.status), pass: m ? Number(m[1]) : -1, fail: m ? Number(m[2]) : -1, out }
}

console.log('变异测试 · AudioFocusPolicy + PendingSlots（证明断言不是摆设）')

// 两组基线都必须全绿，否则变异测试没有意义
const groups = [
  { check: CHECK_POLICY, label: '焦点策略', expectAtLeast: 25 },
  { check: CHECK_SLOTS, label: '代次簿记', expectAtLeast: 15 }
]
const baseline = {}
let baselineOk = true
for (const g of groups) {
  const r = runCheck(g.check)
  if (r.rc !== 0 || r.fail !== 0 || r.pass < g.expectAtLeast) {
    console.log(`  ❌ ${g.label}基线不是全绿（${r.pass} 通过 / ${r.fail} 失败），变异测试无意义`)
    baselineOk = false
  } else {
    baseline[g.check] = r.pass
    ok(`${g.label}基线 ${r.pass} 通过 / 0 失败`)
  }
}
if (!baselineOk) process.exit(1)

const originals = {}
for (const p of [POLICY, SLOTS]) {
  try {
    originals[p] = fs.readFileSync(p, 'utf8')
  } catch (e) {
    console.log('  ❌ 读不到源文件：' + p + '（' + e.message + '）')
    process.exit(1)
  }
}

let survived = 0
for (const mu of MUTANTS) {
  const src = originals[mu.target]
  const hits = src.split(mu.old).length - 1
  if (hits !== 1) {
    bad(`${mu.id} ${mu.name}：变异点匹配 ${hits} 次（要求恰好 1 次，源码已改则变异体要同步更新）`)
    continue
  }
  try {
    fs.writeFileSync(mu.target, src.replace(mu.old, mu.new), 'utf8')
    const r = runCheck(mu.check)
    if (!r.out.includes('编译通过')) {
      bad(`${mu.id} ${mu.name}：变异体编译失败（变异本身无效，不是断言抓住的）`)
    } else if (r.rc === 0) {
      survived++
      bad(`${mu.id} ${mu.name}：改坏后仍然全绿 → 该缺陷「${mu.defect}」没有任何断言覆盖`)
    } else {
      ok(`${mu.id} ${mu.name}：${r.pass} 通过 / ${r.fail} 失败（缺陷「${mu.defect}」被抓）`)
    }
  } finally {
    fs.writeFileSync(mu.target, src, 'utf8')
  }
}

// 还原必须彻底：两组基线都要回到原位
for (const g of groups) {
  const restored = runCheck(g.check)
  if (restored.rc !== 0 || restored.fail !== 0 || restored.pass !== baseline[g.check]) {
    bad(`还原后${g.label}基线异常（${restored.pass} 通过 / ${restored.fail} 失败，期望 ${baseline[g.check]}/0）`)
  } else {
    ok(`还原后${g.label}基线回到 ${baseline[g.check]}/0`)
  }
}

console.log('')
if (red.length) {
  console.log(`❌ 变异测试不通过：${red.length} 项`)
  process.exit(1)
}
console.log(`✅ 变异测试：${MUTANTS.length} 条变异全部被抓，0 条存活`)
