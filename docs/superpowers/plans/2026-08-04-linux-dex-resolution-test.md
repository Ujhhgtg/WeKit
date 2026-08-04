# Linux Dex Resolution Test Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `./x dex-test`, a Linux desktop tool that runs WeKit's real DexKit resolvers against every selected `wechat_*.apk` in an isolated JVM and reports successful, expected-failure, unexpected-failure, blocked, and incomplete results.

**Architecture:** `xtask` discovers APKs, builds the Linux DexKit 2.2.0 JNI library, extracts APK metadata with `apkanalyzer`, and launches one Gradle unit-test worker JVM per APK. The app module exposes a shared resolution context so both Android and desktop execution use the same `resolveInlineDex()` and `resolveDex()` code while resolver-to-resolver dependencies use DexKit metadata rather than reflection on live host classes. KSP generates a string-only registry of resolver Features; Kotlin workers write per-APK JSON, and Rust aggregates and prints the cross-version report.

**Tech Stack:** Kotlin 2.4/JVM 21, Android Gradle Plugin 9.3, KSP/KotlinPoet, DexKit 2.2.0 JNI/CMake/Ninja, JUnit Jupiter, kotlinx.serialization JSON, Rust 2024, clap, serde/serde_json, and the existing `cargo xtask` wrapper.

## Global Constraints

- Follow `/home/ujhhgtg/coding/WeKit/AGENTS.md`; preserve unrelated worktree changes and always use `./x build` for final Android validation.
- JDK is 21; compile SDK and target SDK are 37; the implementation target is the current Linux desktop.
- DexKit stays single-sourced from `gradle/libs.versions.toml` (`2.2.0` at plan time); the verified upstream tag `2.2.0` resolves to commit `ffa6c51c38fe3ecfddb18d8949c30c48dbfbfd6a`.
- Default APK discovery is every regular file matching `~/coding/wechat_*.apk`, natural-sorted by filename; repeated explicit `--apk` paths preserve first-occurrence order after canonical-path de-duplication.
- Each APK runs in a separate JVM process. Feature/delegate/static state must never leak between APKs.
- The worker runs the production `resolveInlineDex()` followed by production `resolveDex()`; it never calls `startup()`, `onEnable()`, hook installation, or on-device cache persistence.
- A no-result `allowFailure = true` lookup that installs a placeholder is `EXPECTED_FAILURE`. Intentional manual version branches must call `setPlaceholderDescriptor(expectedFailure = true, reason = ...)` explicitly; an unclassified placeholder is unexpected.
- The throwing delegate is `UNEXPECTED_FAILURE`; still-pending delegates in the same Feature become `BLOCKED`. Pending delegates after normal completion become `INCOMPLETE` and fail the run.
- Do not add `allowFailure` or expected-placeholder annotations merely to make reports green.
- Reports default to `dex-test-results/<run-id>/`; native/source caches live under `.wekit/dex-test/`; neither location belongs under Gradle's `build/reports` tree or in Git.
- A report containing only success and expected failures exits zero. Unexpected, blocked, incomplete, initialization, worker, APK, native, or report failures exit non-zero after all runnable APKs finish.
- Desktop resolver compatibility must not be achieved with JADX-renamed identifiers, copied matchers, or a per-version allowlist.

## File and Responsibility Map

### Build-time source validation

- Create `buildSrc/src/main/kotlin/DexResolverSourceScanner.kt`: reusable lexer/block extractor for `resolveDex()` and inline `dexClass`/`dexField`/`dexMethod`/`dexConstructor` declarations.
- Create `buildSrc/src/main/kotlin/ValidateDesktopDexResolversTask.kt`: fail on live-host reflection or `HostInfo` version branching inside resolution blocks.
- Create `buildSrc/src/test/kotlin/DexResolverSourceScannerTest.kt`: regression tests for source extraction and forbidden-access detection.
- Modify `buildSrc/src/main/kotlin/GenerateMethodHashesTask.kt`: use the shared scanner and include inline `dexField` blocks in hashes.
- Modify `buildSrc/build.gradle.kts`: enable JUnit Jupiter tests.
- Modify `app/build.gradle.kts`: register and eventually wire `validateDesktopDexResolvers` into `preBuild`; configure the worker-only unit-test mode and runtime stubs.

### Shared resolver runtime

- Create `app/src/main/java/dev/ujhhgtg/wekit/dexkit/resolution/DexResolutionContext.kt`: thread-local bridge/host metadata and shared `resolveAllDex` executor.
- Create `app/src/main/java/dev/ujhhgtg/wekit/dexkit/resolution/DexResolutionDiagnostic.kt`: outcome/status model used by delegates and reports.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/dexkit/abc/IResolveDex.kt`: expose the shared execution entry.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/features/core/BaseFeature.kt`: keep inline resolution callable only through the shared executor.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/dexkit/dsl/DexDelegates.kt`: metadata accessors, reset/finalize operations, placeholder classification, and loud exception recording.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/ui/content/DexResolver.kt`: call the shared executor before saving Android cache.
- Create `app/src/test/java/dev/ujhhgtg/wekit/dexkit/resolution/DexResolutionDiagnosticTest.kt`: pure state-transition tests.

### Platform-neutral resolver migrations

- Modify the resolver files listed in Tasks 3-6 so resolution-time dependencies use `delegate.data` (`ClassData`, `MethodData`, `FieldData`) and `DexResolutionContext.host`, not `.clazz`, `.method`, `.field`, `.constructor`, or `HostInfo`.

### Generated registry and worker

- Modify `libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/FeaturesScanner.kt`: generate a string-only resolver registry.
- Create `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexResolutionRegistryTest.kt`: validate generated metadata without eager object initialization.
- Create `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexTestReport.kt`: serializable report schema and atomic JSON writer.
- Create `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexFeatureRunner.kt`: initialize and resolve one Feature, then finalize delegate statuses.
- Create `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexFeatureRunnerTest.kt`: controlled success/expected/unexpected/blocked/incomplete tests.
- Create `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexTestWorkerTest.kt`: worker-JVM JUnit entrypoint driven by system properties.

### xtask orchestration

- Create `xtask/src/dex_test.rs`: CLI, APK discovery/order, report directories, DexKit native build/cache, `apkanalyzer` metadata, Gradle worker invocation, JSON aggregation, terminal rendering, and tests.
- Modify `xtask/src/main.rs`: add `dex-test` command and dispatch.
- Modify `xtask/Cargo.toml`: add `serde_json` and `time` dependencies.
- Modify `.gitignore`: ignore `/.wekit/dex-test/` and `/dex-test-results/`.
- Create `docs/development/linux-dex-test.md`: user-facing command/report/troubleshooting guide.

---

### Task 1: Extract and Validate Resolver Source Blocks

**Files:**
- Create: `buildSrc/src/main/kotlin/DexResolverSourceScanner.kt`
- Create: `buildSrc/src/main/kotlin/ValidateDesktopDexResolversTask.kt`
- Create: `buildSrc/src/test/kotlin/DexResolverSourceScannerTest.kt`
- Modify: `buildSrc/src/main/kotlin/GenerateMethodHashesTask.kt:10-123`
- Modify: `buildSrc/build.gradle.kts`
- Modify: `app/build.gradle.kts:187-205`

