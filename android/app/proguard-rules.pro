# Site2App generated app - ProGuard rules

# Keep the JavaScript bridge
-keepclassmembers class com.site2app.app.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}
-keep public class com.site2app.app.AppConfig { *; }

# Google Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# WebView
-keep class android.webkit.** { *; }
-dontwarn android.webkit.**

# JSON
-dontwarn org.json.**
-keep class org.json.** { *; }
