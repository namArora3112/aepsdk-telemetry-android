plugins {
    id("com.android.library")
    kotlin("android")
}
android {
    namespace = "com.adobe.marketing.mobile.telemetry"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    implementation("com.adobe.marketing.mobile:core:3.11.0")
}
