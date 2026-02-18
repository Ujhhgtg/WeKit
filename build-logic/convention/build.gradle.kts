import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

group = "moe.ouom.wekit.buildLogic"

repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.android.tools)
    implementation(libs.kotlin.gradle)
}

java {
    targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jdk.get())
    }
}
