package com.adobe.marketing.mobile.telemetry.internal

/**
 * Durable FIFO queue for automatic events. Implementations must be safe to call from
 * any thread; inserts may happen synchronously on the crash-handler thread.
 */
internal interface EventQueue {
    fun enqueue(event: AutoEvent)
    /** Oldest-first view of up to [maxEvents], without removing anything. */
    fun peekChunk(maxEvents: Int): List<QueuedEvent>
    /** Delete only after the server accepted the chunk. */
    fun delete(ids: Collection<Long>)
    fun clear()
    fun size(): Int
}

internal data class QueuedEvent(val id: Long, val event: AutoEvent)
