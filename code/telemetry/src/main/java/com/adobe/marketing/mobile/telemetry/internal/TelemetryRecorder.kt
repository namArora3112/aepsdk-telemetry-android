package com.adobe.marketing.mobile.telemetry.internal

import com.adobe.marketing.mobile.telemetry.Clock
import com.adobe.marketing.mobile.telemetry.TelemetryConfig
import com.adobe.marketing.mobile.telemetry.TelemetryRecord

/**
 * Single shared recorder (Q2: dual-path, one implementation) over a durable outbox.
 * Idempotent install-once; both the Core bootstrap hook and the customer install
 * line resolve here. Policy gate at capture: unknown/denied -> record nothing.
 * Records persist across process death; they leave the outbox only after a
 * successful upload or an explicit purge.
 */
internal class TelemetryRecorder(
    private val clock: Clock,
    private val configProvider: () -> TelemetryConfig,
    private val outbox: RecordOutbox = InMemoryOutbox()
) {
    private val recentKeys = LinkedHashMap<String, Long>() // bounded duplicate history
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
            recentKeys.entries.removeAll { now - it.value >= config.dedupeWindowMs }
            val key = record.dedupeKey()
            val last = recentKeys[key]
            if (last != null && now - last < config.dedupeWindowMs) return false // duplicate suppression
            recentKeys[key] = now
            while (recentKeys.size > config.maxQueueSize.coerceAtLeast(1)) {
                recentKeys.remove(recentKeys.keys.first())
            }

            if (outbox.count() >= config.maxQueueSize) {
                outbox.evictOldest()                      // evict oldest; drop is counted, not hidden
                droppedCount++
            }
            outbox.insert(record)
            recordedCount++
            return true
        }
    }

    /** Dry-run probe: identical gating to record() (policy, validity, dedupe window) without mutating state. */
    fun wouldAccept(record: TelemetryRecord): Boolean {
        val config = configProvider()
        if (!config.enabled || !record.isMeaningful()) return false
        synchronized(lock) {
            val last = recentKeys[record.dedupeKey()] ?: return true
            return clock.nowMs() - last >= config.dedupeWindowMs
        }
    }

    fun pending(): List<TelemetryRecord> = outbox.pending()

    fun pendingBytes(): Int = outbox.pendingBytes()

    /** Size OR age trigger: flush when the batch threshold is met or the oldest record is stale. */
    fun shouldFlush(): Boolean {
        val c = configProvider()
        if (!c.enabled || c.endpoint.isBlank()) return false
        if (pendingBytes() >= c.maxBatchBytes) return true
        val oldest = outbox.oldestTimestamp() ?: return false
        return clock.nowMs() - oldest >= c.flushIntervalMs
    }

    /** Oldest records that fit one batch envelope; capped at maxBytes, at least one record. */
    fun peekBatch(maxBytes: Int): List<TelemetryRecord> = outbox.peekBatch(maxBytes)

    /** Removes successfully uploaded records. Failed uploads leave records in place. */
    fun removeSent(recordIds: Collection<String>) = outbox.remove(recordIds)

    /** Kill switch: stop capture AND purge local data (Q3 consent semantics). */
    fun purge() = synchronized(lock) { outbox.clear(); recentKeys.clear() }

    fun stats(): Map<String, Int> = mapOf(
        "pending" to pending().size, "recorded" to recordedCount, "dropped" to droppedCount
    )
}
