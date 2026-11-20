# App Startup initializer is referenced from the merged manifest only.
-keep class com.adobe.marketing.mobile.telemetry.TelemetryStartupInitializer { *; }

# WorkManager instantiates workers reflectively with (Context, WorkerParameters).
-keep class com.adobe.marketing.mobile.telemetry.internal.TelemetrySyncWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Event/property models are serialized by name into upload JSON.
-keepclassmembers class com.adobe.marketing.mobile.telemetry.internal.AutoEvent { *; }
