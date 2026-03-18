import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.security.MessageDigest

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

fun findNdkClang(androidHome: String, minSdk: Int, minNdk: Int = 29): String? {
    val ndkRoot = File("$androidHome/ndk")
    if (!ndkRoot.exists()) return null
    val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
    val ext = if (isWindows) ".cmd" else ""

    return ndkRoot.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir ->
            val parts = dir.name.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.isNotEmpty() && parts[0] >= minNdk) dir else null
        }
        ?.sortedWith(compareBy(*Array(3) { i -> { d: File -> d.name.split(".").getOrNull(i)?.toIntOrNull() ?: 0 } }))
        ?.lastOrNull()
        ?.let { ndkDir ->
            fileTree(ndkDir).matching { include("**/*-linux-android${minSdk}-clang$ext") }
                .firstOrNull()
                ?.absolutePath
        }
}

fun setCargoClang(androidHome: String) {
    val minSdk = libs.versions.minSdk.get().toInt()
    val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
    val ext = if (isWindows) ".cmd" else ""

    val clangPath = findNdkClang(androidHome, minSdk) ?: error("No NDK >= $minSdk found in $androidHome/ndk")
    logger.lifecycle("Found NDK clang: $clangPath")

    // TOML requires forward slashes on all platforms
    val ndkBinDir = File(clangPath).parent.replace('\\', '/')
    val configToml = rootProject.file("app/src/main/rust/wekit-native/.cargo/config.toml")

    configToml.parentFile.mkdirs()
    configToml.writeText("""
        [target.aarch64-linux-android]
        linker = "$ndkBinDir/aarch64-linux-android${minSdk}-clang$ext"

        [target.x86_64-linux-android]
        linker = "$ndkBinDir/x86_64-linux-android${minSdk}-clang$ext"
    """.trimIndent())
    logger.lifecycle("Written .cargo/config.toml to ${configToml.absolutePath}")
}

configure<ApplicationExtension> {
    val androidHome = gradleLocalProperties(rootDir, providers).getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: error("ANDROID_HOME / sdk.dir not set")
    val ndkVer = libs.versions.ndk.get()

    if (!File("$androidHome/ndk/$ndkVer").exists()) {
        logger.lifecycle("NDK $ndkVer not found, installing via sdkmanager...")
        val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
        val sdkmanager = "$androidHome/cmdline-tools/latest/bin/sdkmanager" + if (isWindows) ".bat" else ""
        providers.exec { commandLine(sdkmanager, "--install", "ndk;$ndkVer") }
        logger.lifecycle("NDK $ndkVer installed.")
    }

    if (!rootProject.file("app/src/main/rust/wekit-native/.cargo/config.toml").exists()) {
        logger.lifecycle("Cargo config not found, configuring it...")
        setCargoClang(androidHome)
    }

    namespace = libs.versions.namespace.get()
    compileSdk = libs.versions.targetSdk.get().toInt()

    ndkVersion = ndkVer

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
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    sourceSets["main"].jniLibs.directories += "src/main/jniLibs"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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

fun isHooksDirPresent(task: Task): Boolean {
    return task.outputs.files.any { outputDir ->
        File(outputDir, "moe/ouom/wekit/hooks").exists()
    }
}

tasks.withType<KotlinCompile>().configureEach {
    if (name.contains("Release")) {
        outputs.upToDateWhen { task ->
            isHooksDirPresent(task)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    if (name.contains("Release")) {
        outputs.upToDateWhen { task ->
            isHooksDirPresent(task)
        }
    }
}

// --- tasks ---

abstract class GenerateMethodHashesTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val srcDir = sourceDir.get().asFile
        val outDir = outputDir.get().asFile
        val outputFile = outDir.resolve("moe/ouom/wekit/dexkit/cache/GeneratedMethodHashes.kt")

        val hashMap = mutableMapOf<String, String>()
        srcDir.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("IResolvesDex") }.forEach { file ->
            val content = file.readText()
            val packageName = Regex("""package\s+([\w.]+)""").find(content)?.groupValues?.get(1)
            val className = Regex("""(?:class|object)\s+(\w+)""").find(content)?.groupValues?.get(1) ?: return@forEach
            val fullClassName = if (packageName != null) "$packageName.$className" else className

            val dexFindMatch = Regex("""override\s+fun\s+resolveDex\s*\(""").find(content)
            if (dexFindMatch != null) {
                val start = content.indexOf('{', dexFindMatch.range.last)
                if (start != -1) {
                    var count = 0
                    for (i in start until content.length) {
                        if (content[i] == '{') count++ else if (content[i] == '}') count--
                        if (count == 0) {
                            val body = content.substring(start, i + 1)
                            val hash = MessageDigest.getInstance("MD5").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
                            hashMap[fullClassName] = hash
                            break
                        }
                    }
                }
            }
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText("""
            package moe.ouom.wekit.dexkit.cache
            object GeneratedMethodHashes {
                private val hashes = mapOf(${hashMap.entries.sortedBy { it.key }.joinToString(",") { "\"${it.key}\" to \"${it.value}\"" }})
                fun getHash(className: String) = hashes[className] ?: ""
            }
        """.trimIndent())
    }
}

val generateMethodHashes = tasks.register<GenerateMethodHashesTask>("generateMethodHashes") {
    group = "wekit"
    sourceDir.set(file("src/main/java"))
    outputDir.set(layout.buildDirectory.dir("generated/source/methodhashes"))
}

val rustProjectDir = file("src/main/rust/wekit-native")
val rustLibName    = "libwekit_native.so"

val abiToTarget = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64"    to "x86_64-linux-android",
)
val cargoTasks = abiToTarget.map { (abi, target) ->
    tasks.register<Exec>("cargoBuild_${abi.replace('-', '_')}") {
        group       = "rust"
        description = "Compile Rust for $abi"
        workingDir  = rustProjectDir
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

// --- end tasks ---

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.kyant0.backdrop)
    implementation(libs.kyant0.shapes)

    implementation(libs.kotlinx.io.jvm)
    implementation(libs.gson)
    implementation(libs.google.guava)
    implementation(libs.google.protobuf.java)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mmkv)
    implementation(libs.fastjson2)

    implementation(libs.silkdecoder)

    compileOnly(libs.legacyxposed.api)
    compileOnly(project(":libs:common:libxposed-api"))
    implementation(libs.libxposed.service)
    implementation(libs.dexlib2)
    implementation(libs.dexkit)
    implementation(libs.hiddenApiBypass)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.libsu.core)
    implementation(project(":libs:common:annotation-scanner"))
    ksp(project(":libs:common:annotation-scanner"))

    implementation(libs.material.dialogs.core)
    implementation(libs.material.dialogs.input)

    implementation(libs.dalvik.dx)
    implementation(libs.okhttp3.okhttp)

    implementation(libs.rhino.android)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)
    implementation(libs.markwon.image)
    implementation(libs.markwon.linkify)
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

    implementation(project(":libs:external:nameof-kt:api"))
}

// markwon conflict
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
}

evaluationDependsOn(":libs:external:nameof-kt:plugin")
tasks.withType<KotlinJvmCompile>().configureEach {
    val pluginJarTask = project(":libs:external:nameof-kt:plugin").tasks.named<org.gradle.jvm.tasks.Jar>("jar")
    dependsOn(pluginJarTask)

    compilerOptions {
        val pluginJarPath = pluginJarTask.get().archiveFile.get().asFile.absolutePath
        freeCompilerArgs.add("-Xplugin=$pluginJarPath")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}
