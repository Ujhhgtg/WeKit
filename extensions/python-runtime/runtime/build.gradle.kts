import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import java.util.zip.ZipFile

plugins {
    id("com.android.application") version "9.2.0"
    id("com.chaquo.python") version "17.0.0"
}

val apiVersion = providers.gradleProperty("wekitPythonApiVersion").orElse("1.0.0")
val apiRepository = providers.gradleProperty("wekitPythonApiRepo")
    .orElse(System.getenv("WEKIT_PYTHON_API_REPO") ?: "")
val runtimeAgpVersion = "9.2.0"
val runtimeChaquopyVersion = "17.0.0"
val runtimeGradleVersion = "9.7.0"
val runtimeJdkVersion = "21"
val runtimePythonVersion = "3.13"
val runtimeNdkVersion = "30.0.14904198"
val runtimeAbi = "arm64-v8a"

group = "dev.ujhhgtg.wekit.python.runtime"
version = "0.1.0"

configure<ApplicationExtension> {
    namespace = "dev.ujhhgtg.wekit.python.runtime"
    compileSdk = 35
    ndkVersion = runtimeNdkVersion
    defaultConfig {
        applicationId = "dev.ujhhgtg.wekit.python.runtime.container"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += runtimeAbi }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
}

chaquopy {
    defaultConfig {
        version = runtimePythonVersion
    }
}

dependencies {
    // The API is supplied by the controlled local repository and is deliberately
    // compile-only: its classes must be resolved by the base APK's loader.
    compileOnly("dev.ujhhgtg.wekit:python-runtime-api:${apiVersion.get()}")
}

val verifyApiArtifact = tasks.register("verifyPythonRuntimeApiArtifact") {
    group = "verification"
    description = "Requires the versioned API AAR in the controlled local repository."
    doLast {
        check(apiRepository.isPresent && apiRepository.get().isNotBlank()) {
            "wekitPythonApiRepo must point to a controlled local Maven repository"
        }
        val repo = file(apiRepository.get())
        val pom = repo.resolve("dev/ujhhgtg/wekit/python-runtime-api/${apiVersion.get()}/python-runtime-api-${apiVersion.get()}.pom")
        val aar = repo.resolve("dev/ujhhgtg/wekit/python-runtime-api/${apiVersion.get()}/python-runtime-api-${apiVersion.get()}.aar")
        check(pom.isFile && aar.isFile) { "Missing versioned Python runtime API artifact ${apiVersion.get()} under $repo" }
    }
}

fun registerApkVerification(variant: String) = tasks.register("verifyNoDuplicateApiClasses${variant.replaceFirstChar(Char::uppercase)}") {
    group = "verification"
    description = "Fails when the runtime APK packages a duplicate API class."
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/$variant/runtime-$variant.apk").get().asFile
        val unsignedApk = layout.buildDirectory.file("outputs/apk/$variant/runtime-$variant-unsigned.apk").get().asFile
        val selectedApk = apk.takeIf(File::isFile) ?: unsignedApk.takeIf(File::isFile)
        check(selectedApk != null) { "Expected $variant runtime APK is missing: $apk" }
        val sdkRoot = System.getenv("ANDROID_HOME")?.takeIf(String::isNotBlank)
            ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf(String::isNotBlank)
        val analyzer = sdkRoot?.let { File(it, "cmdline-tools/latest/bin/apkanalyzer") }
            ?.takeIf(File::isFile)
        check(analyzer != null) { "apkanalyzer is required to inspect defined runtime DEX classes" }
        val process = ProcessBuilder(analyzer.absolutePath, "dex", "packages", "--defined-only", selectedApk.absolutePath)
            .redirectErrorStream(true)
            .start()
        val definedPackages = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "apkanalyzer failed while inspecting $selectedApk: $definedPackages" }
        check(!definedPackages.lines().any { it.startsWith("C ") && it.contains("dev.ujhhgtg.wekit.python.api") }) {
            "Runtime APK defines duplicate Python runtime API classes"
        }
        ZipFile(selectedApk!!).use { zip ->
                check(zip.getEntry("assets/chaquopy/build.json") != null) {
                    "Runtime APK is missing Chaquopy's generated assets/chaquopy/build.json"
                }
        }
    }
}

val verifyNoDuplicateApiClassesRelease = registerApkVerification("release")
val verifyNoDuplicateApiClassesDebug = registerApkVerification("debug")

val verifyRuntimeManifest = tasks.register("verifyRuntimeManifest") {
    group = "verification"
    description = "Checks runtime-manifest.json against the resolved standalone toolchain."
    doLast {
        val manifest = projectDir.resolve("src/main/assets/runtime-manifest.json")
        check(manifest.isFile) { "Missing runtime manifest: $manifest" }
        val text = manifest.readText()
        fun field(name: String): String = Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.get(1)
            ?: error("runtime-manifest.json is missing $name")
        check(field("agp") == runtimeAgpVersion) { "Manifest AGP does not match configured AGP $runtimeAgpVersion" }
        check(field("chaquopy") == runtimeChaquopyVersion) { "Manifest Chaquopy version mismatch" }
        check(field("gradle") == runtimeGradleVersion && gradle.gradleVersion == runtimeGradleVersion) {
            "Manifest/actual Gradle mismatch: ${field("gradle")} vs ${gradle.gradleVersion}"
        }
        check(field("jdk") == runtimeJdkVersion && System.getProperty("java.specification.version") == runtimeJdkVersion) {
            "Manifest/actual JDK mismatch"
        }
        check(field("python") == runtimePythonVersion) { "Manifest Python version mismatch" }
        check(field("ndk") == runtimeNdkVersion) { "Manifest NDK version mismatch" }
        check(field("abi") == runtimeAbi) { "Manifest ABI mismatch" }
    }
}

val verifyRuntimeEntrypointSignature = tasks.register("verifyRuntimeEntrypointSignature") {
    group = "verification"
    description = "Checks the static loader-neutral RuntimeEntrypoint bootstrap contract."
    doLast {
        val source = projectDir.resolve("src/main/java/dev/ujhhgtg/wekit/python/runtime/RuntimeEntrypoint.java")
        check(source.isFile) { "Missing RuntimeEntrypoint source" }
        val text = source.readText()
        check(Regex("static\\s+PythonRuntimeBackend\\s+bootstrap\\s*\\(\\s*int\\s+apiVersion\\s*,\\s*PythonRuntimeConfig\\s+config\\s*,\\s*PythonPluginHost\\s+host\\s*\\)").containsMatchIn(text)) {
            "RuntimeEntrypoint.bootstrap does not have the required static signature"
        }
        check(!text.contains("com.chaquo.python")) { "Entrypoint bootstrap must not reference runtime implementation classes" }
    }
}

tasks.named("check") {
    dependsOn(verifyApiArtifact, verifyRuntimeManifest, verifyRuntimeEntrypointSignature)
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "assembleDebug") {
        dependsOn(verifyApiArtifact)
        dependsOn(verifyRuntimeManifest)
        finalizedBy(if (name == "assembleRelease") verifyNoDuplicateApiClassesRelease else verifyNoDuplicateApiClassesDebug)
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(verifyApiArtifact)
    dependsOn(verifyRuntimeManifest)
}
