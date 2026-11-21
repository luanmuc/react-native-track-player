import com.guichaguri.trackplayer.service.focus.AudioFocusPolicy;
import com.guichaguri.trackplayer.service.focus.AudioFocusPolicy.Action;
import com.guichaguri.trackplayer.service.focus.AudioFocusPolicy.Event;
import com.guichaguri.trackplayer.service.focus.AudioFocusPolicy.Snapshot;

import java.util.List;

/**
 * AudioFocusPolicy 的场景断言（纯 JDK，无需 Android SDK / JUnit）。
 * 运行方式：node scripts/check_audio_focus_policy.js（内部 javac + java）。
 *
 * 每个用例都对应一个真实缺陷或一条主路径，断言"动作序列里有什么/没有什么"。
 */
public final class AudioFocusPolicyTest {

  private static int pass = 0;
  private static int fail = 0;

  private static final Snapshot PLAYING = new Snapshot(AudioFocusPolicy.PS_PLAYING, true, true, 1.0f);
  private static final Snapshot PLAYING_AT_60 = new Snapshot(AudioFocusPolicy.PS_PLAYING, true, true, 0.6f);
  private static final Snapshot BUFFERING = new Snapshot(AudioFocusPolicy.PS_BUFFERING, true, false, 1.0f);
  private static final Snapshot PAUSED = new Snapshot(AudioFocusPolicy.PS_PAUSED, false, false, 1.0f);
  /** 非通话场景（微信通知音、应用提示音）：不该让整首歌停下来 */
  private static final Snapshot PLAYING_IDLE = new Snapshot(AudioFocusPolicy.PS_PLAYING, true, true, 0.6f, false, false);
  private static final Snapshot BUFFERING_IDLE = new Snapshot(AudioFocusPolicy.PS_BUFFERING, true, false, 1.0f, false, false);
  /** 蓝牙耳机仍在位（BECOMING_NOISY 属路由抖动） */
  private static final Snapshot PLAYING_BT = new Snapshot(AudioFocusPolicy.PS_PLAYING, true, true, 0.6f, false, true);

