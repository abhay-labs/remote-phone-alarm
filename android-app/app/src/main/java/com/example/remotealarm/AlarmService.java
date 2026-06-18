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
import android.telephony.TelephonyManager;

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

import android.os.HandlerThread;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.media.ImageReader;
import android.media.Image;
import android.graphics.ImageFormat;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Arrays;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import android.Manifest;

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

    // Camera streaming state and session fields
    private boolean isStreamingCamera = false;
    private HandlerThread cameraBackgroundThread;
    private android.os.Handler cameraBackgroundHandler;
    private CameraDevice streamingCameraDevice;
    private CameraCaptureSession streamingCaptureSession;
    private ImageReader streamingImageReader;
    private long lastFrameTime = 0;
    private long lastRemoteUploadTime = 0;
    private static final long FRAME_INTERVAL_MS = 66; // Max ~15 frames per second (smooth stream)
    private static final long REMOTE_UPLOAD_INTERVAL_MS = 1000; // Remote fallback: 1 FPS to prevent network congestion
    private String activeStreamingCameraId = null;
    private boolean isUploadingFrame = false;
    private LocalStreamServer localStreamServer;

    // Screen mirroring state
    public static boolean hasProjectionToken = false;
    public static int pendingResultCode = 0;
    public static Intent pendingIntentData = null;
    private boolean isScreenSharing = false;
    private android.media.projection.MediaProjection mediaProjection;
    private android.hardware.display.VirtualDisplay virtualDisplay;
    private ImageReader screenImageReader;
    private boolean isUploadingScreenFrame = false;
    private long lastScreenFrameTime = 0;
    private static final long SCREEN_FRAME_INTERVAL_MS = 100; // max 10 FPS for screen mirroring
    private long lastRemoteScreenUploadTime = 0;

    // Call recording state
    private android.media.MediaRecorder callRecorder;
    private boolean isRecordingCall = false;
    private String currentCallNumber = "Unknown";
    private String callRecordingFilePath = null;
    private long callStartTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogger.init(getApplicationContext());
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        httpClient = new OkHttpClient();
        
        localStreamServer = new LocalStreamServer();
        localStreamServer.start(8085);
        
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

        Log.i(TAG, "onStartCommand received action: " + action);

        // Ensure service is in foreground listening state first
        if (!isListening) {
            isListening = true;
            createNotificationChannel();
            updateServiceForegroundState();
            pollingHandler.post(pollingRunnable);
        }

        if ("CALL_STATE_CHANGED".equals(action)) {
            String state = intent.getStringExtra("state");
            String incomingNumber = intent.getStringExtra("incoming_number");
            handleCallStateChanged(state, incomingNumber);
        } else if ("STOP_ALARM".equals(action)) {
            Log.i(TAG, "Stop action received in service");
            
            // Turn off alarm locally
            handleAlarmStateChange(false, "default");
            
            // Update backend that we stopped it
            sendStopRequestToBackend();
        } else if ("TRIGGER_ALARM".equals(action)) {
            Log.i(TAG, "Trigger action received in service via FCM");
            String sound = intent != null ? intent.getStringExtra("sound") : "default";
            handleAlarmStateChange(true, sound != null ? sound : "default");
        } else if ("START_CAMERA".equals(action)) {
            Log.i(TAG, "Start camera action received in service via FCM");
            String cameraSource = intent != null ? intent.getStringExtra("cameraSource") : "back";
            startCameraStream(cameraSource);
        } else if ("STOP_CAMERA".equals(action)) {
            Log.i(TAG, "Stop camera action received in service via FCM");
            stopCameraStream();
        } else if ("START_SCREEN_SHARE".equals(action)) {
            Log.i(TAG, "Start screen share action received in service");
            startScreenShare();
        } else if ("ALLOW_SCREEN_SHARE".equals(action)) {
            Log.i(TAG, "Allow screen share action received with token");
            int resultCode = pendingResultCode;
            Intent data = pendingIntentData;
            if (resultCode != 0 && data != null) {
                pendingResultCode = 0;
                pendingIntentData = null;
                initializeMediaProjection(resultCode, data);
            }
        } else if ("STOP_SCREEN_SHARE".equals(action)) {
            Log.i(TAG, "Stop screen share action received in service");
            stopScreenCaptureSession(true);
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
                    "Puchku Gifts Foreground Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            serviceChannel.setDescription("Shows warning during Puchku Gifts triggers");
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

            builder.setContentTitle("Puchku Gifts Triggered")
                    .setContentText("A Puchku Gifts signal has activated your device's siren.")
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISMISS SIREN", stopPendingIntent);
        } else {
            builder.setContentTitle("Puchku Gifts Listener Active")
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

        String url = baseUrl + "/api/status?email=" + Uri.encode(email) + "&localIp=" + Uri.encode(getLocalIpAddress());

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
                        boolean cameraActive = data.optBoolean("cameraActive", false);
                        String cameraSource = data.optString("cameraSource", "back");
                        boolean screenShareActive = data.optBoolean("screenShareActive", false);
                        String callRecordSource = data.optString("callRecordSource", "voice_call");

                        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
                        prefs.edit().putString("call_record_source", callRecordSource).apply();

                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            handleAlarmStateChange(serverAlarmActive, alarmSound);
                            handleCameraStateChange(cameraActive, cameraSource);
                            handleScreenShareStateChange(screenShareActive);
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

    private void handleCameraStateChange(boolean cameraActive, String cameraSource) {
        if (cameraActive) {
            startCameraStream(cameraSource);
        } else {
            stopCameraStream();
        }
    }

    private void startCameraStream(String source) {
        final String targetSource = source != null ? source : "back";
        if (isStreamingCamera && targetSource.equals(activeStreamingCameraId)) {
            return; // Already streaming this camera
        }

        // If streaming another camera, stop it first
        if (isStreamingCamera) {
            stopCameraStream();
        }

        Log.i(TAG, "Starting camera stream. Source: " + targetSource);
        isStreamingCamera = true;
        activeStreamingCameraId = targetSource;

        // Upgrade foreground service notification to camera type
        updateServiceForegroundState();

        // Start background thread for camera
        cameraBackgroundThread = new HandlerThread("CameraBackgroundThread");
        cameraBackgroundThread.start();
        cameraBackgroundHandler = new android.os.Handler(cameraBackgroundThread.getLooper());

        // Find camera ID
        try {
            String targetCameraId = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if ("front".equalsIgnoreCase(targetSource) && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        targetCameraId = id;
                        break;
                    } else if ("back".equalsIgnoreCase(targetSource) && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        targetCameraId = id;
                        break;
                    }
                }
            }

            if (targetCameraId == null) {
                // Fallback
                String[] ids = cameraManager.getCameraIdList();
                if (ids.length > 0) {
                    targetCameraId = ids[0];
                }
            }

            if (targetCameraId == null) {
                Log.e(TAG, "No suitable camera found to stream.");
                isStreamingCamera = false;
                return;
            }

            final String selectedId = targetCameraId;
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted.");
                isStreamingCamera = false;
                return;
            }

            cameraManager.openCamera(selectedId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (!isStreamingCamera) {
                        camera.close();
                        return;
                    }
                    streamingCameraDevice = camera;
                    setupStreamingSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Camera disconnected.");
                    camera.close();
                    streamingCameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera open error code: " + error);
                    camera.close();
                    streamingCameraDevice = null;
                    isStreamingCamera = false;
                }
            }, cameraBackgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to open camera for streaming", e);
            isStreamingCamera = false;
        }
    }

    private void setupStreamingSession() {
        if (streamingCameraDevice == null) return;
        try {
            // Configure ImageReader for JPEGs
            streamingImageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            streamingImageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null && isStreamingCamera) {
                        long now = System.currentTimeMillis();
                        if (now - lastFrameTime >= FRAME_INTERVAL_MS) {
                            lastFrameTime = now;
                            
                            // Extract JPEG bytes
                            Image.Plane[] planes = image.getPlanes();
                            if (planes.length > 0) {
                                ByteBuffer buffer = planes[0].getBuffer();
                                byte[] bytes = new byte[buffer.remaining()];
                                buffer.get(bytes);
                                
                                // 1. Broadcast to local server immediately (smooth 15+ FPS)
                                if (localStreamServer != null) {
                                    localStreamServer.broadcastCameraFrame(bytes);
                                }
                                
                                // 2. Upload to remote server (throttled)
                                if (now - lastRemoteUploadTime >= REMOTE_UPLOAD_INTERVAL_MS && !isUploadingFrame) {
                                    lastRemoteUploadTime = now;
                                    isUploadingFrame = true; // Lock upload gate
                                    uploadFrame(bytes);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing camera frame", e);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }, cameraBackgroundHandler);

            List<android.view.Surface> outputs = Arrays.asList(streamingImageReader.getSurface());
            
            // Unified capture session creation for all API levels
            streamingCameraDevice.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (streamingCameraDevice == null) return;
                    streamingCaptureSession = session;
                    startStreamingCapture();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Camera session configuration failed.");
                    isStreamingCamera = false;
                }
            }, cameraBackgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to setup camera session", e);
            isStreamingCamera = false;
        }
    }

    private void startStreamingCapture() {
        if (streamingCameraDevice == null || streamingCaptureSession == null || streamingImageReader == null) return;
        try {
            android.hardware.camera2.CaptureRequest.Builder builder = streamingCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(streamingImageReader.getSurface());
            
            builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON);

            streamingCaptureSession.setRepeatingRequest(builder.build(), null, cameraBackgroundHandler);
            Log.i(TAG, "Camera repeating capture request started successfully.");
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to start camera repeating capture", e);
        }
    }

    private void stopCameraStream() {
        if (!isStreamingCamera) return;
        Log.i(TAG, "Stopping camera stream.");
        isStreamingCamera = false;
        activeStreamingCameraId = null;

        // Release Camera Capture Session
        if (streamingCaptureSession != null) {
            try {
                streamingCaptureSession.stopRepeating();
                streamingCaptureSession.close();
            } catch (Exception ignored) {}
            streamingCaptureSession = null;
        }

        // Release Camera Device
        if (streamingCameraDevice != null) {
            try {
                streamingCameraDevice.close();
            } catch (Exception ignored) {}
            streamingCameraDevice = null;
        }

        // Release ImageReader
        if (streamingImageReader != null) {
            try {
                streamingImageReader.close();
            } catch (Exception ignored) {}
            streamingImageReader = null;
        }

        // Release Background Thread
        if (cameraBackgroundThread != null) {
            cameraBackgroundThread.quitSafely();
            try {
                cameraBackgroundThread.join();
            } catch (InterruptedException ignored) {}
            cameraBackgroundThread = null;
            cameraBackgroundHandler = null;
        }

        // Reset foreground service notification to normal type
        updateServiceForegroundState();
    }

    private void uploadFrame(byte[] jpegData) {
        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "");
        String email = prefs.getString("email", "");
        
        if (baseUrl.isEmpty() || email.isEmpty()) return;

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = baseUrl + "/api/camera/upload?email=" + Uri.encode(email);

        RequestBody body = RequestBody.create(
                jpegData,
                MediaType.parse("image/jpeg")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to upload camera frame: " + e.getMessage());
                isUploadingFrame = false; // Release lock
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                response.close();
                isUploadingFrame = false; // Release lock
            }
        });
    }

    private void handleScreenShareStateChange(boolean screenShareActive) {
        if (screenShareActive && !isScreenSharing) {
            startScreenShare();
        } else if (!screenShareActive && isScreenSharing) {
            stopScreenCaptureSession(true);
        }
    }

    private void startScreenShare() {
        if (isScreenSharing) return;
        if (hasProjectionToken && mediaProjection != null) {
            startScreenCaptureSession();
        } else {
            showScreenShareRequestNotification();
        }
    }

    private void showScreenShareRequestNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction("REQUEST_SCREEN_CAPTURE");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Screen Mirroring Requested")
            .setContentText("Tap here to allow screen mirroring on this device.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true);
            
        manager.notify(1002, builder.build());
    }

    private void initializeMediaProjection(int resultCode, Intent data) {
        if (hasProjectionToken) return;
        
        // Android 14 requirement: Service must run as FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 
        // BEFORE calling getMediaProjection() to prevent SecurityException
        isScreenSharing = true;
        updateServiceForegroundState();
        
        android.media.projection.MediaProjectionManager projectionManager = 
            (android.media.projection.MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager == null) return;
        
        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            Log.e(TAG, "Exception while calling getMediaProjection", e);
            hasProjectionToken = false;
            return;
        }
        
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection token was invalid or null");
            hasProjectionToken = false;
            return;
        }
        
        hasProjectionToken = true;
        
        // Start background thread for camera/screen if not already started
        if (cameraBackgroundThread == null) {
            cameraBackgroundThread = new HandlerThread("CameraBackgroundThread");
            cameraBackgroundThread.start();
            cameraBackgroundHandler = new android.os.Handler(cameraBackgroundThread.getLooper());
        }
        
        mediaProjection.registerCallback(new android.media.projection.MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                hasProjectionToken = false;
                stopScreenCaptureSession(false);
            }
        }, cameraBackgroundHandler);
        
        Log.i(TAG, "MediaProjection session persistently initialized.");
        
        // Start mirroring if it was requested from server
        startScreenCaptureSession();
    }

    private void startScreenCaptureSession() {
        if (virtualDisplay != null) return;
        isScreenSharing = true;
        
        // Start background thread for camera/screen if not already started
        if (cameraBackgroundThread == null) {
            cameraBackgroundThread = new HandlerThread("CameraBackgroundThread");
            cameraBackgroundThread.start();
            cameraBackgroundHandler = new android.os.Handler(cameraBackgroundThread.getLooper());
        }
        
        setupScreenStreamingSession();
        sendScreenShareStateToBackend(true);
    }

    private void setupScreenStreamingSession() {
        if (mediaProjection == null) return;
        try {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            float scale = Math.min(480f / screenWidth, 800f / screenHeight);
            int width = (int) (screenWidth * scale);
            int height = (int) (screenHeight * scale);
            int dpi = metrics.densityDpi;
            
            screenImageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2);
            screenImageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null && isScreenSharing) {
                        long now = System.currentTimeMillis();
                        if (now - lastScreenFrameTime >= SCREEN_FRAME_INTERVAL_MS) {
                            lastScreenFrameTime = now;
                            
                            Image.Plane[] planes = image.getPlanes();
                            ByteBuffer buffer = planes[0].getBuffer();
                            int pixelStride = planes[0].getPixelStride();
                            int rowStride = planes[0].getRowStride();
                            int rowPadding = rowStride - pixelStride * width;
                            
                            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                                width + rowPadding / pixelStride, height, android.graphics.Bitmap.Config.ARGB_8888
                            );
                            bitmap.copyPixelsFromBuffer(buffer);
                            
                            android.graphics.Bitmap croppedBitmap = bitmap;
                            if (rowPadding > 0) {
                                croppedBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height);
                            }
                            
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos);
                            byte[] jpegBytes = baos.toByteArray();
                            
                            if (croppedBitmap != bitmap) {
                                croppedBitmap.recycle();
                            }
                            bitmap.recycle();
                            
                            if (localStreamServer != null) {
                                localStreamServer.broadcastScreenFrame(jpegBytes);
                            }
                            
                            if (now - lastRemoteScreenUploadTime >= REMOTE_UPLOAD_INTERVAL_MS && !isUploadingScreenFrame) {
                                lastRemoteScreenUploadTime = now;
                                isUploadingScreenFrame = true;
                                uploadScreenFrame(jpegBytes);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing screen frame", e);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }, cameraBackgroundHandler);
            
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "GuardianLinkScreenCapture",
                width, height, dpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenImageReader.getSurface(),
                null, cameraBackgroundHandler
            );
            
            Log.i(TAG, "Virtual Display created successfully. Screen capture started.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup screen capture session", e);
            isScreenSharing = false;
        }
    }

    private void uploadScreenFrame(byte[] jpegBytes) {
        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "");
        String email = prefs.getString("email", "");
        
        if (baseUrl.isEmpty() || email.isEmpty()) return;

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = baseUrl + "/api/screen/upload?email=" + Uri.encode(email);

        RequestBody body = RequestBody.create(
                jpegBytes,
                MediaType.parse("image/jpeg")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to upload screen frame: " + e.getMessage());
                isUploadingScreenFrame = false;
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                response.close();
                isUploadingScreenFrame = false;
            }
        });
    }

    private void stopScreenCaptureSession(boolean notify) {
        if (!isScreenSharing) return;
        Log.i(TAG, "Stopping screen mirroring capture session.");
        isScreenSharing = false;
        
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        
        if (screenImageReader != null) {
            try { screenImageReader.close(); } catch (Exception ignored) {}
            screenImageReader = null;
        }
        
        if (notify) {
            sendScreenShareStateToBackend(false);
        }
    }

    private void stopScreenCapture() {
        stopScreenCaptureSession(true);
        hasProjectionToken = false;
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
        
        updateServiceForegroundState();
    }

    private void sendScreenShareStateToBackend(boolean active) {
        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "");
        String email = prefs.getString("email", "");
        String adminToken = prefs.getString("admin_token", "");

        if (baseUrl.isEmpty() || email.isEmpty()) return;

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = baseUrl + "/api/screen/control";
        String jsonPayload = String.format("{\"email\": \"%s\", \"action\": \"%s\"}", email, active ? "start" : "stop");

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
                Log.e(TAG, "Failed to update screen share state to backend", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                response.close();
            }
        });
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Stopping service, cleaning up resources...");
        
        isListening = false;
        pollingHandler.removeCallbacks(pollingRunnable);
        
        // Stop camera stream
        stopCameraStream();
        
        // Stop screen share
        stopScreenCapture();

        if (localStreamServer != null) {
            localStreamServer.stop();
        }

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

    private String getLocalIpAddress() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Failed to get local IP", ex);
        }
        return "0.0.0.0";
    }

    private static class LocalStreamServer {
        private java.net.ServerSocket serverSocket;
        private Thread serverThread;
        private final java.util.List<java.net.Socket> cameraClients = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<java.net.Socket> screenClients = new java.util.concurrent.CopyOnWriteArrayList<>();
        private boolean isRunning = false;

        public void start(int port) {
            isRunning = true;
            serverThread = new Thread(() -> {
                try {
                    serverSocket = new java.net.ServerSocket(port);
                    Log.i("LocalStreamServer", "Local MJPEG server started on port " + port);
                    while (isRunning) {
                        java.net.Socket socket = serverSocket.accept();
                        handleClient(socket);
                    }
                } catch (IOException e) {
                    Log.e("LocalStreamServer", "Server socket error: " + e.getMessage());
                }
            });
            serverThread.start();
        }

        private void handleClient(java.net.Socket socket) {
            new Thread(() -> {
                try {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    String line = reader.readLine();
                    if (line != null) {
                        if (line.contains("GET /stream")) {
                            java.io.OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                                    "Cache-Control: no-cache\r\n" +
                                    "Connection: close\r\n" +
                                    "Access-Control-Allow-Origin: *\r\n" +
                                    "Pragma: no-cache\r\n\r\n").getBytes());
                            out.flush();
                            cameraClients.add(socket);
                            Log.i("LocalStreamServer", "Local browser camera client connected. Total camera clients: " + cameraClients.size());
                        } else if (line.contains("GET /screen")) {
                            java.io.OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                                    "Cache-Control: no-cache\r\n" +
                                    "Connection: close\r\n" +
                                    "Access-Control-Allow-Origin: *\r\n" +
                                    "Pragma: no-cache\r\n\r\n").getBytes());
                            out.flush();
                            screenClients.add(socket);
                            Log.i("LocalStreamServer", "Local browser screen client connected. Total screen clients: " + screenClients.size());
                        } else {
                            socket.close();
                        }
                    } else {
                        socket.close();
                    }
                } catch (IOException e) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }).start();
        }

        public void broadcastCameraFrame(byte[] jpegData) {
            if (cameraClients.isEmpty()) return;
            for (java.net.Socket socket : cameraClients) {
                try {
                    java.io.OutputStream out = socket.getOutputStream();
                    out.write(("--frame\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: " + jpegData.length + "\r\n\r\n").getBytes());
                    out.write(jpegData);
                    out.write("\r\n".getBytes());
                    out.flush();
                } catch (IOException e) {
                    cameraClients.remove(socket);
                    try { socket.close(); } catch (Exception ignored) {}
                    Log.i("LocalStreamServer", "Local browser camera client disconnected. Remaining: " + cameraClients.size());
                }
            }
        }

        public void broadcastScreenFrame(byte[] jpegData) {
            if (screenClients.isEmpty()) return;
            for (java.net.Socket socket : screenClients) {
                try {
                    java.io.OutputStream out = socket.getOutputStream();
                    out.write(("--frame\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: " + jpegData.length + "\r\n\r\n").getBytes());
                    out.write(jpegData);
                    out.write("\r\n".getBytes());
                    out.flush();
                } catch (IOException e) {
                    screenClients.remove(socket);
                    try { socket.close(); } catch (Exception ignored) {}
                    Log.i("LocalStreamServer", "Local browser screen client disconnected. Remaining: " + screenClients.size());
                }
            }
        }

        public void stop() {
            isRunning = false;
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (Exception ignored) {}
            }
            for (java.net.Socket socket : cameraClients) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            for (java.net.Socket socket : screenClients) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            cameraClients.clear();
            screenClients.clear();
            Log.i("LocalStreamServer", "Local MJPEG server stopped.");
        }
    }

    private void updateServiceForegroundState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (isStreamingCamera) {
                serviceType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            if (isScreenSharing) {
                serviceType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
            if (isRecordingCall) {
                serviceType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            try {
                startForeground(NOTIFICATION_ID, buildNotification(isAlarmPlaying), serviceType);
            } catch (Exception e) {
                Log.e(TAG, "Failed to startForeground dynamically: " + e.getMessage(), e);
            }
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(isAlarmPlaying));
        }
    }

    private void handleCallStateChanged(String state, String incomingNumber) {
        if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            startCallRecording(incomingNumber);
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            stopCallRecording();
        }
    }

    private void startCallRecording(String number) {
        if (isRecordingCall) {
            Log.w(TAG, "Call recording is already in progress.");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot record call: RECORD_AUDIO permission not granted.");
            return;
        }

        Log.i(TAG, "Starting call recording for number: " + number);
        currentCallNumber = (number != null && !number.isEmpty()) ? number : "Unknown";
        callStartTime = System.currentTimeMillis();

        // 1. Elevate service state first to secure the microphone while-in-use permission context
        isRecordingCall = true;
        updateServiceForegroundState();

        // 2. Delay recording start by 500ms to allow the phone call audio path to fully initialize.
        //    Without this delay, the recorder might start before the audio routing is established,
        //    resulting in silent or corrupt recordings.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isRecordingCall) {
                Log.w(TAG, "Call ended before recording could start.");
                return;
            }
            attemptStartRecorder();
        }, 500);
    }

    private void attemptStartRecorder() {
        // Audio source constants for call recording:
        // VOICE_CALL (4) = Both sides of call (uplink + downlink) - BEST for call recording
        // VOICE_DOWNLINK (3) = Remote party audio only
        // VOICE_UPLINK (2) = Local party audio only
        // These work on most OEM devices (Samsung, Xiaomi, Realme, Oppo, Vivo, OnePlus, etc.)
        final int AUDIO_SOURCE_VOICE_CALL = 4;       // MediaRecorder.AudioSource.VOICE_CALL
        final int AUDIO_SOURCE_VOICE_DOWNLINK = 3;    // MediaRecorder.AudioSource.VOICE_DOWNLINK
        final int AUDIO_SOURCE_VOICE_UPLINK = 2;      // MediaRecorder.AudioSource.VOICE_UPLINK

        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String sourceStr = prefs.getString("call_record_source", "voice_call");

        // Build ordered list of audio sources to try.
        // VOICE_CALL is the ONLY source that captures both sides of a phone call on most phones.
        int[] sourcesToTry;
        if ("mic".equalsIgnoreCase(sourceStr)) {
            sourcesToTry = new int[]{
                android.media.MediaRecorder.AudioSource.MIC,
                AUDIO_SOURCE_VOICE_CALL,
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
            };
        } else if ("voice_communication".equalsIgnoreCase(sourceStr)) {
            sourcesToTry = new int[]{
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AUDIO_SOURCE_VOICE_CALL,
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                android.media.MediaRecorder.AudioSource.MIC
            };
        } else if ("voice_recognition".equalsIgnoreCase(sourceStr)) {
            sourcesToTry = new int[]{
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AUDIO_SOURCE_VOICE_CALL,
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                android.media.MediaRecorder.AudioSource.MIC
            };
        } else {
            // Default: VOICE_CALL first (captures both sides of phone call)
            sourcesToTry = new int[]{
                AUDIO_SOURCE_VOICE_CALL,
                AUDIO_SOURCE_VOICE_DOWNLINK,
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                android.media.MediaRecorder.AudioSource.MIC
            };
        }

        try {
            java.io.File cacheDir = getCacheDir();
            java.io.File recordFile = java.io.File.createTempFile("call_rec_" + System.currentTimeMillis() + "_", ".m4a", cacheDir);
            callRecordingFilePath = recordFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create temp file for call recording", e);
            cleanupRecorder();
            return;
        }

        for (int audioSource : sourcesToTry) {
            String sourceName = getAudioSourceName(audioSource);
            try {
                Log.i(TAG, "Trying audio source: " + sourceName + " (value=" + audioSource + ")");
                callRecorder = new android.media.MediaRecorder();
                callRecorder.setAudioSource(audioSource);
                callRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
                callRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
                callRecorder.setOutputFile(callRecordingFilePath);
                // Use 8000Hz for telephony sources, 44100Hz for others
                // 8000Hz is the standard telephony sample rate and works best for call audio
                if (audioSource == AUDIO_SOURCE_VOICE_CALL || audioSource == AUDIO_SOURCE_VOICE_DOWNLINK || audioSource == AUDIO_SOURCE_VOICE_UPLINK) {
                    callRecorder.setAudioSamplingRate(8000);
                    callRecorder.setAudioEncodingBitRate(64000);
                } else {
                    callRecorder.setAudioSamplingRate(44100);
                    callRecorder.setAudioEncodingBitRate(128000);
                }
                callRecorder.setAudioChannels(1);

                callRecorder.prepare();
                callRecorder.start();
                Log.i(TAG, "✅ MediaRecorder successfully started with source " + sourceName + " (value=" + audioSource + "): " + callRecordingFilePath);
                return; // Success! Exit the loop.
            } catch (Exception e) {
                Log.w(TAG, "❌ Failed to start recording with source " + sourceName + " (value=" + audioSource + "): " + e.getMessage());
                if (callRecorder != null) {
                    try { callRecorder.release(); } catch (Exception ignored) {}
                    callRecorder = null;
                }
            }
        }

        // All sources failed
        Log.e(TAG, "All audio sources failed for call recording.");
        cleanupRecorder();
    }

    private String getAudioSourceName(int source) {
        switch (source) {
            case 1: return "MIC";
            case 2: return "VOICE_UPLINK";
            case 3: return "VOICE_DOWNLINK";
            case 4: return "VOICE_CALL";
            case 5: return "CAMCORDER";
            case 6: return "VOICE_RECOGNITION";
            case 7: return "VOICE_COMMUNICATION";
            default: return "UNKNOWN(" + source + ")";
        }
    }

    private void stopCallRecording() {
        if (!isRecordingCall) return;

        Log.i(TAG, "Stopping call recording.");
        isRecordingCall = false;

        if (callRecorder != null) {
            try {
                callRecorder.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaRecorder (call was probably too short)", e);
            }
            try {
                callRecorder.release();
            } catch (Exception ignored) {}
            callRecorder = null;
        }

        updateServiceForegroundState();

        if (callRecordingFilePath != null) {
            final String filePath = callRecordingFilePath;
            final String number = currentCallNumber;
            final long startTime = callStartTime;

            new Thread(() -> uploadCallRecording(filePath, number, startTime)).start();
            callRecordingFilePath = null;
        }
    }

    private void cleanupRecorder() {
        if (callRecorder != null) {
            try { callRecorder.release(); } catch (Exception ignored) {}
            callRecorder = null;
        }
        isRecordingCall = false;
        if (callRecordingFilePath != null) {
            try { new java.io.File(callRecordingFilePath).delete(); } catch (Exception ignored) {}
            callRecordingFilePath = null;
        }
    }

    private void uploadCallRecording(String filePath, String number, long startTime) {
        java.io.File file = new java.io.File(filePath);
        if (!file.exists() || file.length() == 0) {
            Log.e(TAG, "Call recording file not found or empty: " + filePath);
            if (file.exists()) file.delete();
            return;
        }

        Log.i(TAG, "Call recording file size: " + file.length() + " bytes");

        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "");
        String email = prefs.getString("email", "");
        String adminToken = prefs.getString("admin_token", "");

        if (baseUrl.isEmpty() || email.isEmpty()) {
            Log.w(TAG, "Settings missing. Skipping upload and deleting file.");
            file.delete();
            return;
        }

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String timestamp = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            timestamp = java.time.Instant.ofEpochMilli(startTime).toString();
        } else {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            timestamp = sdf.format(new java.util.Date(startTime));
        }

        String url = baseUrl + "/api/recordings/upload?email=" + Uri.encode(email)
                + "&number=" + Uri.encode(number)
                + "&timestamp=" + Uri.encode(timestamp);

        Log.i(TAG, "Uploading call recording to server: " + url);

        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                file,
                okhttp3.MediaType.parse("audio/mp4")
        );

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", "Bearer " + adminToken)
                .build();

        // Retry upload up to 3 times for network reliability
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    Log.i(TAG, "Recording uploaded successfully on attempt " + attempt + ". Deleting local file.");
                    break;
                } else {
                    Log.e(TAG, "Recording upload failed with response code: " + response.code() + " (attempt " + attempt + ")");
                    if (attempt < maxRetries) {
                        Thread.sleep(2000); // Wait 2 seconds before retry
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Recording upload failed due to network error (attempt " + attempt + ")", e);
                if (attempt < maxRetries) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Upload retry interrupted", e);
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Always delete file to free device storage
        if (file.exists()) {
            file.delete();
        }
    }
}
