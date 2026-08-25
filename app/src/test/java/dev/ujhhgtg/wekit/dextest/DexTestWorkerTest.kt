package dev.ujhhgtg.wekit.dextest

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.BaseDexDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.dexkit.resolution.DexHostMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexOwnerMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerKind
import dev.ujhhgtg.wekit.dexkit.resolution.DexProducerMetadata
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionCoordinator
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionRegistry
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionStatus
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.DexResolutionTestEntry
import dev.ujhhgtg.wekit.features.core.DexResolutionTestRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.luckypray.dexkit.DexKitBridge
import sun.misc.Unsafe

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
                apk = required("wekit.dexTest.apk").asPath.toAbsolutePath().normalize(),
                nativeLibrary = required("wekit.dexTest.nativeLibrary").asPath.toAbsolutePath().normalize(),
                report = required("wekit.dexTest.report").asPath.toAbsolutePath().normalize(),
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
            val selectedEntries = selectDexTestEntries(
                DexResolutionTestRegistry.ITEMS,
                config.featureSelectors,
            )
            require(Files.isRegularFile(config.apk)) { "APK is not a regular file: ${config.apk}" }
            require(Files.isRegularFile(config.nativeLibrary)) { "DexKit native library is not a regular file: ${config.nativeLibrary}" }
            System.load(config.nativeLibrary.toString())
            DexKitBridge.create(config.apk.toString()).use { dexKit ->
                val host = DexHostMetadata(config.versionCode, config.versionName, config.isGooglePlay)
                val loadedOwners = loadDexOwners(
                    DexResolutionTestRegistry.ITEMS,
                    javaClass.classLoader ?: error("worker class loader is null"),
                )
                val features = resolveDexFeatureReports(
                    loadedOwners = loadedOwners,
                    selectedEntries = selectedEntries,
                    dexKit = dexKit,
                    host = host,
                )
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

class DexTestWorkerSchedulingTest {

    @Test
    fun selectorsResolveOneSharedGraphAndReportDependencyClosureOnce() {
        val fixture = WorkerGraphFixture()
        var coordinatorCreations = 0

        val reports = fixture.resolve(
            loadedOwners = fixture.loadedOwners.reversed(),
            onCoordinatorCreated = { coordinatorCreations++ },
        )

        assertEquals(1, coordinatorCreations)
        assertEquals(1, fixture.sharedExecutions.get())
        assertEquals(fixture.reportClassNames, reports.map(DexTestFeatureReport::className))
        assertEquals(
            DexResolutionStatus.SUCCESS,
            reports.single { it.className == fixture.shared.entry.className }.delegates.single().status,
        )
        fixture.consumers.forEach { consumer ->
            assertEquals(
                listOf(fixture.shared.owner.dexDelegates.single().stableId),
                reports.single { it.className == consumer.entry.className }.delegates.single().dependencies,
            )
        }
    }

    @Test
    fun registryOrderingDoesNotChangeSharedGraphReports() {
        val fixture = WorkerGraphFixture()

        val forward = fixture.resolve(fixture.loadedOwners)
        val reversed = fixture.resolve(fixture.loadedOwners.reversed())

        assertEquals(stableGraphSnapshot(forward), stableGraphSnapshot(reversed))
        assertEquals(2, fixture.sharedExecutions.get())
    }

    @Test
    fun unrelatedRootsContinueAndAggregateEveryReportedDelegateOnce() {
        val fixture = WorkerGraphFixture()

        val reports = fixture.resolve(fixture.loadedOwners)

        assertEquals(1, fixture.unrelatedExecutions.get())
        assertEquals(DexTestFeatureOutcome.FAIL, reports.single { it.className == fixture.blocked.entry.className }.outcome)
        assertEquals(DexTestFeatureOutcome.PASS, reports.single { it.className == fixture.unrelated.entry.className }.outcome)
        assertEquals(
            DexTestCounts(
                success = 4,
                expectedFailure = 1,
                unexpectedFailure = 1,
                blocked = 1,
                incomplete = 1,
            ),
            countDexTestOutcomes(reports),
        )
    }

    @Test
    fun ownerLoadingKeepsInitializationFailureFeatureScoped() {
        val entries = listOf(
            DexResolutionTestEntry(LoadableOwner::class.java.name),
            DexResolutionTestEntry("dev.example.DoesNotExist"),
        )

        val loaded = loadDexOwners(entries, javaClass.classLoader!!)

        assertInstanceOf(LoadedDexOwner.Ready::class.java, loaded[0])
        assertInstanceOf(LoadedDexOwner.Failed::class.java, loaded[1])
        assertEquals("dev.example.DoesNotExist", loaded[1].entry.className)
    }

    private object LoadableOwner : WorkerTestOwner()

    private fun stableGraphSnapshot(reports: List<DexTestFeatureReport>) = reports.map { report ->
        report.copy(
            delegates = report.delegates.map { delegate -> delegate.copy(stackTrace = null) },
            featureError = report.featureError?.copy(stackTrace = null),
        )
    }
}

private class WorkerGraphFixture {
    val sharedExecutions = AtomicInteger()
    val unrelatedExecutions = AtomicInteger()

    val shared = ready(object : WorkerTestOwner() {}) { owner ->
        owner.inlineMethod("shared") { delegate ->
            sharedExecutions.incrementAndGet()
            delegate.setDescriptor("fixture.Shared", "run", "()V")
            true
        }
    }
    val consumers = listOf(
        ready(object : WorkerTestOwner() {}) { owner ->
            owner.inlineMethod("consumerOne") { delegate ->
                DexResolutionContext.requireData(shared.owner.dexDelegates.single())
                delegate.setDescriptor("fixture.ConsumerOne", "run", "()V")
                true
            }
        },
        ready(object : WorkerTestOwner() {}) { owner ->
            owner.inlineMethod("consumerTwo") { delegate ->
                DexResolutionContext.requireData(shared.owner.dexDelegates.single())
                delegate.setDescriptor("fixture.ConsumerTwo", "run", "()V")
                true
            }
        },
    )
    val failing = ready(object : WorkerTestOwner() {}) { owner ->
        owner.inlineMethod("failing") { error("fixture failure") }
    }
    val blocked = ready(object : WorkerTestOwner() {}) { owner ->
        owner.inlineMethod("blocked") { delegate ->
            DexResolutionContext.requireData(failing.owner.dexDelegates.single())
            delegate.setDescriptor("fixture.Blocked", "run", "()V")
            true
        }
    }
    val unrelated = ready(object : WorkerTestOwner() {}) { owner ->
        owner.inlineMethod("unrelated") { delegate ->
            unrelatedExecutions.incrementAndGet()
            delegate.setDescriptor("fixture.Unrelated", "run", "()V")
            true
        }
    }
    val expected = ready(object : WorkerTestOwner() {}) { owner ->
        owner.inlineMethod("expected") { delegate ->
            delegate.setPlaceholderDescriptor(expectedFailure = true, reason = "fixture absence")
            true
        }
    }
    val incomplete = ready(object : WorkerTestOwner() {}) { owner ->
        owner.inlineMethod("incomplete") { true }
    }

    val loadedOwners = listOf(shared) + consumers + listOf(failing, blocked, unrelated, expected, incomplete)
    private val selectedEntries = consumers.map(LoadedDexOwner.Ready::entry) +
        listOf(blocked.entry, unrelated.entry, expected.entry, incomplete.entry)
    val reportClassNames = loadedOwners.map { it.entry.className }.sorted()

    fun resolve(
        loadedOwners: List<LoadedDexOwner.Ready>,
        onCoordinatorCreated: () -> Unit = {},
    ): List<DexTestFeatureReport> = resolveDexFeatureReports(
        loadedOwners = loadedOwners,
        selectedEntries = selectedEntries,
        dexKit = allocateDexKitWithoutNativeState(),
        host = DexHostMetadata(versionCode = 1, versionName = "test", isGooglePlay = false),
        registryFactory = { owners -> DexResolutionRegistry.create(owners, metadataFor(owners)) },
        coordinatorFactory = { registry, dexKit, host ->
            onCoordinatorCreated()
            DexResolutionCoordinator(registry, dexKit, host)
        },
    )

    private fun ready(
        owner: WorkerTestOwner,
        configure: (WorkerTestOwner) -> Unit,
    ): LoadedDexOwner.Ready {
        configure(owner)
        return LoadedDexOwner.Ready(
            entry = DexResolutionTestEntry(owner.javaClass.name),
            owner = owner,
            elapsedMillis = 0,
        )
    }

    private fun metadataFor(owners: List<IResolveDex>): Map<String, DexOwnerMetadata> =
        owners.associate { resolver ->
            val owner = resolver as WorkerTestOwner
            val ownerId = owner.javaClass.name
            ownerId to DexOwnerMetadata(
                ownerClassName = ownerId,
                producers = owner.dexDelegates.associate { delegate ->
                    delegate.stableId to DexProducerMetadata(
                        stableId = delegate.stableId,
                        ownerClassName = ownerId,
                        propertyName = delegate.propertyName,
                        kind = DexProducerKind.INLINE_METHOD,
                        localFingerprint = "producer-${delegate.propertyName}",
                    )
                },
                customOutputPropertyNames = emptySet(),
            )
        }
}

private open class WorkerTestOwner : BaseFeature(), IResolveDex {
    override val technicalId = "worker-test"
    override val nameRes = 0
    override val categoryIds = emptyList<String>()

    fun inlineMethod(
        propertyName: String,
        block: DexResolutionCoordinator.(DexMethodDelegate) -> Boolean,
    ): DexMethodDelegate = DexMethodDelegate(this, propertyName, block).also(::registerDexDelegate)
}

private fun allocateDexKitWithoutNativeState(): DexKitBridge {
    val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    return (field.get(null) as Unsafe).allocateInstance(DexKitBridge::class.java) as DexKitBridge
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
    val counts = countDexTestOutcomes(features)
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

internal fun countDexTestOutcomes(features: List<DexTestFeatureReport>): DexTestCounts {
    val delegates = features.flatMap(DexTestFeatureReport::delegates)
    return DexTestCounts(
        success = delegates.count { it.status == DexResolutionStatus.SUCCESS },
        expectedFailure = delegates.count { it.status == DexResolutionStatus.EXPECTED_FAILURE },
        unexpectedFailure = delegates.count { it.status == DexResolutionStatus.UNEXPECTED_FAILURE },
        blocked = delegates.count { it.status == DexResolutionStatus.BLOCKED },
        incomplete = delegates.count { it.status == DexResolutionStatus.INCOMPLETE },
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
