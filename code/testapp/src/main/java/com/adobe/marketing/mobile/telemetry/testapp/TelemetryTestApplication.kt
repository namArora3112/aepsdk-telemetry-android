package com.adobe.marketing.mobile.telemetry.testapp

import android.app.Application
import com.adobe.marketing.mobile.AdobeCallback
import com.adobe.marketing.mobile.LoggingMode
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.telemetry.Telemetry

/**
 * Customer-install path (Q5 Part 2): one line BEFORE MobileCore.initialize,
 * so pre-initialize calls are also captured.
 */
class TelemetryTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Telemetry.install(this) // telemetry attaches first — dual-path customer entry

        MobileCore.setLogLevel(LoggingMode.DEBUG)

        // Captured even though Core is not initialized yet (pre-init diagnostic):
        Telemetry.event("com.adobe.module.core", "sdk.core.preInit.customCall")

        MobileCore.initialize(this, "poc-no-appid", AdobeCallback<Any> { /* capture-only POC mode */ })
        Telemetry.event("com.adobe.module.core", "sdk.core.initialize", "coreBootstrap")
    }
}
