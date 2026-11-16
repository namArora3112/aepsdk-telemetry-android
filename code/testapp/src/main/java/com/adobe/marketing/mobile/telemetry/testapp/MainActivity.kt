package com.adobe.marketing.mobile.telemetry.testapp

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.CampaignClassic
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
            // The central typed observer captures this existing business event.
            MobileCore.trackAction("poc_action_hub", null)
        }
        button("Telemetry via exposed functions (direct hook)") {
            CampaignClassic.registerDevice("", null, null) // rejected before hub dispatch
        }
        button("Real Edge.sendEvent (with-Edge flavor)") { EdgeScenario.sendValid() }
        button("Real Campaign receive via EventHub") {
            CampaignClassic.trackNotificationReceive(mapOf("_dId" to "test-delivery", "_mId" to "test-message"))
        }
        button("Flush queue (public API)") { Telemetry.flushQueue() }
        button("Example local operation (not a Messaging API)") {
            val op = Telemetry.operationStarted("poc.example", "sdk.example.localWork")
            output.postDelayed({
                Telemetry.operationCompleted(op, "poc.example", "sdk.example.localWork",
                    outcome = "success", outcomeMeaning = "local example completed")
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
