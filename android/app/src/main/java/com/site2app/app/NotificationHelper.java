package com.site2app.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Builds and shows a system notification.
 *
 * - image URL present → downloads it on a background thread and shows a
 *   BigPictureStyle notification.
 * - no image           → BigTextStyle notification.
 * - Android 13+        → POST_NOTIFICATIONS permission is checked first.
 */
public class NotificationHelper {

    private static final String CHANNEL_ID = "wevlo_default";

    public static void show(Context context, String title, String body, String imageUrl, String url) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                return; // system will ask for permission; next push shows normally
            }
        } catch (Throwable ignored) {
        }

        ensureChannel(context);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_fallback)
                .setContentTitle(title == null || title.isEmpty() ? "Notification" : title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (url != null && !url.isEmpty()) {
            try {
                Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                PendingIntent pi = PendingIntent.getActivity(
                        context, (int) System.currentTimeMillis(), open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.setContentIntent(pi);
            } catch (Throwable ignored) {
            }
        }

        final int id = (int) (System.currentTimeMillis() & 0x7FFFFFFF);

        if (imageUrl != null && !imageUrl.isEmpty()) {
            // download the image off the main thread, then post the rich notification
            final NotificationCompat.Builder finalBuilder = builder;
            new Thread(() -> {
                Bitmap image = downloadImage(imageUrl);
                if (image != null) {
                    finalBuilder.setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(image)
                            .setSummaryText(body));
                } else {
                    finalBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(body));
                }
                try {
                    NotificationManagerCompat.from(context).notify(id, finalBuilder.build());
                } catch (Throwable ignored) {
                }
            }).start();
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(body));
            try {
                NotificationManagerCompat.from(context).notify(id, builder.build());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
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
