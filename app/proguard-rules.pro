# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line numbers for readable release crash traces, and hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- App-specific keeps -------------------------------------------------------------------
# Activities/ViewModels are instantiated reflectively by the framework; keep their entry points.
-keep public class * extends android.app.Activity
-keep public class * extends androidx.lifecycle.ViewModel { <init>(...); }

# Kotlin coroutines and AndroidX/Material ship their own consumer ProGuard rules, so no manual
# keeps are needed for them. Nothing in this app uses reflection or serialization beyond the
# framework entry points above.