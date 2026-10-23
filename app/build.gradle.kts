plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.meinook.labelscanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.filterpointsoftware.filterpoint"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "1.0.0-beta01"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Retained for local testing/demo release builds
        buildConfigField("Boolean", "BYPASS_PAYWALL", "true")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "keepRules/rules.keep"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Google Jetpack Core Splash Screen Library
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Standard Google Play Billing Library with Coroutines support
    val billing_version = "8.0.0"
    implementation("com.android.billingclient:billing:$billing_version")
    implementation("com.android.billingclient:billing-ktx:$billing_version")

    // Stabilized Firebase BoM (Aligns all standard core libraries to your Kotlin compiler)
    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))

    // Firebase Auth & Firestore SDKs (Managed by BoM 33.9.0)
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Coroutines Play Services Integration
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Core Android & UI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Google ML Kit Text Recognition (Used by OCR label scan)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    // Barcode Scanning - ZXing
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Navigation Components
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Jetpack Compose (The Thumb Menu)
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // OkHttp for Web Scraping & Secure Backend REST Proxies
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}