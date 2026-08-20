package com.site2app.app;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Global crash guard.
 *
 * 1. Every launch writes a "launching" flag; MainActivity clears it once it
 *    has fully started. If the flag is still there on the NEXT launch, the
 *    previous run crashed (possibly natively — try/catch cannot catch those)
 *    and MainActivity shows the safe fallback (open the site in the browser).
 * 2. Uncaught Java exceptions are written to s2a-crash.log and shown on a
 *    dedicated crash screen, so the exact reason is always visible.
 */
public class S2AApplication extends Application {

    private static boolean sCrashedBefore = false;

    public static boolean crashedBefore() {
        return sCrashedBefore;
    }

    private static File launchFlagFile(Application app) {
        File dir = app.getExternalFilesDir(null);
        if (dir == null) {
            dir = app.getFilesDir();
        }
        return new File(dir, "s2a-launching.flag");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Flags are read from resources — initialize AppConfig here too.
        AppConfig.init(getApplicationContext());

        // Detect a previous crash (flag survived from the last launch).
        File flag = launchFlagFile(this);
        sCrashedBefore = flag.exists();
        try {
            if (!flag.getParentFile().exists() && !flag.getParentFile().mkdirs()) {
                // ignore
            }
            try (FileWriter fw = new FileWriter(flag)) {
                fw.write(String.valueOf(System.currentTimeMillis()));
            }
        } catch (Throwable ignored) {
        }

        // OneSignal (push provider = onesignal) — reflection keeps this
        // compile-safe even when the SDK dependency is not bundled.
        try {
            if (AppConfig.flag("push_onesignal_enabled")) {
                String appId = AppConfig.get("s2a_onesignal_app_id");
                if (appId != null && !appId.isEmpty()) {
                    Class<?> cls = Class.forName("com.onesignal.OneSignal");
                    cls.getMethod("initWithContext", Context.class).invoke(null, this);
                    cls.getMethod("setAppId", String.class).invoke(null, appId);
                }
            }
        } catch (Throwable ignored) {
        }

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String stack = stackOf(throwable);
            try {
                writeCrashLog(thread, throwable, stack);
            } catch (Throwable ignored) {
            }
            try {
                Intent i = new Intent(S2AApplication.this, CrashActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                i.putExtra("stack", stack);
                startActivity(i);
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                System.exit(2);
            }
        });
    }

    public static void clearLaunchFlag(Application app) {
        try {
            File flag = launchFlagFile(app);
            if (flag.exists()) {
                //noinspection ResultOfMethodCallIgnored
                flag.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void resetCrashFlag(Application app) {
        sCrashedBefore = false;
        clearLaunchFlag(app);
    }

    private static String stackOf(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    private void writeCrashLog(Thread thread, Throwable throwable, String stack) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                dir = getFilesDir();
            }
            File log = new File(dir, "s2a-crash.log");
            try (FileWriter fw = new FileWriter(log, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " ===");
                pw.println("thread: " + thread.getName());
                pw.println(stack);
                pw.println();
            }
            Log.e("Site2App", "Crash logged to " + log.getAbsolutePath(), throwable);
        } catch (Throwable ignored) {
        }
    }
}
