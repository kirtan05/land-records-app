# Keep default optimizations; app is currently distributed as an unminified debug APK.
# WebView JS interface (added later for the AnyRoR fetch bridge):
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
