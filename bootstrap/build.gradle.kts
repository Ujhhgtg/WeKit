
import com.android.build.api.variant.BuildConfigField
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val commitCount = providers.exec {
    workingDir(rootProject.layout.projectDirectory)
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toInt() }
val gitHash = providers.exec {
    workingDir(rootProject.layout.projectDirectory)
    commandLine("git", "rev-parse", "--short=8", "HEAD")
}.standardOutput.asText.map { it.trim() }
val commitTimestamp = providers.exec {
    workingDir(rootProject.layout.projectDirectory)
    commandLine("git", "log", "-1", "--format=%ct", "HEAD")
}.standardOutput.asText.map { it.trim().toLong() * 1000L }
val embeddedPayloadBuildType = providers.gradleProperty("wekit.embeddedPayloadBuildType").orElse("debug")
var foundKeystore = false

android {
    namespace = "dev.ujhhgtg.wekit.bootstrap"
    compileSdk { version = release(libs.versions.compileSdk.get().toInt()) {
        minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
    } }
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        applicationId = "dev.ujhhgtg.wekit"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        buildConfigField("String", "TAG", "\"WeKit\"")
        // noinspection ChromeOsAbiSupport
        ndk { abiFilters += "arm64-v8a" }
    }

    @Suppress("LocalVariableName")
    signingConfigs {
        val _storeFile = System.getenv("WEKIT_KEYSTORE_FILE")
            ?: runCatching { project.property("WEKIT_KEYSTORE_FILE") }.getOrNull() as? String?
        val _storePassword = System.getenv("WEKIT_KEYSTORE_PASSWORD")
            ?: runCatching { project.property("WEKIT_KEYSTORE_PASSWORD") }.getOrNull() as? String?
        val _keyAlias = System.getenv("WEKIT_KEY_ALIAS")
            ?: runCatching { project.property("WEKIT_KEY_ALIAS") }.getOrNull() as? String?
        val _keyPassword = System.getenv("WEKIT_KEY_PASSWORD")
            ?: runCatching { project.property("WEKIT_KEY_PASSWORD") }.getOrNull() as? String?

        if (_storeFile != null && _storePassword != null && _keyAlias != null && _keyPassword != null) {
            create("release") {
                foundKeystore = true
                storeFile = file(_storeFile)
                storePassword = _storePassword
                keyAlias = _keyAlias
                keyPassword = _keyPassword

                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }


    buildTypes {
        debug { signingConfig = signingConfigs.getByName(if (foundKeystore) "release" else "debug") }
        release {
            optimization.enable = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "bootstrap-rules.pro")
            signingConfig = signingConfigs.getByName(if (foundKeystore) "release" else "debug")
        }
        create("local") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName(if (foundKeystore) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }
    buildFeatures { buildConfig = true; compose = true }
    lint { checkReleaseBuilds = false }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
    packaging {
        dex { useLegacyPackaging = true }
        // Framework module loaders resolve native code directly inside the APK (ZIP STORED).
        jniLibs { useLegacyPackaging = false }
        resources.merges += "META-INF/xposed/*"
    }
    androidResources {
        additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x68",
            "--rename-resources-package", "dev.ujhhgtg.wekit.bootstrap")
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get())) } }
androidComponents {
    onVariants { variant ->
        val embeddedPayload = variant.name == "local"
        val payloadBuildType = if (embeddedPayload) {
            embeddedPayloadBuildType.get().also {
                require(it == "debug" || it == "release") {
                    "wekit.embeddedPayloadBuildType must be debug or release"
                }
            }
        } else null
        variant.outputs.forEach { output ->
            output.versionCode.set(commitCount)
            output.versionName.set(gitHash.map { "git+$it" })
            if (payloadBuildType != null) {
                output.outputFileName.set("bootstrap-local-$payloadBuildType.apk")
            }
        }
        variant.buildConfigFields!!.put("EMBEDDED_PAYLOAD", BuildConfigField("boolean", "$embeddedPayload", null))
        variant.buildConfigFields!!.put("COMMIT_HASH", gitHash.map {
            BuildConfigField("String", "\"$it\"", null)
        })
        variant.buildConfigFields!!.put("COMMIT_TIMESTAMP", commitTimestamp.map {
            BuildConfigField("long", "${it}L", null)
        })
        val generateZygiskResources = tasks.register<GenerateZygiskResourcesTask>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}ZygiskResources"
        ) {
            description = "Generate Zygisk module resources"
            templateDir.set(rootProject.layout.projectDirectory.dir("wekit-zygisk/template"))
            versionCode.set(variant.outputs.single().versionCode)
            versionName.set(variant.outputs.single().versionName)
            variantName.set(variant.name)
            outputDir.set(layout.buildDirectory.dir("generated/zygiskResources/${variant.name}"))
        }
        variant.sources.resources!!.addGeneratedSourceDirectory(generateZygiskResources,
            GenerateZygiskResourcesTask::outputDir)
        if (embeddedPayload) {
            val appBuildType = payloadBuildType!!
            val prepareEmbeddedPayload = tasks.register<PrepareEmbeddedPayloadTask>(
                "prepare${variant.name.replaceFirstChar { it.uppercase() }}EmbeddedPayload"
            ) {
                description = "Embed the $appBuildType app payload in the bootstrap APK"
                dependsOn(":app:assemble${appBuildType.replaceFirstChar { it.uppercase() }}")
                appApk.set(rootProject.layout.projectDirectory.file(
                    "app/build/outputs/apk/$appBuildType/app-$appBuildType.apk"
                ))
                requireProductionSigning.set(true)
                productionSigningConfigured.set(foundKeystore)
                outputDir.set(layout.buildDirectory.dir("generated/embeddedPayload/${variant.name}"))
            }
            variant.sources.assets!!.addGeneratedSourceDirectory(prepareEmbeddedPayload,
                PrepareEmbeddedPayloadTask::outputDir)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.composablehorizons.material.symbols.outlined)
    implementation(libs.composablehorizons.material.symbols.filled)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.libsu.core)
    implementation(libs.libxposed.service)
    implementation(project(":libs:common:bsh"))
    implementation(project(":libs:loader-api"))
    implementation(project(":libs:common:reflekt"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(libs.hiddenapibypass)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        freeCompilerArgs.add("-opt-in=kotlin.io.path.ExperimentalPathApi")
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}
