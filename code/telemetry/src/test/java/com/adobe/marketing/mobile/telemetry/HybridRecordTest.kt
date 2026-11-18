package com.adobe.marketing.mobile.telemetry

import org.junit.Assert.*
import org.junit.Test

class HybridRecordTest {
    @Test
    fun explicitApiProvenanceSurvivesPersistenceWithoutSharingEventDedupeIdentity() {
        val api = TelemetryRecord(
            "record", 100, "sdk", "get", "started", "directApi",
            operationId = "call", recordKind = "api_invocation",
            extensionVersion = "1.0", contractVersion = 2,
            outcomeContract = "cache_callback.v1", coverage = "entry_and_outcome",
            captureMode = "hybrid"
        )
        assertEquals(api, TelemetryRecord.fromJson(api.toJson()))
        assertNotEquals(api.dedupeKey(), api.copy(recordKind = "event_observation").dedupeKey())
    }

    @Test
    fun oldPersistedRecordsAreNotPromotedToConfirmedApiTelemetry() {
        val old = TelemetryRecord("record", 100, "sdk", "get", "event", "hubProjection")
            .toJson().apply { remove("recordKind") }
        val restored = TelemetryRecord.fromJson(old)
        assertEquals("event_observation", restored.recordKind)
        assertNull(restored.coverage)
        assertNull(restored.contractVersion)
    }
}
