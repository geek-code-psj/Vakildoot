# VakilDoot ProGuard Rules

# ── ExecuTorch ────────────────────────────────────────────────────────────────
-keep class org.pytorch.executorch.** { *; }
-keep class org.pytorch.** { *; }
-dontwarn org.pytorch.**

# ── iText 7 ───────────────────────────────────────────────────────────────────
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**
-dontwarn org.bouncycastle.**

# ── ObjectBox ─────────────────────────────────────────────────────────────────
-keep class io.objectbox.** { *; }
-keep @io.objectbox.annotation.Entity class * { *; }
-keep class * extends io.objectbox.EntityInfo { *; }
-dontwarn io.objectbox.**

# ── Hilt / DI ─────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── Compose ───────────────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }

# ── General Android ───────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application

# ── MediaPipe (for embedding model) ──────────────────────────────────────────
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ── Remove logging in release ─────────────────────────────────────────────────
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}
