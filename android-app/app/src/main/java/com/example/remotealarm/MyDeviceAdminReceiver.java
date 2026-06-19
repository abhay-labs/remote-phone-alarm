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

        // Start AlarmService with HIDE_WARNING_OVERLAY to remove floating overlay
        try {
            Intent serviceIntent = new Intent(context, AlarmService.class);
            serviceIntent.setAction("HIDE_WARNING_OVERLAY");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service to hide overlay", e);
        }
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(TAG, "Device Admin disabled");
        Toast.makeText(context, "Device Admin Protection Disabled! ⚠️", Toast.LENGTH_SHORT).show();

        // Start AlarmService with SHOW_WARNING_OVERLAY to draw overlay immediately over the Settings screen
        try {
            Intent serviceIntent = new Intent(context, AlarmService.class);
            serviceIntent.setAction("SHOW_WARNING_OVERLAY");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service to show overlay", e);
        }

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

        // Launch WarningActivity immediately in the background task stack
        launchWarning.run();

        // Launch again at 500ms and 1000ms to guarantee it stays in the background/foreground stack
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(launchWarning, 500);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(launchWarning, 1000);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        Log.w(TAG, "Disable requested");
        
        // Launch DeactivateAuthActivity with a 500ms delay to cover the system settings dialog
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Intent authIntent = new Intent(context, DeactivateAuthActivity.class);
            authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                                Intent.FLAG_ACTIVITY_CLEAR_TASK |
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            try {
                context.startActivity(authIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch DeactivateAuthActivity", e);
            }
        }, 500);

        return "Verification Required! Please enter your love's name in the popup to confirm deactivation.";
    }
}
