<?php
/**
 * Site2App — GitHub Actions project generator (standalone, no framework).
 *
 * Reads the build config (job.json) fetched from the Site2App platform and
 * writes a complete, compilable Android Gradle project.
 *
 * Usage:  php generate.php <job.json> <output-dir>
 *
 * Also handles the signing keystore:
 *   - when the platform sent an existing keystore (base64) it is reused;
 *   - on the FIRST build the runner generates a fresh keystore with
 *     keytool and writes it to <output-dir>/work/newkeystore/ so the
 *     workflow can upload it back to the platform.
 */

if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit('CLI only');
}

error_reporting(E_ALL);
ini_set('display_errors', '0');

$jobFile = $argv[1] ?? null;
$outDir = $argv[2] ?? sys_get_temp_dir().'/s2a-out';

if (! $jobFile || ! is_file($jobFile)) {
    fwrite(STDERR, "Usage: php generate.php <job.json> <output-dir>\n");
    exit(1);
}

$job = json_decode((string) file_get_contents($jobFile), true);
if (! is_array($job) || empty($job['package_name'])) {
    fwrite(STDERR, "Invalid job.json\n");
    exit(1);
}

require __DIR__.'/worker/lib/project_writer.php';

$work = rtrim($outDir, '/').'/work';
@mkdir($work, 0775, true);

// ---------- Assets ----------
$iconPath = null;
$splashPath = null;
if (! empty($job['icon_base64'])) {
    $iconPath = $work.'/icon.png';
    file_put_contents($iconPath, base64_decode($job['icon_base64']));
}
if (! empty($job['splash_base64'])) {
    $splashPath = $work.'/splash.png';
    file_put_contents($splashPath, base64_decode($job['splash_base64']));
}

// ---------- Signing keystore ----------
$keystorePath = null;
$keystorePassword = (string) ($job['keystore_password'] ?? '');
$keystoreAlias = (string) ($job['keystore_alias'] ?? 'site2app');
$newKeystoreDir = null;

if (! empty($job['keystore_base64'])) {
    $keystorePath = $work.'/signing.jks';
    file_put_contents($keystorePath, base64_decode($job['keystore_base64']));
    @chmod($keystorePath, 0600);
} else {
    // First build: generate a fresh keystore with keytool (Java 17 is
    // available on the GitHub runner) and expose it for upload.
    $newKeystoreDir = $work.'/newkeystore';
    @mkdir($newKeystoreDir, 0775, true);
    $keystorePath = $newKeystoreDir.'/new.jks';
    $keystorePassword = bin2hex(random_bytes(12));
    $dname = 'CN='.preg_replace('/[^a-zA-Z0-9 ._-]/', '', (string) ($job['app_name'] ?? 'Site2App')).', OU=Site2App, O=Site2App, C=BD';

    $cmd = 'keytool -genkeypair -v'
        .' -keystore '.escapeshellarg($keystorePath)
        .' -alias '.escapeshellarg($keystoreAlias)
        .' -keyalg RSA -keysize 2048 -validity 10000'
        .' -storepass '.escapeshellarg($keystorePassword)
        .' -keypass '.escapeshellarg($keystorePassword)
        .' -dname '.escapeshellarg($dname)
        .' 2>&1';

    exec($cmd, $output, $rc);
    if ($rc !== 0 || ! file_exists($keystorePath)) {
        fwrite(STDERR, "keytool failed: ".implode("\n", $output)."\n");
        exit(1);
    }
    file_put_contents($newKeystoreDir.'/password.txt', $keystorePassword);
    file_put_contents($newKeystoreDir.'/alias.txt', $keystoreAlias);
}

// ---------- Project ----------
$projectDir = rtrim($outDir, '/').'/project';

S2AProjectWriter::write([
    'package' => (string) $job['package_name'],
    'version_name' => (string) ($job['version_name'] ?? '1.0.0'),
    'version_code' => (int) ($job['version_code'] ?? 1),
    'app_name' => (string) ($job['app_name'] ?? 'Site2App'),
    'website_url' => (string) $job['website_url'],
    'home_url' => (string) ($job['home_url'] ?? $job['website_url']),
    'theme_color' => (string) ($job['theme_color'] ?? '#6C5CE7'),
    'status_bar_color' => (string) ($job['status_bar_color'] ?? '#6C5CE7'),
    'nav_bar_color' => (string) ($job['nav_bar_color'] ?? '#FFFFFF'),
    'navigation_type' => (string) ($job['navigation_type'] ?? 'bottom'),
    'nav_items' => is_array($job['nav_items'] ?? null) ? $job['nav_items'] : [],
    'permissions' => is_array($job['permissions'] ?? null) ? $job['permissions'] : [],
    'settings' => is_array($job['settings'] ?? null) ? $job['settings'] : [],
    'push_enabled' => ! empty($job['push_enabled']),
    'fcm_json' => ! empty($job['fcm_json']) ? (string) $job['fcm_json'] : null,
    'admob_enabled' => ! empty($job['admob_enabled']),
    'admob_app_id' => (string) ($job['admob_app_id'] ?? ''),
    'admob_banner_id' => (string) ($job['admob_banner_id'] ?? ''),
    'admob_interstitial_id' => (string) ($job['admob_interstitial_id'] ?? ''),
    'admob_rewarded_id' => (string) ($job['admob_rewarded_id'] ?? ''),
    'icon_path' => $iconPath,
    'splash_path' => $splashPath,
    'keystore_path' => $keystorePath,
    'keystore_password' => $keystorePassword,
    'keystore_alias' => $keystoreAlias,
    'push_register_url' => (string) ($job['push_register_url'] ?? ''),
], __DIR__.'/android', $projectDir);

// Point Gradle at the SDK installed on the GitHub runner
$sdk = getenv('ANDROID_HOME') ?: getenv('ANDROID_SDK_ROOT') ?: '/usr/local/lib/android/sdk';
file_put_contents($projectDir.'/local.properties', "sdk.dir=".$sdk."\n");

echo "Project generated: ".$projectDir."\n";
echo "Keystore: ".(file_exists($keystorePath) ? 'ready' : 'missing')."\n";
