package com.site2app.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.webkit.JavascriptInterface;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/**
 * JavaScript bridge exposed to the website as window.Site2App.
 *
 * Your website can call, for example:
 *   Site2App.notify("Komret", "আপনার অর্ডার কনফার্ম হয়েছে!");
 *   Site2App.share("Title", "https://…");
 *   Site2App.openExternal("https://…");
 *   Site2App.deviceInfo();
 *
 * When Firebase push is configured, the app also publishes the FCM token to
 * the website: window.S2A_FCM_TOKEN plus window.S2A.onToken(token) — so the
 * website can register the device with its OWN push system and send
 * notifications from the website side.
 */
public class WebAppInterface {

    private final Activity activity;
    private String fcmToken = "";

    public WebAppInterface(Activity activity) {
        this.activity = activity;
    }

    public void setFcmToken(String token) {
        this.fcmToken = token == null ? "" : token;
    }

    public String getFcmToken() {
        return this.fcmToken;
    }

    /** Show a local notification right from the website's JavaScript. */
    @JavascriptInterface
    public void notify(String title, String message) {
        try {
            if (title == null || title.isEmpty()) {
                title = "Notification";
            }
            if (message == null || message.isEmpty()) {
                message = " ";
            }
            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 910);
                return; // the website can call notify() again after the user allows
            }
            String channelId = "s2a_web";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        channelId, "Website notifications", NotificationManager.IMPORTANCE_DEFAULT);
                NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(channel);
                }
            }
            NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, channelId)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title.length() > 60 ? title.substring(0, 60) : title)
                    .setContentText(message.length() > 240 ? message.substring(0, 240) : message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message.length() > 240 ? message.substring(0, 240) : message))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);
            try {
                builder.setContentIntent(android.app.PendingIntent.getActivity(
                        activity, 0,
                        new Intent(activity, activity.getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
            } catch (Throwable ignored) {
            }
            NotificationManagerCompat.from(activity).notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), builder.build());
        } catch (Throwable ignored) {
        }
    }

    /** Share a link from the website. */
    @JavascriptInterface
    public void share(String title, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, title == null ? "" : title);
            intent.putExtra(Intent.EXTRA_TEXT, (title == null ? "" : title + "\n") + (url == null ? "" : url));
            activity.startActivity(Intent.createChooser(intent, "Share"));
        } catch (Exception ignored) {
        }
    }

    /** Open a URL in the external browser. */
    @JavascriptInterface
    public void openExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** The FCM push token (empty when Firebase is not configured). */
    @JavascriptInterface
    public String fcmToken() {
        return this.fcmToken;
    }

    /** Basic device info for the website (optional integration). */
    @JavascriptInterface
    public String deviceInfo() {
        try {
            JSONObject info = new JSONObject();
            info.put("app", "site2app");
            info.put("model", android.os.Build.MODEL);
            info.put("android", android.os.Build.VERSION.RELEASE);
            info.put("sdk", android.os.Build.VERSION.SDK_INT);
            info.put("fcmToken", this.fcmToken);
            return info.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
