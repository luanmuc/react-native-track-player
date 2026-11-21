#!/usr/bin/env node
/**
 * 续播撤销调用点契约 · check_resume_cancel_callsites
 *
 * 背景：RT-01/RT-04 的缺陷是「postDelayed 的句柄被丢弃，用户暂停无法撤销已排队的续播」。
 * 修复把句柄存进 `pendingResume` 并加了 `cancelPendingResume()`，但**必须在所有会让播放器
 * 状态失真的入口调用它**——少一处，那条路径就退回原缺陷（500ms 窗口内用户的意图被到点的
 * play() 覆盖）。
 *
 * 67 条焦点断言测的是 AudioFocusPolicy 纯逻辑，不经过 MusicManager，所以这条链路只能靠
 * 调用点契约来守。本脚本是**静态契约**：证明「该调的调用点都在」，不证明「调用后真的不执行」
 * （后者需要 Handler 桩，见 STRESS_PLAN §3 的后续项）。
 *
 * 纪律：调用点清单与 FORK_NOTES 的 RT-04 表述必须一一对应；任一处缺失即红。
 */
const fs = require('fs')
const path = require('path')

const root = path.resolve(__dirname, '..')
const MM = path.join(root, 'android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java')
const MOD = path.join(root, 'android/src/main/java/com/guichaguri/trackplayer/module/MusicModule.java')

const red = []
const ok = (msg) => console.log('  ✅ ' + msg)
const bad = (msg) => {
  red.push(msg)
  console.log('  ❌ ' + msg)
}

/**
 * 取方法体：从签名所在行到「当前缩进层级的下一个 `}`」。
 * 比正则贪心更稳，避免把后面的方法也算进来（那样断言会假绿）。
 */
function methodBody(src, signature) {
  const at = src.indexOf(signature)
  if (at < 0) return null
  const bodyStart = src.indexOf('{', at)
  if (bodyStart < 0) return null
  let depth = 0
  for (let i = bodyStart; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') {
      depth--
      if (depth === 0) return src.slice(bodyStart, i + 1)
    }
  }
  return null
}

function expectCall(fileSrc, fileLabel, signature, calls) {
  const body = methodBody(fileSrc, signature)
  if (body === null) {
    bad(`${fileLabel} 找不到方法 ${signature}`)
    return
  }
  for (const c of calls) {
    const re = new RegExp('\\b' + c + '\\s*\\(')
    if (re.test(body)) ok(`${fileLabel} ${signature.split('(')[0].trim()} → ${c}()`)
    else bad(`${fileLabel} 的 ${signature.split('(')[0].trim()} 没有调 ${c}()（该路径会退回 RT-01/RT-04）`)
  }
}

console.log('续播撤销调用点契约（RT-01 / RT-04）')

let mm, mod
try {
  mm = fs.readFileSync(MM, 'utf8')
} catch (e) {
  bad('读不到 MusicManager.java：' + e.message)
}
try {
  mod = fs.readFileSync(MOD, 'utf8')
} catch (e) {
  bad('读不到 MusicModule.java：' + e.message)
}

if (mm && mod) {
  // 1. 事件入口：用户自己的动作一律撤销两条待执行任务
  expectCall(mm, 'MusicManager', 'private void dispatch(AudioFocusPolicy.Event event)', ['cancelPendingResume', 'cancelPendingRecheck'])
  // 2. 换播放器：旧实例上的任务必须撤销，否则会去 play 已销毁的对象
  expectCall(mm, 'MusicManager', 'public void switchPlayback(ExoPlayback playback)', ['cancelPendingResume'])
  // 3. 焦点复位：RT-05 先恢复音量，再撤销两条任务
  expectCall(mm, 'MusicManager', 'public void resetFocus()', ['cancelPendingResume', 'cancelPendingRecheck'])
  // 4. 续播任务自身：到点前要复核「仍持有焦点 / 策略仍认为该恢复 / 播放器仍暂停」
  expectCall(mm, 'MusicManager', 'private void scheduleResume(long delayMs)', ['cancelPendingResume'])
  // 5. 复核任务自身：二次排队前先撤销旧的，避免两条任务同时到点
  expectCall(mm, 'MusicManager', 'private void scheduleRecheck(long delayMs)', ['cancelPendingRecheck'])
  // 6. JS 侧 reset：复位前撤销待续播（否则到点会把已 reset 的播放器拉回播放）
  expectCall(mod, 'MusicModule', 'public void reset(final Promise callback)', ['cancelPendingResume', 'resetFocus'])
  // 7. JS 侧 stop：停止同样要撤销
  const stopBody = methodBody(mod, 'public void stop(final Promise callback)')
  if (stopBody === null) bad('MusicModule 找不到方法 stop()')
  else if (/\bcancelPendingResume\s*\(/.test(stopBody)) ok('MusicModule stop() → cancelPendingResume()')
  else bad('MusicModule 的 stop() 没有调 cancelPendingResume()（停止后仍会被到点的 play() 拉回播放）')
}

console.log('')
if (red.length) {
  console.log(`❌ 续播撤销调用点契约：${red.length} 处缺失`)
  process.exit(1)
}
console.log('✅ 续播撤销调用点契约：9 处调用点齐全')
