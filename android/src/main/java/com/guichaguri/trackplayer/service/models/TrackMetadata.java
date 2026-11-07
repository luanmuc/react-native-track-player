package com.guichaguri.trackplayer.service.models;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART_URI;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DATE;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_GENRE;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_RATING;
import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;

import androidx.media3.common.util.UnstableApi;

import com.guichaguri.trackplayer.service.Utils;

@UnstableApi
public abstract class TrackMetadata {
    public Uri artwork;

    public String title;
    public String artist;
    public String album;
    public String date;
    public String genre;
    public long duration;
    public String lyric;
    // lx-music fork: 音源名（如「树音」）与音质（如「FLAC」），由 JS 传入，
    // 在 toMediaMetadata() 里拼到艺术家行尾部；上游没有这两个字段，升级时必须保留
    public String quality;
    public String subSource;

    public RatingCompat rating;

    public void setMetadata(Context context, Bundle bundle, int ratingType) {
      if (bundle.containsKey("artwork")) {
        artwork = Utils.getUri(context, bundle, "artwork");
      }

      if (bundle.containsKey("title")) {
        title = bundle.getString("title");
      }

      if (bundle.containsKey("artist")) {
        artist = bundle.getString("artist");
      }

      if (bundle.containsKey("album")) {
        album = bundle.getString("album");
      }

      if (bundle.containsKey("date")) {
        date = bundle.getString("date");
      }

      if (bundle.containsKey("genre")) {
        genre = bundle.getString("genre");
      }

      if (bundle.containsKey("duration")) {
        duration = Utils.toMillis(bundle.getDouble("duration", 0));
      }

      if (bundle.containsKey("lyric")) {
        lyric = bundle.getString("lyric");
      }

      if (bundle.containsKey("quality")) {
        quality = bundle.getString("quality");
      }

      if (bundle.containsKey("subSource")) {
        subSource = bundle.getString("subSource");
      }

      if (bundle.containsKey("rating")) {
        rating = Utils.getRating(bundle, "rating", ratingType);
      }
    }

    public MediaMetadataCompat.Builder toMediaMetadata() {
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();

        // 标题保持歌名本身，不再挂音质标签（标题：黄昏）
        builder.putString(METADATA_KEY_TITLE, title);

        // 艺术家行依次拼接音源与音质：周传雄 · 树音 · FLAC
        // artist 为空时不拼；音源/音质缺哪项就自动跳过，不会出现连续的分隔符
        String displayArtist = artist;
        if (artist != null && !artist.isEmpty()) {
            StringBuilder sb = new StringBuilder(artist);
            if (subSource != null && !subSource.isEmpty()) sb.append(" · ").append(subSource);
            if (quality != null && !quality.isEmpty()) sb.append(" · ").append(quality);
            displayArtist = sb.toString();
        }
        builder.putString(METADATA_KEY_ARTIST, displayArtist);
        builder.putString(METADATA_KEY_ALBUM, album);
        builder.putString(METADATA_KEY_DATE, date);
        builder.putString(METADATA_KEY_GENRE, genre);

        if (lyric != null) {
            builder.putString("android.media.metadata.LYRICS", lyric);
        }

        if (duration > 0) {
            builder.putLong(METADATA_KEY_DURATION, duration);
        }

        if (artwork != null) {
            builder.putString(METADATA_KEY_ART_URI, artwork.toString());
        }

        if (rating != null) {
            builder.putRating(METADATA_KEY_RATING, rating);
        }

        return builder;
    }

}
