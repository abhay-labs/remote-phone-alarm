package com.example.remotealarm;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GiftViewActivity extends AppCompatActivity {
    private static final String TAG = "GiftViewActivity";

    private ViewPager2 viewPager;
    private TextView quoteText;
    private ProgressBar loadingBar;
    private LinearLayout emptyGiftsLayout;
    private ImageButton closeBtn;
    
    private final OkHttpClient httpClient = new OkHttpClient();
    private final List<String> imageUrls = new ArrayList<>();
    private GiftAdapter adapter;

    private static final String[] LOVE_QUOTES = {
        "“I love you not only for what you are, but for what I am when I am with you.”",
        "“You are my today and all of my tomorrows.”",
        "“If I know what love is, it is because of you.”",
        "“I swear I couldn't love you more than I do right now, and yet I know I will tomorrow.”",
        "“In all the world, there is no heart for me like yours.”",
        "“My love for you is a journey; starting at forever and ending at never.”",
        "“Every time I see you, I fall in love all over again.”",
        "“You are the best thing that's ever been mine.”",
        "“To the world you may be one person, but to one person you are the world.”",
        "“I want all of you, forever, you and me, every day.”",
        "“I love you, and I will love you until I die, and if there's a life after that, I'll love you then.”",
        "“You have bewitched me, body and soul, and I love, I love, I love you.”",
        "“Loving you was the best decision of my life.”",
        "“If I had a flower for every time I thought of you... I could walk through my garden forever.”",
        "“You are my heart, my life, my entire existence.”"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gift_view);

        // Bind views
        viewPager = findViewById(R.id.gift_viewpager);
        quoteText = findViewById(R.id.quote_text);
        loadingBar = findViewById(R.id.gift_loading);
        emptyGiftsLayout = findViewById(R.id.empty_gifts_layout);
        closeBtn = findViewById(R.id.close_btn);

        closeBtn.setOnClickListener(v -> finish());

        // Initialize adapter
        adapter = new GiftAdapter();
        viewPager.setAdapter(adapter);

        // Update quote when page changes
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                showRandomQuote();
            }
        });

        // Load quotes on startup
        showRandomQuote();

        // Load gifts
        fetchGifts();
    }

    private void showRandomQuote() {
        int index = new Random().nextInt(LOVE_QUOTES.length);
        quoteText.setText(LOVE_QUOTES[index]);
    }

    private void fetchGifts() {
        SharedPreferences prefs = getSharedPreferences("RemoteAlarmPrefs", MODE_PRIVATE);
        String backendUrl = prefs.getString("backend_url", "https://remote-phone-alarm.onrender.com");
        String email = prefs.getString("email", "");

        if (email.isEmpty()) {
            showEmptyState();
            return;
        }

        try {
            String url = backendUrl + "/api/gifts?email=" + URLEncoder.encode(email, "UTF-8");
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            loadingBar.setVisibility(View.VISIBLE);

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "Failed to load gifts", e);
                    runOnUiThread(() -> {
                        loadingBar.setVisibility(View.GONE);
                        Toast.makeText(GiftViewActivity.this, "Network error loading gifts", Toast.LENGTH_SHORT).show();
                        showEmptyState();
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    final String responseBody = response.body() != null ? response.body().string() : "";
                    runOnUiThread(() -> {
                        loadingBar.setVisibility(View.GONE);
                        try {
                            JSONObject json = new JSONObject(responseBody);
                            if (json.getBoolean("success")) {
                                JSONArray giftsArray = json.getJSONArray("gifts");
                                imageUrls.clear();
                                for (int i = 0; i < giftsArray.length(); i++) {
                                    JSONObject gift = giftsArray.getJSONObject(i);
                                    String giftUrl = backendUrl + gift.getString("url");
                                    imageUrls.add(giftUrl);
                                }

                                if (imageUrls.isEmpty()) {
                                    showEmptyState();
                                } else {
                                    emptyGiftsLayout.setVisibility(View.GONE);
                                    adapter.notifyDataSetChanged();
                                }
                            } else {
                                Log.e(TAG, "API failed: " + json.optString("error"));
                                showEmptyState();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "JSON parsing error", e);
                            showEmptyState();
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "URL encode error", e);
            showEmptyState();
        }
    }

    private void showEmptyState() {
        emptyGiftsLayout.setVisibility(View.VISIBLE);
        viewPager.setVisibility(View.GONE);
    }

    private class GiftAdapter extends RecyclerView.Adapter<GiftAdapter.GiftViewHolder> {

        @NonNull
        @Override
        public GiftViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gift_image, parent, false);
            return new GiftViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull GiftViewHolder holder, int position) {
            String imageUrl = imageUrls.get(position);
            holder.imageView.setTag(imageUrl);
            holder.imageView.setImageResource(android.R.color.transparent);

            // Fetch image in background thread using traditional HttpURLConnection
            new Thread(() -> {
                try {
                    URL url = new URL(imageUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setDoInput(true);
                    connection.connect();
                    InputStream input = connection.getInputStream();
                    final Bitmap bitmap = BitmapFactory.decodeStream(input);
                    input.close();

                    runOnUiThread(() -> {
                        if (imageUrl.equals(holder.imageView.getTag())) {
                            holder.imageView.setImageBitmap(bitmap);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error downloading image: " + e.getMessage(), e);
                }
            }).start();
        }

        @Override
        public int getItemCount() {
            return imageUrls.size();
        }

        class GiftViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;

            public GiftViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(R.id.gift_image_view);
            }
        }
    }
}
