# WeChat 8.0.77 DexKit Support Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand WeKit's DexKit compatibility gate through WeChat 8.0.77 by repairing the six primary resolver failures while preserving 8.0.65–8.0.76 resolution and runtime hook paths.

**Architecture:** Keep existing resolver declarations and runtime hooks as the source of truth. Add narrow host-aware matcher alternatives only where 8.0.77 changed the host structure; use `DexResolutionContext.host` and DexKit `.data` metadata during resolution. Repair root delegates so the existing diagnostic runner naturally unblocks dependent delegates.

**Tech Stack:** Kotlin, DexKit 2.2.0, Gradle/JUnit Platform, Rust `xtask` (`./x dex-test`, `./x build`), JADX/decompiled WeChat sources.

## Global Constraints

- WeChat 8.0.65–8.0.76 remain compatibility gates and must continue to pass.
- WeChat 8.0.77 becomes a compatibility gate after the repair.
- Required targets remain strict; do not add `allowFailure` to hide a required target miss.
- Resolver-side version selection uses `DexResolutionContext.host`, never `HostInfo`.
- Resolver matchers use DexKit metadata (`delegate.data`) and never JVM reflection or resolved `.method`/`.clazz` during desktop resolution.
- Preserve intended descriptor, signature, string, and structural constraints; do not broaden a matcher solely to obtain a green run.
- Run `./x build`, relevant tests, affected/full DexKit tests, and `git diff --check` before claiming completion.
- Do not add low-value tests for simple matcher declarations or host reflection glue.

---

### Task 1: Reproduce and inventory the six 8.0.77 root failures

**Files:**
- Read: `dex-test-results/2026-08-06T12-19-25Z/wechat_8077.json`
- Read: `app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt`
- Read: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/SplitGroupCall.kt`
- Read: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/CustomLocalFriendAvatars.kt`
- Read: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/HideContacts.kt`
- Read: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt`
- Read: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeMomentsApi.kt`

**Interfaces:**
- Consumes: Existing 8.0.77 report and resolver declarations.
- Produces: A verified six-root failure list and exact source/artifact evidence for each changed descriptor, used by Tasks 2–4.

- [ ] **Step 1: Confirm the root failures and dependency cascades**

Run:

```bash
jq -r '.features[] as $f | $f.delegates[] | select(.status == "UNEXPECTED_FAILURE") | [$f.className,.key,.message] | @tsv' dex-test-results/2026-08-06T12-19-25Z/wechat_8077.json
jq -r '.features[] as $f | $f.delegates[] | select(.status == "BLOCKED") | [.key,.blockedBy] | @tsv' dex-test-results/2026-08-06T12-19-25Z/wechat_8077.json | sort -k2
```

Expected root keys:

```text
PipVoip:methodVoipMpLaunchPage
SplitGroupCall:classSubCoreMultiTalk
CustomLocalFriendAvatars:methodFeatureAvatarSimple1
HideContacts:methodVoipMpInsertMsg
WeConversationListViewApi:methodLegacyGetView
WeMomentsApi:methodAddSightObjectByPath
```

- [ ] **Step 2: Compare each resolver against the 8.0.76 success descriptor**

Run:

```bash
for key in PipVoip SplitGroupCall CustomLocalFriendAvatars HideContacts WeConversationListViewApi WeMomentsApi; do
  jq -r --arg key "$key" '.features[] | .delegates[] | select(.key | startswith($key + ":")) | [$key,.key,.status,.descriptor] | @tsv' dex-test-results/2026-08-04T15-05-42Z/wechat_8076.json
 done
```

Then inspect the exact matcher source with `rg -n -C 15` and inspect the corresponding WeChat 8.0.77 decompiled source or DEX strings. Record only evidence that identifies a unique method/class and preserves the existing semantic signature.

- [ ] **Step 3: Verify the baseline before edits**

Run:

```bash
./x dex-test --apk /home/ujhhgtg/coding/wechat_8076.apk
```

Expected: `outcome=PASS`, with no `UNEXPECTED_FAILURE`, `BLOCKED`, or `INCOMPLETE` delegates. Do not modify code if this baseline regresses.

---

### Task 2: Repair VoIPMP and MultiTalk root matchers

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/SplitGroupCall.kt`

**Interfaces:**
- Consumes: Task 1's verified 8.0.77 descriptors and stable strings.
- Produces: `PipVoip:methodVoipMpLaunchPage` and `SplitGroupCall:classSubCoreMultiTalk` resolving on 8.0.77, with all dependent delegates unblocked.

- [ ] **Step 1: Add the narrowest 8.0.77 matcher alternative**

For each root delegate, preserve the existing 8.0.65–8.0.76 matcher as the first branch. Add the 8.0.77 branch only with evidence-backed class/package, signature, stable log/string, or unique structural predicates. If the root class name remains discoverable by stable strings, keep `searchPackages`/`usingEqStrings`; if only an API/log changed, use the changed 8.0.77 anchor and retain the same semantic class role.

When a downstream matcher needs the root result, use metadata exactly as follows:

```kotlin
val resolvedClassName = rootDelegate.data.name
matcher {
    declaredClass = resolvedClassName
}
```

