package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.plugin.TelemetryHubEvent
import com.adobe.marketing.mobile.telemetry.internal.EventNameCatalog
import com.adobe.marketing.mobile.telemetry.internal.OptimizedPayload
import com.adobe.marketing.mobile.telemetry.internal.TelemetryRecorder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EventNameMapperTest {
    private val asset = File("src/main/assets/aep-telemetry/event-name-catalog.json").readText()
    private val catalog = EventNameCatalog.parse(asset)
    private val context = """{"metadata":{"uuid":"install-1","platform":"android","osVersion":"17","application":{"package":"sample","version":"before"},"device":{"model":"emulator"}},"sdkVersions":[{"name":"com.adobe.messaging","version":"before"}],"orgId":"TEST@AdobeOrg"}"""
    private fun hub(name: String = "Refresh in-app messages", source: String = "com.adobe.eventSource.requestContent", id: String = "event-1") =
        TelemetryHubEvent(name, "com.adobe.eventType.messaging", source, id, "parent-1", "response-1", 123456)
    private fun record(event: TelemetryHubEvent): TelemetryRecord {
        val mapped = catalog.map(event)
        return TelemetryRecord(event.eventId, event.timestamp, mapped.extension, mapped.apiName ?: "unmapped",
            "event", "hubProjection", requestId = event.eventId, recordKind = "catalog_mapped_event",
            optimizedContext = context, hubEvent = event, apiEventType = mapped.category,
            mappingStatus = mapped.status, apiCandidates = mapped.candidates)
    }
    private fun tenant(record: TelemetryRecord) =
        OptimizedPayload.usage(record).getJSONArray("events").getJSONObject(0).getJSONObject("xdm").getJSONObject("_cjmstage")
    private fun entry(record: TelemetryRecord) = tenant(record).getJSONObject("telemetry").getJSONArray("events").getJSONObject(0)

    @Test fun nameChoosesOperationButDocumentedTypeAndSourceMustMatch() {
        val first = catalog.map(hub())
        val otherType = catalog.map(hub().copy(type = "different.type"))
        assertEquals("refreshInAppMessages", first.apiName)
        assertEquals("API_CALL", first.category)
        assertEquals("WILD_EVENT", otherType.category)
        assertEquals("unknown_type", otherType.status)
        assertNull(otherType.apiName)
        assertEquals(first, catalog.map(hub().copy(type = "com.adobe.eventtype.messaging",
            source = "COM.ADOBE.EVENTSOURCE.REQUESTCONTENT")))
    }

    @Test fun actualMessagingResponseNameAndSourceProduceApiResponseNotSuccessOrFailure() {
        val event = hub("Message propositions response", "com.adobe.eventSource.responseContent")
        val result = entry(record(event))
        assertEquals("getPropositionsForSurfaces", result.getString("apiName"))
        assertEquals("API_RESPONSE", result.getString("apiEventType"))
        assertEquals("response-1", result.getString("responseId"))
        assertFalse(result.has("outcome"))
        assertFalse(result.has("errorCode"))
        assertEquals("WILD_EVENT", catalog.map(hub(source = "com.adobe.eventSource.errorResponseContent")).category)
    }

    @Test fun unknownNamesAndUnsupportedSourcesAreWildWithoutInventedApiIdentity() {
        val unknown = entry(record(hub("Not in the catalog")))
        assertEquals("WILD_EVENT", unknown.getString("apiEventType"))
        assertEquals("unknown_name", unknown.getString("mappingStatus"))
        assertFalse(unknown.has("apiName"))
        val source = catalog.map(hub(source = "com.adobe.eventSource.notification"))
        assertEquals("WILD_EVENT", source.category)
        assertEquals("unknown_source", source.status)
        assertNull(source.apiName)
    }

    @Test fun sharedNamesRemainAmbiguousEvenWhenSourceLooksLikeARequest() {
        val result = entry(record(hub("Analytics Track").copy(type = "com.adobe.eventType.generic.track")))
        assertEquals("WILD_EVENT", result.getString("apiEventType"))
        assertEquals("ambiguous_name", result.getString("mappingStatus"))
        assertFalse(result.has("apiName"))
        assertEquals(listOf("trackAction", "trackState"),
            result.getJSONArray("apiCandidates").let { array -> (0 until array.length()).map { array.getString(it) } })
    }

    @Test fun persistenceAndWireKeepOriginalLinksTimestampAndFullCapturedContext() {
        val event = hub()
        val restored = TelemetryRecord.fromJson(record(event).toJson())
        assertEquals(event, restored.hubEvent)
        val data = tenant(restored)
        val wire = entry(restored)
        assertEquals("event-1", wire.getString("eventId"))
        assertEquals("parent-1", wire.getString("parentId"))
        assertEquals("response-1", wire.getString("responseId"))
        assertEquals(event.timestamp, wire.getLong("timestamp"))
        assertEquals("eventHubCatalog", wire.getString("classificationOrigin"))
        assertEquals("before", data.getJSONObject("metadata").getJSONObject("application").getString("version"))
        assertEquals("before", data.getJSONArray("sdkVersions").getJSONObject(0).getString("version"))
        assertEquals("TEST@AdobeOrg", data.getString("orgId"))
        assertFalse(wire.has("eventData"))
        assertFalse(wire.has("operationId"))
        assertEquals(0, OptimizedPayload.usage(restored).getJSONObject("xdm").getJSONObject("identityMap").length())
    }

    @Test fun absentLinksRemainNullInsteadOfInventingAParentOrResponse() {
        val original = hub().copy(parentId = null, responseId = null)
        val restored = TelemetryRecord.fromJson(record(original).toJson())
        assertNull(restored.hubEvent!!.parentId)
        assertNull(restored.hubEvent!!.responseId)
        assertTrue(entry(restored).isNull("parentId"))
        assertTrue(entry(restored).isNull("responseId"))
    }

    @Test fun distinctEventsWithTheSameNameAreNotCoalescedByTheRecorder() {
        val config = TelemetryConfig.optimizedDefaults().copy(enabled = true, eventNameMapperEnabled = true)
        val recorder = TelemetryRecorder(Clock { 123456 }, { config })
        assertTrue(recorder.record(record(hub(id = "first"))))
        assertTrue(recorder.record(record(hub(id = "second"))))
        assertFalse(recorder.record(record(hub(id = "first"))))
        assertEquals(2, recorder.pending().size)
    }

    @Test fun malformedOrDuplicateCatalogRulesFailClosed() {
        val duplicate = JSONObject(asset).apply {
            getJSONArray("mappings").put(getJSONArray("mappings").getJSONObject(0))
        }
        assertThrows(IllegalArgumentException::class.java) { EventNameCatalog.parse(duplicate.toString()) }
        val payloadPredicate = JSONObject(asset).apply {
            getJSONArray("mappings").getJSONObject(0).put("requiredBooleanKey", "refreshmessages")
        }
        assertThrows(IllegalArgumentException::class.java) { EventNameCatalog.parse(payloadPredicate.toString()) }
        val invalidSource = JSONObject(asset).apply {
            getJSONArray("mappings").getJSONObject(0).put("category", "API_FAIL")
        }
        assertThrows(IllegalArgumentException::class.java) { EventNameCatalog.parse(invalidSource.toString()) }
    }

    @Test fun mapperIsExplicitlyOptInAndRequiresItsTransport() {
        assertFalse(TelemetryConfig().eventNameMapperEnabled)
        assertThrows(IllegalArgumentException::class.java) { TelemetryConfig(eventNameMapperEnabled = true) }
        assertTrue(TelemetryConfig.fromJson(JSONObject("""{"optimizedPayload":true,"eventNameMapperEnabled":true}""")).eventNameMapperEnabled)
    }

    @Test fun documentedRootsAndResponsesAcrossExtensionsAreRecognized() {
        val cases = listOf(
            listOf("CampaignClassic Register Device", "campaign", "requestContent", "registerDevice", "API_CALL"),
            listOf("Device Registration Status", "campaign", "responseContent", "registerDevice", "API_RESPONSE"),
            listOf("Optimize Update Propositions Request", "optimize", "requestContent", "updatePropositions", "API_CALL"),
            listOf("Optimize Update Propositions Complete", "optimize", "contentComplete", "updatePropositions", "API_RESPONSE"),
            listOf("Edge Identity Request ECID", "edgeIdentity", "requestIdentity", "getExperienceCloudId", "API_CALL"),
            listOf("Consent Update Request", "edgeConsent", "updateConsent", "update", "API_CALL"),
            listOf("UserProfileUpdate", "userProfile", "requestProfile", "updateUserAttributes", "API_CALL"),
            listOf("requestgetnearbyplaces", "places", "requestContent", "getNearbyPointsOfInterest", "API_CALL"),
            listOf("Flags Get Feature Request", "flags", "requestContent", "getFeature", "API_CALL"),
            listOf("TargetPrefetchResponse", "target", "responseContent", "prefetchContent", "API_RESPONSE"),
            listOf("GetQueueSize", "analytics", "requestContent", "getQueueSize", "API_CALL")
        )
        cases.forEach { (name, type, source, api, category) ->
            val mapped = catalog.map(hub(name, "com.adobe.eventSource.$source")
                .copy(type = "com.adobe.eventtype.${type.lowercase()}"))
            assertEquals(name, api, mapped.apiName)
            assertEquals(name, category, mapped.category)
        }
    }

    @Test fun sharedPrivacyIdentityAndModelMethodsAreNeverGuessed() {
        val privacy = catalog.map(hub("Configuration Update").copy(type = "com.adobe.eventType.configuration"))
        assertEquals(setOf("setPrivacyStatus", "updateConfiguration"), privacy.candidates.toSet())
        val identity = catalog.map(hub("IdentityRequestIdentity", "com.adobe.eventSource.requestIdentity")
            .copy(type = "com.adobe.eventType.identity"))
        assertEquals(6, identity.candidates.size)
        val media = catalog.map(hub("Edge Media TrackMedia", "com.adobe.eventSource.trackMedia")
            .copy(type = "com.adobe.eventType.edgeMedia"))
        assertTrue(media.candidates.containsAll(listOf("trackSessionStart", "trackPlay", "trackPause", "trackEvent")))
        listOf(privacy, identity, media).forEach {
            assertNull(it.apiName)
            assertEquals("WILD_EVENT", it.category)
            assertEquals("ambiguous_name", it.status)
        }
    }

    @Test fun internalRequestsNotificationsAndMetadataAreNotApiCallsOrCompletions() {
        val cases = listOf(
            Triple("Retrieve message definitions", "edge", "requestContent"),
            Triple("Edge Bridge Request", "edge", "requestContent"),
            Triple("Edge Optimize Personalization Request", "edge", "requestContent"),
            Triple("Edge Media - media.play", "edge", "requestContent"),
            Triple("AEP Response Complete", "edge", "contentComplete"),
            Triple("Message propositions notification", "messaging", "notification"),
            Triple("Shared state change", "hub", "sharedState")
        )
        cases.forEach { (name, type, source) ->
            val result = catalog.map(hub(name, "com.adobe.eventSource.$source")
                .copy(type = "com.adobe.eventType.$type"))
            assertEquals(name, "internal_event", result.status)
            assertEquals(name, "WILD_EVENT", result.category)
            assertNull(result.apiName)
            assertTrue(result.candidates.isEmpty())
        }
    }

    @Test fun customMediaSourcesAreSupportedWithoutTreatingPayloadDiscriminatorsAsIdentity() {
        val result = catalog.map(hub("Media::CreateTrackerRequest", "com.adobe.eventsource.media.requesttracker")
            .copy(type = "com.adobe.eventType.media"))
        assertEquals("createTracker", result.apiName)
        assertEquals("API_CALL", result.category)
    }

    @Test fun sharedResponsesAndDelegatingRequestsRetainCandidatesInsteadOfOneApi() {
        val cases = listOf(
            listOf("Flags Response", "flags", "responseContent", "getFeature", "isFeatureEnabled"),
            listOf("UserProfile Response Event", "userProfile", "responseProfile",
                "updateUserAttributes", "removeUserAttributes", "getUserAttributes"),
            listOf("Update propositions", "messaging", "requestContent",
                "updatePropositionsForSurfaces", "handleNotificationResponse"),
            listOf("AEP Request Event", "edge", "requestContent", "sendEvent", "prefetchOffers")
        )
        cases.forEach { values ->
            val result = catalog.map(hub(values[0], "com.adobe.eventSource.${values[2]}")
                .copy(type = "com.adobe.eventType.${values[1]}"))
            assertEquals(values[0], "ambiguous_name", result.status)
            assertEquals("WILD_EVENT", result.category)
            assertNull(result.apiName)
            assertEquals(values.drop(3).toSet(), result.candidates.toSet())
        }
    }

    @Test fun schemaOneRetainsItsOriginalNameOnlyAndUnknownSourceSemantics() {
        val legacy = EventNameCatalog.parse("""{
          "schemaVersion":1,"catalogVersion":1,
          "sourceCategories":{"com.adobe.eventSource.requestContent":"API_CALL"},
          "mappings":[{"eventName":"Refresh in-app messages","extension":"com.adobe.messaging","apiName":"refreshInAppMessages"}]
        }""")
        assertEquals("API_CALL", legacy.map(hub().copy(type = "different.type")).category)
        val unsupported = legacy.map(hub(source = "custom:notification"))
        assertEquals("unknown_source", unsupported.status)
        assertEquals("refreshInAppMessages", unsupported.apiName)
    }

    @Test fun strictSchemaTwoRejectsCoercionConflictsUnknownFieldsAndOversizedInput() {
        val original = """{"schemaVersion":2,"catalogVersion":2,"mappings":[{
          "eventName":"Example","eventType":"com.adobe.eventType.messaging",
          "eventSource":"custom:request","extension":"brandconcierge",
          "category":"API_CALL","apiName":"sendDataHandoff"}]}"""
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("schemaVersion", "2") },
            { it.put("catalogVersion", true) },
            { it.put("sourceCategories", JSONObject()) },
            { it.getJSONArray("mappings").getJSONObject(0).put("apiName", 123) },
            { it.getJSONArray("mappings").getJSONObject(0).put("eventSource", "*") },
            { it.getJSONArray("mappings").getJSONObject(0).put("category", "WILD_EVENT") },
            { it.getJSONArray("mappings").getJSONObject(0).remove("apiName") },
            { root ->
                val duplicate = JSONObject(root.getJSONArray("mappings").getJSONObject(0).toString())
                    .put("eventType", "COM.ADOBE.EVENTTYPE.MESSAGING")
                    .put("eventSource", "CUSTOM:REQUEST")
                root.getJSONArray("mappings").put(duplicate)
            }
        )
        assertEquals(2, EventNameCatalog.parse(original).version)
        mutations.forEach { mutate ->
            assertThrows(Exception::class.java) {
                EventNameCatalog.parse(JSONObject(original).apply(mutate).toString())
            }
            assertThrows(IllegalArgumentException::class.java) {
                EventNameCatalog.parse(original.replace("\"schemaVersion\":2", "\"schemaVersion\":2.0"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { EventNameCatalog.parse(" ".repeat(512 * 1024 + 1)) }
    }

    @Test fun allBundledSignaturesAreReachableAndRetainTheirReviewedRole() {
        val rows = JSONObject(asset).getJSONArray("mappings")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val mapped = catalog.map(hub(row.getString("eventName"), row.getString("eventSource"))
                .copy(type = row.getString("eventType")))
            assertEquals(row.getString("eventName"), row.getString("category"), mapped.category)
            assertEquals(row.optString("apiName").ifEmpty { null }, mapped.apiName)
            assertEquals(row.getString("extension"), mapped.extension)
        }
    }
}
