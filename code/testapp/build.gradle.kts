plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "com.adobe.marketing.mobile.telemetry.testapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.adobe.marketing.mobile.telemetry.typedtestapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-alpha01"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "capture"
    productFlavors {
        create("withEdge") { dimension = "capture" }
        create("noEdge") { dimension = "capture"; applicationIdSuffix = ".noedge" }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":telemetry"))
    implementation("com.adobe.marketing.mobile:core:3.11.0-telemetry-hybrid-poc-SNAPSHOT")
    implementation("com.adobe.marketing.mobile:campaignclassic:3.1.4-telemetry-typed")
    "withEdgeImplementation"("com.adobe.marketing.mobile:edge:3.0.0-telemetry-typed")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
