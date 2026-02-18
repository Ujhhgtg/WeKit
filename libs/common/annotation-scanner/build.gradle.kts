plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
    }
}

dependencies {
    implementation(libs.ksp)
    implementation(libs.kotlinpoet.ksp)
}




