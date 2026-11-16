plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}
group = "com.adobe.marketing.mobile"
version = "1.0.0-alpha01"

android {
    namespace = "com.adobe.marketing.mobile.telemetry"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    publishing { singleVariant("release") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.adobe.marketing.mobile:core:3.11.0-telemetry-typed")
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
                version = "1.0.0-alpha01"
            }
        }
    }
}
