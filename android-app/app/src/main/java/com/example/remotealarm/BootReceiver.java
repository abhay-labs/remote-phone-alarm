package com.example.remotealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "BootReceiver received action: " + action);
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.example.remotealarm.RESTART_SERVICE".equals(action)) {
            
            Log.i(TAG, "Attempting to start AlarmService in the foreground...");
            Intent serviceIntent = new Intent(context, AlarmService.class);
            try {
                ContextCompat.startForegroundService(context, serviceIntent);
                Log.i(TAG, "AlarmService started successfully from BootReceiver.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start AlarmService from BootReceiver: " + e.getMessage(), e);
            }
        }
    }
}
