package com.jay.appnew;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers what you watch. While a streaming app plays, Android shares "now playing"
 * (title, picture, position) with apps that have Notification access. We save the last
 * few titles so the home screen can show them under Continue watching.
 */
public class MediaWatcher extends NotificationListenerService {
    /** Apps we remember. YouTube is left out on purpose (every clip would fill the row). */
    static final Set<String> TRACKED = new HashSet<>();
    static {
        TRACKED.add("com.netflix.ninja");
        TRACKED.add("com.disney.disneyplus");
        TRACKED.add("in.startv.hotstar.dplus.tv");
        TRACKED.add("com.wbd.stream");
        TRACKED.add("com.hbo.asia.androidtv");
        TRACKED.add("com.amazon.amazonvideo.livingroom");
        TRACKED.add("com.iqiyi.i18n.tv");
    }
    static final String PREFS = "history";
    static final int KEEP = 12;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    private MediaSessionManager msm;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::attach;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            for (MediaController c : new ArrayList<>(callbacks.keySet())) save(c);
            h.postDelayed(this, 20000);
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaWatcher.class);
            msm.addOnActiveSessionsChangedListener(sessionsChanged, me);
            attach(msm.getActiveSessions(me));
            h.postDelayed(tick, 20000);
        } catch (Exception ignored) { }
    }

    @Override
    public void onListenerDisconnected() {
        h.removeCallbacks(tick);
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsChanged); } catch (Exception ignored) { }
        detachAll();
        super.onListenerDisconnected();
    }

    private void detachAll() {
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try { e.getKey().unregisterCallback(e.getValue()); } catch (Exception ignored) { }
        }
        callbacks.clear();
    }

    private void attach(List<MediaController> list) {
        for (MediaController c : new ArrayList<>(callbacks.keySet())) save(c);
        detachAll();
        if (list == null) return;
        for (final MediaController c : list) {
            if (!TRACKED.contains(c.getPackageName())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) { save(c); }
                @Override public void onPlaybackStateChanged(PlaybackState s) { save(c); }
                @Override public void onSessionDestroyed() { save(c); }
            };
            c.registerCallback(cb, h);
            callbacks.put(c, cb);
            save(c);
        }
    }

    private static String pick(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private void save(MediaController c) {
        try {
            MediaMetadata m = c.getMetadata();
            if (m == null) return;
            String title = pick(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) return;
            String sub = pick(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM);
            long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long pos = -1;
            PlaybackState st = c.getPlaybackState();
            if (st != null) {
                pos = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                }
            }
            String pkg = c.getPackageName();
            String key = pkg + "|" + title;
            String id = Integer.toHexString(key.hashCode());

            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            String artUri = pick(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (art != null) {
                File f = new File(getFilesDir(), "art_" + id + ".jpg");
                int w = Math.min(640, art.getWidth()), hh = Math.max(1, art.getHeight() * w / Math.max(1, art.getWidth()));
                try (FileOutputStream out = new FileOutputStream(f)) {
                    Bitmap.createScaledBitmap(art, w, hh, true).compress(Bitmap.CompressFormat.JPEG, 85, out);
                }
            }

            JSONObject o = new JSONObject();
            o.put("key", key); o.put("id", id); o.put("pkg", pkg); o.put("title", title);
            o.put("sub", sub == null ? "" : sub);
            o.put("pos", pos); o.put("dur", dur);
            o.put("artUri", artUri == null ? "" : artUri);
            o.put("time", System.currentTimeMillis());
            store(this, o);
        } catch (Exception ignored) { }
    }

    static synchronized void store(Context ctx, JSONObject item) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONArray old = new JSONArray(sp.getString("items", "[]"));
        JSONArray out = new JSONArray();
        out.put(item);
        for (int i = 0; i < old.length() && out.length() < KEEP; i++) {
            JSONObject o = old.getJSONObject(i);
            if (!o.optString("key").equals(item.optString("key"))) out.put(o);
        }
        sp.edit().putString("items", out.toString()).apply();
    }

    static synchronized JSONArray load(Context ctx) {
        try {
            return new JSONArray(ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString("items", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void remove(Context ctx, String key) {
        try {
            JSONArray old = load(ctx), out = new JSONArray();
            for (int i = 0; i < old.length(); i++) if (!old.getJSONObject(i).optString("key").equals(key)) out.put(old.get(i));
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("items", out.toString()).apply();
        } catch (Exception ignored) { }
    }
}
