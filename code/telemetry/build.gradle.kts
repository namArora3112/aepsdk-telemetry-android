plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}
group = "com.adobe.marketing.mobile"
version = "1.0.0-catalog-poc"

android {
    namespace = "com.adobe.marketing.mobile.telemetry"
    compileSdk = 36
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
    publishing { singleVariant("release") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.adobe.marketing.mobile:core:3.11.0-telemetry-catalog-poc-SNAPSHOT")
    // Auto-init + auto-collect pipeline (Phase 1/2). `api` so the manifest entries and
    // transitive providers survive delivery of this AAR through Core.
    api("androidx.startup:startup-runtime:1.2.0")
    api("androidx.lifecycle:lifecycle-process:2.7.0")
    api("androidx.work:work-runtime:2.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components.findByName("release"))
                groupId = "com.adobe.marketing.mobile"
                artifactId = "telemetry"
                version = "1.0.0-catalog-poc"
            }
        }
    }
}
