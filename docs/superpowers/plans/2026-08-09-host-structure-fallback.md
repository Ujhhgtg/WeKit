# Host Structure Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace five WeChat host-version decisions with Dex-structure probes, placeholder-driven runtime selection, or signature-compatible matchers, and degrade the removed 8.0.77 MultiTalk structures without blocking still-supported call paths.

**Architecture:** Each genuinely split compatibility path gets one new-only Dex probe. A zero-result probe records an expected placeholder and activates the old path; a unique result activates a strict new path, while multiple results and downstream failures remain fatal. `AutoDndAfterJoinGroup` has no semantic path split, so its one matcher accepts both confirmed parameter counts.

**Tech Stack:** Kotlin, DexKit metadata queries, WeKit Dex delegates, Xposed hook runtime, Rust xtask desktop Dex testing.

## Global Constraints

- Work directly on `dev`; do not create a worktree or isolated branch unless the user requests one.
- Preserve unrelated worktree changes and stage only the files belonging to each task.
- Target only supported WeChat 8.0.65–8.0.77 APKs; include normal and Google Play APKs where available.
- Resolver code may use DexKit metadata only; do not read `.method`, `.clazz`, `.field`, or `.constructor` while resolving.
- Only a probe with zero results may fall back. Multiple results, matcher errors, and failures after a successful probe remain visible failures.
- Runtime compatibility selection uses `isPlaceholder` or the actual resolved reflection signature, never host `versionCode`, `versionName`, hard-coded WeChat versions, or equivalent constants.
- Google Play detection is not a host-version check, but no new Google Play branch is required by this change.
- Do not add low-value JVM tests for matcher declarations, placeholder branches, or parameter counts; desktop Dex tests are the automated compatibility gate.
- Do not wrap `hookBefore` or `hookAfter` in `try-catch`/`runCatching`.
- Validate with the affected desktop Dex tests, `./x build`, and `git diff --check`; physical-device behavior remains manually verified.

---

### Task 1: Make MD5 image sending probe the actual host structure

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt:26-33,636-687,1290-1330`

**Interfaces:**
- Consumes: `DexKitBridge.findMethod`, `DexMethodDelegate.setDescriptor`, `setPlaceholderDescriptor(expectedFailure, reason)`, and `isPlaceholder`.
- Produces: `methodImgUploadFeatureServiceSendImage` as the single new-path probe used by both `resolveDex()` and `sendImageByMd5()`.

- [ ] **Step 1: Remove version-only imports from `WeMessageApi`**

Delete these imports if no other reference remains after the task:

```kotlin
import dev.ujhhgtg.wekit.constants.WeChatVersions
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
```

Keep `HostInfo`; unrelated APIs in this file still use its application and filesystem properties.

- [ ] **Step 2: Replace the resolver version predicate with a strict new-structure probe**

Replace the `DexResolutionContext.host` threshold block with a query whose result count explicitly controls the path:

```kotlin
val imageFeatureServiceMethods = dexKit.findMethod {
    matcher {
        declaredClass {
            usingEqStrings(
                "MicroMsg.ImgUpload.MsgImgFeatureService",
                "taskListener",
                "params",
            )
        }
        paramCount(1)
        usingEqStrings("params")
    }
}

