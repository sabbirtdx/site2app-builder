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
        self::saveResized($source, $drawable.'/ic_notification.png', 96, 96);
        $made[] = 'drawable/ic_notification.png';

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

        // ---------- 2. Gradle files ----------
        self::replaceTokens($outDir.'/app/build.gradle', [
            '__PACKAGE__' => $c['package'],
            '__VERSION_NAME__' => $c['version_name'] ?? '1.0.0',
            '__VERSION_CODE__' => (string) ($c['version_code'] ?? 1),
            '__GOOGLE_SERVICES__' => (! empty($c['push_enabled']) && ! empty($c['fcm_json']))
                ? "id 'com.google.gms.google-services'"
                : "// Google services plugin not applied (push notifications disabled)",
            '__FIREBASE_DEPS__' => (! empty($c['push_enabled']) && ! empty($c['fcm_json']))
                ? "    implementation platform('com.google.firebase:firebase-bom:33.1.2')\n    implementation 'com.google.firebase:firebase-messaging'"
                : "// Firebase dependencies not applied (push notifications disabled)",
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
        if (! empty($c['push_enabled']) && ! empty($c['fcm_json'])) {
            $pushService = '
        <service
            android:name=".S2APushService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>';
        }

        $manifest = str_replace('__PERMISSIONS__', $permissions, $manifest);
        $manifest = str_replace('__PUSH_SERVICE__', $pushService, $manifest);
        file_put_contents($outDir.'/app/src/main/AndroidManifest.xml', $manifest);

        // ---------- 4. Firebase / AdMob ----------
        if (! empty($c['push_enabled']) && ! empty($c['fcm_json'])) {
            file_put_contents($outDir.'/app/google-services.json', $c['fcm_json']);
        } else {
            // Keep FCM classes out of the compilation when push is disabled
            @unlink($outDir.'/app/src/main/java/com/site2app/app/S2APushService.java');
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
            'navigation_type' => $c['navigation_type'] ?? 'bottom',
            'home_url' => $c['home_url'] ?? $c['website_url'],
            'website_url' => $c['website_url'],
            'push_register_url' => $c['push_register_url'] ?? '',
        ];

        $xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n";
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
            '@string/s2a_push_register_url',
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
