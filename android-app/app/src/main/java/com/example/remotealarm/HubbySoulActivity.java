package com.example.remotealarm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

public class HubbySoulActivity extends AppCompatActivity {
    private static final String TAG = "HubbySoulActivity";

    private ScrollView chatScrollView;
    private LinearLayout chatMessagesLayout;
    private EditText etChatInput;
    private ImageButton btnChatSend;
    private TextView tvChatSubtitle;
    private ImageButton btnChatAttach;
    private LinearLayout layoutAttachPreview;
    private ImageView ivPreviewThumbnail;
    private TextView tvPreviewFilename;
    private ImageButton btnClearAttach;

    private SharedPreferences prefs;
    private String backendUrl;
    private String userEmail;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Handler pollHandler = new Handler();
    private int currentMessageCount = 0;
    private String chatbotMode = "chatbot";

    private Uri selectedFileUri = null;
    private String selectedFileType = null; // "image", "video", "pdf"
    private String selectedFileName = null;

    private ActivityResultLauncher<String> filePickerLauncher;

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
        btnChatAttach = findViewById(R.id.btn_chat_attach);
        layoutAttachPreview = findViewById(R.id.layout_attach_preview);
        ivPreviewThumbnail = findViewById(R.id.iv_preview_thumbnail);
        tvPreviewFilename = findViewById(R.id.tv_preview_filename);
        btnClearAttach = findViewById(R.id.btn_clear_attach);

