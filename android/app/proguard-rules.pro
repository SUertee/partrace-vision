# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line numbers for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ========== kotlinx.serialization ==========
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
   static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
   static **$* *;
}
-keepclassmembers class <2>$<3> {
   kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
   public static ** INSTANCE;
}
-keepclassmembers class <1> {
   public static <1> INSTANCE;
   kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class kotlinx.serialization.json.** { *; }

# ========== Compose ==========
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ========== Firebase ==========
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ========== Ktor ==========
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ========== OkHttp ==========
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ========== Voyager Navigation ==========
-keep class cafe.adriel.voyager.** { *; }
-keep class * extends cafe.adriel.voyager.core.screen.Screen { *; }

# ========== Project model classes ==========
-keep class com.anker.esiotkit.sample.main.mega.device_settings.mode.** { *; }
-keep class com.anker.esiotkit.sample.support.model.** { *; }
-keep class com.esiotkit.security.bcdc.model.** { *; }
-keep class com.esiotkit.security.bcdc.usecase.** { *; }
-keep class com.esiotkit.security.bcdc.module.** { *; }

# ========== Koin DI ==========
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# ========== Coil ==========
-keep class coil3.** { *; }
-dontwarn coil3.**

# ========== ZXing ==========
-keep class com.google.zxing.** { *; }

# ========== Glide ==========
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }

# ========== Ktorfit ==========
-keep class de.jensklingenberg.ktorfit.** { *; }
-dontwarn de.jensklingenberg.ktorfit.**

# ========== Kotlin Coroutines ==========
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ========== PermissionX ==========
-dontwarn com.permissionx.guolindev.PermissionMediator
-dontwarn com.permissionx.guolindev.PermissionX
-dontwarn com.permissionx.guolindev.callback.ForwardToSettingsCallback
-dontwarn com.permissionx.guolindev.callback.RequestCallback
-dontwarn com.permissionx.guolindev.request.PermissionBuilder

# ========== SLF4J ==========
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder

# ========== JNI and Native Methods ==========
# Keep all classes with native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep IJKPlayer class for reflection-based log level control
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer { *; }

# Keep P2P JNI classes and methods
-keep class com.eufy.security.p2p.jni.JNIBaseMedia { *; }
-keep public class com.eufy.security.p2p.jni.** { *; }
-keep class com.eufy.security.p2p.** { *; }

# Keep P2P Session and models
-keep class com.eufy.security.p2p.Session { *; }
-keep class com.eufy.security.p2p.model.** { *; }

# Keep Mp4JNI (media codec)
-keep public class com.oceanwing.battery.cam.media.Mp4JNI { *; }

# Keep all native method parameters and return types
-keepclassmembers class * {
    native <methods>;
}

# Keep P2P client classes
-keep class com.anker.esiotkit.p2p.** { *; }
-keep class com.anker.esiotkit.security_device.p2p.** { *; }

# ========== WebRTC ==========
# Keep all WebRTC classes - needed for native library JNI registration
-keep class org.webrtc.** { *; }
-keep class com.eufy.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-keep interface com.eufy.webrtc.** { *; }

# Keep WebRTC JNI classes - CRITICAL for libmega_media_sdk.so
-keep class com.eufy.webrtc.jni.** { *; }
-keep class org.webrtc.jni.** { *; }

# Keep WebRTC native callback interfaces
-keep class com.eufy.webrtc.internal.callback.** { *; }

# Keep WebRTC enum classes
-keepclassmembers enum com.eufy.webrtc.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum org.webrtc.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
