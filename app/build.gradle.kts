
import org.gradle.internal.extensions.core.serviceOf
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

plugins {
    alias(libs.plugins.protobuf)
    alias(libs.plugins.serialization)
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 生成方法 hash 的任务
val generateMethodHashes = tasks.register("generateMethodHashes") {
    group = "wekit"
    val sourceDir = file("src/main/java")
    val outputDir = layout.buildDirectory.dir("generated/source/methodhashes")
    val outputFile = outputDir.get().file("moe/ouom/wekit/dexkit/cache/GeneratedMethodHashes.kt").asFile

    inputs.dir(sourceDir)
    outputs.dir(outputDir)

    doLast {
        val hashMap = mutableMapOf<String, String>()
        sourceDir.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("IDexFind") }.forEach { file ->
            val content = file.readText()
            val packageName = Regex("""package\s+([\w.]+)""").find(content)?.groupValues?.get(1)
            val className = Regex("""(?:class|object)\s+(\w+)""").find(content)?.groupValues?.get(1) ?: return@forEach
            val fullClassName = if (packageName != null) "$packageName.$className" else className
            val dexFindMatch = Regex("""override\s+fun\s+dexFind\s*\(""").find(content)
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

val embedBuiltinJavaScript = tasks.register("embedBuiltinJavaScript") {
    val sourceFile = file("src/main/java/moe/ouom/wekit/hooks/items/automation/script.js")
    val outputDir = layout.buildDirectory.dir("generated/sources/embeddedJs/kotlin")
    val outputFile = outputDir.map { it.file("moe/ouom/wekit/hooks/items/automation/BuiltinJs.kt") }

    inputs.file(sourceFile)
    outputs.file(outputFile)

    doLast {
        val jsContent = sourceFile.readText()

        val ktCode = """
            package moe.ouom.wekit.hooks.item.automation

            object EmbeddedBuiltinJs {
                const val SCRIPT: String = """ + "\"\"\"\n" + jsContent + "\n\"\"\"" + """
            }
        """

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(ktCode)
        }
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/sources/embeddedJs/kotlin"))
}

private fun getBuildVersionCode(): Int {
    val appVerCode: Int by lazy {
        val versionCode = SimpleDateFormat("yyMMddHH", Locale.ENGLISH).format(Date())
        versionCode.toInt()
    }
    return appVerCode
}

private fun getCurrentDate(): String {
    val sdf = SimpleDateFormat("MMddHHmm", Locale.getDefault())
    return sdf.format(Date())
}

private fun getShortGitRevision(): String {
    val command = "git rev-parse --short HEAD"
    val processBuilder = ProcessBuilder(*command.split(" ").toTypedArray())
    val process = processBuilder.start()

    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    return if (exitCode == 0) {
        output.trim()
    } else {
        "no_commit"
    }
}

private fun getBuildVersionName(): String {
    return "${getShortGitRevision()}.${getCurrentDate()}"
}

android {
    namespace = libs.versions.namespace.get()
    compileSdk = libs.versions.targetSdk.get().toInt()

    val buildUuid = UUID.randomUUID()
    println(
        """
        __        __  _____   _  __  ___   _____ 
         \ \      / / | ____| | |/ / |_ _| |_   _|
          \ \ /\ / /  |  _|   | ' /   | |    | |  
           \ V  V /   | |___  | . \   | |    | |  
            \_/\_/    |_____| |_|\_\ |___|   |_|  
                                              
                        [WECHAT KIT] WeChat, now with superpowers
        """
    )

    println("build uuid: $buildUuid")

    defaultConfig {
        applicationId = libs.versions.namespace.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = getBuildVersionCode()
        versionName = getBuildVersionName()

        buildConfigField("String", "BUILD_UUID", "\"${buildUuid}\"")
        buildConfigField("String", "TAG", "\"[WeKit-TAG]\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")
        // noinspection ChromeOsAbiSupport
        ndk {
            abiFilters += "arm64-v8a"
        }

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                cppFlags("-I${project.file("src/main/cpp/include")}")

                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir(generateMethodHashes)
            val generatedJsDir = layout.buildDirectory.dir("generated/sources/embeddedJs/kotlin")
            kotlin.srcDir(generatedJsDir)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            signingConfig = signingConfigs.getByName("debug")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get()))
        }
        jvmToolchain(libs.versions.jdk.get().toInt())
    }

    packaging {
        resources.excludes += listOf(
            "kotlin/**",
            "**.bin",
            "kotlin-tooling-metadata.json"
        )
        resources {
            merges += "META-INF/xposed/*"
            merges += "org/mozilla/javascript/**"  // 合并 Mozilla Rhino 所有资源
            excludes += "**"
        }
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters.clear()
        localeFilters += setOf("zh", "en")

        additionalParameters += listOf(
            "--allow-reserved-package-id",
            "--package-id", "0x69"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
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

fun String.capitalizeUS() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

val adbProvider = androidComponents.sdkComponents.adb
fun hasConnectedDevice(): Boolean {
    val adbPath = adbProvider.orNull?.asFile?.absolutePath ?: return false
    return runCatching {
        val proc = ProcessBuilder(adbPath, "devices").redirectErrorStream(true).start()
        proc.waitFor(5, TimeUnit.SECONDS)
        proc.inputStream.bufferedReader().readLines().any { it.trim().endsWith("\tdevice") }
    }.getOrElse { false }
}

val packageName = "com.tencent.mm"
val killWeChat = tasks.register("killWeChat") {
    group = "wekit"
    description = "Force-stop WeChat on a connected device; skips gracefully if none."
    onlyIf { hasConnectedDevice() }
    val execOperations = project.serviceOf<ExecOperations>()
    doLast {
        val adbFile = adbProvider.orNull?.asFile ?: return@doLast
        execOperations.exec {
            commandLine(adbFile, "shell", "am", "force-stop", packageName)
            isIgnoreExitValue = true
            standardOutput = ByteArrayOutputStream(); errorOutput = ByteArrayOutputStream()
        }

        logger.lifecycle("✅ killWeChat executed.")
    }
}

androidComponents.onVariants { variant ->
    if (!variant.debuggable) return@onVariants

    val vCap = variant.name.capitalizeUS()
    val installTaskName = "install${vCap}"

    val installAndRestart = tasks.register("install${vCap}AndRestartWeChat") {
        group = "wekit"
        dependsOn(installTaskName)
        finalizedBy(killWeChat)
        onlyIf { hasConnectedDevice() }
    }

    afterEvaluate { tasks.findByName("assemble${vCap}")?.finalizedBy(installAndRestart) }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("install") }.configureEach { onlyIf { hasConnectedDevice() } }
    if (!hasConnectedDevice()) logger.lifecycle("⚠️ No device detected — all install tasks skipped")
}

android.applicationVariants.all {
    val variant = this
    val buildTypeName = variant.buildType.name.uppercase()

    outputs.all {
        if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
            val config = project.android.defaultConfig
            val versionName = config.versionName
            this.outputFileName = "WeKit-${buildTypeName}-${versionName}.apk"
        }
    }
}

// =========================================================================

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateMethodHashes)
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateMethodHashes)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(embedBuiltinJavaScript)
}

