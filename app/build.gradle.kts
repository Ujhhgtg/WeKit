import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.lang.Long.toHexString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.security.MessageDigest

// 定义生成的头文件路径
val secretsHeaderDir = file("src/main/cpp/include")
val generatedHeaderFile = file("src/main/cpp/include/generated_checksums.h")
val MAGIC_PLACEHOLDER_INT = 0x1A2B3C4D
val MAGIC_BYTES = ByteBuffer.allocate(4)
    .order(ByteOrder.LITTLE_ENDIAN)
    .putInt(MAGIC_PLACEHOLDER_INT)
    .array()

// 定义用于定位结构体的魔数
val INTEGRITY_MAGIC_TAG = 0xFEEDDEAD.toInt()
// 定义用于混淆的异或 Key
val INTEGRITY_XOR_KEY = 0x5A5A5A5A

class Elf64Parser(val file: File) {
    private val buffer = file.readBytes()
    private val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

    // Key: Section Index, Value: Pair(sh_addr, sh_offset)
    private val sectionMap = mutableMapOf<Int, Pair<Long, Long>>()
    // Key: Symbol Section Index, Value: String Table Section Index
    private val linkMap = mutableMapOf<Int, Int>()

    init {
        parseSections()
    }

    private fun parseSections() {
        if (byteBuffer.getInt(0) != 0x464C457F) throw GradleException("Not an ELF file")
        if (byteBuffer.get(4).toInt() != 2) throw GradleException("Not a 64-bit ELF file")

        val shOff = byteBuffer.getLong(0x28)
        val shEntSize = byteBuffer.getShort(0x3A).toInt()
        val shNum = byteBuffer.getShort(0x3C).toInt()

        for (i in 0 until shNum) {
            val offset = (shOff + i * shEntSize).toInt()
            val shType = byteBuffer.getInt(offset + 0x04)
            val shAddr = byteBuffer.getLong(offset + 0x10)
            val shOffset = byteBuffer.getLong(offset + 0x18)
            val shLink = byteBuffer.getInt(offset + 0x28)

            sectionMap[i] = Pair(shAddr, shOffset)

            // SHT_SYMTAB (2) or SHT_DYNSYM (11)
            if (shType == 2 || shType == 11) {
                linkMap[i] = shLink
            }
        }
    }

