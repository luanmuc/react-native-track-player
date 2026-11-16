#!/usr/bin/env node
/**
 * 口径门禁（rntp 侧）· check_truth_gates
 * 背景：本仓是播放器底座（Java），此前只剩 check-java-syntax（只能拦语法错）与 docs-audit，
 * Java 侧的语义回归（queueId 用时间戳、状态裸数字比较、AGP 与 namespace 不匹配、
 * 裸延时数字）没有任何守卫。2026-11-16 按交接单补回这四条。
 *
 * 纪律（与 App 侧同）：
 *  - 跳过 = 判据失效，必须算红（读不到文件不等于没问题）。
 *  - 每条通过文案必须带数量，否则「零命中」和「真没问题」分不开。
 *  - 末尾打印 📊 本次共执行 N 条门禁，防止某条被静默删除后「全绿」照样全绿。
 */
const fs = require('fs')
const path = require('path')

const root = path.resolve(__dirname, '..')
const JAVA_DIR = path.join(root, 'android/src/main/java')
const GRADLE = path.join(root, 'android/build.gradle')

let red = 0
const P = (id, msg) => console.log('  ✅ [' + id + '] ' + msg)
const F = (id, msg) => { red++; console.log('  ❌ [' + id + '] ' + msg) }
const S = (id, msg) => { red++; console.log('  ❌ [' + id + '] 无法验证：' + msg) }

const walk = (d, out = []) => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, e.name)
    if (e.isDirectory()) walk(full, out)
    else if (e.name.endsWith('.java')) out.push(full)
  }
  return out
}

let files = []
try {
  files = walk(JAVA_DIR)
} catch (e) {
  S('G0', '读不到 android/src/main/java：' + e.message)
}
if (!files.length && red === 0) F('G0', '没有任何 .java 文件，判据失效')
const texts = files.map(f => ({ rel: path.relative(root, f), txt: fs.readFileSync(f, 'utf8') }))

// G1 queueId 必须单调序列，不能拿时间戳
try {
  const bad = texts.filter(({ txt }) => /queueId[\s\S]{0,60}currentTimeMillis/.test(txt) || /currentTimeMillis\(\)[\s\S]{0,40}queueId/.test(txt))
  const seq = texts.filter(({ txt }) => /QUEUE_ID_SEQ|AtomicLong/.test(txt))
  if (bad.length) F('G1', `${bad.map(b => b.rel).join('、')} 用时间戳生成 queueId（同毫秒会撞 id）`)
  else if (!seq.length) F('G1', '找不到 queueId 的单调序列实现（判据失效）')
  else P('G1', `${texts.length} 个 Java 文件：queueId 走单调序列（${seq.length} 处 AtomicLong），无时间戳生成`)
} catch (e) { S('G1', e.message) }

// G2 播放状态禁止与裸数字比较（PlaybackStateCompat 与 media3 Player 的状态值同名不同义）
try {
  const hits = []
  for (const { rel, txt } of texts) {
    for (const m of txt.matchAll(/(?:getState\(\)|playbackState|state)\s*(?:==|!=)\s*(\d+)/g)) hits.push(rel + ':' + m[1])
    for (const m of txt.matchAll(/(\d+)\s*(?:==|!=)\s*(?:getState\(\)|playbackState)/g)) hits.push(rel + ':' + m[1])
  }
  if (hits.length) F('G2', `${hits.join('、')} 直接比裸数字（必须用 PlaybackStateCompat.* 常量）`)
  else P('G2', `${texts.length} 个 Java 文件：播放状态比较全部走常量，无裸数字`)
} catch (e) { S('G2', e.message) }

// G3 AGP 与 namespace / compileSdk 自洽
try {
  const g = fs.readFileSync(GRADLE, 'utf8')
  const agp = ((g.match(/com\.android\.tools\.build:gradle:([\d.]+)/) || [])[1] || '')
  const cs = Number((g.match(/compileSdkVersion\s*=\s*(\d+)/) || [])[1])
  const usesNamespace = texts.some(({ txt }) => /namespace\s*=/.test(txt)) || /namespace/.test(fs.readFileSync(path.join(root, 'android/build.gradle'), 'utf8'))
  const [agpMajor, agpMinor] = agp.split('.').map(Number)
  const nsOk = agpMajor >= 7 && (agpMajor > 7 || agpMinor >= 3)
  if (!agp) F('G3', 'android/build.gradle 未声明 AGP 版本')
  else if (usesNamespace && !nsOk) F('G3', `AGP ${agp} 不支持 namespace（需 ≥ 7.3）`)
  else P('G3', `AGP ${agp} / compileSdk ${cs || '未声明'} 自洽${usesNamespace ? '（namespace 可用）' : ''}`)
} catch (e) { S('G3', e.message) }

// G4 postDelayed 延时禁止裸数字（必须具名常量，改值时才 grep 得到）
try {
  const hits = []
  for (const { rel, txt } of texts) {
    for (const m of txt.matchAll(/postDelayed\(/g)) {
      const tail = txt.slice(m.index, m.index + 2000)
      const delay = /\s\}\s*,\s*(\d[\d_]*)\s*\)/.exec(tail) || /,[^,()]{0,80},\s*(\d[\d_]*)\s*\)/.exec(tail) || /\}\s*,\s*(\d[\d_]*)\s*\)/.exec(tail)
      if (delay) hits.push(rel + ':' + delay[1])
    }
  }
  if (hits.length) F('G4', `${hits.join('、')} 用裸数字做延时（抽成常量，例如 INTERRUPT_RESUME_DELAY_MS）`)
  else P('G4', `${texts.length} 个 Java 文件：postDelayed 延时均用具名常量`)
} catch (e) { S('G4', e.message) }

const count = 4
console.log(red
  ? `\n❌ 口径门禁：${red} 条不一致（📊 本次共执行 ${count} 条门禁）`
  : `\n✅ 口径门禁：全部一致（📊 本次共执行 ${count} 条门禁）`)
process.exit(red ? 1 : 0)
