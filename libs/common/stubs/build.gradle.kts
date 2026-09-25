import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.base")
    id("com.android.library")
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get()))
    }
    jvmToolchain(libs.versions.jdk.get().toInt())
}

configure<LibraryExtension> {
    namespace = "dev.ujhhgtg.wekit.stubs"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        lint.targetSdk = libs.versions.targetSdk.get().toInt()
        compileSdk = libs.versions.targetSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)

    annotationProcessor(libs.hiddenapirefine.annotation.processor)
    compileOnly(libs.hiddenapirefine.annotation)
}
