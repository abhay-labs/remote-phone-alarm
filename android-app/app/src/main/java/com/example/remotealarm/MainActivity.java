package com.example.remotealarm;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 101;

    private EditText backendUrlInput;
    private TextView tokenTextView;
    private TextView permissionStatusText;
    private SharedPreferences prefs;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);

        // Initialize UI Elements
        backendUrlInput = findViewById(R.id.backend_url_input);
        tokenTextView = findViewById(R.id.token_text_view);
        permissionStatusText = findViewById(R.id.permission_status);
        Button saveBtn = findViewById(R.id.save_btn);
        Button copyTokenBtn = findViewById(R.id.copy_token_btn);
        Button testAlarmBtn = findViewById(R.id.test_alarm_btn);
        Button stopAlarmBtn = findViewById(R.id.stop_alarm_btn);
        Button requestDndBtn = findViewById(R.id.request_dnd_btn);

        // Load saved Backend URL config
        String savedUrl = prefs.getString("backend_url", "http://10.0.2.2:3000");
        backendUrlInput.setText(savedUrl);

        // Setup Listeners
        saveBtn.setOnClickListener(v -> {
            String url = backendUrlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show();
                return;
            }
            // Remove trailing slash
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            prefs.edit().putString("backend_url", url).apply();
            Toast.makeText(this, "Server URL configuration saved!", Toast.LENGTH_SHORT).show();
            
            // Re-fetch FCM token and upload to the newly saved backend
            fetchAndRegisterFCMToken();
        });

        copyTokenBtn.setOnClickListener(v -> {
            String token = tokenTextView.getText().toString();
            if (token.isEmpty() || token.startsWith("Fetching") || token.startsWith("Failed")) {
                Toast.makeText(this, "No valid FCM token to copy", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("FCM Token", token);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Token copied to clipboard!", Toast.LENGTH_SHORT).show();
            }
        });

        testAlarmBtn.setOnClickListener(v -> {
            Toast.makeText(this, "Testing Alarm Sound & Flashlight...", Toast.LENGTH_SHORT).show();
            Intent serviceIntent = new Intent(this, AlarmService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
        });

        stopAlarmBtn.setOnClickListener(v -> {
            Toast.makeText(this, "Stopping Alarm...", Toast.LENGTH_SHORT).show();
            Intent serviceIntent = new Intent(this, AlarmService.class);
            serviceIntent.setAction("STOP_ALARM");
            startService(serviceIntent);
        });

        requestDndBtn.setOnClickListener(v -> requestDndPermission());

        // Check/Request Permissions
        checkAndRequestPermissions();

        // Fetch and display Firebase Cloud Messaging Token
        fetchAndRegisterFCMToken();
    }

    private void checkAndRequestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA
            };
        }

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            permissionStatusText.setText("Status: Permissions pending");
            permissionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        } else {
            permissionStatusText.setText("Status: Core permissions active");
            permissionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
    }

    private void requestDndPermission() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null && !notificationManager.isNotificationPolicyAccessGranted()) {
            Toast.makeText(this, "Please grant Do Not Disturb access to override silent mode", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Do Not Disturb permission already granted!", Toast.LENGTH_SHORT).show();
        }
    }

    private void fetchAndRegisterFCMToken() {
        tokenTextView.setText("Fetching FCM device token...");
        
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                tokenTextView.setText("Failed to retrieve FCM token");
                return;
            }

            // Get token
            String token = task.getResult();
            Log.i(TAG, "Active FCM Token: " + token);
            tokenTextView.setText(token);
            
            // Save token
            prefs.edit().putString("fcm_token", token).apply();

            // Register Token with the Backend API
            registerTokenOnBackend(token);
        });
    }

    private void registerTokenOnBackend(String token) {
        String baseUrl = prefs.getString("backend_url", "http://10.0.2.2:3000");
        String url = baseUrl + "/api/register";

        String model = Build.MODEL;
        int sdkVersion = Build.VERSION.SDK_INT;

        String jsonPayload = String.format(
                "{\"token\": \"%s\", \"deviceInfo\": {\"model\": \"%s\", \"sdkVersion\": %d}}",
                token, model, sdkVersion
        );

        RequestBody body = RequestBody.create(
                jsonPayload,
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Network registration failed for url: " + url, e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Server registration failed. Verify URL.", Toast.LENGTH_LONG).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.i(TAG, "Backend registered device successfully");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Device token updated on server!", Toast.LENGTH_SHORT).show());
                } else {
                    Log.w(TAG, "Server registration error: " + response.code());
                }
                response.close();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                permissionStatusText.setText("Status: Core permissions active");
                permissionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
                Toast.makeText(this, "All core permissions granted successfully!", Toast.LENGTH_SHORT).show();
            } else {
                permissionStatusText.setText("Status: Permissions denied");
                permissionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                Toast.makeText(this, "Permissions denied. Some functions might not operate correctly.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