    fun getSymbolFileOffset(symbolName: String): Pair<Long, Long>? {
        val shOff = byteBuffer.getLong(0x28)
        val shEntSize = byteBuffer.getShort(0x3A).toInt()
        val shNum = byteBuffer.getShort(0x3C).toInt()

        // 遍历所有 Section，寻找符号表，SHT_SYMTAB=2 或 SHT_DYNSYM=11
        for (i in 0 until shNum) {
            val secOffset = (shOff + i * shEntSize).toInt()
            val type = byteBuffer.getInt(secOffset + 0x04)

            if (type == 2 || type == 11) { // .symtab OR .dynsym
                val symTabOff = byteBuffer.getLong(secOffset + 0x18)
                val symTabSize = byteBuffer.getLong(secOffset + 0x20)
                val symEntSize = byteBuffer.getLong(secOffset + 0x38)

                // 获取对应的字符串表位置
                val strTabIdx = linkMap[i] ?: continue
                val strTabInfo = sectionMap[strTabIdx] ?: continue
                val strTabFileOff = strTabInfo.second

                val symCount = (symTabSize / symEntSize).toInt()

                // 遍历该表中的符号
                for (j in 0 until symCount) {
                    val symOff = (symTabOff + j * symEntSize).toInt()
                    val stNameIdx = byteBuffer.getInt(symOff)

                    // 读取符号名
                    val name = getString(strTabFileOff.toInt() + stNameIdx)

                    if (name == symbolName) {
                        val stShndx = byteBuffer.getShort(symOff + 0x06).toInt() and 0xFFFF
                        val stValue = byteBuffer.getLong(symOff + 0x08)
                        val stSize = byteBuffer.getLong(symOff + 0x10)

                        val sectionInfo = sectionMap[stShndx]
                        if (sectionInfo != null) {
                            val (secAddr, secOffset) = sectionInfo
                            // 虚拟地址 - Section 虚拟地址 + Section 文件偏移
                            val fileOffset = stValue - secAddr + secOffset
                            return Pair(fileOffset, stSize)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun getString(offset: Int): String {
        if (offset >= buffer.size) return ""
        val sb = StringBuilder()
        var idx = offset
        while (idx < buffer.size) {
            val c = buffer[idx].toInt().toChar()
            if (c == '\u0000') break
            sb.append(c)
            idx++
        }
        return sb.toString()
    }
}

// FNV-1a Hash 算法
fun calcFnv1aHash(bytes: ByteArray): Int {
    var hash = 0x811C9DC5.toInt()
    for (b in bytes) {
        hash = hash xor (b.toInt() and 0xFF)
        hash = hash * 0x01000193
    }
    return hash
}


val sensitivePackagePath = "moe/ouom/wekit/hooks"

plugins {
    id("build-logic.android.application")
    alias(libs.plugins.protobuf)
    alias(libs.plugins.serialization)
    alias(libs.plugins.android.application)
    id("com.google.devtools.ksp") version "2.3.4"
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 生成方法hash的任务
tasks.register("generateMethodHashes") {
    group = "wekit"
    description = "Generate hash values for all IDexFind implementations at compile time"

    val sourceDir = file("src/main/java")
    val outputDir = file("build/generated/source/methodhashes")
    val outputFile = file("$outputDir/moe/ouom/wekit/dexkit/cache/GeneratedMethodHashes.kt")

    inputs.dir(sourceDir)
    outputs.file(outputFile)

    doLast {
        println("🔍 [MethodHash] Scanning for IDexFind implementations...")

        val hashMap = mutableMapOf<String, String>()

        // 扫描所有Kotlin文件
        sourceDir.walk()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val content = file.readText()

                // 检查是否实现了 IDexFind 接口
                if (content.contains("IDexFind")) {
                    // 提取类名
                    val classNameRegex = Regex("""(?:class|object)\s+(\w+)""")
                    val match = classNameRegex.find(content)

                    if (match != null) {
                        val className = match.groupValues[1]

                        // 计算文件内容的MD5 hash
                        val md = MessageDigest.getInstance("MD5")
                        val digest = md.digest(content.toByteArray())
                        val hash = digest.joinToString("") { byte -> "%02x".format(byte) }

                        // 获取完整类名
                        val packageRegex = Regex("""package\s+([\w.]+)""")
                        val packageMatch = packageRegex.find(content)
                        val fullClassName = if (packageMatch != null) {
                            "${packageMatch.groupValues[1]}.$className"
                        } else {
                            className
                        }

                        hashMap[fullClassName] = hash
                        println("   ✅ $fullClassName -> $hash")
                    }
                }
            }

        if (hashMap.isEmpty()) {
            println("   ⚠️ No IDexFind implementations found")
        } else {
            println("   📊 Total: ${hashMap.size} classes")
        }

        // 生成Kotlin文件
        outputFile.parentFile.mkdirs()

        val mapEntries = hashMap.entries.joinToString(",\n        ") { (className, hash) ->
            "\"$className\" to \"$hash\""
        }

        outputFile.writeText("""
            // AUTOMATICALLY GENERATED BY GRADLE TASK: generateMethodHashes
            // Generated at: ${System.currentTimeMillis()}
            // DO NOT EDIT THIS FILE MANUALLY
            package moe.ouom.wekit.dexkit.cache
            object GeneratedMethodHashes {
                val hashes: Map<String, String> = mapOf(
                    $mapEntries
                )
                fun getHash(className: String): String {
                    return hashes[className] ?: ""
                }
            }
        """.trimIndent())

        println("✅ [MethodHash] Generated: ${outputFile.absolutePath}")
    }
}


android {
    namespace = "moe.ouom.wekit"
    compileSdk = 36

    val buildUUID = UUID.randomUUID()
    println(
        """
        __        __  _____   _  __  ___   _____ 
         \ \      / / | ____| | |/ / |_ _| |_   _|
          \ \ /\ / /  |  _|   | ' /   | |    | |  
           \ V  V /   | |___  | . \   | |    | |  
            \_/\_/    |_____| |_|\_\ |___|   |_|  
                                              
                        [WECHAT KIT] WeChat, Now with Superpowers
        """
    )

    println("buildUUID: $buildUUID")

    signingConfigs {
        create("release") {
            val storePath = project.findProperty("KEYSTORE_FILE") as String? ?: "wekit.jks"
            val resolved = file(storePath)
            if (resolved.exists()) {
                storeFile = resolved
                storePassword = project.findProperty("KEYSTORE_PASSWORD") as String? ?: ""
                keyAlias = project.findProperty("KEY_ALIAS") as String? ?: "key0"
                keyPassword = project.findProperty("KEY_PASSWORD") as String? ?: ""
            } else {
                println("🔐 Release keystore not found at '${resolved.path}'. Will fallback for PR/builds without secrets.")
            }
        }
    }


    defaultConfig {
        applicationId = "moe.ouom.wekit"
        buildConfigField("String", "BUILD_UUID", "\"${buildUUID}\"")
        buildConfigField("String", "TAG", "\"[WeKit-TAG]\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                cppFlags("-I${project.file("src/main/cpp/include")}")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        val releaseSigning = signingConfigs.getByName("release")
        val debugSigning = signingConfigs.getByName("debug")

        release {
            isMinifyEnabled = true
            isShrinkResources = true

            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                println("✅ No release keystore detected; using DEBUG signing for release variant (PR-friendly).")
                debugSigning
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("17"))
        }
    }

    packaging {
        resources.excludes += listOf(
            "kotlin/**",
            "**.bin",
            "kotlin-tooling-metadata.json"
        )
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    androidResources {
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
        File(outputDir, sensitivePackagePath).exists()
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
val killWeChat = tasks.register("kill-wechat") {
    group = "wekit"
    description = "Force-stop WeChat on a connected device; skips gracefully if none."
    onlyIf { hasConnectedDevice() }
    doLast {
        val adbFile = adbProvider.orNull?.asFile ?: return@doLast
        for (i in 1..10) {  // 貌似国内定制系统中的的微信一次杀不死？
            project.exec {
                commandLine(adbFile, "shell", "am", "force-stop", packageName)
                isIgnoreExitValue = true
                standardOutput = ByteArrayOutputStream(); errorOutput = ByteArrayOutputStream()
            }
        }

        logger.lifecycle("✅  kill-wechat executed.")
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
    if (!hasConnectedDevice()) logger.lifecycle("⚠️  No device detected — all install tasks skipped")
}

android.applicationVariants.all {
    outputs.all {
        if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
            val config = project.android.defaultConfig
            val versionName = config.versionName
            this.outputFileName = "WeKit-RELEASE-${versionName}.apk"
        }
    }
}

fun getDexIndex(name: String): Int {
    if (name == "classes.dex") return 1
    val number = name.substringAfter("classes").substringBefore(".dex")
    return if (number.isEmpty()) 1 else number.toInt()
}

tasks.register("generateDexChecksum") {
    group = "wekit"
    description = "Calculates CRC32 of ALL classes*.dex files and updates generated_checksums.h"
    outputs.file(generatedHeaderFile)
    outputs.upToDateWhen { false }
    mustRunAfter(tasks.named("minifyReleaseWithR8"))

    doLast {
        // 获取 R8/混淆后的输出目录
        val dexDir = layout.buildDirectory.dir("intermediates/dex/release/minifyReleaseWithR8").get().asFile

        // 查找所有 classes*.dex 文件
        val dexFiles = dexDir.walk()
            .filter { it.isFile && it.name.matches(Regex("classes\\d*\\.dex")) }
            .toList()

        if (dexFiles.isEmpty()) {
            println("⚠️ [WeKit] No dex files found in ${dexDir.absolutePath}. Writing placeholders.")
            if (!secretsHeaderDir.exists()) secretsHeaderDir.mkdirs()
            generatedHeaderFile.writeText("""
                #pragma once
                #include <vector>
                #include <cstdint>
                static const int EXPECTED_DEX_COUNT = 0;
                static const uint32_t EXPECTED_DEX_CRCS[] = {};
            """.trimIndent())
            return@doLast
        }

        val sortedDexFiles = dexFiles.sortedWith(Comparator { f1, f2 ->
            val n1 = getDexIndex(f1.name)
            val n2 = getDexIndex(f2.name)
            n1.compareTo(n2)
        })

        val crcList = mutableListOf<String>()
        var totalSize: Long = 0

        println("🔒 [WeKit] Found ${sortedDexFiles.size} DEX files. Calculating Checksums...")

        sortedDexFiles.forEach { file ->
            val crc = CRC32()
            crc.update(file.readBytes())
            val hexCrc = "0x${toHexString(crc.value).uppercase()}"
            crcList.add(hexCrc)
            totalSize += file.length()
            println("   -> ${file.name}: $hexCrc (Size: ${file.length()})")
        }

        val cppArrayContent = crcList.joinToString(", ")

        if (!secretsHeaderDir.exists()) secretsHeaderDir.mkdirs()

        generatedHeaderFile.writeText("""
            // AUTOMATICALLY GENERATED BY GRADLE
            // Generated at: ${System.currentTimeMillis()}
            
            #pragma once
            #include <vector>
            #include <cstdint>
            
            static const int EXPECTED_DEX_COUNT = ${sortedDexFiles.size};
            static const uint32_t EXPECTED_DEX_CRCS[] = { $cppArrayContent };
        """.trimIndent())
    }
}

tasks.register("patchSoSize") {
    group = "wekit"
    description = "Injects the real SO file size into the binary by replacing the placeholder."

    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val libDir = buildDir.resolve("intermediates/stripped_native_libs/release/out/lib")

        if (!libDir.exists()) {
            println("⚠️ [WeKit-Patch] Native lib dir not found. Build might have failed or path changed.")
            return@doLast
        }

        libDir.walk().filter { it.isFile && it.name == "libwekit.so" }.forEach { soFile ->
            val content = soFile.readBytes()
            val fileSize = soFile.length().toInt()

            var patchOffset = -1
            for (i in 0 until content.size - 4) {
                if (content[i] == MAGIC_BYTES[0] &&
                    content[i + 1] == MAGIC_BYTES[1] &&
                    content[i + 2] == MAGIC_BYTES[2] &&
                    content[i + 3] == MAGIC_BYTES[3]) {
                    patchOffset = i
                    break
                }
            }

            if (patchOffset != -1) {
                println("💉 [WeKit-Patch] Patching ${soFile.absolutePath}")
                println("   -> Found placeholder at offset: $patchOffset")
                println("   -> Injecting size: $fileSize bytes")

                val sizeBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(fileSize)
                    .array()

                RandomAccessFile(soFile, "rw").use { raf ->
                    raf.seek(patchOffset.toLong())
                    raf.write(sizeBytes)
                }
            } else {
                println("❌ [WeKit-Patch] Placeholder NOT found in ${soFile.absolutePath}. Optimizer might have killed it?")
            }
        }
    }
}

tasks.register("protectSensitiveCode") {
    group = "wekit-protection"

    val variantName = "Release"
    val javacTask = tasks.findByName("compile${variantName}JavaWithJavac")
    val kotlincTask = tasks.findByName("compile${variantName}Kotlin")
    val r8Task = tasks.findByName("minify${variantName}WithR8")

    dependsOn(javacTask, kotlincTask)
    r8Task?.mustRunAfter(this)

    val headerFile = file("src/main/cpp/include/generated_hidden_dex.h")

    doLast {
        val classFiles = mutableListOf<File>()
        val packagePath = sensitivePackagePath

        val buildDir = layout.buildDirectory.asFile.get()
        val searchDirs = listOf(
            File(buildDir, "intermediates/javac/release/compileReleaseJavaWithJavac/classes"),
            File(buildDir, "tmp/kotlin-classes/release")
        )

        searchDirs.forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().forEach { file ->
                    if (file.isFile && file.extension == "class") {
                        val normalizedPath = file.absolutePath.replace('\\', '/')
                        val isHookPackage = normalizedPath.contains("moe/ouom/wekit/hooks")
                        val isPublicPackage = normalizedPath.contains("/_")

                        if (isHookPackage && !isPublicPackage) {
                            classFiles.add(file)
                        }

                        if (normalizedPath.contains("StringsKt")) {
                            classFiles.add(file)
                        }
                    }
                }
            }
        }

        if (classFiles.isEmpty()) {
            println("❌ [Protect] ERROR: Still no classes in $packagePath")
            return@doLast
        }

        println("✅ [Protect] Intercepted ${classFiles.size} classes BEFORE R8/Multi-DEX.")

        val tempDir = file("${buildDir}/tmp/hidden_dex_build")
        tempDir.deleteRecursively(); tempDir.mkdirs()

        val d8Name = if (System.getProperty("os.name").lowercase().contains("win")) "d8.bat" else "d8"
        val d8Path = "${android.sdkDirectory}/build-tools/${android.buildToolsVersion}/$d8Name"

        project.exec {
            commandLine(d8Path, "--release", "--min-api", "26", "--output", tempDir.absolutePath, *classFiles.map { it.absolutePath }.toTypedArray())
        }

        val dexFile = File(tempDir, "classes.dex")
        if (dexFile.exists()) {
            val bytes = dexFile.readBytes()
            val xorKey = 0x32.toByte()
            val hexData = bytes.map { (it.toInt() xor xorKey.toInt()).toByte() }.joinToString(",") { "0x%02x".format(it) }

            headerFile.writeText("""
                // AUTOMATICALLY GENERATED BY GRADLE
                // Generated at: ${System.currentTimeMillis()}
            
                #pragma once
                static const int HIDDEN_DEX_SIZE = ${bytes.size};
                static const unsigned char HIDDEN_DEX_KEY = 0x${"%02x".format(xorKey)};
                static const unsigned char HIDDEN_DEX_DATA[] = { $hexData };
            """.trimIndent())

            println("🚀 [Protect] Hidden DEX generated: ${bytes.size} bytes.")

            classFiles.forEach { it.delete() }
            println("🗑️ [Protect] Deleted original classes to exclude them from Multi-DEX.")
        }
    }
}

// =========================================================================

/**
 * 模块 A: 配置 Java/Kotlin 层的保护
 * 包含: 代码隐藏 (protectSensitiveCode), R8 混淆, DEX Checksum 生成, 以及 CMake 依赖绑定
 */
fun Project.configureJavaProtection(
    variantName: String,
    javacTask: Task?,
    r8Task: Task?,
    protectTask: Task?,
    checksumTask: Task?
) {
    // 绑定 Protect Task 到编译流程
    if (javacTask != null && protectTask != null) {
        protectTask.dependsOn(javacTask)
        r8Task?.mustRunAfter(protectTask)
    }

    // 绑定 Checksum Task 并锁定 CMake 编译
    if (r8Task != null && checksumTask != null) {
        checksumTask.dependsOn(r8Task)

        // 让 CMake 任务依赖于 DEX Checksum (因为 C++ 需要生成的 generated_checksums.h)
        tasks.configureEach {
            val taskName = this.name
            // 匹配 Release 版的 CMake 构建任务
            if ((taskName.startsWith("buildCMake") || taskName.startsWith("configureCMake"))
                && taskName.contains("Rel")) {

                if (protectTask != null) {
                    this.dependsOn(protectTask)
                    println("   🔒 Task '$taskName' locked on protectSensitiveCode")
                }

                println("   🔒 Task '$taskName' locked on Dex Checksum")
                this.dependsOn(checksumTask)
            }
        }
    }
}

/**
 * 模块 B: 配置 Native (.so) 层的补丁
 * 包含: SO Size 注入, 代码完整性 Hash 注入
 * 顺序: Strip -> Patch Size -> Patch Integrity -> Package
 */
fun Project.configureNativePatching(
    variantName: String,
    stripTask: Task,
    packageTask: Task
) {
    // 注册 SO Size Patch 任务
    val patchSizeTaskName = "patchSoSize${variantName}"
    val patchSizeTask = if (tasks.findByName(patchSizeTaskName) == null) {
        tasks.register(patchSizeTaskName) {
            group = "wekit-protection"
            description = "Injects SO size for $variantName"
            dependsOn(stripTask)

            doLast {
                println("💉 [Patch-Size] Starting...")
                val searchDirs = stripTask.outputs.files.files.filter { it.exists() && it.isDirectory }
                if (searchDirs.isEmpty()) return@doLast

                var count = 0
                searchDirs.forEach { dir ->
                    dir.walk().filter { it.isFile && it.name == "libwekit.so" }.forEach { soFile ->
                        val content = soFile.readBytes()
                        var offset = -1
                        for (i in 0 until content.size - 4) {
                            if (content[i] == MAGIC_BYTES[0] && content[i+1] == MAGIC_BYTES[1] &&
                                content[i+2] == MAGIC_BYTES[2] && content[i+3] == MAGIC_BYTES[3]) {
                                offset = i; break
                            }
                        }
                        if (offset != -1) {
                            val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(soFile.length().toInt()).array()
                            RandomAccessFile(soFile, "rw").use { raf -> raf.seek(offset.toLong()); raf.write(sizeBytes) }
                            count++
                        }
                    }
                }
                println(if (count > 0) "   ✅ Patched $count SO files." else "   ⚠️ No placeholders found.")
            }
        }
    } else tasks.named(patchSizeTaskName)

    // 注册 Integrity Hash Patch 任务
    val patchIntegrityTaskName = "patchNativeIntegrity${variantName}"
    val patchIntegrityTask = if (tasks.findByName(patchIntegrityTaskName) == null) {
        tasks.register(patchIntegrityTaskName) {
            group = "wekit-protection"
            description = "Injects code hash for $variantName using Unstripped source"

            dependsOn(stripTask)
            mustRunAfter(patchSizeTask)

            doLast {
                println("🛡️ [Patch-Integrity] Starting...")

                // 这里是 Strip 任务的输出目录，包含的是已经去符号的 Stripped SO
                val strippedDirs = stripTask.outputs.files.files.filter { it.exists() && it.isDirectory }

                strippedDirs.forEach { strippedDir ->
                    strippedDir.walk().filter { it.isFile && it.name == "libwekit.so" }.forEach { strippedSoFile ->
                        println("   -> Target (Stripped): ${strippedSoFile.absolutePath}")

                        var unstrippedSoFile: File? = null
                        val buildDir = layout.buildDirectory.get().asFile
                        val archName = strippedSoFile.parentFile.name // e.g., arm64-v8a

                        // 尝试在 merged_native_libs 中查找
                        val candidate1 = File(strippedSoFile.absolutePath.replace("stripped_native_libs", "merged_native_libs"))
                        if (candidate1.exists()) {
                            unstrippedSoFile = candidate1
                        } else {
                            // 暴力在 build 目录找同名且体积比 stripped 大的文件
                            val candidates = buildDir.walk()
                                .filter { it.isFile && it.name == "libwekit.so" && it.parentFile.name == archName && it.length() > strippedSoFile.length() }
                                .sortedByDescending { it.length() } // 最大的通常是 unstripped
                                .toList()

                            if (candidates.isNotEmpty()) {
                                unstrippedSoFile = candidates.first()
                            }
                        }

                        if (unstrippedSoFile == null || !unstrippedSoFile.exists()) {
                            println("      ❌ Critical: Could not find Unstripped SO! Hash injection skipped.")
                            return@forEach
                        }

                        println("      Source (Unstripped): ${unstrippedSoFile.absolutePath}")

                        try {
                            // 使用升级版 Parser 解析 Unstripped 文件 (包含 .symtab)
                            val parser = Elf64Parser(unstrippedSoFile)

                            val symbolInfo = parser.getSymbolFileOffset("nc")
                            val endSymbolInfo = parser.getSymbolFileOffset("nc_end")

                            if (symbolInfo != null) {
                                val funcOffset = symbolInfo.first

                                // 动态计算大小：使用 nc_end 的位置减去 nc 的位置
                                var scanSize = 256L
                                if (endSymbolInfo != null && endSymbolInfo.first > funcOffset) {
                                    scanSize = endSymbolInfo.first - funcOffset
                                    println("      Dynamic Size: $scanSize bytes (nc -> nc_end)")
                                } else if (symbolInfo.second > 0) {
                                    scanSize = symbolInfo.second
                                    println("      Symbol Size: $scanSize bytes")
                                } else {
                                    println("      ⚠️ Warning: Using fixed size 256 bytes (nc_end not found)")
                                }

                                // 读取 Unstripped 文件的机器码计算 Hash
                                // 虽然 Unstripped 包含符号，但 .text 段的机器码与 Stripped 文件是完全一致的
                                val fileBytes = unstrippedSoFile.readBytes()

                                if (funcOffset + scanSize <= fileBytes.size) {
                                    val codeBytes = fileBytes.copyOfRange(funcOffset.toInt(), (funcOffset + scanSize).toInt())
                                    val hash = calcFnv1aHash(codeBytes)
                                    println("      Calculated Hash: 0x${Integer.toHexString(hash).uppercase()}")

                                    // 将计算结果注入到 Stripped 文件中
                                    val targetBytes = strippedSoFile.readBytes()

                                    val magicBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(INTEGRITY_MAGIC_TAG).array()
                                    var placeOffset = -1
                                    for (i in 0 until targetBytes.size - 16) {
                                        if (targetBytes[i] == magicBytes[0] && targetBytes[i+1] == magicBytes[1] &&
                                            targetBytes[i+2] == magicBytes[2] && targetBytes[i+3] == magicBytes[3]) {
                                            placeOffset = i; break
                                        }
                                    }

                                    if (placeOffset != -1) {
                                        val part1 = hash xor INTEGRITY_XOR_KEY
                                        val part2 = hash.inv()
                                        val noise = (System.currentTimeMillis() % 0xFFFFFFFF).toInt()
                                        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                                        buffer.putInt(INTEGRITY_MAGIC_TAG); buffer.putInt(part1); buffer.putInt(part2); buffer.putInt(noise)

                                        RandomAccessFile(strippedSoFile, "rw").use { raf -> raf.seek(placeOffset.toLong()); raf.write(buffer.array()) }
                                        println("      ✅ Injected hash successfully.")
                                    } else println("      ❌ Placeholder 0xFEEDDEAD not found in target.")
                                } else println("      ❌ Offset out of bounds.")
                            } else println("      ⚠️ Symbol 'nc' not found in Unstripped file (Check if 'hidden' is compiled correctly).")
                        } catch (e: Exception) {
                            println("      ❌ Error: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    } else tasks.named(patchIntegrityTaskName)

    // 绑定顺序
    packageTask.dependsOn(patchSizeTask)
    packageTask.dependsOn(patchIntegrityTask)
}

// 配置 generateMethodHashes 任务在 Kotlin 编译之前执行
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("generateMethodHashes")
}

// 配置 KSP 任务也依赖于 generateMethodHashes
tasks.matching { it.name.startsWith("ksp") && it.name.contains("Kotlin") }.configureEach {
    dependsOn("generateMethodHashes")
}

afterEvaluate {
    android.applicationVariants.forEach { variant ->
        // 只针对 Release 版本进行 Hook
        if (variant.buildType.name.equals("release", ignoreCase = true)) {
            val variantName = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            println("⚙️ [WeKit] Configuring tasks for: $variantName")

            // 获取所有需要的 Task
            val javacTask = tasks.findByName("compile${variantName}JavaWithJavac")
            val r8Task = tasks.findByName("minify${variantName}WithR8")
            val checksumTask = tasks.findByName("generateDexChecksum")
            val protectTask = tasks.findByName("protectSensitiveCode")

            val stripTask = tasks.findByName("strip${variantName}DebugSymbols")
            val packageTask = tasks.findByName("package${variantName}")

            // 配置 Java 层保护
            configureJavaProtection(variantName, javacTask, r8Task, protectTask, checksumTask)

            // 配置 Native 层补丁 (如果 Strip 和 Package 任务存在)
            if (stripTask != null && packageTask != null) {
                configureNativePatching(variantName, stripTask, packageTask)
            }
        }
    }
}

// =========================================================================



kotlin {
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
    implementation(libs.core.ktx)

    implementation(libs.appcompat)

    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout) { exclude("androidx.appcompat", "appcompat") }

    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.io.jvm)
    implementation(libs.dexkit)
    implementation(libs.hiddenapibypass)
    implementation(libs.gson)

    implementation(ktor("serialization", "kotlinx-json"))
    implementation(grpc("protobuf", "1.62.2"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.mmkv)
    implementation(projects.libs.common.libxposed.service)

    compileOnly(libs.xposed)
    compileOnly(projects.libs.common.libxposed.api)

    implementation(libs.dexlib2)
    implementation(libs.google.guava)
    implementation(libs.google.protobuf.java)
    implementation(libs.kotlinx.serialization.protobuf)

    implementation(libs.sealedEnum.runtime)
    ksp(libs.sealedEnum.ksp)
    implementation(projects.libs.common.annotationScanner)
    ksp(projects.libs.common.annotationScanner)

    implementation(libs.material.preference)
    implementation(libs.dev.appcompat)

    implementation(libs.recyclerview)

    implementation(libs.material.dialogs.core)
    implementation(libs.material.dialogs.input)
    implementation(libs.preference)
    implementation(libs.fastjson2)

    // 因为放弃了资源注入，此库不再使用
//    implementation(projects.libs.ui.xView)

    implementation(libs.glide)
    implementation(libs.byte.buddy)
    implementation(libs.dalvik.dx)
    implementation(libs.okhttp3.okhttp)
    implementation(libs.markdown.core)
    implementation(libs.blurview)
    implementation(libs.hutool.core)
    implementation(libs.nanohttpd)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

//    implementation(libs.jackson.databind)
//    implementation(libs.jackson.core)
//    implementation(libs.stax.api)
//    implementation(libs.woodstox.core)
}