**Interfaces:**
- Produces: `scanDexResolverSource(file: File): DexResolverSource?`
- Produces: `findDesktopIncompatibleAccesses(source: DexResolverSource): List<DesktopResolverViolation>`
- Produces: Gradle task `:app:validateDesktopDexResolvers` with optional project property `dexResolverValidationInclude` containing comma-separated repository-relative paths.
- Consumes later: Tasks 3-6 use the filtered validator; Task 6 wires the full validator into `preBuild`.

- [ ] **Step 1: Add failing scanner tests**

```kotlin
class DexResolverSourceScannerTest {
    @Test
    fun extractsCustomAndAllInlineDelegateKinds() {
        val source = scanDexResolverSource(
            "Sample.kt",
            """
                package sample
                object Sample : IResolveDex {
                    private val field by dexField { matcher { name = "f" } }
                    private val method by dexMethod(allowFailure = true) {
                        matcher { declaredClass(classOwner.clazz) }
                    }
                    override fun resolveDex(dexKit: DexKitBridge) {
                        method.find(dexKit) { matcher { name = "m" } }
                    }
                }
            """.trimIndent(),
        )!!

        assertEquals(listOf(ResolveBlockKind.INLINE_FIELD, ResolveBlockKind.INLINE_METHOD, ResolveBlockKind.CUSTOM), source.blocks.map { it.kind })
    }

    @Test
    fun flagsHostReflectionOnlyInsideResolutionBlocks() {
        val source = scanDexResolverSource("Sample.kt", sampleSource)!!
        assertEquals(
            listOf("classOwner.clazz", "HostInfo.versionCode"),
            findDesktopIncompatibleAccesses(source).map { it.expression },
        )
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew -p buildSrc test --tests DexResolverSourceScannerTest`

Expected: FAIL because `scanDexResolverSource`, `ResolveBlockKind`, and the violation finder do not exist.

- [ ] **Step 3: Extract the existing lexer and block parsing into a reusable scanner**

```kotlin
internal enum class ResolveBlockKind { CUSTOM, INLINE_CLASS, INLINE_FIELD, INLINE_METHOD, INLINE_CONSTRUCTOR }

internal data class ResolveSourceBlock(
    val kind: ResolveBlockKind,
    val startLine: Int,
    val text: String,
)

internal data class DexResolverSource(
    val file: File,
    val qualifiedClassName: String,
    val blocks: List<ResolveSourceBlock>,
)

internal fun scanDexResolverSource(file: File): DexResolverSource? =
    scanDexResolverSource(file.path, file.readText(), file)
```

Move `ScannedSource`, `LexContext`, and `stripCommentsPreservingStrings` out of `GenerateMethodHashesTask.kt`. Preserve byte-identical string handling and add `Field` to the inline delegate regex:

```kotlin
private val INLINE_DELEGATE = Regex("""\bby\s+dex(Class|Field|Method|Constructor)\b""")
```

- [ ] **Step 4: Implement the forbidden-access detector and Gradle task**

```kotlin
private val LIVE_HOST_ACCESS = Regex(
    """\b(?:class|method|field|ctor)[A-Za-z0-9_]*\.(clazz|method|field|constructor)\b"""
)
private val HOST_VERSION_ACCESS = Regex(
    """\bHostInfo\.(versionCode|versionName|isHostGooglePlay)\b"""
)

abstract class ValidateDesktopDexResolversTask : DefaultTask() {
    @get:InputDirectory abstract val sourceDir: DirectoryProperty
    @get:Optional @get:Input abstract val includePaths: ListProperty<String>

    @TaskAction
    fun validate() {
        val violations = sourceDir.asFileTree.matching { include("**/*.kt") }
            .files.sortedBy { it.path }
            .filter { includePaths.getOrElse(emptyList()).isEmpty() || includePaths.get().any(it.path::endsWith) }
            .mapNotNull(::scanDexResolverSource)
            .flatMap(::findDesktopIncompatibleAccesses)
        if (violations.isNotEmpty()) error(violations.joinToString("\n") { it.render() })
    }
}
```

Register `validateDesktopDexResolvers` in `app/build.gradle.kts`, mapping
`-PdexResolverValidationInclude=a.kt,b.kt` to `includePaths`. Do not wire it into `preBuild` until Task 6 clears the current violations.

- [ ] **Step 5: Make method hashes consume the shared scanner**

Replace the private parsing loop in `GenerateMethodHashesTask.generate()` with:

```kotlin
val hashMap = srcDir.walkTopDown()
    .filter { it.isFile && it.extension == "kt" }
    .mapNotNull(::scanDexResolverSource)
    .associate { source ->
        val body = source.blocks.joinToString("\n") { it.text }
        source.qualifiedClassName to md5(body)
    }
```

Keep the existing guard that an `IResolveDex` declaration must have a custom or inline block.

- [ ] **Step 6: Run focused tests and record the expected migration inventory**

Run:

```bash
./gradlew -p buildSrc test
./gradlew :app:generateMethodHashes
./gradlew :app:validateDesktopDexResolvers
```

Expected: buildSrc tests and hash generation PASS. The validator intentionally FAILS and lists the platform-dependent resolution expressions currently present in the 23 resolver files enumerated in Tasks 3-6.

- [ ] **Step 7: Commit**

```bash
git add buildSrc/src/main/kotlin/DexResolverSourceScanner.kt \
  buildSrc/src/main/kotlin/ValidateDesktopDexResolversTask.kt \
  buildSrc/src/test/kotlin/DexResolverSourceScannerTest.kt \
  buildSrc/src/main/kotlin/GenerateMethodHashesTask.kt \
  buildSrc/build.gradle.kts app/build.gradle.kts
git commit -m "build: validate desktop-compatible dex resolvers"
```

### Task 2: Add Shared Resolution Context and Delegate Diagnostics

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/dexkit/resolution/DexResolutionContext.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/dexkit/resolution/DexResolutionDiagnostic.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dexkit/resolution/DexResolutionDiagnosticTest.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/dexkit/abc/IResolveDex.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/core/BaseFeature.kt:64-76`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/dexkit/dsl/DexDelegates.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/content/DexResolver.kt:102-121`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/RoundAvatars.kt:75-87`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt:489-521`

**Interfaces:**
- Produces: `data class DexHostMetadata(val versionCode: Long, val versionName: String, val isGooglePlay: Boolean)`
- Produces: `fun IResolveDex.resolveAllDex(dexKit: DexKitBridge, host: DexHostMetadata = DexHostMetadata.currentAndroidHost())`
- Produces on each delegate: `val data`, `val diagnostic`, `resetForDexTest()`, `markBlocked(causeKey)`, and `markIncomplete()`.
- Produces: `setPlaceholderDescriptor(expectedFailure: Boolean = false, reason: String? = null)`.
- Consumes later: all resolver migrations and the desktop worker.

- [ ] **Step 1: Write failing diagnostic transition tests**

```kotlin
class DexResolutionDiagnosticTest {
    @Test
    fun explicitExpectedPlaceholderDoesNotFail() {
        val delegate = DexMethodDelegate("Feature:method")
        delegate.resetForDexTest()
        delegate.setPlaceholderDescriptor(expectedFailure = true, reason = "not present in this host branch")
        assertEquals(DexResolutionStatus.EXPECTED_FAILURE, delegate.diagnostic.status)
    }

    @Test
    fun pendingDelegateBecomesBlockedAfterSiblingThrows() {
        val delegate = DexClassDelegate("Feature:later")
        delegate.resetForDexTest()
        delegate.markBlocked("Feature:failing")
        assertEquals(DexResolutionStatus.BLOCKED, delegate.diagnostic.status)
    }

    @Test
    fun normalCompletionTurnsPendingIntoIncomplete() {
        val delegate = DexFieldDelegate("Feature:field")
        delegate.resetForDexTest()
        delegate.markIncomplete()
        assertEquals(DexResolutionStatus.INCOMPLETE, delegate.diagnostic.status)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*DexResolutionDiagnosticTest'`

