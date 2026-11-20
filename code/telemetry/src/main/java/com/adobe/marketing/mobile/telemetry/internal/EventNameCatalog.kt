package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import com.adobe.marketing.mobile.plugin.TelemetryHubEvent
import org.json.JSONObject
import java.util.Locale

internal data class MappedHubEvent(
    val apiName: String?,
    val extension: String,
    val category: String,
    val status: String,
    val candidates: List<String> = emptyList()
)

/** Metadata-only, observational associations. No payload inspection or temporal inference. */
internal class EventNameCatalog private constructor(
    val version: Int,
    private val sources: Map<String, String>?,
    private val legacyEntries: Map<String, Entry>,
    private val entries: Map<String, Map<String, Map<String, Entry>>>
) {
    private data class Entry(val extension: String, val names: List<String>, val category: String)

    fun map(event: TelemetryHubEvent): MappedHubEvent {
        if (sources != null) return mapLegacy(event)
        val named = entries[event.name] ?: return wild("unknown_name")
        val typed = named[event.type.lowercase(Locale.ROOT)] ?: return wild("unknown_type")
        val entry = typed[event.source.lowercase(Locale.ROOT)] ?: return wild("unknown_source")
        if (entry.names.size > 1) {
            return MappedHubEvent(null, entry.extension, "WILD_EVENT", "ambiguous_name", entry.names)
        }
        if (entry.category == "WILD_EVENT") {
            return MappedHubEvent(null, entry.extension, "WILD_EVENT", "internal_event")
        }
        return MappedHubEvent(entry.names.single(), entry.extension, entry.category, "matched")
    }

    private fun mapLegacy(event: TelemetryHubEvent): MappedHubEvent {
        val entry = legacyEntries[event.name] ?: return wild("unknown_name")
        if (entry.names.size != 1) {
            return MappedHubEvent(null, entry.extension, "WILD_EVENT", "ambiguous_name", entry.names)
        }
        val category = sources!![event.source.lowercase(Locale.ROOT)]
        return MappedHubEvent(entry.names.single(), entry.extension, category ?: "WILD_EVENT",
            if (category == null) "unknown_source" else "matched")
    }

    companion object {
        private const val MAX_CHARS = 512 * 1024
        private const val MAX_RULES = 4096
        private const val MAX_CANDIDATES = 128
        private val constant = Regex("[A-Za-z0-9_.:-]{1,160}")
        private val categories = setOf("API_CALL", "API_RESPONSE", "WILD_EVENT")
        private fun wild(status: String) =
            MappedHubEvent(null, "com.adobe.module.eventhub", "WILD_EVENT", status)
        private fun keys(json: JSONObject) = json.keys().asSequence().toSet()
        private fun string(json: JSONObject, key: String): String {
            val value = json.get(key)
            require(value is String)
            return value
        }

        fun load(context: Context): EventNameCatalog =
            context.assets.open("aep-telemetry/event-name-catalog.json").bufferedReader().use {
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= MAX_CHARS)
                    text.append(buffer, 0, count)
                }
                parse(text.toString())
            }

        fun parse(text: String): EventNameCatalog {
            require(text.length <= MAX_CHARS)
            val root = JSONObject(text)
            require(root.get("catalogVersion") is Int && root.getInt("catalogVersion") > 0)
            require(root.get("schemaVersion") is Int)
            return when (root.getInt("schemaVersion")) {
                1 -> parseLegacy(root, text.length)
                2 -> parseSignatures(root)
                else -> throw IllegalArgumentException("Unsupported event-name catalog schema")
            }
        }

        private fun names(row: JSONObject, limit: Int, optional: Boolean): List<String> {
            require(!(row.has("apiName") && row.has("apiNames")))
            val names = when {
                row.has("apiName") -> listOf(string(row, "apiName"))
                row.has("apiNames") -> {
                    val array = row.getJSONArray("apiNames")
                    require(array.length() in 2..limit)
                    (0 until array.length()).map {
                        require(array.get(it) is String)
                        array.getString(it)
                    }
                }
                optional -> emptyList()
                else -> throw IllegalArgumentException("Missing API association")
            }
            require(names.all(constant::matches) && names.distinct().size == names.size)
            return names.toList()
        }

        private fun parseLegacy(root: JSONObject, length: Int): EventNameCatalog {
            require(length <= 65536)
            require(keys(root) == setOf("schemaVersion", "catalogVersion", "sourceCategories", "mappings"))
            val sourceJson = root.getJSONObject("sourceCategories")
            require(sourceJson.length() in 1..32)
            val sources = linkedMapOf<String, String>()
            keys(sourceJson).forEach { source ->
                require(source.length <= 160 && source.startsWith("com.adobe.eventSource."))
                val category = string(sourceJson, source)
                require(category in setOf("API_CALL", "API_RESPONSE"))
                require(sources.put(source.lowercase(Locale.ROOT), category) == null)
            }
            val mappings = root.getJSONArray("mappings")
            require(mappings.length() in 1..64)
            val entries = linkedMapOf<String, Entry>()
            for (index in 0 until mappings.length()) {
                val row = mappings.getJSONObject(index)
                require(keys(row).all { it in setOf("eventName", "apiName", "apiNames", "extension") })
                val name = string(row, "eventName")
                val extension = string(row, "extension")
                require(name.isNotBlank() && name.length <= 256 && constant.matches(extension))
                require(entries.put(name, Entry(extension, names(row, 16, false), "")) == null)
            }
            return EventNameCatalog(root.getInt("catalogVersion"), sources.toMap(), entries.toMap(), emptyMap())
        }

        private fun parseSignatures(root: JSONObject): EventNameCatalog {
            require(keys(root) == setOf("schemaVersion", "catalogVersion", "mappings"))
            val mappings = root.getJSONArray("mappings")
            require(mappings.length() in 1..MAX_RULES)
            val entries = linkedMapOf<String, MutableMap<String, MutableMap<String, Entry>>>()
            val required = setOf("eventName", "eventType", "eventSource", "extension", "category")
            for (index in 0 until mappings.length()) {
                val row = mappings.getJSONObject(index)
                require(keys(row).containsAll(required) &&
                    keys(row).all { it in required || it in setOf("apiName", "apiNames") })
                val name = string(row, "eventName")
                val type = string(row, "eventType")
                val source = string(row, "eventSource")
                val extension = string(row, "extension")
                val category = string(row, "category")
                require(name.isNotBlank() && name.length <= 256)
                require(constant.matches(type) && type.startsWith("com.adobe.eventType.", true))
                require(constant.matches(source))
                require(constant.matches(extension))
                require(category in categories)
                val names = names(row, MAX_CANDIDATES, true)
                require(if (category == "WILD_EVENT") names.size != 1 else names.size == 1)
                val byType = entries.getOrPut(name) { linkedMapOf() }
                val bySource = byType.getOrPut(type.lowercase(Locale.ROOT)) { linkedMapOf() }
                require(bySource.put(source.lowercase(Locale.ROOT), Entry(extension, names, category)) == null)
            }
            return EventNameCatalog(root.getInt("catalogVersion"), null, emptyMap(),
                entries.mapValues { (_, types) -> types.mapValues { (_, sources) -> sources.toMap() } })
        }
    }
}
