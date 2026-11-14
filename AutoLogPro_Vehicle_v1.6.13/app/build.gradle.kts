plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.autologpro.app"
    compileSdk = 35
    // A v3 HardwareTriggerManager relies on the Android Automotive car framework.
    // The Android Studio SDK must include the Android 14/15 Android Car optional library.
    useLibrary("android.car")
    defaultConfig {
        applicationId = "com.autologpro.app"
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 31
        versionName = "1.6.13"
    }
    signingConfigs {
        if (providers.gradleProperty("alpDebugKeystore").isPresent) {
            create("alpEmulator") {
                storeFile = file(providers.gradleProperty("alpDebugKeystore").get())
                storePassword = "android"
                keyAlias = "alp-emulator-demo"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            if (providers.gradleProperty("alpEmulatorSideBySide").orNull == "true") {
                applicationIdSuffix = ".demo"
            }
            signingConfigs.findByName("alpEmulator")?.let { signingConfig = it }
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    // Role A v3 core + Role C latest Room data, docs and QR engine.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    // Pleos Vehicle SDK: A v4부터 필수 (Odometer = getCurrentDistanceDriven). 빠지면 컴파일 실패.
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.20")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
