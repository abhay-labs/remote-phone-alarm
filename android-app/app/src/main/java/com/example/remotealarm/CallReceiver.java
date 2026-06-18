package com.example.remotealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.util.Log;

public class CallReceiver extends BroadcastReceiver {
    private static final String TAG = "CallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            Log.d(TAG, "Phone state changed: " + state);

            SharedPreferences prefs = context.getSharedPreferences("RemoteAlarmPrefs", Context.MODE_PRIVATE);

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                Log.d(TAG, "Incoming call from: " + incomingNumber);
                if (incomingNumber != null && !incomingNumber.isEmpty()) {
                    prefs.edit().putString("last_incoming_number", incomingNumber).apply();
                }
            }

            // Forward to the foreground service
            Intent serviceIntent = new Intent(context, AlarmService.class);
            serviceIntent.setAction("CALL_STATE_CHANGED");
            serviceIntent.putExtra("state", state);

            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (incomingNumber != null && !incomingNumber.isEmpty()) {
                serviceIntent.putExtra("incoming_number", incomingNumber);
            } else {
                String savedNumber = prefs.getString("last_incoming_number", "");
                if (!savedNumber.isEmpty()) {
                    serviceIntent.putExtra("incoming_number", savedNumber);
                }
            }

            try {
                context.startService(serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service / deliver call state intent: " + e.getMessage());
            }
        }
    }
}
