@file:Suppress("AvoidDuplicateDependencies")

import com.android.build.api.variant.BuildConfigField
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("dev.ujhhgtg.lsparanoid") version "0.13.0"
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
    alias(libs.plugins.hiddenapirefine)
}

// Native release/pilot protection; use -PlspBackend=jvm for desktop DexKit workers.
lsparanoid {
    backend = providers.gradleProperty("lspBackend").orElse("native").get()
    automaticLoading = false
    allowedHostCertificates = mapOf(
        "com.tencent.mm" to setOf("0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c"),
    )
    nativeNdkVersion = providers.gradleProperty("lspNdkVersion").orElse("29.0.14206865").get()
    omvllPlugin = providers.gradleProperty("lspOmvllPlugin").orNull
    omvllPythonPath = providers.gradleProperty("lspOmvllPythonPath").orNull
    classFilter = { it.startsWith("dev.ujhhgtg.wekit.") }
    excludedClassPrefixes = setOf(
        "dev.ujhhgtg.wekit.application.ModuleApplication",
        "dev.ujhhgtg.wekit.loader.startup.ModuleLoader",
        "dev.ujhhgtg.wekit.loader.entry",
        "dev.ujhhgtg.wekit.loader.utils.NativeLoader",
        "dev.ujhhgtg.wekit.loader.utils.ZygiskNativePayload",
        "dev.ujhhgtg.wekit.constants.PackageNames",
        "dev.ujhhgtg.wekit.utils.fs.PathUtilsKt",
        "dev.ujhhgtg.wekit.BuildConfig",
    )
    if (backend == "native") {
        wrappedResourceAccess = true
        resourceIncludes = setOf("string/*", "plurals/*", "array/*")
        resourceExcludes = setOf(
            "string/restart_wechat_to_apply", "string/nav_edit_enable_first",
            "string/automation_name_required", "string/res_inject_success",
        )
    }
}

// Keep Git values lazy: reading them during configuration invalidates the configuration cache
// on every commit. Wire these providers into variant/task inputs instead.
val commitCount = providers.exec {
    workingDir(rootProject.layout.projectDirectory)
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toInt() }

// Keep the same short-hash width as xtask's module metadata.
val gitHash = providers.exec {
    workingDir(rootProject.layout.projectDirectory)
    commandLine("git", "rev-parse", "--short=8", "HEAD")
}.standardOutput.asText.map { it.trim() }

// Committer timestamp of HEAD in milliseconds, so builds of the same commit are reproducible.
val commitTimestamp = providers.exec {
    workingDir(rootProject.layout.projectDirectory)
    commandLine("git", "log", "-1", "--format=%ct", "HEAD")
}.standardOutput.asText.map { it.trim().toLong() * 1000L }

