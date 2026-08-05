# ViewStickerAsImage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a switchable feature that opens sticker messages in WeChat’s native image viewer, preferring WeChat’s decoded file, then a reusable GIF cache, then a PNG snapshot.

**Architecture:** `WeMessageApi` owns the reusable EmojiInfo decrypt/WXGF-to-GIF/write pipeline. `ViewStickerAsImage` owns mandatory Dex resolution, synchronous click interception, path selection, Activity resolution, snapshot fallback, and viewer launch. A small Android-free logic file contains deterministic MD5, scaling, and retention decisions used directly by the runtime implementation; no new JVM test is added for this host-integrated feature.

**Tech Stack:** Kotlin, Android Views/graphics, Xposed hook utilities, DexKit DSL, `reflekt`, `java.nio.file`, xtask (`./x`).

## Global Constraints

- Target only WeChat `com.tencent.mm` versions 8.0.65, 8.0.67, 8.0.69, 8.0.69 Google Play, 8.0.74, and 8.0.76.
- Run only in the WeChat main process through the existing `SwitchFeature` lifecycle.
- All sticker Dex delegates are mandatory; none may use `allowFailure` or an expected-failure placeholder.
- Resolver dependencies must use delegate `.data` metadata, never `.method`, `.clazz`, `Class.forName`, or reflection-derived types.
- Never wrap the `hookBefore` body in `try/catch` or `runCatching`.
- Runtime reflection over host objects must use `reflekt` unless invoking a specifically Dex-resolved `Method`.
- Keep the click flow synchronous; consume the original click only after `startActivity()` returns without throwing.
- Do not add JVM tests for this feature: its core behavior is host hook, Android UI, filesystem, native conversion, or runtime reflection. Do not add low-value identity-function tests merely to satisfy a workflow.
- Build through `./x build`; direct Gradle assembly can package a stale Rust native library.
- Do not modify `AntiStatusDeletion`, `WeTextStatusApi`, conversation-list files, or generated feature registries.
- Produce one implementation commit: `feat: view sticker messages as images`.

---

## File Map

- Create `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ViewStickerAsImageLogic.kt` — pure MD5, size, retention, and click-consumption decisions.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt:1919-1949` — reusable atomic sticker decoding API while preserving `saveStickerByMd5`.
- Create `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ViewStickerAsImage.kt` — feature, Dex resolvers, hook, native-path lookup, caches, snapshot, and viewer launch.

## Stable Interfaces

```kotlin
internal data class StickerPixelSize(val width: Int, val height: Int)

internal data class PreviewFileMetadata(
    val name: String,
    val lastModifiedMillis: Long,
)

internal fun resolveStickerMd5(
    imagePath: String?,
    messageXml: String,
): String?

internal fun scaleStickerSnapshot(
    width: Int,
    height: Int,
    maxDimension: Int = 2048,
): StickerPixelSize?

internal fun previewFilesToDelete(
    existing: List<PreviewFileMetadata>,
    oldFilesToKeep: Int = 10,
): List<PreviewFileMetadata>

```

```kotlin
fun WeMessageApi.decodeStickerToFile(
    md5: String,
    destination: Path,
): Path?
```

`saveStickerByMd5(md5: String, fileName: String? = null): String?` remains source-compatible and delegates to `decodeStickerToFile`.

---

### Task 1: Add the Shared Sticker Decision Helpers

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ViewStickerAsImageLogic.kt`

**Interfaces:**
- Consumes: `XmlUtils.extractXmlAttr(String, String): String` and `XmlUtils.extractXmlTag(String, String): String`.
- Produces: all pure interfaces listed in “Stable Interfaces”.

- [ ] **Step 1: Implement the shared runtime helpers**

Create `ViewStickerAsImageLogic.kt` with the exact pure helpers below. These are implementation extraction points used by the Android feature, not a reason to add a new test suite:

