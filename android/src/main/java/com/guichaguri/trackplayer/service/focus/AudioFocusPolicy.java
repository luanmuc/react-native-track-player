package com.guichaguri.trackplayer.service.focus;

import java.util.ArrayList;
import java.util.List;

/**
 * 音频焦点恢复策略（纯逻辑，零 Android 依赖）。
 *
 * <p>设计背景：原来把「打断前的状态」压在两个布尔里（wasPlayingBeforeTransientLoss / wasDucking），
 * 无法表达「缓冲中被打断」「连续两次打断」「用户在打断期间自己按了暂停」「duck 期间用户改了音量」
 * 这些交叉态，导致该续播的不续播、不该续播的续播。这里把状态升级成显式 Phase，并让每次事件
 * 转移都产出**动作列表**，由 MusicManager 执行——逻辑因此可以脱离 Android 在 JVM 上穷举验证。
 *
 * <p>约定：
 * <ul>
 *   <li>输入 = 事件 + 播放器快照（不可变）。</li>
 *   <li>输出 = 动作列表（按顺序执行）。</li>
 *   <li>策略不持有 Handler、不碰 AudioManager，所有延时/系统调用都由执行器负责。</li>
 * </ul>
 */
public final class AudioFocusPolicy {

  /** 播放器状态：与 PlaybackStateCompat 数值对齐，这里独立定义以免依赖 android.* */
  public static final int PS_NONE = 0;
  public static final int PS_STOPPED = 1;
  public static final int PS_PAUSED = 2;
  public static final int PS_PLAYING = 3;
  public static final int PS_BUFFERING = 6;
  public static final int PS_ERROR = 7;
  public static final int PS_CONNECTING = 8;

  /** 打断恢复的延迟：等通话彻底结束、音频路由从听筒切回来 */
  public static final long RESUME_DELAY_MS = 500L;
  /** 压低目标：相对当前音量的系数（不是绝对值，避免用户音量 60% 与 100% 降幅不一致） */
  public static final float DUCK_RATIO = 0.25f;
  /**
   * GAIN 缺失时的兜底复核延时。
   *
   * <p>背景：微信/系统通知音这类打断只响 1~3 秒，抢焦点的一方**不一定会调用
   * abandonAudioFocus**（厂商 ROM 尤其如此），于是 AUDIOFOCUS_GAIN 永远不到，
   * 播放器就一直停在暂停态或 25% 音量。这里排一次延时复核，由执行器先放弃再重新
   * 申请焦点，用"系统真的判给我们"来替代"等对方还"。
   */
  public static final long RECHECK_DELAY_MS = 3000L;

  public enum Event {
    /** onPlay 时申请焦点成功 */
    FOCUS_GRANTED,
    /** onPlay 时申请焦点失败（通话中、其他 App 持有） */
    FOCUS_DENIED,
    /** 临时丢失：来电、其他 App 临时播放 */
    LOSS_TRANSIENT,
    /** 可压低：导航播报 */
    LOSS_CAN_DUCK,
    /** 永久丢失：其他播放器接管 */
    LOSS_PERMANENT,
    /** 重新获得焦点 */
    GAIN,
    /** 用户主动播放（JS/通知栏/媒体键） */
    USER_PLAY,
    /** 用户主动暂停 */
    USER_PAUSE,
    /** 用户主动改音量 */
    USER_VOLUME,
    /** 耳机拔出 / 蓝牙断开 */
    NOISY_DISCONNECTED,
    /** 兜底复核到点：对方始终不还焦点，按"焦点已回到我们手里"收尾 */
    FOCUS_RECHECK,
    /**
     * 兜底复核到点但**焦点仍被别人持有**（闹钟、长通话、长视频这类长时间占用）。
     * 此时不该继续以压低音量出声，应降级为暂停，并把欠账留着等对方释放。
     */
    RECHECK_DENIED,
    /** Service 销毁 */
    SERVICE_DESTROY
  }

  /** 状态机阶段（互斥） */
  public enum Phase {
    /** 没有打断 */
    NORMAL,
    /** 临时打断中（已暂停，欠一次续播） */
    INTERRUPTED,
    /** 压低中（仍在播放） */
    DUCKED,
    /** 永久丢失（不续播） */
    LOST
  }

