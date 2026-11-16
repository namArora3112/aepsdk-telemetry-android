package com.adobe.marketing.mobile.telemetry.testapp

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.telemetry.Telemetry

class MainActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 12f }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun button(label: String, onClick: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { onClick(); render() } })
        }

        button("Telemetry via EventHub (projection)") {
            // business call dispatches a hub event; the projection listener maps it (no direct call here)
            MobileCore.trackAction("poc_action_hub", null)
        }
        button("Telemetry via exposed functions (direct hook)") {
            Telemetry.event("com.adobe.module.core", "sdk.core.trackAction") // direct hook record
        }
        button("Flush queue (public API)") { Telemetry.flushQueue() }
        button("Simulate Messaging refresh (operationId lifecycle)") {
            val op = Telemetry.operationStarted("com.adobe.messaging", "sdk.messaging.refresh")
            output.postDelayed({
                Telemetry.operationCompleted(op, "com.adobe.messaging", "sdk.messaging.refresh",
                    outcome = "success", outcomeMeaning = "pipeline finalized")
            }, 350)
        }
        button("Duplicate spam x5 (dedupe window test)") {
            repeat(5) { Telemetry.event("com.adobe.messaging", "sdk.messaging.duplicateSpam") }
        }
        button("Flush now") { Telemetry.flush() }
        button("Kill switch OFF (purge)") {
            Telemetry.applyConfig(Telemetry.stats().let { com.adobe.marketing.mobile.telemetry.TelemetryConfig(enabled = false) })
        }
        button("Kill switch ON") {
            Telemetry.applyConfig(com.adobe.marketing.mobile.telemetry.TelemetryConfig(enabled = true))
        }

        layout.addView(ScrollView(this).apply { addView(output) })
        setContentView(layout)
        render()
    }

    private fun render() {
        output.text = buildString {
            appendLine("== Telemetry POC ==")
            Telemetry.stats().forEach { (k, v) -> appendLine("$k: $v") }
        }
    }
}
