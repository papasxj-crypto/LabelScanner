// Top-level build file where you can add configuration options common to all subprojects/modules.
plugins {
    // These versions must match. Using Kotlin 2.0.0 with the new Compose Compiler plugin.
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin") version "2.0.1" apply false
}