tasks.matching { it.name.contains("ksp", ignoreCase = true) }.configureEach {
    dependsOn(embedBuiltinJavaScript)
}

// =========================================================================

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    sourceSets.configureEach {
        kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/$name/kotlin/"))
        // 添加生成的方法hash文件目录
        kotlin.srcDir(layout.buildDirectory.dir("generated/source/methodhashes"))
    }
}

protobuf {
    protoc { artifact = libs.google.protobuf.protoc.get().toString() }
    generateProtoTasks { all().forEach { it.builtins { create("java") { option("lite") } } } }
}

configurations.configureEach { exclude(group = "androidx.appcompat", module = "appcompat") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintLayout) { exclude("androidx.appcompat", "appcompat") }
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
    implementation(libs.accompanist.drawablepainter)

    implementation(libs.kotlinx.io.jvm)
    implementation(libs.gson)
    implementation(libs.grpc.protobuf)
    implementation(libs.google.guava)
    implementation(libs.google.protobuf.java)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mmkv)

    compileOnly(libs.xposed.api)
    compileOnly(projects.libs.common.libxposed.api)
    implementation(projects.libs.common.libxposed.service)
    implementation(libs.dexlib2)
    implementation(libs.dexkit)
    implementation(libs.hiddenApiBypass)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)

    implementation(projects.libs.common.annotationScanner)
    ksp(projects.libs.common.annotationScanner)

    implementation(libs.rikka.rikkax.material.preference)
    implementation(libs.rikka.rikkax.appcompat)


    implementation(libs.material.dialogs.core)
    implementation(libs.material.dialogs.input)
    implementation(libs.androidx.preference)
    implementation(libs.fastjson2)

    implementation(libs.dalvik.dx)
    implementation(libs.okhttp3.okhttp)
    implementation(libs.markwon.core)
    implementation(libs.hutool.core)

    implementation(libs.rhino.android)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}
