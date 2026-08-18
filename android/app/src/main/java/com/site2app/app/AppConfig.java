package com.site2app.app;

import android.content.Context;

/**
 * Reads the Site2App configuration that was baked into the app at build time.
 * All values live in res/values/config.xml (written by the project writer).
 */
public class AppConfig {

    private static Context ctx;

    public static void init(Context context) {
        ctx = context.getApplicationContext();
    }

    public static String get(String name) {
        if (ctx == null) return "";
        int id = ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
        return id == 0 ? "" : ctx.getString(id);
    }

    public static boolean flag(String name) {
        return "true".equals(get("s2a_" + name));
    }

    public static String homeUrl() {
        return get("s2a_home_url");
    }

    public static String websiteUrl() {
        return get("s2a_website_url");
    }

    public static String navigationType() {
        return get("s2a_navigation_type");
    }

    public static int navCount() {
        if (ctx == null) return 0;
        int id = ctx.getResources().getIdentifier("s2a_nav_count", "integer", ctx.getPackageName());
        return id == 0 ? 0 : ctx.getResources().getInteger(id);
    }

    public static String navId(int i) {
        return get("s2a_nav_" + i + "_id");
    }

    public static String navLabel(int i) {
        return get("s2a_nav_" + i + "_label");
    }

    public static String navUrl(int i) {
        return get("s2a_nav_" + i + "_url");
    }

    public static String navIcon(int i) {
        return get("s2a_nav_" + i + "_icon");
    }

    public static String admobBannerId() {
        if (ctx == null) return "";
        int id = ctx.getResources().getIdentifier("s2a_admob_banner_id", "string", ctx.getPackageName());
        return id == 0 ? "" : ctx.getString(id);
    }

    public static String admobInterstitialId() {
        if (ctx == null) return "";
        int id = ctx.getResources().getIdentifier("s2a_admob_interstitial_id", "string", ctx.getPackageName());
        return id == 0 ? "" : ctx.getString(id);
    }
}