```kotlin
package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.serialization.XmlUtils
import kotlin.math.roundToInt

internal data class StickerPixelSize(val width: Int, val height: Int)

internal data class PreviewFileMetadata(
    val name: String,
    val lastModifiedMillis: Long,
)

internal fun resolveStickerMd5(
    imagePath: String?,
    messageXml: String,
): String? = imagePath?.trim()?.takeIf { it.isNotEmpty() }
    ?: XmlUtils.extractXmlAttr(messageXml, "md5").trim().takeIf { it.isNotEmpty() }
    ?: XmlUtils.extractXmlTag(messageXml, "md5").trim().takeIf { it.isNotEmpty() }

internal fun scaleStickerSnapshot(
    width: Int,
    height: Int,
    maxDimension: Int = 2048,
): StickerPixelSize? {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return null
    val scale = minOf(1.0, maxDimension.toDouble() / maxOf(width, height))
    return StickerPixelSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}

internal fun previewFilesToDelete(
    existing: List<PreviewFileMetadata>,
    oldFilesToKeep: Int = 10,
): List<PreviewFileMetadata> {
    require(oldFilesToKeep >= 0)
    return existing.sortedByDescending { it.lastModifiedMillis }.drop(oldFilesToKeep)
}
```

- [ ] **Step 2: Compile the helper boundary**

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. Confirm the helper file imports no Android, DexKit, hook, filesystem, reflection, or WeChat host type. Do not add a test file for these straightforward helpers.

---

### Task 2: Extract Reusable Sticker Decode and Atomic Write

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt:1919-1949`

**Interfaces:**
- Consumes: current `WeServiceApi.getEmojiInfoByMd5`, EmojiFileEncryptMgr reflection, and `MMWXGFJNI.nativeWxamToGif` pipeline.
- Produces: `decodeStickerToFile(md5: String, destination: Path): Path?` while preserving `saveStickerByMd5`.

- [ ] **Step 1: Add the reusable decode method beside `saveStickerByMd5`**

Implement this control flow, retaining the existing `reflekt` calls that obtain bytes:

```kotlin
fun decodeStickerToFile(md5: String, destination: Path): Path? {
    var temporary: Path? = null
    return try {
        if (destination.isRegularFile() && destination.fileSize() > 0L) {
            Files.setLastModifiedTime(destination, FileTime.fromMillis(System.currentTimeMillis()))
            return destination
        }

        destination.parent.createDirectories()
        val emojiInfo = WeServiceApi.getEmojiInfoByMd5(md5)
        val emojiFileEncryptMgr = classEmojiFileEncryptMgr.reflekt()
            .firstMethod {
                modifiers(Modifiers.STATIC)
                parameterCount = 0
            }
            .invokeStatic()!!
        val encryptedBytes = emojiFileEncryptMgr.reflekt()
            .firstMethod {
                parameters(IEmojiInfo::class)
                returnType = ByteArray::class
            }
            .invoke(emojiInfo) as ByteArray
        val gifBytes = MMWXGFJNI.nativeWxamToGif(encryptedBytes)
        check(gifBytes.isNotEmpty()) { "converted sticker GIF is empty" }

        temporary = Files.createTempFile(destination.parent, ".${destination.name}.", ".tmp")
        temporary.outputStream().use { output -> output.write(gifBytes) }
        check(temporary.isRegularFile() && temporary.fileSize() > 0L) {
            "temporary sticker GIF is empty"
        }

        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        temporary = null
        check(destination.isRegularFile() && destination.fileSize() > 0L) {
            "final sticker GIF is empty"
        }
        destination
    } catch (error: Exception) {
        WeLogger.e(TAG, "decodeStickerToFile failed for md5=$md5", error)
        null
    } finally {
        temporary?.deleteIfExists()
    }
}
```

Add the needed `java.nio.file`/`kotlin.io.path` imports matching existing project style. Catch only the expected decode/filesystem boundary inside this helper; do not alter hook exception policy.

- [ ] **Step 2: Delegate the existing save API without changing its contract**

Replace only the `saveStickerByMd5` body:

```kotlin
fun saveStickerByMd5(md5: String, fileName: String? = null): String? {
    val outPath = KnownPaths.downloads /
        (fileName ?: "sticker_${System.currentTimeMillis()}.gif")
    return decodeStickerToFile(md5, outPath)?.absolutePathString()
}
```

- [ ] **Step 3: Compile the affected source**

Run:

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. Do not add a JVM test for EmojiInfo, native WXGF conversion, Android storage, or NIO behavior coupled to the runtime pipeline.

---

### Task 3: Resolve the Five Mandatory Sticker Host Methods

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ViewStickerAsImage.kt`

**Interfaces:**
- Consumes: Dex delegate `.data` properties and `DexResolutionContext` rules.
- Produces: five non-placeholder delegates used by the runtime hook.

- [ ] **Step 1: Declare the feature and required delegates**

