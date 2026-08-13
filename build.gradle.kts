plugins {
    kotlin("jvm") version libs.versions.kotlin apply false
    kotlin("kapt") version libs.versions.kotlin apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
}

// Skip release lint analysis on every module (library + application). The
// lintVital* tasks are heavy and OOM on constrained CI runners; release APKs
// don't need lint to be produced.
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            lint {
                checkReleaseBuilds = false
                abortOnError = false
            }
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            lint {
                checkReleaseBuilds = false
                abortOnError = false
            }
        }
    }
}
