package com.jay.appnew;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Functions the home screen page (index.html) can call through window.Android. */
public class Bridge {
    private final Activity act;
    private final WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Map<String, String> iconCache = new HashMap<>();
    private final List<Intent> watchNextIntents = new ArrayList<>();

    Bridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    void shutdown() {
        pool.shutdownNow();
    }

    private void js(final String code) {
        web.post(() -> web.evaluateJavascript(code, null));
    }

    // ---------- apps ----------

    /** All apps that can be opened from a TV home screen, with icons as data URLs. */
    @JavascriptInterface
    public String getApps() {
        PackageManager pm = act.getPackageManager();
        Set<String> seen = new HashSet<>();
        JSONArray arr = new JSONArray();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            for (ResolveInfo ri : list) {
                String p = ri.activityInfo.packageName;
                if (p.equals(act.getPackageName()) || seen.contains(p)) continue;
                seen.add(p);
                try {
                    JSONObject o = new JSONObject();
                    o.put("pkg", p);
                    o.put("label", ri.loadLabel(pm).toString());
                    o.put("icon", iconFor(p));
                    o.put("tv", Intent.CATEGORY_LEANBACK_LAUNCHER.equals(cat));
                    arr.put(o);
                } catch (Exception ignored) { }
            }
        }
        return arr.toString();
    }

    @JavascriptInterface
    public boolean isInstalled(String pkg) {
        try {
            act.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String getIcon(String pkg) {
        return iconFor(pkg);
    }

    private String iconFor(String pkg) {
        synchronized (iconCache) {
            if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
        }
        String out = "";
        try {
            PackageManager pm = act.getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap b = toBitmap(d, 160, 160);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            out = "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) { }
        synchronized (iconCache) {
            iconCache.put(pkg, out);
        }
        return out;
    }

    private static Bitmap toBitmap(Drawable d, int w, int h) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), w, h, true);
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    /** The app's wide TV banner picture (like a poster), or "" if it has none. */
    @JavascriptInterface
    public String getBanner(String pkg) {
        try {
            PackageManager pm = act.getPackageManager();
            Drawable d = null;
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (li != null) d = pm.getActivityBanner(li);
            if (d == null) d = pm.getApplicationBanner(pkg);
            if (d == null) return "";
            int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 320;
            int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 180;
            float k = 640f / w;
            Bitmap b = toBitmap(d, 640, Math.max(1, Math.round(h * k)));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** Downloads and installs the newest แอปNEW from GitHub. Progress goes to window.onUpdate(text). */
    @JavascriptInterface
    public void updateApp() {
        act.runOnUiThread(() -> {
            if (!Updater.ensureAllowed(act)) {
                js("window.onUpdate && window.onUpdate('Turn on the switch for แอปNEW, then press Update again')");
                return;
            }
            pool.execute(() -> Updater.run(act, msg ->
                    js("window.onUpdate && window.onUpdate(" + JSONObject.quote(msg) + ")")));
        });
    }

    @JavascriptInterface
    public String version() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- Continue watching from what you played (needs Notification access) ----------

    @JavascriptInterface
    public boolean isWatchAccessOn() {
        String list = Settings.Secure.getString(act.getContentResolver(), "enabled_notification_listeners");
        return list != null && list.contains(act.getPackageName() + "/");
    }

    @JavascriptInterface
    public void openWatchAccess() {
        act.runOnUiThread(() -> {
            Intent[] tries = {
                    new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                                    new ComponentName(act, MediaWatcher.class).flattenToString()),
                    new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                    new Intent().setComponent(new ComponentName("com.android.tv.settings",
                            "com.android.tv.settings.device.apps.specialaccess.NotificationAccessActivity")),
                    new Intent().setComponent(new ComponentName("com.android.tv.settings",
                            "com.android.tv.settings.device.apps.specialaccess.SpecialAppAccessActivity")),
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName()))
            };
            for (Intent i : tries) if (tryStart(i)) return;
            js("window.onUpdate && window.onUpdate('Notification access screen not found on this TV')");
        });
    }

    /** Titles you watched recently, newest first, with picture as a data URL. */
    @JavascriptInterface
    public String getHistory() {
        JSONArray in = MediaWatcher.load(act), out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            try {
                JSONObject o = in.getJSONObject(i);
                long pos = o.optLong("pos"), dur = o.optLong("dur");
                if (dur > 0 && pos > dur * 0.96) continue; // finished
                File f = new File(act.getFilesDir(), "art_" + o.optString("id") + ".jpg");
                String img = "";
                if (f.exists()) {
                    byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                    img = "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP);
                } else if (o.optString("artUri").startsWith("http")) {
                    img = o.optString("artUri");
                }
                o.put("img", img);
                out.put(o);
            } catch (Exception ignored) { }
        }
        return out.toString();
    }

    @JavascriptInterface
    public void forget(String key) {
        MediaWatcher.remove(act, key);
    }

    /** Opens the Play Store page so a missing app can be installed. */
    @JavascriptInterface
    public void openStore(String pkg) {
        act.runOnUiThread(() -> {
            if (!tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)))) {
                tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
            }
        });
    }

    @JavascriptInterface
    public void launch(String pkg) {
        act.runOnUiThread(() -> {
            PackageManager pm = act.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                act.startActivity(i);
            } catch (Exception ignored) { }
        });
    }

    // ---------- system buttons ----------

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(act.getPackageManager()) == null) return false;
            act.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void openSettings() {
        act.runOnUiThread(() -> tryStart(new Intent(Settings.ACTION_SETTINGS)));
    }

    @JavascriptInterface
    public void openWifi() {
        act.runOnUiThread(() -> {
            if (!tryStart(new Intent(Settings.ACTION_WIFI_SETTINGS))) tryStart(new Intent(Settings.ACTION_SETTINGS));
        });
    }

    /** Opens the TV's input (HDMI) picker. Different TVs use different screens, so try several. */
    @JavascriptInterface
    public void openInputs() {
        act.runOnUiThread(() -> {
            Intent[] tries = {
                    new Intent("com.android.tv.action.VIEW_INPUTS"),
                    new Intent("android.settings.TV_INPUT_SETTINGS"),
                    new Intent().setComponent(new ComponentName("com.android.tv.settings",
                            "com.android.tv.settings.device.display.inputs.InputsActivity")),
                    new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(null)),
                    new Intent(Settings.ACTION_SETTINGS)
            };
            for (Intent i : tries) if (tryStart(i)) return;
        });
    }

    /** Shows the TV's original home screen; Home will not pull back to แอปNEW until another app is opened. */
    @JavascriptInterface
    public void openOriginal() {
        act.runOnUiThread(() -> {
            String p = HomeState.stockHome(act);
            if (p == null) return;
            HomeState.allowStock = true;
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(p);
            if (!tryStart(i)) HomeState.allowStock = false;
        });
    }

    @JavascriptInterface
    public boolean isHomeWatcherOn() {
        String list = Settings.Secure.getString(act.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return false;
        String me = act.getPackageName() + "/";
        for (String s : list.split(":")) if (s.startsWith(me)) return true;
        return false;
    }

    @JavascriptInterface
    public void openAccessibility() {
        act.runOnUiThread(() -> tryStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }

    // ---------- system stats ----------

    private long[] lastCpu = null;

    @JavascriptInterface
    public String getStats() {
        JSONObject o = new JSONObject();
        try {
            ActivityManager am = (ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            o.put("ramTotal", mi.totalMem);
            o.put("ramUsed", mi.totalMem - mi.availMem);

            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            long total = st.getTotalBytes();
            o.put("storTotal", total);
            o.put("storUsed", total - st.getAvailableBytes());

            o.put("cpu", cpuPercent());
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** CPU use in percent. Uses /proc/stat when allowed, otherwise current clock speed versus maximum. */
    private int cpuPercent() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String[] p = r.readLine().trim().split("\\s+");
            long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
            long all = 0;
            for (int i = 1; i < p.length; i++) all += Long.parseLong(p[i]);
            long[] now = {all, idle};
            int pct = -1;
            if (lastCpu != null && now[0] > lastCpu[0]) {
                pct = (int) (100 * (1 - (double) (now[1] - lastCpu[1]) / (now[0] - lastCpu[0])));
            }
            lastCpu = now;
            if (pct >= 0) return pct;
        } catch (Exception ignored) { }
        try {
            long cur = 0, max = 0;
            for (int i = 0; i < 16; i++) {
                File f = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
                File m = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
                if (!f.exists()) continue;
                cur += readLong(f);
                max += readLong(m);
            }
            if (max > 0) return (int) (100 * cur / max);
        } catch (Exception ignored) { }
        return -1;
    }

    private static long readLong(File f) throws Exception {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return Long.parseLong(r.readLine().trim());
        }
    }

    // ---------- weather (Open-Meteo, free, no key) ----------

    @JavascriptInterface
    public void requestWeather(final double lat, final double lon) {
        pool.execute(() -> {
            try {
                String w = httpGet("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&hourly=temperature_2m,precipitation_probability,weather_code,is_day"
                        + "&timezone=Asia%2FBangkok&forecast_days=2");
                String a = "{}";
                try {
                    a = httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat
                            + "&longitude=" + lon + "&current=pm2_5&timezone=Asia%2FBangkok");
                } catch (Exception ignored) { }
                JSONObject o = new JSONObject();
                o.put("weather", new JSONObject(w));
                o.put("air", new JSONObject(a));
                js("window.onWeather && window.onWeather(" + JSONObject.quote(o.toString()) + ")");
            } catch (Exception e) {
                js("window.onWeather && window.onWeather(null)");
            }
        });
    }

    private static String httpGet(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ---------- continue watching (Android TV "Watch Next") ----------

    /** Returns what apps have put in the TV's Continue watching list. Empty if the TV does not allow reading it. */
    @JavascriptInterface
    public String getWatchNext() {
        JSONArray arr = new JSONArray();
        synchronized (watchNextIntents) {
            watchNextIntents.clear();
        }
        Cursor c = null;
        try {
            c = act.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null,
                    TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC");
            if (c == null) return arr.toString();
            int n = 0;
            while (c.moveToNext() && n < 8) {
                String title = str(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
                String intentUri = str(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                if (title == null || intentUri == null) continue;
                JSONObject o = new JSONObject();
                o.put("title", title);
                o.put("season", str(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER));
                o.put("episode", str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER));
                o.put("pkg", str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME));
                long pos = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                long dur = lng(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                o.put("pos", pos);
                o.put("dur", dur);
                o.put("poster", posterData(str(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI)));
                Intent i = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                synchronized (watchNextIntents) {
                    watchNextIntents.add(i);
                    o.put("index", watchNextIntents.size() - 1);
                }
                arr.put(o);
                n++;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return arr.toString();
    }

    @JavascriptInterface
    public void openWatchNext(int index) {
        act.runOnUiThread(() -> {
            Intent i;
            synchronized (watchNextIntents) {
                if (index < 0 || index >= watchNextIntents.size()) return;
                i = watchNextIntents.get(index);
            }
            tryStart(i);
        });
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private String posterData(String uri) {
        if (uri == null) return "";
        if (uri.startsWith("http")) return uri;
        try (InputStream in = act.getContentResolver().openInputStream(Uri.parse(uri))) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b == null) return "";
            int w = 480, h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }
}