when (imageFeatureServiceMethods.size) {
    1 -> {
        methodImgUploadFeatureServiceSendImage.setDescriptor(
            imageFeatureServiceMethods.single()
        )
        methodAppInfoSetAppId.find(dexKit) {
            matcher {
                declaredClass {
                    usingEqStrings(
                        "appinfo",
                        "appid",
                        "version",
                        "appname",
                        "isforceupdate",
                        "messageaction",
                        "messageext",
                        "mediatagname",
                    )
                }
                paramTypes(BString)
                usingNumbers(0)
            }
        }
        ctorNetSceneUploadMsgImg.setPlaceholderDescriptor(
            expectedFailure = true,
            reason = "new image feature service path is active",
        )
    }

    0 -> {
        methodImgUploadFeatureServiceSendImage.setPlaceholderDescriptor(
            expectedFailure = true,
            reason = "ImgUploadFeatureService is absent; using legacy NetSceneUploadMsgImg",
        )
        methodAppInfoSetAppId.setPlaceholderDescriptor(
            expectedFailure = true,
            reason = "legacy NetSceneUploadMsgImg path is active",
        )
        ctorNetSceneUploadMsgImg.find(dexKit) {
            searchPackages("com.tencent.mm.modelimage")
            matcher {
                name = "<init>"
                declaredClass {
                    usingEqStrings(
                        "MicroMsg.NetSceneUploadMsgImg",
                        "/cgi-bin/micromsg-bin/uploadmsgimg",
                    )
                }
                paramTypes(
                    int,
                    BString,
                    BString,
                    BString,
                    int,
                    null,
                    int,
                    BString,
                    BString,
                    bool,
                    int,
                )
            }
        }
    }

    else -> error(
        "multiple ImgUploadFeatureService send methods found: " +
            imageFeatureServiceMethods.joinToString { it.descriptor }
    )
}
```

Do not catch query errors. The new-path companion method and legacy constructor remain strict after the probe has selected their path.

- [ ] **Step 3: Select the runtime send implementation from the probe placeholder**

Change only the condition around the existing two send bodies:

```kotlin
fun sendImageByMd5(toUser: String, md5: String, appMsgAppId: String? = null) {
    if (!methodImgUploadFeatureServiceSendImage.isPlaceholder) {
        val sendImageMethod = methodImgUploadFeatureServiceSendImage.method
        val paramsClass = sendImageMethod.parameterTypes[0]
        val crossParamsClass = paramsClass.reflekt()
            .firstField { type { !it.isBuiltin } }.self.type
        val crossParams = crossParamsClass.createInstance()

        if (appMsgAppId != null) {
            val appInfoClass = methodAppInfoSetAppId.method.declaringClass
            val appInfo = appInfoClass.createInstance()
            methodAppInfoSetAppId.method.invoke(appInfo, appMsgAppId)
            crossParams.reflekt()
                .firstField { type = appInfoClass }
                .set(appInfo)
        }

        val params = paramsClass.createInstance(md5, 1, WeApi.selfWxId, toUser, crossParams)
        sendImageMethod.invoke(
            WeServiceApi.getServiceByClass(sendImageMethod.declaringClass),
            params,
        )
    } else {
        val xml: String?
        val wxId = WeApi.selfWxId
        if (appMsgAppId != null) {
            val json = JSONObject()
            val json2 = JSONObject()
            val json3 = JSONObject()
            json3.put("appid", appMsgAppId)
            json2.put("appinfo", json3)
            json.put("msg", json2)
            xml = JsonToXmlConverter(json, emptyHashSet(), emptyHashSet()).toString()
        } else {
            xml = null
        }
        WeNetSceneApi.sendNetScene(
            ctorNetSceneUploadMsgImg.newInstance(
                4,
                wxId,
                toUser,
                md5,
                1,
                null,
                0,
                xml,
                "",
                true,
                0,
            )
        )
    }
}
```

- [ ] **Step 4: Run boundary Dex tests for old, new, and Google Play image paths**

Run:

```bash
./x dex-test \
  --apk /home/ujhhgtg/coding/wechat_8065.apk \
  --apk /home/ujhhgtg/coding/wechat_8067.apk \
  --apk /home/ujhhgtg/coding/wechat_8069_3020_play.apk \
  --output-dir dex-test-results/host-structure-fallback-image
```

Expected: command exits 0. For `WeMessageApi`, 8.0.65 reports the Feature Service probe and companion method as expected failures while the legacy constructor succeeds; 8.0.67 and 8.0.69 Play resolve the new path and report the legacy constructor as an expected failure. No unexpected, blocked, or incomplete delegate remains.

- [ ] **Step 5: Commit the image-path change**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt
git commit -m "refactor: select image send path by host structure"
```

---

### Task 2: Probe layered original-media support without a version name

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/AutoViewOriginalMedia.kt:12-30,62-106,121-163`

**Interfaces:**
- Consumes: `DexKitBridge.findClass`, Dex class/method delegates, `ClassData.name`, and hook-time `isPlaceholder`.
- Produces: `classMediaGalleryChatLiveBottomBarLayer` as the one new-layout probe controlling both new hooks.

- [ ] **Step 1: Remove the exact-version declaration**

Delete:

```kotlin
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext

private const val LAYERED_ORIGINAL_MEDIA_VERSION = "8.0.76"
```

- [ ] **Step 2: Resolve the new layout by probing its chat-live bottom-bar class**

Keep the common resolvers and `classMediaGalleryVideoBottomBarLayer` strict. Replace the version block and later class lookup with:

```kotlin
val chatLiveBottomBarLayers = dexKit.findClass {
    matcher {
        usingEqStrings("MediaGallery.ChatLiveBottomBarLayer")
    }
}

