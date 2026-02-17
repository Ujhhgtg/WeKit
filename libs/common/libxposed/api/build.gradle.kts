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
    compileSdk = 35
    namespace = "io.github.libxposed.api"
    sourceSets {
        val main by getting
        main.apply {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("api/api/src/main/java"))
        }
    }

    defaultConfig {
        minSdk = 35
        //noinspection OldTargetApi
        lint.targetSdk = 35
        buildToolsVersion = findBuildToolsVersion()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    dependencies {
        // androidx nullability stubs
        compileOnly(libs.androidx.annotation)
    }
}
