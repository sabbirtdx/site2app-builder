package com.site2app.app;

/**
 * Wevlo push configuration.
 *
 * The project writer injects the real values at build time:
 *   __WEVLO_SERVER_URL__  → the Wevlo push server URL
 *   __WEVLO_APP_ID__      → this app's package name (the App ID)
 *
 * This file only ships in apps whose push provider is "wevlo".
 */
public final class PushConfig {

    public static final String WEVLO_SERVER_URL = "__WEVLO_SERVER_URL__";
    public static final String APP_ID = "__WEVLO_APP_ID__";

    private PushConfig() {
    }
}
