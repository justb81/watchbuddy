# ── Retrofit service interfaces ───────────────────────────────────────────────
# Prevent R8 from renaming or merging Retrofit service interfaces defined in
# this module. Retrofit.create(Foo::class.java) relies on the original class
# name; renaming it breaks the Kotlin checkcast that follows
# Proxy.newProxyInstance and throws ClassCastException at DI graph
# initialisation (first seen in issue #247).
#
# Declared as consumerProguardFiles so this rule is automatically inherited by
# every module that depends on :core (app-phone and app-tv). Adding a new
# Retrofit interface to :core therefore requires no manual entry in any app
# module's proguard-rules.pro file.
-if interface * { @retrofit2.http.* <methods>; }
-keep interface <1> { *; }
