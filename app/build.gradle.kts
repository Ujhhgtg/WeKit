import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
}

fun getCommitCount(): Int {
    return providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

fun getGitHash(): String {
    return providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
}

android {
    namespace = libs.versions.namespace.get()
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    ndkVersion = libs.versions.ndk.get()

    val commitCount = getCommitCount()
    val gitHash = getGitHash()

    logger.lifecycle(
        """
             _       __     __ __ _ __
            | |     / /__  / //_/(_) /_
            | | /| / / _ \/ ,<  / / __/
            | |/ |/ /  __/ /| |/ / /_
            |__/|__/\___/_/ |_/_/\__/

       [WeKit] WeChat, now with superpowers
        """
    )

    defaultConfig {
        applicationId = libs.versions.namespace.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = commitCount
        versionName = "git+$gitHash"

        buildConfigField("String", "GIT_HASH", "\"${gitHash}\"")
        buildConfigField("String", "TAG", "\"WeKit\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true
        }
    }

    sourceSets["main"].jniLibs.directories += "src/main/jniLibs"

    var foundKeystore = false

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
        debug {
            signingConfig = signingConfigs.getByName(if (foundKeystore) "release" else "debug")
        }

        release {
            optimization.enable = true
            signingConfig = signingConfigs.getByName(if (foundKeystore) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }

    packaging {
        resources.excludes += listOf(
            "kotlin/**",
            "**.bin",
            "kotlin-tooling-metadata.json",
            "META-INF/INDEX.LIST"
        )
        resources.merges += listOf(
            "META-INF/io.netty.versions.properties",
            "META-INF/xposed/*",
            "org/mozilla/javascript/**"
        )
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += setOf("zh")
        additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x69")
    }

    buildFeatures {
        resValues = false
        compose = true
        buildConfig = true
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get()))
    }
}

val adbProvider = androidComponents.sdkComponents.adb
androidComponents {
    onVariants { variant ->
        val kotlinSources = variant.sources.kotlin ?: return@onVariants

        kotlinSources.addGeneratedSourceDirectory(
            generateMethodHashes,
            GenerateMethodHashesTask::outputDir
        )

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val embedAboutLibraries = tasks.register<EmbedAboutLibrariesTask>("embedAboutLibraries$variantName") {
            group = "wekit"
            description = "Embed aboutlibraries.json as a String constant for $variantName"

            val aboutLibrariesJson = layout.buildDirectory.file("generated/aboutLibraries/${variant.name}/res/raw/aboutlibraries.json")
            inputFile.set(aboutLibrariesJson)
            outputDir.set(layout.buildDirectory.dir("generated/source/aboutlibraries/${variant.name}"))
            namespace.set(libs.versions.namespace.get())
        }

        embedAboutLibraries.configure {
            dependsOn(tasks.named("prepareLibraryDefinitions$variantName"))
        }

        kotlinSources.addGeneratedSourceDirectory(
            embedAboutLibraries,
            EmbedAboutLibrariesTask::outputDir
        )

        val embedEruda = tasks.register<EmbedErudaTask>("embedEruda$variantName") {
            group = "wekit"
            description = "Embed eruda.min.js as a String constant for $variantName"

            url.set("https://cdn.jsdelivr.net/npm/eruda@3.4.3/eruda.min.js")
            outputDir.set(layout.buildDirectory.dir("generated/source/eruda/${variant.name}"))
            namespace.set(libs.versions.namespace.get())
        }

        kotlinSources.addGeneratedSourceDirectory(
            embedEruda,
            EmbedErudaTask::outputDir
        )
    }
}

// --- tasks ---

val generateMethodHashes = tasks.register<GenerateMethodHashesTask>("generateMethodHashes") {
    description = "Generate resolveDex() method hashes"
    group = "wekit"
    sourceDir.set(file("src/main/java"))
    outputDir.set(layout.buildDirectory.dir("generated/source/methodhashes"))
    namespace.set(libs.versions.namespace.get())
}

val rustProjectDir = file("src/main/rust/wekit-native")
val rustLibName = "libwekit_native.so"

val abiToTarget = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86" to "i686-linux-android"
)
val cargoTasks = abiToTarget.map { (abi, target) ->
    val soSrcFile = rustProjectDir.resolve("target/$target/release/$rustLibName")
    val soDestDir = layout.projectDirectory.dir("src/main/jniLibs/$abi").asFile
    val currentLibName = rustLibName

    tasks.register<Exec>("cargoBuild_${abi.replace('-', '_')}") {
        group = "rust"
        description = "Compile Rust for $abi"
        workingDir = rustProjectDir
        commandLine = listOf(
            "cargo", "build",
            "--release",
            "--target", target,
        )

        doLast {
            soDestDir.mkdirs()
            soSrcFile.copyTo(soDestDir.resolve(currentLibName), overwrite = true)
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { cargoTasks.forEach { t -> dependsOn(t) } }

val configureCargo = tasks.register<ConfigureCargoTask>("configureCargo") {
    group = "wekit"
    description = "Generate .cargo/config.toml"

    val home = gradleLocalProperties(rootDir, providers).getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: error("ANDROID_HOME / sdk.dir not set")

    androidHome.set(home)
    minSdk.set(libs.versions.minSdk.get().toInt())
    outputFile.set(rustProjectDir.resolve(".cargo/config.toml"))

    outputs.upToDateWhen { outputFile.get().asFile.exists() }
}

cargoTasks.forEach { t -> t.configure { dependsOn(configureCargo) } }

// --- end tasks ---

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.composablehorizons.material.symbols.filled)
    implementation(libs.composablehorizons.material.symbols.outlined)

    implementation(libs.google.protobuf.javalite)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.mmkv)

    implementation(project(":libs:common:bsh"))

    compileOnly(libs.legacyxposed.api)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.hiddenapibypass)
    implementation(project(":libs:common:reflekt"))
    implementation(libs.libsu.core)
    implementation(libs.dexmaker)
    @Suppress("AvoidDuplicateDependencies")
    implementation(project(":libs:common:annotation-scanner"))
    @Suppress("AvoidDuplicateDependencies")
    ksp(project(":libs:common:annotation-scanner"))

    implementation(libs.okhttp3.okhttp)

    implementation(libs.rhino)

    implementation(libs.fastjson2)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)

    implementation(libs.mcp.server)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.osmdroid.android)

    implementation(project(":libs:external:comptime-kt:api"))
    compileOnly(project(":libs:common:stubs"))
}

// markwon conflict
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")

//    resolutionStrategy {
//        force("androidx.compose.ui:ui:1.12.0-beta01")
//        force("androidx.compose.ui:ui-android:1.12.0-beta01")
//        force("androidx.compose.material3:material3:1.5.0-alpha21")
//        force("androidx.compose.material3:material3-android:1.5.0-alpha21")
//    }
}

evaluationDependsOn(":libs:external:comptime-kt:plugin")
tasks.withType<KotlinJvmCompile>().configureEach {
    val pluginJarTask = project(":libs:external:comptime-kt:plugin").tasks.named<org.gradle.jvm.tasks.Jar>("jar")
    dependsOn(pluginJarTask)

    compilerOptions {
        val pluginJarPath = pluginJarTask.get().archiveFile.get().asFile.absolutePath
        freeCompilerArgs.add("-Xplugin=$pluginJarPath")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}