```kotlin
@Feature(
    name = "表情消息以图片打开",
    categories = ["聊天"],
    description = "点击聊天中的贴纸时使用微信原生图片查看器打开",
)
object ViewStickerAsImage : SwitchFeature(), IResolveDex {
    private const val TAG = "ViewStickerAsImage"

    private val methodEmojiClickHandler by dexMethod()
    private val methodEmojiClickEntry by dexMethod()
    private val methodEmojiResolverGetter by dexMethod()
    private val methodResolveEmojiInfo by dexMethod()
    private val methodGetEmojiDecryptPath by dexMethod()
}
```

- [ ] **Step 2: Resolve the handler and three-argument entry through `.data`**

```kotlin
override fun resolveDex(dexKit: DexKitBridge) {
    methodEmojiClickHandler.find(dexKit) {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            paramCount = 1
            returnType = "void"
            usingEqStrings("MicroMsg.EmojiClickListener", "exit in teen mode")
        }
    }

    methodEmojiClickEntry.find(dexKit) {
        matcher {
            declaredClass(methodEmojiClickHandler.data.declaredClassName)
            paramTypes(
                "android.view.View",
                null,
                null,
            )
            returnType = "void"
        }
    }

    resolveEmojiMethods(dexKit)
}
```

- [ ] **Step 3: Resolve the EmojiInfo chain through metadata**

Implement `resolveEmojiMethods` so each dependency uses the preceding `MethodData` only:

```kotlin
private fun resolveEmojiMethods(dexKit: DexKitBridge) {
    val resolveEmojiInfo = dexKit.findMethod {
        matcher {
            paramTypes(methodEmojiClickEntry.data.paramTypeNames[2])
            returnType = "com.tencent.mm.storage.emotion.EmojiInfo"
        }
    }.single { !Modifier.isStatic(it.modifiers) }
    methodResolveEmojiInfo.setDescriptor(resolveEmojiInfo)

    methodEmojiResolverGetter.find(dexKit) {
        searchPackages("com.tencent.mm.feature.emoji")
        matcher {
            paramCount = 0
            returnType = methodResolveEmojiInfo.data.declaredClassName
        }
    }

    methodGetEmojiDecryptPath.find(dexKit) {
        matcher {
            declaredClass = "com.tencent.mm.storage.emotion.EmojiInfo"
            paramCount = 0
            returnType = "java.lang.String"
            usingEqStrings(
                "MicroMsg.emoji.EmojiInfo",
                "[cpan] get icon path failed. product id and md5 are null.",
                "decrypt/",
                "getDecryptPath decrypt %s",
            )
        }
    }
}
```

Resolve in this exact order: handler, entry, non-static `MsgInfo → EmojiInfo`, zero-argument getter returning that concrete resolver class, then `EmojiInfo → getDecryptPath()`. The 8.0.65 handler takes a tag wrapper, so the MsgInfo type always comes from `methodEmojiClickEntry.data.paramTypeNames[2]`, not the handler parameter. Filtering the two same-signature candidates by `!Modifier.isStatic(it.modifiers)` selects the resolver and excludes the static chatting-component wrapper using desktop-safe `MethodData` metadata.

The runtime receiver contract is verified across all inspected hosts: `WeServiceApi.emojiFeatureService` obtains the service singleton through the existing static ServiceManager delegate, and the Emoji resolver getter is an **instance** method invoked on that singleton. Use the existing API rather than duplicating service-manager reflection:

```kotlin
val resolver = methodEmojiResolverGetter.method
    .invoke(WeServiceApi.emojiFeatureService)!!
val emojiInfo = methodResolveEmojiInfo.method
    .invoke(resolver, hostMessage)!!
```

The getter is never invoked with a null receiver. Its declared return type is the concrete resolver class found above, so the getter matcher uses `methodResolveEmojiInfo.data.declaredClassName`; runtime invocation still goes through the existing emoji feature service singleton.

- [ ] **Step 4: Run the complete supported Dex matrix**

```bash
./x dex-test \
  --apk ~/coding/wechat_8065.apk \
  --apk ~/coding/wechat_8067.apk \
  --apk ~/coding/wechat_8069.apk \
  --apk ~/coding/wechat_8069_3020_play.apk \
  --apk ~/coding/wechat_8074.apk \
  --apk ~/coding/wechat_8076.apk \
  --output-dir dex-test-results/view-sticker-as-image-resolvers \
  --verbose
```

