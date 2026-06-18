package com.example.remotealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.os.Build;

public class CallReceiver extends BroadcastReceiver {
    private static final String TAG = "CallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        SharedPreferences prefs = context.getSharedPreferences("RemoteAlarmPrefs", Context.MODE_PRIVATE);

        if (Intent.ACTION_NEW_OUTGOING_CALL.equals(intent.getAction())) {
            String outgoingNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            Log.d(TAG, "Outgoing call detected to: " + outgoingNumber);
            if (outgoingNumber != null && !outgoingNumber.isEmpty()) {
                prefs.edit()
                    .putString("last_outgoing_number", outgoingNumber)
                    .putBoolean("is_incoming", false)
                    .apply();
            }
        } else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            Log.d(TAG, "Phone state changed: " + state);

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                Log.d(TAG, "Incoming call ringing from: " + incomingNumber);
                if (incomingNumber != null && !incomingNumber.isEmpty()) {
                    prefs.edit()
                        .putString("last_incoming_number", incomingNumber)
                        .putBoolean("is_incoming", true)
                        .apply();
                } else {
                    prefs.edit()
                        .putBoolean("is_incoming", true)
                        .apply();
                }
            }

            // Forward to the foreground service
            Intent serviceIntent = new Intent(context, AlarmService.class);
            serviceIntent.setAction("CALL_STATE_CHANGED");
            serviceIntent.putExtra("state", state);

            if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                boolean isIncoming = prefs.getBoolean("is_incoming", false);
                String number = "Unknown";
                if (isIncoming) {
                    number = prefs.getString("last_incoming_number", "Unknown Incoming");
                } else {
                    number = prefs.getString("last_outgoing_number", "Unknown Outgoing");
                }
                serviceIntent.putExtra("incoming_number", number);
            } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                // Reset flag for future calls
                prefs.edit().putBoolean("is_incoming", false).apply();
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service / deliver call state intent: " + e.getMessage());
            }
        }
    }
}
