package com.adobe.marketing.mobile.telemetry.internal

import com.adobe.marketing.mobile.plugin.TelemetryApiCapability
import com.adobe.marketing.mobile.plugin.TelemetryApiRecord
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Telemetry-owned API observation state; Core contains only the optional handoff bridge. */
internal class TelemetryOperationController(
    private val recordOperation: (
        id: String,
        producer: String,
        name: String,
        phase: String,
        outcome: String?,
        meaning: String?,
        durationMs: Long?
    ) -> Unit,
    private val recordApi: (TelemetryApiRecord) -> Unit,
    internal var clock: () -> Long = System::nanoTime
) {
    private data class Operation(
        val producer: String,
        val name: String,
        val startedNanos: Long,
        val capability: TelemetryApiCapability? = null,
        val mode: String = "hybrid"
    )

    private val lock = Any()
    private val capabilities = AtomicReference<Map<Pair<String, String>, TelemetryApiCapability>>(emptyMap())
    private val captureMode = AtomicReference("hybrid")
    private val operations = mutableMapOf<String, Operation>()
    private val dropped = AtomicLong()
    private val unknown = AtomicLong()
    private val suppressed = AtomicLong()

    fun operationStarted(producer: String, name: String): String? {
        if (producer.isBlank() || name.isBlank()) return null
        synchronized(lock) {
            if (operations.size >= 256) {
                dropped.incrementAndGet()
                return null
            }
            val id = UUID.randomUUID().toString()
            operations[id] = Operation(producer, name, clock())
            recordOperation(id, producer, name, "started", null, null, null)
            return id
        }
    }

    fun operationCompleted(
        id: String,
        producer: String,
        name: String,
        outcome: String,
        meaning: String
    ) {
        val operation = synchronized(lock) {
            val pending = operations[id] ?: return
            if (pending.producer != producer || pending.name != name || pending.capability != null) return
            operations.remove(id) ?: return
        }
        val duration = TimeUnit.NANOSECONDS.toMillis(
            (clock() - operation.startedNanos).coerceAtLeast(0L)
        )
        recordOperation(id, producer, name, "completed", outcome, meaning, duration)
    }

    fun declareApiCapabilities(
        producer: String,
        declarations: List<TelemetryApiCapability>
    ): Boolean {
        if (!validName(producer)) return false
        synchronized(lock) {
            val remaining = capabilities.get().filterKeys { it.first != producer }
            val valid = declarations.size <= 64 &&
                remaining.size + declarations.size <= 128 &&
                declarations.map { it.apiName }.distinct().size == declarations.size &&
                declarations.all {
                    validName(it.apiName) && validName(it.extensionVersion) && it.contractVersion > 0 &&
                        it.observationTtlMs in 1..3_600_000 &&
                        (it.outcomes.isEmpty() || it.outcomeContract?.let(::validName) == true) &&
                        it.outcomes.all { outcome ->
                            outcome in setOf(
                                "success", "failure", "rejected", "timed_out", "cancelled", "ignored"
                            )
                        }
                }
            capabilities.set(
                if (!valid) remaining else remaining + declarations.associate {
                    (producer to it.apiName) to it.copy(
                        outcomes = Collections.unmodifiableSet(it.outcomes.toSet())
                    )
                }
            )
            return valid
        }
    }

    fun apiStarted(producer: String, name: String): String? {
        expireApiOperations()
        val capability = capabilities.get()[producer to name] ?: return null
        synchronized(lock) {
            if (operations.size >= 256) {
                dropped.incrementAndGet()
                return null
            }
            val id = UUID.randomUUID().toString()
            val operation = Operation(producer, name, clock(), capability, captureMode.get())
            if (capability.outcomes.isNotEmpty()) operations[id] = operation
            recordApi(apiRecord(id, operation, "started"))
            return id
        }
    }

    fun apiCompleted(id: String, producer: String, name: String, outcome: String) {
        val record = synchronized(lock) {
            val operation = operations[id] ?: return
            if (operation.producer != producer || operation.name != name ||
                operation.capability?.outcomes?.contains(outcome) != true
            ) return
            operations.remove(id)
            apiRecord(id, operation, "completed", outcome, operation.capability.outcomeContract)
        }
        recordApi(record)
    }

    fun setCaptureMode(mode: String): Boolean {
        val valid = mode == "hybrid" || mode == "direct_only"
        captureMode.set(if (valid) mode else "direct_only")
        return valid
    }

    fun expireApiOperations() {
        val expired = synchronized(lock) {
            operations.filterValues { operation ->
                val capability = operation.capability
                capability != null &&
                    clock() - operation.startedNanos >=
                    TimeUnit.MILLISECONDS.toNanos(capability.observationTtlMs)
            }.onEach { (id, _) -> operations.remove(id) }
        }
        expired.forEach { (id, operation) ->
            unknown.incrementAndGet()
            recordApi(
                apiRecord(id, operation, "completed", "unknown", "completion_not_observed")
            )
        }
    }

    fun migrationStatus(): Map<String, Any> = synchronized(lock) {
        val declared = capabilities.get().values
        mapOf(
            "captureMode" to captureMode.get(),
            "declaredApis" to declared.size,
            "outcomeApis" to declared.count { it.outcomes.isNotEmpty() },
            "pendingApis" to operations.values.count { it.capability != null },
            "droppedHandoffs" to dropped.get(),
            "unknownOutcomes" to unknown.get(),
            "suppressedFallbacks" to suppressed.get()
        )
    }

    private fun apiRecord(
        id: String,
        operation: Operation,
        phase: String,
        outcome: String? = null,
        meaning: String? = null
    ): TelemetryApiRecord {
        val capability = requireNotNull(operation.capability)
        return TelemetryApiRecord(
            id, operation.producer, operation.name, phase, capability.extensionVersion,
            capability.contractVersion, capability.outcomeContract,
            if (capability.outcomes.isEmpty()) "entry_only" else "entry_and_outcome",
            operation.mode, outcome, meaning,
            if (phase == "started") null else TimeUnit.NANOSECONDS.toMillis(
                (clock() - operation.startedNanos).coerceAtLeast(0L)
            )
        )
    }

    private fun validName(value: String): Boolean =
        value.length in 1..160 && value.matches(Regex("[A-Za-z0-9_.-]+"))
}
