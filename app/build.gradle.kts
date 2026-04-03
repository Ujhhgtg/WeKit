import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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

configure<ApplicationExtension> {
    namespace = libs.versions.namespace.get()
    compileSdk = libs.versions.targetSdk.get().toInt()
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

    signingConfigs {
        create("release") {
            storeFile = file(
                System.getenv("WEKIT_KEYSTORE_FILE")
                    ?: project.property("WEKIT_KEYSTORE_FILE") as String
            )
            storePassword = System.getenv("WEKIT_KEYSTORE_PASSWORD")
                ?: project.property("WEKIT_KEYSTORE_PASSWORD") as String
            keyAlias = System.getenv("WEKIT_KEY_ALIAS")
                ?: project.property("WEKIT_KEY_ALIAS") as String
            keyPassword = System.getenv("WEKIT_KEY_PASSWORD")
                ?: project.property("WEKIT_KEY_PASSWORD") as String

            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        resValues = true
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get()))
    }
    jvmToolchain(libs.versions.jdk.get().toInt())
}

val adbProvider = androidComponents.sdkComponents.adb
androidComponents {
    onVariants { variant ->
        val kotlinSources = variant.sources.kotlin ?: return@onVariants

        kotlinSources.addGeneratedSourceDirectory(
            generateMethodHashes,
            GenerateMethodHashesTask::outputDir
        )
    }
}

// --- tasks ---

val generateMethodHashes = tasks.register<GenerateMethodHashesTask>("generateMethodHashes") {
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
            val soSrc = rustProjectDir
                .resolve("target/$target/release/$rustLibName")
            val soDir = layout.projectDirectory
                .dir("src/main/jniLibs/$abi").asFile
            soDir.mkdirs()
            soSrc.copyTo(soDir.resolve(rustLibName), overwrite = true)
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.activity)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.kyant0.backdrop)
    implementation(libs.kyant0.shapes)

    implementation(libs.composablehorizons.material.symbols.filled)
    implementation(libs.composablehorizons.material.symbols.outlined)

    implementation(libs.google.guava)
    implementation(libs.google.protobuf.javalite)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mmkv)

    compileOnly(libs.legacyxposed.api)
    compileOnly(project(":libs:common:libxposed-api"))
    implementation(libs.libxposed.service)
    implementation(libs.dexlib2)
    implementation(libs.dexkit)
    implementation(libs.hiddenapibypass)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.libsu.core)
    implementation(libs.dexmaker)
    implementation(project(":libs:common:annotation-scanner"))
    ksp(project(":libs:common:annotation-scanner"))

    implementation(libs.dalvik.dx)
    implementation(libs.okhttp3.okhttp)

    implementation(libs.rhino.android)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)
    implementation(libs.markwon.image)
    implementation(libs.svg)
    implementation(libs.gif)

    implementation(libs.mcp.server)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.netty)
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
