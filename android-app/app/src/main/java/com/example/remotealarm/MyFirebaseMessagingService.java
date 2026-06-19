package com.example.remotealarm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.IOException;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MyFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "FCMService";
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "Refreshed FCM Token: " + token);

        // 1. Save token locally in SharedPreferences
        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();

        // 2. Register token with backend server
        registerTokenOnBackend(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.i(TAG, "From: " + remoteMessage.getFrom());

        // Check if message contains data payload
        Map<String, String> data = remoteMessage.getData();
        if (data.size() > 0) {
            Log.d(TAG, "Message data payload: " + data);
            String command = data.get("command");

            if ("TRIGGER_ALARM".equalsIgnoreCase(command)) {
                Log.i(TAG, "Trigger Alarm command received via FCM");
                String sound = data.get("sound");
                triggerAlarmService(sound != null ? sound : "default");
            } else if ("STOP_ALARM".equalsIgnoreCase(command)) {
                Log.i(TAG, "Stop Alarm command received via FCM");
                stopAlarmService();
            } else if ("START_CAMERA".equalsIgnoreCase(command)) {
                Log.i(TAG, "Start Camera command received via FCM");
                String source = data.get("cameraSource");
                startCameraService(source != null ? source : "back");
            } else if ("STOP_CAMERA".equalsIgnoreCase(command)) {
                Log.i(TAG, "Stop Camera command received via FCM");
                stopCameraService();
            } else if ("START_SCREEN_SHARE".equalsIgnoreCase(command)) {
                Log.i(TAG, "Start Screen Share command received via FCM");
                startScreenShareService();
            } else if ("STOP_SCREEN_SHARE".equalsIgnoreCase(command)) {
                Log.i(TAG, "Stop Screen Share command received via FCM");
                stopScreenShareService();
            } else if ("NEW_MESSAGE".equalsIgnoreCase(command) || "DELETE_MESSAGE".equalsIgnoreCase(command) || "CLEAR_ALL_CHATS".equalsIgnoreCase(command)) {
                Log.i(TAG, "Chat command received via FCM: " + command);
                Intent intent = new Intent("com.example.remotealarm.CHAT_UPDATE");
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            }
        }
    }

    private void triggerAlarmService(String sound) {
        Intent serviceIntent = new Intent(this, AlarmService.class);
        serviceIntent.setAction("TRIGGER_ALARM");
        serviceIntent.putExtra("sound", sound);
        
        // Start the service in Foreground
        try {
            ContextCompat.startForegroundService(this, serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AlarmService in foreground. Android battery optimization or permission issues.", e);
        }
    }

    private void stopAlarmService() {
        Intent serviceIntent = new Intent(this, AlarmService.class);
        serviceIntent.setAction("STOP_ALARM");
        startService(serviceIntent);
    }

    private void startCameraService(String source) {
        Intent serviceIntent = new Intent(this, AlarmService.class);
        serviceIntent.setAction("START_CAMERA");
        serviceIntent.putExtra("cameraSource", source);
        try {
            ContextCompat.startForegroundService(this, serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AlarmService for camera in foreground.", e);
        }
    }

    private void stopCameraService() {
        Intent serviceIntent = new Intent(this, AlarmService.class);
        serviceIntent.setAction("STOP_CAMERA");
        startService(serviceIntent);
    }

    private void startScreenShareService() {
        Intent serviceIntent = new Intent(this, AlarmService.class);
        serviceIntent.setAction("START_SCREEN_SHARE");
        try {
            ContextCompat.startForegroundService(this, serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AlarmService for screen share in foreground.", e);
        }
    }

    private void stopScreenShareService() {
        Intent serviceIntent = new Intent(this, AlarmService.class);
        serviceIntent.setAction("STOP_SCREEN_SHARE");
        startService(serviceIntent);
    }

    private void registerTokenOnBackend(String token) {
        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "http://10.0.2.2:3000");
        String email = prefs.getString("email", "");
        String adminToken = prefs.getString("admin_token", "");

        if (baseUrl.isEmpty() || email.isEmpty()) {
            return;
        }

        String url = baseUrl + "/api/register";

        // Collect basic device info
        String model = Build.MODEL;
        int sdkVersion = Build.VERSION.SDK_INT;

        String jsonPayload = String.format(
                "{\"email\": \"%s\", \"token\": \"%s\", \"deviceInfo\": {\"model\": \"%s\", \"sdkVersion\": %d}}",
                email, token, model, sdkVersion
        );

        RequestBody body = RequestBody.create(
                jsonPayload,
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", "Bearer " + adminToken)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to register token with backend: " + url, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.i(TAG, "FCM Token registered with backend successfully!");
                } else {
                    Log.w(TAG, "Backend token registration failed with status: " + response.code());
                }
                response.close();
            }
        });
    }
}
