<?php

/**
 * Site2App — Android project writer (plain PHP, no framework dependencies).
 *
 * This file is shared between the Laravel application (local build engine)
 * and the standalone build worker (/worker). It turns a Site2App project
 * configuration into a complete, compilable Gradle Android project:
 *
 *   - copies the Android template
 *   - applies app name / package / version
 *   - generates every launcher icon size from the uploaded icon (GD)
 *   - prepares the splash screen
 *   - writes theme colors, navigation config and feature flags
 *   - applies Android permissions and Firebase / AdMob configuration
 *   - writes the release signing configuration (keystore path/alias/password)
 *
 * No Laravel classes are referenced here so the file can run anywhere.
 */

if (! function_exists('s2a_recursive_copy')) {
    function s2a_recursive_copy(string $src, string $dst): void
    {
        if (is_dir($src)) {
            if (! is_dir($dst)) {
                mkdir($dst, 0775, true);
            }
            foreach (scandir($src) ?: [] as $item) {
                if ($item === '.' || $item === '..') {
                    continue;
                }
                s2a_recursive_copy($src.'/'.$item, $dst.'/'.$item);
            }
        } else {
            $dir = dirname($dst);
            if (! is_dir($dir)) {
                mkdir($dir, 0775, true);
            }
            copy($src, $dst);
        }
    }
}

if (! function_exists('s2a_hex_to_rgb')) {
    function s2a_hex_to_rgb(string $hex): array
    {
        $hex = ltrim($hex, '#');
        if (strlen($hex) === 3) {
            $hex = $hex[0].$hex[0].$hex[1].$hex[1].$hex[2].$hex[2];
        }
        return [hexdec(substr($hex, 0, 2)), hexdec(substr($hex, 2, 2)), hexdec(substr($hex, 4, 2))];
    }
}

class S2AIconGenerator
{
    /**
     * Generate mipmap / adaptive icons from an uploaded icon (GD only).
     * Returns array of created file names.
     */
    public static function generate(string $iconPath, string $resDir, string $themeColor = '#6C5CE7'): array
    {
        if (! file_exists($iconPath)) {
            throw new RuntimeException('Icon file not found: '.$iconPath);
        }

        $info = getimagesize($iconPath);
        if (! $info) {
            throw new RuntimeException('Icon is not a valid image: '.$iconPath);
        }

        $source = self::loadImage($iconPath);
        $made = [];

        $sizes = ['mipmap-mdpi' => 48, 'mipmap-hdpi' => 72, 'mipmap-xhdpi' => 96, 'mipmap-xxhdpi' => 144, 'mipmap-xxxhdpi' => 192];

        foreach ($sizes as $folder => $size) {
            $dir = $resDir.'/'.$folder;
            if (! is_dir($dir)) {
                mkdir($dir, 0775, true);
            }
            self::saveResized($source, $dir.'/ic_launcher.png', $size, $size);
            if ($folder === 'mipmap-xxxhdpi') {
                self::saveResized($source, $dir.'/ic_launcher_round.png', $size, $size);
                $made[] = $folder.'/ic_launcher_round.png';
            }
            $made[] = $folder.'/ic_launcher.png';
        }

        $drawable = $resDir.'/drawable';
        if (! is_dir($drawable)) {
            mkdir($drawable, 0775, true);
        }

        // Notification icon (small, centered)
        self::saveResized($source, $drawable.'/ic_stat_s2a.png', 96, 96);
        $made[] = 'drawable/ic_stat_s2a.png';

        // Adaptive foreground: icon centered with safe-zone padding
        $fg = self::createCanvas(432, 432);
        $glyph = self::resize($source, 240, 240);
        imagecopy($fg, $glyph, 96, 96, 0, 0, 240, 240);
        imagepng($fg, $drawable.'/ic_launcher_foreground.png', 9);
        imagedestroy($fg);
        imagedestroy($glyph);
        $made[] = 'drawable/ic_launcher_foreground.png';

        [$r, $g, $b] = s2a_hex_to_rgb($themeColor);
        $bg = self::createCanvas(432, 432, $r, $g, $b);
        imagepng($bg, $drawable.'/ic_launcher_background.png', 9);
        imagedestroy($bg);
        $made[] = 'drawable/ic_launcher_background.png';

        $anyDpi = $resDir.'/mipmap-anydpi-v26';
        if (! is_dir($anyDpi)) {
            mkdir($anyDpi, 0775, true);
        }
        $xml = '<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>';
        file_put_contents($anyDpi.'/ic_launcher.xml', $xml);
        file_put_contents($anyDpi.'/ic_launcher_round.xml', $xml);
        $made[] = 'mipmap-anydpi-v26/ic_launcher.xml';
        $made[] = 'mipmap-anydpi-v26/ic_launcher_round.xml';

        imagedestroy($source);

        return $made;
    }

