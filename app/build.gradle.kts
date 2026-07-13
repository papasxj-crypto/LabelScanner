import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // The secrets plugin is removed here because we are handling the injection manually below
}

android {
    namespace = "net.meinook.labelscanner"

    // FIX: Replaced the broken block with the standard SDK assignment
    compileSdk = 34

    defaultConfig {
        applicationId = "net.meinook.labelscanner"
        minSdk = 24
        targetSdk = 34 // Locked to match your compile SDK stable target
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 1. Read your local.properties file manually
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }

        // 2. Fetch the key string safely
        val apiKey = localProperties.getProperty("GEMINI_API_KEY") ?: ""

        // 3. Force Gradle to write this directly into your BuildConfig class
        buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            // FIX: Simplified to the standard built-in minification tool string
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(libs.google.generativeai)
    implementation("com.google.android.material:material:1.12.0")
// ML Kit Barcode Scanning API
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0")

    // 🟢 Upgraded to 1.4.2 for strict 16 KB hardware compatibility
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
}