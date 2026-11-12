plugins {
    id("com.android.application")
}

android {
    namespace = "ly.dojoon.poslink.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "ly.dojoon.poslink.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":poslink"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
