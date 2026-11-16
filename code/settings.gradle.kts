pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://maven.adobe.com/nexus/content/repositories/public/") }
}
rootProject.name = "telemetry-poc"
include(":telemetry", ":testapp")
