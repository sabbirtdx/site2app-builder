package com.site2app.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.json.JSONObject;

/**
 * Firebase Cloud Messaging service.
 * - Registers the device token with the Site2App platform.
 * - Shows incoming push notifications.
 */
public class S2APushService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "site2app_default";

    @Override
    public void onNewToken(@NonNull String token) {
        registerToken(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        String title = data.containsKey("title") ? data.get("title") : message.getNotification() != null ? message.getNotification().getTitle() : "New notification";
        String body = data.containsKey("body") ? data.get("body") : message.getNotification() != null ? message.getNotification().getBody() : "";
        String imageUrl = data.containsKey("image") ? data.get("image")
                : message.getNotification() != null && message.getNotification().getImageUrl() != null
                    ? message.getNotification().getImageUrl().toString() : null;
        showNotification(title, body, imageUrl);
    }

    private void registerToken(String token) {
        String endpoint = AppConfig.get("s2a_push_register_url");
        if (endpoint == null || endpoint.isEmpty()) return;

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("token", token);
                payload.put("package_name", getPackageName());
                payload.put("device_id", UUID.randomUUID().toString());
                payload.put("platform", "android");

                URL url = new URL(endpoint);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                connection.getResponseCode();
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void showNotification(String title, String body, String imageUrl) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_fallback)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        // Image support: when the push carries an image URL, download it
        // and show it big (BigPictureStyle) so the notification is rich.
        if (imageUrl != null && !imageUrl.isEmpty()) {
            android.graphics.Bitmap image = downloadImage(imageUrl);
            if (image != null) {
                builder.setStyle(new NotificationCompat.BigPictureStyle()
                        .bigPicture(image)
                        .setSummaryText(body));
            }
        }

        manager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private android.graphics.Bitmap downloadImage(String url) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Site2App/1.0");
            connection.connect();
            try (java.io.InputStream in = connection.getInputStream()) {
                return android.graphics.BitmapFactory.decodeStream(in);
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
