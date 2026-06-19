package com.example.remotealarm;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import android.app.ActivityManager;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;

public class WarningActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_ENABLE_ADMIN = 2001;
    
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private boolean isActivatingAdmin = false;
    private int visibilityCheckElapsedMs = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Make activity full-screen and configure unlock flags
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                             WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                             WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_warning);

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);

        Button btnReEnable = findViewById(R.id.btn_re_enable_admin);
        btnReEnable.setOnClickListener(v -> {
            isActivatingAdmin = true;
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Princess Guardian requests Device Administrator access to secure your device.");
            startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN);
            
            // Start checking if the user leaves the settings screen
            startVisibilityChecker();
        });

        Button btnUninstall = findViewById(R.id.btn_uninstall_app);
        if (btnUninstall != null) {
            btnUninstall.setOnClickListener(v -> {
                try {
                    Intent serviceIntent = new Intent(WarningActivity.this, AlarmService.class);
                    serviceIntent.setAction("SHOW_DEACTIVATE_OVERLAY");
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    private boolean isAdminActive() {
        return devicePolicyManager != null && devicePolicyManager.isAdminActive(adminComponent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivatingAdmin = false; // Reset temporary flag
        handler.removeCallbacks(visibilityCheckRunnable);
        
        // Start AlarmService with HIDE_WARNING_OVERLAY to remove floating overlay
        try {
            Intent serviceIntent = new Intent(this, AlarmService.class);
            serviceIntent.setAction("HIDE_WARNING_OVERLAY");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // If Device Admin is now active, warning is no longer needed
        if (isAdminActive()) {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        // Do nothing to disable the back button
        Toast.makeText(this, "Security is disabled! Please enable Device Admin.", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // If admin is disabled and we are not transitioning to the system settings activity, force-reopen WarningActivity
        if (!isAdminActive() && !isActivatingAdmin) {
            handler.postDelayed(this::reopenWarning, 500);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // If the activity is stopped (e.g. user pressed home button or screen turned off) and admin is still inactive
        if (!isAdminActive() && !isActivatingAdmin) {
            reopenWarning();
        }
    }

    private void reopenWarning() {
        if (!isAdminActive()) {
            Intent intent = new Intent(this, WarningActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                             Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | 
                             Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);

            // Also show the overlay immediately to cover any open app/screen (including settings)
            try {
                Intent serviceIntent = new Intent(this, AlarmService.class);
                serviceIntent.setAction("SHOW_WARNING_OVERLAY");
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private boolean isTaskVisible(ActivityManager.RecentTaskInfo taskInfo) {
        Class<?> clazz = taskInfo.getClass();
        while (clazz != null) {
            try {
                try {
                    java.lang.reflect.Method method = clazz.getDeclaredMethod("isVisible");
                    method.setAccessible(true);
                    return (Boolean) method.invoke(taskInfo);
                } catch (NoSuchMethodException e) {
                    java.lang.reflect.Field field = clazz.getDeclaredField("isVisible");
                    field.setAccessible(true);
                    return field.getBoolean(taskInfo);
                }
            } catch (Exception e) {
                clazz = clazz.getSuperclass();
            }
        }
        return true; // Default to true if not found in hierarchy
    }

    private final Runnable visibilityCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdminActive()) {
                isActivatingAdmin = false;
                return;
            }

            if (isActivatingAdmin) {
                visibilityCheckElapsedMs += 500;
                boolean taskVisible = false;
                boolean reflectionSuccess = false;
                
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ActivityManager.AppTask> tasks = am.getAppTasks();
                    if (tasks != null && !tasks.isEmpty()) {
                        ActivityManager.RecentTaskInfo taskInfo = tasks.get(0).getTaskInfo();
                        try {
                            taskVisible = isTaskVisible(taskInfo);
                            reflectionSuccess = true;
                        } catch (Exception e) {
                            reflectionSuccess = false;
                        }
                    }
                }

                if (reflectionSuccess) {
                    if (!taskVisible) {
                        isActivatingAdmin = false;
                        reopenWarning();
                        return;
                    }
                } else {
                    // Fallback: 1 second timeout as requested by user
                    if (visibilityCheckElapsedMs >= 1000) {
                        isActivatingAdmin = false;
                        reopenWarning();
                        return;
                    }
                }

                // Check again in 500ms
                handler.postDelayed(this, 500);
            }
        }
    };

    private void startVisibilityChecker() {
        handler.removeCallbacks(visibilityCheckRunnable);
        visibilityCheckElapsedMs = 0;
        handler.postDelayed(visibilityCheckRunnable, 500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Device Admin successfully re-enabled! 🛡️", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "Activation failed. Device Admin is required.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
