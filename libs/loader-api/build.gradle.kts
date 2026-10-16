import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.base")
    id("com.android.library")
}

configure<LibraryExtension> {
    namespace = "dev.ujhhgtg.wekit.loader.api"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get())) } }
dependencies { compileOnly(libs.androidx.annotation) }
