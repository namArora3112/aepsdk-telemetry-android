package com.adobe.marketing.mobile.telemetry.internal

import com.adobe.marketing.mobile.telemetry.TelemetryRecord

/**
 * Durable FIFO outbox for pending telemetry records (Q3: bounded SQLite outbox).
 * Records leave the outbox only after a successful upload or an explicit purge;
 * retryable failures keep records in place, so a process death loses nothing.
 *
 * Implementation detail, not a supported public API surface.
 */
interface RecordOutbox {
    fun insert(record: TelemetryRecord)
    fun evictOldest()
    fun pending(): List<TelemetryRecord>
    fun pendingBytes(): Int
    fun count(): Int
    fun oldestTimestamp(): Long?

    /** Oldest records whose serialized size fits maxBytes; always returns at least one when non-empty. */
    fun peekBatch(maxBytes: Int): List<TelemetryRecord>
    fun remove(recordIds: Collection<String>)
    fun clear()
}

/** Test/fallback outbox without durability. */
class InMemoryOutbox : RecordOutbox {
    private val queue = ArrayDeque<TelemetryRecord>()

    override fun insert(record: TelemetryRecord) { queue.addLast(record) }
    override fun evictOldest() { queue.removeFirstOrNull() }
    override fun pending(): List<TelemetryRecord> = queue.toList()
    override fun pendingBytes(): Int = queue.sumOf { it.toJson().toString().toByteArray().size }
    override fun count(): Int = queue.size
    override fun oldestTimestamp(): Long? = queue.firstOrNull()?.timestamp

    override fun peekBatch(maxBytes: Int): List<TelemetryRecord> {
        if (queue.isEmpty()) return emptyList()
        val out = mutableListOf<TelemetryRecord>()
        var bytes = 0
        for (record in queue) {
            val size = record.toJson().toString().toByteArray().size
            if (out.isNotEmpty() && bytes + size > maxBytes) break
            out.add(record); bytes += size
        }
        return out
    }

    override fun remove(recordIds: Collection<String>) {
        if (recordIds.isEmpty()) return
        queue.removeAll { it.recordId in recordIds }
    }

    override fun clear() { queue.clear() }
}
