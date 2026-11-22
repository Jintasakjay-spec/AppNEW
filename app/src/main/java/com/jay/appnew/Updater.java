package com.jay.appnew;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads the newest AppNEW.apk from GitHub and opens the TV's install screen. */
public final class Updater {
    public static final String APK_URL =
            "https://github.com/Jintasakjay-spec/AppNEW/releases/download/latest/AppNEW.apk";

    public interface Progress { void say(String msg); }

    private Updater() {}

    /** Returns false (and opens the permission screen) if แอปNEW may not install apps yet. */
    public static boolean ensureAllowed(Activity act) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { act.startActivity(i); } catch (Exception ignored) { }
            return false;
        }
        return true;
    }

    /** Runs on a background thread. */
    public static void run(Context ctx, Progress p) {
        HttpURLConnection c = null;
        try {
            p.say("Downloading update...");
            URL url = new URL(APK_URL + "?t=" + System.currentTimeMillis());
            for (int hop = 0; hop < 5; hop++) {
                c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    url = new URL(url, c.getHeaderField("Location"));
                    c.disconnect();
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                break;
            }
            long total = c.getContentLengthLong();

            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(ctx.getPackageName());
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = c.getInputStream();
                     OutputStream out = s.openWrite("AppNEW.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[65536];
                    long done = 0; int n; int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (100 * done / total);
                            if (pct / 10 != lastPct / 10) { lastPct = pct; p.say("Downloading update " + pct + "%"); }
                        }
                    }
                    s.fsync(out);
                }
                Intent cb = new Intent(ctx, InstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getBroadcast(ctx, 3, cb, flags);
                p.say("Installing...");
                s.commit(pend.getIntentSender());
            }
        } catch (Exception e) {
            p.say("Update failed: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Receives the installer's answer and shows the "Install" confirmation screen. */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { context.startActivity(confirm); } catch (Exception ignored) { }
                }
            }
        }
    }
}
