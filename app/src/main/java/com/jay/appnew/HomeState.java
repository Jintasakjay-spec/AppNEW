package com.jay.appnew;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared state between the home screen and the Home-button watcher. */
public final class HomeState {
    private HomeState() {}

    /** True while the user has chosen to look at the TV's original home screen. */
    public static volatile boolean allowStock = false;

    /** Time of the last redirect, to avoid launching twice in a row. */
    public static volatile long lastRedirect = 0;

    private static final String[] KNOWN_STOCK = {
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
            "com.google.android.tungsten.setupwraith"
    };

    /** Package names of the TV's own home screens (everything that answers HOME except this app). */
    public static Set<String> stockPackages(Context ctx) {
        Set<String> out = new HashSet<>();
        for (String p : KNOWN_STOCK) out.add(p);
        out.remove("com.google.android.tungsten.setupwraith");
        PackageManager pm = ctx.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> list = pm.queryIntentActivities(home, 0);
        for (ResolveInfo ri : list) {
            String p = ri.activityInfo.packageName;
            if (!p.equals(ctx.getPackageName()) && !p.startsWith("com.android.settings")
                    && !p.equals("com.android.tv.settings")) {
                out.add(p);
            }
        }
        return out;
    }

    /** First installed stock home package, or null. */
    public static String stockHome(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String p : stockPackages(ctx)) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(p);
            if (pm.resolveActivity(i, 0) != null) return p;
        }
        return null;
    }
}
