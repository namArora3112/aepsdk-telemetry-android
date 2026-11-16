package com.adobe.marketing.mobile.telemetry.testapp

import android.app.Application
import com.adobe.marketing.mobile.AdobeCallback
import com.adobe.marketing.mobile.LoggingMode
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.CampaignClassic
import com.adobe.marketing.mobile.telemetry.Telemetry
import java.util.concurrent.CountDownLatch

/**
 * Customer-install path (Q5 Part 2): one line BEFORE MobileCore.initialize,
 * so pre-initialize calls are also captured.
 */
class TelemetryTestApplication : Application() {
    val ready = CountDownLatch(1)

    override fun onCreate() {
        super.onCreate()

        Telemetry.install(this) // telemetry attaches first — dual-path customer entry

        MobileCore.setLogLevel(LoggingMode.DEBUG)

        MobileCore.setApplication(this)
        MobileCore.registerExtensions(listOf(CampaignClassic.EXTENSION) + EdgeScenario.extensions()) {
            ready.countDown()
        }
    }
}
