package com.adobe.marketing.mobile.telemetry.testapp;

import com.adobe.marketing.mobile.Edge;
import com.adobe.marketing.mobile.EdgeCallback;
import com.adobe.marketing.mobile.ExperienceEvent;
import com.adobe.marketing.mobile.Extension;
import java.util.Collections;
import java.util.List;

public final class EdgeScenario {
    public static boolean available() { return true; }
    public static List<Class<? extends Extension>> extensions() {
        return Collections.singletonList(Edge.EXTENSION);
    }
    public static void sendValid() {
        Edge.sendEvent(new ExperienceEvent.Builder()
                .setXdmSchema(Collections.singletonMap("eventType", "application.launch"))
                .build(), (EdgeCallback) null);
    }
    public static void sendInvalid() {
        Edge.sendEvent(null, (EdgeCallback) null);
    }
    private EdgeScenario() {}
}
