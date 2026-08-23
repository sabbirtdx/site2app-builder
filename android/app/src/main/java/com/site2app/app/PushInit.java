package com.site2app.app;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * Wevlo push initializer.
 * App open হলে MainActivity এটা call করে।
 * কাজ: Firebase init → FCM token নেওয়া → Wevlo server-এ device register করা।
 * এই ফাইলটা শুধু Wevlo builds-এ থাকে।
 */
public class PushInit {

    private static final String TAG = "WevloPush";
    private static volatile boolean sInitialized = false;

    public static void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;

        if (PushConfig.WEVLO_SERVER_URL == null
                || PushConfig.WEVLO_SERVER_URL.startsWith("__")
                || PushConfig.WEVLO_SERVER_URL.isEmpty()) {
            Log.w(TAG, "Wevlo server URL not injected — skipping");
            return;
        }

        new Thread(() -> {
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseOptions options = new FirebaseOptions.Builder()
                            .setApiKey(PushConfig.FIREBASE_API_KEY)
                            .setApplicationId(PushConfig.FIREBASE_APP_ID)
                            .setProjectId(PushConfig.FIREBASE_PROJECT_ID)
                            .setGcmSenderId(PushConfig.FIREBASE_SENDER_ID)
                            .build();
                    FirebaseApp.initializeApp(context, options);
                    Log.d(TAG, "Firebase initialized");
                }

                FirebaseMessaging.getInstance().getToken()
                        .addOnSuccessListener(token -> {
                            if (token != null && !token.isEmpty()) {
                                Log.d(TAG, "Token received — registering device");
                                TokenRegistrar.register(context, token);
                            }
                        })
                        .addOnFailureListener(e ->
                                Log.e(TAG, "FCM token failed: " + e.getMessage()));

            } catch (Throwable e) {
                Log.e(TAG, "PushInit error: " + e.getMessage());
            }
        }).start();
    }
}