    /** Prepare splash screen (returns relative drawable path). */
    public static function prepareSplash(string $splashPath, string $resDir): string
    {
        $dir = $resDir.'/drawable-nodpi';
        if (! is_dir($dir)) {
            mkdir($dir, 0775, true);
        }
        $dest = $dir.'/splash.png';

        if (! file_exists($splashPath)) {
            throw new RuntimeException('Splash image not found: '.$splashPath);
        }

        $src = self::loadImage($splashPath);
        $w = imagesx($src);
        $h = imagesy($src);

        $maxW = 1080;
        $maxH = 1920;
        if ($w > $maxW || $h > $maxH) {
            $scale = min($maxW / $w, $maxH / $h);
            $src = self::resize($src, (int) ($w * $scale), (int) ($h * $scale));
        }

        imagepng($src, $dest, 9);
        imagedestroy($src);

        return 'drawable-nodpi/splash.png';
    }

    protected static function loadImage(string $path)
    {
        $info = getimagesize($path);
        $img = match ($info[2]) {
            IMAGETYPE_JPEG => imagecreatefromjpeg($path),
            IMAGETYPE_PNG => imagecreatefrompng($path),
            IMAGETYPE_WEBP => imagecreatefromwebp($path),
            default => null,
        };
        if (! $img) {
            throw new RuntimeException('Unsupported image type for '.$path);
        }
        if ($info[2] === IMAGETYPE_PNG) {
            imagealphablending($img, true);
            imagesavealpha($img, true);
        }
        return $img;
    }

    protected static function createCanvas(int $w, int $h, int $r = 0, int $g = 0, int $b = 0)
    {
        $img = imagecreatetruecolor($w, $h);
        imagealphablending($img, false);
        imagesavealpha($img, true);
        $transparent = imagecolorallocatealpha($img, $r, $g, $b, ($r === 0 && $g === 0 && $b === 0) ? 127 : 0);
        imagefilledrectangle($img, 0, 0, $w, $h, $transparent);
        return $img;
    }

    protected static function resize($source, int $w, int $h)
    {
        $dest = imagecreatetruecolor($w, $h);
        imagealphablending($dest, false);
        imagesavealpha($dest, true);
        imagecopyresampled($dest, $source, 0, 0, 0, 0, $w, $h, imagesx($source), imagesy($source));
        return $dest;
    }

    protected static function saveResized($source, string $destPath, int $w, int $h): void
    {
        $resized = self::resize($source, $w, $h);
        imagepng($resized, $destPath, 9);
        imagedestroy($resized);
    }
}