Expected: all five `ViewStickerAsImage` delegates are `SUCCESS` in every report; no `EXPECTED_FAILURE`, `UNEXPECTED_FAILURE`, `BLOCKED`, or `INCOMPLETE`. If a matcher fails, use the systematic-debugging skill and strengthen it from host-source evidence; never add `allowFailure` to make the report green.

---

### Task 4: Implement Synchronous Click Path Selection and Viewer Launch

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ViewStickerAsImage.kt`

**Interfaces:**
- Consumes: `WeMessageApi.decodeStickerToFile`, the five delegates, `MessageInfo`, `Context.baseActivity`, `getTopMostActivity`, and pure logic from Task 1.
- Produces: complete feature behavior except snapshot rendering, which Task 5 adds.

- [ ] **Step 1: Install only the three-argument entry hook**

```kotlin
override fun onEnable() {
    methodEmojiClickEntry.hookBefore {
        val clickedView = args[0] as View
        val hostMessage = args[2]!!
        val messageInfo = MessageInfo(hostMessage)
        if (messageInfo.type?.isSticker != true) return@hookBefore

        val activity = resolveUsableActivity(clickedView) ?: return@hookBefore
        val path = resolveViewerPath(clickedView, hostMessage, messageInfo) ?: return@hookBefore
        if (startNativeImageViewer(activity, path)) {
            result = null
        }
    }
}
```

Do not wrap this block. Direct argument casts and the non-null message express the verified host contract.

- [ ] **Step 2: Resolve the usable Activity**

```kotlin
private fun resolveUsableActivity(view: View): Activity? {
    val fromView = view.context.baseActivity
    if (fromView != null && !fromView.isFinishing && !fromView.isDestroyed) return fromView
    return getTopMostActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
}
```

- [ ] **Step 3: Invoke WeChat’s native decrypt path first**

Use the resolved getter, resolver, and decrypt-path methods. The exact receiver sequence is:

```kotlin
private fun resolveWechatDecodedPath(hostMessage: Any): Path? {
    return try {
        val resolver = methodEmojiResolverGetter.method
            .invoke(WeServiceApi.emojiFeatureService)!!
        val emojiInfo = methodResolveEmojiInfo.method
            .invoke(resolver, hostMessage)!!
        val path = (methodGetEmojiDecryptPath.method.invoke(emojiInfo) as String).asPath
        path.takeIf { it.isAbsolute && it.isRegularFile() && it.fileSize() > 0L }
    } catch (error: Exception) {
        WeLogger.e(TAG, "failed to resolve WeChat sticker decrypt path", error)
        null
    }
}
```

- [ ] **Step 4: Add the bounded decoded-GIF cache**

```kotlin
private fun resolveCachedGif(messageInfo: MessageInfo): Path? {
    val md5 = resolveStickerMd5(messageInfo.imagePath, messageInfo.content) ?: run {
        WeLogger.e(TAG, "failed to resolve sticker md5")
        return null
    }
    val directory = KnownPaths.moduleCache / "view-sticker-as-image" / "decoded"
    val destination = directory / "$md5.gif"
    if (!destination.isRegularFile() || destination.fileSize() <= 0L) {
        prunePreviewDirectory(directory, ".gif")
    }
    return WeMessageApi.decodeStickerToFile(md5, destination)
        ?.takeIf { it.isRegularFile() && it.fileSize() > 0L }
}
```

`prunePreviewDirectory` must create the directory, project regular matching files to `PreviewFileMetadata`, use `previewFilesToDelete`, delete selected files, and log cleanup failures as warnings without aborting the main flow.

- [ ] **Step 5: Preserve the exact three-level path priority**

```kotlin
private fun resolveViewerPath(
    clickedView: View,
    hostMessage: Any,
    messageInfo: MessageInfo,
): Path? = resolveWechatDecodedPath(hostMessage)
    ?: resolveCachedGif(messageInfo)
    ?: createSnapshot(clickedView)
```

- [ ] **Step 6: Launch `ShowImageUI` and return request acceptance**

```kotlin
private fun startNativeImageViewer(activity: Activity, imagePath: Path): Boolean {
    return try {
        activity.startActivity(
            Intent().apply {
                component = ComponentName(activity.packageName, "com.tencent.mm.ui.tools.ShowImageUI")
                putExtra("key_image_path", imagePath.absolutePathString())
            },
        )
        true
    } catch (error: Exception) {
        WeLogger.e(TAG, "failed to start WeChat image viewer", error)
        false
    }
}
```

A `true` return means only that `startActivity` did not throw.

---

### Task 5: Add the PNG Snapshot Fallback

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ViewStickerAsImage.kt`