Expected: FAIL because the status types and delegate APIs do not exist.

- [ ] **Step 3: Implement the status and context model**

```kotlin
enum class DexResolutionStatus {
    PENDING, SUCCESS, EXPECTED_FAILURE, UNEXPECTED_FAILURE, BLOCKED, INCOMPLETE
}

data class DexResolutionDiagnostic(
    val status: DexResolutionStatus,
    val descriptor: String? = null,
    val message: String? = null,
    val exceptionType: String? = null,
    val stackTrace: String? = null,
    val blockedBy: String? = null,
)

data class DexHostMetadata(
    val versionCode: Long,
    val versionName: String,
    val isGooglePlay: Boolean,
)
```

Use a `ThreadLocal<Session?>` in `DexResolutionContext` so eight concurrent Feature scans can share one bridge without sharing the current Feature session:

```kotlin
internal inline fun <T> withResolutionContext(
    dexKit: DexKitBridge,
    host: DexHostMetadata,
    block: () -> T,
): T

val DexResolutionContext.dexKit: DexKitBridge
val DexResolutionContext.host: DexHostMetadata
```

- [ ] **Step 4: Convert `BaseDexDelegate` to a shared sealed base class**

Put reset/diagnostic transitions in the base class and leave descriptor storage in each concrete delegate:

```kotlin
sealed class BaseDexDelegate(val key: String) {
    var diagnostic = DexResolutionDiagnostic(DexResolutionStatus.PENDING)
        private set

    internal fun resetForDexTest() {
        clearResolvedValue()
        diagnostic = DexResolutionDiagnostic(DexResolutionStatus.PENDING)
    }

    protected fun recordSuccess(descriptor: String) {
        diagnostic = DexResolutionDiagnostic(
            status = DexResolutionStatus.SUCCESS,
            descriptor = descriptor,
        )
    }

    protected fun recordExpectedFailure(descriptor: String, reason: String) {
        diagnostic = DexResolutionDiagnostic(
            status = DexResolutionStatus.EXPECTED_FAILURE,
            descriptor = descriptor,
            message = reason,
        )
    }

    protected fun recordUnexpectedFailure(error: Throwable) {
        diagnostic = DexResolutionDiagnostic(
            status = DexResolutionStatus.UNEXPECTED_FAILURE,
            message = error.message,
            exceptionType = error::class.java.name,
            stackTrace = error.stackTraceToString(),
        )
    }

    internal fun markBlocked(causeKey: String) {
        if (diagnostic.status == DexResolutionStatus.PENDING) {
            diagnostic = DexResolutionDiagnostic(
                status = DexResolutionStatus.BLOCKED,
                blockedBy = causeKey,
            )
        }
    }

    internal fun markIncomplete() {
        if (diagnostic.status == DexResolutionStatus.PENDING) {
            diagnostic = DexResolutionDiagnostic(DexResolutionStatus.INCOMPLETE)
        }
    }

    protected abstract fun clearResolvedValue()
    abstract fun getDescriptorString(): String?
    abstract fun loadDescriptor(value: String)
    open fun findInline(dexKit: DexKitBridge): Boolean = true
}
```

Every `find()` wraps matcher construction, JNI/query execution, cardinality validation, index selection, and assignment. Record unexpected failure immediately before rethrowing the original exception.

- [ ] **Step 5: Add metadata accessors that never load host classes**

```kotlin
val DexClassDelegate.data: ClassData
    get() = DexResolutionContext.dexKit.getClassData(getDescriptorString()!!)!!

val DexMethodDelegate.data: MethodData
    get() = DexResolutionContext.dexKit.getMethodData(getDescriptorString()!!)!!

val DexConstructorDelegate.data: MethodData
    get() = DexResolutionContext.dexKit.getMethodData(getDescriptorString()!!)!!

val DexFieldDelegate.data: FieldData
    get() = DexResolutionContext.dexKit.getFieldData(getDescriptorString()!!)!!
```

Keep `.clazz`, `.method`, `.field`, and `.constructor` unchanged for hook-time use outside resolution blocks.

- [ ] **Step 6: Add the shared production/desktop executor**

```kotlin
fun IResolveDex.resolveAllDex(
    dexKit: DexKitBridge,
    host: DexHostMetadata = DexHostMetadata.currentAndroidHost(),
) = withResolutionContext(dexKit, host) {
    (this as BaseFeature).resolveInlineDex(dexKit)
    resolveDex(dexKit)
}
```

Change Android `DexResolver.scanItem()` to call `item.resolveAllDex(dexKit)` before `DexCacheManager.saveItemCache(item)`.

- [ ] **Step 7: Make intentional manual placeholders explicit**

Use source-level reasons without changing descriptor behavior:

```kotlin
methodAvatarModify.setPlaceholderDescriptor(
    expectedFailure = true,
    reason = "avatar modify method is absent in this host variant",
)

ctorNetSceneUploadMsgImg.setPlaceholderDescriptor(
    expectedFailure = true,
    reason = "new image feature service path is active",
)
```

Apply the corresponding explicit reason to `methodImgUploadFeatureServiceSendImage` and
`methodAppInfoSetAppId` in the legacy branch. Calls made by `find(... allowFailure = true)` pass
`expectedFailure = true` internally.

- [ ] **Step 8: Run focused tests and Android compilation**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests '*DexResolutionDiagnosticTest'
./gradlew :app:compileStandardDebugKotlin
git diff --check
```

Expected: PASS. The full desktop resolver validator still fails until Tasks 3-6 migrate current source.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/dexkit/resolution \
  app/src/main/java/dev/ujhhgtg/wekit/dexkit/abc/IResolveDex.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/core/BaseFeature.kt \
  app/src/main/java/dev/ujhhgtg/wekit/dexkit/dsl/DexDelegates.kt \
  app/src/main/java/dev/ujhhgtg/wekit/ui/content/DexResolver.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/RoundAvatars.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt \
  app/src/test/java/dev/ujhhgtg/wekit/dexkit/resolution/DexResolutionDiagnosticTest.kt
git commit -m "refactor: share dex resolution context and diagnostics"
```

### Task 3: Migrate Core API Resolvers to DexKit Metadata

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeConversationApi.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeDatabaseApi.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeServiceApi.kt`

**Interfaces:**
- Consumes: delegate `.data` and `DexResolutionContext.host` from Task 2.
- Produces: core API resolver blocks with zero validator violations while preserving hook-time reflection outside those blocks.

- [ ] **Step 1: Run the validator on only these files and confirm failure**

```bash
./gradlew :app:validateDesktopDexResolvers \
  -PdexResolverValidationInclude=features/api/core/WeConversationApi.kt,features/api/core/WeDatabaseApi.kt,features/api/core/WeMessageApi.kt,features/api/core/WeServiceApi.kt
