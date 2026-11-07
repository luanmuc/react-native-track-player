# 文档语义一致性清单（机器判不了，靠人）

> 定位：本 fork 的文档断言全部落在 `FORK_NOTES.md`（定制清单 + 文件索引）与 `README.md`（fork 说明）。
> `scripts/docs-audit.js` 覆盖可计算项；本清单覆盖行为语义。
> 注意：**本 fork 不启用自带 CI**（见 FORK_NOTES「验证方式」），唯一验证入口是 App 仓的 release CI。

## 一、改动类型 → 必须人工核对的断言

| 改动类型 | 必须核对 | 典型翻车 |
|---|---|---|
| 新增/修改通知栏按钮 | FORK_NOTES 定制清单、文件索引、requestCode 占用、图标张数 | 图标数量与「4 套 × 5 密度」不符 |
| 改播放模式映射 | FORK_NOTES 的 `0=顺序 1=列表循环 2=单曲循环 3=随机` 与 Java `switch(repeatMode)` 是否一致 | 文档写 1=列表，代码 default 走 loop |
| 改音质/子源透传 | FORK_NOTES 通知栏参数段、`TrackMetadata` 拼接说明 | 标题仍挂音质后缀 |
| 改音频焦点/暂停语义 | FORK_NOTES「暂停只能走 pauseByUser」等注意事项 | 新增入口绕过用户暂停标记 |
| 改 App 侧锁定 commit | FORK_NOTES「App 侧当前锁定的 commit」+ App `package.json` 的 sha | 文档 sha 与实际锁定不符（机器已覆盖） |
| 上游合并 | FORK_NOTES「升级上游时注意」8 条逐条重放 | 定制分支被上游重构吃掉 |

## 二、机器已覆盖的项

`scripts/docs-audit.js`（4 条）：模式图标张数、App 侧锁定 sha ↔ 本 fork HEAD 说明、`PLAYER_CONTROL` 声明处数、定制事件/方法名存在性。

## 红绿自检（机制自身改动后必须跑，否则等于没装）

```bash
# 1. 红：把某文档里的一个数字改错 1 位
node scripts/docs-audit.js --dry-run    # 必须报出 🟡/🔴 且指对文件与旧值
# 2. 绿：自动修回真值
node scripts/docs-audit.js
# 3. 确认只改回该数字，没误伤别处
git diff
```

> 只用「跑完打印一堆绿字」当作机制生效，等于自欺——绿字可能是规则根本没命中。
