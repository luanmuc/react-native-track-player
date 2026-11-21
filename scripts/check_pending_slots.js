#!/usr/bin/env node
/**
 * 代次簿记门禁 · check_pending_slots
 *
 * PendingSlots 是 RT-01/RT-04 真正生效的那一半：撤销调用点齐全（静态契约）之后，
 * 还要保证「撤销能让到点的任务失效」。Handler.removeCallbacks 对已开始执行的任务无效，
 * 所以靠的是任务到点先问一句「我还是当前那一条吗」。
 *
 * 纪律与 check_audio_focus_policy 一致：编译失败 / 断言失败 / 找不到工具链一律算红；
 * 必须打印用例数量，「零用例通过」和「真没问题」分不开。
 */
const fs = require('fs')
const path = require('path')
const os = require('os')
const { execFileSync, spawnSync } = require('child_process')

const root = path.resolve(__dirname, '..')
const SRC = path.join(root, 'android/src/main/java/com/guichaguri/trackplayer/service/focus/PendingSlots.java')
const RUNNER = path.join(root, 'scripts/pending-slots-test/PendingSlotsTest.java')

const red = []
const ok = (msg) => console.log('  ✅ ' + msg)
const bad = (msg) => {
  red.push(msg)
  console.log('  ❌ ' + msg)
}

// 静态断言：PendingSlots 不得引入 Android（否则本机编不了，等于门禁失效）
try {
  const src = fs.readFileSync(SRC, 'utf8')
  if (/^\s*import\s+android\./m.test(src)) bad('PendingSlots 引入了 android.* 依赖（本机无法编译，门禁失效）')
  else if (!/public final class PendingSlots/.test(src)) bad('PendingSlots 类声明缺失或不再是 final')
  else ok('PendingSlots 零 Android 依赖、类声明正确')
} catch (e) {
  bad('读不到 PendingSlots：' + e.message)
}

if (!red.length) {
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'psl-'))
  try {
    execFileSync('javac', ['-encoding', 'UTF-8', '-d', outDir, SRC, RUNNER], { stdio: 'pipe' })
    ok('javac 编译通过（PendingSlots + runner）')
  } catch (e) {
    bad('javac 编译失败：' + String(e.stderr || e.message).slice(0, 400))
  }

  if (!red.length) {
    const r = spawnSync('java', ['-cp', outDir, 'PendingSlotsTest'], { encoding: 'utf8' })
    const stdout = r.stdout || ''
    for (const line of stdout.split('\n').filter(Boolean)) console.log(line)
    const m = stdout.match(/结果：(\d+) 通过 \/ (\d+) 失败/)
    if (!m) bad('runner 未输出结果行（判据失效）：' + String(r.stderr || '').slice(0, 400))
    else if (Number(m[2]) !== 0) bad(`代次断言 ${m[1]} 通过 / ${m[2]} 失败`)
    else if (Number(m[1]) < 15) bad(`用例数仅 ${m[1]}，低于约定的 15（有断言被删除）`)
    else ok(`代次断言 ${m[1]} 通过 / 0 失败`)
  }

  try {
    fs.rmSync(outDir, { recursive: true, force: true })
  } catch (e) {
    /* 清理失败不影响结论 */
  }
}

console.log('')
if (red.length) {
  console.log('❌ 代次簿记门禁：不通过')
  process.exit(1)
}
console.log('✅ 代次簿记门禁：全部通过')
