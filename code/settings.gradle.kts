pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://maven.adobe.com/nexus/content/repositories/public/") }
}
rootProject.name = "telemetry"
include(":telemetry", ":testapp")

// Artifacts stay local to this composite; no shared Maven-local overwrite is required.
providers.gradleProperty("telemetryCorePath").orNull?.let { path ->
    includeBuild(path) {
        name = "typed-core"
        dependencySubstitution {
            substitute(module("com.adobe.marketing.mobile:core")).using(project(":core"))
        }
    }
}
providers.gradleProperty("telemetryEdgePath").orNull?.let { path ->
    includeBuild(path) {
        name = "typed-edge"
        dependencySubstitution {
            substitute(module("com.adobe.marketing.mobile:edge")).using(project(":edge"))
        }
    }
}
providers.gradleProperty("telemetryCampaignPath").orNull?.let { path ->
    includeBuild(path) {
        name = "typed-campaign"
        dependencySubstitution {
            substitute(module("com.adobe.marketing.mobile:campaignclassic")).using(project(":campaignclassic"))
        }
    }
}