**Interfaces:**
- Consumes: `scaleStickerSnapshot`, `previewFilesToDelete`, and `KnownPaths.moduleCache`.
- Produces: `createSnapshot(clickedView: View): Path?` used as path priority three.

- [ ] **Step 1: Find the first drawable ImageView depth-first**

```kotlin
private fun findDrawableImageView(view: View): ImageView? {
    if (view is ImageView && view.drawable != null) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            findDrawableImageView(view.getChildAt(index))?.let { return it }
        }
    }
    return null
}
```

- [ ] **Step 2: Render and write the snapshot**

Implement `createSnapshot` with these exact operations:

```kotlin
private fun createSnapshot(clickedView: View): Path? {
    val imageView = findDrawableImageView(clickedView) ?: return null
    val drawable = imageView.drawable
    val sourceWidth = imageView.width.takeIf { it > 0 } ?: drawable.intrinsicWidth
    val sourceHeight = imageView.height.takeIf { it > 0 } ?: drawable.intrinsicHeight
    val outputSize = scaleStickerSnapshot(sourceWidth, sourceHeight) ?: return null
    val directory = KnownPaths.moduleCache / "view-sticker-as-image" / "snapshots"
    prunePreviewDirectory(directory, ".png")
    val output = Files.createTempFile(directory, "sticker-preview-", ".png")
    val bitmap = Bitmap.createBitmap(
        outputSize.width,
        outputSize.height,
        Bitmap.Config.ARGB_8888,
    )
    return try {
        val canvas = Canvas(bitmap)
        canvas.scale(
            outputSize.width.toFloat() / sourceWidth,
            outputSize.height.toFloat() / sourceHeight,
        )
        if (imageView.width > 0 && imageView.height > 0) {
            imageView.draw(canvas)
        } else {
            drawable.setBounds(0, 0, sourceWidth, sourceHeight)
            drawable.draw(canvas)
        }
        output.outputStream().buffered().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        output.takeIf { it.isRegularFile() && it.fileSize() > 0L }
    } catch (error: Exception) {
        output.deleteIfExists()
        WeLogger.e(TAG, "failed to create sticker snapshot", error)
        null
    } finally {
        bitmap.recycle()
    }
}
```

Ensure `prunePreviewDirectory` calls `createDirectories()` before `Files.createTempFile`.

- [ ] **Step 3: Compile the completed snapshot path**

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: the command exits zero. Do not add Bitmap/View or helper identity tests.

---

### Task 6: Verify the Feature and Create Its Single Commit

**Files:**
- Verify all files from Tasks 1–5.

**Interfaces:**
- Produces: one independently shippable sticker feature commit.

- [ ] **Step 1: Run the final supported Dex matrix after all resolver edits**

Use the Task 3 command with output directory `dex-test-results/view-sticker-as-image-final`. Expected: all five delegates succeed on all six APK variants, with no blocked/incomplete result.

- [ ] **Step 2: Run the relevant existing test suite**

```bash
./gradlew :app:testStandardDebugUnitTest
```

Expected: the existing standard debug JVM suite passes. This feature adds no new JVM test file.

- [ ] **Step 3: Build through xtask**

```bash
./x build
```

Expected: debug APK build completes successfully after native-library refresh.

- [ ] **Step 4: Check the exact change scope and whitespace**

```bash
git diff --check
git status --short
git diff --name-only
```

Expected implementation paths are only the four files in this plan. The already-reviewed design/plan documents may also be present as documentation changes, but the implementation commit must not stage them or `.claude/`.

- [ ] **Step 5: Record device-only acceptance separately**

On real WeChat, verify type-47 and Sogou stickers, native decoded path, first GIF conversion, cache hit, PNG fallback, 2048px cap, original-click fallback on all handled failures, cache retention at 11 files, normal-message non-interference, and disable behavior. Do not describe desktop tests as proving these behaviors.

- [ ] **Step 6: Commit only the sticker implementation**

```bash
git add \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ViewStickerAsImage.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ViewStickerAsImageLogic.kt
git commit -m "feat: view sticker messages as images"
```

Do not include conversation-list, status, `.claude/`, design, or plan files in this commit.