```

Expected: FAIL on `.clazz`, `.method`, and `HostInfo` inside resolution blocks.

- [ ] **Step 2: Replace class/method reflection dependencies with DexKit data**

Apply these exact conversion families only inside resolution blocks:

```kotlin
declaredClass(classConversationStorage.data.name)
declaredClass(classConversationStorage.data.superClass!!.name)
paramTypes("int", classConversationStorage.data.superClass!!.name, "java.lang.Object")

declaredClass(classMmKernel.data.name)
returnType(classMsgInfo.data.name)

val taskClassName = methodImageSendEntry.data.paramTypeNames[1]
classImageTask.setDescriptor(taskClassName)

val targetInterface = classVoiceServiceImpl.data.interfaces.first {
    !it.name.startsWith("ki0.")
}
classVoiceServiceInterface.setDescriptor(targetInterface.name)
```

For `WeServiceApi`, replace values passed to `addFieldForType` with names and change that local helper to accept a type name:

```kotlin
fun FindMethod.addFieldForType(typeName: String) {
    matcher!!.addUsingField { type(typeName) }
}

addFieldForType(classImageInfoStorage.data.name)
addFieldForType(methodDownloadImageServiceDownloadImage.data.declaredClassName)
```

- [ ] **Step 3: Replace resolver version branching with the session host metadata**

```kotlin
val host = DexResolutionContext.host
if (host.versionCode >= WeChatVersions.MM_8_0_67 && !host.isGooglePlay ||
    host.versionCode >= WeChatVersions.MM_8_0_66_PLAY && host.isGooglePlay
) {
    // existing branch unchanged
}
```

Do not replace hook-time `HostInfo` uses outside resolution blocks.

- [ ] **Step 4: Run filtered validation and compile**

Run:

```bash
./gradlew :app:validateDesktopDexResolvers \
  -PdexResolverValidationInclude=features/api/core/WeConversationApi.kt,features/api/core/WeDatabaseApi.kt,features/api/core/WeMessageApi.kt,features/api/core/WeServiceApi.kt
./gradlew :app:compileStandardDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeConversationApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeDatabaseApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeServiceApi.kt
git commit -m "refactor: use dex metadata in core resolvers"
```

### Task 4: Migrate Network and Contact Resolvers to DexKit Metadata

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/net/WePacketHelper.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeContactLabelApi.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ForceEnableAllTools.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/SplitGroupCall.kt`

**Interfaces:**
- Consumes: Task 2 metadata accessors.
- Produces: these custom resolvers runnable without a host class loader.

- [ ] **Step 1: Confirm the filtered validator fails**

```bash
./gradlew :app:validateDesktopDexResolvers \
  -PdexResolverValidationInclude=features/api/net/WePacketHelper.kt,features/api/core/WeContactLabelApi.kt,features/items/chat/ForceEnableAllTools.kt,features/items/contacts/SplitGroupCall.kt
```

- [ ] **Step 2: Migrate `WePacketHelper`**

```kotlin
val wrapperName = classRawReq.data.superClass!!.name
val callbackInterfaceName = classCallbackIface.data.name

matcher {
    superClass = classProtoBase.data.name
}
```

Where downstream logic only needs a class identity in a matcher, pass the dotted name. Where it
needs interface membership, use `classCallbackIface.data.interfaces`/`.name`, never `.clazz`.

- [ ] **Step 3: Migrate the remaining custom resolvers**

```kotlin
// SplitGroupCall
val iLinkServiceName = classILinkService.data.name
declaredClass(methodEnterTalkRoom.data.declaredClassName)

// ForceEnableAllTools: UsingFieldData.field is already DexKit FieldData and remains valid.
val usedFields = refreshMethod.usingFields.map { it.field }.distinct()

// WeContactLabelApi: keep UsingFieldData.field, but do not call delegate.field.
val pbFields = targetMethod.usingFields.map { it.field }
```

The validator distinguishes `it.field` (`FieldData`) from names such as
`fieldRoomId.field` (live reflection).

- [ ] **Step 4: Run filtered validation and compile**

Run the Task 4 validator command again, followed by:

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/api/net/WePacketHelper.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeContactLabelApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ForceEnableAllTools.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/SplitGroupCall.kt
git commit -m "refactor: use dex metadata in network resolvers"
```

### Task 5: Migrate UI and Moments Resolvers to DexKit Metadata

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeHomeScreenPopupMenuApi.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeMomentsApi.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeMomentsContextMenuApi.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/moments/DisplayDetails.kt`

**Interfaces:**
- Consumes: Task 2 metadata accessors.
- Produces: the large moments dependency chain as pure DexKit metadata lookups.

- [ ] **Step 1: Confirm the filtered validator fails**

```bash
./gradlew :app:validateDesktopDexResolvers \
  -PdexResolverValidationInclude=features/api/ui/WeHomeScreenPopupMenuApi.kt,features/api/ui/WeMomentsApi.kt,features/api/ui/WeMomentsContextMenuApi.kt,features/items/moments/DisplayDetails.kt
```

- [ ] **Step 2: Convert class and method-derived matcher types**

Use the corresponding metadata property at every reported line:

```kotlin
declaredClass(classImproveInteractionLayout.data.name)
type(classImproveSnsInfo.data.name)
declaredClass(classSnsService.data.name)
returnType(methodGetSnsInfoByLocalId.data.declaredClassName)
declaredClass(methodGetSnsVideoService.data.returnTypeName)
declaredClass(classImproveSnsInfo.data.superClass!!.name)
returnType(methodSnsInfoStorage.data.declaredClassName)
```

Apply the same `.data.name` conversion to `classUploadPackHelper`, `classSnsUploadElement`,
`classSnsUtil`, `classSnsPathHelper`, `classSnsVideoLogic`, `classSnsCore`,
`classSnsDownloadManager`, `classSnsUiAction`, `classSnsUploadUi`, and `classMenuItemData`.

- [ ] **Step 3: Run filtered validation and compile**

Run the Task 5 validator command again, followed by:

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: PASS with no matcher behavior or hook-time reflection changes.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeHomeScreenPopupMenuApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeMomentsApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeMomentsContextMenuApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/moments/DisplayDetails.kt
git commit -m "refactor: use dex metadata in moments resolvers"
```

### Task 6: Migrate Remaining Feature Resolvers and Enable the Global Gate

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/QuickBackToBottom.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/QuotedMessageDirectJump.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/RemoveCustomStickersLimit.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/RemoveMessageSelectionLimit.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/SuperConversationPinning.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/CustomLocalFriendAvatars.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/payment/AutoOpenRedPackets.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/payment/OpenHistoryRedPackets.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/system/DisableWebViewSafetyWarnings.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/RemoveLimitsDuringCalls.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: Task 2 metadata accessors and Task 1 global validator.
- Produces: zero desktop-incompatible source violations and a permanent `preBuild` regression gate.

- [ ] **Step 1: Confirm the full validator still fails only on the remaining files**

Run: `./gradlew :app:validateDesktopDexResolvers`

Expected: FAIL with no Task 3-5 files in the output.

- [ ] **Step 2: Convert chat/payment/system matcher dependencies**

```kotlin
declaredClass(classChattingContext.data.name)
declaredClass(methodClickEvent.data.declaredClassName)
declaredClass(classMmkv.data.name)
returnType(classCgiBack.data.name)
type(classConversation.data.name)
declaredClass(classReceiveLuckyMoney.data.name)
declaredClass(methodGetIsInterceptEnabled.data.declaredClassName)
```

Use `.data.name`, `.data.declaredClassName`, `.data.returnTypeName`, or `.data.paramTypeNames`
according to what the existing reflection expression supplied.

- [ ] **Step 3: Convert `PipVoip` and `RemoveLimitsDuringCalls`**

Examples covering every form in those inline matchers:

```kotlin
declaredClass(methodBallAddVoipView.data.declaredClassName)
declaredClass(classVoipMpService.data.name)
type(classVoipMpService.data.name)
returnType(classVoipMpAudioCapturer.data.interfaces.single().name)
type(methodVoipMpSwitchMute.data.declaredClassName)
type(classVoipAudioManager.data.interfaces.single().name)
declaredClass(methodIsDuringCall.data.declaredClassName)
```

Do not touch `.method`, `.field`, `.constructor`, or `.clazz` uses in `onEnable()` and operational
helpers outside resolver blocks.

- [ ] **Step 4: Make the global validator pass**

Run:

```bash
./gradlew :app:validateDesktopDexResolvers
./gradlew :app:compileStandardDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Wire validation into every Android pre-build**

