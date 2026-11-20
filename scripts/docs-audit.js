#!/usr/bin/env node
/**
 * docs-audit.js — 本 fork 的文档一致性巡检（L4 兜底）
 *
 * 本 fork 刻意不启用自带 CI（见 FORK_NOTES「验证方式」），因此本脚本是唯一的自动闸门：
 * 由 git hook（scripts/hooks/prepare-commit-msg）在每次提交时自动调用。
 *
 * 用法：
 *   node scripts/docs-audit.js            # 自动修复低风险项
 *   node scripts/docs-audit.js --dry-run  # 只报告
 *
 * 覆盖：能算出唯一真值的断言（张数 / sha / 声明处数 / 符号存在性）。
 * 覆盖不到：行为语义与说明文字 → 见 docs/DOC_SEMANTIC_CHECKLIST.md。
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const DRY = process.argv.includes('--dry-run');
const report = [];
let fixed = 0;

const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8');
const write = (p, s) => fs.writeFileSync(path.join(ROOT, p), s);

function align(p, re, build, label) {
  const s = read(p);
  const m = s.match(re);
  if (!m) { report.push(`⚠️ [${p}] ${label} 未命中`); return; }
  const want = build(m);
  if (m[0] === want) { report.push(`🟢 [${p}] ${label}：${m[0].slice(0, 72)}`); return; }
  if (DRY) { report.push(`🟡 [${p}] ${label} ${m[0]} → ${want}`); return; }
  write(p, s.replace(m[0], want));
  fixed++;
  report.push(`✅ [${p}] ${label} ${m[0]} → ${want}`);
}

// ── 规则 1：模式图标张数 ↔ FORK_NOTES「4 套 × 5 密度」──────────────────
function ruleIconCount() {
  const n = execSync(`find android/src/main/res -name 'notify_mode_*.png' | wc -l`, { encoding: 'utf8', cwd: ROOT }).trim();
  const note = read('FORK_NOTES.md');
  const m = note.match(/(\d+) 套 × (\d+) 密度/);
  if (!m) { report.push('⚠️ 规则1：FORK_NOTES 未找到「N 套 × M 密度」'); return; }
  const declared = Number(m[1]) * Number(m[2]);
  if (declared !== Number(n)) report.push(`🔴 [FORK_NOTES.md] 声明 ${m[1]}×${m[2]}=${declared} 张，实际 ${n} 张`);
  else report.push(`🟢 模式图标张数一致：${n} 张（${m[1]} 套 × ${m[2]} 密度）`);
}

// ── 规则 2：App 侧锁定 sha ↔ App 仓 package.json（跨仓）────────────────
function ruleLockedSha() {
  // 候选顺序：环境变量 → 真实 App 仓（repo-clones/mobile-app）→ 旁路 clone（../lx-music-mobile）
  // 2026-11-20：此前只认 ../lx-music-mobile，那是 11-18 的旧 clone（锁 12049cd），
  // 会把 FORK_NOTES 里已升到 3f8546d 的正确值又改回旧 sha。
  const sib = [
    process.env.LX_APP_ROOT && path.join(process.env.LX_APP_ROOT, 'package.json'),
    path.resolve(ROOT, '..', '..', 'repo-clones', 'mobile-app', 'package.json'),
    path.resolve(ROOT, '..', 'lx-music-mobile', 'package.json'),
  ].filter(Boolean).find((p) => fs.existsSync(p));
  if (!sib) { report.push('⚪ 规则2 跳过：未找到 App 仓 package.json'); return; }
  const m = JSON.parse(fs.readFileSync(sib, 'utf8')).dependencies['react-native-track-player'].match(/#([0-9a-f]{7,40})/);
  if (!m) { report.push('🔴 App package.json 未锁定 sha'); return; }
  const sha7 = m[1].slice(0, 7);
  align('FORK_NOTES.md', /当前锁定的 commit：`[0-9a-f]{7,40}`/, () => `当前锁定的 commit：\`${sha7}\``, 'App 锁定 sha');
}

// ── 规则 3：PLAYER_CONTROL 三处声明（permission / uses-permission / service）──
function rulePermission() {
  const man = read('android/src/main/AndroidManifest.xml');
  const hasPerm = /<permission[^>]*PLAYER_CONTROL/.test(man);
  const hasUse = /<uses-permission[^>]*PLAYER_CONTROL/.test(man);
  const hasSvc = /android:permission="com\.guichaguri\.trackplayer\.permission\.PLAYER_CONTROL"/.test(man);
  const cnt = [hasPerm, hasUse, hasSvc].filter(Boolean).length;
  if (cnt === 3) report.push('🟢 PLAYER_CONTROL 三处声明齐全（permission / uses-permission / service 挂载）');
  else report.push(`🔴 PLAYER_CONTROL 声明缺项（${cnt}/3：permission=${hasPerm} uses=${hasUse} service=${hasSvc}）——服务会对外裸奔或本 App 都调不进`);
}

// ── 规则 4：定制符号存在性（表格驱动，覆盖 FORK_NOTES 的主要定制面）──────────
// 原实现只核 4 个符号（覆盖率 ~15%），等于「只守住了最早那批定制」。
// 这里把可机器判定的定制符号按「符号 → 允许出现的文件」列成表；新增定制必须加行，
// 否则 FORK_NOTES 与代码会静默漂移（G19）。
const SYMBOLS = [
  ['setNotificationRepeatMode', ['android/src/main/java/com/guichaguri/trackplayer/module/MusicModule.java', 'src/trackPlayer.ts']],
  ['remote-cycle-repeat', ['android/src/main/java/com/guichaguri/trackplayer/module/MusicEvents.java', 'src/interfaces.ts']],
  ['remote-toggle-desktop-lyric', ['android/src/main/java/com/guichaguri/trackplayer/module/MusicEvents.java', 'src/interfaces.ts']],
  ['setLyricButtonActive', ['android/src/main/java/com/guichaguri/trackplayer/service/metadata/MetadataManager.java']],
  ['lyricButtonActive', ['android/src/main/java/com/guichaguri/trackplayer/service/metadata/MetadataManager.java']],
  // B 方案（2026-11-18）：焦点恢复决策迁到纯逻辑状态机，旧的 userPausedDuringInterrupt 字段
  // 改由 AudioFocusPolicy 的 userPaused 状态承担，表格随之演进（不是删掉守卫，而是换守卫对象）。
  ['AudioFocusPolicy', ['android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java', 'android/src/main/java/com/guichaguri/trackplayer/service/focus/AudioFocusPolicy.java']],
  ['resumePending', ['android/src/main/java/com/guichaguri/trackplayer/service/focus/AudioFocusPolicy.java']],
  ['pauseByUser', ['android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java', 'android/src/main/java/com/guichaguri/trackplayer/service/MusicBinder.java']],
  ['setUserVolume', ['android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java', 'android/src/main/java/com/guichaguri/trackplayer/service/MusicBinder.java']],
  // 2026-11-20：JS 的 setVolume 属程序路径（App 用它做逐帧淡入），改走 setProgrammaticVolume，
  // 不再触发 USER_VOLUME（否则每帧都会清掉焦点策略的 duck 标记）
  ['setProgrammaticVolume', ['android/src/main/java/com/guichaguri/trackplayer/module/MusicModule.java', 'android/src/main/java/com/guichaguri/trackplayer/service/MusicBinder.java']],
  ['setVolumeInternal', ['android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java', 'android/src/main/java/com/guichaguri/trackplayer/service/MusicBinder.java']],
  ['autoResumeAfterInterrupt', ['android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java', 'src/interfaces.ts']],
  ['hasAudioFocus', ['android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java']],
  ['QUEUE_ID_SEQ', ['android/src/main/java/com/guichaguri/trackplayer/service/models/Track.java']],
  ['subSource', ['android/src/main/java/com/guichaguri/trackplayer/service/models/TrackMetadata.java', 'src/interfaces.ts']],
  ['displayArtist', ['android/src/main/java/com/guichaguri/trackplayer/service/models/TrackMetadata.java']],
  ['ACTION_TOGGLE_DESKTOP_LYRIC', ['android/src/main/java/com/guichaguri/trackplayer/service/MusicService.java', 'android/src/main/java/com/guichaguri/trackplayer/service/metadata/MetadataManager.java']],
  ['ACTION_CYCLE_REPEAT', ['android/src/main/java/com/guichaguri/trackplayer/service/MusicService.java']],
  ['ACTION_AUDIO_BECOMING_NOISY', ['android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java']],
]

function ruleSymbols() {
  let bad = 0
  for (const [sym, files] of SYMBOLS) {
    const missing = files.filter(f => !fs.existsSync(path.join(ROOT, f)) || !read(f).includes(sym))
    if (missing.length) { bad++; report.push(`🔴 定制符号 ${sym} 缺失于 ${missing.join('、')}`) }
  }
  if (!bad) report.push(`🟢 定制符号齐全：${SYMBOLS.length} 个（表格驱动，${SYMBOLS.length} 个符号 × 允许文件清单）`)
}

ruleIconCount();
ruleLockedSha();
rulePermission();
ruleSymbols();

console.log(`\n[L4 文档巡检·fork] ${DRY ? 'DRY-RUN（不修改）' : '自动修复'} — ${new Date().toISOString().slice(0, 10)}\n`);
for (const r of report) console.log(r);
console.log(`\n汇总：${DRY ? '待处理' : '已修复'} ${fixed} 项 · 报告 ${report.length} 项`);
console.log('语义类断言见 docs/DOC_SEMANTIC_CHECKLIST.md（机器判不了，发布前人工过）');
