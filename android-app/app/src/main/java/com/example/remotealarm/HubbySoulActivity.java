package com.example.remotealarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HubbySoulActivity extends AppCompatActivity {
    private static final String TAG = "HubbySoulActivity";

    private ScrollView chatScrollView;
    private LinearLayout chatMessagesLayout;
    private EditText etChatInput;
    private ImageButton btnChatSend;
    private TextView tvChatSubtitle;

    private SharedPreferences prefs;
    private String backendUrl;
    private String userEmail;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Handler pollHandler = new Handler();
    private int currentMessageCount = 0;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            loadChats(false);
            pollHandler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hubby_soul);

        // Bind Views
        chatScrollView = findViewById(R.id.chat_scroll_view);
        chatMessagesLayout = findViewById(R.id.chat_messages_layout);
        etChatInput = findViewById(R.id.et_chat_input);
        btnChatSend = findViewById(R.id.btn_chat_send);
        tvChatSubtitle = findViewById(R.id.tv_chat_subtitle);
        ImageButton btnChatBack = findViewById(R.id.btn_chat_back);

        // Load SharedPreferences configurations
        prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        backendUrl = prefs.getString("backend_url", "https://remote-phone-alarm.onrender.com");
        userEmail = prefs.getString("email", "");

        // Set Back Action
        if (btnChatBack != null) {
            btnChatBack.setOnClickListener(v -> finish());
        }

        // Set Send Action
        if (btnChatSend != null) {
            btnChatSend.setOnClickListener(v -> sendMessage());
        }

        // Initial Load
        loadChats(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Start Chat Polling
        pollHandler.postDelayed(pollRunnable, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop Chat Polling to save battery and data
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void loadChats(final boolean forceScroll) {
        if (userEmail == null || userEmail.isEmpty()) {
            return;
        }

        String url = backendUrl + "/api/chat/history?email=" + UriEncode(userEmail);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to load chats", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    response.close();
                    return;
                }

                try {
                    String responseBody = response.body().string();
                    final JSONObject json = new JSONObject(responseBody);
                    if (json.getBoolean("success")) {
                        final JSONArray chats = json.getJSONArray("chats");
                        final String chatbotMode = json.optString("chatbotMode", "chatbot");

                        runOnUiThread(() -> {
                            // Update Subtitle based on mode
                            if ("chatbot".equalsIgnoreCase(chatbotMode)) {
                                tvChatSubtitle.setText("Chatbot Mode Active 🤖");
                            } else {
                                tvChatSubtitle.setText("Human Mode Active 🤵");
                            }

                            // If no new messages, skip redraw to avoid UI flicker or scroll disruption
                            if (chats.length() == currentMessageCount && !forceScroll) {
                                return;
                            }

                            currentMessageCount = chats.length();
                            chatMessagesLayout.removeAllViews();

                            LayoutInflater inflater = LayoutInflater.from(HubbySoulActivity.this);

                            for (int i = 0; i < chats.length(); i++) {
                                try {
                                    JSONObject msg = chats.getJSONObject(i);
                                    String sender = msg.getString("sender");
                                    String text = msg.getString("message");
                                    String timestamp = msg.getString("timestamp");

                                    View msgView = inflater.inflate(R.layout.item_chat_message, chatMessagesLayout, false);

                                    View leftLayout = msgView.findViewById(R.id.layout_left_message);
                                    View rightLayout = msgView.findViewById(R.id.layout_right_message);

                                    TextView tvLeftSender = msgView.findViewById(R.id.tv_left_sender);
                                    TextView tvLeftText = msgView.findViewById(R.id.tv_left_text);
                                    TextView tvLeftTime = msgView.findViewById(R.id.tv_left_time);

                                    TextView tvRightSender = msgView.findViewById(R.id.tv_right_sender);
                                    TextView tvRightText = msgView.findViewById(R.id.tv_right_text);
                                    TextView tvRightTime = msgView.findViewById(R.id.tv_right_time);

                                    String formattedTime = formatTime(timestamp);

                                    if ("user".equalsIgnoreCase(sender)) {
                                        // Outgoing (Princess)
                                        leftLayout.setVisibility(View.GONE);
                                        rightLayout.setVisibility(View.VISIBLE);
                                        tvRightSender.setText("Princess 👑");
                                        tvRightText.setText(text);
                                        tvRightTime.setText(formattedTime);
                                    } else {
                                        // Incoming (Hubby / AI)
                                        leftLayout.setVisibility(View.VISIBLE);
                                        rightLayout.setVisibility(View.GONE);

                                        if ("chatbot".equalsIgnoreCase(sender)) {
                                            tvLeftSender.setText("Hubby's Soul (AI) 💖");
                                        } else {
                                            tvLeftSender.setText("Hubby 🤵");
                                        }

                                        tvLeftText.setText(text);
                                        tvLeftTime.setText(formattedTime);
                                    }

                                    chatMessagesLayout.addView(msgView);

                                } catch (Exception e) {
                                    Log.e(TAG, "Error displaying message", e);
                                }
                            }

                            // Auto scroll to bottom
                            chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing chat response", e);
                } finally {
                    response.close();
                }
            }
        });
    }

    private void sendMessage() {
        final String messageText = etChatInput.getText().toString().trim();
        if (messageText.isEmpty()) {
            return;
        }

        if (userEmail == null || userEmail.isEmpty()) {
            Toast.makeText(this, "Configuration error. Link app first.", Toast.LENGTH_SHORT).show();
            return;
        }

        etChatInput.setText("");

        // Construct JSON Payload
        JSONObject payload = new JSONObject();
        try {
            payload.put("email", userEmail);
            payload.put("sender", "user");
            payload.put("message", messageText);
        } catch (Exception e) {
            Log.e(TAG, "JSON serialization error", e);
        }

        RequestBody body = RequestBody.create(
                payload.toString(),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(backendUrl + "/api/chat/send")
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to send message", e);
                runOnUiThread(() -> Toast.makeText(HubbySoulActivity.this, "Connection error. Failed to send message.", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    // Force refresh chat log immediately after sending
                    loadChats(true);
                } else {
                    Log.w(TAG, "Server error sending message: " + response.code());
                }
                response.close();
            }
        });
    }

    private String formatTime(String isoTimestamp) {
        try {
            // ISO 8601 parser
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            Date date = parser.parse(isoTimestamp);

            SimpleDateFormat formatter = new SimpleDateFormat("hh:mm a", Locale.getDefault());
            return formatter.format(date);
        } catch (Exception e) {
            try {
                // Fallback for timestamps without milliseconds
                SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                Date date = parser.parse(isoTimestamp);

                SimpleDateFormat formatter = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                return formatter.format(date);
            } catch (Exception ex) {
                return "";
            }
        }
    }

    private String UriEncode(String value) {
        try {
            return android.net.Uri.encode(value);
        } catch (Exception e) {
            return value;
        }
    }
}
