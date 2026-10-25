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
