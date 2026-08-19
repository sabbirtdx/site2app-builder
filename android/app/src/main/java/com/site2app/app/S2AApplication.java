package com.site2app.app;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Global crash logger. If the app ever crashes, the full stack trace is
 * written to a file (s2a-crash.log) so it can be reported and fixed fast.
 * The user-facing error screen lives in MainActivity.showFatalError().
 */
public class S2AApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashLog(thread, throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                System.exit(2);
            }
        });
    }

    private void writeCrashLog(Thread thread, Throwable throwable) {
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
                throwable.printStackTrace(pw);
                pw.println();
            }
            Log.e("Site2App", "Crash logged to " + log.getAbsolutePath(), throwable);
        } catch (Throwable ignored) {
        }
    }
}
