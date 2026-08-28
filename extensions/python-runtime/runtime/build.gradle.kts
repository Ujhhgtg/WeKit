import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version libs.versions.agp
    id("com.chaquo.python") version libs.versions.pythonRuntimeChaquopy
    id("org.jetbrains.kotlin.android") version libs.versions.kotlin
}

group = "dev.ujhhgtg.wekit.python.runtime"
version = libs.versions.pythonRuntimeVersion.get()

val nativeLibraries = listOf(
    "libcrypto_chaquopy.so",
    "libssl_chaquopy.so",
    "libsqlite3_chaquopy.so",
    "libcrypto_python.so",
    "libssl_python.so",
    "libsqlite3_python.so",
    "libpython3.13.so",
    "libchaquopy_java.so",
)
val runtimeManifestValues = mapOf(
    "chaquopy" to libs.versions.pythonRuntimeChaquopy.get(),
    "agp" to libs.versions.agp.get(),
    "gradle" to libs.versions.pythonRuntimeGradle.get(),
    "jdk" to libs.versions.jdk.get(),
    "python" to libs.versions.pythonRuntimeChaquopyTarget.get(),
    "ndk" to libs.versions.pythonRuntimeNdk.get(),
    "abi" to libs.versions.pythonRuntimeAbi.get(),
    "patchRevision" to "${libs.versions.pythonRuntimeChaquopyRevision.get()}+${libs.versions.pythonRuntimePatchRevision.get()}",
    "syncHookBudgetMs" to libs.versions.pythonRuntimeSyncHookBudgetMs.get(),
    "taskDrainTimeoutMs" to libs.versions.pythonRuntimeTaskDrainTimeoutMs.get(),
    "maxManifestBytes" to libs.versions.pythonRuntimeMaxManifestBytes.get(),
    "maxPluginFileBytes" to libs.versions.pythonRuntimeMaxPluginFileBytes.get(),
)
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/runtimeManifest")
val generateRuntimeManifest = tasks.register("generateRuntimeManifest") {
    inputs.properties(runtimeManifestValues)
    inputs.property("nativeLibraries", nativeLibraries)
    outputs.dir(generatedRuntimeAssets)
    doLast {
        val values = runtimeManifestValues
        generatedRuntimeAssets.get().file("runtime-manifest.json").asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                {
                  "packId": "python-runtime",
                  "chaquopy": "${values.getValue("chaquopy")}",
                  "agp": "${values.getValue("agp")}",
                  "gradle": "${values.getValue("gradle")}",
                  "jdk": "${values.getValue("jdk")}",
                  "python": "${values.getValue("python")}",
                  "ndk": "${values.getValue("ndk")}",
                  "abi": "${values.getValue("abi")}",
                  "patchRevision": "${values.getValue("patchRevision")}",
                  "syncHookBudgetMs": ${values.getValue("syncHookBudgetMs")},
                  "taskDrainTimeoutMs": ${values.getValue("taskDrainTimeoutMs")},
                  "maxManifestBytes": ${values.getValue("maxManifestBytes")},
                  "maxPluginFileBytes": ${values.getValue("maxPluginFileBytes")},
                  "nativeLibraries": ${nativeLibraries.joinToString(prefix = "[\"", postfix = "\"]", separator = "\", \"")}
                }
                """.trimIndent(),
            )
        }
    }
}

configure<ApplicationExtension> {
    namespace = "dev.ujhhgtg.wekit.python.runtime"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.pythonRuntimeNdk.get()
    defaultConfig {
        applicationId = "dev.ujhhgtg.wekit.python.runtime.container"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.pythonRuntimeVersionCode.get().toInt()
        versionName = libs.versions.pythonRuntimeVersion.get()
        ndk { abiFilters.add(libs.versions.pythonRuntimeAbi.get()) }
    }
    buildTypes { release { isMinifyEnabled = false } }
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }
    packaging { jniLibs.useLegacyPackaging = true }
    sourceSets["main"].assets.srcDir(generatedRuntimeAssets.get().asFile)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get())) }
    jvmToolchain(libs.versions.jdk.get().toInt())
}

val chaquopyTarget = configurations.create("chaquopyTarget") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val dexKitCodegen = configurations.create("dexKitCodegen") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // Supplied by xtask from a controlled local Maven repository; compile-only
    // prevents API classes from entering the runtime DEX.
    val apiVersion = providers.gradleProperty("wekitPythonApiVersion").orElse(libs.versions.pythonRuntimeApiVersion)
    compileOnly("dev.ujhhgtg.wekit:python-runtime-api:${apiVersion.get()}")
    chaquopyTarget(
        "com.chaquo.python:target:${libs.versions.pythonRuntimeChaquopyTarget.get()}:" +
            "${libs.versions.pythonRuntimeAbi.get()}@zip",
    )
    dexKitCodegen("org.luckypray:dexkit:${libs.versions.dexkit.get()}")
}

val generatedDexKitPython = layout.buildDirectory.dir("generated/dexkitBindings/python")
val generatedDexKitStubs = layout.buildDirectory.dir("generated/dexkitBindings/stubs")
val generateDexKitPythonBindings = tasks.register<Exec>("generateDexKitPythonBindings") {
    val generator = rootProject.file("codegen/generate_dexkit_bindings.py")
    inputs.file(generator)
    inputs.files(dexKitCodegen)
    outputs.dirs(generatedDexKitPython, generatedDexKitStubs)
    doFirst {
        commandLine(
            "python3",
            generator,
            "--aar",
            dexKitCodegen.singleFile,
            "--python-out",
            generatedDexKitPython.get().asFile,
            "--stub-out",
            generatedDexKitStubs.get().asFile,
        )
    }
}

chaquopy {
    defaultConfig { version = libs.versions.pythonRuntimePython.get() }
    sourceSets {
        named("main") {
            srcDir(generatedDexKitPython.get().asFile)
        }
    }
}

tasks.named("preBuild") { dependsOn(generateRuntimeManifest) }
tasks.matching {
    it.name.matches(Regex("generate.+PythonSourceAssets")) ||
        it.name.matches(Regex("merge.+PythonSources"))
}.configureEach {
    dependsOn(generateDexKitPythonBindings)
}

val extractedChaquopyTarget = layout.buildDirectory.dir("chaquopyTarget")
tasks.register<Sync>("extractChaquopyTarget") {
    from({ chaquopyTarget.files.map(::zipTree) })
    into(extractedChaquopyTarget)
}

listOf("debug", "release").forEach { variant ->
    val capitalized = variant.replaceFirstChar(Char::uppercaseChar)
    val stagePatchedBridge = tasks.register<Copy>("stage${capitalized}PatchedChaquopyBridge") {
        dependsOn("generate${capitalized}PythonMiscAssets")
        val bridge = providers.gradleProperty("wekitPatchedChaquopyBridge").map(::file)
        from(bridge)
        into(layout.buildDirectory.dir("python/assets/misc/$variant/chaquopy/bootstrap-native/arm64-v8a/java"))
        doFirst {
            require(bridge.isPresent && bridge.get().isFile) {
                "wekitPatchedChaquopyBridge must point to the bridge built by xtask"
            }
        }
    }
    tasks.matching {
        it.name == "generate${capitalized}PythonBuildAssets" ||
            it.name == "merge${capitalized}Assets"
    }.configureEach {
        dependsOn(stagePatchedBridge)
    }
}
