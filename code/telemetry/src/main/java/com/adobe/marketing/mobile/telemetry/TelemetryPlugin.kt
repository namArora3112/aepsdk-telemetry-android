package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.plugin.IAepPlugin

/**
 * Marker-conformant plugin (Q6 naming: Core plugin shipped as its own SDK artifact).
 * Customer path may use MobileCore.addPlugins(TelemetryPlugin()) before initialize;
 * functionally it only anchors the shared recorder install.
 */
class TelemetryPlugin : IAepPlugin
