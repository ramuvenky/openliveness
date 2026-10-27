pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "openliveness-sample"

// Composite build: pulls android-sdk in as a source dependency. The
// implementation("dev.openliveness:openliveness:0.1") line in
// build.gradle.kts resolves to the sibling project rather than to a
// published Maven artifact — so the sample always builds against the
// local SDK source, no publish step required.
includeBuild("../android-sdk")
