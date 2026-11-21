package com.guichaguri.trackplayer.service.focus;

/**
 * 待执行任务的「当前有效代次」簿记（纯 JDK，可在无 Android SDK 的 JVM 上穷举验证）。
 *
 * 存在的理由：`Handler.removeCallbacks()` 对已经「离开队列、开始执行」的任务无效；
 * 而用户暂停与到点 `play()` 之间只有 500ms，一旦错过这个窗口，撤销就形同虚设
 * （RT-01 / RT-04）。把「谁才是当前有效的那一条」变成显式代次号，任务到点先问一句，
 * 就不再依赖 Handler 的时序保证，也不再依赖「撤销调用点一处不漏」。
 *
 * 语义约定：
 *  - `armXxx()` 返回新的代次号，**任何更早的代次立即失效**（同一种任务只保留最新一条）。
 *  - `isXxxCurrent(token)` 判断该 token 是否仍是当前有效代次；`NONE` 永远无效。
 *  - `disarmXxx()` 让当前代次失效并返回它（供 `removeCallbacks` 用），已失效时返回 `NONE`。
 *  - 两种任务的代次号共用一条自增序列，便于在日志里区分「第几次排队」。
 */
public final class PendingSlots {

  public static final long NONE = 0L;

  private long seq = 0L;
  private long resume = NONE;
  private long recheck = NONE;

  public long armResume() {
    return resume = ++seq;
  }

  public long resumeToken() {
    return resume;
  }

  public boolean isResumeCurrent(long token) {
    return token != NONE && token == resume;
  }

  public long disarmResume() {
    long token = resume;
    resume = NONE;
    return token;
  }

  public long armRecheck() {
    return recheck = ++seq;
  }

  public long recheckToken() {
    return recheck;
  }

  public boolean isRecheckCurrent(long token) {
    return token != NONE && token == recheck;
  }

  public long disarmRecheck() {
    long token = recheck;
    recheck = NONE;
    return token;
  }

  /** 销毁 / 复位时清空两种任务 */
  public void reset() {
    resume = NONE;
    recheck = NONE;
  }
}
