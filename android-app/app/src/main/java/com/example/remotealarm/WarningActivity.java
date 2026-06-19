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

import androidx.appcompat.app.AppCompatActivity;

public class WarningActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_ENABLE_ADMIN = 2001;
    
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private boolean isActivatingAdmin = false;
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
            
            // Start a safety timeout: if they don't activate within 15 seconds, reopen the warning screen
            startActivationTimeout();
        });
    }

    private boolean isAdminActive() {
        return devicePolicyManager != null && devicePolicyManager.isAdminActive(adminComponent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivatingAdmin = false; // Reset temporary flag
        handler.removeCallbacks(activationTimeoutRunnable);
        
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
        }
    }

    private final Runnable activationTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdminActive() && isActivatingAdmin) {
                isActivatingAdmin = false;
                reopenWarning();
            }
        }
    };

    private void startActivationTimeout() {
        handler.removeCallbacks(activationTimeoutRunnable);
        // 15 seconds timeout: if they press Home and escape, they are blocked again in 15 seconds
        handler.postDelayed(activationTimeoutRunnable, 15000);
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
