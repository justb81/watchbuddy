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

# Explicit non-obfuscated keep rules for every Retrofit service interface in
# the project.  Under AGP 9 / R8 full mode, the generic
# `-keep,allowobfuscation interface <1>` rule above permits renaming and
# horizontal class merging, which breaks the `Proxy.newProxyInstance` →
# Kotlin-inserted checkcast invariant that `retrofit.create(Foo::class.java)`
# relies on.  Symptom: ClassCastException thrown from the @Provides method
# the moment the Hilt graph constructs the Retrofit service (issue #247).
# The phone has more live call sites into TmdbApiService / TraktApiService
# than the TV, so R8 has been less aggressive there in practice — but the
# guarantee is fragile.  Keep the rules aligned across modules.
# NOTE: Every new Retrofit interface MUST be added here as well.
-keep interface com.justb81.watchbuddy.core.tmdb.TmdbApiService { *; }
-keep interface com.justb81.watchbuddy.core.trakt.TraktApiService { *; }
-keep interface com.justb81.watchbuddy.core.trakt.TokenProxyService { *; }

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
# Room 2.7+ reflectively invokes a no-arg constructor on generated _Impl
# classes (see androidx.room.Room.getGeneratedImplementation).  R8 full mode
# in AGP 9 strips constructors that have no static callers even under
# `-keep class X { *; }` — issue #232 traced to this pathway on the phone
# (SettingsViewModel has an @Inject WorkManager dependency).  Kept the
# constructor explicitly; matches the TV module for consistency.
-keep,includedescriptorclasses class * extends androidx.room.RoomDatabase {
    <init>();
    *;
}
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl {
    <init>();
    *;
}

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