```kotlin
tasks.named("preBuild").configure {
    dependsOn(validateDesktopDexResolvers)
}
```

Run: `./gradlew :app:preBuild`

Expected: PASS and the task graph includes `validateDesktopDexResolvers`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/CustomLocalFriendAvatars.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/payment \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/system/DisableWebViewSafetyWarnings.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/RemoveLimitsDuringCalls.kt \
  app/build.gradle.kts
git commit -m "refactor: make feature resolvers desktop compatible"
```

### Task 7: Generate a Lazy Resolver Registry

**Files:**
- Modify: `libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/FeaturesScanner.kt:34-155`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexResolutionRegistryTest.kt`

**Interfaces:**
- Produces generated `dev.ujhhgtg.wekit.features.core.DexResolutionTestEntry`.
- Produces generated `DexResolutionTestRegistry.ITEMS: List<DexResolutionTestEntry>`.
- Consumes later: `DexTestWorkerTest` iterates registry entries without touching `FeaturesProvider`.

- [ ] **Step 1: Write the failing generated-registry test**

```kotlin
class DexResolutionRegistryTest {
    @Test
    fun registryContainsOnlyLazyResolverMetadata() {
        val entries = DexResolutionTestRegistry.ITEMS
        assertTrue(entries.isNotEmpty())
        assertEquals(entries.size, entries.map { it.className }.distinct().size)
        assertTrue(entries.any { it.className.endsWith("DisableTypingStatusUploading") })
        assertFalse(entries.any { it.className.endsWith("MomentsEditorBackOptimization") })

        entries.forEach { entry ->
            val type = Class.forName(entry.className, false, javaClass.classLoader)
            assertTrue(IResolveDex::class.java.isAssignableFrom(type))
        }
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*DexResolutionRegistryTest'`

Expected: compilation FAIL because the generated registry does not exist.

- [ ] **Step 3: Generate metadata-only entries**

Add `IResolveDex` detection through `getAllSuperTypes()` and generate:

```kotlin
public data class DexResolutionTestEntry(
    public val className: String,
    public val name: String,
    public val categories: List<String>,
    public val description: String,
)

public object DexResolutionTestRegistry {
    public val ITEMS: List<DexResolutionTestEntry> = listOf(
        DexResolutionTestEntry(
            className = "dev.ujhhgtg.wekit.features.items.chat.DisableTypingStatusUploading",
            name = "禁止上传正在输入状态",
            categories = listOf("聊天"),
            description = "禁止微信上传「对方正在输入」状态",
        ),
    )
}
```

Use only string literals in initializers. Do not emit `%T::class`, `%T`, object references, or an
`INSTANCE` access.

- [ ] **Step 4: Run the registry test and compile both app flavors**

```bash
./gradlew :app:testStandardDebugUnitTest --tests '*DexResolutionRegistryTest'
./gradlew :app:compileStandardDebugKotlin :app:compileLegacyDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/FeaturesScanner.kt \
  app/src/test/java/dev/ujhhgtg/wekit/dextest/DexResolutionRegistryTest.kt
git commit -m "build: generate lazy dex resolver registry"
```

### Task 8: Define and Test the JSON Report Model

**Files:**
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexTestReport.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexTestReportTest.kt`

**Interfaces:**
- Produces: schema version `1` Kotlin serializable types `DexTestApkReport`, `DexTestFeatureReport`, `DexTestDelegateReport`, `DexTestEnvironment`, and outcome enums.
- Produces: `fun DexTestApkReport.writeAtomically(path: Path)`.
- Consumes later: worker writes this schema; Rust mirrors the fields it needs for aggregation.

- [ ] **Step 1: Write failing serialization and atomic-write tests**

```kotlin
@TempDir lateinit var tempDir: Path

@Test
fun reportRoundTripsAndKeepsStackTrace() {
    val report = sampleReport(exceptionType = "java.lang.IllegalStateException", stackTrace = "boom\n at test")
    val json = DexTestJson.encodeToString(report)
    assertEquals(report, DexTestJson.decodeFromString<DexTestApkReport>(json))
}

