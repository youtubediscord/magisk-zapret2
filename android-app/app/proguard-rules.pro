# Add project specific ProGuard rules here.

# libsu core ships its own consumer rules for Shell.Initializer/RootService and
# release-only debug stripping. Do not add a broad package keep here: it would
# prevent R8 from optimizing the dependency while duplicating the trusted AAR rule.

# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
