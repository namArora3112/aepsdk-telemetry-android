plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "com.adobe.marketing.mobile.telemetry.testapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.adobe.marketing.mobile.telemetry.testapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-poc"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    implementation(project(":telemetry"))
    implementation("com.adobe.marketing.mobile:core:3.11.0")
}
