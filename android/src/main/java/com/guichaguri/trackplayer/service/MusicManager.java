package com.guichaguri.trackplayer.service;

import static androidx.media3.common.C.WAKE_MODE_NONE;
import static androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS;
import static androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS;
import static androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS;
import static androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS;
import static androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import com.guichaguri.trackplayer.service.focus.AudioFocusPolicy;
import com.guichaguri.trackplayer.service.focus.PendingSlots;

import androidx.media3.common.C;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;

import com.guichaguri.trackplayer.module.MusicEvents;
import com.guichaguri.trackplayer.service.metadata.MetadataManager;
import com.guichaguri.trackplayer.service.models.Track;
import com.guichaguri.trackplayer.service.player.ExoPlayback;
import com.guichaguri.trackplayer.service.player.LocalPlayback;

/**
 * @author Guichaguri
 */
@UnstableApi
public class MusicManager {

    private final MusicService service;

    private final WakeLock wakeLock;
    private WifiLock wifiLock = null;

    private final MetadataManager metadata;
    private ExoPlayback playback;

    // lx-music fork: 音频焦点精细处理
    private AudioManager audioManager;
    // 是否启用焦点处理（读 setupPlayer 的 handleAudioFocus 选项，默认开）
    // 注意：无论开关如何，media3 内置 AudioFocusManager 都必须关闭（见 setAudioAttributes 处），
    // 否则两套管理器会互相下发 LOSS，把刚开始的播放打断（b53 点播放一直缓冲的直接原因）
    private boolean handleAudioFocus = true;
    private boolean hasAudioFocus = false;
    // lx-music fork（B 方案）：焦点恢复决策全部交给纯逻辑状态机 AudioFocusPolicy，
    // 本类只负责「采集播放器快照 → 把事件喂给策略 → 执行返回的动作」。
    // 这样 4 个焦点回调 × 播放器 5 个状态 × 用户 3 个开关的交叉态才能在 JVM 上穷举验证。
    private final AudioFocusPolicy focusPolicy = new AudioFocusPolicy();
    // RT-04：已排队的续播任务句柄。原来是 `getHandler().postDelayed(...)` 丢弃返回值，
    // 于是用户暂停 / 切歌 / reset / 划掉通知都无法撤销，到点仍会把状态拉回播放（RT-01 同源）。
    private Runnable pendingResume = null;
    /** 兜底复核任务句柄（对方不还焦点时到点主动重申请） */
    private Runnable pendingRecheck = null;
    /**
     * 待执行任务的代次簿记（纯逻辑类，可脱离 Android 单测）。
     * `removeCallbacks` 对已开始执行的任务无效，光靠调用点撤销挡不住 500ms 窗口的竞态；
     * 任务到点先问一次「我还是当前那一条吗」，撤销才能真正生效（RT-01/RT-04）。
     */
    private final PendingSlots pendingSlots = new PendingSlots();
    /** 兜底复核已重试次数，避免对方长期占用时无限自我打断 */
    private int recheckAttempts = 0;
    private static final int RECHECK_MAX_ATTEMPTS = 2;

