package com.guichaguri.trackplayer.service;

import android.os.Binder;
import android.os.Bundle;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.media3.common.util.UnstableApi;

import com.facebook.react.bridge.Promise;
import com.guichaguri.trackplayer.service.metadata.MetadataManager;
import com.guichaguri.trackplayer.service.models.NowPlayingMetadata;
import com.guichaguri.trackplayer.service.player.ExoPlayback;

/**
 * @author Guichaguri
 */
@UnstableApi
public class MusicBinder extends Binder {

    private final MusicService service;
    private final MusicManager manager;

    public MusicBinder(MusicService service, MusicManager manager) {
        this.service = service;
        this.manager = manager;
    }

    public void post(Runnable r) {
        service.handler.post(r);
    }

    public ExoPlayback getPlayback() {
        ExoPlayback playback = manager.getPlayback();

        // TODO remove?
        if(playback == null) {
            playback = manager.createLocalPlayback(new Bundle());
            manager.switchPlayback(playback);
        }

        return playback;
    }

    public void setupPlayer(Bundle bundle, Promise promise) {
        manager.switchPlayback(manager.createLocalPlayback(bundle));
        promise.resolve(null);
    }

    public void updateOptions(Bundle bundle) {
        manager.setStopWithApp(bundle.getBoolean("stopWithApp", false));
        // manager.setAlwaysPauseOnInterruption(bundle.getBoolean("alwaysPauseOnInterruption", false));
        manager.getMetadata().updateOptions(bundle);
        // RT-03：三个焦点开关原来只在 setupPlayer 读一次，这里补运行时更新通道
        // （只覆盖 bundle 里显式给出的键，见 MusicManager.updateFocusOptions）
        manager.updateFocusOptions(bundle);
    }

    public void updateNowPlayingMetadata(NowPlayingMetadata nowPlaying, boolean isPlaying) {
        MetadataManager metadata = manager.getMetadata();

        // TODO elapsedTime
        metadata.updateMetadata(getPlayback(), nowPlaying, isPlaying);
        metadata.setActive(true);
    }

    public void clearNowPlayingMetadata() {
        manager.getMetadata().setActive(false);
    }

    public void updateNowPlayingTitles(Bundle data) {
      MetadataManager metadata = manager.getMetadata();
      metadata.updateTitles(data);
      MediaSessionCompat session = metadata.getSession();
      if (session.isActive()) return;
      session.setActive(true);
    }

    public void setLyricButtonActive(boolean active) {
      manager.getMetadata().setLyricButtonActive(active);
    }

    public void setRepeatMode(int mode) {
      manager.getMetadata().setRepeatMode(mode);
    }

    // lx-music fork: JS 暂停统一走 manager.pauseByUser()，
    // 让焦点打断逻辑知道这次暂停是用户按的（否则打断结束后会自动续播）
    public void pauseByUser() {
      manager.pauseByUser();
    }

    // RT-04：让上层（reset / stop）能撤销已排队的续播
    public void cancelPendingResume() {
        manager.cancelPendingResume();
    }

    // RT-05：复位焦点策略并恢复被压低的音量
    public void resetFocus() {
        manager.resetFocus();
    }

    // lx-music fork: 用户改音量统一走 manager.setUserVolume()，让策略知道音量已被用户接管
    public void setUserVolume(float volume) {
        manager.setUserVolume(volume);
    }

    public int getRatingType() {
        return manager.getMetadata().getRatingType();
    }

    public void destroy() {
        service.destroy();
        service.stopSelf();
    }

}
