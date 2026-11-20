package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.plugin.IApiTelemetryPlugin
import com.adobe.marketing.mobile.plugin.IEventTelemetryPlugin
import com.adobe.marketing.mobile.plugin.TelemetryApiRecord
import com.adobe.marketing.mobile.plugin.TelemetryHubEvent

/**
 * Typed optional Core capability. Only reviewed names and correlation metadata cross the bridge.
 * Installation and policy publication are owned by Telemetry.install/applyConfig.
 */
class TelemetryPlugin : IApiTelemetryPlugin, IEventTelemetryPlugin {
    override fun eventMapper(event: TelemetryHubEvent, generation: Long) {
        Telemetry.eventMapper(event, generation)
    }

    override fun recordApi(record: TelemetryApiRecord, generation: Long) {
        Telemetry.recordFromCore(
            record.producer, record.apiName, record.phase, "directApi",
            operationId = record.operationId, outcome = record.outcome, meaning = record.meaning,
            durationMs = record.durationMs, generation = generation, api = record
        )
    }

    override fun recordEvent(producer: String, eventName: String, eventId: String, timestamp: Long, generation: Long) {
        Telemetry.recordFromCore(producer, eventName, "event", "hubProjection",
            requestId = eventId, timestamp = timestamp, generation = generation)
    }

    override fun recordOperation(
        operationId: String, producer: String, eventName: String, phase: String,
        outcome: String?, meaning: String?, durationMs: Long?, generation: Long
    ) {
        Telemetry.recordFromCore(producer, eventName, phase, "directHook",
            operationId = operationId, outcome = outcome, meaning = meaning, durationMs = durationMs,
            generation = generation)
    }

    override fun operationStarted(producer: String, eventName: String) =
        Telemetry.operationStarted(producer, eventName)

    override fun operationCompleted(
        operationId: String, producer: String, eventName: String, outcome: String, meaning: String
    ) = Telemetry.operationCompleted(operationId, producer, eventName, outcome, meaning)

    override fun declareApiCapabilities(
        producer: String,
        declarations: List<com.adobe.marketing.mobile.plugin.TelemetryApiCapability>
    ) = Telemetry.declareApiCapabilities(producer, declarations)

    override fun apiStarted(producer: String, apiName: String) =
        Telemetry.apiStarted(producer, apiName)

    override fun apiCompleted(id: String, producer: String, apiName: String, outcome: String) =
        Telemetry.apiCompleted(id, producer, apiName, outcome)

    override fun setCaptureMode(mode: String) = Telemetry.setCaptureMode(mode)
    override fun migrationStatus() = Telemetry.operationStatus()
    override fun expireApiOperations() = Telemetry.expireApiOperations()
}
