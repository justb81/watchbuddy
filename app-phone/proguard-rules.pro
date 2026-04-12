# ──────────────────────────────────────────────────────────────────────────────
# WatchBuddy Phone — ProGuard / R8 Rules
# ──────────────────────────────────────────────────────────────────────────────

# ── Kotlin Serialization ─────────────────────────────────────────────────────
# Keep @Serializable classes and their generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.justb81.watchbuddy.**$$serializer { *; }
-keepclassmembers class com.justb81.watchbuddy.** {
    *** Companion;
}
-keepclasseswithmembers class com.justb81.watchbuddy.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Retrofit ─────────────────────────────────────────────────────────────────
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ── OkHttp ───────────────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Hilt / Dagger ────────────────────────────────────────────────────────────
-dontwarn dagger.hilt.android.internal.**

# ── Room ─────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── Ktor (phone HTTP server) ────────────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# ── MediaPipe GenAI ──────────────────────────────────────────────────────────
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Security Crypto ──────────────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }

# ── Coroutines ───────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── General ──────────────────────────────────────────────────────────────────
-keep class * extends android.app.Application
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
