#!/usr/bin/env node
/**
 * Java 语法本地检查（无 Android SDK 也能跑）
 *
 * 背景：云主机只有 JDK 17、无 ANDROID_HOME，`javac` 直接编 `Track.java` 会冒出上百个
 * `package android.* does not exist` / `cannot find symbol`——这些是**类路径缺失**，不是代码错误，
 * 不能当红。真正需要本地拦住的是语法/词法错误（少分号、括号不配对、非法表达式），
 * 这类错误 CI 要等 15 分钟才告诉我们。
 *
 * 做法：对每个 .java 跑 `javac -proc:none`，把 `error:` 行分类：
 *   - 允许（符号解析类，缺 SDK/依赖）：计入 unresolvedCount，不算失败
 *   - 其它（语法类）：算失败并打印原文
 *
 * 用法：node scripts/check-java-syntax.js [源码根目录]
 * 退出码：0 无语法错误 / 1 有语法错误 / 3 javac 不可用
 */
'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const ROOT = process.argv[2] ? path.resolve(process.argv[2]) : path.join(__dirname, '..');
const SRC = path.join(ROOT, 'android/src/main/java');

// 类路径缺失导致的报错，全部允许
const UNRESOLVED = [
  /^package .* does not exist$/,
  /^cannot find symbol$/,
  /^static import only from classes and interfaces$/,
  /^static import of type variable .* not allowed$/,
  /^cannot access .*/,
  /^class file for .* not found$/,
  /^bad class file: .*/,
  /^package .* is not visible$/,
  // 重写 Android/依赖里的方法时，缺父类会报这条；属类路径问题
  /^method does not override or implement a method from a supertype$/,
  // `DataSource.Factory ds = () -> raw;`：目标类型来自 media3，缺 jar 时 javac 无法判定
  // lambda 的目标类型，报「lambda 不在期望位置」；这是类路径问题，不是语法问题
  /^lambda expression not expected here$/,
];

function walk(dir, out = []) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    const st = fs.statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (name.endsWith('.java')) out.push(p);
  }
  return out;
}

function main() {
  const probe = spawnSync('javac', ['-version'], { encoding: 'utf8' });
  if (probe.error || probe.status !== 0) {
    console.log('❌ 无法运行 javac（未安装 JDK）——跳过语法检查并计为未验证');
    process.exit(3);
  }
  if (!fs.existsSync(SRC)) {
    console.log(`❌ 找不到源码目录：${SRC}`);
    process.exit(1);
  }

  const files = walk(SRC).sort();
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'javasyn-'));
  const failures = [];
  let unresolved = 0;

  for (const file of files) {
    const r = spawnSync(
      'javac',
      ['-proc:none', '-nowarn', '-Xmaxerrs', '500', '-d', outDir, file],
      { encoding: 'utf8' }
    );
    // javac 的错误行以文件开头：`X.java:3: error: package android.content does not exist`
    // 只认 'error: ' 开头会漏掉全部错误（假绿），必须按行内位置抠
    const errLines = `${r.stderr || ''}`
      .split('\n')
      .map((l) => {
        // 必须是 `X.java:12: error: ...`——不要求文件前缀会把 javac 打印的源码片段
        // （含 error: 字样的字符串常量行）误当报错
        const m = l.match(/^[^\s].*\.java:\d+: error: (.+)$/);
        return m ? m[1].trim() : null;
      })
      .filter(Boolean);

    for (const msg of errLines) {
      if (UNRESOLVED.some((re) => re.test(msg))) unresolved += 1;
      else failures.push({ file: path.relative(ROOT, file), msg });
    }
  }

  console.log(`扫描文件：${files.length} 个 .java`);
  console.log(`类路径缺失类报错：${unresolved} 条（无 Android SDK 属预期，不判红）`);

  if (failures.length) {
    console.log(`\n🔴 语法错误 ${failures.length} 条：`);
    for (const f of failures.slice(0, 30)) console.log(`   ${f.file}: ${f.msg}`);
    if (failures.length > 30) console.log(`   …其余 ${failures.length - 30} 条省略`);
    process.exit(1);
  }

  console.log('🟢 语法检查通过：0 条语法错误');
}

main();
