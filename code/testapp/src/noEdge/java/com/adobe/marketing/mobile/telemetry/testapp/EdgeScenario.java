package com.adobe.marketing.mobile.telemetry.testapp;

import com.adobe.marketing.mobile.Extension;
import java.util.Collections;
import java.util.List;

public final class EdgeScenario {
    public static boolean available() { return false; }
    public static List<Class<? extends Extension>> extensions() { return Collections.emptyList(); }
    public static void sendValid() {}
    public static void sendInvalid() {}
    private EdgeScenario() {}
}