when (chatLiveBottomBarLayers.size) {
    1 -> classMediaGalleryChatLiveBottomBarLayer.setDescriptor(
        chatLiveBottomBarLayers.single()
    )

    0 -> {
        classMediaGalleryChatLiveBottomBarLayer.setPlaceholderDescriptor(
            expectedFailure = true,
            reason = "chat live-photo bottom bar is absent; using common media controls",
        )
        methodUpdateMediaGalleryVideoOriginButton.setPlaceholderDescriptor(
            expectedFailure = true,
            reason = "layered original-video controls are absent",
        )
        methodBindMediaGalleryChatLiveBottomBar.setPlaceholderDescriptor(
            expectedFailure = true,
            reason = "chat live-photo bottom bar is absent",
        )
        return
    }

    else -> error(
        "multiple MediaGallery.ChatLiveBottomBarLayer classes found: " +
            chatLiveBottomBarLayers.joinToString { it.name }
    )
}

methodUpdateMediaGalleryVideoOriginButton.find(dexKit) {
    matcher {
        declaredClass(classMediaGalleryVideoBottomBarLayer.data.name)
        paramTypes("java.lang.String", "boolean")
        returnType = "void"
        usingEqStrings("getString(...)")
    }
}

methodBindMediaGalleryChatLiveBottomBar.find(dexKit) {
    matcher {
        declaredClass(classMediaGalleryChatLiveBottomBarLayer.data.name)
        paramCount = 1
        returnType = "void"
        usingEqStrings("bindContext", "msgInfo")
    }
}
```

This order ensures that successful probe selection makes both new methods mandatory.

- [ ] **Step 3: Gate both layered hooks with the class probe**

Replace the two independent method-placeholder conditions with one probe condition while preserving the bodies:

```kotlin
if (!classMediaGalleryChatLiveBottomBarLayer.isPlaceholder) {
    methodUpdateMediaGalleryVideoOriginButton.hookAfter {
        if (args[1] as Boolean) return@hookAfter

        val binding = thisObject!!.reflekt().firstField {
            type { !it.isPrimitive }
        }.get()!!
        val originalVideoButton = binding.reflekt().firstField {
            type = Button::class
        }.get() as Button
        clickOriginalMediaButton(originalVideoButton)
    }

    methodBindMediaGalleryChatLiveBottomBar.hookAfter {
        val layer = thisObject!!
        val bindContext = args[0]!!
        if (lastLivePhotoBindings[layer] === bindContext) return@hookAfter
        lastLivePhotoBindings[layer] = bindContext

        val binding = layer.reflekt().firstField {
            type { candidate ->
                candidate.reflekt().fields {
                    type = MEDIA_DOWNLOAD_TEXT_CLASS
                }.isNotEmpty()
            }
        }.get()!!
        val originalImageControl = binding.reflekt().firstField {
            type = MEDIA_DOWNLOAD_TEXT_CLASS
        }.get() as View

        originalImageControl.post {
            if (lastLivePhotoBindings[layer] === bindContext &&
                originalImageControl.isShown &&
                originalImageControl.isEnabled &&
                originalImageControl.hasVisibleOriginalMediaLabel()
            ) {
                originalImageControl.performClick()
            }
        }
    }
}
```

- [ ] **Step 4: Run old/new/8.0.77 Dex boundary tests**

Run:

```bash
./x dex-test \
  --apk /home/ujhhgtg/coding/wechat_8065.apk \
  --apk /home/ujhhgtg/coding/wechat_8076.apk \
  --apk /home/ujhhgtg/coding/wechat_8077.apk \
  --output-dir dex-test-results/host-structure-fallback-original-media
```

Expected: command exits 0. Old layouts produce expected placeholders for the probe and its two new methods; every layout containing the probe strictly resolves both new methods. No version-name branch determines 8.0.77 behavior.

- [ ] **Step 5: Commit the original-media change**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/AutoViewOriginalMedia.kt
git commit -m "refactor: detect layered media controls structurally"
```

---

