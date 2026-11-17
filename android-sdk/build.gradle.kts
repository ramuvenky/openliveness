plugins {
    id("com.android.library") version "8.2.2"
    kotlin("android") version "1.9.0"
    `maven-publish`
}

// Published coordinate. Two consumers:
//   1. sibling Gradle project (android-sample) via `includeBuild(...)` —
//      composite-build dependency substitution matches on group +
//      rootProject.name, version is ignored.
//   2. JitPack release pipeline — on any pushed `v*` tag, JitPack runs
//      `./gradlew publishToMavenLocal` and serves the resulting AAR at
//      `com.github.ramuvenky:openliveness:<tag>` (JitPack flattens
//      single-module projects to groupId=com.github.<user>,
//      artifactId=<repo>, ignoring the Gradle `group` below). Version
//      here must match the tag minus the leading `v`.
group = "dev.openliveness"
version = "0.2.0"

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
    // Phase 2 drift tests touch AttestationBuilder which calls
    // Settings.Secure.getString(). In JVM unit tests android.* methods
    // throw by default; returnDefaultValues=true makes them return null
    // and the builder handles that gracefully ("unknown" fallback).
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Required by maven-publish for an Android library. Without this,
    // `publishToMavenLocal` can't find a release component to attach,
    // and JitPack falls back to an unusable "no release artifact" build.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Registers the Maven publication that JitPack consumes. Must be inside
// afterEvaluate because the "release" software component is only
// materialized after AGP finishes configuring the library variant.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "android-sdk"
                version = project.version.toString()
            }
        }
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
    // TFLite runtime for Layer 4 LBP classifier. The .tflite flatbuffer
    // itself ships in src/main/assets/lbp_classifier.tflite (Phase 5
    // Step 3 export). Keep the version pinned to what the exporter was
    // tested against — TFLite flatbuffers are forward-compatible but
    // op-set drift between TF 2.13 (export) and newer runtimes has
    // historically re-written the Dense fused weights.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
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
