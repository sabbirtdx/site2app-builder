package com.site2app.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Main WebView activity - the entire generated app runs here.
 * All behavior is driven by AppConfig values written at build time.
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private MaterialToolbar toolbar;
    private BottomNavigationView bottomNav;
    private LinearLayout topNav;
    private android.widget.HorizontalScrollView topNavScroll;
    private DrawerLayout drawerLayout;
    private NavigationView navView;
    private AdView adView;

    private String currentUrl = "";
    private int pagesLoaded = 0;
    private final List<NavItem> navItems = new ArrayList<>();

    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private InterstitialAd interstitialAd;
    private boolean interstitialShown = false;
    private long lastBackPress = 0;

    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<Intent> cameraLauncher;

    private static class NavItem {
        final String id;
        final String label;
        final String url;
        final String icon;

        NavItem(String id, String label, String url, String icon) {
            this.id = id;
            this.label = label;
            this.url = url;
            this.icon = icon;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppConfig.init(getApplicationContext());
        setContentView(R.layout.activity_main);

        bindViews();
        loadNavItems();
        setupToolbar();
        setupLaunchers();
        setupWebView();
        setupNavigation();
        setupAdMob();

        if (AppConfig.flag("push_enabled") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 900);
            }
        }

        if (savedInstanceState == null) {
            String home = AppConfig.homeUrl();
            webView.loadUrl(home == null || home.isEmpty() ? "https://example.com" : home);
        }
    }

    private void bindViews() {
        webView = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        progressBar = findViewById(R.id.progress_bar);
        toolbar = findViewById(R.id.toolbar);
        bottomNav = findViewById(R.id.bottom_nav);
        topNav = findViewById(R.id.top_nav);
        topNavScroll = findViewById(R.id.top_nav_scroll);
        drawerLayout = findViewById(R.id.drawer_layout);
        navView = findViewById(R.id.nav_view);
        adView = findViewById(R.id.ad_view);

        toolbar.setTitle(getString(R.string.app_name));
        toolbar.setOnMenuItemClickListener(this::onToolbarItem);
        setSupportActionBar(toolbar);
    }

    private void loadNavItems() {
        navItems.clear();
        for (int i = 0; i < AppConfig.navCount(); i++) {
            String id = AppConfig.navId(i);
            String label = AppConfig.navLabel(i);
            String url = AppConfig.navUrl(i);
            String icon = AppConfig.navIcon(i);
            if (id == null || id.isEmpty()) {
                id = "item" + i;
            }
            if (label == null || label.isEmpty()) {
                label = "Page " + (i + 1);
            }
            if (url == null || url.isEmpty()) {
                url = AppConfig.homeUrl();
            }
            navItems.add(new NavItem(id, label, url, icon == null ? "link" : icon));
        }
    }

    private void setupToolbar() {
        String navType = AppConfig.navigationType();
        if ("hamburger".equals(navType) && !navItems.isEmpty()) {
            toolbar.setNavigationIcon(R.drawable.ic_hamburger);
            toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(Gravity.START));
        } else if (AppConfig.flag("show_back_button")) {
            toolbar.setNavigationIcon(R.drawable.ic_back);
            toolbar.setNavigationOnClickListener(v -> goBack());
        } else {
            toolbar.setNavigationIcon(null);
        }
    }

    private void setupLaunchers() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) {
                        return;
                    }
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData()));
                    } else {
                        filePathCallback.onReceiveValue(null);
                    }
                    filePathCallback = null;
                });

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) {
                        return;
                    }
                    if (result.getResultCode() == RESULT_OK && cameraUri != null) {
                        filePathCallback.onReceiveValue(new Uri[]{cameraUri});
                    } else {
                        filePathCallback.onReceiveValue(null);
                    }
                    filePathCallback = null;
                    cameraUri = null;
                });
    }

    private boolean onToolbarItem(MenuItem item) {
        if (item.getItemId() == R.id.action_home) {
            webView.loadUrl(AppConfig.homeUrl());
            return true;
        }
        if (item.getItemId() == R.id.action_share) {
            shareCurrentPage();
            return true;
        }
        return false;
    }

    private void shareCurrentPage() {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name));
            intent.putExtra(Intent.EXTRA_TEXT, currentUrl);
            startActivity(Intent.createChooser(intent, "Share"));
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(AppConfig.flag("zoom_enabled"));
        settings.setBuiltInZoomControls(AppConfig.flag("zoom_enabled"));
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(AppConfig.flag("file_upload_enabled"));
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setGeolocationEnabled(AppConfig.flag("location_enabled"));
        settings.setUserAgentString(settings.getUserAgentString() + " Site2App");

        CookieManager.getInstance().setAcceptCookie(true);

        webView.addJavascriptInterface(new WebAppInterface(this), "Site2App");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    if (newProgress < 100) {
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.setProgress(newProgress);
                    } else {
                        progressBar.setVisibility(View.GONE);
                    }
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!AppConfig.flag("file_upload_enabled")) {
                    Toast.makeText(MainActivity.this, "File upload is disabled", Toast.LENGTH_SHORT).show();
                    return false;
                }
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent contentIntent = params.createIntent();
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE);

                Intent chooser = Intent.createChooser(contentIntent, "Choose file");

                if (AppConfig.flag("camera_enabled")
                        && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        File photoFile = File.createTempFile("s2a_capture_", ".jpg", getCacheDir());
                        cameraUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", photoFile);
                        Intent cameraIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                        cameraIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
                    } catch (IOException ignored) {
                    }
                }

                try {
                    fileChooserLauncher.launch(chooser);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "No file manager found", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, AppConfig.flag("location_enabled"), false);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

                switch (scheme) {
                    case "http":
                    case "https":
                        return handleHttpUrl(view, uri);
                    case "tel":
                        return openDialer(uri);
                    case "mailto":
                        return openMail(uri);
                    case "sms":
                        return openSms(uri);
                    case "whatsapp":
                        return openWhatsApp(uri);
                    case "market":
                        return openMarket(uri);
                    case "geo":
                        return openExternal(uri);
                    case "intent":
                        return openIntent(uri);
                    default:
                        // Block unknown schemes (file:, javascript:, etc.)
                        return true;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentUrl = url;
                progressBar.setVisibility(View.VISIBLE);
                swipeRefresh.setRefreshing(true);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url;
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                highlightCurrentNav(url);
                pagesLoaded++;
                if (interstitialAd != null && !interstitialShown && pagesLoaded >= 2) {
                    interstitialAd.show(MainActivity.this);
                    interstitialShown = true;
                }
            }
        });

        if (AppConfig.flag("downloads_enabled")) {
            webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                downloadFile(url, fileName);
            });
        } else {
            webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                    Toast.makeText(MainActivity.this, "Downloads are disabled", Toast.LENGTH_SHORT).show());
        }

        swipeRefresh.setOnRefreshListener(() -> webView.reload());
        swipeRefresh.setColorSchemeResources(android.R.color.holo_blue_light, android.R.color.holo_green_light, android.R.color.holo_orange_light);
    }

    private boolean handleHttpUrl(WebView view, Uri uri) {
        String host = uri.getHost();
        String websiteHost = Uri.parse(AppConfig.websiteUrl()).getHost();
        boolean isExternal = host != null && websiteHost != null && !host.equalsIgnoreCase(websiteHost);

        if (isExternal && AppConfig.flag("open_links_external")) {
            return openExternal(uri);
        }

        // External links inside the app (when not forced outside)
        if (isExternal && !AppConfig.flag("external_links_allowed")) {
            return false; // load in WebView anyway (single web experience)
        }

        view.loadUrl(uri.toString());
        return true;
    }

    private boolean openDialer(Uri uri) {
        if (!AppConfig.flag("phone_links_enabled")) {
            return true;
        }
        Intent intent = new Intent(Intent.ACTION_DIAL, uri);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No dialer app found", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean openMail(Uri uri) {
        if (!AppConfig.flag("email_links_enabled")) {
            return true;
        }
        Intent intent = new Intent(Intent.ACTION_SENDTO, uri);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean openSms(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SENDTO, uri);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No SMS app found", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean openWhatsApp(Uri uri) {
        if (!AppConfig.flag("whatsapp_links_enabled")) {
            return true;
        }
        try {
            String url = uri.toString();
            if (url.startsWith("https://wa.me/") || url.startsWith("http://wa.me/")) {
                String phone = uri.getPath().replaceFirst("/", "");
                String text = uri.getQueryParameter("text");
                String wa = "https://api.whatsapp.com/send?phone=" + phone + (text != null ? "&text=" + text : "");
                return openExternal(Uri.parse(wa));
            }
            return openExternal(uri);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean openMarket(Uri uri) {
        return openExternal(uri);
    }

    private boolean openIntent(Uri uri) {
        try {
            Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open this link", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    private void downloadFile(String url, String fileName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 901);
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType("application/octet-stream");
        request.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
        request.setTitle(fileName);
        request.setDescription(getString(R.string.app_name) + " download");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) {
            try {
                manager.enqueue(request);
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupNavigation() {
        String navType = AppConfig.navigationType();
        if (navItems.isEmpty()) {
            return;
        }

        switch (navType) {
            case "top":
                setupTopNav();
                break;
            case "hamburger":
                setupDrawerNav();
                break;
            case "bottom":
            default:
                setupBottomNav();
                break;
        }
    }

    private void setupBottomNav() {
        bottomNav.setVisibility(View.VISIBLE);
        bottomNav.getMenu().clear();
        for (NavItem item : navItems) {
            bottomNav.getMenu().add(Menu.NONE, navItems.indexOf(item), Menu.NONE, item.label)
                    .setIcon(iconResource(item.icon));
        }
        bottomNav.setOnItemSelectedListener(item -> {
            NavItem navItem = navItems.get(item.getItemId());
            webView.loadUrl(navItem.url);
            return true;
        });
        if (!navItems.isEmpty()) {
            bottomNav.setSelectedItemId(0);
        }
    }

    private void setupTopNav() {
        topNavScroll.setVisibility(View.VISIBLE);
        topNav.removeAllViews();
        for (NavItem item : navItems) {
            Button button = new Button(this, null, 0, com.google.android.material.R.style.Widget_MaterialComponents_Button_TextButton);
            button.setText(item.label);
            button.setAllCaps(false);
            button.setTextColor(Color.parseColor("#444444"));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            params.setMargins(4, 0, 4, 0);
            button.setLayoutParams(params);
            button.setOnClickListener(v -> webView.loadUrl(item.url));
            topNav.addView(button);
        }
    }

    private void setupDrawerNav() {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        navView.getMenu().clear();
        for (NavItem item : navItems) {
            navView.getMenu().add(Menu.NONE, navItems.indexOf(item), Menu.NONE, item.label)
                    .setIcon(iconResource(item.icon));
        }
        navView.setNavigationItemSelectedListener(item -> {
            NavItem navItem = navItems.get(item.getItemId());
            webView.loadUrl(navItem.url);
            drawerLayout.closeDrawers();
            return true;
        });

        // Keep the hamburger icon
        toolbar.setNavigationIcon(R.drawable.ic_hamburger);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(Gravity.START));
    }

    private int iconResource(String icon) {
        if (icon == null) {
            return R.drawable.ic_nav_link;
        }
        switch (icon) {
            case "home":
                return R.drawable.ic_nav_home;
            case "products":
                return R.drawable.ic_nav_products;
            case "categories":
                return R.drawable.ic_nav_categories;
            case "cart":
                return R.drawable.ic_nav_cart;
            case "profile":
                return R.drawable.ic_nav_profile;
            default:
                return R.drawable.ic_nav_link;
        }
    }

    private void highlightCurrentNav(String url) {
        // Light touch: only refresh nav state without reloading the page
    }

    private void setupAdMob() {
        String bannerId = AppConfig.admobBannerId();
        String interstitialId = AppConfig.admobInterstitialId();

        if (bannerId == null || bannerId.isEmpty()) {
            adView.setVisibility(View.GONE);
        } else {
            MobileAds.initialize(this);
            adView.setVisibility(View.VISIBLE);
            adView.setAdUnitId(bannerId);
            adView.loadAd(new AdRequest.Builder().build());
        }

        if (interstitialId != null && !interstitialId.isEmpty()) {
            MobileAds.initialize(this);
            InterstitialAd.load(this, interstitialId, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
                @Override
                public void onAdLoaded(@NonNull InterstitialAd ad) {
                    interstitialAd = ad;
                }
            });
        }
    }

    private void goBack() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawers();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (AppConfig.flag("show_back_button")) {
            goBack();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (webView != null) {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