### Task 3: Match both confirmed AutoDND signatures structurally

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroup.kt:5,52-79`

**Interfaces:**
- Consumes: `FindMethod.matcher.paramCount(Int, Int)` and `MethodData.paramTypeNames`.
- Produces: one strict `methodSyncChatroomMembers` delegate accepting the confirmed 10- and 11-parameter signatures.

- [ ] **Step 1: Remove the resolution-context import and version-derived count**

Delete:

```kotlin
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
```

Do not replace it with another version or channel source.

- [ ] **Step 2: Accept both signatures in the existing unique structural query**

Rewrite `resolveDex()` as:

```kotlin
override fun resolveDex(dexKit: DexKitBridge) {
    val matches = dexKit.findMethod {
        matcher {
            returnType = "boolean"
            paramCount(10, 11)
            usingStrings("MicroMsg.ChatroomMembersLogic", "SyncAddChatroomMember")
        }
    }.filter { method ->
        val params = method.paramTypeNames
        params[0] == "java.lang.String" &&
            params[1] == "java.lang.String" &&
            params[3] == "int" &&
            params[4] == "int" &&
            params[5] == "int" &&
            params[6] == "java.lang.String" &&
            params[8] == "boolean" &&
            params[9] == "boolean" &&
            (params.size == 10 || params[10] == "int") &&
            params[2] !in PRIMITIVE_TYPE_NAMES &&
            params[7] !in PRIMITIVE_TYPE_NAMES
    }

    check(matches.size == 1) {
        "expected one ChatroomMembersLogic sync method, found ${matches.size}: " +
            matches.joinToString { it.descriptor }
    }
    methodSyncChatroomMembers.setDescriptor(matches.single())
}
```

The existing Hook reads only positions shared by both signatures, so do not add a hook-time branch.

- [ ] **Step 3: Run both signature families through desktop Dex resolution**

Run:

```bash
./x dex-test \
  --apk /home/ujhhgtg/coding/wechat_8065.apk \
  --apk /home/ujhhgtg/coding/wechat_8067.apk \
  --apk /home/ujhhgtg/coding/wechat_8077.apk \
  --output-dir dex-test-results/host-structure-fallback-auto-dnd
```

Expected: command exits 0 and `AutoDndAfterJoinGroup:methodSyncChatroomMembers` resolves exactly once on every APK, with no host-version access in the resolver.

- [ ] **Step 4: Commit the AutoDND matcher change**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroup.kt
git commit -m "refactor: match AutoDND sync signatures structurally"
```

---

### Task 4: Treat the direct MultiTalk microphone method as the probe

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt:27,807-832`

**Interfaces:**
- Consumes: the existing `directMicMethods` DexKit query and `methodMultiTalkMic.isPlaceholder` runtime branch.
- Produces: a version-independent expected placeholder only when the direct method query has zero results.

- [ ] **Step 1: Remove the unused resolution-context import**

Delete:

```kotlin
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
```

- [ ] **Step 2: Remove the version guard from the zero-result fallback**

Keep the existing query and result-count switch, but make the zero branch purely structural:

```kotlin
when (directMicMethods.size) {
    1 -> methodMultiTalkMic.setDescriptor(directMicMethods.single())
    0 -> methodMultiTalkMic.setPlaceholderDescriptor(
        expectedFailure = true,
        reason = "direct MultiTalk mic method is absent; using inlined ControlPanelLogic path",
    )
    else -> error(
        "multiple direct MultiTalk mic methods found: " +
            directMicMethods.joinToString { it.descriptor }
    )
}
```

Do not change `toggleMic()`: it already checks `methodMultiTalkMic.isPlaceholder` and calls either the direct method or `toggleLegacyMultiTalkMic(viewModel)`.

- [ ] **Step 3: Run old/new MultiTalk resolver boundaries**

Run:

```bash
./x dex-test \
  --apk /home/ujhhgtg/coding/wechat_8065.apk \
  --apk /home/ujhhgtg/coding/wechat_8067.apk \
  --apk /home/ujhhgtg/coding/wechat_8077.apk \
  --output-dir dex-test-results/host-structure-fallback-multitalk
```

Expected: command exits 0. APKs without the direct method report an expected placeholder and APKs with it resolve exactly one method; no absence is classified from `versionName`.

- [ ] **Step 4: Commit the MultiTalk fallback change**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt
git commit -m "refactor: select MultiTalk mic path structurally"
```

---