Do not use `rootDelegate.clazz`, `rootDelegate.method`, `Class.forName`, or reflection in `resolveDex`/inline matcher declarations.

- [ ] **Step 2: Keep runtime hook installation unchanged**

Verify with:

```bash
git diff -- app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/SplitGroupCall.kt
```

Expected: only resolver declarations/host selection change; no `hookBefore`, `hookAfter`, invocation arguments, or feature behavior changes.

- [ ] **Step 3: Run the focused resolver check**

Run:

```bash
./x dex-test --apk /home/ujhhgtg/coding/wechat_8077.apk
```

Expected: the two root delegates report `SUCCESS`, and their dependent delegates no longer report `BLOCKED`. If another root failure remains, stop and return to Task 1 rather than weakening the matcher.

- [ ] **Step 4: Commit the isolated change**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/voip/PipVoip.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/SplitGroupCall.kt
 git commit -m "fix: resolve VoIP targets on WeChat 8.0.77"
```

---

### Task 3: Repair avatar and VoIPMP message root matchers

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/CustomLocalFriendAvatars.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/HideContacts.kt`

**Interfaces:**
- Consumes: Task 1's verified 8.0.77 method evidence.
- Produces: `CustomLocalFriendAvatars:methodFeatureAvatarSimple1` and `HideContacts:methodVoipMpInsertMsg` resolving on 8.0.77, with their dependent delegates unblocked.

- [ ] **Step 1: Extend the avatar matcher without dropping the old constraint**

Keep these shared constraints unchanged:

```kotlin
declaredClass(classAvatarDrawable.data.name)
paramTypes("android.widget.ImageView", "java.lang.String")
returnType(Void.TYPE)
```

Preserve the 8.0.76 `View.invalidate` invocation branch and add the verified 8.0.77 branch anchored by `usingNumbers(0.5f)` only if Task 1 confirms that it uniquely identifies `w.Wi`:

```kotlin
anyOf(
    MethodMatcher().apply {
        addInvoke {
            declaredClass = "android.view.View"
            name = "invalidate"
        }
    },
    MethodMatcher().apply {
        usingNumbers(0.5f)
    },
)
```

Use the repository's existing `anyOf` and matcher imports/API exactly as shown by current source examples. If the 8.0.77 artifact proves a more specific stable anchor, use that instead of the numeric anchor.

- [ ] **Step 2: Extend the VoIPMP insertion matcher with semantic constraints intact**

Keep `methodVoipMpInsertMsg` as a required delegate. Preserve `usingEqStrings("MicroMsg.VoIPMP.Launcher", "insertMsg() called with: toUser = ")`, and add only the verified 8.0.77 class/signature predicate when the old string is moved or shared. Do not replace it with a broad package-wide `paramCount` search.

- [ ] **Step 3: Run the focused resolver check and inspect descriptors**

Run:

```bash
./x dex-test --apk /home/ujhhgtg/coding/wechat_8077.apk
jq -r '.features[] | select(.className == "dev.ujhhgtg.wekit.features.items.contacts.CustomLocalFriendAvatars" or .className == "dev.ujhhgtg.wekit.features.items.contacts.HideContacts") | .delegates[] | [.key,.status,.descriptor,.blockedBy] | @tsv' dex-test-results/$(ls -1dt dex-test-results/* | head -1 | xargs basename)/wechat_8077.json
```

Expected: both roots and all delegates downstream of them are `SUCCESS` or a pre-existing, explicitly documented `EXPECTED_FAILURE`; no `UNEXPECTED_FAILURE` or `BLOCKED` caused by these roots.

- [ ] **Step 4: Commit the isolated change**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/CustomLocalFriendAvatars.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/HideContacts.kt
git commit -m "fix: resolve contact targets on WeChat 8.0.77"
```

---

### Task 4: Repair conversation-list and Moments API matchers

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeMomentsApi.kt`

**Interfaces:**
- Consumes: Task 1's exact 8.0.77 adapter and `UploadPackHelper` evidence.
- Produces: `WeConversationListViewApi` and `WeMomentsApi` resolving on 8.0.77 without changing API hook behavior.

- [ ] **Step 1: Update conversation-list host branching**

Keep the existing signature `(int, android.view.View, android.view.ViewGroup) -> android.view.View` and MVVM matcher. Add the 8.0.77 branch based on the actual adapter transition:

```kotlin
when (hostVersion) {
    "8.0.74", "8.0.76" -> methodLegacyGetView.setPlaceholderDescriptor(
        expectedFailure = true,
        reason = "ConversationWithCacheAdapter is absent in WeChat $hostVersion; MVVM adapter remains required",
    )
    "8.0.77" -> methodLegacyGetView.find(dexKit) {
        // Use the exact 8.0.77 adapter class/log evidence from Task 1.
        matcher {
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
            returnType = "android.view.View"
            // retain the stable 8.0.77 conversation-row evidence
        }
    }
    else -> methodLegacyGetView.find(dexKit) {
        // retain the existing 8.0.65–8.0.73 matcher unchanged
    }
}
```

