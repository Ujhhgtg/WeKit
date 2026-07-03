enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
//        maven {
//            url = uri("$rootDir/local-maven")
//            content {
//                includeModule("androidx.compose.ui", "ui")
//                includeModule("androidx.compose.ui", "ui-android")
//                includeModule("androidx.compose.ui", "ui-jvmstubs")
//                includeModule("androidx.compose.ui", "ui-linuxx64stubs")
//                includeModule("androidx.compose.material3", "material3")
//                includeModule("androidx.compose.material3", "material3-android")
//                includeModule("androidx.compose.material3", "material3-jvmstubs")
//                includeModule("androidx.compose.material3", "material3-linuxx64stubs")
//            }
//        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")

//                excludeModule("androidx.compose.ui", "ui")
//                excludeModule("androidx.compose.ui", "ui-android")
//                excludeModule("androidx.compose.ui", "ui-jvmstubs")
//                excludeModule("androidx.compose.ui", "ui-linuxx64stubs")
//                excludeModule("androidx.compose.material3", "material3")
//                excludeModule("androidx.compose.material3", "material3-android")
//                excludeModule("androidx.compose.material3", "material3-jvmstubs")
//                excludeModule("androidx.compose.material3", "material3-linuxx64stubs")
            }
        }
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.Ujhhgtg")
                includeGroup("com.github.Ujhhgtg.rhino")
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
        maven("https://api.xposed.info/") {
            content {
                includeGroup("de.robv.android.xposed")
            }
        }
        mavenCentral()
        maven("https://raw.githubusercontent.com/HighCapable/maven-repository/main/repository/releases")
    }

    versionCatalogs {
        create("libs")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "wekit"

include(":app",
    ":libs:common:annotation-scanner",
    ":libs:external:comptime-kt:plugin",
    ":libs:external:comptime-kt:api",
    ":libs:common:stubs",
    ":libs:common:bsh",
    ":libs:common:reflekt"
)
