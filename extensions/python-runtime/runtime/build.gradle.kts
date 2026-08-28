import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.tasks.compile.JavaCompile
import java.util.zip.ZipFile

plugins {
    id("com.android.application") version "9.2.0"
    id("com.chaquo.python") version "17.0.0"
}

val apiVersion = providers.gradleProperty("wekitPythonApiVersion").orElse("1.0.0")
val apiRepository = providers.gradleProperty("wekitPythonApiRepo")
    .orElse(System.getenv("WEKIT_PYTHON_API_REPO") ?: "")

group = "dev.ujhhgtg.wekit.python.runtime"
version = "0.1.0"

configure<ApplicationExtension> {
    namespace = "dev.ujhhgtg.wekit.python.runtime"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.ujhhgtg.wekit.python.runtime.container"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
}

chaquopy {
    defaultConfig {
        version = "3.13"
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

val verifyNoDuplicateApiClasses = tasks.register("verifyNoDuplicateApiClasses") {
    group = "verification"
    description = "Fails when the runtime APK packages a duplicate API class."
    doLast {
        val candidates = listOf(
            layout.buildDirectory.file("outputs/apk/release/runtime-release.apk").get().asFile,
            layout.buildDirectory.file("outputs/apk/debug/runtime-debug.apk").get().asFile,
        )
        candidates.filter(File::isFile).forEach { apk ->
            ZipFile(apk).use { zip ->
                val duplicates = zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) }
                    .flatMap { entry ->
                        val dexText = zip.getInputStream(entry).use { input ->
                            input.readBytes().toString(Charsets.ISO_8859_1)
                        }
                        if (dexText.contains("dev/ujhhgtg/wekit/python/api/")) sequenceOf(entry.name)
                        else emptySequence()
                    }
                    .toList()
                check(duplicates.isEmpty()) { "Runtime APK contains duplicate API classes in DEX: ${duplicates.joinToString()}" }
                check(zip.getEntry("assets/chaquopy/build.json") != null) {
                    "Runtime APK is missing Chaquopy's generated assets/chaquopy/build.json"
                }
            }
        }
    }
}

tasks.register("verifyRuntimeEntrypointSignature") {
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

tasks.configureEach {
    if (name == "assembleRelease" || name == "assembleDebug") {
        dependsOn(verifyApiArtifact)
        finalizedBy(verifyNoDuplicateApiClasses)
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(verifyApiArtifact)
}
