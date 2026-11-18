package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import android.content.SharedPreferences

/** Persists installationId. Reset on reinstall happens naturally (SharedPreferences are wiped). */
internal class TelemetryStore(context: Context, private val ids: com.adobe.marketing.mobile.telemetry.IdGenerator) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("adobe.telemetry.store", Context.MODE_PRIVATE)

    val installationId: String
        get() = prefs.getString(KEY_INSTALLATION_ID, null) ?: ids.newId().also {
            prefs.edit().putString(KEY_INSTALLATION_ID, it).apply()
        }

    var adoptionFingerprint: String?
        get() = prefs.getString("adoptionFingerprint", null)
        set(value) { prefs.edit().putString("adoptionFingerprint", value).apply() }

    fun purge() = prefs.edit().clear().apply()

    private companion object { const val KEY_INSTALLATION_ID = "installationId" }
}