  public static void main(String[] args) {
    System.out.println("AudioFocusPolicy 场景断言");
    try {

    // 1. 主路径：播放中来电 → 挂断应续播
    AudioFocusPolicy p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    List<Action> a = p.handle(Event.GAIN, PLAYING);
    expect("主路径：来电后挂断续播", has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));
    expect("主路径：续播延时 500ms", delayOf(a) == AudioFocusPolicy.RESUME_DELAY_MS);

    // 2. R3 缓冲中被打断也必须续播（旧实现用 == STATE_PLAYING 会漏掉 BUFFERING）
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, BUFFERING);
    a = p.handle(Event.GAIN, PAUSED);
    expect("R3：缓冲中被打断后仍续播", has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 3. R2 连续两次打断不能被覆盖
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    p.handle(Event.LOSS_TRANSIENT, PAUSED);
    a = p.handle(Event.GAIN, PAUSED);
    expect("R2：连续两次打断后仍续播", has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 4. 用户打断期间手动暂停 → 不得续播
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    p.handle(Event.USER_PAUSE, PAUSED);
    a = p.handle(Event.GAIN, PAUSED);
    expect("用户暂停：挂断后不续播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 5. 用户手动暂停后自己又播 → 欠账清零，GAIN 不再插手
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    p.handle(Event.USER_PAUSE, PAUSED);
    p.handle(Event.USER_PLAY, PLAYING);
    a = p.handle(Event.GAIN, PLAYING);
    expect("用户接管：GAIN 不再自动播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 5b. RT-02 回归：连续打断不得抹掉用户暂停意愿
    //     打断 A（来电）→ 用户在通话中点暂停 → 打断 B（导航播报）→ 挂断
    //     修复前 LOSS_TRANSIENT 会无条件 userPaused=false，GAIN 时判定通过，音乐自动响起。
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    p.handle(Event.USER_PAUSE, PAUSED);
    expect("RT-02：用户暂停已记录", p.isUserPaused());
    p.handle(Event.LOSS_TRANSIENT, PAUSED);
    expect("RT-02：第二次打断后用户暂停意愿仍保留", p.isUserPaused());
    a = p.handle(Event.GAIN, PAUSED);
    expect("RT-02：连续打断后 GAIN 不得续播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 5c. RT-02 对照：同一轮打断里"没被用户暂停过"必须照常续播（保证修复没把功能弄死）
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    p.handle(Event.LOSS_TRANSIENT, PAUSED);
    a = p.handle(Event.GAIN, PAUSED);
    expect("RT-02 对照：无用户暂停时连续打断仍续播", has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 5d. RT-02 对照：用户暂停后自己按播放，意愿清零，后续打断恢复正常语义
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    p.handle(Event.USER_PAUSE, PAUSED);
    p.handle(Event.USER_PLAY, PLAYING);
    expect("RT-02 对照：USER_PLAY 清空用户暂停标记", !p.isUserPaused());
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    a = p.handle(Event.GAIN, PAUSED);
    expect("RT-02 对照：清空后新一轮打断照常续播", has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 6b. RT-05：DUCK 期间必须能在 reset 前读到状态与原始音量
    p = new AudioFocusPolicy();
    List<Action> duck = p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    expect("RT-05：DUCK 期间 isDucked 为真", p.isDucked());
    expect("RT-05：记录到的原始音量是 0.6", Math.abs(p.getVolumeBeforeDuck() - 0.6f) < 1e-6);
    expect("RT-05：DUCK 产出压低动作", volumeOf(duck) > 0f);

    // 6. 暂停态收到 CAN_DUCK 不得改写音量
    p = new AudioFocusPolicy();
    a = p.handle(Event.LOSS_CAN_DUCK, PAUSED);
    expect("CAN_DUCK：暂停态不改音量", !has(a, AudioFocusPolicy.A_SET_VOLUME));

    // 7. 播放中 CAN_DUCK → 按比例压低，然后 GAIN 恢复原音量
    p = new AudioFocusPolicy();
    a = p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    expect("CAN_DUCK：播放态压低", has(a, AudioFocusPolicy.A_SET_VOLUME));
    expect("CAN_DUCK：压低值是原音量×系数", Math.abs(volumeOf(a) - 0.6f * AudioFocusPolicy.DUCK_RATIO) < 0.001f);
    a = p.handle(Event.GAIN, PLAYING_AT_60);
    expect("CAN_DUCK：GAIN 恢复 0.6", Math.abs(volumeOf(a) - 0.6f) < 0.001f);
    expect("CAN_DUCK：GAIN 不触发续播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 8. R6 duck 期间用户调音量 → GAIN 不得用旧值覆盖
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    p.handle(Event.USER_VOLUME, PLAYING);
    a = p.handle(Event.GAIN, PLAYING);
    expect("R6：用户调过音量后 GAIN 不再覆盖", !has(a, AudioFocusPolicy.A_SET_VOLUME));

    // 9. R1 申请焦点失败不得播放
    p = new AudioFocusPolicy();
    a = p.handle(Event.FOCUS_DENIED, PLAYING);
    expect("R1：焦点被拒不播放", has(a, AudioFocusPolicy.A_PAUSE));
    expect("R1：焦点被拒给提示", has(a, AudioFocusPolicy.A_NOTIFY_FOCUS_DENIED));

    // 10. R4 永久丢失：先暂停再释放焦点
    p = new AudioFocusPolicy();
    a = p.handle(Event.LOSS_PERMANENT, PLAYING);
    expect("R4：永久丢失先 PAUSE 再 ABANDON", indexOf(a, AudioFocusPolicy.A_PAUSE) < indexOf(a, AudioFocusPolicy.A_ABANDON_FOCUS));
    a = p.handle(Event.GAIN, PAUSED);
    expect("永久丢失后 GAIN 不续播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 11. 拔线/断蓝牙：恢复音量且不续播
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    a = p.handle(Event.NOISY_DISCONNECTED, PLAYING_AT_60);
    expect("拔线：恢复被压低的音量", Math.abs(volumeOf(a) - 0.6f) < 0.001f);
    expect("拔线：暂停", has(a, AudioFocusPolicy.A_PAUSE));
    a = p.handle(Event.GAIN, PAUSED);
    expect("拔线后 GAIN 不续播（不外放）", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 12. 交叉：压低中被打断，挂断后既要恢复音量也要续播
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    a = p.handle(Event.GAIN, PAUSED);
    expect("交叉：恢复音量", Math.abs(volumeOf(a) - 0.6f) < 0.001f);
    expect("交叉：同时续播", has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 13. 压低中永久丢失：恢复音量 + 暂停 + 释放焦点
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    a = p.handle(Event.LOSS_PERMANENT, PLAYING_AT_60);
    expect("交叉：永久丢失恢复音量", Math.abs(volumeOf(a) - 0.6f) < 0.001f);

    // 14. R5 销毁释放焦点并复位
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    a = p.handle(Event.SERVICE_DESTROY, PLAYING);
    expect("R5：销毁时释放焦点", has(a, AudioFocusPolicy.A_ABANDON_FOCUS));
    expect("R5：销毁后欠账清零", !p.isResumePending());
    expect("R5：销毁后回到 NORMAL", p.getPhase() == AudioFocusPolicy.Phase.NORMAL);

    // 15. 开关全关（reset）不产出动作、状态干净
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    p.reset();
    a = p.handle(Event.GAIN, PAUSED);
    expect("reset：不再续播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 16. 关闭「打断结束后自动继续播放」→ 不续播
    p = new AudioFocusPolicy();
    p.configure(false, true);
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    a = p.handle(Event.GAIN, PAUSED);
    expect("开关：关闭自动续播时不续播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 17. 关闭「自动降音量」→ 忽略 CAN_DUCK
    p = new AudioFocusPolicy();
    p.configure(true, false);
    a = p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    expect("开关：关闭降音量时不改音量", !has(a, AudioFocusPolicy.A_SET_VOLUME));

    // ═══ 2026-11 短打断分级 ═══
    // 18. 非通话 + 播放中收到 TRANSIENT（微信提示音）→ 压低继续播，不暂停
    p = new AudioFocusPolicy();
    a = p.handle(Event.LOSS_TRANSIENT, PLAYING_IDLE);
    expect("短打断：非通话时压低而不是暂停", has(a, AudioFocusPolicy.A_SET_VOLUME) && !has(a, AudioFocusPolicy.A_PAUSE));
    expect("短打断：压低值是原音量×系数", Math.abs(volumeOf(a) - 0.6f * AudioFocusPolicy.DUCK_RATIO) < 0.001f);
    expect("短打断：同时排兜底复核", has(a, AudioFocusPolicy.A_SCHEDULE_RECHECK));
    expect("短打断：复核延时取 RECHECK_DELAY_MS", recheckOf(a) == AudioFocusPolicy.RECHECK_DELAY_MS);

    // 19. 非通话但有兜底：对方不还焦点 → RECHECK 收回音量，播放不中断
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING_IDLE);
    a = p.handle(Event.FOCUS_RECHECK, PLAYING_IDLE);
    expect("短打断：复核收回音量", Math.abs(volumeOf(a) - 0.6f) < 0.001f);
    expect("短打断：复核后回到 NORMAL", p.getPhase() == AudioFocusPolicy.Phase.NORMAL);

    // 20. 通话中收到 TRANSIENT 必须让路（不能被短打断分级放过去）
    p = new AudioFocusPolicy();
    a = p.handle(Event.LOSS_TRANSIENT, PLAYING);
    expect("短打断：通话中仍然暂停", has(a, AudioFocusPolicy.A_PAUSE));
    expect("短打断：通话中也排兜底复核", has(a, AudioFocusPolicy.A_SCHEDULE_RECHECK));

    // 21. 非通话但缓冲中（没真在出声）→ 保守暂停，不擅改音量
    p = new AudioFocusPolicy();
    a = p.handle(Event.LOSS_TRANSIENT, BUFFERING_IDLE);
    expect("短打断：缓冲中不压音量", !has(a, AudioFocusPolicy.A_SET_VOLUME));
    expect("短打断：缓冲中暂停并等兜底", has(a, AudioFocusPolicy.A_PAUSE) && has(a, AudioFocusPolicy.A_SCHEDULE_RECHECK));

    // 22. CAN_DUCK 也排兜底（导航只发 CAN_DUCK 不发 GAIN 的老坑）
    p = new AudioFocusPolicy();
    a = p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    expect("CAN_DUCK：排队兜底防止音量卡在低位", has(a, AudioFocusPolicy.A_SCHEDULE_RECHECK));

    // 23. 兜底复核：从 DUCKED 收回音量，但不触发续播（本来就没停）
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    a = p.handle(Event.FOCUS_RECHECK, PLAYING_AT_60);
    expect("复核：DUCKED 收回原音量", Math.abs(volumeOf(a) - 0.6f) < 0.001f);
    expect("复核：DUCKED 不触发续播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 24. 兜底复核：从 INTERRUPTED 续播（这就是"暂停了必须会恢复"的保底）
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    a = p.handle(Event.FOCUS_RECHECK, PAUSED);
    expect("复核：INTERRUPTED 到点续播", has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));
    expect("复核：续播延时仍是 500ms", delayOf(a) == AudioFocusPolicy.RESUME_DELAY_MS);

    // 25. 兜底复核：用户自己按过暂停则不续播
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    p.handle(Event.USER_PAUSE, PAUSED);
    a = p.handle(Event.FOCUS_RECHECK, PAUSED);
    expect("复核：用户暂停过则不续播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 26. 兜底复核在 NORMAL 态是空操作（GAIN 已经处理过的场景不能重复恢复）
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING);
    p.handle(Event.GAIN, PAUSED);
    a = p.handle(Event.FOCUS_RECHECK, PAUSED);
    expect("复核：NORMAL 态空操作", a.isEmpty());

    // 27. 蓝牙还连着却收到 BECOMING_NOISY（路由抖动）→ 不得暂停、不得清欠账
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING_BT);
    a = p.handle(Event.NOISY_DISCONNECTED, PLAYING_BT);
    expect("路由抖动：不暂停", !has(a, AudioFocusPolicy.A_PAUSE));
    expect("路由抖动：不清掉续播欠账", p.isResumePending());
    expect("路由抖动：不置用户暂停", !p.isUserPaused());
    a = p.handle(Event.FOCUS_RECHECK, PAUSED);
    expect("路由抖动：之后仍能恢复", has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // ═══ 2026-11-20 通用性补修 ═══
    // 28. 已在压低时再来一次 TRANSIENT 不得二次压低（否则 0.6 → 0.15 → 0.0375）
    p = new AudioFocusPolicy();
    a = p.handle(Event.LOSS_TRANSIENT, PLAYING_IDLE);
    List<Action> second = p.handle(Event.LOSS_TRANSIENT, new Snapshot(AudioFocusPolicy.PS_PLAYING, true, true,
            0.6f * AudioFocusPolicy.DUCK_RATIO, false, false));
    expect("二次打断：不再重复压低", !has(second, AudioFocusPolicy.A_SET_VOLUME));
    expect("二次打断：原始音量仍记作 0.6", Math.abs(p.getVolumeBeforeDuck() - 0.6f) < 0.001f);

    // 29. 用户在压低期间按了播放（phase 被清成 NORMAL）→ 复核仍要收回音量
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_CAN_DUCK, PLAYING_AT_60);
    p.handle(Event.USER_PLAY, PLAYING_AT_60);
    a = p.handle(Event.FOCUS_RECHECK, PLAYING_AT_60);
    expect("复核：NORMAL 但音量仍压着时也要收回", Math.abs(volumeOf(a) - 0.6f) < 0.001f);
    expect("复核：这种情况不触发续播", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 30. 对方长期占用（闹钟/长视频）：复核失败要降级为暂停，不能继续以低音量出声
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING_IDLE);
    a = p.handle(Event.RECHECK_DENIED, PLAYING_IDLE);
    expect("长占用：降级为暂停", has(a, AudioFocusPolicy.A_PAUSE));
    expect("长占用：同时收回被压的音量", Math.abs(volumeOf(a) - 0.6f) < 0.001f);
    expect("长占用：欠账保留待对方释放", p.isResumePending());
    a = p.handle(Event.FOCUS_RECHECK, PAUSED);
    expect("长占用：对方释放后仍能恢复", has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    // 31. 长占用期间用户自己按暂停 → 释放后不得自动复活
    p = new AudioFocusPolicy();
    p.handle(Event.LOSS_TRANSIENT, PLAYING_IDLE);
    p.handle(Event.RECHECK_DENIED, PLAYING_IDLE);
    p.handle(Event.USER_PAUSE, PAUSED);
    a = p.handle(Event.FOCUS_RECHECK, PAUSED);
    expect("长占用：用户暂停过则不复活", !has(a, AudioFocusPolicy.A_SCHEDULE_RESUME));

    } catch (Throwable t) {
      // 用例抛异常（空动作序列上取值、策略返回 null 等）也必须计入一条失败并继续给出结果行，
      // 否则 CI 只能说"runner 崩了"，定位不到是哪一条断言失效。
      fail++;
      System.out.println("  ❌ 用例异常中断：" + t);
    }
    System.out.println("\n结果：" + pass + " 通过 / " + fail + " 失败");
    if (fail > 0) System.exit(1);
  }

  private static boolean has(List<Action> list, int type) {
    return indexOf(list, type) >= 0;
  }

  private static int indexOf(List<Action> list, int type) {
    for (int i = 0; i < list.size(); i++) if (list.get(i).type == type) return i;
    return -1;
  }

  private static float volumeOf(List<Action> list) {
    for (Action x : list) if (x.type == AudioFocusPolicy.A_SET_VOLUME) return x.volume;
    return -1f;
  }

  private static long delayOf(List<Action> list) {
    for (Action x : list) if (x.type == AudioFocusPolicy.A_SCHEDULE_RESUME) return x.delayMs;
    return -1L;
  }

  private static long recheckOf(List<Action> list) {
    for (Action x : list) if (x.type == AudioFocusPolicy.A_SCHEDULE_RECHECK) return x.delayMs;
    return -1L;
  }

  private static void expect(String name, boolean ok) {
    if (ok) { pass++; System.out.println("  ✅ " + name); }
    else { fail++; System.out.println("  ❌ " + name); }
  }
}
