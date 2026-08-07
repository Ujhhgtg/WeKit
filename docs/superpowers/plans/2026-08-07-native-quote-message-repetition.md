# Native Quote Message Repetition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve WeChat quote messages when `RepeatMessages` repeats them by using WeChat's native quote/text builder for both referenced-message sends and existing type-57 retransmission.

**Architecture:** Extend `WeMessageApi` with server-ID and local-ID native quote-send operations. Resolve WeChat's stable native builder factory/executor from the `MsgRetransmitUI` type-57 branch, derive host-specific obfuscated descriptors from Dex metadata, and pass destination, title, scene, and source local ID/talker to the builder. Route `RepeatMessages` quotes through the server-ID operation first and the local-ID retransmission operation second; never hand-build or resend stored AppMsg XML.

**Tech Stack:** Kotlin, Android reflection through `reflekt`, DexKit DSL and `IResolveDex`, WeChat native send CGI builder, MMKV-independent message APIs, `./x dex-test`, Gradle/Rust build through `./x`.

## Global Constraints

- Target WeChat versions are 8.0.65–8.0.77, with separate normal and Google Play APK validation where available.
- Use WeChat's native quote/text send path; do not send packets or manually construct AppMsg XML.
- The public server-ID API signature is `sendQuoteText(talker: String, quotedMsgSvrId: Long, content: String): Boolean`.
- The local-ID native operation is `sendQuoteTextByMsgId(talker: String, quotedMsgId: Long, content: String): Boolean`.
- `RepeatMessages` owns primary/fallback orchestration; `WeMessageApi` does not accept original quote XML.
- Preserve the existing quote's embedded `refermsg` by giving the existing type-57 source local ID/talker to WeChat's native builder.
- Resolver branches must use `DexResolutionContext.host`, not `HostInfo`.
- Resolver matchers must use Dex metadata, not JVM reflection over WeChat classes.
- Do not set `allowFailure` for the native builder targets expected on every supported version.
- Do not add low-value JVM tests for host reflection, DexKit glue, or WeChat runtime behavior.
- Always validate with `./x`, never Gradle alone, when native or resolver code changes.

---

