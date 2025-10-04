# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep ChildWatch specific classes and methods
-keep class ru.example.childwatch.** { *; }
-keep class ru.example.childwatch.MainActivity { *; }
-keep class ru.example.childwatch.ConsentActivity { *; }
-keep class ru.example.childwatch.SettingsActivity { *; }
-keep class ru.example.childwatch.AboutActivity { *; }
-keep class ru.example.childwatch.service.MonitorService { *; }
-keep class ru.example.childwatch.network.** { *; }
-keep class ru.example.childwatch.location.** { *; }
-keep class ru.example.childwatch.audio.** { *; }
-keep class ru.example.childwatch.utils.** { *; }

# Keep Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep location and audio related classes
-keep class com.google.android.gms.location.** { *; }
-keep class android.media.MediaRecorder { *; }
-keep class android.location.** { *; }

# OkHttp and Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep class retrofit2.** { *; }
-keep class com.squareup.okhttp3.** { *; }
-keep class com.squareup.retrofit2.** { *; }

# Keep JSON serialization
-keep class com.google.gson.** { *; }
-keep class org.json.** { *; }

# Keep coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep WorkManager
-keep class androidx.work.** { *; }

# Keep Material Design
-keep class com.google.android.material.** { *; }

# Keep AndroidX
-keep class androidx.** { *; }

# Remove debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Obfuscate package names (except for main package)
-repackageclasses 'obfuscated'
-keep class ru.example.childwatch.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
