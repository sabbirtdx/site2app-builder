package com.site2app.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * JavaScript bridge exposed to the website as window.Site2App.
 */
public class WebAppInterface {

    private final Activity activity;

    public WebAppInterface(Activity activity) {
        this.activity = activity;
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

    /** Basic device info for the website (optional integration). */
    @JavascriptInterface
    public String deviceInfo() {
        try {
            JSONObject info = new JSONObject();
            info.put("app", "site2app");
            info.put("model", android.os.Build.MODEL);
            info.put("android", android.os.Build.VERSION.RELEASE);
            info.put("sdk", android.os.Build.VERSION.SDK_INT);
            return info.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
