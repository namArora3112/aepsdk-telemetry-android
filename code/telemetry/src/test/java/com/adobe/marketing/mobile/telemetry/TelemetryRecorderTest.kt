package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.telemetry.internal.TelemetryRecorder
import org.junit.Assert.*
import org.junit.Test

class TelemetryRecorderTest {
    private fun record(requestId: String? = null, operationId: String? = null, phase: String = "event") =
        TelemetryRecord("record", 100, "sdk", "reviewed.event", phase, "hubProjection",
            operationId = operationId, requestId = requestId)

    @Test
    fun distinctHubCallsSurviveWhileDuplicateObservationIsSuppressed() {
        val recorder = TelemetryRecorder(Clock { 100 }) { TelemetryConfig(enabled = true) }
        assertTrue(recorder.record(record(requestId = "event-1")))
        assertTrue(recorder.record(record(requestId = "event-2")))
        assertFalse(recorder.record(record(requestId = "event-1")))
        assertEquals(2, recorder.pending().size)
    }

    @Test
    fun independentOperationsAndTheirPhasesAreNotCollapsed() {
        val recorder = TelemetryRecorder(Clock { 100 }) { TelemetryConfig(enabled = true) }
        assertTrue(recorder.record(record(operationId = "operation-1", phase = "started")))
        assertTrue(recorder.record(record(operationId = "operation-1", phase = "completed")))
        assertTrue(recorder.record(record(operationId = "operation-2", phase = "started")))
        assertFalse(recorder.record(record(operationId = "operation-1", phase = "started")))
    }

    @Test
    fun retryCannotExceedQueueBoundOrEvictNewerRecords() {
        val recorder = TelemetryRecorder(Clock { 100 }) { TelemetryConfig(enabled = true, maxQueueSize = 2) }
        recorder.record(record(requestId = "old-1"))
        recorder.record(record(requestId = "old-2"))
        val retry = recorder.drain()
        recorder.record(record(requestId = "new-1"))
        recorder.record(record(requestId = "new-2"))
        recorder.requeue(retry)
        assertEquals(listOf("new-1", "new-2"), recorder.pending().map { it.requestId })
        assertEquals(2, recorder.droppedCount)
    }

    @Test
    fun disabledPolicyRejectsCaptureAndLateRetry() {
        var config = TelemetryConfig(enabled = true)
        val recorder = TelemetryRecorder(Clock { 100 }) { config }
        recorder.record(record(requestId = "old"))
        val retry = recorder.drain()
        config = config.copy(enabled = false)
        recorder.purge()
        recorder.requeue(retry)
        assertFalse(recorder.record(record(requestId = "new")))
        assertTrue(recorder.pending().isEmpty())
    }

    @Test
    fun uncorrelatedDirectEventsRetainWindowDedupe() {
        var now = 100L
        val recorder = TelemetryRecorder(Clock { now }) { TelemetryConfig(enabled = true) }
        assertTrue(recorder.record(record()))
        assertFalse(recorder.record(record()))
        now += 5000
        assertTrue(recorder.record(record()))
    }
}