  /** 播放器快照：由执行器在事件发生时采集 */
  public static final class Snapshot {
    /** getState()：PS_* 之一 */
    public final int state;
    /** playWhenReady：用户意图是否是播放（缓冲中也算） */
    public final boolean playWhenReady;
    /** isPlaying：当前是否真的在出声音 */
    public final boolean isPlaying;
    /** 当前播放器音量（0..1） */
    public final float volume;
    /**
     * 是否处于通话/铃声场景（AudioManager.getMode() ∈ {IN_CALL, IN_COMMUNICATION, RINGTONE}）。
     * 通话必须让路（暂停），非通话的短打断不该让整首歌停下来。 */
    public final boolean inCall;
    /**
     * 当前是否仍有外部音频设备在位（蓝牙 A2DP/SCO、有线耳机、USB 耳机等）。
     * 用于区分"真的拔线/断蓝牙"与"蓝牙路由抖动误发的 BECOMING_NOISY"。 */
    public final boolean externalDevice;

    /** 兼容旧调用方：按通话级打断处理（保守暂停），且视为外部设备已不在 */
    public Snapshot(int state, boolean playWhenReady, boolean isPlaying, float volume) {
      this(state, playWhenReady, isPlaying, volume, true, false);
    }

    public Snapshot(int state, boolean playWhenReady, boolean isPlaying, float volume,
                    boolean inCall, boolean externalDevice) {
      this.state = state;
      this.playWhenReady = playWhenReady;
      this.isPlaying = isPlaying;
      this.volume = volume;
      this.inCall = inCall;
      this.externalDevice = externalDevice;
    }

    /** 用户意图是「在播」：真在播、准备播、或缓冲中。这是续播判定的唯一依据 */
    public boolean intentPlaying() {
      return isPlaying || playWhenReady || state == PS_PLAYING || state == PS_BUFFERING || state == PS_CONNECTING;
    }
  }

  /** 动作类型 */
  public static final int A_PAUSE = 1;
  public static final int A_PLAY = 2;
  public static final int A_SET_VOLUME = 3;
  public static final int A_REQUEST_FOCUS = 4;
  public static final int A_ABANDON_FOCUS = 5;
  /** 延时续播：执行器负责延迟并在触发时复核 hasAudioFocus；策略已保证 userPaused 为 false */
  public static final int A_SCHEDULE_RESUME = 6;
  /** 申请焦点失败：不要播放，并给出提示 */
  public static final int A_NOTIFY_FOCUS_DENIED = 7;
  /** 延时兜底复核：执行器负责到点放弃并重新申请焦点，成功则回灌 FOCUS_RECHECK */
  public static final int A_SCHEDULE_RECHECK = 8;

  public static final class Action {
    public final int type;
    public final float volume;
    public final long delayMs;

    private Action(int type, float volume, long delayMs) {
      this.type = type;
      this.volume = volume;
      this.delayMs = delayMs;
    }

    static Action of(int type) {
      return new Action(type, 0f, 0L);
    }

    static Action volume(float volume) {
      return new Action(A_SET_VOLUME, volume, 0L);
    }

    static Action resume(long delayMs) {
      return new Action(A_SCHEDULE_RESUME, 0f, delayMs);
    }

    static Action recheck(long delayMs) {
      return new Action(A_SCHEDULE_RECHECK, 0f, delayMs);
    }

    @Override public String toString() {
      switch (type) {
        case A_PAUSE: return "PAUSE";
        case A_PLAY: return "PLAY";
        case A_SET_VOLUME: return "SET_VOLUME(" + volume + ")";
        case A_REQUEST_FOCUS: return "REQUEST_FOCUS";
        case A_ABANDON_FOCUS: return "ABANDON_FOCUS";
        case A_SCHEDULE_RESUME: return "SCHEDULE_RESUME(" + delayMs + "ms)";
        case A_NOTIFY_FOCUS_DENIED: return "NOTIFY_FOCUS_DENIED";
        case A_SCHEDULE_RECHECK: return "SCHEDULE_RECHECK(" + delayMs + "ms)";
        default: return "UNKNOWN(" + type + ")";
      }
    }
  }

