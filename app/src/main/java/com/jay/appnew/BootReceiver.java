package com.jay.appnew;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens แอปNEW when the TV starts. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
        } catch (Exception ignored) {
            // Some TVs block starting apps at boot; the Home watcher still brings it up.
        }
    }
}