class S2AProjectWriter
{
    /**
     * Write a complete Android project.
     *
     * $config keys:
     *   package, version_name, version_code, app_name, website_url, home_url,
     *   theme_color, status_bar_color, nav_bar_color, navigation_type (bottom|top|hamburger),
     *   nav_items (array: [ {id,label,url,icon} ... ]),
     *   permissions (array of feature keys),
     *   settings (array: open_links_external, external_links_allowed, show_back_button, show_home_button...),
     *   push_enabled (bool), fcm_json (raw google-services.json or null),
     *   admob_enabled (bool), admob_app_id, admob_banner_id, admob_interstitial_id, admob_rewarded_id,
     *   icon_path (abs path), splash_path (abs path),
     *   keystore_path, keystore_password, keystore_alias
     */
    public static function write(array $c, string $templateDir, string $outDir): string
    {
        if (! is_dir($templateDir)) {
            throw new RuntimeException('Android template directory not found: '.$templateDir);
        }
        if (is_dir($outDir)) {
            self::deleteDir($outDir);
        }
        mkdir($outDir, 0775, true);

        s2a_recursive_copy($templateDir, $outDir);

        // ===== Stale-file guard =====
        // Older templates shipped ic_notification.xml / ic_stat_s2a.xml,
        // which collide with the generated notification PNG and aborted
        // every build with "Resource and asset merger: Duplicate resources".
        // Delete them from EVERY generated project so this error can never
        // come back, no matter what the template folder still contains.
        foreach ([
            $outDir.'/app/src/main/res/drawable/ic_notification.xml',
            $outDir.'/app/src/main/res/drawable/ic_notification.png',
            $outDir.'/app/src/main/res/drawable/ic_stat_s2a.xml',
        ] as $stale) {
            if (file_exists($stale)) {
                @unlink($stale);
            }
        }

        // Java-level stale fixes (same problems the template endpoint
        // sanitizes — kept here as defense-in-depth for every path).
        $pushSvc = $outDir.'/app/src/main/java/com/site2app/app/S2APushService.java';
        if (file_exists($pushSvc)) {
            $svc = (string) file_get_contents($pushSvc);
            if (str_contains($svc, 'bigLargeIcon')) {
                $svc = preg_replace('/[\t ]*\.bigLargeIcon\([^)]*\)\s*/', '', $svc);
                file_put_contents($pushSvc, $svc);
            }
        }
        $webIface = $outDir.'/app/src/main/java/com/site2app/app/WebAppInterface.java';
        if (file_exists($webIface)) {
            $wif = (string) file_get_contents($webIface);
            if (str_contains($wif, 'ic_stat_s2a') || str_contains($wif, 'ic_notification')) {
                $wif = str_replace(['R.drawable.ic_stat_s2a', 'R.drawable.ic_notification'], 'R.drawable.ic_notif_fallback', $wif);
                file_put_contents($webIface, $wif);
            }
        }
        $bellPath = $outDir.'/app/src/main/res/drawable/ic_notif_fallback.xml';
        if (! file_exists($bellPath)) {
            @mkdir(dirname($bellPath), 0775, true);
            file_put_contents($bellPath, '<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFF"
        android:pathData="M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.63,-5.64 -4.5,-6.32L13.5,4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.64,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z" />
</vector>
');
        }

        $resDir = $outDir.'/app/src/main/res';

        // ---------- 1. Icons & splash ----------
        if (! empty($c['icon_path']) && file_exists($c['icon_path'])) {
            S2AIconGenerator::generate($c['icon_path'], $resDir, $c['theme_color'] ?? '#6C5CE7');
        }
        if (! empty($c['splash_path']) && file_exists($c['splash_path'])) {
            S2AIconGenerator::prepareSplash($c['splash_path'], $resDir);
        } elseif (! empty($c['icon_path']) && file_exists($c['icon_path'])) {
            // No splash uploaded: derive one from the icon so the resource always exists
            S2AIconGenerator::prepareSplash($c['icon_path'], $resDir);
        }

        // ---------- 1a. Push provider ----------
        // 'firebase' (default), 'wevlo' (Wevlo push server over FCM),
        // 'onesignal' (no Firebase), or 'site' (self-hosted polling).
        $pushProvider = (string) ($c['settings']['push_provider'] ?? 'firebase');
        if (! in_array($pushProvider, ['firebase', 'wevlo', 'onesignal', 'site'], true)) {
            $pushProvider = 'firebase';
        }
        if (! in_array($pushProvider, ['firebase', 'wevlo'], true)) {
            // Only firebase/wevlo use google-services.json (FCM delivery
            // channel); onesignal/site must never include the FCM service
            // or its Gradle plugin.
            $c['fcm_json'] = null;
        }

        // ---------- 1b. Firebase config tolerance ----------
        // A malformed google-services.json (truncated paste, missing
        // api_key, or wrong package name) used to fail the whole Gradle
        // build at processReleaseGoogleServices — and the workflow then
        // blamed the upload step for it. Never let one broken file kill
        // the build: fall back to a push-disabled app and record a clear
        // warning for the runner log instead.
        $pushWarning = null;
        if (! empty($c['push_enabled']) && ! empty($c['fcm_json']) && is_string($c['fcm_json'])) {
            $fcmCheck = json_decode($c['fcm_json'], true);
            $fcmPkg = $fcmCheck['client'][0]['client_info']['android_client_info']['package_name'] ?? null;
            $fcmHasKey = false;
            foreach (($fcmCheck['client'] ?? []) as $fcmCli) {
                foreach (($fcmCli['api_key'] ?? []) as $fcmK) {
                    if (! empty($fcmK['current_key'])) {
                        $fcmHasKey = true;
                    }
                }
            }
            $fcmBad = null;
            if (! is_array($fcmCheck)) {
                $fcmBad = 'not valid JSON';
            } elseif (! $fcmHasKey) {
                $fcmBad = 'missing api_key/current_key';
            } elseif ($fcmPkg && $fcmPkg !== ($c['package'] ?? '')) {
                $fcmBad = 'package mismatch (file has '.$fcmPkg.', app is '.($c['package'] ?? '?').')';
            }
            if ($fcmBad !== null) {
                $c['push_enabled'] = false;
                $pushWarning = 'WARNING: Firebase config invalid ('.$fcmBad.') — push notifications were disabled for this build so it can still complete. Upload a correct google-services.json (Firebase Console) to re-enable push.';
            }
        }
        if ($pushWarning !== null) {
            $warningDir = dirname($outDir);
            @mkdir($warningDir, 0775, true);
            file_put_contents($warningDir.'/push-warning.txt', $pushWarning."
");
        }

        // ---------- 2. Gradle files ----------
        self::replaceTokens($outDir.'/app/build.gradle', [
            '__PACKAGE__' => $c['package'],
            '__VERSION_NAME__' => $c['version_name'] ?? '1.0.0',
            '__VERSION_CODE__' => (string) ($c['version_code'] ?? 1),
            '__GOOGLE_SERVICES__' => (in_array($pushProvider, ['firebase', 'wevlo'], true) && ! empty($c['push_enabled']) && ! empty($c['fcm_json']))
                ? "id 'com.google.gms.google-services'"
                : "// Google services plugin not applied (push provider: {$pushProvider})",
            '__FIREBASE_DEPS__' => (in_array($pushProvider, ['firebase', 'wevlo'], true) && ! empty($c['push_enabled']) && ! empty($c['fcm_json']))
                ? "    implementation platform('com.google.firebase:firebase-bom:33.1.2')\n    implementation 'com.google.firebase:firebase-messaging'"
                : "// Firebase dependencies not applied (push provider: {$pushProvider})",
            '__WEVLO_DEPS__' => ($pushProvider === 'wevlo' && ! empty($c['push_enabled']) && ! empty($c['fcm_json']))
                ? "    implementation 'com.squareup.okhttp3:okhttp:4.12.0'"
                : "// OkHttp not included (Wevlo provider off: {$pushProvider})",
        ]);

        $gradleProps = [
            'org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8',
            'org.gradle.parallel=true',
            'org.gradle.caching=true',
            'android.useAndroidX=true',
            'android.nonTransitiveRClass=true',
            'android.enableJetifier=true',
            'RELEASE_STORE_FILE='.($c['keystore_path'] ?? ''),
            'RELEASE_STORE_PASSWORD='.($c['keystore_password'] ?? ''),
            'RELEASE_KEY_ALIAS='.($c['keystore_alias'] ?? 'site2app'),
            'RELEASE_KEY_PASSWORD='.($c['keystore_password'] ?? ''),
            'S2A_ADMOB_APP_ID='.($c['admob_app_id'] ?? ''),
        ];
        file_put_contents($outDir.'/gradle.properties', implode("\n", $gradleProps)."\n");

        // ---------- 2b. AdMob dependency (only when ads are configured) ----
        // The Google Mobile Ads SDK initializes itself in a ContentProvider
        // that runs BEFORE the app code on every launch — on several devices
        // that provider is what crashes the app at startup. Apps without
        // AdMob IDs therefore get a smaller, safer APK without the SDK at
        // all (the app code talks to ads through reflection only).
        $adsOn = ! empty($c['admob_banner_id']) || ! empty($c['admob_interstitial_id']);
        self::replaceTokens($outDir.'/app/build.gradle', [
            '__ADS_DEPS__' => $adsOn
                ? "    implementation 'com.google.android.gms:play-services-ads:23.2.0'"
                : '// Google Mobile Ads SDK not included (no AdMob IDs) — safer startup',
            '__ONESIGNAL_DEPS__' => $pushProvider === 'onesignal'
                ? "    implementation 'com.onesignal:OneSignal:5.1.29'"
                : "// OneSignal SDK not included (push provider: {$pushProvider})",
        ]);
        // The template ALWAYS contains a FrameLayout placeholder with the
        // id ad_view (so the R class and MainActivity compile on every
        // variant). Only when AdMob IDs are configured do we swap the
        // placeholder for the real AdView — the id stays the same.
        if ($adsOn) {
            $layoutPath = $outDir.'/app/src/main/res/layout/activity_main.xml';
            $layout = file_get_contents($layoutPath);
            $adXml = '            <com.google.android.gms.ads.AdView
                android:id="@+id/ad_view"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_gravity="bottom"
                android:visibility="gone"
                app:adSize="BANNER"
                app:adUnitId="@string/s2a_admob_banner_id" />';
            $layout = preg_replace(
                '#<!-- ADS_PLACEHOLDER_BEGIN:.*?<!-- ADS_PLACEHOLDER_END -->#s',
                $adXml,
                $layout,
                1
            );
            file_put_contents($layoutPath, $layout);
        }

        // ---------- 3. AndroidManifest ----------
        $permissions = self::buildPermissions($c['permissions'] ?? []);
        $manifest = file_get_contents($outDir.'/app/src/main/AndroidManifest.xml');

        $pushService = '';
        if (in_array($pushProvider, ['firebase', 'wevlo'], true) && ! empty($c['push_enabled']) && ! empty($c['fcm_json'])) {
            $svcClass = $pushProvider === 'wevlo' ? 'WevloFcmService' : 'S2APushService';
            $pushService = '
        <service
            android:name=".'.$svcClass.'"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>';
        }

        $manifest = str_replace('__PERMISSIONS__', $permissions, $manifest);
        $manifest = str_replace('__PUSH_SERVICE__', $pushService, $manifest);
        file_put_contents($outDir.'/app/src/main/AndroidManifest.xml', $manifest);

        // ---------- 4. Firebase / Wevlo files ----------
        $wevloOn = ($pushProvider === 'wevlo' && ! empty($c['push_enabled']) && ! empty($c['fcm_json']));
        if (in_array($pushProvider, ['firebase', 'wevlo'], true) && ! empty($c['push_enabled']) && ! empty($c['fcm_json'])) {
            file_put_contents($outDir.'/app/google-services.json', $c['fcm_json']);
        } else {
            // Keep FCM classes out of the compilation when push is disabled
            // OR when the provider is onesignal/site.
            @unlink($outDir.'/app/src/main/java/com/site2app/app/S2APushService.java');
        }

        // S2APushService is the FIREBASE-provider service; Wevlo apps use
        // WevloFcmService instead.
        if ($pushProvider !== 'firebase') {
            @unlink($outDir.'/app/src/main/java/com/site2app/app/S2APushService.java');
        }

        // The four Wevlo files ship ONLY in Wevlo builds.
        if ($wevloOn) {
            $wevloUrl = (string) ($c['wevlo_server_url'] ?? 'https://pushserver-37wj.onrender.com');
            $wevloUrl = rtrim($wevloUrl, '/');
            $pushCfgPath = $outDir.'/app/src/main/java/com/site2app/app/PushConfig.java';
            $pushCfg = (string) file_get_contents($pushCfgPath);
            $pushCfg = str_replace('__WEVLO_SERVER_URL__', $wevloUrl, $pushCfg);
            $pushCfg = str_replace('__WEVLO_APP_ID__', (string) ($c['package'] ?? ''), $pushCfg);
            file_put_contents($pushCfgPath, $pushCfg);
        } else {
            foreach (['PushConfig.java', 'WevloFcmService.java', 'TokenRegistrar.java', 'NotificationHelper.java'] as $wevloFile) {
                @unlink($outDir.'/app/src/main/java/com/site2app/app/'.$wevloFile);
            }
        }

        // ---------- 5. App configuration resource ----------
        self::writeConfigXml($c, $resDir);

        // ---------- 6. Theme colors ----------
        [$r, $g, $b] = s2a_hex_to_rgb($c['theme_color'] ?? '#6C5CE7');
        $theme = sprintf('#%02X%02X%02X', $r, $g, $b);
        $statusBar = strtoupper($c['status_bar_color'] ?? $theme);
        $navBar = strtoupper($c['nav_bar_color'] ?? '#FFFFFF');

        $colors = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
            ."    <color name=\"s2a_theme\">{$theme}</color>\n"
            ."    <color name=\"s2a_status_bar\">{$statusBar}</color>\n"
            ."    <color name=\"s2a_nav_bar\">{$navBar}</color>\n"
            ."    <color name=\"white\">#FFFFFF</color>\n"
            ."    <color name=\"s2a_splash_bg\">{$theme}</color>\n"
            ."</resources>\n";
        file_put_contents($resDir.'/values/colors.xml', $colors);

        // ---------- 7. App name ----------
        $strings = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
            .'    <string name="app_name">'.htmlspecialchars($c['app_name'] ?? 'Site2App', ENT_XML1)."</string>\n"
            .'    <string name="s2a_admob_banner_id">'.htmlspecialchars($c['admob_banner_id'] ?? '', ENT_XML1)."</string>\n"
            .'    <string name="s2a_admob_interstitial_id">'.htmlspecialchars($c['admob_interstitial_id'] ?? '', ENT_XML1)."</string>\n"
            .'    <string name="s2a_admob_rewarded_id">'.htmlspecialchars($c['admob_rewarded_id'] ?? '', ENT_XML1)."</string>\n"
            ."</resources>\n";
        file_put_contents($resDir.'/values/strings.xml', $strings);

        return $outDir;
    }

    protected static function buildPermissions(array $features): string
    {
        $perms = ['android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE'];
        $featureMap = [
            'camera' => ['android.permission.CAMERA'],
            'location' => ['android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION'],
            'microphone' => ['android.permission.RECORD_AUDIO', 'android.permission.MODIFY_AUDIO_SETTINGS'],
            'downloads' => ['android.permission.WRITE_EXTERNAL_STORAGE'],
            'file_upload' => ['android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_EXTERNAL_STORAGE'],
        ];
        foreach ($features as $feature) {
            foreach ($featureMap[$feature] ?? [] as $perm) {
                if (! in_array($perm, $perms, true)) {
                    $perms[] = $perm;
                }
            }
        }
        $perms[] = 'android.permission.POST_NOTIFICATIONS';

        $lines = '';
        foreach ($perms as $perm) {
            $lines .= "    <uses-permission android:name=\"{$perm}\" />\n";
        }
        return $lines;
    }

    protected static function writeConfigXml(array $c, string $resDir): void
    {
        $navItems = $c['nav_items'] ?? [];
        if (! is_array($navItems)) {
            $navItems = [];
        }
        // No default menu: if the user removed all pages, the app ships
        // with NO navigation at all (clean full-screen webview).

        $settings = $c['settings'] ?? [];
        $permissions = $c['permissions'] ?? [];

        $flags = [
            'pull_to_refresh' => in_array('pull_to_refresh', $permissions, true) ? 'true' : 'false',
            'zoom_enabled' => in_array('zoom', $permissions, true) ? 'true' : 'false',
            'file_upload_enabled' => in_array('file_upload', $permissions, true) ? 'true' : 'false',
            'camera_enabled' => in_array('camera', $permissions, true) ? 'true' : 'false',
            'location_enabled' => in_array('location', $permissions, true) ? 'true' : 'false',
            'microphone_enabled' => in_array('microphone', $permissions, true) ? 'true' : 'false',
            'downloads_enabled' => in_array('downloads', $permissions, true) ? 'true' : 'false',
            'external_links_allowed' => in_array('external_links', $permissions, true) ? 'true' : 'false',
            'phone_links_enabled' => in_array('phone_calls', $permissions, true) ? 'true' : 'false',
            'whatsapp_links_enabled' => in_array('whatsapp', $permissions, true) ? 'true' : 'false',
            'email_links_enabled' => in_array('email', $permissions, true) ? 'true' : 'false',
            'share_enabled' => in_array('share', $permissions, true) ? 'true' : 'false',
            'open_links_external' => ! empty($settings['open_links_external']) ? 'true' : 'false',
            'show_back_button' => empty($settings['show_back_button']) ? 'false' : 'true',
            'show_home_button' => empty($settings['show_home_button']) ? 'false' : 'true',
            'show_toolbar' => array_key_exists('show_toolbar', $settings) && empty($settings['show_toolbar']) ? 'false' : 'true',
            'navigation_type' => $c['navigation_type'] ?? 'bottom',
            'home_url' => $c['home_url'] ?? $c['website_url'],
            'website_url' => $c['website_url'],
            'push_register_url' => $c['push_register_url'] ?? '',
        ];

        // Provider-driven runtime flags (push provider lives in settings).
        $provider = (string) ($settings['push_provider'] ?? 'firebase');
        if (! in_array($provider, ['firebase', 'wevlo', 'onesignal', 'site'], true)) {
            $provider = 'firebase';
        }
        $flags['push_site_enabled'] = ($provider === 'site' && ! empty($c['push_enabled'])) ? 'true' : 'false';
        $flags['push_onesignal_enabled'] = ($provider === 'onesignal' && ! empty($c['push_enabled'])) ? 'true' : 'false';
        $flags['push_wevlo_enabled'] = ($provider === 'wevlo' && ! empty($c['push_enabled'])) ? 'true' : 'false';
        $flags['platform_url'] = (string) ($c['platform_url'] ?? '');
        $flags['version_code'] = (string) ($c['version_code'] ?? '1');

        $xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n";
        // app id (for the self-hosted polling endpoint)
        if (! empty($c['app_id'])) {
            $xml .= '    <integer name="s2a_app_id" translatable="false">'.(int) $c['app_id'].'</integer>\n';
        }
        // OneSignal app id
        if (! empty($settings['onesignal_app_id'])) {
            $xml .= '    <string name="s2a_onesignal_app_id" translatable="false">'.htmlspecialchars((string) $settings['onesignal_app_id'], ENT_XML1).'</string>\n';
        }
        foreach ($flags as $key => $value) {
            $safeKey = str_replace('_', '_', $key);
            $xml .= '    <string name="s2a_'.$safeKey.'" translatable="false">'.htmlspecialchars((string) $value, ENT_XML1)."</string>\n";
        }

        // Nav items
        $keep = [];
        foreach ($navItems as $i => $item) {
            $id = preg_replace('/[^a-z0-9_]/i', '', (string) ($item['id'] ?? 'item'.$i));
            $label = $item['label'] ?? 'Page';
            $url = $item['url'] ?? $c['website_url'];
            $icon = $item['icon'] ?? 'home';
            $xml .= '    <string name="s2a_nav_'.$i.'_id" translatable="false">'.$id."</string>\n";
            $xml .= '    <string name="s2a_nav_'.$i.'_label">'.htmlspecialchars((string) $label, ENT_XML1)."</string>\n";
            $xml .= '    <string name="s2a_nav_'.$i.'_url" translatable="false">'.htmlspecialchars((string) $url, ENT_XML1)."</string>\n";
            $xml .= '    <string name="s2a_nav_'.$i.'_icon" translatable="false">'.htmlspecialchars((string) $icon, ENT_XML1)."</string>\n";
            $keep[] = '@string/s2a_nav_'.$i.'_id';
            $keep[] = '@string/s2a_nav_'.$i.'_label';
            $keep[] = '@string/s2a_nav_'.$i.'_url';
            $keep[] = '@string/s2a_nav_'.$i.'_icon';
        }
        $xml .= '    <integer name="s2a_nav_count" translatable="false">'.count($navItems)."</integer>\n";
        $xml .= "</resources>\n";

        file_put_contents($resDir.'/values/config.xml', $xml);

        // Keep every config resource alive even when R8 resource shrinking is
        // enabled. These values are read dynamically via
        // Resources.getIdentifier(), which the shrinker cannot see — without
        // this list the released app can silently lose its URL/navigation.
        $keep = array_merge($keep, [
            '@integer/s2a_nav_count',
            '@string/s2a_home_url',
            '@string/s2a_website_url',
            '@string/s2a_navigation_type',
            '@string/s2a_pull_to_refresh',
            '@string/s2a_zoom_enabled',
            '@string/s2a_file_upload_enabled',
            '@string/s2a_camera_enabled',
            '@string/s2a_location_enabled',
            '@string/s2a_microphone_enabled',
            '@string/s2a_downloads_enabled',
            '@string/s2a_external_links_allowed',
            '@string/s2a_phone_links_enabled',
            '@string/s2a_whatsapp_links_enabled',
            '@string/s2a_email_links_enabled',
            '@string/s2a_share_enabled',
            '@string/s2a_open_links_external',
            '@string/s2a_show_back_button',
            '@string/s2a_show_home_button',
            '@string/s2a_show_toolbar',
            '@string/s2a_push_register_url',
            '@string/s2a_push_site_enabled',
            '@string/s2a_push_onesignal_enabled',
            '@string/s2a_push_wevlo_enabled',
            '@string/s2a_platform_url',
            '@string/s2a_version_code',
            '@integer/s2a_app_id',
            '@string/s2a_onesignal_app_id',
            '@string/app_name',
            '@string/s2a_admob_banner_id',
            '@string/s2a_admob_interstitial_id',
            '@string/s2a_admob_rewarded_id',
            '@drawable/splash',
            '@drawable/bg_splash',
        ]);
        $rawDir = $resDir.'/raw';
        if (! is_dir($rawDir)) {
            mkdir($rawDir, 0775, true);
        }
        $keepXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            ."<resources xmlns:tools=\"http://schemas.android.com/tools\"\n"
            ."    tools:keep=\"".implode(',', array_unique($keep))."\" />\n";
        file_put_contents($rawDir.'/keep.xml', $keepXml);
    }

    protected static function replaceTokens(string $file, array $tokens): void
    {
        $content = file_get_contents($file);
        foreach ($tokens as $token => $value) {
            $content = str_replace($token, $value, $content);
        }
        file_put_contents($file, $content);
    }

    public static function deleteDir(string $dir): void
    {
        if (! is_dir($dir)) {
            return;
        }
        $items = scandir($dir) ?: [];
        foreach ($items as $item) {
            if ($item === '.' || $item === '..') {
                continue;
            }
            $path = $dir.'/'.$item;
            is_dir($path) ? self::deleteDir($path) : unlink($path);
        }
        rmdir($dir);
    }
}
