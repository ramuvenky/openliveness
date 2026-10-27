plugins {
    id("com.android.application") version "8.2.2"
    kotlin("android") version "1.9.0"
}

android {
    namespace = "dev.openliveness.sample"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.openliveness.sample"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
    // Resolves to the sibling android-sdk via the composite build declared
    // in settings.gradle.kts. The coordinate matches android-sdk's
    // group ("dev.openliveness") + rootProject.name ("openliveness") +
    // version ("0.1") so Gradle substitutes automatically.
    implementation("dev.openliveness:openliveness:0.1")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Needed because camera-view / camera-core are declared with
    // `implementation` scope in the SDK — not transitively exposed to
    // this sample. The sample references PreviewView directly to build
    // its layout and must list the dep explicitly.
    implementation("androidx.camera:camera-view:1.3.0")
}
