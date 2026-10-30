# Page JavaScript calls the bridge methods by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
