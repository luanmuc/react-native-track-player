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

    public Snapshot(int state, boolean playWhenReady, boolean isPlaying, float volume) {
      this.state = state;
      this.playWhenReady = playWhenReady;
      this.isPlaying = isPlaying;
      this.volume = volume;
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

    @Override public String toString() {
      switch (type) {
        case A_PAUSE: return "PAUSE";
        case A_PLAY: return "PLAY";
        case A_SET_VOLUME: return "SET_VOLUME(" + volume + ")";
        case A_REQUEST_FOCUS: return "REQUEST_FOCUS";
        case A_ABANDON_FOCUS: return "ABANDON_FOCUS";
        case A_SCHEDULE_RESUME: return "SCHEDULE_RESUME(" + delayMs + "ms)";
        case A_NOTIFY_FOCUS_DENIED: return "NOTIFY_FOCUS_DENIED";
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
        // 累积而不是覆盖：第二次打断时播放器已是 PAUSED，直接赋值会把第一次的欠账抹掉
        resumePending = resumePending || snap.intentPlaying();
        // RT-02：userPaused 同样必须"累积"而非清零。连续打断场景下（来电 → 通话中点暂停 →
        // 导航播报再打断），清零点会让用户在第一次打断里的暂停意愿被第二次打断抹掉，
        // GAIN 时 shouldResume 判定通过，音乐自动响起。清零点只在 USER_PLAY 里保留。
        phase = Phase.INTERRUPTED;
        out.add(Action.of(A_PAUSE));
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
        // 拔线/断蓝牙：恢复被压低的音量，并明确不续播（否则挂断电话会从扬声器外放）
        if (ducked) {
          out.add(Action.volume(volumeBeforeDuck));
          ducked = false;
        }
        resumePending = false;
        userPaused = true;
        if (phase == Phase.DUCKED || phase == Phase.INTERRUPTED) phase = Phase.NORMAL;
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
