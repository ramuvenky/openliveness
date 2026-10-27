plugins {
    id("com.android.library") version "8.2.2"
    kotlin("android") version "1.9.0"
}

// Published coordinate so a sibling Gradle project (android-sample) can
// pull this in via `includeBuild("../android-sdk")` + a normal Maven
// `implementation(...)` line. The composite-build dependency-substitution
// rule matches on group + rootProject.name.
group = "dev.openliveness"
version = "0.1"

android {
    namespace = "dev.openliveness"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // BuildConfig.STUB_PATH_ENABLED is the compile-time half of the
    // stub_mode defense. It mirrors the iOS OPENLIVENESS_STUB_ALLOWED
    // SwiftPM flag: debug variants of the SDK (and any app depending on
    // this library's debug flavour) can take the stub liveness path;
    // release variants cannot. CDLSession enforces this at construction
    // time so a hostile fork that flips `allowStubMode = true` in source
    // still can't ship a release AAR that silently always passes
    // liveness. The server-side STUB_MODE_RP_ALLOWLIST is the second
    // layer of defense for the Android case (iOS doesn't need it because
    // its gate is physically compiled out — Android's constructor arg
    // is runtime, so this check + the server policy together close the
    // asymmetry).
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "STUB_PATH_ENABLED", "true")
        }
        getByName("release") {
            buildConfigField("boolean", "STUB_PATH_ENABLED", "false")
        }
    }
    // Phase 2 drift tests touch AttestationBuilder which calls
    // Settings.Secure.getString(). In JVM unit tests android.* methods
    // throw by default; returnDefaultValues=true makes them return null
    // and the builder handles that gracefully ("unknown" fallback).
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.0")
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")
    implementation("com.google.android.play:integrity:1.3.0")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // mockk powers the Phase 2 drift tests that need a stub Context /
    // PlayIntegrityService without pulling in Robolectric.
    testImplementation("io.mockk:mockk:1.13.8")
    // Real org.json shadows the Android stub on the JVM unit-test classpath.
    // Without this, JSONObject.getString() returns null under
    // isReturnDefaultValues=true, which silently breaks any test that
    // parses the attestation JSON by field name.
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    // MockWebServer powers the WebSocket close-before-ack and silent-peer
    // regression tests in CDLSessionE2ETest.
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
