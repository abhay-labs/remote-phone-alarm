package com.example.remotealarm;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

public class MyDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "MyDeviceAdminReceiver";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device Admin enabled");
        Toast.makeText(context, "Device Admin Protection Enabled! 🛡️", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(TAG, "Device Admin disabled");
        Toast.makeText(context, "Device Admin Protection Disabled! ⚠️", Toast.LENGTH_SHORT).show();

        // Helper to launch WarningActivity with correct flags
        Runnable launchWarning = () -> {
            Intent warningIntent = new Intent(context, WarningActivity.class);
            warningIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                                 Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | 
                                 Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                context.startActivity(warningIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch WarningActivity", e);
            }
        };

        // Launch immediately
        launchWarning.run();

        // Launch again at 500ms and 1000ms to guarantee it pops up within 1 second 
        // regardless of settings activity transitions and foreground window changes.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(launchWarning, 500);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(launchWarning, 1000);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        Log.w(TAG, "Disable requested");
        return "Disabling this permission will prevent remote device locking and anti-theft security features. Are you sure?";
    }
}
