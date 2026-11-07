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
  const sib = path.resolve(ROOT, '..', 'lx-music-mobile', 'package.json');
  if (!fs.existsSync(sib)) { report.push('⚪ 规则2 跳过：本地无 ../lx-music-mobile'); return; }
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

// ── 规则 4：定制符号存在性（FORK_NOTES 文件索引逐个核）──────────────────
function ruleSymbols() {
  const checks = [
    ['setNotificationRepeatMode', ['android/src/main/java/com/guichaguri/trackplayer/module/MusicModule.java', 'src/trackPlayer.ts']],
    ['remote-cycle-repeat', ['android/src/main/java/com/guichaguri/trackplayer/module/MusicEvents.java', 'src/interfaces.ts']],
    ['remote-toggle-desktop-lyric', ['android/src/main/java/com/guichaguri/trackplayer/module/MusicEvents.java', 'src/interfaces.ts']],
    ['setLyricButtonActive', ['android/src/main/java/com/guichaguri/trackplayer/service/metadata/MetadataManager.java']],
  ];
  let bad = 0;
  for (const [sym, files] of checks) {
    const missing = files.filter(f => !fs.existsSync(path.join(ROOT, f)) || !read(f).includes(sym));
    if (missing.length) { bad++; report.push(`🔴 定制符号 ${sym} 缺失于 ${missing.join('、')}`); }
  }
  if (!bad) report.push(`🟢 定制符号齐全：${checks.length} 个（FORK_NOTES 文件索引核对）`);
}

ruleIconCount();
ruleLockedSha();
rulePermission();
ruleSymbols();

console.log(`\n[L4 文档巡检·fork] ${DRY ? 'DRY-RUN（不修改）' : '自动修复'} — ${new Date().toISOString().slice(0, 10)}\n`);
for (const r of report) console.log(r);
console.log(`\n汇总：${DRY ? '待处理' : '已修复'} ${fixed} 项 · 报告 ${report.length} 项`);
console.log('语义类断言见 docs/DOC_SEMANTIC_CHECKLIST.md（机器判不了，发布前人工过）');
