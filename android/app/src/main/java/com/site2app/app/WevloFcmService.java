package com.site2app.app;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Wevlo push service (Firebase delivery channel).
 *
 * - onNewToken(): the FCM token changed/created → register it with the
 *   Wevlo push server so the platform can reach this device.
 * - onMessageReceived(): a push arrived (from the Wevlo server through
 *   FCM) → hand it to NotificationHelper to display.
 */
public class WevloFcmService extends FirebaseMessagingService {

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        TokenRegistrar.register(this, token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();

        String title = data.get("title");
        if (title == null || title.isEmpty()) {
            title = message.getNotification() != null && message.getNotification().getTitle() != null
                    ? message.getNotification().getTitle()
                    : "Notification";
        }

        String body = data.get("body");
        if (body == null || body.isEmpty()) {
            body = message.getNotification() != null && message.getNotification().getBody() != null
                    ? message.getNotification().getBody()
                    : "";
        }

        String image = data.get("image");
        if (image == null || image.isEmpty()) {
            image = data.get("imageUrl");
        }
        if (image == null || image.isEmpty()) {
            image = message.getNotification() != null ? String.valueOf(message.getNotification().getImageUrl()) : null;
            if ("null".equals(image)) {
                image = null;
            }
        }

        String url = data.get("url");

        NotificationHelper.show(this, title, body, image, url);
    }
}
