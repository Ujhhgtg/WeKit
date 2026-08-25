import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val play3084ApksProperty = "wekit.monet.play3084Apks"
val play3084Apks = providers.systemProperty(play3084ApksProperty)

plugins {
    id("com.android.base")
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get()))
    }
    jvmToolchain(libs.versions.jdk.get().toInt())
}

configure<LibraryExtension> {
    namespace = "dev.ujhhgtg.wekit.monet.generator"
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        compileSdk = libs.versions.compileSdk.get().toInt()
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }

    testOptions {
        unitTests.all { test ->
            test.useJUnitPlatform()
            play3084Apks.orNull?.let { path ->
                test.systemProperty(play3084ApksProperty, path)
            }
        }
    }
}

val monetTestWorkerProperties = listOf(
    "wekit.monetTest.inputKind",
    "wekit.monetTest.inputPath",
    "wekit.monetTest.nativeLibrary",
    "wekit.monetTest.report",
    "wekit.monetTest.dexKitVersion",
    "wekit.monetTest.dexKitRevision",
    "wekit.monetTest.versionCode",
    "wekit.monetTest.versionName",
    "wekit.monetTest.isGooglePlay",
)
val monetTestWorker = providers.gradleProperty("monetTestWorker").map(String::toBoolean).orElse(false)

tasks.withType<Test>().configureEach {
    if (monetTestWorker.get()) {
        filter {
            includeTestsMatching("dev.ujhhgtg.wekit.extensions.monettest.MonetTestWorkerTest")
        }
        monetTestWorkerProperties.forEach { propertyName ->
            providers.gradleProperty(propertyName).orNull
                ?.takeIf(String::isNotBlank)
                ?.let { value -> systemProperty(propertyName, value) }
        }
        maxHeapSize = "3g"
        maxParallelForks = 1
        outputs.upToDateWhen { false }
    } else {
        filter {
            excludeTestsMatching("dev.ujhhgtg.wekit.extensions.monettest.MonetTestWorkerTest")
        }
    }
}

val r8Tool = configurations.detachedConfiguration(
    dependencies.create("com.android.tools:r8:8.7.18"),
)

val generateMonetGeneratorDex = tasks.register<GenerateMonetGeneratorDexTask>("generateMonetGeneratorDex") {
    group = "wekit"
    description = "Shrink the isolated Monet generator engine into one extension DEX"
    dependsOn("bundleReleaseAar")
    extensionAar.set(layout.buildDirectory.file("outputs/aar/monet-generator-release.aar"))
    r8Classpath.from(r8Tool)
    rulesFile.set(layout.projectDirectory.file("proguard-rules.pro"))
    minApi.set(libs.versions.minSdk.get().toInt())
    androidJar.set(
        androidComponents.sdkComponents.bootClasspath.map { jars -> jars.first().asFile.absolutePath },
    )
    outputDir.set(layout.buildDirectory.dir("outputs/extension-dex"))
}

afterEvaluate {
    val runtimeClasspath = configurations.getByName("releaseRuntimeClasspath")
    val compileClasspath = configurations.getByName("releaseCompileClasspath")
    val apiLibrary = compileClasspath.incoming.artifactView {
        attributes {
            attribute(
                org.gradle.api.attributes.Attribute.of("artifactType", String::class.java),
                "android-classes-jar",
            )
        }
        componentFilter { component ->
            component is org.gradle.api.artifacts.component.ProjectComponentIdentifier &&
                component.projectPath == ":libs:monet-generator-api"
        }
    }.files
    generateMonetGeneratorDex.configure {
        programJars.from(runtimeClasspath)
        libraryJars.from(apiLibrary)
    }
}

dependencies {
    compileOnly(project(":libs:monet-generator-api"))
    implementation(libs.arsclib)
    implementation(libs.apksig)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":libs:monet-generator-api"))
    testImplementation(project(":libs:monet-dex-evidence"))
    testImplementation(libs.dexkit)
    testRuntimeOnly(libs.junit.platform.launcher)
}
