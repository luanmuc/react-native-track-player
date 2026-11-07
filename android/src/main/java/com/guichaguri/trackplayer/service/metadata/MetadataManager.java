package com.guichaguri.trackplayer.service.metadata;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.Action;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;
import androidx.palette.graphics.Palette;
import androidx.media3.common.util.UnstableApi;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.facebook.react.views.imagehelper.ResourceDrawableIdHelper;
import com.guichaguri.trackplayer.R;
import com.guichaguri.trackplayer.service.MusicManager;
import com.guichaguri.trackplayer.service.MusicService;
import com.guichaguri.trackplayer.service.Utils;
import com.guichaguri.trackplayer.service.models.Track;
import com.guichaguri.trackplayer.service.models.TrackMetadata;
import com.guichaguri.trackplayer.service.player.ExoPlayback;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Guichaguri
 */
@UnstableApi
public class MetadataManager {

    private final MusicService service;
    private final MusicManager manager;
    private final MediaSessionCompat session;

    private int ratingType = RatingCompat.RATING_NONE;
    private int forwardJumpInterval = 15;
    private int backwardJumpInterval = 15;
    private long actions = 0;
    private long compactActions = 0;
    private CustomTarget<Bitmap> artworkTarget;
    private NotificationCompat.Builder builder;
    private MediaMetadataCompat.Builder prevMetadata = null;
    private Uri prevArtwork = null;
    private Bitmap prevArtResource = null;

    private Action previousAction, rewindAction, playAction, pauseAction, stopAction, forwardAction, nextAction;

    // 桌面歌词按钮状态（lx-music fork）
    private boolean lyricButtonActive = false;
    // 播放模式（lx-music fork）：0=顺序 1=列表循环 2=单曲循环 3=随机
    private int repeatMode = 1;

    @SuppressLint("UnspecifiedImmutableFlag")
    public MetadataManager(MusicService service, MusicManager manager) {
        this.service = service;
        this.manager = manager;

        String channel = Utils.getNotificationChannel(service);
        this.builder = new NotificationCompat.Builder(service, channel);
        this.session = new MediaSessionCompat(service, "TrackPlayer", null, null);

        session.setFlags(MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS);
        session.setCallback(new ButtonEvents(service, manager));

        Context context = service.getApplicationContext();
        String packageName = context.getPackageName();
        Intent openApp = context.getPackageManager().getLaunchIntentForPackage(packageName);

        if (openApp == null) {
            openApp = new Intent();
            openApp.setPackage(packageName);
            openApp.addCategory(Intent.CATEGORY_LAUNCHER);
        }

        // Prevent the app from launching a new instance
        openApp.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // Add the Uri data so apps can identify that it was a notification click
        openApp.setAction(Intent.ACTION_VIEW);
        openApp.setData(Uri.parse("trackplayer://notification.click"));

        // lx-music fork: 与下面两个按钮的 PendingIntent 保持一致，显式声明不可变。
        // 之前只有 CANCEL_CURRENT，targetSdk 升到 31+ 后创建即抛 IllegalArgumentException（建通知直接崩）
        builder.setContentIntent(PendingIntent.getActivity(context, 0, openApp,
                PendingIntent.FLAG_CANCEL_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)));

        builder.setSmallIcon(R.drawable.play);
        builder.setCategory(NotificationCompat.CATEGORY_TRANSPORT);

