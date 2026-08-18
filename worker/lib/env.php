<?php
/**
 * Site2App build worker — environment provisioning.
 *
 * Ensures Java, Gradle and the Android SDK are available, downloading them
 * when auto_provision is enabled. Used by worker.php and run_build.php.
 */

if (! function_exists('s2w_ensure_env')) {
    function s2w_ensure_env(bool $provision = true): array
    {
        $config = s2w_config();
        $tools = rtrim((string) $config['tools_dir'], '/');

        $result = [
            'java' => ['ok' => false, 'version' => null, 'path' => null],
            'gradle' => ['ok' => false, 'version' => null, 'path' => null],
            'android_sdk' => ['ok' => false, 'path' => null],
            'android_platform' => ['ok' => false],
            'build_tools' => ['ok' => false],
        ];

        // ----- Java -----
        $java = trim((string) shell_exec('command -v java 2>/dev/null'));
        if ($java !== '') {
            $version = shell_exec($java.' -version 2>&1');
            if (preg_match('/version "(\d+)/', (string) $version, $m)) {
                if ((int) $m[1] >= 17) {
                    $result['java'] = ['ok' => true, 'version' => trim((string) $version), 'path' => $java];
                } else {
                    $result['java']['version'] = trim((string) $version);
                }
            }
        }
        if (! $result['java']['ok'] && $provision && $config['auto_provision'] ?? false) {
            // Try common locations before giving up
            foreach (['/usr/lib/jvm/java-17-openjdk-amd64/bin/java', '/usr/lib/jvm/java-21-openjdk-amd64/bin/java'] as $candidate) {
                if (file_exists($candidate)) {
                    $result['java'] = ['ok' => true, 'version' => 'found at '.$candidate, 'path' => $candidate];
                    break;
                }
            }
        }

        // ----- Gradle -----
        $gradle = trim((string) shell_exec('command -v gradle 2>/dev/null'));
        $gradleDir = $tools.'/gradle-'.$config['gradle_version'];
        if ($gradle === '' && is_dir($gradleDir)) {
            $gradle = $gradleDir.'/bin/gradle';
        }
        if ($gradle === '' && $provision && ($config['auto_provision'] ?? false)) {
            $gradle = s2w_download_gradle($tools, (string) $config['gradle_version']);
        }
        if ($gradle !== '') {
            $result['gradle'] = ['ok' => true, 'version' => trim((string) shell_exec($gradle.' --version 2>&1 | head -3')), 'path' => $gradle];
        }

        // ----- Android SDK -----
        $sdkRoot = getenv('ANDROID_HOME') ?: getenv('ANDROID_SDK_ROOT');
        if (! $sdkRoot) {
            $candidate = $tools.'/android-sdk';
            if (is_dir($candidate)) {
                $sdkRoot = $candidate;
            } elseif ($provision && ($config['auto_provision'] ?? false)) {
                $sdkRoot = s2w_download_sdk($tools);
            }
        }
        if ($sdkRoot && is_dir($sdkRoot)) {
            $result['android_sdk'] = ['ok' => true, 'path' => $sdkRoot];
            $platform = $sdkRoot.'/platforms/android-'.$config['compile_sdk'];
            $buildTools = $sdkRoot.'/build-tools/'.$config['build_tools'];
            $result['android_platform'] = ['ok' => is_dir($platform)];
            $result['build_tools'] = ['ok' => is_dir($buildTools)];

            if ((! $result['android_platform']['ok'] || ! $result['build_tools']['ok'])
                && $provision && ($config['auto_provision'] ?? false) && $result['java']['ok']) {
                s2w_install_sdk_packages($sdkRoot, (int) $config['compile_sdk'], (string) $config['build_tools']);
                $result['android_platform'] = ['ok' => is_dir($platform)];
                $result['build_tools'] = ['ok' => is_dir($buildTools)];
            }
        }

        return $result;
    }

    function s2w_download_gradle(string $tools, string $version): string
    {
        if (! is_dir($tools)) {
            mkdir($tools, 0775, true);
        }
        $dir = $tools.'/gradle-'.$version;
        if (is_dir($dir.'/bin')) {
            return $dir.'/bin/gradle';
        }

        $url = 'https://services.gradle.org/distributions/gradle-'.$version.'-bin.zip';
        $zip = $tools.'/gradle-'.$version.'-bin.zip';

        if (! file_exists($zip)) {
            s2w_download_file($url, $zip);
        }

        $unzip = trim((string) shell_exec('command -v unzip'));
        if ($unzip === '') {
            return '';
        }
        shell_exec('cd '.escapeshellarg($tools).' && '.escapeshellarg($unzip).' -q -o '.escapeshellarg($zip).' 2>&1');
        @unlink($zip);

        return is_dir($dir.'/bin') ? $dir.'/bin/gradle' : '';
    }

    function s2w_download_sdk(string $tools): string
    {
        if (! is_dir($tools)) {
            mkdir($tools, 0775, true);
        }
        $root = $tools.'/android-sdk';
        $cmdline = $root.'/cmdline-tools/latest/bin';

        if (! is_dir($cmdline)) {
            $url = 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip';
            $zip = $tools.'/cmdline-tools.zip';
            if (! file_exists($zip)) {
                s2w_download_file($url, $zip);
            }
            $unzip = trim((string) shell_exec('command -v unzip'));
            if ($unzip === '') {
                return $root;
            }
            if (! is_dir($root)) {
                mkdir($root, 0775, true);
            }
            shell_exec('cd '.escapeshellarg($root).' && '.escapeshellarg($unzip).' -q -o '.escapeshellarg($zip).' 2>&1');
            // cmdline-tools zip extracts to cmdline-tools/
            if (is_dir($root.'/cmdline-tools') && ! is_dir($root.'/cmdline-tools/latest')) {
                rename($root.'/cmdline-tools', $root.'/cmdline-tools-latest-tmp');
                mkdir($root.'/cmdline-tools');
                rename($root.'/cmdline-tools-latest-tmp', $root.'/cmdline-tools/latest');
            }
            @unlink($zip);
        }

        return $root;
    }

    function s2w_install_sdk_packages(string $sdkRoot, int $compileSdk, string $buildTools): void
    {
        $sdkmanager = $sdkRoot.'/cmdline-tools/latest/bin/sdkmanager';
        if (! file_exists($sdkmanager)) {
            return;
        }
        putenv('ANDROID_HOME='.$sdkRoot);
        $cmd = escapeshellarg($sdkmanager)
            .' --sdk_root='.escapeshellarg($sdkRoot)
            .' "platforms;android-'.$compileSdk.'" "build-tools;'.$buildTools.'" platform-tools'
            .' < /dev/null > /dev/null 2>&1'; // accept all licenses via stdin EOF
        shell_exec('yes | '.$sdkmanager.' --sdk_root='.escapeshellarg($sdkRoot)
            .' --licenses > /dev/null 2>&1');
        shell_exec($cmd);
    }

    function s2w_download_file(string $url, string $dest): bool
    {
        $fp = @fopen($dest, 'wb');
        if (! $fp) {
            return false;
        }
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_FILE => $fp,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT => 1800,
            CURLOPT_CONNECTTIMEOUT => 30,
            CURLOPT_USERAGENT => 'Site2App-Worker/1.0',
        ]);
        $ok = curl_exec($ch) !== false;
        curl_close($ch);
        fclose($fp);
        if (! $ok || filesize($dest) < 1000) {
            @unlink($dest);
            return false;
        }
        return true;
    }
}
