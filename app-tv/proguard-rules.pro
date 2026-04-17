# ──────────────────────────────────────────────────────────────────────────────
# WatchBuddy TV — ProGuard / R8 Rules
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

# ── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Security Crypto / Tink ───────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.errorprone.annotations.**

# ── Room ────────────────────────────────────────────────────────────────────
# Room 2.7+ reflectively invokes a no-arg constructor on generated _Impl
# classes (see androidx.room.Room.getGeneratedImplementation).  R8 full mode
# in AGP 9 strips constructors that have no static callers even under
# `-keep class X { *; }` — issues #232 and the 0.12.0 trace attached to #244
# both trace to this pathway.  Kept the constructor explicitly.
-keep,includedescriptorclasses class * extends androidx.room.RoomDatabase {
    <init>();
    *;
}
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# ── WorkManager (safety net) ────────────────────────────────────────────────
# androidx.work is excluded from this module's classpath (see
# app-tv/build.gradle.kts `configurations.all`), so these rules are a safety
# net in case a future transitive dep re-introduces work-runtime.  `-dontwarn`
# keeps R8 from failing the build while the classes are absent.
-keep class androidx.work.impl.WorkDatabase_Impl {
    <init>();
    *;
}
-dontwarn androidx.work.**

# ── Coroutines ───────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Diagnostic Logging ───────────────────────────────────────────────────────
# Keep class and method names so the breadcrumb tags and crash-report stack
# frames in shared reports remain readable in release builds.  The entire
# purpose of this module is to make crashes investigable after the fact.
-keep class com.justb81.watchbuddy.core.logging.** { *; }
-keepnames class com.justb81.watchbuddy.core.logging.**

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
