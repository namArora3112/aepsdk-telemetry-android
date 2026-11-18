package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.plugin.TelemetryEventMapping
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Loads reviewed observation selectors only; no event payload or arbitrary predicates are retained. */
internal object EventApiCatalog {
    const val ASSET_NAME = "aep-telemetry/event-api-catalog.json"
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 64
    private const val MAX_NAME_LENGTH = 128
    private val constantName = Regex("[A-Za-z0-9_.:-]+")
    private val rootFields = setOf("schemaVersion", "catalogVersion", "mappings")
    private val requiredFields = setOf("eventType", "eventSource", "producer", "telemetryEventName")
    private val entryFields = requiredFields + setOf(
        "eventName", "xdmEventType", "requiredKey", "requiredBooleanKey", "forbiddenKey"
    )
    private val trackingKeys = setOf("action", "state")
    private val booleanKeys = setOf("refreshmessages", "getpropositions", "updatepropositions")

    data class Catalog(val version: Int, val mappings: List<TelemetryEventMapping>)

    fun load(context: Context): Catalog {
        try {
            val bytes = context.assets.open(ASSET_NAME).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                // Read at most one byte past the budget; never buffer an unbounded asset.
                while (output.size() <= MAX_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                require(output.size() <= MAX_BYTES) { "Catalog exceeds byte budget" }
                output.toByteArray()
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return parse(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: Exception) {
            throw IllegalArgumentException("Unable to load event catalog")
        }
    }

    fun parse(text: String): Catalog {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Catalog exceeds byte budget"
        }
        try {
            val tokener = JSONTokener(text)
            val root = tokener.nextValue() as? JSONObject
                ?: throw IllegalArgumentException("Catalog must be an object")
            require(tokener.nextClean() == '\u0000') { "Unexpected trailing catalog content" }
            require(keys(root) == rootFields) { "Invalid catalog fields" }
            require(root.get("schemaVersion") is Int && root.get("schemaVersion") == 1) {
                "Unsupported catalog schema"
            }
            val version = root.get("catalogVersion")
            require(version is Int && version > 0) { "Invalid catalog version" }
            val entries = root.get("mappings")
            require(entries is JSONArray && entries.length() <= MAX_ENTRIES) { "Invalid mappings" }
            val signatures = mutableSetOf<List<String?>>()
            val labels = mutableSetOf<Pair<String, String>>()
            val mappings = (0 until entries.length()).map { index ->
                val entry = entries.get(index)
                require(entry is JSONObject) { "Mapping must be an object" }
                val fields = keys(entry)
                require(fields.containsAll(requiredFields) && entryFields.containsAll(fields)) {
                    "Invalid mapping fields"
                }
                val type = name(entry, "eventType")!!
                val source = name(entry, "eventSource")!!
                require(!type.equals(EventType.WILDCARD, true) &&
                    !source.equals(EventSource.WILDCARD, true)) { "Wildcard selector" }
                val producer = name(entry, "producer")!!
                val label = name(entry, "telemetryEventName")!!
                val eventName = name(entry, "eventName", optional = true, spaces = true)
                val xdmType = name(entry, "xdmEventType", optional = true, xdm = true)
                val requiredKey = name(entry, "requiredKey", optional = true)
                val booleanKey = name(entry, "requiredBooleanKey", optional = true)
                val forbiddenKey = name(entry, "forbiddenKey", optional = true)
                require(requiredKey == null || requiredKey in trackingKeys) { "Invalid required key" }
                require(forbiddenKey == null || forbiddenKey in trackingKeys) { "Invalid forbidden key" }
                require(booleanKey == null || booleanKey in booleanKeys) { "Invalid boolean key" }
                require(requiredKey == null || requiredKey != forbiddenKey) { "Contradictory keys" }
                require(signatures.add(listOf(
                    type.lowercase(Locale.ROOT), source.lowercase(Locale.ROOT), eventName,
                    xdmType, requiredKey, booleanKey, forbiddenKey
                ))) { "Duplicate selector" }
                require(labels.add(producer to label)) { "Duplicate telemetry label" }
                TelemetryEventMapping(
                    eventType = type, eventSource = source, producer = producer,
                    telemetryEventName = label, eventName = eventName, xdmEventType = xdmType,
                    requiredKey = requiredKey, requiredBooleanKey = booleanKey,
                    forbiddenKey = forbiddenKey
                )
            }
            return Catalog(version, Collections.unmodifiableList(ArrayList(mappings)))
        } catch (_: Exception) {
            // Do not retain parser exceptions, which may contain the input text.
            throw IllegalArgumentException("Invalid event catalog")
        }
    }

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    private fun name(
        json: JSONObject,
        key: String,
        optional: Boolean = false,
        spaces: Boolean = false,
        xdm: Boolean = false
    ): String? {
        if (optional && !json.has(key)) return null
        val value = json.get(key)
        require(value is String && value.isNotBlank() && value.length <= MAX_NAME_LENGTH) {
            "Invalid catalog name"
        }
        val exact = if (xdm && value.endsWith(".*")) value.dropLast(2) else value
        require(if (spaces) {
            value == value.trim() && value.none { it.isISOControl() || it == '*' }
        } else {
            constantName.matches(exact)
        }) { "Invalid catalog name" }
        return value
    }
}
