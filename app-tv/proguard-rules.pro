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

# Explicit non-obfuscated keep rules for every Retrofit service interface in
# the project.  Under AGP 9 / R8 full mode, the generic
# `-keep,allowobfuscation interface <1>` rule above permits renaming and
# horizontal class merging, which breaks the `Proxy.newProxyInstance` →
# Kotlin-inserted checkcast invariant that `retrofit.create(Foo::class.java)`
# relies on.  Symptom: ClassCastException thrown from the @Provides method
# the moment the Hilt graph constructs the Retrofit service (issue #247 on
# 0.14.3, first triggered on TV because app-tv has fewer live call sites
# into these interfaces than app-phone, so R8 is more aggressive).
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

# ── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Security Crypto / Tink ───────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.errorprone.annotations.**

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
