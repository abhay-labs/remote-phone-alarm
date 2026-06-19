package com.example.remotealarm;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class DeactivateAuthActivity extends AppCompatActivity {
    private EditText etHubbyName;
    private Button btnConfirm;
    private Button btnCancel;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    private boolean isVerified = false;
    private boolean isCancelled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Configure transparent window and full screen
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                                 WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                                 WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        setContentView(R.layout.dialog_deactivate_auth);

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);

        etHubbyName = findViewById(R.id.et_hubby_name);
        btnConfirm = findViewById(R.id.btn_confirm);
        btnCancel = findViewById(R.id.btn_cancel);

        btnConfirm.setOnClickListener(v -> {
            String nameInput = etHubbyName.getText().toString().trim();
            if (nameInput.equalsIgnoreCase("myloveabhay")) {
                isVerified = true;
                Toast.makeText(this, "Verification Successful! 💖", Toast.LENGTH_SHORT).show();
                
                // Deactivate device admin programmatically
                if (devicePolicyManager != null) {
                    devicePolicyManager.removeActiveAdmin(adminComponent);
                }

                // Trigger package uninstall
                Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
                uninstallIntent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(uninstallIntent);

                finish();
            } else {
                Toast.makeText(this, "Oops! Galat naam. 💔 Try again!", Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> {
            isCancelled = true;
            
            // Redirect to App Details settings to clear the deactivation dialog
            try {
                Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                settingsIntent.setData(Uri.parse("package:" + getPackageName()));
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(settingsIntent);
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Redirect to home screen to close/hide Settings app
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
            finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isVerified && !isCancelled) {
            // User is trying to bypass the popup (e.g. via Home or Recents keys)
            // Redirect to App Details settings to clear the deactivation dialog
            try {
                Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                settingsIntent.setData(Uri.parse("package:" + getPackageName()));
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(settingsIntent);
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            // Redirect to home screen
            try {
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        // Do nothing to disable the back button and prevent bypass
        Toast.makeText(this, "Verification required to deactivate or uninstall! 🔒", Toast.LENGTH_SHORT).show();
    }
}
