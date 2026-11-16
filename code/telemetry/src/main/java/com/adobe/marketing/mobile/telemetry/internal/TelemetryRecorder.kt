package com.adobe.marketing.mobile.telemetry.internal

import com.adobe.marketing.mobile.telemetry.Clock
import com.adobe.marketing.mobile.telemetry.IdGenerator
import com.adobe.marketing.mobile.telemetry.TelemetryConfig
import com.adobe.marketing.mobile.telemetry.TelemetryRecord
import java.util.ArrayDeque

/**
 * Single shared recorder (Q2: dual-path, one implementation). Idempotent install-once;
 * both the Core bootstrap hook and the customer install line resolve here.
 * Policy gate at capture: unknown/denied -> record nothing (no buffering).
 */
internal class TelemetryRecorder(
    private val clock: Clock,
    private val configProvider: () -> TelemetryConfig
) {
    private val queue = ArrayDeque<TelemetryRecord>()
    private val recentKeys = HashMap<String, Long>() // dedupeKey -> lastAcceptedMs
    private val lock = Any()

    @Volatile var droppedCount: Int = 0; private set
    @Volatile var recordedCount: Int = 0; private set

    /** Returns true if the record was accepted. Mirrors send-time gating for the dry-run seam. */
    fun record(record: TelemetryRecord): Boolean {
        val config = configProvider()
        if (!config.enabled) return false                 // policy: off -> nothing is recorded
        if (!record.isMeaningful()) return false          // Omega empty-payload guard

        synchronized(lock) {
            val now = clock.nowMs()
            val key = record.dedupeKey()
            val last = recentKeys[key]
            if (last != null && now - last < config.dedupeWindowMs) return false // duplicate suppression
            recentKeys[key] = now

            if (queue.size >= config.maxQueueSize) {
                queue.pollFirst()                         // evict oldest; drop is counted, not hidden
                droppedCount++
            }
            queue.addLast(record)
            recordedCount++
            return true
        }
    }

    fun pending(): List<TelemetryRecord> = synchronized(lock) { queue.toList() }

    fun pendingBytes(): Int = pending().sumOf { it.toJson().toString().toByteArray().size }

    fun shouldFlush(): Boolean {
        val c = configProvider()
        return c.enabled && c.endpoint.isNotBlank() && pendingBytes() >= c.maxBatchBytes
    }

    /** Returns drained records for upload attempt. Caller re-queues on retryable failure. */
    fun drain(): List<TelemetryRecord> = synchronized(lock) {
        val out = queue.toList(); queue.clear(); out
    }

    fun requeue(records: List<TelemetryRecord>) = synchronized(lock) {
        records.asReversed().forEach { queue.addFirst(it) }
        Unit
    }

    /** Kill switch: stop capture AND purge local data (Q3 consent semantics). */
    fun purge() = synchronized(lock) { queue.clear(); recentKeys.clear() }

    fun stats(): Map<String, Int> = mapOf(
        "pending" to pending().size, "recorded" to recordedCount, "dropped" to droppedCount
    )
}
