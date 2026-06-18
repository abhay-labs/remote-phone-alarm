package com.example.remotealarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.StrictMode;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CrashLogger {
    private static final String TAG = "CrashLogger";
    private static final String PREFS_NAME = "RemoteAlarmPrefs";
    private static final String KEY_LAST_CRASH = "last_crash";

    public static void init(final Context context) {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
                // Generate stacktrace
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = sw.toString();

                Log.e(TAG, "FATAL CRASH DETECTED:\n" + stackTrace);

                // Save crash log locally in SharedPreferences immediately
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_LAST_CRASH, stackTrace).commit(); // commit synchronously

                // Attempt to send to backend synchronously before process terminates
                sendCrashLogSync(context, stackTrace);

                // Pass execution to default handler
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                }
            }
        });
    }

    public static void checkAndUploadPendingCrash(final Context context) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final String lastCrash = prefs.getString(KEY_LAST_CRASH, "");
        if (!lastCrash.isEmpty()) {
            Log.i(TAG, "Pending crash log found. Uploading asynchronously...");
            sendCrashLogAsync(context, lastCrash, new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "Failed to upload pending crash log", e);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        prefs.edit().remove(KEY_LAST_CRASH).apply();
                        Log.i(TAG, "Pending crash log successfully uploaded and cleared.");
                    } else {
                        Log.w(TAG, "Server returned error for crash upload: " + response.code());
                    }
                    response.close();
                }
            });
        }
    }

    private static void sendCrashLogSync(Context context, String stackTrace) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "https://remote-phone-alarm.onrender.com");
        String email = prefs.getString("email", "unknown");
        String url = baseUrl + "/api/log";

        // Normalize URL
        if (url.contains("//api/log")) {
            url = url.replace("//api/log", "/api/log");
        }

        try {
            OkHttpClient client = new OkHttpClient();
            String json = String.format("{\"email\": \"%s\", \"log\": \"%s\"}",
                    email, stackTrace.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"));
            RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            // Bypass NetworkOnMainThreadException during crash sequence
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);

            try (Response response = client.newCall(request).execute()) {
                Log.d(TAG, "Sync crash log send result: " + response.isSuccessful());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending crash log synchronously", e);
        }
    }

    private static void sendCrashLogAsync(Context context, String stackTrace, Callback callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String baseUrl = prefs.getString("backend_url", "https://remote-phone-alarm.onrender.com");
        String email = prefs.getString("email", "unknown");
        String url = baseUrl + "/api/log";

        // Normalize URL
        if (url.contains("//api/log")) {
            url = url.replace("//api/log", "/api/log");
        }

        try {
            OkHttpClient client = new OkHttpClient();
            String json = String.format("{\"email\": \"%s\", \"log\": \"%s\"}",
                    email, stackTrace.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"));
            RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(callback);
        } catch (Exception e) {
            Log.e(TAG, "Error sending crash log asynchronously", e);
        }
    }
}
