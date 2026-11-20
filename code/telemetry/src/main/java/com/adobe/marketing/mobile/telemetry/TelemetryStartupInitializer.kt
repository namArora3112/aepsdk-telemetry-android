package com.adobe.marketing.mobile.telemetry

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.Initializer
import com.adobe.marketing.mobile.telemetry.internal.TelemetryCollector

/**
 * Jetpack App Startup entry point: starts Phase-1 automatic, disk-only collection at
 * process launch, before Core runs. Merged into the host manifest even when this SDK
 * is only a transitive dependency of Core.
 *
 * Hosts can opt out with tools:node="remove" on this meta-data, or with
 * `com.adobe.marketing.mobile.telemetry.AUTO_START = false` application meta-data.
 */
class TelemetryStartupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        android.util.Log.d("AEPTelemetryAuto", "initializer_create; phase=disk_only")
        (context.applicationContext as? Application)?.let { TelemetryCollector.start(it) }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(ProcessLifecycleInitializer::class.java)
}