Do not treat 8.0.77 as an expected failure: it is a supported host. If 8.0.77 uses only the MVVM adapter, resolve the legacy delegate as an explicit expected-failure placeholder with a version-specific reason and ensure `methodMvvmGetView` resolves; use the actual 8.0.77 evidence, not assumption.

- [ ] **Step 2: Update Moments `UploadPackHelper` matcher**

Keep the semantic constraints:

```kotlin
declaredClass(classUploadPackHelper.data.name)
returnType(bool)
paramCount(4)
paramTypes(String::class.java, String::class.java, String::class.java, String::class.java)
```

Retain `addSightObjectByPath` and `UploadPackHelper` evidence where present. If 8.0.77 moved the logical-name instrumentation, anchor the method using the exact equivalent stable body evidence identified in Task 1; do not broaden to all four-String boolean methods.

- [ ] **Step 3: Run the focused resolver check**

Run:

```bash
./x dex-test --apk /home/ujhhgtg/coding/wechat_8077.apk
```

Expected: `WeConversationListViewApi` and `WeMomentsApi` have no unexpected or blocked delegates; an intentional conversation legacy absence is `EXPECTED_FAILURE` only when the 8.0.77 artifact proves it absent and the MVVM target succeeds.

- [ ] **Step 4: Commit the isolated change**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeMomentsApi.kt
git commit -m "fix: resolve conversation and moments targets on WeChat 8.0.77"
```

---

### Task 5: Update the documented supported host matrix

**Files:**
- Modify: `AGENTS.md:58,136,149` (and any exact support-range references discovered by `rg`)
- Modify: `docs/superpowers/specs/2026-08-06-wechat-8077-dexkit-support-design.md` only if validation wording needs the final matrix

**Interfaces:**
- Consumes: Successful resolver implementation from Tasks 2–4.
- Produces: Repository instructions that consistently identify 8.0.65–8.0.77 as the supported compatibility range.

- [ ] **Step 1: Update only support-policy references**

Change each applicable range from `8.0.65–8.0.76` to `8.0.65–8.0.77`, preserving the distinction between supported gates and unrelated historical examples. Do not change feature-specific comments that describe a verified 8.0.65–8.0.76 behavior unless the new 8.0.77 evidence proves that statement is now inaccurate.

- [ ] **Step 2: Verify no stale global range remains**

Run:

```bash
rg -n 'supported.*8\.0\.65|range.*8\.0\.65|8\.0\.65–8\.0\.76|8\.0\.65-8\.0\.76' AGENTS.md app xtask buildSrc docs/superpowers/specs/2026-08-06-wechat-8077-dexkit-support-design.md
```

Expected: only historical/version-specific references remain; the global support policy names 8.0.77.

- [ ] **Step 3: Commit the policy update**

```bash
git add AGENTS.md docs/superpowers/specs/2026-08-06-wechat-8077-dexkit-support-design.md
git commit -m "docs: mark WeChat 8.0.77 supported"
```

---

### Task 6: Validate the complete support expansion

**Files:**
- Read: Latest reports under `dex-test-results/`
- No production files should be modified by validation commands.

**Interfaces:**
- Consumes: Resolver changes and support-policy update from Tasks 2–5.
- Produces: Passing evidence for 8.0.65–8.0.77, build, relevant tests, and clean whitespace.

- [ ] **Step 1: Run the newly supported APK first**

Run:

```bash
./x dex-test --apk /home/ujhhgtg/coding/wechat_8077.apk
```

Expected: `PASS`; zero unexpected, blocked, incomplete, initialization, worker, native-library, metadata, or report failures. Any expected failure must be an explicitly documented version absence.

- [ ] **Step 2: Run every available supported APK separately**

Run each APK separately so its metadata is isolated:

```bash
for apk in /home/ujhhgtg/coding/wechat_8065.apk /home/ujhhgtg/coding/wechat_8067.apk /home/ujhhgtg/coding/wechat_8069.apk /home/ujhhgtg/coding/wechat_8069_3020_play.apk /home/ujhhgtg/coding/wechat_8074.apk /home/ujhhgtg/coding/wechat_8076.apk /home/ujhhgtg/coding/wechat_8077.apk; do
  ./x dex-test --apk "$apk" || exit $?
done
```

Expected: every command exits zero and each report has no unexpected, blocked, or incomplete delegates.

- [ ] **Step 3: Run relevant existing tests**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.dexkit.*' --tests 'dev.ujhhgtg.wekit.dextest.*'
```

Expected: all selected tests pass. Do not add tests for simple matcher declarations.

- [ ] **Step 4: Build through xtask**

Run:

```bash
./x build
```

Expected: debug APK and refreshed native library complete successfully.

- [ ] **Step 5: Perform final integrity checks**

Run:

```bash
git diff --check
git status --short
git log --oneline -8
```

Expected: no whitespace errors; only intentional source/docs changes and pre-existing untracked artifacts remain; all repair commits are visible.

- [ ] **Step 6: Report evidence without overstating runtime validation**

Report the exact DexKit report directories, test command result, build result, and `git diff --check` result. State explicitly that physical-device behavior still requires manual WeChat validation; do not claim desktop resolution proves hook-time behavior.
