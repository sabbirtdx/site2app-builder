package com.site2app.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * SELF-HOSTED push (no Firebase, no third party):
 * the app polls the Site2App site every 5 minutes, receives the
 * notifications created there and shows them like normal push messages.
 * Started from MainActivity when the app's push provider is "site".
 */
public class S2APushPoller {

    private static final String CHANNEL_ID = "site2app_default";
    private static final long INTERVAL_MS = 5 * 60 * 1000L;

    public static void start(Context appContext) {
        final Context ctx = appContext.getApplicationContext();
        ensureChannel(ctx);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    try {
                        registerIfNeeded(ctx);
                        poll(ctx);
                    } catch (Throwable ignored) {
                    }
                }).start();
                handler.postDelayed(this, INTERVAL_MS);
            }
        };
        handler.postDelayed(tick, 2000);
    }

    private static void registerIfNeeded(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("s2a_push", Context.MODE_PRIVATE);
        if (prefs.getBoolean("registered", false)) {
            return;
        }
        String endpoint = AppConfig.get("s2a_push_register_url");
        if (endpoint == null || endpoint.isEmpty()) {
            return;
        }
        try {
            String deviceId = prefs.getString("device_id", null);
            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString();
                prefs.edit().putString("device_id", deviceId).apply();
            }
            JSONObject payload = new JSONObject();
            payload.put("token", deviceId);
            payload.put("package_name", ctx.getPackageName());
            payload.put("device_id", deviceId);

            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            connection.disconnect();
            if (code >= 200 && code < 300) {
                prefs.edit().putBoolean("registered", true).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void poll(Context ctx) {
        String endpoint = AppConfig.get("s2a_push_register_url");
        String appId = AppConfig.get("s2a_app_id");
        if (endpoint == null || endpoint.isEmpty() || appId == null || appId.isEmpty()) {
            return;
        }
        SharedPreferences prefs = ctx.getSharedPreferences("s2a_push", Context.MODE_PRIVATE);
        String deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            return;
        }
        try {
            String pendingUrl = endpoint.replaceAll("/register$", "/pending")
                    + "?app_id=" + appId
                    + "&device_id=" + deviceId
                    + "&token=" + deviceId;

            HttpURLConnection connection = (HttpURLConnection) new URL(pendingUrl).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            int code = connection.getResponseCode();
            if (code != 200) {
                connection.disconnect();
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = connection.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            connection.disconnect();

            JSONObject json = new JSONObject(sb.toString());
            JSONArray pushes = json.optJSONArray("pushes");
            if (pushes == null || pushes.length() == 0) {
                return;
            }
            for (int i = 0; i < pushes.length(); i++) {
                JSONObject p = pushes.getJSONObject(i);
                show(ctx,
                        p.optString("title", "Notification"),
                        p.optString("body", ""),
                        p.optString("image", ""),
                        (int) (System.currentTimeMillis() + i));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private static void show(Context ctx, String title, String body, String imageUrl, int id) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notif_fallback)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            if (imageUrl != null && !imageUrl.isEmpty()) {
                Bitmap image = downloadImage(imageUrl);
                if (image != null) {
                    builder.setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(image)
                            .setSummaryText(body));
                }
            }
            NotificationManagerCompat.from(ctx).notify(id, builder.build());
        } catch (Throwable ignored) {
        }
    }

    private static Bitmap downloadImage(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.connect();
            try (InputStream in = connection.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
