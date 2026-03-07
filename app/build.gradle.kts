import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.internal.api.ApkVariantOutputImpl
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
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

configure<ApplicationExtension> {
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
                                              
            [WEKIT] WeChat, now with superpowers
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
        buildConfigField("String", "TAG", "\"WeKit\"")
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
            "kotlin-tooling-metadata.json"
        )
        resources.merges += listOf(
            "META-INF/xposed/*",
            "org/mozilla/javascript/**"
        )
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += setOf("zh", "en")
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

tasks.withType<KotlinCompile>().configureEach {
    exclude("**/scripts/**")
}

val adbProvider = androidComponents.sdkComponents.adb

androidComponents {
    onVariants { variant ->
        val kotlinSources = variant.sources.kotlin ?: return@onVariants

        kotlinSources.addGeneratedSourceDirectory(
            generateMethodHashes,
            GenerateMethodHashesTask::outputDir
        )

        kotlinSources.addGeneratedSourceDirectory(
            embedBuiltinJavaScript,
            EmbedJsTask::outputDir
        )
    }

    onVariants { variant ->
        if (!variant.debuggable) return@onVariants

        val vName = variant.name
        val vCap = vName.capitalizeUS()
        val installTaskName = "install$vCap"

        val installAndRestart = tasks.register("install${vCap}AndRestartWeChat") {
            group = "wekit"
            description = "Installs ${variant.name} and force-stops WeChat"

            dependsOn(installTaskName)
            finalizedBy(killWeChat)

            onlyIf { hasConnectedDevice() }
        }

        tasks.matching { it.name == "assemble$vCap" }.configureEach {
            finalizedBy(installAndRestart)
        }

        tasks.matching { it.name == installTaskName }.configureEach {
            onlyIf { hasConnectedDevice() }
        }
    }

    onVariants { variant ->
        val buildTypeName = variant.buildType?.uppercase()
        variant.outputs.forEach { output ->
            if (this is ApkVariantOutputImpl) {
                val config = project.android.defaultConfig
                val versionName = config.versionName
                (output as ApkVariantOutputImpl).outputFileName = "WeKit-${buildTypeName}-${versionName}.apk"
            }
        }
    }
}

gradle.taskGraph.whenReady {
    if (!hasConnectedDevice()) {
        println("⚠️ No device detected — all install tasks will be skipped")
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

fun hasConnectedDevice(): Boolean {
    val adbPath = adbProvider.orNull?.asFile?.absolutePath ?: return false
    return runCatching {
        val proc = ProcessBuilder(adbPath, "devices").redirectErrorStream(true).start()
        proc.waitFor(5, TimeUnit.SECONDS)
        proc.inputStream.bufferedReader().readLines().any { it.trim().endsWith("\tdevice") }
    }.getOrElse { false }
}

val killWeChat: TaskProvider<Task> = tasks.register("killWeChat") {
    group = "wekit"
    description = "Force-stop WeChat on a connected device; skips gracefully if none."
    onlyIf { hasConnectedDevice() }
    val execOperations = project.serviceOf<ExecOperations>()
    doLast {
        val adbFile = adbProvider.orNull?.asFile ?: return@doLast
        execOperations.exec {
            commandLine(adbFile, "shell", "am", "force-stop", "com.tencent.mm")
            isIgnoreExitValue = true
            standardOutput = ByteArrayOutputStream(); errorOutput = ByteArrayOutputStream()
        }

        logger.lifecycle("✅ killWeChat executed.")
    }
}

fun String.capitalizeUS() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

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
        srcDir.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("IDexFind") }.forEach { file ->
            val content = file.readText()
            val packageName = Regex("""package\s+([\w.]+)""").find(content)?.groupValues?.get(1)
            val className = Regex("""(?:class|object)\s+(\w+)""").find(content)?.groupValues?.get(1) ?: return@forEach
            val fullClassName = if (packageName != null) "$packageName.$className" else className

            // 你的逻辑保持不变...
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

val generateMethodHashes = tasks.register<GenerateMethodHashesTask>("generateMethodHashes") {
    group = "wekit"
    sourceDir.set(file("src/main/java"))
    outputDir.set(layout.buildDirectory.dir("generated/source/methodhashes"))
}

abstract class EmbedJsTask : DefaultTask() {
    @get:InputFile
    abstract val sourceJsFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val jsContent = sourceJsFile.get().asFile.readText()
        val outDir = outputDir.get().asFile
        val outputFile = outDir.resolve("moe/ouom/wekit/hooks/items/scripting_js/BuiltinJs.kt")

        val ktCode = """
            package moe.ouom.wekit.hooks.item.scripting_js

            object EmbeddedBuiltinJs {
                const val SCRIPT: String = ""${'"'}
$jsContent
""${'"'}
            }
        """.trimIndent()

        outputFile.parentFile.mkdirs()
        outputFile.writeText(ktCode)
    }
}

val embedBuiltinJavaScript = tasks.register<EmbedJsTask>("embedBuiltinJavaScript") {
    group = "wekit"
    sourceJsFile.set(file("src/main/java/moe/ouom/wekit/hooks/items/scripting_js/script.js"))
    outputDir.set(layout.buildDirectory.dir("generated/sources/embeddedJs/kotlin"))
}

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
    implementation(libs.accompanist.drawablepainter)

    implementation(libs.kotlinx.io.jvm)
    implementation(libs.gson)
    implementation(libs.google.guava)
    implementation(libs.google.protobuf.java)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mmkv)
    implementation(libs.fastjson2)

    implementation(libs.silkdecoder)

    compileOnly(libs.xposed.api)
    compileOnly(libs.libxposed.api)
    // 哪个智障发明的 Gradle
    // 不是他 libxposed AndroidManifest package 定义冲突就冲突关你屁事啊
    // 要你管吗
    // FIXME: change this when libxposed is publishes to maven
    implementation(files("../files/libxposed-service-interfaces-classes.jar"))
    implementation(libs.libxposed.service) {
        exclude(group = "com.github.libxposed.service", module = "interface")
    }
    implementation(libs.dexlib2)
    implementation(libs.dexkit)
    implementation(libs.hiddenApiBypass)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.libsu.core)
    implementation(projects.libs.common.annotationScanner)
    ksp(projects.libs.common.annotationScanner)

    implementation(libs.material.dialogs.core)
    implementation(libs.material.dialogs.input)

    implementation(libs.dalvik.dx)
    implementation(libs.okhttp3.okhttp)

    implementation(libs.rhino.android)
    // implementation(libs.kotlin.scripting.common)
    // implementation(libs.kotlin.scripting.jvm)
    // implementation(libs.kotlin.scripting.jvm.host)
    // implementation(libs.kotlin.compiler.embeddable)
    // implementation(libs.kotlinx.coroutines.core)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}
