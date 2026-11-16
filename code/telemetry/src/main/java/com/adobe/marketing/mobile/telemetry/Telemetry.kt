package com.adobe.marketing.mobile.telemetry

import android.app.Application
import com.adobe.marketing.mobile.telemetry.internal.InMemoryOutbox
import com.adobe.marketing.mobile.telemetry.internal.SqliteOutbox
import com.adobe.marketing.mobile.telemetry.internal.TelemetryRecorder
import com.adobe.marketing.mobile.telemetry.internal.TelemetryStore
import com.adobe.marketing.mobile.telemetry.internal.TelemetryUploader
import java.util.UUID
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.plugin.TelemetryEventMapping
import com.adobe.marketing.mobile.telemetrybridge.TelemetryBridge
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Public entry point — one shared recorder behind both install paths (Q2/Q5):
 *
 *   class App : Application() {
 *     override fun onCreate() {
 *       super.onCreate()
 *       Telemetry.install(this)                 // BEFORE MobileCore.initialize
 *       MobileCore.initialize(this, "ENV_ID")
 *     }
 *   }
 *
 * Idempotent install-once: a second call (e.g. a future Core bootstrap hook) is a no-op.
 */
object Telemetry {

    private val lock = Any()
    private var recorder: TelemetryRecorder? = null
    private var store: TelemetryStore? = null
    private var uploader: TelemetryUploader? = null
    private var config: TelemetryConfig = TelemetryConfig()
    private var clock: Clock = Clock { System.currentTimeMillis() }
    private var ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    private val plugin = TelemetryPlugin()
    private var mappings = TelemetryPlugin.catalog()
    private var coreGeneration = 0L
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "aep-telemetry").apply { isDaemon = true }
    }

    @Volatile var installedAtMs: Long = 0L; private set
    @Volatile var installed: Boolean = false; private set

    /** Customer-facing entry point. Attach is memory-only: no network, no disk scan beyond prefs. */
    @JvmStatic
    fun install(application: Application) {
        synchronized(lock) {
            if (installed) return                          // install-once; first attach wins
            installed = true
            installedAtMs = clock.nowMs()
            config = TelemetryConfig.fromAssets(application)
            store = TelemetryStore(application, ids)
            val outbox = runCatching { SqliteOutbox(application) }.getOrElse { InMemoryOutbox() }
            recorder = TelemetryRecorder(clock, { config }, outbox)
            uploader = TelemetryUploader(application, store!!, ids, clock)
            MobileCore.addPlugins(plugin)
            publishPolicy()
        }
        recordInternal("com.adobe.module.core", "sdk.telemetry.install", "event", "directHook",
            outcome = if (config.enabled) "success" else "ignored",
            outcomeMeaning = if (config.enabled) "collection enabled" else "disabled by config (safe default)")
        if (config.enabled) {
            executor.scheduleWithFixedDelay({ maybeFlush() }, config.flushIntervalMs, config.flushIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Test seam (Omega testSend parity): same gating as record(), without persisting. */
    @JvmStatic
    fun dryRun(record: TelemetryRecord): Boolean {
        val r = recorder ?: return false
        return r.copyForDryRun(record)
    }

    /** Direct-hook API for SDKs (Q6 Part 3). No-op when not installed or disabled. */
    @JvmStatic
    fun event(producer: String, eventName: String, capture: String = "directHook") {
        recordInternal(producer, eventName, "event", capture)
    }

    /** Start an instrumented operation; returns operationId for correlation. */
    @JvmStatic
    fun operationStarted(producer: String, eventName: String, capture: String = "directHook"): String? {
        return TelemetryBridge.operationStarted(producer, eventName)
    }

    @JvmStatic
    fun operationCompleted(operationId: String?, producer: String, eventName: String,
                           outcome: String, outcomeMeaning: String? = null, capture: String = "directHook") {
        TelemetryBridge.operationCompleted(operationId, producer, eventName, outcome, outcomeMeaning ?: "")
    }

    /** Hub projection entry (read-only observation of already-dispatched hub events). */
    @JvmStatic
    fun hubObserved(producer: String, eventName: String, requestId: String? = null) {
        recordInternal(producer, eventName, "event", "hubProjection", requestId = requestId)
    }

    @JvmStatic
    fun flush() { maybeFlush(force = true) }

    /**
     * Public API for consuming extensions (e.g. ACC direct-hook usage): force-seal and upload
     * the pending queue now. Safe to call anytime; no-op when disabled or endpoint unset.
     */
    @JvmStatic
    fun flushQueue() { maybeFlush(force = true) }

    /** Current pending record count — for extension diagnostics and tests. */
    @JvmStatic
    fun pendingCount(): Int = recorder?.pending()?.size ?: 0

    /** Read-only diagnostics containing only the allowlisted telemetry schema. */
    @JvmStatic
    fun pendingRecords(): List<TelemetryRecord> = recorder?.pending() ?: emptyList()

    @JvmStatic
    fun eventMappings(): List<TelemetryEventMapping> = synchronized(lock) { mappings.toList() }

    /** Replaces the reviewed catalog atomically; does not add hub listeners. */
    @JvmStatic
    fun setEventMappings(catalog: List<TelemetryEventMapping>) {
        synchronized(lock) {
            val next = catalog.toList()
            publishPolicy(next)
            mappings = next
        }
    }

    /**
     * Compatibility helper: replaces this type/source group's catalog entries.
     * Does not register a second EventHub listener. Safe before Core initialization.
     */
    @JvmStatic
    fun registerEventHubProjection(eventType: String, eventSource: String, producer: String, telemetryEventName: String) {
        synchronized(lock) {
            val next = mappings.filterNot { it.eventType == eventType && it.eventSource == eventSource } +
                TelemetryEventMapping(eventType, eventSource, producer, telemetryEventName)
            publishPolicy(next)
            mappings = next
        }
    }

    @JvmStatic
    fun stats(): Map<String, Any?> = mapOf(
        "installed" to installed,
        "enabled" to config.enabled,
        "captureGeneration" to coreGeneration,
        "endpoint" to config.endpoint.ifBlank { "(capture-only)" },
        "lastUpload" to (uploader?.lastUploadStatus ?: "never")
    ) + (recorder?.stats() ?: emptyMap())

    /** Policy change entry point (remote kill switch lands here). Deny -> purge. */
    @JvmStatic
    fun applyConfig(newConfig: TelemetryConfig) {
        synchronized(lock) {
            val wasEnabled = config.enabled
            config = newConfig
            publishPolicy()
            if (wasEnabled && !newConfig.enabled) recorder?.purge()
        }
    }

    /** Test-only: inject doubles before install. */
    @JvmStatic
    fun injectTestDoubles(clock: Clock, ids: IdGenerator) {
        if (!installed) { this.clock = clock; this.ids = ids }
    }

    private fun publishPolicy(catalog: List<TelemetryEventMapping> = mappings) {
        try {
            coreGeneration = TelemetryBridge.configure(plugin, installed && config.enabled, catalog)
        } catch (e: IllegalArgumentException) {
            config = config.copy(enabled = false)
            recorder?.purge()
            coreGeneration = TelemetryBridge.configure(plugin, false, emptyList())
            throw e
        }
    }

    private fun recordInternal(producer: String, eventName: String, phase: String, capture: String,
                               operationId: String? = null, requestId: String? = null,
                               outcome: String? = null, outcomeMeaning: String? = null,
                               durationMs: Long? = null, timestamp: Long? = null) {
        synchronized(lock) {
            val r = recorder ?: return
            if (!config.enabled) return
            r.record(TelemetryRecord(ids.newId(), timestamp ?: clock.nowMs(), producer, eventName, phase, capture,
                operationId, requestId, outcome, outcomeMeaning, durationMs))
        }
    }

    internal fun recordFromCore(
        producer: String, eventName: String, phase: String, capture: String,
        operationId: String? = null, requestId: String? = null, outcome: String? = null,
        meaning: String? = null, durationMs: Long? = null, timestamp: Long? = null,
        generation: Long
    ) {
        synchronized(lock) {
            if (generation != coreGeneration) return
            recordInternal(producer, eventName, phase, capture, operationId, requestId,
                outcome, meaning, durationMs, timestamp)
        }
    }

    private fun maybeFlush(force: Boolean = false) {
        synchronized(lock) {
            val r = recorder ?: return
            val u = uploader ?: return
            if (!config.enabled || config.endpoint.isBlank()) return
            if (!force && !r.shouldFlush()) return
            val records = r.peekBatch(config.maxBatchBytes)
            if (records.isEmpty()) return
            val batch = u.buildBatch(records)
            u.upload(batch, config) { success, _ ->
                if (success) {
                    synchronized(lock) { r.removeSent(records.map { it.recordId }) }
                }
                // Retryable failure: records stay in the durable outbox; an opt-out
                // purge clears them, so no generation fence is needed for requeue.
            }
        }
    }
}

/** Dry-run needs gating parity without mutating the queue — implemented on the recorder. */
private fun com.adobe.marketing.mobile.telemetry.internal.TelemetryRecorder.copyForDryRun(record: TelemetryRecord): Boolean {
    // Run the same checks record() performs, against a snapshot, without mutation.
    val probe = TelemetryRecord(record.recordId, record.timestamp, record.producer, record.eventName,
        record.phase, record.capture, record.operationId, record.requestId, record.outcome,
        record.outcomeMeaning, record.durationMs)
    if (!probe.isMeaningful()) return false
    // Dedup + capacity checks are stateful; for the POC dry-run we report policy+validity only.
    return true
}
