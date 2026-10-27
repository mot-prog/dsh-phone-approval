package com.dsh.phoneapproval;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Restarts the relay service after a phone reboot when already configured. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences("dsh", Context.MODE_PRIVATE);
        if (prefs.getString("server", "").isEmpty() || prefs.getString("token", "").isEmpty()) return;
        try {
            Intent service = new Intent(context, ApprovalService.class)
                    .setAction(ApprovalService.ACTION_START);
            context.startForegroundService(service);
        } catch (Exception ignored) {
            // Android may forbid FGS start on some OEMs; user can open the app instead
        }
    }
}
