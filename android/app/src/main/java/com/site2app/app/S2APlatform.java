package com.site2app.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Platform ping: ONE background request does TWO jobs.
 *
 *  1) ANALYTICS — reports "open" / "page" events to the platform so the
 *     app owner can see installs/opens on their app page.
 *  2) UPDATE CHECK — the response carries the latest published version;
 *     when it is newer than this build, the app shows an "update
 *     available" notification whose tap opens the download page.
 *
 * Pure HttpURLConnection — compiles on every push-provider variant.
 */
public class S2APlatform {

    private static final String CHANNEL_ID = "site2app_updates";

    public static void ping(Context context, String event, String pageUrl) {
        final Context ctx = context.getApplicationContext();
        final String endpoint = AppConfig.get("s2a_platform_url");
        if (endpoint == null || endpoint.isEmpty()) {
            return;
        }

        new Thread(() -> {
            try {
                SharedPreferences prefs = ctx.getSharedPreferences("s2a_platform", Context.MODE_PRIVATE);
                String deviceId = prefs.getString("device_id", null);
                if (deviceId == null) {
                    deviceId = UUID.randomUUID().toString();
                    prefs.edit().putString("device_id", deviceId).apply();
                }

                JSONObject payload = new JSONObject();
                payload.put("package_name", ctx.getPackageName());
                payload.put("device_id", deviceId);
                payload.put("event", event == null ? "open" : event);
                if (pageUrl != null) {
                    payload.put("url", pageUrl);
                }

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
                StringBuilder sb = new StringBuilder();
                if (code == 200) {
                    java.io.InputStream in = connection.getInputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                    in.close();
                }
                connection.disconnect();

                if (code == 200 && sb.length() > 0) {
                    checkUpdate(ctx, sb.toString());
                }
            } catch (Exception ignored) {
                // never disturb the app
            }
        }).start();
    }

    private static void checkUpdate(Context ctx, String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            JSONObject latest = json.optJSONObject("latest");
            if (latest == null) {
                return;
            }
            int latestCode = latest.optInt("version_code", 0);
            int currentCode = 0;
            try {
                currentCode = Integer.parseInt(AppConfig.get("s2a_version_code"));
            } catch (Exception ignored) {
            }
            if (latestCode <= currentCode) {
                return;
            }

            final String versionName = latest.optString("version_name", "");
            final String downloadUrl = latest.optString("download_url", "");

            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                return; // system will ask for permission later
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT);
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(channel);
                }
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notif_fallback)
                    .setContentTitle("নতুন ভার্সন এসেছে" + (versionName.isEmpty() ? "" : " (v" + versionName + ")"))
                    .setContentText("আপডেট করতে এখানে চাপুন — নতুন ফিচার ও ফিক্স পাবেন।")
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            if (!downloadUrl.isEmpty()) {
                try {
                    Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                    PendingIntent pi = PendingIntent.getActivity(
                            ctx, 7701, open,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    builder.setContentIntent(pi);
                } catch (Throwable ignored) {
                }
            }

            NotificationManagerCompat.from(ctx).notify(7701, builder.build());
        } catch (Exception ignored) {
        }
    }
}