### Task 1: Resolve and wrap WeChat's native quote/text builder

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt:89-550` for delegates, resolution, and runtime wrappers.
- Read-only references: `/home/ujhhgtg/coding/wechat_8065/app/src/main/java/com/tencent/mm/ui/transmit/MsgRetransmitUI.java:2804-2853`, `/home/ujhhgtg/coding/wechat_8076/app/src/main/java/com/tencent/mm/ui/transmit/MsgRetransmitUI.java:2206-2251`, `/home/ujhhgtg/coding/wechat_8065/app/src/main/java/qv0/u1.java:8-20`, `/home/ujhhgtg/coding/wechat_8076/app/src/main/java/y11/s1.java:8-20`, `/home/ujhhgtg/coding/wechat_8065/app/src/main/java/qv0/t1.java:62-123`, `/home/ujhhgtg/coding/wechat_8076/app/src/main/java/y11/r1.java:70-123`, `/home/ujhhgtg/coding/wechat_8065/app/src/main/java/qv0/o1.java:8-23`, `/home/ujhhgtg/coding/wechat_8076/app/src/main/java/y11/n1.java:9-24`.

**Interfaces:**
- Consumes: existing `classMsgInfo`, `methodGetMsgInfoByTalkerAndSvrId`, `WeNetSceneApi`/native runtime conventions, `MessageInfo.id`, `MessageInfo.serverId`, and `MessageInfo.talker`.
- Produces: two resolved native operations used by Task 2:
  - `sendQuoteText(talker: String, quotedMsgSvrId: Long, content: String): Boolean`
  - `sendQuoteTextByMsgId(talker: String, quotedMsgId: Long, content: String): Boolean`

- [ ] **Step 1: Add resolver delegates for the native builder factory, builder execution, and source-message metadata.**

  Anchor the retransmit branch with the stable strings from `MsgRetransmitUI`: `"processAppMessageTransfer error: app content null"`, `"MicroMsg.MsgRetransmitUI"`, and the type-57 branch constants `53` and `57`. Resolve the static builder factory by following the anchored retransmit method's invocation that accepts a destination `String` and returns the builder object; resolve the builder class from that return-type descriptor. Resolve the builder's methods by their stable runtime signatures and metadata rather than obfuscated names:

  - destination setter: one `String` parameter, returns the builder type;
  - content/title setter: one `String` parameter, returns the builder type;
  - scene setter: one `int` parameter, returns the builder type;
  - source-info setter: one `ForwardInfo`-like parameter whose fields are `(long localMessageId, String talker, ...)`, returns the builder type;
  - builder factory: static one-`String` parameter returning the builder type;
  - builder conversion/execution: no-argument method returning the native send task, followed by the task's no-argument Boolean-returning execution method.

  Use the returned builder and task descriptors from `.data.returnTypeName`/`.data.paramTypeNames` to keep the resolver desktop-safe. If the source-info class differs by host, resolve it from the source setter's parameter descriptor and set its local-ID and talker fields by stable field order/types: the native source object is documented by `wechat_8065/zt0/v6.java:7-28` and `wechat_8076/e01/h7.java:7-31`, where local message ID is the first `long` field and talker is the first `String` field used by the builder branch.

- [ ] **Step 2: Verify the resolver on one supported APK before writing runtime code.**

  Run:

  ```bash
  ./x dex-test --apk ~/coding/wechat_8069.apk --output-dir dex-test-results/native-quote-plan-8069
  ```

  Expected: the WeMessageApi delegates resolve without `UNEXPECTED_FAILURE`, `BLOCKED`, or `INCOMPLETE`. If the candidate matcher is ambiguous, tighten it with the anchored caller's invocation graph and the derived builder return descriptor; do not add `allowFailure` or weaken the signature.

- [ ] **Step 3: Implement a private native-send wrapper with the exact native call order.**

  Implement the runtime sequence below using resolved `Method`/`Field` handles and `reflekt` only for runtime host objects:

  ```kotlin
  private fun sendNativeQuote(
      talker: String,
      content: String,
      sourceMsgId: Long,
      sourceTalker: String,
  ): Boolean {
      val builder = methodQuoteBuilderFactory.method.invoke(null, talker)
      methodQuoteBuilderSetDestination.method.invoke(builder, talker)
      methodQuoteBuilderSetContent.method.invoke(builder, content)
      methodQuoteBuilderSetScene.method.invoke(builder, nativeTextScene(talker))
      val source = classQuoteForwardInfo.clazz.createInstance()
      fieldQuoteForwardInfoMsgId.field.set(source, sourceMsgId)
      fieldQuoteForwardInfoTalker.field.set(source, sourceTalker)
      methodQuoteBuilderSetSource.method.invoke(builder, source)
      val task = methodQuoteBuilderBuild.method.invoke(builder)
      return methodQuoteTaskExecute.method.invoke(task) as Boolean
  }
  ```

  Match the actual method/property names to the resolved delegates. `nativeTextScene(talker)` must use the same native helper used at `MsgRetransmitUI.java:2824`/`:2841`, not a guessed constant. Resolve that helper by its stable call from the anchored retransmit method; its runtime contract is `String -> Int` (`zt0.c2.A(String)` on 8.0.65 and the corresponding helper on 8.0.76).

  The source object must carry the *local* message ID and its owning conversation talker. Do not pass the quoted server ID into the local-ID field.

- [ ] **Step 4: Implement the public server-ID operation.**

  Add:

  ```kotlin
  fun sendQuoteText(talker: String, quotedMsgSvrId: Long, content: String): Boolean {
      return try {
          val quoted = getMsgInfoInstanceByMsgSvrId(quotedMsgSvrId, talker)
          val quotedInfo = MessageInfo(quoted)
          sendNativeQuote(talker, content, quotedInfo.id, quotedInfo.talker)
      } catch (e: Exception) {
          WeLogger.e(TAG, "sendQuoteText failed", e)
          false
      }
  }
  ```

  Keep the API's server-ID name and behavior unambiguous. The lookup must use `(talker, quotedMsgSvrId)` and the source metadata must use the resolved message's local `id` and actual `talker`.

- [ ] **Step 5: Implement the local-ID operation and compatibility aliases.**

  Add:

  ```kotlin
  fun sendQuoteTextByMsgId(talker: String, quotedMsgId: Long, content: String): Boolean {
      return try {
          val quoted = getMsgInfoInstanceByMsgSvrId(
              getMsgSvrIdByMsgId(quotedMsgId) ?: return false,
              talker,
          )
          val quotedInfo = MessageInfo(quoted)
          sendNativeQuote(talker, content, quotedInfo.id, quotedInfo.talker)
      } catch (e: Exception) {
          WeLogger.e(TAG, "sendQuoteTextByMsgId failed", e)
          false
      }
  }
  ```

  Preserve existing public names as compatibility aliases only if callers require them, but route them to the native implementations and remove the overload that accepts `referContent` and manually creates `JSONObject` type-57 content. Do not leave two competing quote implementations.

- [ ] **Step 6: Run focused JVM compilation/tests for the changed module.**

  Run:

  ```bash
  ./gradlew :app:compileStandardDebugKotlin
  ```

  Expected: `BUILD SUCCESSFUL`. This is a compile check only; it does not validate WeChat runtime behavior or native DEX resolution.

- [ ] **Step 7: Commit the API/resolver unit.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt
  git commit -m "feat: add native quote message sending"
  ```

---

