package com.adobe.marketing.mobile.telemetry.internal

import org.json.JSONObject

/**
 * One automatic collection event (Phase 1). Carries its real capture timestamp, the
 * owning session id, and non-personal string properties only. No payloads, identities,
 * or free-form user content are ever attached by the collectors.
 */
internal data class AutoEvent(
    val name: String,
    val timestampMs: Long,
    val sessionId: String,
    val properties: Map<String, String> = emptyMap()
) {
    init {
        require(name.isNotBlank() && name.length <= 64) { "event name out of bounds" }
        require(timestampMs > 0) { "timestamp must be positive epoch ms" }
        require(sessionId.isNotBlank() && sessionId.length <= 64) { "session id out of bounds" }
        require(properties.size <= 16) { "too many properties" }
        properties.forEach { (key, value) ->
            require(key.matches(PROPERTY_KEY)) { "invalid property key: $key" }
            require(value.length <= 128) { "property value too long for $key" }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("timestamp", timestampMs)
        .put("sessionId", sessionId)
        .put("properties", JSONObject(properties))

    companion object {
        private val PROPERTY_KEY = Regex("[a-z][a-z0-9_]{0,63}")

        fun fromJson(json: JSONObject): AutoEvent {
            val propsJson = json.optJSONObject("properties") ?: JSONObject()
            val props = mutableMapOf<String, String>()
            propsJson.keys().forEach { key -> props[key] = propsJson.getString(key) }
            return AutoEvent(
                json.getString("name"),
                json.getLong("timestamp"),
                json.getString("sessionId"),
                props
            )
        }
    }
}