        // File picker setup
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) {
                            handleSelectedFile(uri);
                        }
                    }
                }
        );

        if (btnChatAttach != null) {
            btnChatAttach.setOnClickListener(v -> filePickerLauncher.launch("*/*"));
        }

        if (btnClearAttach != null) {
            btnClearAttach.setOnClickListener(v -> clearAttachmentSelection());
        }

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
                        final String modeFromServer = json.optString("chatbotMode", "chatbot");
                        chatbotMode = modeFromServer;

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
                                    JSONObject attachment = msg.optJSONObject("attachment");

                                    View msgView = inflater.inflate(R.layout.item_chat_message, chatMessagesLayout, false);

                                    View leftLayout = msgView.findViewById(R.id.layout_left_message);
                                    View rightLayout = msgView.findViewById(R.id.layout_right_message);

                                    TextView tvLeftSender = msgView.findViewById(R.id.tv_left_sender);
                                    TextView tvLeftText = msgView.findViewById(R.id.tv_left_text);
                                    TextView tvLeftTime = msgView.findViewById(R.id.tv_left_time);

                                    TextView tvRightSender = msgView.findViewById(R.id.tv_right_sender);
                                    TextView tvRightText = msgView.findViewById(R.id.tv_right_text);
                                    TextView tvRightTime = msgView.findViewById(R.id.tv_right_time);

                                    // Attachment Views
                                    ImageView ivLeftImage = msgView.findViewById(R.id.iv_left_image);
                                    LinearLayout layoutLeftVideo = msgView.findViewById(R.id.layout_left_video);
                                    TextView tvLeftVideoName = msgView.findViewById(R.id.tv_left_video_name);
                                    LinearLayout layoutLeftFile = msgView.findViewById(R.id.layout_left_file);
                                    TextView tvLeftFileName = msgView.findViewById(R.id.tv_left_file_name);

                                    ImageView ivRightImage = msgView.findViewById(R.id.iv_right_image);
                                    LinearLayout layoutRightVideo = msgView.findViewById(R.id.layout_right_video);
                                    TextView tvRightVideoName = msgView.findViewById(R.id.tv_right_video_name);
                                    LinearLayout layoutRightFile = msgView.findViewById(R.id.layout_right_file);
                                    TextView tvRightFileName = msgView.findViewById(R.id.tv_right_file_name);

                                    String formattedTime = formatTime(timestamp);

                                    boolean isUser = "user".equalsIgnoreCase(sender);

                                    if (ivLeftImage != null) ivLeftImage.setVisibility(View.GONE);
                                    if (layoutLeftVideo != null) layoutLeftVideo.setVisibility(View.GONE);
                                    if (layoutLeftFile != null) layoutLeftFile.setVisibility(View.GONE);
                                    if (ivRightImage != null) ivRightImage.setVisibility(View.GONE);
                                    if (layoutRightVideo != null) layoutRightVideo.setVisibility(View.GONE);
                                    if (layoutRightFile != null) layoutRightFile.setVisibility(View.GONE);

                                    if (isUser) {
                                        // Outgoing (Princess)
                                        leftLayout.setVisibility(View.GONE);
                                        rightLayout.setVisibility(View.VISIBLE);
                                        tvRightSender.setText("Princess 👑");
                                        tvRightText.setText(text);
                                        tvRightTime.setText(formattedTime);

                                        if (attachment != null) {
                                            setupAttachmentView(attachment, ivRightImage, layoutRightVideo, tvRightVideoName, layoutRightFile, tvRightFileName);
                                        }
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

                                        if (attachment != null) {
                                            setupAttachmentView(attachment, ivLeftImage, layoutLeftVideo, tvLeftVideoName, layoutLeftFile, tvLeftFileName);
                                        }
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
        if (messageText.isEmpty() && selectedFileUri == null) {
            return;
        }

        if (userEmail == null || userEmail.isEmpty()) {
            Toast.makeText(this, "Configuration error. Link app first.", Toast.LENGTH_SHORT).show();
            return;
        }

        etChatInput.setText("");

        if (selectedFileUri != null) {
            uploadAttachmentAndSend(messageText);
        } else {
            // 1. Send the user's message to the server
            sendChatMessageToServer("user", messageText, null);

            // 2. If chatbot mode is active, generate and send the chatbot response locally after a short delay
            if ("chatbot".equalsIgnoreCase(chatbotMode)) {
                final String botReplyText = ChatbotEngine.generateResponse(messageText);
                new Handler(getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        sendChatMessageToServer("chatbot", botReplyText, null);
                    }
                }, 800); // 800ms natural delay to simulate thinking/typing
            }
        }
    }

    private void uploadAttachmentAndSend(final String messageText) {
        Toast.makeText(this, "Uploading attachment...", Toast.LENGTH_SHORT).show();

        String uploadUrl = backendUrl + "/api/chat/upload?email=" + UriEncode(userEmail);
        String mimeType = getContentResolver().getType(selectedFileUri);
        if (mimeType == null) mimeType = "application/octet-stream";

        RequestBody fileBody = createRequestBodyFromUri(selectedFileUri, MediaType.parse(mimeType));
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", selectedFileName, fileBody)
                .build();

        Request request = new Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Attachment upload failed", e);
                runOnUiThread(() -> Toast.makeText(HubbySoulActivity.this, "Failed to upload file", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String respStr = response.body().string();
                        JSONObject json = new JSONObject(respStr);
                        if (json.getBoolean("success")) {
                            final JSONObject attachmentJson = json.getJSONObject("attachment");

                            runOnUiThread(() -> {
                                String msg = messageText;
                                if (msg.isEmpty()) {
                                    msg = selectedFileName;
                                }
                                sendChatMessageToServer("user", msg, attachmentJson);
                                clearAttachmentSelection();
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing upload response", e);
                    }
                } else {
                    Log.e(TAG, "Server error uploading attachment: " + response.code());
                    runOnUiThread(() -> Toast.makeText(HubbySoulActivity.this, "Server error uploading file", Toast.LENGTH_SHORT).show());
                }
                response.close();
            }
        });
    }

    private void sendChatMessageToServer(final String sender, final String messageText, final JSONObject attachment) {
        // Construct JSON Payload
        JSONObject payload = new JSONObject();
        try {
            payload.put("email", userEmail);
            payload.put("sender", sender);
            payload.put("message", messageText);
            if (attachment != null) {
                payload.put("attachment", attachment);
            }
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
                Log.e(TAG, "Failed to send message: " + sender, e);
                if ("user".equals(sender)) {
                    runOnUiThread(() -> Toast.makeText(HubbySoulActivity.this, "Connection error. Failed to send message.", Toast.LENGTH_SHORT).show());
                }
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

    private void setupAttachmentView(JSONObject attachment, ImageView ivImage, LinearLayout layoutVideo, TextView tvVideoName, LinearLayout layoutFile, TextView tvFileName) {
        try {
            String type = attachment.getString("type");
            String url = attachment.getString("url");
            String name = attachment.optString("name", "file");

            final String absoluteUrl;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                absoluteUrl = url;
            } else {
                String cleanUrl = url.startsWith("/") ? url : "/" + url;
                if (backendUrl.endsWith("/")) {
                    absoluteUrl = backendUrl.substring(0, backendUrl.length() - 1) + cleanUrl;
                } else {
                    absoluteUrl = backendUrl + cleanUrl;
                }
            }

            if ("image".equals(type)) {
                if (ivImage != null) {
                    ivImage.setVisibility(View.VISIBLE);
                    Glide.with(this).load(absoluteUrl).placeholder(android.R.drawable.progress_horizontal).into(ivImage);
                    ivImage.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(absoluteUrl));
                        startActivity(intent);
                    });
                }
            } else if ("video".equals(type)) {
                if (ivImage != null) {
                    ivImage.setVisibility(View.VISIBLE);
                    // Load the first frame of the video as a thumbnail using Glide
                    Glide.with(this).load(absoluteUrl).placeholder(android.R.drawable.progress_horizontal).into(ivImage);
                    ivImage.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.parse(absoluteUrl), "video/*");
                        startActivity(intent);
                    });
                }
                if (layoutVideo != null) {
                    layoutVideo.setVisibility(View.VISIBLE);
                    if (tvVideoName != null) {
                        tvVideoName.setText("Play: " + name);
                    }
                    layoutVideo.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.parse(absoluteUrl), "video/*");
                        startActivity(intent);
                    });
                }
            } else {
                if (layoutFile != null) {
                    layoutFile.setVisibility(View.VISIBLE);
                    if (tvFileName != null) {
                        tvFileName.setText(name);
                    }
                    layoutFile.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        if ("pdf".equals(type)) {
                            intent.setDataAndType(Uri.parse(absoluteUrl), "application/pdf");
                        } else {
                            intent.setData(Uri.parse(absoluteUrl));
                        }
                        startActivity(intent);
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up attachment view", e);
        }
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

    private void handleSelectedFile(Uri uri) {
        selectedFileUri = uri;
        selectedFileName = getFileName(uri);
        selectedFileType = getFileType(uri);

        if (layoutAttachPreview != null) {
            layoutAttachPreview.setVisibility(View.VISIBLE);
        }

        if (tvPreviewFilename != null) {
            tvPreviewFilename.setText("Selected: " + selectedFileName);
        }

        if (ivPreviewThumbnail != null) {
            if ("image".equals(selectedFileType)) {
                Glide.with(this).load(uri).into(ivPreviewThumbnail);
            } else if ("video".equals(selectedFileType)) {
                Glide.with(this).load(uri).into(ivPreviewThumbnail);
            } else {
                ivPreviewThumbnail.setImageResource(android.R.drawable.ic_menu_save);
            }
        }
    }

    private void clearAttachmentSelection() {
        selectedFileUri = null;
        selectedFileName = null;
        selectedFileType = null;

        if (layoutAttachPreview != null) {
            layoutAttachPreview.setVisibility(View.GONE);
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting file name", e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private String getFileType(Uri uri) {
        String mimeType = getContentResolver().getType(uri);
        if (mimeType == null) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (extension != null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
            }
        }

        if (mimeType != null) {
            if (mimeType.startsWith("image/")) {
                return "image";
            } else if (mimeType.startsWith("video/")) {
                return "video";
            } else if (mimeType.equals("application/pdf")) {
                return "pdf";
            }
        }
        String name = getFileName(uri).toLowerCase();
        if (name.endsWith(".pdf")) {
            return "pdf";
        } else if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".3gp") || name.endsWith(".webm")) {
            return "video";
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".webp")) {
            return "image";
        }

        return "other";
    }

    private RequestBody createRequestBodyFromUri(final Uri uri, final MediaType mediaType) {
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return mediaType;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    if (inputStream == null) return;
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        sink.write(buffer, 0, read);
                    }
                }
            }
        };
    }
}