### Task 5: Group the MultiTalk structures removed by 8.0.77 behind probes

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/SplitGroupCall.kt`

**Interfaces:**
- Consumes: zero-result DexKit probes, explicit expected placeholders, and hook-time `isPlaceholder`.
- Produces: no probe-caused `UNEXPECTED_FAILURE`/`BLOCKED` on 8.0.77 while preserving single-call PiP and TalkRoom.

- [ ] **Step 1: Make `classMultiTalkViewModel` the old PipVoip MultiTalk probe**

Declare old-MultiTalk-only delegates without inline resolver blocks and resolve them manually after
querying the existing two-string `classMultiTalkViewModel` matcher. A unique probe result makes all
old MultiTalk members strict. A zero result sets expected placeholders for the 14 delegates listed
in the approved design. Multiple probe results remain fatal.

Keep `classObservableState`, `classMutableObservableState`, and `methodObservableValue` as strict,
independent inline delegates because these AndroidX Lifecycle structures still exist on 8.0.77.

- [ ] **Step 2: Gate old MultiTalk hook installation and VoIPMP restore by actual placeholders**

Install `MultiTalkMainUI` session, destroy, leave-hint, and minimize hooks only when
`classMultiTalkViewModel.isPlaceholder` is false. Preserve all single-call hooks. Before invoking
`methodVoipMpLaunchPage`, check that delegate's own `isPlaceholder`; when absent, log and leave the
PiP session closed rather than accessing a placeholder.

- [ ] **Step 3: Make `classSubCoreMultiTalk` the old SplitGroupCall VOIP probe**

Move the old MultiTalk/ILink group into manual strict resolution behind the existing two-string
`classSubCoreMultiTalk` query. On zero results, set expected placeholders for the 14 old VOIP
delegates listed in the approved design. Keep all four TalkRoom delegates inline and strict.

- [ ] **Step 4: Expose only actual SplitGroupCall capabilities**

Use `classSubCoreMultiTalk.isPlaceholder` to omit `OperationMode.VOIP` from the dialog on hosts
without the old stack. Keep `WALKIE_TALKIE` available. Check the same actual probe before starting
a VOIP batch so no placeholder delegate can be reached through a stale selection.

- [ ] **Step 5: Run the 8.0.76/8.0.77 boundary resolver check**

Run:

```bash
./x dex-test \
  --apk /home/ujhhgtg/coding/wechat_8076.apk \
  --apk /home/ujhhgtg/coding/wechat_8077.apk \
  --output-dir dex-test-results/host-structure-fallback-voip-8077
```

Expected: 8.0.76 resolves both old groups strictly. On 8.0.77 the two groups are expected
placeholders, Lifecycle and TalkRoom remain successful, and neither feature has an unexpected,
blocked, or incomplete delegate.

- [ ] **Step 6: Commit the grouped fallback**

```bash
git add \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/SplitGroupCall.kt
git commit -m "fix: degrade removed MultiTalk structures structurally"
```

---

### Task 6: Verify the complete supported matrix and version-check removal

**Files:**
- Verify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt`
- Verify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/AutoViewOriginalMedia.kt`
- Verify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroup.kt`
- Verify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt`
- Verify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/SplitGroupCall.kt`

**Interfaces:**
- Consumes: all four implementation tasks and the repository's xtask validation commands.
- Produces: fresh per-APK JSON reports plus an aggregate summary for every available supported APK, a fresh debug APK build, and a clean static compatibility scan.

- [ ] **Step 1: Confirm the five targeted decisions no longer read host versions**

Run:

```bash
if rg -n \
  'DexResolutionContext\.host|HostInfo\.(versionCode|versionName)|WeChatVersions|LAYERED_ORIGINAL_MEDIA_VERSION|"8\.0\.65"|"8\.0\.76"' \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/AutoViewOriginalMedia.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroup.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt
then
  exit 1
fi
```

Expected: no output for the removed compatibility decisions. If unrelated `HostInfo` uses remain in `WeMessageApi`, narrow the scan to the image resolver and `sendImageByMd5()` and verify they are not version reads.

- [ ] **Step 2: Run the full available supported APK matrix**

Run:

```bash
./x dex-test \
  --apk /home/ujhhgtg/coding/wechat_8065.apk \
  --apk /home/ujhhgtg/coding/wechat_8067.apk \
  --apk /home/ujhhgtg/coding/wechat_8069.apk \
  --apk /home/ujhhgtg/coding/wechat_8069_3020_play.apk \
  --apk /home/ujhhgtg/coding/wechat_8074.apk \
  --apk /home/ujhhgtg/coding/wechat_8076.apk \
  --apk /home/ujhhgtg/coding/wechat_8077.apk \
  --output-dir dex-test-results/host-structure-fallback-final
```

Expected: command exits 0. Every report and the aggregate summary are `PASS`; no initialization, worker, native-library, metadata, unexpected, blocked, or incomplete failure exists. Placeholder diagnostics align with actual structure presence rather than APK version metadata.

- [ ] **Step 3: Build through xtask**

Run:

```bash
./x build
```

Expected: command exits 0 and refreshes/packages the native library and debug APK through xtask.

- [ ] **Step 4: Run final whitespace and worktree checks**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` exits 0. `git status --short` contains only intentional task artifacts or unrelated changes that were present and preserved.

- [ ] **Step 5: Record the validation boundary in the handoff**

Report the exact Dex result directory, matrix outcome, `./x build` result, and static scan result. State explicitly that desktop resolution and compilation do not prove physical-device behavior for MD5 image sending, original-media auto-clicking, AutoDND, or MultiTalk microphone control.