    // 两个子行为开关（仅在 handleAudioFocus 为 true 时才有意义）
    private boolean autoResumeAfterInterrupt = true;   // 临时打断结束后是否自动续播
    private boolean duckOnTransientDuck = true;        // CAN_DUCK 时是否降低音量

    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        if (playback == null || !handleAudioFocus) return;
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                dispatch(AudioFocusPolicy.Event.GAIN);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                dispatch(AudioFocusPolicy.Event.LOSS_TRANSIENT);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                dispatch(AudioFocusPolicy.Event.LOSS_CAN_DUCK);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                dispatch(AudioFocusPolicy.Event.LOSS_PERMANENT);
                break;
            default:
                break;
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // lx-music fork: 耳机拔线/蓝牙断开交给策略处理（复位压低音量 + 明确不续播，避免挂断电话后外放）
            dispatch(AudioFocusPolicy.Event.NOISY_DISCONNECTED);
            service.emit(MusicEvents.BUTTON_PAUSE, null);
        }
    };

    /**
     * lx-music fork: 只记录「用户按了暂停」，不改播放状态。
     * 通知栏/媒体键的暂停是 emit 给 JS、由 JS 回调才真正停的，中间有延迟；
     * 先标记住，避免这期间恰好收到 AUDIOFOCUS_GAIN 被自动续播抢先。
     */
    public void markUserPaused() {
        dispatch(AudioFocusPolicy.Event.USER_PAUSE);
    }

    /**
     * lx-music fork: 用户主动暂停的统一入口（JS / 通知栏按钮走这里）。
     * 让「打断结束后自动续播」能区分「系统把播放暂停了」和「用户自己按了暂停」。
     */
    public void pauseByUser() {
        dispatch(AudioFocusPolicy.Event.USER_PAUSE);
        ExoPlayback pb = getPlayback();
        if (pb != null) pb.pause();
    }

    /**
     * lx-music fork: 用户主动改音量（替代 MusicModule 里直接 setVolume）。
     * 目的：让策略知道「音量已被用户接管」，避免打断结束的 GAIN 用旧值覆盖用户新调的音量。
     */
    public void setUserVolume(float volume) {
        dispatch(AudioFocusPolicy.Event.USER_VOLUME);
        ExoPlayback pb = getPlayback();
        if (pb != null) pb.setVolume(volume);
    }

    /**
     * lx-music fork: 程序改音量（淡入/淡出/睡前淡出/恢复用户设定值）。
     *
     * <p>**不得**走 {@link #setUserVolume}：App 用 `TrackPlayer.setVolume` 做逐帧淡入，
     * 每帧都会打到 JS→native 的 `setVolume`，若每次都 dispatch `USER_VOLUME`，
     * 策略会认为「用户在调音量」并清掉 duck 标记 —— 表现为打断时压不下去、
     * 或压低后音量永久停在 25%。App 侧没有用户可调的 player.volume 控件
     * （用户调的是系统媒体音量，属 stream volume，与 `pb.setVolume` 无关）。
     */
    public void setVolumeInternal(float volume) {
        ExoPlayback pb = getPlayback();
        if (pb != null) pb.setVolume(volume);
    }

    /** 采集播放器快照；playback 为空时给一个"什么都不是"的安全快照 */
    private AudioFocusPolicy.Snapshot snapshot() {
        ExoPlayback pb = playback;
        if (pb == null) return new AudioFocusPolicy.Snapshot(AudioFocusPolicy.PS_NONE, false, false, 1f);
        return new AudioFocusPolicy.Snapshot(pb.getState(), pb.getPlayWhenReady(), pb.isPlayingNow(), pb.getVolume(),
                isInCall(), hasExternalAudioDevice());
    }

    /**
     * 是否处于通话/铃声场景。通话必须让路（暂停），通知音这类非通话短打断了不该让整首歌停下。
     * RINGTONE 也计入：来电响铃期间 getMode() 就是 MODE_RINGTONE。
     */
    private boolean isInCall() {
        if (audioManager == null) return false;
        int mode = audioManager.getMode();
        return mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE;
    }

    /**
     * 当前是否仍有外部音频设备在位（蓝牙 A2DP/SCO/LE、有线耳机、USB 耳机）。
     * 用来区分「真拔线/断蓝牙」与「蓝牙路由抖动误发的 BECOMING_NOISY」——
     * 后者若照旧按拔线处理，会把音乐永久停在暂停态。
     *
     * <p>三重判定（任一为真即视为在位）：
     * <ol>
     *   <li>`isBluetoothA2dpOn` / `isBluetoothScoOn` / `isWiredHeadsetOn`——这三个 deprecated
     *       方法反映的是**当前路由**，比设备枚举准（枚举会把「已配对未激活」也算进来，</br>
     *       导致真断线时误判为在位 → 不暂停 → 外放）；</li>
     *   <li>设备枚举，覆盖 LE Audio 耳机（`TYPE_BLE_HEADSET`）与 USB 系；</li>
     *   <li>任何异常都按「不在位」处理，宁可暂停也不外放。</li>
     * </ol>
     */
    @SuppressWarnings("deprecation")
    private boolean hasExternalAudioDevice() {
        if (audioManager == null) return false;
        try {
            if (audioManager.isBluetoothA2dpOn()) return true;
            if (audioManager.isBluetoothScoOn()) return true;
            if (audioManager.isWiredHeadsetOn()) return true;
        } catch (Exception ignored) {
            // 个别 ROM 对 deprecated 方法抛异常，继续走枚举兜底
        }
        AudioDeviceInfo[] outs;
        try {
            outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        } catch (Exception e) {
            return false;
        }
        if (outs == null) return false;
        for (AudioDeviceInfo d : outs) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    /** 事件入口：开关关闭时完全不接管；每次同步两个子开关（改设置后无需重建策略） */
    private void dispatch(AudioFocusPolicy.Event event) {
        // RT-01/RT-04：用户自己的动作（播放/暂停/停止）一律撤销已排队的续播，
        // 这样"500ms 内点暂停"不会再被到点的 play() 覆盖。
        // 注意：Event 枚举里没有 USER_STOP —— stop 路径在 MusicModule.stop() 里直接撤销。
        if (event == AudioFocusPolicy.Event.USER_PAUSE || event == AudioFocusPolicy.Event.USER_PLAY) {
            cancelPendingResume();
            cancelPendingRecheck();
        }
        if (!handleAudioFocus) return;
        // GAIN 到了就不需要兜底了
        if (event == AudioFocusPolicy.Event.GAIN) cancelPendingRecheck();
        focusPolicy.configure(autoResumeAfterInterrupt, duckOnTransientDuck);
        execute(focusPolicy.handle(event, snapshot()));
    }

    /**
     * RT-04：排队一次续播，并把任务句柄留着以便撤销。
     * 到点时复核三件事：仍持有焦点、策略仍认为该恢复、播放器仍在暂停态。
     */
    private void scheduleResume(long delayMs) {
        Handler h = getHandler();
        if (h == null) return;
        cancelPendingResume();
        final long token = pendingSlots.armResume();
        pendingResume = () -> {
            pendingResume = null;
            if (!pendingSlots.isResumeCurrent(token)) return;
            pendingSlots.disarmResume();
            if (playback != null && hasAudioFocus
                    && focusPolicy.getPhase() == AudioFocusPolicy.Phase.NORMAL
                    && playback.getState() == PlaybackStateCompat.STATE_PAUSED) playback.play();
        };
        h.postDelayed(pendingResume, delayMs);
    }

    /**
     * RT-04：撤销已排队的续播。用户暂停、停止、复位、切换播放器、销毁时必须调，
     * 否则 500ms 窗口内用户的意图会被到点的 play() 覆盖（RT-01）。
     */
    public void cancelPendingResume() {
        // 先让代次失效再 removeCallbacks：即使任务已经开始执行、remove 拦不住，
        // 它到点也会被 isResumeCurrent 挡回去
        pendingSlots.disarmResume();
        if (pendingResume == null) return;
        Handler h = getHandler();
        if (h != null) h.removeCallbacks(pendingResume);
        pendingResume = null;
    }

    /**
     * 兜底复核（2026-11 增加）：通知音/导航这类短打断，抢焦点的一方不一定会 abandonAudioFocus，
     * AUDIOFOCUS_GAIN 就永远不来，播放器会一直停在暂停态或 25% 音量。
     * 到点后先放弃再重新申请焦点——hasAudioFocus 只是本地记账，对方不还时它仍是 true，
     * 直接 request 是空操作，必须先 abandon 才能让系统真的重新判给我们。
     */
    private void scheduleRecheck(long delayMs) {
        Handler h = getHandler();
        if (h == null) return;
        cancelPendingRecheck();
        final long token = pendingSlots.armRecheck();
        pendingRecheck = () -> {
            pendingRecheck = null;
            if (!pendingSlots.isRecheckCurrent(token)) return;
            pendingSlots.disarmRecheck();
            if (playback == null || !handleAudioFocus) return;
            AudioFocusPolicy.Phase phase = focusPolicy.getPhase();
            if (phase == AudioFocusPolicy.Phase.NORMAL || phase == AudioFocusPolicy.Phase.LOST) return;
            abandonAudioFocus();
            if (requestAudioFocus()) {
                recheckAttempts = 0;
                dispatch(AudioFocusPolicy.Event.FOCUS_RECHECK);
                return;
            }
            // 对方仍持有（闹钟、长通话、长视频）：继续压低出声不合适，降级为暂停
            dispatch(AudioFocusPolicy.Event.RECHECK_DENIED);
            if (recheckAttempts < RECHECK_MAX_ATTEMPTS) {
                recheckAttempts++;
                scheduleRecheck(delayMs);
            } else {
                recheckAttempts = 0;
            }
        };
        h.postDelayed(pendingRecheck, delayMs);
    }

    /** 撤销兜底复核：用户接管、GAIN 真的到了、销毁时都要调 */
    private void cancelPendingRecheck() {
        pendingSlots.disarmRecheck();
        if (pendingRecheck == null) return;
        Handler h = getHandler();
        if (h != null) h.removeCallbacks(pendingRecheck);
        pendingRecheck = null;
        recheckAttempts = 0;
    }

    /**
     * RT-03：三个焦点开关的运行时更新通道。
     * 原来只在 createLocalPlayback（setupPlayer）读一次，设置页改了不重建播放器就完全不生效。
     * 只覆盖 bundle 里显式给出的键，避免 App 不传时把用户已关的开关回退成默认 true。
     */
    public void updateFocusOptions(android.os.Bundle bundle) {
        if (bundle.containsKey("handleAudioFocus")) {
            this.handleAudioFocus = bundle.getBoolean("handleAudioFocus", true);
        }
        if (bundle.containsKey("autoResumeAfterInterrupt")) {
            this.autoResumeAfterInterrupt = bundle.getBoolean("autoResumeAfterInterrupt", true);
        }
        if (bundle.containsKey("duckOnTransientDuck")) {
            this.duckOnTransientDuck = bundle.getBoolean("duckOnTransientDuck", true);
        }
        focusPolicy.configure(autoResumeAfterInterrupt, duckOnTransientDuck);
    }

    /**
     * RT-05：复位焦点策略前，先把 duck 期间压下去的音量恢复回来。
     * 原来 reset() 直接清掉 ducked / volumeBeforeDuck，Ducked 期间调用会让音量永久卡在 25%。
     */
    public void resetFocus() {
        ExoPlayback pb = getPlayback();
        if (pb != null && focusPolicy.isDucked()) {
            pb.setVolume(focusPolicy.getVolumeBeforeDuck());
        }
        cancelPendingResume();
        cancelPendingRecheck();
        focusPolicy.reset();
    }

    /** 执行策略返回的动作（顺序敏感：永久丢失要求"先暂停再释放焦点"） */

    private void execute(java.util.List<AudioFocusPolicy.Action> actions) {
        ExoPlayback pb = playback;
        for (AudioFocusPolicy.Action action : actions) {
            switch (action.type) {
                case AudioFocusPolicy.A_PAUSE:
                    if (pb != null) pb.pause();
                    break;
                case AudioFocusPolicy.A_PLAY:
                    if (pb != null) pb.play();
                    break;
                case AudioFocusPolicy.A_SET_VOLUME:
                    if (pb != null) pb.setVolume(action.volume);
                    break;
                case AudioFocusPolicy.A_REQUEST_FOCUS:
                    requestAudioFocus();
                    break;
                case AudioFocusPolicy.A_ABANDON_FOCUS:
                    abandonAudioFocus();
                    break;
                case AudioFocusPolicy.A_SCHEDULE_RESUME:
                    scheduleResume(action.delayMs);
                    break;
                case AudioFocusPolicy.A_SCHEDULE_RECHECK:
                    scheduleRecheck(action.delayMs);
                    break;
                case AudioFocusPolicy.A_NOTIFY_FOCUS_DENIED:
                    Log.w(Utils.LOG, "audio focus denied while playing, pause instead");
                    break;
                default:
                    break;
            }
        }
    }

    private boolean receivingNoisyEvents = false;

    private boolean stopWithApp = false;
    // private boolean alwaysPauseOnInterruption = false;
    private String playState = null;

    @SuppressLint("InvalidWakeLockTag")
    public MusicManager(MusicService service) {
        this.service = service;
        this.metadata = new MetadataManager(service, this);
        this.audioManager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);

        PowerManager powerManager = (PowerManager)service.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "track-player-wake-lock");
        wakeLock.setReferenceCounted(false);

        // Android 7: Use the application context here to prevent any memory leaks
        WifiManager wifiManager = (WifiManager)service.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "track-player-wifi-lock");
            wifiLock.setReferenceCounted(false);
        }
    }

    public ExoPlayback getPlayback() {
        return playback;
    }

    // lx-music fork: 音频焦点请求/释放
    private boolean requestAudioFocus() {
        if (!handleAudioFocus) return true; // 用户关掉「音频焦点处理」时完全不接管，也不拦播放
        // 已有焦点时直接返回（v1.9.2-b54）：旧实现每次 onPlay 都重复请求，
        // 焦点栈累积会触发系统下发 LOSS，配合下面的 stop() 会把刚开始的播放打断，
        // 表现为「点播放一直卡在加载态」
        if (hasAudioFocus) return true;
        int result = audioManager.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        );
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (!handleAudioFocus) return;
        if (hasAudioFocus) {
            audioManager.abandonAudioFocus(audioFocusListener);
            hasAudioFocus = false;
        }
    }

    public boolean shouldStopWithApp() {
        return stopWithApp;
    }

    public void setStopWithApp(boolean stopWithApp) {
        this.stopWithApp = stopWithApp;
    }

    // public void setAlwaysPauseOnInterruption(boolean alwaysPauseOnInterruption) {
    //     this.alwaysPauseOnInterruption = alwaysPauseOnInterruption;
    // }

    public MetadataManager getMetadata() {
        return metadata;
    }

    public Handler getHandler() {
        return service.handler;
    }

    public void switchPlayback(ExoPlayback playback) {
        // RT-04：换播放器时旧实例上的续播任务必须撤销，否则会去 play 已销毁的对象
        cancelPendingResume();
        if(this.playback != null) {
            this.playback.stop();
            this.playback.destroy();
        }

        this.playback = playback;

        if(this.playback != null) {
            this.playback.initialize();
        }
    }

    public LocalPlayback createLocalPlayback(Bundle options) {
        boolean autoUpdateMetadata = options.getBoolean("autoUpdateMetadata", true);
        boolean shouldHandleAudioFocus = options.getBoolean("handleAudioFocus", true);
        this.handleAudioFocus = shouldHandleAudioFocus; // lx-music fork: 手动焦点实现按此开关启停
        this.autoResumeAfterInterrupt = options.getBoolean("autoResumeAfterInterrupt", true);
        this.duckOnTransientDuck = options.getBoolean("duckOnTransientDuck", true);
        boolean shouldEnableAudioOffload = options.getBoolean("audioOffload", true);
        int minBuffer = (int)Utils.toMillis(options.getDouble("minBuffer", Utils.toSeconds(DEFAULT_MIN_BUFFER_MS)));
        int maxBuffer = (int)Utils.toMillis(options.getDouble("maxBuffer", Utils.toSeconds(DEFAULT_MAX_BUFFER_MS)));
        int playBuffer = (int)Utils.toMillis(options.getDouble("playBuffer", Utils.toSeconds(DEFAULT_BUFFER_FOR_PLAYBACK_MS)));
        int backBuffer = (int)Utils.toMillis(options.getDouble("backBuffer", Utils.toSeconds(DEFAULT_BACK_BUFFER_DURATION_MS)));
        long cacheMaxSize = (long)(options.getDouble("maxCacheSize", 0) * 1024);
        int multiplier = DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / DEFAULT_BUFFER_FOR_PLAYBACK_MS;

        LoadControl control = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, playBuffer * multiplier)
                .setBackBuffer(backBuffer, false)
                .build();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(service);

        ExoPlayer player = new ExoPlayer.Builder(service, renderersFactory)
                .setLoadControl(control)
                .setWakeMode(WAKE_MODE_NONE)
                .build();

        if (shouldEnableAudioOffload) {
          TrackSelectionParameters trackSelectionParameters = player.getTrackSelectionParameters().buildUpon()
            .setAudioOffloadPreferences(new TrackSelectionParameters.AudioOffloadPreferences.Builder()
              .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            //   .setIsGaplessSupportRequired(true)
              .setIsSpeedChangeSupportRequired(true)
              .build())
            .build();
          player.setTrackSelectionParameters(trackSelectionParameters);
          // player.addAudioOffloadListener(new ExoPlayer.AudioOffloadListener() {
          //     @Override
          //     public void onSleepingForOffloadChanged(boolean isSleepingForOffload) {
          //         Log.d(Utils.LOG, "onSleepingForOffloadChanged: " + isSleepingForOffload);
          //     }

          //     @Override
          //     public void onOffloadedPlayback(boolean isOffloadedPlayback) {
          //         Log.d(Utils.LOG, "onOffloadedPlayback: " + isOffloadedPlayback);
          //     }
          // });
        }

        // player.addAnalyticsListener(new EventLogger(null));

        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), false);
        // lx-music fork: 上面恒传 false——焦点统一由本类 requestAudioFocus/audioFocusListener 管理，
        // 避免 media3 内置 AudioFocusManager 与手动实现双重持有焦点、互相触发 LOSS 打断播放

        return new LocalPlayback(service, this, player, cacheMaxSize, autoUpdateMetadata);
    }

    @SuppressLint("WakelockTimeout")
    public void onPlay() {
        Log.d(Utils.LOG, "onPlay");
        if(playback == null) return;

        Track track = playback.getCurrentTrack();
        if(track == null) return;

        if(!playback.isRemote()) {
            // lx-music fork: 焦点申请失败（通话中/其他 App 持有）时不能「没焦点也照播」
            if (!requestAudioFocus()) dispatch(AudioFocusPolicy.Event.FOCUS_DENIED);

            if(!receivingNoisyEvents) {
                receivingNoisyEvents = true;
                service.registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            }

            if(!wakeLock.isHeld()) wakeLock.acquire();

            if(wifiLock != null && !Utils.isLocal(track.uri)) {
                if(!wifiLock.isHeld()) wifiLock.acquire();
            }
        }

        if (playback.shouldAutoUpdateMetadata())
            metadata.setActive(true);
    }

    public void onPause() {
        Log.d(Utils.LOG, "onPause");

        // lx-music fork: 这里**不注销** noisyReceiver。暂停期间（尤其被焦点打断的暂停）仍要能收到
        // 拔耳机/断蓝牙广播，否则通话中拔线再挂断，音乐会被自动续播从扬声器外放出去。
        // 注销统一放在 onStop / destroy。

        // Release the wake and the wifi locks
        if(wakeLock.isHeld()) wakeLock.release();
        if(wifiLock != null && wifiLock.isHeld()) wifiLock.release();

        if (playback != null && playback.shouldAutoUpdateMetadata())
            metadata.setActive(true);
    }

    public void onStop() {
        Log.d(Utils.LOG, "onStop");

        // Unregisters the noisy receiver
        if(receivingNoisyEvents) {
            service.unregisterReceiver(noisyReceiver);
            receivingNoisyEvents = false;
        }

        // Release the wake and the wifi locks
        if(wakeLock.isHeld()) wakeLock.release();
        if(wifiLock != null && wifiLock.isHeld()) wifiLock.release();

        abandonAudioFocus(); // lx-music fork: 释放音频焦点
        cancelPendingRecheck(); // 兜底复核一并撤销，避免 stopped 态再自己抢焦点

        if (playback != null && playback.shouldAutoUpdateMetadata())
            metadata.setActive(false);
    }

    public void onStateChange(int state) {
        Log.d(Utils.LOG, "onStateChange");

        Bundle bundle = new Bundle();
        bundle.putInt("state", state);
        service.emit(MusicEvents.PLAYBACK_STATE, bundle);
        if (playback != null && playback.shouldAutoUpdateMetadata()) handleUpdateMetadata();
    }

    private void handleUpdateMetadata() {
        int state = playback.getState();
        String playState = Utils.isPlaying(state) ? "playing" : "paused";

        if (playState.equals(this.playState) && !Utils.isPlayingState(state))  return;

        this.playState = playState;

        // if (playback.shouldAutoUpdateMetadata())
        metadata.updatePlayback(playback, Utils.isPlaying(state));
    }

    public void onTrackUpdate(Integer prevIndex, long prevPos, Integer nextIndex, Track next) {
        Log.d(Utils.LOG, "onTrackUpdate");

        if (prevIndex == null && next == null) return;

        if(next != null && playback.shouldAutoUpdateMetadata()) metadata.updateMetadata(playback, next, Utils.isPlaying(playback.getState()));

        Bundle bundle = new Bundle();
        if (prevIndex != null) bundle.putInt("track", prevIndex);
        bundle.putDouble("position", Utils.toSeconds(prevPos));
        if (nextIndex != null) bundle.putInt("nextTrack", nextIndex);
        service.emit(MusicEvents.PLAYBACK_TRACK_CHANGED, bundle);
    }

    public void onReset() {
        metadata.removeNotifications();
    }

    public void onEnd(Integer previousIndex, long prevPos) {
        Log.d(Utils.LOG, "onEnd");

        Bundle bundle = new Bundle();
        if (previousIndex != null) bundle.putInt("track", previousIndex);
        bundle.putDouble("position", Utils.toSeconds(prevPos));
        service.emit(MusicEvents.PLAYBACK_QUEUE_ENDED, bundle);
    }

    public void onMetadataReceived(String source, String title, String url, String artist, String album, String date, String genre) {
        Log.d(Utils.LOG, "onMetadataReceived: " + source);

        Bundle bundle = new Bundle();
        bundle.putString("source", source);
        bundle.putString("title", title);
        bundle.putString("url", url);
        bundle.putString("artist", artist);
        bundle.putString("album", album);
        bundle.putString("date", date);
        bundle.putString("genre", genre);
        service.emit(MusicEvents.PLAYBACK_METADATA, bundle);
    }

    public void onError(String code, String error) {
        Log.d(Utils.LOG, "onError");
        Log.e(Utils.LOG, "Playback error: " + code + " - " + error);

        Bundle bundle = new Bundle();
        bundle.putString("code", code);
        bundle.putString("message", error);
        service.emit(MusicEvents.PLAYBACK_ERROR, bundle);
    }

    public void onAudioFocusChange(boolean permanent, boolean paused, boolean ducking) {
        Bundle bundle = new Bundle();
        bundle.putBoolean("permanent", permanent);
        bundle.putBoolean("paused", paused);
        bundle.putBoolean("ducking", ducking);
        // Log.e(Utils.LOG, "permanent： " + permanent + "  paused: " + paused + "  ducking: " + ducking);
        service.emit(MusicEvents.BUTTON_DUCK, bundle);
    }

    /* @Override
    public void onAudioFocusChange(int focus) {
        Log.d(Utils.LOG, "onDuck");

        boolean permanent = false;
        boolean paused = false;
        boolean ducking = false;

        switch(focus) {
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.d(Utils.LOG, "AUDIOFOCUS_LOSS_TRANSIENT");
            case AudioManager.AUDIOFOCUS_LOSS:
                Log.d(Utils.LOG, "AUDIOFOCUS_LOSS");
                permanent = true;
                abandonFocus();
                paused = true;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.d(Utils.LOG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                if (alwaysPauseOnInterruption)
                    paused = true;
                else
                    ducking = true;
                break;
            default:
                break;
        }

        if (ducking) {
            playback.setVolumeMultiplier(0.5F);
            wasDucking = true;
        } else if (wasDucking) {
            playback.setVolumeMultiplier(1.0F);
            wasDucking = false;
        }

        Bundle bundle = new Bundle();
        bundle.putBoolean("permanent", permanent);
        bundle.putBoolean("paused", paused);
        service.emit(MusicEvents.BUTTON_DUCK, bundle);
    }

    private void requestFocus() {
        if(hasAudioFocus) return;
        Log.d(Utils.LOG, "Requesting audio focus...");

        AudioManager manager = (AudioManager)service.getSystemService(Context.AUDIO_SERVICE);
        int r;

        if(manager == null) {
            r = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        } else if(Build.VERSION.SDK_INT >= 26) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(this)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setWillPauseWhenDucked(alwaysPauseOnInterruption)
                    .build();

            r = manager.requestAudioFocus(focus);
        } else {
            //noinspection deprecation
            r = manager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        hasAudioFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if(!hasAudioFocus) return;
        Log.d(Utils.LOG, "Abandoning audio focus...");

        AudioManager manager = (AudioManager)service.getSystemService(Context.AUDIO_SERVICE);
        int r;

        if(manager == null) {
            r = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        } else if(Build.VERSION.SDK_INT >= 26) {
            r = manager.abandonAudioFocusRequest(focus);
        } else {
            //noinspection deprecation
            r = manager.abandonAudioFocus(this);
        }

        hasAudioFocus = r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }*/

    public void destroy() {
        Log.d(Utils.LOG, "Releasing service resources...");

        // lx-music fork: 销毁前释放焦点并复位策略状态（原来这段是注释掉的，Service 重建后会带脏状态）
        dispatch(AudioFocusPolicy.Event.SERVICE_DESTROY);

        // Stop receiving audio becoming noisy events
        if(receivingNoisyEvents) {
            service.unregisterReceiver(noisyReceiver);
            receivingNoisyEvents = false;
        }

        // Release the playback resources
        if(playback != null) playback.destroy();

        // Release the metadata resources
        metadata.destroy();

        // Release the locks
        if(wakeLock.isHeld()) wakeLock.release();
        if(wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

}
