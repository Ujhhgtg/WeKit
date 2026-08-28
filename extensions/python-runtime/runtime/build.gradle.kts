import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version libs.versions.pythonRuntimeAgp
    id("com.chaquo.python") version libs.versions.pythonRuntimeChaquopy
    id("org.jetbrains.kotlin.android") version libs.versions.kotlin
}

group = "dev.ujhhgtg.wekit.python.runtime"
version = libs.versions.pythonRuntimeVersion.get()

configure<ApplicationExtension> {
    namespace = "dev.ujhhgtg.wekit.python.runtime"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.pythonRuntimeNdk.get()
    defaultConfig {
        applicationId = "dev.ujhhgtg.wekit.python.runtime.container"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.pythonRuntimeVersionCode.get().toInt()
        versionName = libs.versions.pythonRuntimeVersion.get()
        ndk { abiFilters.add(libs.versions.pythonRuntimeAbi.get()) }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }
    packaging { jniLibs.useLegacyPackaging = true }
}

chaquopy { defaultConfig { version = libs.versions.pythonRuntimePython.get() } }

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get())) }
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // Supplied by xtask from a controlled local Maven repository; compile-only
    // prevents API classes from entering the runtime DEX.
    val apiVersion = providers.gradleProperty("wekitPythonApiVersion").orElse(libs.versions.pythonRuntimeApiVersion)
    compileOnly("dev.ujhhgtg.wekit:python-runtime-api:${apiVersion.get()}")
}
