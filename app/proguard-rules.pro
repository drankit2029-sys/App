# ==============================================================================
# TakeOne R8 / ProGuard Rules for Production Release Builds
# ==============================================================================

# Preserve source file names and line numbers in Play Console crash stacktraces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 1. Room Database Entities, DAOs, and generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.example.hello.TakeEntity { *; }
-keep class com.example.hello.TakeDao { *; }
-keep class com.example.hello.TakeDatabase { *; }
-keep class com.example.hello.TakeDatabase_Impl { *; }

# 2. WorkManager Workers (instantiated via reflection with (Context, WorkerParameters))
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.example.hello.ConversionWatchdogWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 3. Custom Views referenced in XML layouts
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# 4. DSP Processors & Serialization models
-keep class com.example.hello.DspChain$* { *; }
-keep class * extends com.example.hello.AudioProcessor { *; }

# 5. Google Play Billing Library
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.api.** { *; }


================================================================================
ISSUE 3: MISSING ADAPTIVE LAUNCHER ICONS & MANIFEST ATTRIBUTES
================================================================================
PROBLEM DETAILS:
  1. `app/src/main/AndroidManifest.xml` has no `android:icon` or
     `android:roundIcon` attributes on `<application>`, and `app/src/main/res/`
     contains no `mipmap-*` directories. On a user's phone and in Play Console,
     the app falls back to the generic Android default green robot icon.
  2. `<application>` uses `android:theme="@style/Theme.HelloWorld"`.
  3. Missing `<uses-permission android:name="com.android.vending.BILLING" />`.

FIX DETAILS:
  - Add vector-based Adaptive Launcher Icons (`ic_launcher_background.xml` in
    studio dark `#0E1013` and `ic_launcher_foreground.xml` with TakeOne's
    signature `#FF3B30` record ring, microphone capsule, and waveform bars),
    wired via `mipmap-anydpi-v26/ic_launcher.xml` (including Android 13+
    `<monochrome>` themed icon support) and `mipmap/ic_launcher.xml` fallback
    for API 24-25.
  - Alias `Theme.TakeOne` in `themes.xml` while keeping `Theme.HelloWorld` as a
    parent/alias so any test referencing `R.style.Theme_HelloWorld` continues
    to pass.

