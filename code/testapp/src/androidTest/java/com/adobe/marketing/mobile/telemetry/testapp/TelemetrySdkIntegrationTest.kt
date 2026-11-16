package com.adobe.marketing.mobile.telemetry.testapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adobe.marketing.mobile.CampaignClassic
import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.telemetry.Telemetry
import com.adobe.marketing.mobile.telemetry.TelemetryConfig
import com.adobe.marketing.mobile.telemetry.TelemetryRecord
import com.adobe.marketing.mobile.telemetry.TelemetryPlugin
import com.adobe.marketing.mobile.plugin.TelemetryEventMapping
import com.adobe.marketing.mobile.plugin.ITelemetryPlugin
import com.adobe.marketing.mobile.telemetrybridge.TelemetryBridge
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TelemetrySdkIntegrationTest {
    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<TelemetryTestApplication>()
        assertTrue("SDK registration", app.ready.await(10, TimeUnit.SECONDS))
        Telemetry.applyConfig(TelemetryConfig(enabled = false))
        Telemetry.applyConfig(TelemetryConfig(enabled = true))
    }

    private fun records(count: Int): List<TelemetryRecord> {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Telemetry.pendingCount() < count && System.nanoTime() < end) Thread.sleep(10)
        val result = Telemetry.pendingRecords()
        assertEquals("Pending records: $result", count, result.size)
        return result
    }

    @Test
    fun actualCoreTrackingPreservesDistinctCallsAndOmitsCustomerData() {
        MobileCore.trackAction("private-action", mapOf("customer-id" to "private-value"))
        MobileCore.trackAction("private-action", null)
        MobileCore.trackState("private-screen", null)
        val result = records(3)
        assertEquals(2, result.count { it.eventName == "sdk.core.trackAction" })
        assertEquals(1, result.count { it.eventName == "sdk.core.trackState" })
        assertTrue(result.all { it.capture == "hubProjection" })
        assertEquals(3, result.map { it.requestId }.distinct().size)
        assertFalse(result.joinToString { it.toJson().toString() }.contains("private-"))
    }

    @Test
    fun actualCampaignApisAreMappedWithoutEdgeTransport() {
        CampaignClassic.registerDevice("fake-token", "fake-user", mapOf("secret" to "hidden"))
        CampaignClassic.trackNotificationReceive(mapOf("_dId" to "fake-delivery", "_mId" to "fake-message"))
        CampaignClassic.trackNotificationClick(mapOf("_dId" to "fake-delivery", "_mId" to "fake-message"))
        val result = records(3)
        assertEquals(
            listOf("campaign.registerDevice", "campaign.pushTracking.received", "campaign.pushTracking.clicked"),
            result.map { it.eventName }
        )
        assertTrue(result.all { it.capture == "hubProjection" })
        assertFalse(result.joinToString { it.toJson().toString() }.contains("fake-"))
    }

    @Test
    fun actualRejectedCampaignCallsUseTypedDirectHooksOnly() {
        CampaignClassic.registerDevice("", null, null)
        CampaignClassic.trackNotificationReceive(emptyMap())
        CampaignClassic.trackNotificationClick(emptyMap())
        val result = records(6)
        assertTrue(result.all { it.capture == "directHook" })
        val completed = result.filter { it.phase == "completed" }
        assertEquals(3, completed.size)
        assertTrue(completed.all { it.outcome == "rejected" && it.durationMs != null })
        assertEquals(
            setOf("campaign.registerDevice", "campaign.pushTracking.received", "campaign.pushTracking.clicked"),
            completed.map { it.eventName }.toSet()
        )
        assertEquals(result.filter { it.phase == "started" }.map { it.operationId }.toSet(),
            completed.map { it.operationId }.toSet())
    }

    @Test
    fun actualEdgeApiUsesHubCapture() {
        assumeTrue(EdgeScenario.available())
        EdgeScenario.sendValid()
        val record = records(1).single()
        assertEquals("sdk.edge.sendEvent", record.eventName)
        assertEquals("hubProjection", record.capture)
        assertNotNull(record.requestId)
    }

    @Test
    fun actualRejectedEdgeApiUsesTypedLifecycle() {
        assumeTrue(EdgeScenario.available())
        EdgeScenario.sendInvalid()
        val result = records(2)
        assertTrue(result.all { it.eventName == "sdk.edge.sendEvent" && it.capture == "directHook" })
        assertEquals("rejected", result.single { it.phase == "completed" }.outcome)
    }

    @Test
    fun optOutPurgesAndDoesNotInterruptBusinessListeners() {
        MobileCore.trackAction("before-optout", null)
        records(1)
        Telemetry.applyConfig(TelemetryConfig(enabled = false))
        val delivered = CountDownLatch(1)
        MobileCore.registerEventListener(EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT) {
            delivered.countDown()
        }
        MobileCore.trackAction("while-disabled", null)
        CampaignClassic.registerDevice("", null, null)
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertTrue(Telemetry.pendingRecords().isEmpty())
        Telemetry.applyConfig(TelemetryConfig(enabled = true))
        MobileCore.trackAction("after-reenable", null)
        assertEquals(1, records(1).size)
    }

    @Test
    fun lateCallbackFromOldPolicyCannotReappearAfterReenable() {
        val generation = Telemetry.stats()["captureGeneration"] as Long
        Telemetry.applyConfig(TelemetryConfig(enabled = false))
        Telemetry.applyConfig(TelemetryConfig(enabled = true))
        TelemetryPlugin().recordEvent("sdk", "stale.event", "old-event-id", 1L, generation)
        assertTrue(Telemetry.pendingRecords().isEmpty())
        MobileCore.trackAction("current-policy", null)
        assertEquals("sdk.core.trackAction", records(1).single().eventName)
    }

    @Test
    fun actualEdgeXdmTypeUsesEnabledCatalogWithoutDuplicateObservation() {
        assumeTrue(EdgeScenario.available())
        val original = Telemetry.eventMappings()
        val semantic = TelemetryEventMapping(EventType.EDGE, EventSource.REQUEST_CONTENT,
            "com.adobe.edge", "application.launch", xdmEventType = "application.*")
        try {
            Telemetry.setEventMappings(original + semantic + semantic +
                semantic.copy(telemetryEventName = "disabled.event", enabled = false))
            EdgeScenario.sendValid()
            val result = records(2)
            assertEquals(setOf("sdk.edge.sendEvent", "application.launch"), result.map { it.eventName }.toSet())
            assertEquals(1, result.map { it.requestId }.distinct().size)
        } finally {
            Telemetry.setEventMappings(original)
        }
    }

    @Test
    fun invalidCatalogFailsClosedAndPurgesPendingRecords() {
        MobileCore.trackAction("before-invalid-policy", null)
        records(1)
        val original = Telemetry.eventMappings()
        try {
            Telemetry.setEventMappings(listOf(original.first().copy(xdmEventType = "invalid*selector")))
            fail("Invalid wildcard selector must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(false, Telemetry.stats()["enabled"])
            assertTrue(Telemetry.pendingRecords().isEmpty())
        } finally {
            Telemetry.setEventMappings(original)
            Telemetry.applyConfig(TelemetryConfig(enabled = true))
        }
        MobileCore.trackAction("after-policy-recovery", null)
        assertEquals("sdk.core.trackAction", records(1).single().eventName)
    }

    @Test
    fun blockedTelemetryAndFullQueueDoNotDelayRealBusinessDispatch() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(101)
        val blocked = object : ITelemetryPlugin {
            override fun recordEvent(producer: String, eventName: String, eventId: String,
                                     timestamp: Long, generation: Long) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }

            override fun recordOperation(operationId: String, producer: String, eventName: String,
                                         phase: String, outcome: String?, meaning: String?,
                                         durationMs: Long?, generation: Long) {}
        }
        MobileCore.registerEventListener(EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT) {
            delivered.countDown()
        }
        try {
            TelemetryBridge.configure(blocked, true, Telemetry.eventMappings())
            MobileCore.trackAction("blocked-sink", null)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(100) { MobileCore.trackAction("business-continues", null) }
            assertTrue("Business dispatch must not wait for telemetry", delivered.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            Telemetry.applyConfig(TelemetryConfig(enabled = true))
        }
    }
}
