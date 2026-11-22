package com.jay.appnew;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Set;

/**
 * Watches which app comes to the front. When the TV's original home screen appears
 * (for example after pressing Home), it brings แอปNEW back instead,
 * unless the user chose "หน้าจอเดิม" on purpose.
 */
public class HomeWatcherService extends AccessibilityService {
    private Set<String> stock;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        stock = HomeState.stockPackages(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        if (stock == null) stock = HomeState.stockPackages(this);

        if (stock.contains(pkg)) {
            if (HomeState.allowStock) return;
            long now = System.currentTimeMillis();
            if (now - HomeState.lastRedirect < 800) return;
            HomeState.lastRedirect = now;
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } else if (pkg.equals(getPackageName())) {
            HomeState.allowStock = false;
        } else if (!pkg.equals("com.android.systemui") && !pkg.startsWith("com.google.android.inputmethod")) {
            // Opened some other app: next time Home is pressed, come back to แอปNEW.
            HomeState.allowStock = false;
        }
    }

    @Override
    public void onInterrupt() {}
}
