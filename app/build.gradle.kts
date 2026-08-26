
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
    // fixed width: bare --short widens as history grows and varies across git versions, which would
    // make versionName disagree with the hash xtask bakes into module.prop and the Zygisk zip name
    return providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
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

    defaultConfig {
        applicationId = libs.versions.namespace.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = commitCount
        versionName = "git+$gitHash"

        ndk {
            // noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "COMMIT_HASH", "\"${gitHash}\"")
        buildConfigField("String", "TAG", "\"WeKit\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")
    }

    splits {
        abi {
            isEnable = false
        }
    }

    // Two entry-point variants:
    //  - standard: ships the modern libxposed entry point (entry/lxp/* sources +
    //              META-INF/xposed/*), placed in the `standard` flavor source set.
    //  - legacy:   omits both, so frameworks with poor libxposed compatibility fall
    //              back to the traditional de.robv entry (Xp51HookEntry via
    //              assets/xposed_init, which lives in `main` and is shared by both).
    flavorDimensions += "entrypoint"
    productFlavors {
        create("standard") {
            dimension = "entrypoint"
            // ships the libxposed entry point (entry/lxp/* + META-INF/xposed/*)
            buildConfigField("boolean", "HAS_LIBXPOSED_ENTRY", "true")
            buildConfigField("String", "FLAVOR_SLUG", "\"standard\"")
        }
        create("legacy") {
            dimension = "entrypoint"
            // no libxposed entry; framework falls back to the de.robv api
            buildConfigField("boolean", "HAS_LIBXPOSED_ENTRY", "false")
            buildConfigField("String", "FLAVOR_SLUG", "\"legacy\"")
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
        jniLibs {
            useLegacyPackaging = true
        }
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
        localeFilters += setOf("zh-rCN", "zh-rTW")
        additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x69")
    }

    buildFeatures {
        resValues = false
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.useJUnitPlatform()
        }
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

        kotlinSources.addGeneratedSourceDirectory(
            generateNewFeatures,
            GenerateNewFeaturesTask::outputDir
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

val validateDesktopDexResolvers = tasks.register<ValidateDesktopDexResolversTask>("validateDesktopDexResolvers") {
    description = "Validate that Dex resolvers can run without a live WeChat host"
    group = "verification"
    sourceDir.set(file("src/main/java"))
    includePaths.set(
        providers.gradleProperty("dexResolverValidationInclude")
            .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
            .orElse(emptyList()),
    )
}

tasks.named("preBuild") {
    dependsOn(validateDesktopDexResolvers)
}

val generateNewFeatures = tasks.register<GenerateNewFeaturesTask>("generateNewFeatures") {
    description = "Collect features added within the last 30 days of history"
    group = "wekit"
    sourceDir.set(file("src/main/java"))
    repoDir.set(rootProject.layout.projectDirectory)
    outputDir.set(layout.buildDirectory.dir("generated/source/newfeatures"))
    namespace.set(libs.versions.namespace.get())
    windowDays.set(30)
    gitHead.set(getGitHash())
}

val scriptDeps = configurations.create("scriptDeps") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

// R8/D8 fat jar, resolved through the project repositories (google()).
val r8Tool = configurations.detachedConfiguration(
    dependencies.create("com.android.tools:r8:8.7.18"),
)

val generateScriptDepsDex = tasks.register<GenerateScriptDepsDexTask>("generateScriptDepsDex") {
    group = "wekit"
    description = "Compile the script-deps extension pack DEX (fastjson2 + okhttp + kotlin-stdlib)"
    jars.from(scriptDeps)
    r8Classpath.from(r8Tool)
    minApi.set(28)
    // Bump together with compileSdk when it changes.
    androidJar.set(
        androidComponents.sdkComponents.bootClasspath.map { jars -> jars.first().asFile.absolutePath },
    )
    outputDir.set(layout.buildDirectory.dir("outputs/script-deps"))
}

// --- end tasks ---

ksp {
    // Room schema export for migration diffing
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.shader)
    implementation(libs.miuix.nav)
    implementation(libs.materialkolor)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    implementation(libs.composablehorizons.material.symbols.filled)
    implementation(libs.composablehorizons.material.symbols.outlined)

    implementation(libs.google.protobuf.javalite)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.mmkv)

    implementation(project(":libs:common:bsh"))
    implementation(project(":libs:monet-generator-api"))

    compileOnly(libs.legacyxposed.api)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.hiddenapibypass)
    implementation(project(":libs:common:reflekt"))
    implementation(libs.libsu.core)
    implementation(libs.dexmaker)
//    implementation(libs.arsclib)
//    implementation(libs.apksig)
//    implementation(libs.bouncycastle.prov)
//    implementation(libs.bouncycastle.pkix)
    @Suppress("AvoidDuplicateDependencies")
    implementation(project(":libs:common:annotation-scanner"))
    @Suppress("AvoidDuplicateDependencies")
    ksp(project(":libs:common:annotation-scanner"))

    implementation(libs.okhttp3.okhttp)
    implementation(libs.jsoup)

    scriptDeps(libs.alibaba.fastjson2)
    scriptDeps(libs.okhttp3.okhttp)
    scriptDeps(kotlin("stdlib"))

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)

    implementation(libs.mcp.server)
    implementation(libs.mcp.client)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.jsch)

    implementation(libs.osmdroid.android)

    compileOnly(project(":libs:common:stubs"))

    testImplementation(libs.junit.jupiter)
    testImplementation(project(":libs:common:stubs"))
    testImplementation(project(":extensions:monet-generator"))
    testImplementation(libs.legacyxposed.api)
    testImplementation(libs.libxposed.api)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val dexTestWorkerProperties = listOf(
    "wekit.dexTest.apk",
    "wekit.dexTest.nativeLibrary",
    "wekit.dexTest.report",
    "wekit.dexTest.dexKitVersion",
    "wekit.dexTest.dexKitRevision",
    "wekit.dexTest.versionCode",
    "wekit.dexTest.versionName",
    "wekit.dexTest.buildTag",
    "wekit.dexTest.isGooglePlay",
    "wekit.dexTest.features",
)
val dexTestWorker = providers.gradleProperty("dexTestWorker").map(String::toBoolean).orElse(false)
val monetCorpus = providers.gradleProperty("wekit.monetCorpus").map(String::toBoolean).orElse(false)

tasks.withType<Test>().configureEach {
    systemProperty("wekit.monetCorpus", monetCorpus.get())
    if (monetCorpus.get()) maxHeapSize = "4g"
    if (dexTestWorker.get()) {
        filter {
            includeTestsMatching("dev.ujhhgtg.wekit.dextest.DexTestWorkerTest")
        }
        dexTestWorkerProperties.forEach { propertyName ->
            systemProperty(propertyName, providers.gradleProperty(propertyName).orNull.orEmpty())
        }
        outputs.upToDateWhen { false }
    } else {
        filter {
            excludeTestsMatching("dev.ujhhgtg.wekit.dextest.DexTestWorkerTest")
        }
    }
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

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}
