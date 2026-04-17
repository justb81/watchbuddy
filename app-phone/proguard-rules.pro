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

# ── Ktor (phone HTTP server) ────────────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# ── LiteRT-LM ────────────────────────────────────────────────────────────────
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# ── AICore (Gemini Nano) ─────────────────────────────────────────────────────
-keep class com.google.ai.edge.aicore.** { *; }
-dontwarn com.google.ai.edge.aicore.**

# ── Reactor / BlockHound (transitive via Ktor Netty) ─────────────────────────
# Ktor's Netty engine bundles a META-INF/services descriptor that references
# reactor.blockhound.integration.BlockHoundIntegration, but BlockHound itself
# is not on the classpath.  Suppress the R8 missing-service-class warning.
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# ── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Security Crypto ──────────────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }

# ── Room / WorkManager ──────────────────────────────────────────────────────
# Room 2.7+ instantiates generated _Impl classes via reflection
# (Class.getDeclaredConstructor().newInstance()), so the no-arg constructor
# must survive R8 shrinking.  Without this rule, the first call to
# WorkManager.getInstance() in a release build throws
# "NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>[]".
# Reported in issue #232 — the crash that opening Settings produced because
# SettingsViewModel has an @Inject WorkManager dependency.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

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
