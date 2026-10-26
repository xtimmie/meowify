# meowify proguard rules

# keep chaquopy classes
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# keep our app classes
-keep class com.xtim.meowify.** { *; }

# keep javascript interface methods
-keepclassmembers class com.xtim.meowify.MainActivity$MeowifyBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# webkit
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**
