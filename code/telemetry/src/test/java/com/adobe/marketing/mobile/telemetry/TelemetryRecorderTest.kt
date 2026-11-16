package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.telemetry.internal.InMemoryOutbox
import com.adobe.marketing.mobile.telemetry.internal.TelemetryRecorder
import org.junit.Assert.*
import org.junit.Test

class TelemetryRecorderTest {
    private fun record(requestId: String? = null, operationId: String? = null, phase: String = "event") =
        TelemetryRecord("record-$requestId$operationId$phase", 100, "sdk", "reviewed.event", phase, "hubProjection",
            operationId = operationId, requestId = requestId)

    @Test
    fun distinctHubCallsSurviveWhileDuplicateObservationIsSuppressed() {
        val recorder = TelemetryRecorder(Clock { 100 }, configProvider = { TelemetryConfig(enabled = true) })
        assertTrue(recorder.record(record(requestId = "event-1")))
        assertTrue(recorder.record(record(requestId = "event-2")))
        assertFalse(recorder.record(record(requestId = "event-1")))
        assertEquals(2, recorder.pending().size)
    }

    @Test
    fun independentOperationsAndTheirPhasesAreNotCollapsed() {
        val recorder = TelemetryRecorder(Clock { 100 }, configProvider = { TelemetryConfig(enabled = true) })
        assertTrue(recorder.record(record(operationId = "operation-1", phase = "started")))
        assertTrue(recorder.record(record(operationId = "operation-1", phase = "completed")))
        assertTrue(recorder.record(record(operationId = "operation-2", phase = "started")))
        assertFalse(recorder.record(record(operationId = "operation-1", phase = "started")))
    }

    @Test
    fun failedUploadKeepsRecordsAndSuccessRemovesOnlySentBatch() {
        val recorder = TelemetryRecorder(Clock { 100 }, configProvider = { TelemetryConfig(enabled = true) })
        recorder.record(record(requestId = "a"))
        recorder.record(record(requestId = "b"))
        recorder.record(record(requestId = "c"))
        val batch = recorder.peekBatch(Int.MAX_VALUE)
        // Simulated retryable failure: nothing is removed.
        assertEquals(3, recorder.pending().size)
        recorder.removeSent(listOf(batch[0].recordId, batch[1].recordId))
        assertEquals(listOf("c"), recorder.pending().map { it.requestId })
    }

    @Test
    fun peekBatchHonorsHardByteCap() {
        val config = TelemetryConfig(enabled = true, maxBatchBytes = 500)
        val recorder = TelemetryRecorder(Clock { 100 }, configProvider = { config })
        repeat(5) { recorder.record(record(requestId = "r$it")) }
        val batch = recorder.peekBatch(config.maxBatchBytes)
        assertTrue(batch.isNotEmpty())
        assertTrue(batch.size < 5)
        assertTrue(batch.sumOf { it.toJson().toString().toByteArray().size } <= config.maxBatchBytes)
    }

    @Test
    fun flushTriggersOnSizeOrAgeOfOldestRecord() {
        var now = 100L
        val config = TelemetryConfig(enabled = true, endpoint = "https://example.test",
            maxBatchBytes = Int.MAX_VALUE, flushIntervalMs = 60_000)
        val recorder = TelemetryRecorder(Clock { now }, configProvider = { config })
        assertFalse(recorder.shouldFlush())
        recorder.record(record(requestId = "old"))
        assertFalse(recorder.shouldFlush())
        now += 60_000
        assertTrue("stale record must flush even below size threshold", recorder.shouldFlush())
    }

    @Test
    fun queueCapEvictsOldestAcrossOutbox() {
        val recorder = TelemetryRecorder(Clock { 100 }, configProvider = { TelemetryConfig(enabled = true, maxQueueSize = 2) })
        recorder.record(record(requestId = "old-1"))
        recorder.record(record(requestId = "old-2"))
        recorder.record(record(requestId = "new-1"))
        assertEquals(listOf("old-2", "new-1"), recorder.pending().map { it.requestId })
        assertEquals(1, recorder.droppedCount)
    }

    @Test
    fun recordsSurviveRecorderRestartOverSameOutbox() {
        val outbox = InMemoryOutbox()
        val config = TelemetryConfig(enabled = true)
        val first = TelemetryRecorder(Clock { 100 }, { config }, outbox)
        first.record(record(requestId = "durable-1"))
        first.record(record(requestId = "durable-2"))
        val second = TelemetryRecorder(Clock { 100 }, { config }, outbox)
        assertEquals(listOf("durable-1", "durable-2"), second.pending().map { it.requestId })
    }

    @Test
    fun disabledPolicyRejectsCaptureAndPurgeClearsOutbox() {
        var config = TelemetryConfig(enabled = true)
        val outbox = InMemoryOutbox()
        val recorder = TelemetryRecorder(Clock { 100 }, { config }, outbox)
        recorder.record(record(requestId = "old"))
        config = config.copy(enabled = false)
        recorder.purge()
        assertFalse(recorder.record(record(requestId = "new")))
        assertTrue(recorder.pending().isEmpty())
    }

    @Test
    fun uncorrelatedDirectEventsRetainWindowDedupe() {
        var now = 100L
        val recorder = TelemetryRecorder(Clock { now }, configProvider = { TelemetryConfig(enabled = true) })
        assertTrue(recorder.record(record()))
        assertFalse(recorder.record(record()))
        now += 5000
        assertTrue(recorder.record(record()))
    }
}
