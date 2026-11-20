package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

internal object UploadControl {
    @Volatile var enabled = true
        private set
    private var configured = false

    @Synchronized
    fun configure(context: Context) {
        if (configured) return
        enabled = runCatching {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) true
            else context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getBoolean("com.adobe.marketing.mobile.telemetry.UPLOADS_ENABLED", true) ?: true
        }.getOrDefault(false)
        configured = true
    }

    @Synchronized
    fun setEnabled(value: Boolean): Boolean {
        configured = true
        if (enabled == value) return false
        enabled = value
        return true
    }
}
