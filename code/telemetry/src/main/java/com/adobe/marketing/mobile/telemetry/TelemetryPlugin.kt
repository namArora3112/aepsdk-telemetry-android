package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.plugin.TelemetryEventMapping
import com.adobe.marketing.mobile.plugin.IApiTelemetryPlugin
import com.adobe.marketing.mobile.plugin.TelemetryApiRecord

/**
 * Typed optional Core capability. Only reviewed names and correlation metadata cross the bridge.
 * Installation and policy publication are owned by Telemetry.install/applyConfig.
 */
class TelemetryPlugin : IApiTelemetryPlugin {
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

    internal companion object {
        fun catalog(): List<TelemetryEventMapping> = listOf(
            TelemetryEventMapping(EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT,
                "com.adobe.module.core", "sdk.core.trackAction", eventName = "Analytics Track", requiredKey = "action"),
            TelemetryEventMapping(EventType.GENERIC_TRACK, EventSource.REQUEST_CONTENT,
                "com.adobe.module.core", "sdk.core.trackState", eventName = "Analytics Track", requiredKey = "state"),
            TelemetryEventMapping(EventType.GENERIC_LIFECYCLE, EventSource.REQUEST_CONTENT,
                "com.adobe.module.core", "sdk.lifecycle.resume", eventName = "Lifecycle Resume"),
            TelemetryEventMapping(EventType.EDGE, EventSource.REQUEST_CONTENT,
                "com.adobe.edge", "sdk.edge.sendEvent", eventName = "AEP Request Event"),
            TelemetryEventMapping(EventType.CAMPAIGN, EventSource.REQUEST_CONTENT,
                "com.adobe.campaignclassic", "campaign.registerDevice",
                eventName = "CampaignClassic Register Device"),
            TelemetryEventMapping(EventType.CAMPAIGN, EventSource.REQUEST_CONTENT,
                "com.adobe.campaignclassic", "campaign.pushTracking.received",
                eventName = "CampaignClassic Track Notification Receive"),
            TelemetryEventMapping(EventType.CAMPAIGN, EventSource.REQUEST_CONTENT,
                "com.adobe.campaignclassic", "campaign.pushTracking.clicked",
                eventName = "CampaignClassic Track Notification Click")
        )
    }
}
