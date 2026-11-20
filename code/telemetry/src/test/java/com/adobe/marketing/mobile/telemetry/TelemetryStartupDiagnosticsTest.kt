package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.telemetry.internal.AutoEvent
import com.adobe.marketing.mobile.telemetry.internal.EventQueue
import com.adobe.marketing.mobile.telemetry.internal.QueuedEvent
import com.adobe.marketing.mobile.telemetry.internal.TelemetryStartupDiagnostics
import org.junit.Assert.*
import org.junit.Test

class TelemetryStartupDiagnosticsTest {
    @Test fun disabledDiagnosticsNeverReadStorageOrLog() {
        val diagnostics = TelemetryStartupDiagnostics(false) { error("must not log") }
        val queue = object : EventQueue {
            override fun enqueue(event: AutoEvent) = error("must not write")
            override fun peekChunk(maxEvents: Int): List<QueuedEvent> = error("must not read")
            override fun delete(ids: Collection<Long>) = error("must not delete")
            override fun clear() = error("must not clear")
            override fun size(): Int = error("must not count")
        }
        diagnostics.snapshot(queue)
        diagnostics.persisted(1, AutoEvent("app_start", 123L, "private-session")) { error("must not count") }
        diagnostics.milestone("AUTO_COLLECTION_STARTING")
    }

    @Test fun snapshotIsBoundedReadOnlyAndRedactsUnapprovedFields() {
        val output = mutableListOf<String>()
        val event = AutoEvent("app_start", 123L, "private-session",
            mapOf("cold_start_ms" to "42", "custom" to "private-value", "thread" to "private-thread"))
        var requested = 0
        val queue = object : EventQueue {
            override fun enqueue(event: AutoEvent) = error("must not write")
            override fun peekChunk(maxEvents: Int): List<QueuedEvent> {
                requested = maxEvents
                return (1L..maxEvents.toLong()).map { QueuedEvent(it, event) }
            }
            override fun delete(ids: Collection<Long>) = error("must not delete")
            override fun clear() = error("must not clear")
            override fun size() = 50
        }
        TelemetryStartupDiagnostics(true, output::add).snapshot(queue)
        assertEquals(20, requested)
        assertEquals(20, output.count { it.startsWith("AUTO_QUEUE_ROW;") })
        val logs = output.joinToString("\n")
        assertTrue(logs.contains("total_rows=50; shown=20"))
        assertTrue(logs.contains("\"cold_start_ms\":\"42\""))
        assertTrue(logs.contains("\"captured_at_ms\":123"))
        assertFalse(logs.contains("private-"))
        assertFalse(logs.contains("\"custom\""))
    }

    @Test fun failingDiagnosticSinkDoesNotEscapeIntoCollection() {
        val diagnostics = TelemetryStartupDiagnostics(true) { error("logger unavailable") }
        diagnostics.persisted(1, AutoEvent("app_start", 123L, "s")) { 1 }
    }
}
