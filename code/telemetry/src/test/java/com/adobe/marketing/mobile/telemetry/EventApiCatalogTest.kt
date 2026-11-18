package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.telemetry.internal.EventApiCatalog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EventApiCatalogTest {
    private fun entry() = JSONObject().apply {
        put("eventType", EventType.GENERIC_TRACK)
        put("eventSource", EventSource.REQUEST_CONTENT)
        put("eventName", "Analytics Track")
        put("producer", "com.adobe.module.core")
        put("telemetryEventName", "trackAction.requestObserved")
    }

    private fun document(vararg entries: JSONObject) = JSONObject().apply {
        put("schemaVersion", 1)
        put("catalogVersion", 1)
        put("mappings", JSONArray(entries.toList()))
    }

    private fun rejected(text: String) {
        try {
            EventApiCatalog.parse(text)
            fail("Expected catalog rejection")
        } catch (_: IllegalArgumentException) {
            // Catalog failures must not leak JSON/IO exception types into installation.
        }
    }

    @Test
    fun bundledAssetParsesIntoReviewedObservationSelectors() {
        val catalog = EventApiCatalog.parse(
            File("src/main/assets/${EventApiCatalog.ASSET_NAME}").readText(Charsets.UTF_8)
        )
        assertEquals(1, catalog.version)
        assertEquals(12, catalog.mappings.size)
        val byLabel = catalog.mappings.associateBy { it.telemetryEventName }
        val messaging = mapOf(
            "refreshInAppMessages" to ("Refresh in-app messages" to "refreshmessages"),
            "updatePropositionsForSurfaces" to ("Update propositions" to "updatepropositions"),
            "getPropositionsForSurfaces" to ("Get propositions" to "getpropositions")
        )
        messaging.forEach { (api, selector) ->
            val mapping = byLabel.getValue("$api.requestObserved")
            assertEquals(EventType.MESSAGING, mapping.eventType)
            assertEquals(EventSource.REQUEST_CONTENT, mapping.eventSource)
            assertEquals("com.adobe.messaging", mapping.producer)
            assertEquals(selector.first, mapping.eventName)
            assertEquals(selector.second, mapping.requiredBooleanKey)
        }
        listOf("trackAction" to ("action" to "state"), "trackState" to ("state" to "action"))
            .forEach { (api, keys) ->
                val mapping = byLabel.getValue("$api.requestObserved")
                assertEquals(EventType.GENERIC_TRACK, mapping.eventType)
                assertEquals(EventSource.REQUEST_CONTENT, mapping.eventSource)
                assertEquals("Analytics Track", mapping.eventName)
                assertEquals(keys.first, mapping.requiredKey)
                assertEquals(keys.second, mapping.forbiddenKey)
            }
        mapOf(
            "lifecycleStart" to (EventType.GENERIC_LIFECYCLE to "Lifecycle Resume"),
            "lifecyclePause" to (EventType.GENERIC_LIFECYCLE to "Lifecycle Pause"),
            "updateConfiguration" to (EventType.CONFIGURATION to "Configuration Update"),
            "configureWithAppID" to (EventType.CONFIGURATION to "Configure with App ID")
        ).forEach { (api, selector) ->
            val mapping = byLabel.getValue("$api.requestObserved")
            assertEquals(selector.first, mapping.eventType)
            assertEquals(selector.second, mapping.eventName)
            assertEquals(EventSource.REQUEST_CONTENT, mapping.eventSource)
            assertEquals("com.adobe.module.core", mapping.producer)
        }
        val identity = catalog.mappings.filter { it.eventType == EventType.IDENTITY }.single()
        assertEquals(EventSource.REQUEST_IDENTITY, identity.eventSource)
        assertEquals("IdentityRequestIdentity", identity.eventName)
        assertEquals("identityRead.requestObserved", identity.telemetryEventName)
        assertEquals("com.adobe.module.identity", identity.producer)
        assertNull(identity.requiredKey)
        assertNull(identity.requiredBooleanKey)
        assertNull(identity.forbiddenKey)
        assertNull(identity.xdmEventType) // No fabricated distinction between Identity getters.
        val edgeRequest = byLabel.getValue("sendEvent.requestObserved")
        assertEquals(EventType.EDGE, edgeRequest.eventType)
        assertEquals(EventSource.REQUEST_CONTENT, edgeRequest.eventSource)
        assertEquals("AEP Request Event", edgeRequest.eventName)
        assertEquals("com.adobe.edge", edgeRequest.producer)
        val edgeError = byLabel.getValue("error.responseObserved")
        assertEquals(EventType.EDGE, edgeError.eventType)
        assertEquals(EventSource.ERROR_RESPONSE_CONTENT, edgeError.eventSource)
        assertEquals("AEP Error Response", edgeError.eventName)
        assertEquals("com.adobe.edge", edgeError.producer)
        assertTrue(catalog.mappings.all {
            it.retireWhenApisCovered.isEmpty() &&
                (it.telemetryEventName.endsWith(".requestObserved") ||
                    it.telemetryEventName.endsWith(".responseObserved"))
        })
    }

    @Test
    fun rejectsMalformedDocumentsAndTrailingContent() {
        listOf("", "{", "null", "[]", "true", "{\"schemaVersion\":").forEach(::rejected)
        rejected(document(entry()).toString() + "{}")
    }

    @Test
    fun requiresExactRootFieldsAndIntegerVersions() {
        listOf("schemaVersion", "catalogVersion", "mappings").forEach { key ->
            rejected(document(entry()).apply { remove(key) }.toString())
        }
        rejected(document(entry()).put("extra", true).toString())
        listOf<Any>(0, -1, 2, "1", true, JSONObject.NULL, 1.5).forEach {
            rejected(document(entry()).put("schemaVersion", it).toString())
        }
        listOf<Any>(0, -1, "1", true, JSONObject.NULL, 1.5, 2147483648L).forEach {
            rejected(document(entry()).put("catalogVersion", it).toString())
        }
        rejected(document(entry()).toString().replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"))
        rejected(document(entry()).toString().replace("\"catalogVersion\":1", "\"catalogVersion\":1.0"))
        assertEquals(42, EventApiCatalog.parse(document(entry()).put("catalogVersion", 42).toString()).version)
        listOf<Any>("[]", JSONObject(), true, JSONObject.NULL).forEach {
            rejected(document(entry()).put("mappings", it).toString())
        }
        listOf<Any>(true, "entry", JSONArray(), JSONObject.NULL).forEach {
            rejected(document().put("mappings", JSONArray().put(it)).toString())
        }
    }

    @Test
    fun requiresExactEntryFieldsAndStringTypesIncludingOptionalFields() {
        val required = listOf("eventType", "eventSource", "producer", "telemetryEventName")
        required.forEach { key ->
            rejected(document(entry().apply { remove(key) }).toString())
        }
        (required + listOf("eventName", "xdmEventType", "requiredKey", "requiredBooleanKey", "forbiddenKey"))
            .forEach { key ->
                listOf<Any>(true, 1, JSONObject.NULL, JSONObject(), JSONArray()).forEach {
                    rejected(document(entry().put(key, it)).toString())
                }
            }
        listOf("enabled", "retireWhenApisCovered", "legacyFallback", "payload", "typo").forEach {
            rejected(document(entry().put(it, true)).toString())
        }
        val minimal = entry().apply { remove("eventName") }
        assertNull(EventApiCatalog.parse(document(minimal).toString()).mappings.single().eventName)
    }

    @Test
    fun rejectsEmptyOversizedAndWildcardNames() {
        listOf("eventType", "eventSource", "producer", "telemetryEventName", "eventName", "xdmEventType")
            .forEach { key ->
                listOf("", " ", "a".repeat(129), "*", "prefix*suffix").forEach {
                    rejected(document(entry().put(key, it)).toString())
                }
            }
        listOf("eventType", "eventSource", "producer", "telemetryEventName").forEach { key ->
            listOf("contains space", "slash/value", "value\n").forEach {
                rejected(document(entry().put(key, it)).toString())
            }
        }
        rejected(document(entry().put("eventType", EventType.WILDCARD)).toString())
        rejected(document(entry().put("eventSource", EventSource.WILDCARD.uppercase())).toString())
        rejected(document(entry().put("eventName", "Event\nName")).toString())
        assertEquals("a".repeat(128), EventApiCatalog.parse(
            document(entry().put("telemetryEventName", "a".repeat(128))).toString()
        ).mappings.single().telemetryEventName)
    }

    @Test
    fun acceptsOnlyExactOrTrailingDotStarXdmSelectors() {
        listOf("decisioning.propositionDisplay", "decisioning.*").forEach {
            assertEquals(it, EventApiCatalog.parse(
                document(entry().put("xdmEventType", it)).toString()
            ).mappings.single().xdmEventType)
        }
        listOf(".*", "*", "decisioning*", "decisioning.*.display", "decisioning.**").forEach {
            rejected(document(entry().put("xdmEventType", it)).toString())
        }
    }

    @Test
    fun allowsOnlyReviewedKeysAndRejectsContradictoryPresenceSelectors() {
        listOf("requiredKey", "forbiddenKey").forEach { field ->
            listOf("action", "state").forEach {
                val mapping = EventApiCatalog.parse(document(entry().put(field, it)).toString()).mappings.single()
                assertEquals(it, if (field == "requiredKey") mapping.requiredKey else mapping.forbiddenKey)
            }
            listOf("", "Action", "refreshmessages", "xdm", "arbitrary").forEach {
                rejected(document(entry().put(field, it)).toString())
            }
        }
        listOf("refreshmessages", "getpropositions", "updatepropositions").forEach {
            assertEquals(it, EventApiCatalog.parse(
                document(entry().put("requiredBooleanKey", it)).toString()
            ).mappings.single().requiredBooleanKey)
        }
        listOf("", "action", "state", "RefreshMessages", "arbitrary", "true").forEach {
            rejected(document(entry().put("requiredBooleanKey", it)).toString())
        }
        listOf("action", "state").forEach {
            rejected(document(entry().put("requiredKey", it).put("forbiddenKey", it)).toString())
        }
        val mapping = EventApiCatalog.parse(
            document(entry().put("requiredKey", "action").put("forbiddenKey", "state")).toString()
        ).mappings.single()
        assertEquals("action", mapping.requiredKey)
        assertEquals("state", mapping.forbiddenKey)
    }

    @Test
    fun rejectsDuplicateSelectorsEvenWithDifferentLabelsOrTypeSourceCase() {
        val second = entry().put("producer", "another.producer").put("telemetryEventName", "another.label")
        rejected(document(entry(), second).toString())
        second.put("eventType", EventType.GENERIC_TRACK.uppercase())
            .put("eventSource", EventSource.REQUEST_CONTENT.lowercase())
        rejected(document(entry(), second).toString())
    }

    @Test
    fun rejectsDuplicateProducerLabelPairsEvenWithDifferentSelectors() {
        rejected(document(entry(), entry().put("eventName", "Different event")).toString())
        val differentLabel = entry().put("eventName", "Different event").put("telemetryEventName", "another.label")
        assertEquals(2, EventApiCatalog.parse(document(entry(), differentLabel).toString()).mappings.size)
    }

    @Test
    fun boundsEntryCountAndReturnsAnUnmodifiableSnapshot() {
        fun entries(count: Int) = Array(count) {
            entry().put("eventName", "Event $it").put("telemetryEventName", "observed.$it")
        }
        assertTrue(EventApiCatalog.parse(document().toString()).mappings.isEmpty())
        val catalog = EventApiCatalog.parse(document(*entries(64)).toString())
        assertEquals(64, catalog.mappings.size)
        rejected(document(*entries(65)).toString())
        try {
            (catalog.mappings as MutableList<*>).clear()
            fail("Mappings must not be mutable")
        } catch (_: UnsupportedOperationException) {
            assertEquals(64, catalog.mappings.size)
        }
    }

    @Test
    fun boundsUtf8BytesNotOnlyStringLength() {
        val text = document(entry()).toString()
        assertEquals(1, EventApiCatalog.parse(text.padEnd(65536, ' ')).mappings.size)
        rejected(text.padEnd(65537, ' '))
        fun largeDocument(eventName: String): String = document(*Array(64) { index ->
            entry().apply {
                put("eventType", "t".repeat(128))
                put("eventSource", "s".repeat(128))
                put("producer", "p".repeat(128))
                put("telemetryEventName", index.toString().padEnd(128, 'n'))
                put("xdmEventType", "x".repeat(128))
                put("eventName", index.toString().padEnd(128, eventName.single()))
            }
        }).toString()
        assertEquals(64, EventApiCatalog.parse(largeDocument("a")).mappings.size)
        val multibyte = largeDocument("界")
        assertTrue(multibyte.length < 65536)
        assertTrue(multibyte.toByteArray(Charsets.UTF_8).size > 65536)
        rejected(multibyte)
    }
}
