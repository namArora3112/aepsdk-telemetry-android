package com.adobe.marketing.mobile.telemetry.internal

import com.adobe.marketing.mobile.telemetry.ConsentStatus
import com.adobe.marketing.mobile.telemetry.SyncSettings
import org.json.JSONObject

/**
 * Phase-2 gate decisions, isolated from Android for unit tests:
 *
 *  1. Invalid config (non-HTTPS / disallowed host after sanitize, or blank API key)
 *     -> keep buffering, warn, no upload.
 *  2. OPTED_OUT  -> clear the queue and stop.
 *     UNKNOWN    -> keep buffering; re-evaluated when consent changes.
 *     OPTED_IN   -> drain.
 *  3. Drain in chunks; a chunk is deleted only after server success; consent is
 *     re-checked before every chunk.
 */
internal class SyncEngine(
    private val queue: EventQueue,
    private val transport: Transport,
    private val consent: () -> ConsentStatus,
    private val settings: () -> SyncSettings,
    private val warn: (String) -> Unit = {},
    private val scheduleRetry: (delayMs: Long) -> Unit = {},
    private val uploadsEnabled: () -> Boolean = { true }
) {
    sealed class Outcome {
        object NotInitialized : Outcome()
        object InvalidConfig : Outcome()
        object OptedOut : Outcome()
        object Buffering : Outcome()
        object Drained : Outcome()
        data class Failed(val retryAfterMs: Long?) : Outcome()
        object RejectedHead : Outcome()
    }

    fun isValid(settings: SyncSettings): Boolean = settings.isValid()

    @Synchronized
    fun drain(): Outcome {
        while (true) {
            when (consent()) {
                ConsentStatus.OPTED_OUT -> {
                    queue.clear()
                    return Outcome.OptedOut
                }
                ConsentStatus.UNKNOWN -> return Outcome.Buffering
                ConsentStatus.OPTED_IN -> Unit
            }
            if (!uploadsEnabled()) return Outcome.Buffering
            val raw = settings()
            if (!isValid(raw)) {
                warn("Telemetry sync config invalid; still buffering.")
                return Outcome.InvalidConfig
            }
            val active = raw.sanitized()
            val chunk = queue.peekChunk(active.batchSize)
            if (chunk.isEmpty()) return Outcome.Drained
            if (consent() != ConsentStatus.OPTED_IN) continue
            if (!uploadsEnabled()) return Outcome.Buffering
            val result = transport.post(active, chunkBody(active, chunk))
            if (!uploadsEnabled()) return Outcome.Buffering
            when (result) {
                UploadResult.Accepted -> queue.delete(chunk.map { it.id })
                is UploadResult.Retryable -> {
                    scheduleRetry(result.retryAfterMs ?: active.flushIntervalMs)
                    return Outcome.Failed(result.retryAfterMs)
                }
                UploadResult.Rejected -> return Outcome.RejectedHead
            }
        }
    }

    private fun chunkBody(settings: SyncSettings, chunk: List<QueuedEvent>): String {
        val events = org.json.JSONArray()
        chunk.forEach { events.put(it.event.toJson()) }
        return JSONObject()
            .put("configVersion", settings.configVersion)
            .put("events", events)
            .toString()
    }
}
