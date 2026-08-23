package com.site2app.app;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Registers this device's FCM token with the Wevlo push server.
 *
 * POST {server}/register-token
 *   { "token": "<fcm token>", "appId": "<package name>", "userAgent": "<device info>" }
 *
 * Runs on a background thread so the app never blocks.
 */
public class TokenRegistrar {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static void register(final Context context, final String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        if (PushConfig.WEVLO_SERVER_URL == null
                || PushConfig.WEVLO_SERVER_URL.isEmpty()
                || PushConfig.WEVLO_SERVER_URL.startsWith("__")) {
            return; // config not injected — nothing to register against
        }

        new Thread(() -> {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();

            try {
                JSONObject payload = new JSONObject();
                payload.put("token", token);
                payload.put("appId", PushConfig.APP_ID);
                payload.put("userAgent", Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);

                RequestBody body = RequestBody.create(payload.toString(), JSON);
                Request request = new Request.Builder()
                        .url(PushConfig.WEVLO_SERVER_URL + "/register-token")
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    // 2xx = registered; anything else we simply retry on the next launch
                }
            } catch (Exception ignored) {
                // the next token refresh or app start will retry
            }
        }).start();
    }
}
