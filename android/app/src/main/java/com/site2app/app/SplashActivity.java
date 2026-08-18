package com.site2app.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Splash screen shown while the app is starting.
 */
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppConfig.init(getApplicationContext());

        setContentView(R.layout.activity_splash);

        ImageView splashImage = findViewById(R.id.splash_image);
        TextView splashTitle = findViewById(R.id.splash_title);

        int resId = getResources().getIdentifier("splash", "drawable", getPackageName());
        if (resId != 0) {
            splashImage.setImageResource(resId);
        }
        splashTitle.setText(getString(R.string.app_name));

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }, 1400);
    }
}
