package com.example.remotealarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AlarmService extends Service {
    private static final String TAG = "AlarmService";
    private static final String CHANNEL_ID = "RemoteAlarmChannel";
    private static final int NOTIFICATION_ID = 999;

    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private int originalVolume = -1;
    private int originalRingerMode = -1;
    private int originalInterruptionFilter = -1;

    // Flashlight blinking state
    private boolean isBlinking = false;
    private Thread blinkThread;
    private String cameraId;
    private CameraManager cameraManager;

    private FusedLocationProviderClient fusedLocationClient;
    private OkHttpClient httpClient;

    // Polling and state control
    private boolean isListening = false;
    private boolean isAlarmPlaying = false;
    private final android.os.Handler pollingHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pollingRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        httpClient = new OkHttpClient();
        
        // Initialize Camera Manager for Flashlight
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            if (cameraIds.length > 0) {
                cameraId = cameraIds[0]; // Rear camera
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to access camera for flashlight", e);
        }

        // Initialize Polling Runnable
        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                if (isListening) {
                    pollBackendStatus();
                    pollingHandler.postDelayed(this, 3000); // Poll every 3 seconds
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if ("STOP_ALARM".equals(action)) {
            Log.i(TAG, "Stop action received in service");
            
            // Turn off alarm locally
            handleAlarmStateChange(false, "default");
            
            // Update backend that we stopped it
            sendStopRequestToBackend();
            
            return START_STICKY;
        }

        if ("TRIGGER_ALARM".equals(action)) {
            Log.i(TAG, "Trigger action received in service via FCM");
            String sound = intent != null ? intent.getStringExtra("sound") : "default";
            handleAlarmStateChange(true, sound != null ? sound : "default");
            return START_STICKY;
        }

        Log.i(TAG, "Starting Alarm Service in listening mode...");
        
        if (!isListening) {
            isListening = true;
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification(isAlarmPlaying));
            pollingHandler.post(pollingRunnable);
        }

        return START_STICKY;
    }

    private void playAlarm(String sound) {
        try {
            // Save original volume and ringer mode
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            originalRingerMode = audioManager.getRingerMode();
            
            // Set DND override if permission is granted
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager != null) {
                if (notificationManager.isNotificationPolicyAccessGranted()) {
                    try {
                        originalInterruptionFilter = notificationManager.getCurrentInterruptionFilter();
                        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                        audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                        Log.d(TAG, "DND filter set to ALL and ringer set to NORMAL");
                    } catch (Exception e) {
                        Log.e(TAG, "Error overriding DND / Ringer Mode", e);
                    }
                } else {
                    Log.w(TAG, "Notification Policy Access NOT granted. Cannot override DND.");
                }
            }
            
            // Set volume to maximum
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);
                Log.d(TAG, "Volume set to maximum: " + maxVolume);
            } catch (SecurityException se) {
                Log.e(TAG, "DND is active, failed to set volume without permission override", se);
            }

            // Get custom selected alarm sound Uri or fallback to default
            Uri alertUri = null;
            if (sound != null && (sound.startsWith("http://") || sound.startsWith("https://"))) {
                alertUri = Uri.parse(sound);
            } else if ("spaceship".equalsIgnoreCase(sound)) {
                alertUri = Uri.parse("https://actions.google.com/sounds/v1/alarms/spaceship_alarm.ogg");
            } else if ("digital".equalsIgnoreCase(sound)) {
                alertUri = Uri.parse("https://actions.google.com/sounds/v1/alarms/dosimeter_alarm.ogg");
            } else if ("bell".equalsIgnoreCase(sound)) {
                alertUri = Uri.parse("https://actions.google.com/sounds/v1/alarms/medium_bell_ringing_near.ogg");
            } else if ("bugle".equalsIgnoreCase(sound)) {
                alertUri = Uri.parse("https://actions.google.com/sounds/v1/alarms/bugle_tune.ogg");
            }

            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            }
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, alertUri);
            
            // Set attributes to routing audio to Alarm stream
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            mediaPlayer.setAudioAttributes(attributes);
            
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
            Log.i(TAG, "Alarm MediaPlayer started successfully");

        } catch (IOException e) {
            Log.e(TAG, "Error playing alarm sound", e);
        }
    }

    private void startBlinking() {
        if (cameraId == null) {
            Log.w(TAG, "Flashlight unavailable: Camera ID is null");
            return;
        }

        isBlinking = true;
        blinkThread = new Thread(() -> {
            boolean torchState = false;
            while (isBlinking) {
                try {
                    cameraManager.setTorchMode(cameraId, torchState);
                    torchState = !torchState;
                    Thread.sleep(300); // Blink interval
                } catch (CameraAccessException | IllegalArgumentException e) {
                    Log.e(TAG, "Error toggling flashlight torch mode", e);
                    isBlinking = false;
                } catch (InterruptedException e) {
                    Log.i(TAG, "Blink thread interrupted");
                    break;
                }
            }
            // Ensure flash is off when loop exits
            try {
                cameraManager.setTorchMode(cameraId, false);
            } catch (Exception ignored) {}
        });
        blinkThread.start();
    }

    private void fetchAndSendLocation() {
        try {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            sendLocationToBackend(location.getLatitude(), location.getLongitude(), location.getAccuracy());
                        } else {
                            Log.w(TAG, "Location is null. GPS might be disabled on device.");
                        }
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to get GPS location", e));
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing", e);
        }
    }

    private void sendLocationToBackend(double latitude, double longitude, float accuracy) {
        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "http://10.0.2.2:3000");
        String email = prefs.getString("email", "");
        String adminToken = prefs.getString("admin_token", "");
        String url = baseUrl + "/api/location";

        Log.i(TAG, "Sending location for " + email + " to: " + url);

        String jsonPayload = String.format(
                "{\"email\": \"%s\", \"latitude\": %f, \"longitude\": %f, \"accuracy\": %f}",
                email, latitude, longitude, accuracy
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
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to post location to backend", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.i(TAG, "Location posted successfully to backend!");
                } else {
                    Log.w(TAG, "Backend location update returned error code: " + response.code());
                }
                response.close();
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Remote Alarm Foreground Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            serviceChannel.setDescription("Shows warning during remote alarm triggers");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification buildNotification(boolean isActive) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX);

        if (isActive) {
            // Stop button inside notification
            Intent stopIntent = new Intent(this, AlarmService.class);
            stopIntent.setAction("STOP_ALARM");
            PendingIntent stopPendingIntent = PendingIntent.getService(
                    this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
            );

            builder.setContentTitle("GuardianLink Alarm Triggered")
                    .setContentText("A remote alarm signal has activated your device's siren.")
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISMISS SIREN", stopPendingIntent);
        } else {
            builder.setContentTitle("GuardianLink Listener Active")
                    .setContentText("Listening for remote trigger signals in the background...")
                    .setCategory(NotificationCompat.CATEGORY_SERVICE);
        }

        return builder.build();
    }

    private void pollBackendStatus() {
        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "");
        String email = prefs.getString("email", "");

        if (baseUrl.isEmpty() || email.isEmpty()) {
            return;
        }

        // Remove trailing slash if present
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = baseUrl + "/api/status?email=" + Uri.encode(email);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Polling request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Polling request failed with code: " + response.code());
                    response.close();
                    return;
                }

                String responseBody = response.body().string();
                response.close();

                try {
                    org.json.JSONObject json = new org.json.JSONObject(responseBody);
                    if (json.getBoolean("success")) {
                        org.json.JSONObject data = json.getJSONObject("data");
                        boolean serverAlarmActive = data.getBoolean("alarmActive");
                        String alarmSound = data.optString("alarmSound", "default");

                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            handleAlarmStateChange(serverAlarmActive, alarmSound);
                        });
                    }
                } catch (org.json.JSONException e) {
                    Log.e(TAG, "Error parsing polling response", e);
                }
            }
        });
    }

    private void handleAlarmStateChange(boolean serverAlarmActive, String sound) {
        if (serverAlarmActive && !isAlarmPlaying) {
            Log.i(TAG, "Siren triggered from server command with sound: " + sound);
            isAlarmPlaying = true;
            
            // Play Siren
            playAlarm(sound);
            
            // Start Flashlight
            startBlinking();
            
            // Fetch and Send Location
            fetchAndSendLocation();

            // Update Notification
            updateNotification(true);
        } else if (!serverAlarmActive && isAlarmPlaying) {
            Log.i(TAG, "Siren stopped from server command");
            isAlarmPlaying = false;
            
            // Stop sound
            stopAlarmMedia();

            // Stop Flashlight
            stopFlashlightBlinking();

            // Update Notification
            updateNotification(false);
        }
    }

    private void stopAlarmMedia() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }

        // Restore original volume if available
        if (originalVolume != -1 && audioManager != null) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
            } catch (Exception ignored) {}
        }

        // Restore original interruption filter and ringer mode
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager != null) {
            if (originalInterruptionFilter != -1 && notificationManager.isNotificationPolicyAccessGranted()) {
                try {
                    notificationManager.setInterruptionFilter(originalInterruptionFilter);
                } catch (Exception ignored) {}
            }
        }
        if (originalRingerMode != -1 && audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager != null && notificationManager.isNotificationPolicyAccessGranted()) {
                try {
                    audioManager.setRingerMode(originalRingerMode);
                } catch (Exception ignored) {}
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                try {
                    audioManager.setRingerMode(originalRingerMode);
                } catch (Exception ignored) {}
            }
        }
    }

    private void stopFlashlightBlinking() {
        isBlinking = false;
        if (blinkThread != null) {
            blinkThread.interrupt();
            blinkThread = null;
        }
        try {
            cameraManager.setTorchMode(cameraId, false);
        } catch (Exception ignored) {}
    }

    private void updateNotification(boolean isActive) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            Notification notification = buildNotification(isActive);
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void sendStopRequestToBackend() {
        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "");
        String email = prefs.getString("email", "");
        String adminToken = prefs.getString("admin_token", "");

        if (baseUrl.isEmpty() || email.isEmpty()) {
            return;
        }

        // Remove trailing slash if present
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = baseUrl + "/api/stop";

        Log.i(TAG, "Sending stop alarm request for " + email + " to: " + url);

        String jsonPayload = String.format("{\"email\": \"%s\"}", email);

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
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to send stop command to backend", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.i(TAG, "Backend stop command sent successfully!");
                } else {
                    Log.w(TAG, "Backend stop command returned error: " + response.code());
                }
                response.close();
            }
        });
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Stopping service, cleaning up resources...");
        
        isListening = false;
        pollingHandler.removeCallbacks(pollingRunnable);
        
        // Stop sound and restore volume
        stopAlarmMedia();

        // Stop flashlight blinking
        stopFlashlightBlinking();

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
