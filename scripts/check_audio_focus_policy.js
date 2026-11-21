#!/usr/bin/env node
/**
 * 音频焦点策略门禁 · check_audio_focus_policy
 *
 * 背景：AudioFocusPolicy 是把焦点恢复决策从 Android 里剥出来的纯逻辑类，
 * 目的就是能在没有 SDK、没有真机、没有 JUnit 的环境里穷举验证。
 * 本脚本用 javac 编译「策略类 + 场景 runner」，再用 java 跑断言。
 *
 * 纪律（与 truth gates 一致）：
 *  - 编译失败 / 断言失败 / 找不到工具链 一律算红（跳过 = 判据失效）。
 *  - 必须打印用例数量，否则「零用例通过」和「真没问题」分不开。
 *  - 额外做静态断言：策略类不得 import android.*，否则本机就编译不了，等于门禁失效。
 */
const fs = require('fs')
const path = require('path')
const os = require('os')
const { execFileSync, spawnSync } = require('child_process')

const root = path.resolve(__dirname, '..')
const POLICY = path.join(root, 'android/src/main/java/com/guichaguri/trackplayer/service/focus/AudioFocusPolicy.java')
const RUNNER = path.join(root, 'scripts/audio-focus-policy-test/AudioFocusPolicyTest.java')

const red = []
const ok = (msg) => console.log('  ✅ ' + msg)
const bad = (msg) => { red.push(msg); console.log('  ❌ ' + msg) }

// 静态断言 1：策略类零 Android 依赖
try {
  const src = fs.readFileSync(POLICY, 'utf8')
  if (/^\s*import\s+android\./m.test(src)) bad('AudioFocusPolicy 引入了 android.* 依赖（本机无法编译，门禁失效）')
  else ok('AudioFocusPolicy 零 Android 依赖（可脱离 SDK 编译）')

  // 静态断言 2：不得出现裸数字比较（与 G2 同一口径）
  if (/\b(?:getState\(\)|state)\s*(?:==|!=)\s*\d/.test(src)) bad('AudioFocusPolicy 出现裸数字状态比较')
  else ok('AudioFocusPolicy 无裸数字状态比较')
} catch (e) {
  bad('读不到策略类：' + e.message)
}

// 编译 + 运行
if (!red.length) {
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'afp-'))
  try {
    execFileSync('javac', ['-encoding', 'UTF-8', '-d', outDir, POLICY, RUNNER], { stdio: 'pipe' })
    ok('javac 编译通过（策略 + case runner）')
  } catch (e) {
    bad('javac 编译失败：' + String(e.stderr || e.message).slice(0, 400))
  }

  if (!red.length) {
    // 用 spawnSync 而不是 execFileSync：断言红时 java 会以非 0 退出，execFileSync 抛异常会把
    // "40 通过 / 1 失败" 变成笼统的"runner 执行失败"，CI 上定位不到断在哪。
    const r = spawnSync('java', ['-cp', outDir, 'AudioFocusPolicyTest'], { encoding: 'utf8' })
    const stdout = r.stdout || ''
    for (const line of stdout.split('\n').filter(Boolean)) console.log(line)
    const m = stdout.match(/结果：(\d+) 通过 \/ (\d+) 失败/)
    if (!m) bad('runner 未输出结果行（判据失效）：' + String(r.stderr || '').slice(0, 400))
    else if (Number(m[2]) !== 0) bad(`场景断言 ${m[1]} 通过 / ${m[2]} 失败`)
    else if (Number(m[1]) < 25) bad(`用例数仅 ${m[1]}，低于约定的 25（有断言被删除）`)
    else ok(`场景断言 ${m[1]} 通过 / 0 失败`)
  }

  try { fs.rmSync(outDir, { recursive: true, force: true }) } catch (e) { /* 临时目录清理失败不影响结论 */ }
}

console.log('')
if (red.length) {
  console.log('❌ 音频焦点策略门禁：' + red.length + ' 条不通过')
  process.exit(1)
}
console.log('✅ 音频焦点策略门禁：全部通过')
