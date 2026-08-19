package com.site2app.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Shown when the app hits an uncaught exception. Displays the exact stack
 * trace so it can be reported (screenshot) and fixed quickly.
 */
public class CrashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String stack = getIntent().getStringExtra("stack");
        if (stack == null || stack.isEmpty()) {
            stack = "No stack trace captured.";
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);
        root.setBackgroundColor(Color.parseColor("#F8F9FA"));

        TextView title = new TextView(this);
        title.setText("App crashed — please send this screenshot to support");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#C0392B"));
        title.setGravity(Gravity.START);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(this);
        meta.setText("Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nApp version: " + getVersionName());
        meta.setTextSize(13);
        meta.setTextColor(Color.parseColor("#555555"));
        meta.setPadding(0, 12, 0, 12);
        root.addView(meta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        TextView trace = new TextView(this);
        trace.setText(stack);
        trace.setTextSize(12);
        trace.setTextColor(Color.parseColor("#222222"));
        trace.setBackgroundColor(Color.WHITE);
        trace.setPadding(16, 16, 16, 16);
        trace.setHorizontallyScrolling(true);
        scroll.addView(trace, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button restart = new Button(this);
        restart.setText("Restart app");
        restart.setAllCaps(false);
        restart.setOnClickListener(v -> {
            try {
                Intent launcher = new Intent(this, MainActivity.class);
                launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(launcher);
            } catch (Throwable ignored) {
            }
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 24, 0, 0);
        root.addView(restart, lp);

        setContentView(root);
    }

    private String getVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
