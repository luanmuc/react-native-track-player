#!/usr/bin/env node
/**
 * 变异测试 · mutation-test（P6 红证的自动化版本）
 *
 * 目的：证明 67 条焦点断言不是摆设——把策略里某个关键判据改坏，断言必须变红。
 * 做法：对每条变异体「原地改写 AudioFocusPolicy.java → 跑 check_audio_focus_policy → 还原」，
 *       要求 ① 编译通过（编译失败说明变异体写错了，不是断言抓住了）② 断言必须变红。
 *
 * 纪律（与 STRESS_PLAN 一致）：
 *  - 任何一条变异「改完仍然全绿」都算红：说明这条判据没有任何断言覆盖。
 *  - 还原用原文覆写而不是 git checkout，避免用户工作区有未提交修改时被误伤。
 *  - 收尾必须复跑一次基线，必须回到 67/0；还原不彻底等于变异测试本身就是污染源。
 *
 * 运行方式：node scripts/mutation-test.js（需要 javac / java 在 PATH）
 */
const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')

const root = path.resolve(__dirname, '..')
const POLICY = path.join(root, 'android/src/main/java/com/guichaguri/trackplayer/service/focus/AudioFocusPolicy.java')
const CHECK = path.join(root, 'scripts/check_audio_focus_policy.js')

const red = []
const ok = (msg) => console.log('  ✅ ' + msg)
const bad = (msg) => {
  red.push(msg)
  console.log('  ❌ ' + msg)
}

/**
 * 变异体：old 必须在原文里唯一出现（find 一次、替换一次），否则视为变异体失效。
 * 每条都在 AudioFocusPolicy 内部——67 条断言测的就是这个纯逻辑类，改别处（MusicManager）
 * 不会被纯策略用例抓到，那种「变异」只能骗自己。
 */
const MUTANTS = [
  {
    id: 'M1',
    name: '短打断二次压低去掉 !ducked 守卫',
    defect: '连续两次 TRANSIENT 叠乘 DUCK_RATIO，音量掉到 6.25%（G3）',
    old: '          if (!ducked) {\n            volumeBeforeDuck = snap.volume;',
    new: '          if (true) {\n            volumeBeforeDuck = snap.volume;'
  },
  {
    id: 'M2',
    name: '兜底复核在 NORMAL 直接短路',
    defect: 'USER_PLAY 清 phase 后音量永久卡 25%（G2）',
    old: '        if (phase == Phase.LOST) break;\n        if (ducked) {\n          out.add(Action.volume(volumeBeforeDuck));\n          ducked = false;\n        }\n        // phase 已被 USER_PLAY',
    new: '        if (phase == Phase.LOST || phase == Phase.NORMAL) break;\n        if (ducked) {\n          out.add(Action.volume(volumeBeforeDuck));\n          ducked = false;\n        }\n        // phase 已被 USER_PLAY'
  },
  {
    id: 'M3',
    name: '长占用复核被拒却仍然续播',
    defect: '闹钟/长通话期间以低音量继续出声（G1）',
    old: '        resumePending = resumePending || snap.intentPlaying();\n        phase = Phase.INTERRUPTED;\n        out.add(Action.of(A_PAUSE));',
    new: '        resumePending = resumePending || snap.intentPlaying();\n        phase = Phase.INTERRUPTED;\n        if (resumePending) out.add(Action.resume(RESUME_DELAY_MS)); else out.add(Action.of(A_PAUSE));'
  },
  {
    id: 'M4',
    name: '打断欠账覆盖而不是累积',
    defect: '第二次打断抹掉第一次的播放意愿，GAIN 时该续播却不续播（RT-02 反例）',
    old: '        resumePending = resumePending || snap.intentPlaying();\n        // RT-02：userPaused',
    new: '        resumePending = snap.intentPlaying();\n        // RT-02：userPaused'
  }
]

function runCheck() {
  const r = spawnSync('node', [CHECK], { cwd: root, encoding: 'utf8' })
  const out = (r.stdout || '') + (r.stderr || '')
  const m = out.match(/结果：(\d+) 通过 \/ (\d+) 失败/)
  return { rc: (r.status === null ? 1 : r.status), pass: m ? Number(m[1]) : -1, fail: m ? Number(m[2]) : -1, out }
}

console.log('变异测试 · AudioFocusPolicy（证明 67 条断言不是摆设）')

const baseline = runCheck()
if (baseline.rc !== 0 || baseline.fail !== 0) {
  console.log(`  ❌ 基线不是全绿（${baseline.pass} 通过 / ${baseline.fail} 失败），变异测试无意义`)
  process.exit(1)
}
const total = baseline.pass
ok(`基线 ${total} 通过 / 0 失败`)

let src
try {
  src = fs.readFileSync(POLICY, 'utf8')
} catch (e) {
  console.log('  ❌ 读不到策略类：' + e.message)
  process.exit(1)
}

let survived = 0
for (const mu of MUTANTS) {
  const hits = src.split(mu.old).length - 1
  if (hits !== 1) {
    bad(`${mu.id} ${mu.name}：变异点匹配 ${hits} 次（要求恰好 1 次，源码已改则变异体要同步更新）`)
    continue
  }
  try {
    fs.writeFileSync(POLICY, src.replace(mu.old, mu.new), 'utf8')
    const r = runCheck()
    if (!r.out.includes('javac 编译通过')) {
      bad(`${mu.id} ${mu.name}：变异体编译失败（变异本身无效，不是断言抓住的）`)
    } else if (r.rc === 0) {
      survived++
      bad(`${mu.id} ${mu.name}：改坏后仍然 ${r.pass}/0 全绿 → 该缺陷「${mu.defect}」没有任何断言覆盖`)
    } else {
      ok(`${mu.id} ${mu.name}：${r.pass} 通过 / ${r.fail} 失败（缺陷「${mu.defect}」被抓）`)
    }
  } finally {
    // 无论断言结果如何都必须还原；用原文覆写，不动用户工作区里的其他改动
    fs.writeFileSync(POLICY, src, 'utf8')
  }
}

// 还原必须彻底：复跑基线，必须回到 67/0
const restored = runCheck()
if (restored.rc !== 0 || restored.pass !== total) {
  bad(`还原后基线异常（${restored.pass} 通过 / ${restored.fail} 失败，期望 ${total}/0）`)
} else {
  ok(`还原后基线回到 ${total}/0`)
}

console.log('')
if (red.length) {
  console.log(`❌ 变异测试不通过：${red.length} 项`)
  process.exit(1)
}
console.log(`✅ 变异测试：${MUTANTS.length} 条变异全部被抓，0 条存活`)
