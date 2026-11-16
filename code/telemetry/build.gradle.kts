plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}
group = "com.adobe.marketing.mobile"
version = "0.1.0-poc"

android {
    namespace = "com.adobe.marketing.mobile.telemetry"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    publishing { singleVariant("release") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    implementation("com.adobe.marketing.mobile:core:3.11.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components.findByName("release"))
                groupId = "com.adobe.marketing.mobile"
                artifactId = "telemetry"
                version = "0.1.0-poc"
            }
        }
    }
}