@Test
fun atomicWriterLeavesOnlyCompletedJson() {
    val path = tempDir.resolve("wechat_8069.json")
    sampleReport().writeAtomically(path)
    assertTrue(path.exists())
    assertFalse(tempDir.resolve("wechat_8069.json.tmp").exists())
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*DexTestReportTest'`

- [ ] **Step 3: Implement the schema**

```kotlin
@Serializable
data class DexTestDelegateReport(
    val key: String,
    val status: DexResolutionStatus,
    val descriptor: String? = null,
    val isPlaceholder: Boolean = false,
    val message: String? = null,
    val exceptionType: String? = null,
    val stackTrace: String? = null,
    val blockedBy: String? = null,
)

@Serializable
data class DexTestFeatureReport(
    val className: String,
    val displayName: String,
    val methodHash: String,
    val outcome: DexTestFeatureOutcome,
    val elapsedMillis: Long,
    val delegates: List<DexTestDelegateReport>,
    val featureError: DexTestError? = null,
)
```

Include APK absolute path, filename, label, size, SHA-256, version code/name, build tag, Play flag,
Dex count, DexKit version/revision, architecture, JVM version, timestamps, counts, and APK outcome in
`DexTestApkReport`.

- [ ] **Step 4: Implement atomic output**

Write a sibling `.<filename>.tmp`, `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`, and fall back to
a non-atomic same-filesystem replace only when `AtomicMoveNotSupportedException` is thrown.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests '*DexTestReportTest'
git add app/src/test/java/dev/ujhhgtg/wekit/dextest/DexTestReport.kt \
  app/src/test/java/dev/ujhhgtg/wekit/dextest/DexTestReportTest.kt
git commit -m "test: define dex resolution report schema"
```

### Task 9: Implement Feature Resolution and Finalization

**Files:**
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexFeatureRunner.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexFeatureRunnerTest.kt`

**Interfaces:**
- Produces: `fun runDexFeature(entry: DexResolutionTestEntry, dexKit: DexKitBridge, host: DexHostMetadata, classLoader: ClassLoader): DexTestFeatureReport`.
- Consumes: shared executor/diagnostics, registry metadata, method hashes, and report schema.

- [ ] **Step 1: Write controlled Feature finalization tests**

Use test Features whose `resolveDex` directly sets descriptors/placeholders or throws; do not require
native DexKit calls for these state tests:

```kotlin
@Test
fun thrownDelegateLeavesLaterDelegateBlocked() {
    val result = runFixture(ThrowingFixture)
    assertEquals(DexResolutionStatus.UNEXPECTED_FAILURE, result.delegates[1].status)
    assertEquals(DexResolutionStatus.BLOCKED, result.delegates[2].status)
    assertEquals(result.delegates[1].key, result.delegates[2].blockedBy)
}

@Test
fun normalReturnWithPendingDelegateIsIncomplete() {
    val result = runFixture(IncompleteFixture)
    assertEquals(DexResolutionStatus.INCOMPLETE, result.delegates.single().status)
    assertEquals(DexTestFeatureOutcome.FAIL, result.outcome)
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*DexFeatureRunnerTest'`

- [ ] **Step 3: Implement lazy object initialization and metadata assignment**

```kotlin
val clazz = Class.forName(entry.className, true, classLoader)
val feature = clazz.getField("INSTANCE").get(null) as BaseFeature
check(feature is IResolveDex) { "${entry.className} does not implement IResolveDex" }
feature.name = entry.name
feature.categories = entry.categories
feature.description = entry.description
```

Catch `ExceptionInInitializerError`, `NoClassDefFoundError`, and reflective contract errors as
`INITIALIZATION_FAILURE`. Re-throw `VirtualMachineError` and `ThreadDeath`.

- [ ] **Step 4: Implement execution/finalization**

```kotlin
feature.dexDelegates.forEach(BaseDexDelegate::resetForDexTest)
val error = runCatching { feature.resolveAllDex(dexKit, host) }.exceptionOrNull()

if (error == null) {
    feature.dexDelegates.filter { it.diagnostic.status == PENDING }.forEach { it.markIncomplete() }
} else {
    val failingKey = feature.dexDelegates.firstOrNull { it.diagnostic.status == UNEXPECTED_FAILURE }?.key
        ?: "${entry.className}#resolveDex"
    feature.dexDelegates.filter { it.diagnostic.status == PENDING }.forEach { it.markBlocked(failingKey) }
}
```

Build the Feature outcome from finalized delegate states and look up
`GeneratedMethodHashes.HASHES[entry.className]!!`.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests '*DexFeatureRunnerTest'
git add app/src/test/java/dev/ujhhgtg/wekit/dextest/DexFeatureRunner.kt \
  app/src/test/java/dev/ujhhgtg/wekit/dextest/DexFeatureRunnerTest.kt
git commit -m "test: run and classify dex resolver features"
```

### Task 10: Add the Isolated Worker JVM Entrypoint

**Files:**
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexTestWorkerTest.kt`
- Modify: `app/build.gradle.kts:158-163, 300-312`

**Interfaces:**
- Consumes system properties: `wekit.dexTest.apk`, `wekit.dexTest.nativeLibrary`, `wekit.dexTest.report`, `wekit.dexTest.dexKitVersion`, `wekit.dexTest.dexKitRevision`, `wekit.dexTest.versionCode`, `wekit.dexTest.versionName`, `wekit.dexTest.buildTag`, and `wekit.dexTest.isGooglePlay`.
- Produces one atomic `DexTestApkReport` JSON file and returns normally for resolver failures.
- Produces Gradle worker mode: `-PdexTestWorker=true` runs only `DexTestWorkerTest`; normal unit tests exclude it.

- [ ] **Step 1: Add a failing worker-property test**

Extract property parsing into `DexTestWorkerConfig.fromSystemProperties(Properties)` and test all
required keys plus invalid boolean/number cases.

Run: `./gradlew :app:testStandardDebugUnitTest --tests '*DexTestWorkerConfigTest'`

Expected: FAIL before the config parser exists.

- [ ] **Step 2: Configure test runtime dependencies and worker filtering**

Add:

```kotlin
testImplementation(project(":libs:common:stubs"))
testImplementation(libs.legacyxposed.api)
testImplementation(libs.libxposed.api)
```

Configure `testStandardDebugUnitTest` so normal runs exclude the worker. With
`-PdexTestWorker=true`, include only `dev.ujhhgtg.wekit.dextest.DexTestWorkerTest` and forward the
`wekit.dexTest.*` Gradle properties as JVM system properties. Set `outputs.upToDateWhen { false }` in
worker mode.

- [ ] **Step 3: Implement the worker test**

```kotlin
@Test
fun runDexResolutionWorker() {
    val config = DexTestWorkerConfig.fromSystemProperties(System.getProperties())
    System.load(config.nativeLibrary.toString())

    val started = Instant.now()
    val report = DexKitBridge.create(config.apk.toString()).use { dexKit ->
        val host = DexHostMetadata(config.versionCode, config.versionName, config.isGooglePlay)
        val features = runBlocking(Dispatchers.IO) {
            DexResolutionTestRegistry.ITEMS.map { entry ->
                async { runDexFeature(entry, dexKit, host, javaClass.classLoader) }
            }.chunked(8).flatMap { batch -> batch.awaitAll() }
        }
        buildApkReport(config, dexKit.getDexNum(), features, started, Instant.now())
    }
    report.writeAtomically(config.report)
}
```

Preserve registry order when assembling `features`. Catch bridge/APK/native/report exceptions into an
`INFRASTRUCTURE_FAILURE` report whenever the report path is writable; a process crash is handled by
xtask as a missing-report failure.

- [ ] **Step 4: Verify normal tests exclude the worker**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:testStandardDebugUnitTest -PdexTestWorker=true
```

Expected: the normal suite PASSes without requiring worker properties. The worker-mode command FAILS
with a precise missing-property message until xtask supplies its properties.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/test/java/dev/ujhhgtg/wekit/dextest/DexTestWorkerTest.kt
git commit -m "test: add isolated dex resolution worker"
```

### Task 11: Add xtask CLI, APK Discovery, and Report Paths

**Files:**
- Create: `xtask/src/dex_test.rs`
- Modify: `xtask/src/main.rs:14-22, 107-126, 347-355`
- Modify: `xtask/Cargo.toml`

**Interfaces:**
- Produces: clap `DexTestArgs` and `task_dex_test(args: DexTestArgs) -> Result<()>`.
- Produces: `discover_apks`, `normalize_explicit_apks`, `natural_cmp`, `create_run_dir`, `ApkIdentity`, and `report_file_names`.
- Consumes later: native builder, worker launcher, and renderer in Tasks 12-13.

- [ ] **Step 1: Write failing Rust tests**

```rust
fn make_temp_dir(name: &str) -> PathBuf {
    let suffix = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!("wekit-dex-test-{name}-{}-{suffix}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn explicit_apks_preserve_first_canonical_occurrence() {
    let dir = make_temp_dir("explicit-order");
    let first = dir.join("wechat_8069.apk");
    let second = dir.join("wechat_8074.apk");
    std::fs::write(&first, b"first").unwrap();
    std::fs::write(&second, b"second").unwrap();

    let result = normalize_explicit_apks(&[first.clone(), second.clone(), first.clone()]).unwrap();

    assert_eq!(result, vec![first.canonicalize().unwrap(), second.canonicalize().unwrap()]);
}

#[test]
fn natural_sort_orders_8069_before_8074_and_keeps_play_variant_distinct() {
    let mut names = vec!["wechat_8074.apk", "wechat_8069_3020_play.apk", "wechat_8069.apk"];
    names.sort_by(|a, b| natural_cmp(a, b));
    assert_eq!(names, vec!["wechat_8069.apk", "wechat_8069_3020_play.apk", "wechat_8074.apk"]);
}

#[test]
fn duplicate_filenames_receive_sha_suffixes() {
    let dir = make_temp_dir("duplicate-names");
    let left_dir = dir.join("left");
    let right_dir = dir.join("right");
    std::fs::create_dir_all(&left_dir).unwrap();
    std::fs::create_dir_all(&right_dir).unwrap();
    let left = left_dir.join("wechat_same.apk");
    let right = right_dir.join("wechat_same.apk");
    std::fs::write(&left, b"left-apk").unwrap();
    std::fs::write(&right, b"right-apk").unwrap();

    let names = report_file_names(&[
        ApkIdentity {
            path: left.canonicalize().unwrap(),
            sha256: "11111111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".into(),
        },
        ApkIdentity {
            path: right.canonicalize().unwrap(),
            sha256: "22222222bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".into(),
        },
    ]);

    assert_eq!(names, vec!["wechat_same-11111111.json", "wechat_same-22222222.json"]);
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `cargo test -p xtask dex_test::tests --no-fail-fast`

- [ ] **Step 3: Add CLI and dependency declarations**

```rust
#[derive(Args)]
pub struct DexTestArgs {
    #[arg(long = "apk", value_name = "APK")]
    apks: Vec<PathBuf>,
    #[arg(long, value_name = "DIR")]
    output_dir: Option<PathBuf>,
    #[arg(long)]
    verbose: bool,
}
```

Add `serde_json = "1"` and `time = { version = "0.3", features = ["formatting"] }`.

- [ ] **Step 4: Implement discovery and run directory creation**

- Explicit paths: canonicalize, require regular readable files, and de-duplicate with `HashSet<PathBuf>`.
- Default path: read `HOME`, append `coding`, collect filenames beginning `wechat_` and ending `.apk`.
- Run ID: UTC `YYYY-MM-DDTHH-MM-SSZ`; append `-2`, `-3`, ... on collision.
- Default root: `<repo>/dex-test-results`; explicit `--output-dir` is resolved against the caller's current directory.

- [ ] **Step 5: Run tests and check help text**

```bash
cargo test -p xtask dex_test::tests --no-fail-fast
./x dex-test --help
```

Expected: PASS; help documents default discovery and output path.

- [ ] **Step 6: Commit**

```bash
git add xtask/src/dex_test.rs xtask/src/main.rs xtask/Cargo.toml
git commit -m "feat: add dex-test command and apk discovery"
```

### Task 12: Build Pinned Linux DexKit and Extract APK Metadata

**Files:**
- Modify: `xtask/src/dex_test.rs`

**Interfaces:**
- Produces: `ensure_linux_dexkit(root: &Path) -> Result<DexKitNative>`.
- Produces: `read_apk_metadata(root: &Path, apk: &Path) -> Result<ApkMetadata>`.
- `DexKitNative` contains version, revision, source directory, build directory, and absolute library path.
- `ApkMetadata` contains version code/name, BuildInfo build tag, and `is_google_play`.

- [ ] **Step 1: Write failing parser and command-construction tests**

```rust
#[test]
fn build_tag_parser_detects_google_play() {
    let xml = r#"<meta-data android:name="com.tencent.mm.BuildInfo.BUILD_TAG"
        android:value="Android_Wechat_RELEASE_GP_AppBundle" />"#;
    let meta = parse_manifest_metadata("3020", "8.0.69", xml).unwrap();
    assert!(meta.is_google_play);
}

#[test]
fn cmake_command_targets_desktop_jni_project() {
    let args = cmake_configure_args(Path::new("src"), Path::new("out"));
    assert!(args.windows(2).any(|v| v == ["-S", "src/dexkit/src/main/cpp"]));
    assert!(args.contains(&"-G".into()));
    assert!(args.contains(&"Ninja".into()));
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `cargo test -p xtask dex_test::tests --no-fail-fast`

- [ ] **Step 3: Implement source cache and revision validation**

Read `versions.dexkit` from `gradle/libs.versions.toml`. Cache the exact tag checkout at:

```text
.wekit/dex-test/source/DexKit-<version>/
```

On first use run:

```bash
git clone --depth 1 --branch <version> https://github.com/LuckyPray/DexKit.git <temp-source>
git -C <temp-source> rev-parse HEAD
git -C <temp-source> describe --tags --exact-match
```

Require the exact tag and atomically rename the temporary checkout into the cache. On reuse, validate
the cached HEAD/tag locally without a network request.

- [ ] **Step 4: Implement the verified Linux build command**

Support `x86_64` and `aarch64` Linux hosts and reject other OS/architecture pairs. Configure/build:

```bash
cmake -S <source>/dexkit/src/main/cpp \
  -B .wekit/dex-test/native/<version>/<arch>/cmake \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  "-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG" \
  "-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG" \
  -DDEXKIT_ENABLE_INTERNAL_METRICS=OFF \
  -DDEXKIT_ENABLE_INTERNAL_METRICS_API=OFF
cmake --build .wekit/dex-test/native/<version>/<arch>/cmake --target dexkit
```

Require the absolute output `<cmake-build>/libdexkit.so` and record `git rev-parse HEAD` in reports.
Always invoke incremental configure/build; CMake/Ninja decides whether recompilation is necessary.

- [ ] **Step 5: Implement `apkanalyzer` discovery and metadata parsing**

Locate `apkanalyzer` under `ANDROID_HOME` or `ANDROID_SDK_ROOT` at
`cmdline-tools/latest/bin/apkanalyzer`. Run:

```bash
apkanalyzer manifest version-code <apk>
apkanalyzer manifest version-name <apk>
apkanalyzer manifest print <apk>
```

Parse `com.tencent.mm.BuildInfo.BUILD_TAG`; set `is_google_play` when the tag contains `GP`
case-insensitively. Require application ID `com.tencent.mm` before launching a worker.

- [ ] **Step 6: Add and run an ignored native/metadata smoke check**

Add this ignored Rust test after `ensure_linux_dexkit` and `read_apk_metadata` exist:

```rust
#[test]
#[ignore = "requires Android SDK apkanalyzer, CMake/Ninja, DexKit source cache, and a real WeChat APK"]
fn smoke_native_build_and_apk_metadata() {
    let apk = std::env::var_os("WEKIT_DEX_TEST_SMOKE_APK")
        .map(PathBuf::from)
        .expect("set WEKIT_DEX_TEST_SMOKE_APK=/absolute/path/to/wechat_8069.apk");
    let root = repo_root_for_tests();

    let native = ensure_linux_dexkit(&root).expect("DexKit native build should succeed");
    assert_eq!(native.version, "2.2.0");
    assert_eq!(native.revision, "ffa6c51c38fe3ecfddb18d8949c30c48dbfbfd6a");
    assert!(native.library_path.ends_with("libdexkit.so"));
    assert!(native.library_path.is_absolute());
    let file_output = std::process::Command::new("file")
        .arg(&native.library_path)
        .output()
        .expect("file command should inspect libdexkit.so");
    assert!(String::from_utf8_lossy(&file_output.stdout).contains("ELF"));

    let metadata = read_apk_metadata(&root, &apk).expect("APK metadata should parse");
    assert_eq!(metadata.version_code, 3040);
    assert_eq!(metadata.version_name, "8.0.69");
    assert!(!metadata.is_google_play);
    assert!(metadata.build_tag.contains("Android_Wechat_RELEASE"));
}
```

```bash
cargo test -p xtask dex_test::tests --no-fail-fast
WEKIT_DEX_TEST_SMOKE_APK=$HOME/coding/wechat_8069.apk \
  cargo test -p xtask smoke_native_build_and_apk_metadata -- --ignored --nocapture
```

At this task boundary do not invoke `./x dex-test`; worker orchestration is added in Task 13. Verify
`libdexkit.so` is a Linux ELF shared object and metadata reports version code `3040`, version name
`8.0.69`, and a non-Play build tag for the current fixture.

- [ ] **Step 7: Commit**

```bash
git add xtask/src/dex_test.rs
git commit -m "feat: build pinned desktop DexKit"
```

### Task 13: Orchestrate Workers, Aggregate JSON, and Render Reports

**Files:**
- Modify: `xtask/src/dex_test.rs`

**Interfaces:**
- Consumes: Kotlin schema v1 and Gradle worker properties.
- Produces: one worker invocation per APK, `summary.json`, terminal output, and final exit behavior.

- [ ] **Step 1: Write failing Rust aggregation/rendering tests**

Use inline schema-v1 JSON fixtures for PASS, expected-only, unexpected+blocked, initialization failure,
and missing report cases:

```rust
#[test]
fn expected_failures_do_not_fail_run() {
    let summary = aggregate(vec![report_with_expected_failure()]);
    assert_eq!(summary.outcome, RunOutcome::Pass);
}

#[test]
fn unexpected_and_blocked_results_fail_after_rendering() {
    let summary = aggregate(vec![report_with_unexpected_and_blocked()]);
    assert_eq!(summary.outcome, RunOutcome::Fail);
    assert!(render_summary(&summary, false).contains("BLOCKED"));
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `cargo test -p xtask dex_test::tests --no-fail-fast`

- [ ] **Step 3: Implement the Gradle worker invocation**

For each APK, invoke a separate Gradle process:

```text
./gradlew :app:testStandardDebugUnitTest
  -PdexTestWorker=true
  -Pwekit.dexTest.apk=<absolute-apk>
  -Pwekit.dexTest.nativeLibrary=<absolute-libdexkit.so>
  -Pwekit.dexTest.report=<absolute-per-apk-json>
  -Pwekit.dexTest.dexKitVersion=<version>
  -Pwekit.dexTest.dexKitRevision=<revision>
  -Pwekit.dexTest.versionCode=<version-code>
  -Pwekit.dexTest.versionName=<version-name>
  -Pwekit.dexTest.buildTag=<build-tag>
  -Pwekit.dexTest.isGooglePlay=<true|false>
```

Print the APK label before invocation. After exit, require and deserialize its JSON. If Gradle exits
non-zero or the report is missing/malformed, synthesize an APK `INFRASTRUCTURE_FAILURE` and continue.

- [ ] **Step 4: Implement stable report filenames and `summary.json`**

Use the APK stem when unique. When two explicit paths share a filename, append the first eight
characters of the APK SHA-256. Serialize the run metadata and ordered APK summaries atomically to
`summary.json`.

- [ ] **Step 5: Implement terminal rendering**

Default mode:

- one compact `[PASS]` line per successful Feature;
- expand expected, unexpected, blocked, incomplete, initialization, and infrastructure details;
- print per-APK delegate totals;
- print final cross-version rows in input order.

`--verbose` additionally prints every successful delegate descriptor. Never truncate exception text
in JSON; terminal rendering may show the exception message and report path while keeping the full
stack in JSON.

- [ ] **Step 6: Implement exit behavior**

After every runnable APK finishes and summaries are printed:

```rust
if summary.outcome != RunOutcome::Pass {
    bail!("dex resolution test found failures; reports: {}", run_dir.display());
}
```

Expected failures alone leave `RunOutcome::Pass`.

- [ ] **Step 7: Run Rust tests and one real worker**

```bash
cargo test -p xtask dex_test::tests --no-fail-fast
./x dex-test --apk ~/coding/wechat_8069.apk
```

Expected: native library builds/reuses, one isolated worker runs, terminal results print, and
`dex-test-results/<run-id>/{summary.json,wechat_8069.json}` exist. The command's final exit code is
determined by the real resolver report; a non-zero resolver result is valid evidence, not a worker
infrastructure failure.

- [ ] **Step 8: Commit**

```bash
git add xtask/src/dex_test.rs
git commit -m "feat: run and report desktop dex resolution tests"
```

### Task 14: Document, Exercise All APKs, and Complete Verification

**Files:**
- Modify: `.gitignore`
- Create: `docs/development/linux-dex-test.md`
- Modify as needed: files from prior tasks only when verification exposes a scoped defect.

**Interfaces:**
- Produces: user documentation and a fully verified repository change.

- [ ] **Step 1: Add ignored generated paths**

```gitignore
/.wekit/dex-test/
/dex-test-results/
```

- [ ] **Step 2: Write the user guide**

Document:

```bash
./x dex-test
./x dex-test --apk ~/coding/wechat_8069.apk --apk ~/coding/wechat_8069_3020_play.apk
./x dex-test --output-dir /absolute/report/root --verbose
```

Explain status meanings, process isolation, report paths, first-run CMake/Ninja/git/JDK/Android SDK
requirements, exit codes, and the limitation that source resolution success does not prove hook-time
device behavior.

- [ ] **Step 3: Run all focused automated tests**

```bash
./gradlew -p buildSrc test
./gradlew :app:validateDesktopDexResolvers
./gradlew :app:testStandardDebugUnitTest
cargo test -p xtask --no-fail-fast
git diff --check
```

Expected: PASS.

- [ ] **Step 4: Run explicit normal/Play isolation coverage**

```bash
./x dex-test \
  --apk ~/coding/wechat_8069.apk \
  --apk ~/coding/wechat_8069_3020_play.apk
```

Verify the two reports contain different worker process IDs, version codes `3040` and `3020`,
different BuildInfo tags/Play flags, and no descriptor leakage.

- [ ] **Step 5: Run the default cross-version suite**

Run: `./x dex-test`

Verify every discovered `~/coding/wechat_*.apk` has a separate report and appears in
`summary.json`. Preserve the resulting report as local ignored evidence. Do not change resolver
failure policies to force exit zero; record any genuine expected/unexpected compatibility findings in
the handoff.

- [ ] **Step 6: Run the canonical Android build**

Run: `./x build`

Expected: PASS and rebuilds the Rust native library before packaging, per repository policy.

- [ ] **Step 7: Commit documentation/ignore rules**

```bash
git add .gitignore docs/development/linux-dex-test.md
git commit -m "docs: document Linux dex resolution testing"
```

- [ ] **Step 8: Request independent code review**

Invoke `superpowers:requesting-code-review` against the implementation and fix all accepted findings.
Re-run the focused tests, default suite where affected, `git diff --check`, and `./x build` after fixes.

- [ ] **Step 9: Final evidence check**

```bash
git status --short
git log --oneline --decorate -15
```

Confirm no report/native cache is staged, no unrelated user change was included, and the final handoff
states separately:

- automated tests/builds that passed;
- APKs actually scanned;
- real expected/unexpected/blocked results found;
- the fact that no physical-device hook behavior was tested.