        // Stops the playback when the notification is swiped away
        builder.setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(service, PlaybackStateCompat.ACTION_STOP));

        // Make it visible in the lockscreen
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
    }

    public MediaSessionCompat getSession() {
        return session;
    }

    /**
     * Updates the metadata options
     * @param options The options
     */
    public void updateOptions(Bundle options) {
        List<Integer> capabilities = options.getIntegerArrayList("capabilities");
        List<Integer> notification = options.getIntegerArrayList("notificationCapabilities");
        List<Integer> compact = options.getIntegerArrayList("compactCapabilities");

        actions = 0;
        compactActions = 0;

        if(capabilities != null) {
            // Create the actions mask
            for(int cap : capabilities) actions |= cap;

            // If there is no notification capabilities defined, we'll show all capabilities available
            if(notification == null) notification = capabilities;

            // Initialize all actions based on the options

            previousAction = createAction(notification, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS, "Previous",
                    getIcon(options, "previousIcon", R.drawable.previous));
            rewindAction = createAction(notification, PlaybackStateCompat.ACTION_REWIND, "Rewind",
                    getIcon(options, "rewindIcon", R.drawable.rewind));
            playAction = createAction(notification, PlaybackStateCompat.ACTION_PLAY, "Play",
                    getIcon(options, "playIcon", R.drawable.play));
            pauseAction = createAction(notification, PlaybackStateCompat.ACTION_PAUSE, "Pause",
                    getIcon(options, "pauseIcon", R.drawable.pause));
            stopAction = createAction(notification, PlaybackStateCompat.ACTION_STOP, "Stop",
                    getIcon(options, "stopIcon", R.drawable.stop));
            forwardAction = createAction(notification, PlaybackStateCompat.ACTION_FAST_FORWARD, "Forward",
                    getIcon(options, "forwardIcon", R.drawable.forward));
            nextAction = createAction(notification, PlaybackStateCompat.ACTION_SKIP_TO_NEXT, "Next",
                    getIcon(options, "nextIcon", R.drawable.next));

            // Update the action mask for the compact view
            if(compact != null) {
                for(int cap : compact) compactActions |= cap;
            }
        }

        // Update the color
        builder.setColor(Utils.getInt(options, "color", NotificationCompat.COLOR_DEFAULT));

        // Update the icon
        builder.setSmallIcon(getIcon(options, "icon", R.drawable.play));

        // Update the jump interval
        forwardJumpInterval = Utils.getInt(options, "forwardJumpInterval", 15);
        backwardJumpInterval = Utils.getInt(options, "backwardJumpInterval", 15);

        // Update the rating type
        ratingType = Utils.getInt(options, "ratingType", RatingCompat.RATING_NONE);
        session.setRatingType(ratingType);

        updateNotification();
    }

    public int getRatingType() {
        return ratingType;
    }

    public int getForwardJumpInterval() {
        return forwardJumpInterval;
    }

    public int getBackwardJumpInterval() {
        return backwardJumpInterval;
    }

    public void removeNotifications() {
        String ns = Context.NOTIFICATION_SERVICE;
        Context context = service.getApplicationContext();
        NotificationManager manager = (NotificationManager) context.getSystemService(ns);
        manager.cancelAll();
    }

    /**
     * Updates the artwork
     * @param bitmap The new artwork
     */
    protected void updateArtwork(Bitmap bitmap) {
        // lx-music fork: 封面异步回调可能在播放器销毁后到达，判空避免 NullPointerException
        ExoPlayback playback = manager.getPlayback();
        if (playback == null) return;
        Track track = playback.getCurrentTrack();
        if(track == null) return;

        MediaMetadataCompat.Builder metadata = track.toMediaMetadata();

        metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
        builder.setLargeIcon(bitmap);

        // lx-music fork: 通知栏颜色跟随专辑封面主色
        try {
            Palette palette = Palette.from(bitmap).generate();
            int color = palette.getDarkVibrantColor(
                palette.getVibrantColor(NotificationCompat.COLOR_DEFAULT));
            if (color != NotificationCompat.COLOR_DEFAULT) {
                builder.setColor(color);
            }
        } catch (Exception e) {
            // 提取失败时保持默认颜色
        }

        session.setMetadata(metadata.build());
        updateNotification();
    }

    public void updateTitles(Bundle data) {
      if (prevMetadata == null) return;
      if (data.containsKey("title")) {
        prevMetadata.putString(METADATA_KEY_TITLE, data.getString("title"));
      }
      if (data.containsKey("artist")) {
        prevMetadata.putString(METADATA_KEY_ARTIST, data.getString("artist"));
      }
      if (data.containsKey("album")) {
        prevMetadata.putString(METADATA_KEY_ALBUM, data.getString("album"));
      }
      if (data.containsKey("lyric")) {
        prevMetadata.putString("android.media.metadata.LYRICS", data.getString("lyric"));
      }
      session.setMetadata(prevMetadata.build());
      // lx-music fork: 播放器可能尚未初始化（启动早期同步歌词/标题时 playback 为 null），判空防崩溃
      ExoPlayback playback = manager.getPlayback();
      if (playback != null) updatePlaybackState(playback);
    }

    /**
     * 更新桌面歌词按钮状态（lx-music fork）
     * @param active true=高亮（歌词开），false=置灰（歌词关）
     */
    public void setLyricButtonActive(boolean active) {
      this.lyricButtonActive = active;
      // lx-music fork: 启动早期 JS 会同步歌词按钮状态，此时 playback 还没创建（null），
      // 直接 deref 会 NullPointerException 把主线程崩掉；状态已记录，等播放器初始化后随通知一起生效
      ExoPlayback playback = manager.getPlayback();
      if (playback == null) return;
      updatePlayback(playback, playback.getState() == 3);
    }

    public void setRepeatMode(int mode) {
      if (this.repeatMode == mode) return;
      this.repeatMode = mode;
      // lx-music fork: 同上——启动时 syncNotificationRepeatMode 会在播放器初始化前到达，
      // 此时 manager.getPlayback() 为 null，必须判空（b68 关开关后打不开的直接原因）
      ExoPlayback playback = manager.getPlayback();
      if (playback == null) return;
      updatePlayback(playback, playback.getState() == 3);
    }

    /**
     * Updates the current track
     * @param track The new track
     */
    public void updateMetadata(ExoPlayback playback, TrackMetadata track, boolean isPlaying) {
        MediaMetadataCompat.Builder metadata = track.toMediaMetadata();
        prevMetadata = metadata;

        RequestManager rm = Glide.with(service.getApplicationContext());
        if(artworkTarget != null) rm.clear(artworkTarget);

        if(track.artwork == null) {
            prevArtwork = null;
            // lx-music fork: 无封面时显示默认占位图
            prevArtResource = android.graphics.BitmapFactory.decodeResource(
                service.getResources(), R.drawable.default_artwork);
            builder.setLargeIcon(prevArtResource);
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, prevArtResource);
        } else if (track.artwork.equals(prevArtwork) && prevArtResource != null) {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, prevArtResource);
            builder.setLargeIcon(prevArtResource);
        } else {
            prevArtwork = track.artwork;
            prevArtResource = null;

            artworkTarget = rm.asBitmap()
                .load(track.artwork)
                .override(512, 512)
                .centerCrop()
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                        prevArtResource = resource;

                        metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, resource);
                        builder.setLargeIcon(resource);

                        session.setMetadata(metadata.build());
                        updateNotification();
                        artworkTarget = null;
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) { }
                });
        }

        builder.setContentTitle(track.title);
        builder.setContentText(track.artist);
        builder.setSubText(track.album);

        session.setMetadata(metadata.build());

        updatePlayback(isPlaying);
        updatePlaybackState(playback);
        updateNotification();
    }

    /**
     * Updates the playback state and notification buttons
     */
    @SuppressLint("RestrictedApi")
    public void updatePlayback(boolean playing) {
        List<Integer> compact = new ArrayList<>();
        builder.mActions.clear();

        // Adds the media buttons to the notification

        addAction(previousAction, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS, compact);
        addAction(rewindAction, PlaybackStateCompat.ACTION_REWIND, compact);

        if(playing) {
            addAction(pauseAction, PlaybackStateCompat.ACTION_PAUSE, compact);
        } else {
            addAction(playAction, PlaybackStateCompat.ACTION_PLAY, compact);
        }

        addAction(forwardAction, PlaybackStateCompat.ACTION_FAST_FORWARD, compact);
        addAction(nextAction, PlaybackStateCompat.ACTION_SKIP_TO_NEXT, compact);

        // Custom: toggle desktop lyric button (lx-music fork)
        int lyricIcon = lyricButtonActive ? R.drawable.lyric : R.drawable.lyric_inactive;
        builder.mActions.add(new Action(
            lyricIcon,
            "词",
            PendingIntent.getService(service, 200,
                new Intent(service, MusicService.class)
                    .setAction(MusicService.ACTION_TOGGLE_DESKTOP_LYRIC),
                PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0))
        ));

        // Custom: playback repeat mode button (lx-music fork)
        int repeatIcon;
        switch (repeatMode) {
            case 0: repeatIcon = R.drawable.notify_mode_order; break;
            case 2: repeatIcon = R.drawable.notify_mode_single; break;
            case 3: repeatIcon = R.drawable.notify_mode_random; break;
            default: repeatIcon = R.drawable.notify_mode_loop; break;
        }
        builder.mActions.add(new Action(
            repeatIcon,
            "播放模式",
            PendingIntent.getService(service, 201,
                new Intent(service, MusicService.class)
                    .setAction(MusicService.ACTION_CYCLE_REPEAT),
                PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0))
        ));

        // Prevent the media style from being used in older Huawei devices that don't support custom styles
        if(!Build.MANUFACTURER.toLowerCase().contains("huawei") || Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            MediaStyle style = new MediaStyle();

            if(playing) {
                style.setShowCancelButton(false);
            } else {
                // Shows the cancel button on pre-lollipop versions due to a bug
                style.setShowCancelButton(true);
                style.setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(service,
                        PlaybackStateCompat.ACTION_STOP));
            }

            // Links the media session
            style.setMediaSession(session.getSessionToken());

            // Updates the compact media buttons for the notification
            if (!compact.isEmpty()) {
                int[] compactIndexes = new int[compact.size()];

                for (int i = 0; i < compact.size(); i++) compactIndexes[i] = compact.get(i);

                style.setShowActionsInCompactView(compactIndexes);
            }

            builder.setStyle(style);

        }

        // updatePlaybackState(playback);
        // updateNotification();
    }

    @SuppressLint("RestrictedApi")
    public void updatePlayback(ExoPlayback playback, boolean playing) {
        if (prevMetadata != null) session.setMetadata(prevMetadata.build());
        updatePlayback(playing);
        updatePlaybackState(playback);
        updateNotification();
    }

    /**
     * Updates the playback state
     * @param playback The player
     */
    private void updatePlaybackState(ExoPlayback playback) {
        // Updates the media session state
        PlaybackStateCompat.Builder pb = new PlaybackStateCompat.Builder();
        pb.setActions(actions);
        pb.setState(playback.getState(), playback.getPosition(), playback.getRate());
        pb.setBufferedPosition(playback.getBufferedPosition());

        session.setPlaybackState(pb.build());
    }

    public void setActive(boolean active) {
        if (session.isActive() == active) return;
        this.session.setActive(active);

        updateNotification();
    }

    public void destroy() {
        service.stopForeground(true);

        session.setActive(false);
        session.release();
    }

    private void updateNotification() {
        // Log.d(Utils.LOG, "updateNotification");
        // Log.e(Utils.LOG, Log.getStackTraceString(new Throwable()));
        if(session.isActive()) {
            service.startForeground(1, builder.build());
        } else {
            service.stopForeground(true);
        }
    }

    private int getIcon(Bundle options, String propertyName, int defaultIcon) {
        if(!options.containsKey(propertyName)) return defaultIcon;

        Bundle bundle = options.getBundle(propertyName);
        if(bundle == null) return defaultIcon;

        ResourceDrawableIdHelper helper = ResourceDrawableIdHelper.getInstance();
        int icon = helper.getResourceDrawableId(service, bundle.getString("uri"));
        if(icon == 0) return defaultIcon;

        return icon;
    }

    private Action createAction(List<Integer> caps, long action, String title, int icon) {
        if(!caps.contains((int)action)) return null;

        return new Action(icon, title, MediaButtonReceiver.buildMediaButtonPendingIntent(service, action));
    }

    @SuppressLint("RestrictedApi")
    private void addAction(Action action, long id, List<Integer> compact) {
        if(action == null) return;

        if((compactActions & id) != 0) compact.add(builder.mActions.size());
        builder.mActions.add(action);
    }

}