  private Phase phase = Phase.NORMAL;
  /** 欠一次续播（累积语义：连续打断不会被覆盖掉） */
  private boolean resumePending = false;
  /** 打断期间用户主动暂停过 */
  private boolean userPaused = false;
  /** 压低前的音量 */
  private float volumeBeforeDuck = 1f;
  /** 当前是否处于压低（音量已被改写） */
  private boolean ducked = false;
  /** 子开关：打断结束后是否自动续播（对应 player.autoResumeAfterInterrupt） */
  private boolean autoResume = true;
  /** 子开关：CAN_DUCK 时是否压低（对应 player.duckOnTransientDuck） */
  private boolean duckEnabled = true;

  /** 由 MusicManager 在 setupPlayer 时注入两个子开关 */
  public void configure(boolean autoResume, boolean duckEnabled) {
    this.autoResume = autoResume;
    this.duckEnabled = duckEnabled;
  }

  public Phase getPhase() { return phase; }
  public boolean isResumePending() { return resumePending; }
  public boolean isUserPaused() { return userPaused; }
  public boolean isDucked() { return ducked; }
  public float getVolumeBeforeDuck() { return volumeBeforeDuck; }


  /**
   * 处理一个事件，返回要执行的动作（可能为空）。
   * 同一个事件的不同分支互斥，动作按列表顺序执行。
   */
  public List<Action> handle(Event event, Snapshot snap) {
    List<Action> out = new ArrayList<>(4);
    switch (event) {
      case FOCUS_GRANTED:
        // 拿到焦点不代表要播，只更新持有状态；播放动作由 USER_PLAY / GAIN 触发
        break;

      case FOCUS_DENIED:
        // 通话中/其他 App 持有时申请失败：不能"没焦点也照播"
        out.add(Action.of(A_NOTIFY_FOCUS_DENIED));
        out.add(Action.of(A_PAUSE));
        break;

      case LOSS_TRANSIENT:
        // 短打断分级（2026-11 增加）：通知音、提示音这类打断只响一秒，把整首歌停下来
        // 再指望对方还焦点过于脆弱——对方不 abandon 就等于永久暂停。非通话场景改为
        // 压低继续播（与导航播报同语义），并排一次兜底复核。
        if (!snap.inCall && duckEnabled && snap.isPlaying) {
          if (!ducked) {
            volumeBeforeDuck = snap.volume;
            ducked = true;
            out.add(Action.volume(snap.volume * DUCK_RATIO));
          }
          // 已经在压低时再来一次 TRANSIENT 不得二次压低（否则会叠到 6.25%）
          phase = Phase.DUCKED;
          resumePending = resumePending || snap.intentPlaying();
          out.add(Action.recheck(RECHECK_DELAY_MS));
          break;
        }
        // 通话/无 duck 能力：必须让路。累积而不是覆盖：第二次打断时播放器已是 PAUSED，
        // 直接赋值会把第一次的欠账抹掉
        resumePending = resumePending || snap.intentPlaying();
        // RT-02：userPaused 同样必须"累积"而非清零。连续打断场景下（来电 → 通话中点暂停 →
        // 导航播报再打断），清零点会让用户在第一次打断里的暂停意愿被第二次打断抹掉，
        // GAIN 时 shouldResume 判定通过，音乐自动响起。清零点只在 USER_PLAY 里保留。
        phase = Phase.INTERRUPTED;
        out.add(Action.of(A_PAUSE));
        out.add(Action.recheck(RECHECK_DELAY_MS));
        break;

      case LOSS_CAN_DUCK:
        // 只在真正在播时压低。暂停态收到 CAN_DUCK 若照样改写音量，用户之后点播放就会是 20% 音量，
        // 而很多导航实现只发 CAN_DUCK 不发 GAIN，音量会一直卡在低位。
        if (!duckEnabled) break;
        if (!snap.isPlaying || phase == Phase.INTERRUPTED || ducked) break;
        volumeBeforeDuck = snap.volume;
        ducked = true;
        phase = Phase.DUCKED;
        out.add(Action.volume(snap.volume * DUCK_RATIO));
        // 很多导航实现只发 CAN_DUCK 不发 GAIN，音量会一直卡在低位——兜底复核负责收回音量
        out.add(Action.recheck(RECHECK_DELAY_MS));
        break;

      case GAIN:
        if (ducked) {
          out.add(Action.volume(volumeBeforeDuck));
          ducked = false;
        }
        boolean shouldResume = resumePending && !userPaused && autoResume;
        resumePending = false;
        userPaused = false;
        if (phase == Phase.INTERRUPTED || phase == Phase.DUCKED) phase = Phase.NORMAL;
        if (shouldResume) out.add(Action.resume(RESUME_DELAY_MS));
        break;

      case LOSS_PERMANENT:
        // 永久丢失：不续播（用户可能已经切到别的播放器）；先停再释放焦点，避免释放瞬间被重分配打扰
        resumePending = false;
        userPaused = false;
        if (ducked) {
          out.add(Action.volume(volumeBeforeDuck));
          ducked = false;
        }
        phase = Phase.LOST;
        out.add(Action.of(A_PAUSE));
        out.add(Action.of(A_ABANDON_FOCUS));
        break;

      case USER_PLAY:
        // 用户接管：清掉欠账与"用户暂停"标记，之后 GAIN 不得再抢先恢复
        resumePending = false;
        userPaused = false;
        phase = Phase.NORMAL;
        break;

      case USER_PAUSE:
        // 只要存在欠账（打断中 / 还没到点续播），就记下"用户自己按的暂停"
        if (resumePending || phase == Phase.INTERRUPTED) userPaused = true;
        break;

      case USER_VOLUME:
        // 用户在压低期间调音量：视为接管，不再用旧值覆盖他
        if (ducked) {
          ducked = false;
          if (phase == Phase.DUCKED) phase = Phase.NORMAL;
        }
        break;

      case NOISY_DISCONNECTED:
        // 蓝牙路由抖动也会发 BECOMING_NOISY，但设备其实还连着。此时若照旧置 userPaused，
        // 音乐会被永久停在暂停态（这是"暂停后不自动恢复"的第二个来源）。
        if (snap.externalDevice) {
          if (ducked) {
            out.add(Action.volume(volumeBeforeDuck));
            ducked = false;
          }
          // phase 不动：若原本还欠一次续播，抖动不该把欠账悬空（交给 GAIN/复核收尾）
          break;
        }
        // 真拔线/断开：恢复被压低的音量，并明确不续播（否则挂断电话会从扬声器外放）
        if (ducked) {
          out.add(Action.volume(volumeBeforeDuck));
          ducked = false;
        }
        resumePending = false;
        userPaused = true;
        if (phase == Phase.DUCKED || phase == Phase.INTERRUPTED) phase = Phase.NORMAL;
        out.add(Action.of(A_PAUSE));
        break;

      case FOCUS_RECHECK:
        // 对方始终不还焦点（GAIN 没到）。只要还停在打断/压低态，就按"焦点已回到我们手里"收尾：
        // 收回音量、按欠账续播。用户自己按过暂停（userPaused）则不续播。
        if (phase == Phase.LOST) break;
        if (ducked) {
          out.add(Action.volume(volumeBeforeDuck));
          ducked = false;
        }
        // phase 已被 USER_PLAY 清成 NORMAL 但音量还压着：只收音量，不续播
        if (phase == Phase.NORMAL) break;
        boolean resumeAfterRecheck = resumePending && !userPaused && autoResume;
        resumePending = false;
        userPaused = false;
        phase = Phase.NORMAL;
        if (resumeAfterRecheck) out.add(Action.resume(RESUME_DELAY_MS));
        break;

      case RECHECK_DENIED:
        // 对方是长时间占用（闹钟、长通话、长视频），继续压低出声不合适：降级为暂停。
        // 欠账保留，等对方释放后的 GAIN 或下一次复核成功再恢复。
        if (phase == Phase.LOST) break;
        if (ducked) {
          out.add(Action.volume(volumeBeforeDuck));
          ducked = false;
        }
        resumePending = resumePending || snap.intentPlaying();
        phase = Phase.INTERRUPTED;
        out.add(Action.of(A_PAUSE));
        break;

      case SERVICE_DESTROY:
        // 销毁：复位全部状态并释放焦点，避免 Service 重建后带着脏状态
        resumePending = false;
        userPaused = false;
        if (ducked) {
          out.add(Action.volume(volumeBeforeDuck));
          ducked = false;
        }
        phase = Phase.NORMAL;
        out.add(Action.of(A_ABANDON_FOCUS));
        break;

      default:
        break;
    }
    return out;
  }

  /** 开关全关（用户选择完全不接管焦点）时调用：只复位，不产出动作 */
  public void reset() {
    phase = Phase.NORMAL;
    resumePending = false;
    userPaused = false;
    ducked = false;
    volumeBeforeDuck = 1f;
  }
}
