import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.base")
    id("com.android.library")
}

group = "dev.ujhhgtg.wekit"
version = providers.gradleProperty("wekitPythonApiVersion").orElse("1.0.0").get()

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get())) }
    jvmToolchain(libs.versions.jdk.get().toInt())
}

configure<LibraryExtension> {
    namespace = "dev.ujhhgtg.wekit.python.api"
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        compileSdk = libs.versions.compileSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }
}

tasks.register("verifyPythonRuntimeApiBoundary") {
    group = "verification"
    description = "Checks the loader-neutral API and base source tree for forbidden runtime references."
    val repositoryRoot = rootProject.projectDir
    val apiSourceRoot = projectDir.resolve("src/main")
    val sourceRoots = sequenceOf(
        repositoryRoot.resolve("app/src"),
        repositoryRoot.resolve("libs"),
        repositoryRoot.resolve("extensions/monet-generator/src"),
    ).flatMap { root ->
        root.walkTopDown().filter { directory ->
            directory.isDirectory && directory.name in setOf("java", "kotlin") &&
                directory.parentFile.name != "test" && directory.parentFile.name != "androidTest"
        }
    }.filter { it.isDirectory }.toList()
    val entrypoint = repositoryRoot.resolve("extensions/python-runtime/runtime/src/main/java/dev/ujhhgtg/wekit/python/runtime/RuntimeEntrypoint.java")
    doLast {
        val forbiddenImport = Regex("com\\.chaquo\\.python")
        sourceRoots.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.forEach { file ->
                check(!forbiddenImport.containsMatchIn(file.readText())) {
                    "Forbidden Python runtime reference in ${file.relativeTo(repositoryRoot)}"
                }
            }
        }
        val forbiddenApiReference = Regex("com\\.chaquo\\.python|PyObject|BeanShell|JavaEngine")
        apiSourceRoot.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.forEach { file ->
            check(!forbiddenApiReference.containsMatchIn(file.readText())) {
                "Forbidden implementation reference in API source ${file.relativeTo(repositoryRoot)}"
            }
        }
        check(entrypoint.isFile) { "RuntimeEntrypoint source is missing: $entrypoint" }
        val text = entrypoint.readText()
        check(Regex("static\\s+PythonRuntimeBackend\\s+bootstrap\\s*\\(\\s*int\\s+apiVersion\\s*,\\s*PythonRuntimeConfig\\s+config\\s*,\\s*PythonPluginHost\\s+host\\s*\\)").containsMatchIn(text)) {
            "RuntimeEntrypoint.bootstrap signature does not match the API contract"
        }
    }
}

tasks.named("check") {
    dependsOn("verifyPythonRuntimeApiBoundary")
}