android {
    namespace = libs.versions.namespace.get()
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = libs.versions.namespace.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        ndk {
            // noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "TAG", "\"WeKit\"")
    }

    lint {
        checkReleaseBuilds = false
    }

    splits {
        abi {
            isEnable = false
        }
    }

    lint {
        // 模块代码运行在宿主 (微信) 进程中, 使用宿主的权限与运行时环境:
        // 权限检查以模块清单为准会产生误报; 反射调用隐藏/私有 API 是 Xposed 模块的常态。
        // 自定义 View 仅在代码中构造, 不从 XML 加载; 语言切换由模块自行注入资源而非 Play 分包;
        // 动态快捷方式是转发给宿主的入口, 无需上报使用记录。
        disable += setOf(
            "MissingPermission",
            "NotificationPermission",
            "PrivateApi",
            "DiscouragedPrivateApi",
            "SoonBlockedPrivateApi",
            "BlockedPrivateApi",
            "ViewConstructor",
            "ClickableViewAccessibility",
            "AppBundleLocaleChanges",
            "ReportShortcutUsage",
        )
        lintConfig = file("lint.xml")
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (foundKeystore) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += listOf(
            "kotlin/**",
            "**.bin",
            "kotlin-tooling-metadata.json",
            "META-INF/INDEX.LIST",
            "META-INF/LICENSE.md",
            // Monet reads host resource tables with default framework loading disabled.
            "frameworks/android/**",
            // Monet signs with RSA; Picnic's post-quantum lookup tables are unused.
            "org/bouncycastle/pqc/crypto/picnic/**"
        )
        resources.merges += listOf(
            "META-INF/io.netty.versions.properties",
            "META-INF/xposed/*",
            "org/mozilla/javascript/**"
        )
    }

    androidResources {
        @Suppress("UnstableApiUsage")
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
        variant.outputs.forEach { output ->
            output.versionCode.set(commitCount)
            output.versionName.set(gitHash.map { "git+$it" })
        }
        variant.buildConfigFields!!.put("COMMIT_HASH", gitHash.map {
            BuildConfigField("String", "\"$it\"", null)
        })
        variant.buildConfigFields!!.put("COMMIT_TIMESTAMP", commitTimestamp.map {
            BuildConfigField("long", "${it}L", null)
        })

        val generateZygiskResources = tasks.register<GenerateZygiskResourcesTask>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}ZygiskResources"
        ) {
            description = "Generate Zygisk module Resources"
            templateDir.set(rootProject.layout.projectDirectory.dir("wekit-zygisk/template"))
            versionCode.set(variant.outputs.single().versionCode)
            versionName.set(variant.outputs.single().versionName)
            variantName.set(variant.name)
            outputDir.set(layout.buildDirectory.dir("generated/zygiskResources/${variant.name}"))
        }
        variant.sources.resources!!.addGeneratedSourceDirectory(
            generateZygiskResources,
            GenerateZygiskResourcesTask::outputDir
        )

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
    gitHead.set(gitHash)
}

val scriptDeps = configurations.create("scriptDeps") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val arsclibSource = configurations.create("arsclibSource") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

// ARSCLib bundles desktop copies of Android/XML Pull APIs. If supplied to R8 as
// program classes, even AttributeSet::class in host constructor queries gets
// rewritten to the bundled (obfuscated) copy and no longer matches Android.
val prepareAndroidArsclib = tasks.register<Jar>("prepareAndroidArsclib") {
    description = "Prepare Android ARSCLib"
    from(provider { arsclibSource.map { zipTree(it) } })
    exclude("android/**", "org/xmlpull/v1/**")
    archiveFileName.set("arsclib-android.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/arsclib"))
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
    implementation(project(":libs:python-runtime-api"))
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
    implementation(libs.androidx.exifinterface)
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
    implementation(libs.scripta.editor)
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

    implementation(project(":libs:common:bsh"))
    add(arsclibSource.name, libs.arsclib)
    implementation(files(prepareAndroidArsclib))
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.hiddenapibypass)
    implementation(project(":libs:common:reflekt"))
    implementation(libs.libsu.core)
    implementation(libs.dexmaker)
    implementation(project(":libs:common:annotation-scanner"))
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
    implementation(libs.hiddenapirefine.runtime)

    testImplementation(libs.junit.jupiter)
    testImplementation(project(":libs:common:stubs"))
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
    "wekit.dexTest.workers",
)
val dexTestWorker = providers.gradleProperty("dexTestWorker").map(String::toBoolean).orElse(false)
val monetCorpus = providers.gradleProperty("wekit.monetCorpus").map(String::toBoolean).orElse(false)

tasks.withType<Test>().configureEach {
    systemProperty("wekit.monetCorpus", monetCorpus.get())
    systemProperty("wekit.monetRuntime.dump", providers.gradleProperty("wekit.monetRuntime.dump").orNull.orEmpty())
    // Monet resource-graph tests load complete host APKs.
    maxHeapSize = "4g"
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
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        freeCompilerArgs.add("-opt-in=kotlin.io.path.ExperimentalPathApi")
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

// The localization adapter remains present in JVM development variants.
dependencies { implementation("dev.ujhhgtg.lsparanoid:runtime:0.13.0") }
