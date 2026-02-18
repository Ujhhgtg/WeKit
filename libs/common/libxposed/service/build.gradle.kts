plugins {
    id("com.android.library")
    id("com.android.base")
    kotlin("android")
}

private fun findBuildToolsVersion(): String {
    val defaultBuildToolsVersion = "35.0.0"
    return File(System.getenv("ANDROID_HOME"), "build-tools").listFiles()?.filter { it.isDirectory }?.maxOfOrNull { it.name }
        ?.also { println("Using build tools version $it") }
        ?: defaultBuildToolsVersion
}

android {
    compileSdk = libs.versions.targetSdk.get().toInt()
    namespace = "io.github.libxposed.service"
    sourceSets {
        val main by getting
        main.apply {
            manifest.srcFile("service/service/src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("service/service/src/main/java"))
            aidl.setSrcDirs(listOf("service/interface/src/main/aidl"))
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        lint.targetSdk = libs.versions.targetSdk.get().toInt()
        buildToolsVersion = findBuildToolsVersion()
    }
    // Java 17 is required by libxposed-service
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }

    buildFeatures {
        buildConfig = false
        resValues = false
        aidl = true
    }

    dependencies {
        compileOnly(libs.androidx.annotation)
    }

}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of((libs.versions.jdk.get().toInt()))
    }
}
