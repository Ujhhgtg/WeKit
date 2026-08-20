package dev.ujhhgtg.wekit.dextest

import dev.ujhhgtg.wekit.dexkit.resolution.DexHostMetadata
import dev.ujhhgtg.wekit.features.core.DexResolutionTestEntry
import dev.ujhhgtg.wekit.features.core.DexResolutionTestRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import org.junit.jupiter.api.Test
import org.luckypray.dexkit.DexKitBridge

internal data class DexTestWorkerConfig(
    val apk: Path,
    val nativeLibrary: Path,
    val report: Path,
    val dexKitVersion: String,
    val dexKitRevision: String,
    val versionCode: Long,
    val versionName: String,
    val buildTag: String,
    val isGooglePlay: Boolean,
    val featureSelectors: List<String>?,
) {
    companion object {
        fun fromSystemProperties(properties: Properties): DexTestWorkerConfig {
            fun required(key: String) = properties.getProperty(key)?.takeIf(String::isNotBlank)
                ?: error("missing required system property: $key")
            val isGooglePlay = required("wekit.dexTest.isGooglePlay").let { raw ->
                raw.toBooleanStrictOrNull()
                    ?: error("wekit.dexTest.isGooglePlay must be true or false, was $raw")
            }
            return DexTestWorkerConfig(
                apk = Path.of(required("wekit.dexTest.apk")).toAbsolutePath().normalize(),
                nativeLibrary = Path.of(required("wekit.dexTest.nativeLibrary")).toAbsolutePath().normalize(),
                report = Path.of(required("wekit.dexTest.report")).toAbsolutePath().normalize(),
                dexKitVersion = required("wekit.dexTest.dexKitVersion"),
                dexKitRevision = required("wekit.dexTest.dexKitRevision"),
                versionCode = required("wekit.dexTest.versionCode").toLongOrNull()
                    ?: error("wekit.dexTest.versionCode must be a long"),
                versionName = required("wekit.dexTest.versionName"),
                buildTag = required("wekit.dexTest.buildTag"),
                isGooglePlay = isGooglePlay,
                featureSelectors = properties.getProperty("wekit.dexTest.features")
                    ?.takeIf(String::isNotBlank)
                    ?.split(',')
                    ?.map { selector ->
                        selector.trim().also {
                            require(it.isNotEmpty()) {
                                "wekit.dexTest.features contains an empty feature name"
                            }
                        }
                    },
            )
        }
    }
}

class DexTestWorkerTest {

    @Test
    fun runDexResolutionWorker() {
        val config = DexTestWorkerConfig.fromSystemProperties(System.getProperties())
        val started = Instant.now()
        val startedNanos = System.nanoTime()
        val environment = DexTestEnvironment(
            dexKitVersion = config.dexKitVersion,
            dexKitRevision = config.dexKitRevision,
            architecture = System.getProperty("os.arch").orEmpty(),
            jvmVersion = System.getProperty("java.version").orEmpty(),
        )

        val report = try {
            val entries = selectDexTestEntries(
                DexResolutionTestRegistry.ITEMS,
                config.featureSelectors,
            )
            require(Files.isRegularFile(config.apk)) { "APK is not a regular file: ${config.apk}" }
            require(Files.isRegularFile(config.nativeLibrary)) { "DexKit native library is not a regular file: ${config.nativeLibrary}" }
            System.load(config.nativeLibrary.toString())
            DexKitBridge.create(config.apk.toString()).use { dexKit ->
                val host = DexHostMetadata(config.versionCode, config.versionName, config.isGooglePlay)
                val features = entries.map { entry ->
                    runDexFeature(entry, dexKit, host, javaClass.classLoader ?: error("worker class loader is null"))
                }
                buildReport(
                    config = config,
                    environment = environment,
                    dexCount = dexKit.getDexNum(),
                    started = started,
                    elapsedMillis = elapsedMillis(startedNanos),
                    features = features,
                )
            }
        } catch (error: Throwable) {
            DexTestApkReport(
                apkPath = config.apk.toString(),
                fileName = config.apk.fileName.toString(),
                label = config.apk.fileName.toString(),
                apkSize = if (Files.exists(config.apk)) Files.size(config.apk) else 0,
                apkSha256 = if (Files.isRegularFile(config.apk)) sha256(config.apk) else "",
                versionCode = config.versionCode,
                versionName = config.versionName,
                buildTag = config.buildTag,
                isGooglePlay = config.isGooglePlay,
                environment = environment,
                startedAt = started.toString(),
                finishedAt = Instant.now().toString(),
                elapsedMillis = elapsedMillis(startedNanos),
                outcome = DexTestApkOutcome.INFRASTRUCTURE_FAILURE,
                infrastructureError = error.toDexTestError(),
            )
        }
        report.writeAtomically(config.report)
    }
}

internal fun selectDexTestEntries(
    entries: List<DexResolutionTestEntry>,
    selectors: List<String>?,
): List<DexResolutionTestEntry> {
    if (selectors == null) return entries
    return selectors.map { selector ->
        val matches = if ('.' in selector) {
            entries.filter { it.className == selector }
        } else {
            entries.filter { it.className.substringAfterLast('.') == selector }
        }
        require(matches.isNotEmpty()) { "unknown Dex resolver feature: $selector" }
        require(matches.size == 1) {
            "ambiguous Dex resolver feature $selector; use its fully qualified name: " +
                matches.map(DexResolutionTestEntry::className).sorted().joinToString()
        }
        matches.single()
    }
}

private fun buildReport(
    config: DexTestWorkerConfig,
    environment: DexTestEnvironment,
    dexCount: Int,
    started: Instant,
    elapsedMillis: Long,
    features: List<DexTestFeatureReport>,
): DexTestApkReport {
    val delegates = features.flatMap(DexTestFeatureReport::delegates)
    val counts = DexTestCounts(
        success = delegates.count { it.status.name == "SUCCESS" },
        expectedFailure = delegates.count { it.status.name == "EXPECTED_FAILURE" },
        unexpectedFailure = delegates.count { it.status.name == "UNEXPECTED_FAILURE" },
        blocked = delegates.count { it.status.name == "BLOCKED" },
        incomplete = delegates.count { it.status.name == "INCOMPLETE" },
    )
    val outcome = if (features.all {
            it.outcome == DexTestFeatureOutcome.PASS || it.outcome == DexTestFeatureOutcome.PASS_WITH_EXPECTED_FAILURES
        }
    ) DexTestApkOutcome.PASS else DexTestApkOutcome.FAIL
    return DexTestApkReport(
        apkPath = config.apk.toString(),
        fileName = config.apk.fileName.toString(),
        label = config.apk.fileName.toString(),
        apkSize = Files.size(config.apk),
        apkSha256 = sha256(config.apk),
        versionCode = config.versionCode,
        versionName = config.versionName,
        buildTag = config.buildTag,
        isGooglePlay = config.isGooglePlay,
        dexCount = dexCount,
        environment = environment,
        startedAt = started.toString(),
        finishedAt = Instant.now().toString(),
        elapsedMillis = elapsedMillis,
        outcome = outcome,
        counts = counts,
        features = features,
    )
}

private fun elapsedMillis(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