### Task 2: Preserve quotes in `RepeatMessages`

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/RepeatMessages.kt:63-79`.
- Modify if required by the API contract: `app/src/main/java/dev/ujhhgtg/wekit/features/items/system/servers/WeChatService.kt:199-201`.
- Modify if required by renamed compatibility API: `app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java/JavaEngine.kt:1043-1053`.

**Interfaces:**
- Consumes: `WeMessageApi.sendQuoteText` and `WeMessageApi.sendQuoteTextByMsgId` from Task 1; `MessageInfo.toQuoteMessage()`, `MessageInfo.id`, `MessageInfo.talker`, and `QuoteMessage.title`/`svrid`.
- Produces: quote repetition that preserves text/image references and retains the existing failure toast.

- [ ] **Step 1: Replace the plain-text quote branch with native-first/fallback routing.**

  Replace:

  ```kotlin
  MessageType.QUOTE -> WeMessageApi.sendText(msgInfo.talker, msgInfo.quoteMsgActualContent!!)
  ```

  with:

  ```kotlin
  MessageType.QUOTE -> {
      val quote = msgInfo.toQuoteMessage()!!
      val content = quote.title
      WeMessageApi.sendQuoteText(msgInfo.talker, quote.svrid, content) ||
          WeMessageApi.sendQuoteTextByMsgId(msgInfo.talker, msgInfo.id, content)
  }
  ```

  The first call quotes the original referenced message. The second call asks WeChat to retransmit the existing type-57 source message, allowing WeChat to preserve the embedded reference XML—including quoted-image content—while regenerating the new outer sender/destination envelope.

- [ ] **Step 2: Correct directly related API callers.**

  `WeChatService.sendQuoteMessage` currently names its parameter `msgSvrId` but calls the local-ID method. Route it to `WeMessageApi.sendQuoteText` so the server-ID contract is correct. Keep JavaEngine's local-ID script method on `sendQuoteTextByMsgId` because its argument is explicitly a local `msgId`; update its public method name only if the surrounding scripting API requires compatibility aliases.

- [ ] **Step 3: Compile the changed Kotlin callers.**

  Run:

  ```bash
  ./gradlew :app:compileStandardDebugKotlin
  ```

  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit quote routing.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/RepeatMessages.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/system/servers/WeChatService.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java/JavaEngine.kt
  git commit -m "fix: preserve quoted messages when repeating"
  ```

---

### Task 3: Cross-version Dex validation and build verification

**Files:**
- Read: all changed source files and generated reports under `dex-test-results/native-quote-message-repetition/`.
- No committed test files; reports remain ignored/generated.

**Interfaces:**
- Consumes: the resolved WeMessageApi delegates and RepeatMessages routing from Tasks 1–2.
- Produces: supported-version resolver evidence, a successful APK build, and a clean diff check.

- [ ] **Step 1: Run all available supported normal/Play APK resolver tests.**

  Run the available APKs explicitly, preserving separate normal and Play variants:

  ```bash
  ./x dex-test \
    --apk ~/coding/wechat_8065.apk \
    --apk ~/coding/wechat_8067.apk \
    --apk ~/coding/wechat_8069.apk \
    --apk ~/coding/wechat_8069_3020_play.apk \
    --apk ~/coding/wechat_8074.apk \
    --apk ~/coding/wechat_8076.apk \
    --output-dir dex-test-results/native-quote-message-repetition
  ```

  If an APK is absent, run the command with the available supported APKs and record the omission; do not claim coverage for a missing variant. For 8.0.77, use its APK if present and include it explicitly. Expected: all affected WeMessageApi delegates are `SUCCESS`; no infrastructure, unexpected, blocked, or incomplete outcome.

- [ ] **Step 2: Inspect reports for resolver failures.**

  Run:

  ```bash
  rg -n 'UNEXPECTED_FAILURE|BLOCKED|INCOMPLETE|Infrastructure|native quote|WeMessageApi' dex-test-results/native-quote-message-repetition
  ```

  Expected: no failure classification for the changed delegates. If a resolver fails, return to Task 1 Step 1 and tighten the matcher from stable DEX evidence before proceeding.

- [ ] **Step 3: Build through xtask.**

  Run:

  ```bash
  ./x build
  ```

  Expected: `BUILD SUCCESSFUL` and freshly rebuilt native libraries packaged by xtask. Do not substitute `./gradlew assemble`.

- [ ] **Step 4: Run final formatting/diff checks.**

  Run:

  ```bash
  git diff --check
  git status --short
  ```

  Expected: no whitespace errors. Generated Dex reports must not be staged; source changes and the already committed design/plan docs should be the only tracked changes.

- [ ] **Step 5: Commit any final resolver adjustments.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeMessageApi.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/RepeatMessages.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/system/servers/WeChatService.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java/JavaEngine.kt
  git commit -m "test: validate native quote repetition across hosts"
  ```

  Only create this final commit if Task 3 required source corrections; do not create an empty commit.

## Manual device validation checklist

After the build is installed in a supported WeChat host:

- [ ] Repeat a quote of text while its referenced message exists; verify the result is a type-57 quote, not plain text.
- [ ] Repeat a quote of an image while its referenced message exists; verify the quoted image preview remains intact.
- [ ] Make the referenced message unavailable while retaining the outer quote message; repeat it and verify the local-ID native retransmit preserves the embedded image/text reference.
- [ ] Verify the repeated message has current-user ownership, the new destination, and a newly generated outer envelope.
- [ ] Verify the original non-quote text/image/voice/video/sticker repeat paths are unchanged.
