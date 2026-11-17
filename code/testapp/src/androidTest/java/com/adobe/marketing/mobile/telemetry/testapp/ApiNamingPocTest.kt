package com.adobe.marketing.mobile.telemetry.testapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.telemetry.Telemetry
import com.adobe.marketing.mobile.telemetry.TelemetryConfig
import com.adobe.marketing.mobile.telemetry.TelemetryRecord
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Experiments documenting inference limits, not guarantees of API invocation provenance. */
@RunWith(AndroidJUnit4::class)
class ApiNamingPocTest {
    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<TelemetryTestApplication>()
        assertTrue("SDK registration", app.ready.await(10, TimeUnit.SECONDS))
        Telemetry.applyConfig(TelemetryConfig(enabled = false))
        Telemetry.applyConfig(TelemetryConfig(enabled = true))
    }

    @After
    fun cleanup() {
        Telemetry.applyConfig(TelemetryConfig(enabled = false))
    }

    private fun records(count: Int): List<TelemetryRecord> {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Telemetry.pendingCount() < count && System.nanoTime() < end) Thread.sleep(10)
        return Telemetry.pendingRecords().also {
            assertEquals("Pending records: $it", count, it.size)
        }
    }

    @Test
    fun sharedEventNameNeedsPayloadKeySelectorsToDistinguishRealApis() {
        val events = CopyOnWriteArrayList<Event>()
        val delivered = CountDownLatch(2)
        MobileCore.registerEventListener(EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT) {
            if (it.eventData?.get("action") == "naming-poc-action" ||
                it.eventData?.get("state") == "naming-poc-state"
            ) {
                events.add(it)
                delivered.countDown()
            }
        }
        MobileCore.trackAction("naming-poc-action", null)
        MobileCore.trackState("naming-poc-state", null)

        assertTrue("Real API events delivered", delivered.await(5, TimeUnit.SECONDS))
        assertEquals(2, events.size)
        assertEquals(setOf("Analytics Track"), events.map { it.name }.toSet())
        assertEquals(1, events.map { it.type to it.source }.distinct().size)
        val result = records(2)
        assertEquals(setOf("sdk.core.trackAction", "sdk.core.trackState"),
            result.map { it.eventName }.toSet())
        assertEquals(events.map { it.uniqueIdentifier }.toSet(),
            result.map { it.requestId }.toSet())
        assertTrue(result.all { it.capture == "hubProjection" })
        assertFalse(result.joinToString { it.toJson().toString() }.contains("naming-poc-"))
    }

    @Test
    fun customDispatchCanBeMisattributedToAnApiThatWasNeverCalled() {
        val custom = Event.Builder(
            "Analytics Track", EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT
        ).setEventData(mapOf("action" to "naming-poc-custom")).build()

        MobileCore.dispatchEvent(custom)

        val record = records(1).single()
        assertEquals(custom.uniqueIdentifier, record.requestId)
        // This name is inferred even though this test never invokes trackAction.
        assertEquals("sdk.core.trackAction", record.eventName)
        assertEquals("hubProjection", record.capture)
        assertNull(record.operationId)
    }

    @Test
    fun oneCustomEventCanMatchTwoApiNamesWithoutTwoApiCalls() {
        val custom = Event.Builder(
            "Analytics Track", EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT
        ).setEventData(mapOf("action" to "naming-poc-action", "state" to "naming-poc-state")).build()

        MobileCore.dispatchEvent(custom)

        val result = records(2)
        assertEquals(setOf("sdk.core.trackAction", "sdk.core.trackState"),
            result.map { it.eventName }.toSet())
        assertEquals(setOf(custom.uniqueIdentifier), result.map { it.requestId }.toSet())
    }

    @Test
    fun localOnlyApiCallsAreInvisibleToTheEventCatalog() {
        repeat(3) { assertTrue(MobileCore.extensionVersion().isNotBlank()) }
        MobileCore.trackAction("naming-poc-fifo-sentinel", null)

        val record = records(1).single()
        assertEquals("sdk.core.trackAction", record.eventName)
        assertEquals("hubProjection", record.capture)
    }

    @Test
    fun explicitHookCanNameLocalOnlyWorkAndCorrelateItsCompletion() {
        val name = "sdk.core.extensionVersion"
        val producer = "com.adobe.module.core"
        val id = Telemetry.operationStarted(producer, name)
        assertNotNull(id)
        assertTrue(MobileCore.extensionVersion().isNotBlank())
        Telemetry.operationCompleted(id, producer, name, "success", "localReturn")

        val result = records(2)
        assertTrue(result.all { it.eventName == name && it.capture == "directHook" })
        assertEquals(setOf(id), result.map { it.operationId }.toSet())
        assertEquals(setOf("started", "completed"), result.map { it.phase }.toSet())
        val completed = result.single { it.phase == "completed" }
        assertEquals("success", completed.outcome)
        assertEquals("localReturn", completed.outcomeMeaning)
        assertNotNull(completed.durationMs)
    }
}
