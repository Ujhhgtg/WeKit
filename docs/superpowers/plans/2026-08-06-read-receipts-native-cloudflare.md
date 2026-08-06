# Read Receipts Native Server and Cloudflare Tunnel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Read Receipts lifecycle-aware and persistent, integrate it with `$readReceipts`, embed a restricted local server in `wekit-native`, and expose that server through third-party, Quick Tunnel, token-authenticated, or browser-login Cloudflare configurations.

**Architecture:** Keep `services/read-receipts/` as the standalone desktop/reference server while extracting its protocol/database/router core into a reusable library. The Android feature owns message records, endpoint selection, UI, and view lifecycle; the native library owns the loopback origin; a separate pinned `cloudflared` bridge and Android foreground service own public tunneling. Cloudflare transport code is never reimplemented in Kotlin or Rust.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, `WePrefs`/MMKV, OkHttp, WeChat/Xposed hooks, DexKit, Rust 2024, Axum 0.8, Tokio, libSQL 0.9, JNI, Go `cloudflared` bridge, Android foreground service, Android Keystore.

## Global Constraints

- Supported WeChat host range is **8.0.65–8.0.76**; lifecycle Dex changes require affected `./x dex-test` runs for every supported APK variant.
- Work only in `/home/ujhhgtg/coding/WeKit/.claude/worktrees/read-receipts-native-tunnel` on branch `worktree-read-receipts-native-tunnel`, based on `dev` commit `1a646f0c`.
- Initialize submodules before builds; build native code through `./x`, never Gradle alone.
- Preserve the existing `ICreateViewListener` bind API for source compatibility; add lifecycle callbacks separately.
- `onBindView` is not visibility; use attach/detach for active polling and recycle as idempotent cleanup.
- Poll multiple visible records concurrently with a bounded concurrency limit; never start one unbounded coroutine per message.
- Persist only successfully sent tracked records; store versioned JSON elements in a `WePrefs` string set and prune records after 180 days.
- Add `$readReceipts`; if present, replace it in place; otherwise append ` | 已读 x 人`; if `MessageTimeEnhancements` is inactive, append the same suffix to native time text.
- A known count of zero renders as `已读 0 人`; no successful response renders no receipt text.
- The embedded server binds only to `127.0.0.1` and exposes register, pixel, count, and metadata-free health routes.
- Keep `services/read-receipts/`; do not delete or silently replace its desktop dashboard, REPL, management routes, or optional Turso support.
- Do not create Cloudflare tunnels, DNS records, hostnames, or ingress routes from WeKit; users provide/select existing authenticated tunnels.
- Support login-free Quick Tunnel, existing named-tunnel run token, and browser-login credential selection.
- Store tunnel secrets outside `WePrefs`, logs, clipboard, crash text, and saved instance state using Android Keystore-backed encryption.
- Run `git diff --check`, focused tests, and `./x build` before claiming completion; real device testing remains required for host hooks and tunnel lifecycle.

---

## File and Responsibility Map

Create/modify only the following focused boundaries unless implementation discovery proves a documented exception:

- Modify `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeChatMessageViewApi.kt`: bind-state map, attach/detach listener, recycle Dex hook, lifecycle listener API.
- Create `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRecord.kt`: backend identity, versioned serialization, endpoint selection, retention pruning.
- Create `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRendering.kt`: pure text composition for `$readReceipts` and suffix fallback.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/MessageTimeEnhancements.kt`: `$readReceipts`, renderer entry point, forced visibility, placeholder chip.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`: send flow, persistence, lifecycle-driven active views, concurrent polling, server/tunnel state, configuration UI.
- Create `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRecordTest.kt`: record codec and pruning tests.
- Create `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRenderingTest.kt`: placeholder/suffix tests.
- Modify `services/read-receipts/Cargo.toml`: library target and CLI feature split.
- Create `services/read-receipts/src/lib.rs`: reusable local server core and embedded route profile.
- Modify `services/read-receipts/src/main.rs`: desktop wrapper around the library while retaining dashboard/REPL/Turso behavior.
- Create `services/read-receipts/src/lib_tests.rs` or inline library tests: server validation, schema, and route tests.
- Modify `app/src/main/rust/wekit-native/Cargo.toml`: path dependency on the server library and native runtime dependencies/features.
- Create `app/src/main/rust/wekit-native/src/read_receipts_server.rs`: JNI-independent native origin lifecycle.
- Modify `app/src/main/rust/wekit-native/src/lib.rs`: JNI exports for start/stop/status.
- Create `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsNative.kt`: typed JNI declarations and status conversion.
- Create `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt`: foreground connector owner, notification, and status callbacks.
- Modify `app/src/main/AndroidManifest.xml`: service and foreground-service declarations required by the selected Android API levels.
- Create `app/src/main/go/wekit-cloudflared/go.mod`: pinned Go module metadata for the bridge.
- Create `app/src/main/go/wekit-cloudflared/main.go`: narrow C ABI bridge and cancellation-safe connector facade.
- Add pinned upstream Cloudflare source under `third_party/cloudflared/` or an explicitly documented source checkout; preserve Apache/NOTICE files and modified-file markers.
- Modify `xtask/src/main.rs`: build/package bridge for `arm64-v8a` and `armeabi-v7a` before Rust native build.
- Modify `app/src/main/rust/wekit-native/build.rs` only if C ABI/linker search paths require it; do not add runtime logic there.
- Modify `services/read-receipts/README.md` and `docs/features/chat/read-receipts.md`: document shared protocol, standalone/reference status, embedded limitations, and tunnel security.

---

### Task 1: Add bind/attach/detach/recycle message-view lifecycle API

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeChatMessageViewApi.kt:14-86`
- Test/verification: `dex-test-results/<run-id>/` generated by `./x dex-test`; no JVM unit test for WeChat hook glue.

**Interfaces:**
- Consumes: existing `ICreateViewListener`, `methodChatItemOnBindView`, `MessageInfo` construction, and holder-to-root-view reflection.
- Produces:
  ```kotlin
  interface IMessageViewLifecycleListener {
      fun onMessageViewAttached(view: View, message: MessageInfo) {}
      fun onMessageViewDetached(view: View, message: MessageInfo) {}
      fun onMessageViewRecycled(view: View, message: MessageInfo) {}
  }
  fun addLifecycleListener(listener: IMessageViewLifecycleListener)
  fun removeLifecycleListener(listener: IMessageViewLifecycleListener)
  ```

- [ ] **Step 1: Add the lifecycle listener interface and listener collection.**

  Preserve the existing listener collection and add a `CopyOnWriteArrayList<IMessageViewLifecycleListener>`. Lifecycle callback exceptions must be logged in the same way as existing bind listener exceptions.

- [ ] **Step 2: Add weak current-binding and installed-attach-listener maps.**

  Use synchronized `WeakHashMap<View, MessageInfo>` and `WeakHashMap<View, View.OnAttachStateChangeListener>`. The binding map must be updated on every bind before feature listeners run.

- [ ] **Step 3: Install one attach listener per root view and dispatch current state.**

  In the bind hook, resolve `MessageInfo` once. If the view is already attached and its previous binding differs, dispatch detach for the old message before replacing it, then dispatch attach for the new message. The listener methods must read the current message from the binding map at callback time.

- [ ] **Step 4: Add the recycle resolver and hook.**

  Declare a `dexMethod` target using the stable `WxRecyclerAdapter.onViewRecycled` string evidence (`"rvnotify-test-onViewRecycled viewType="`) and hook it before WeChat cleanup. Resolve the holder's item root view with the same `reflekt` field pattern. Dispatch recycle only when a binding exists, then remove it.

- [ ] **Step 5: Keep old bind behavior unchanged.**

  Continue dispatching `ICreateViewListener.onCreateView(this, view)` after updating lifecycle state. Do not rename existing methods in this task; update documentation/comments only if needed.

- [ ] **Step 6: Run supported Dex resolution.**

  Run:
  ```bash
  ./x dex-test
  ```
  Expected: all supported 8.0.65–8.0.76 APK reports resolve the bind and recycle delegates without `UNEXPECTED_FAILURE`, `BLOCKED`, or `INCOMPLETE` status. If the command requires APK arguments in this checkout, run the documented equivalent for each available supported APK and preserve reports under `dex-test-results/<run-id>/`.

- [ ] **Step 7: Commit the lifecycle API.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeChatMessageViewApi.kt dex-test-results/<run-id>/
  git commit -m "feat: expose message view lifecycle callbacks"
  ```

---

### Task 2: Add pure tracked-record persistence and endpoint models

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRecord.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRecordTest.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/preferences/WePrefs.kt` only if a missing string-set helper is discovered; current helpers already support `Set<String>`.

**Interfaces:**
- Produces:
  ```kotlin
  enum class ReadReceiptBackend { THIRD_PARTY, BUILT_IN }
  data class ReadReceiptRecord(
      val id: String,
      val wxId: String,
      val backend: ReadReceiptBackend,
      val endpoint: String,
      val createdAtMillis: Long,
  )
  object ReadReceiptRecordCodec {
      fun encode(record: ReadReceiptRecord): String
      fun decode(value: String): ReadReceiptRecord?
      fun prune(records: Collection<ReadReceiptRecord>, nowMillis: Long, retentionMillis: Long): Set<ReadReceiptRecord>
  }
  ```

- [ ] **Step 1: Write tests for JSON round-trip and malformed data.**

  Add tests covering the exact public behavior:
  ```kotlin
  @Test
  fun `round trips third party endpoint`() {
      val record = ReadReceiptRecord("0123456789abcdef", "wxid_a", ReadReceiptBackend.THIRD_PARTY, "https://receipts.example", 1_700_000_000_000)
      assertEquals(record, ReadReceiptRecordCodec.decode(ReadReceiptRecordCodec.encode(record)))
  }

  @Test
  fun `round trips built in logical endpoint`() {
      val record = ReadReceiptRecord("abcdef0123456789", "wxid_b", ReadReceiptBackend.BUILT_IN, "builtin://local", 1_700_000_000_000)
      assertEquals(record, ReadReceiptRecordCodec.decode(ReadReceiptRecordCodec.encode(record)))
  }

  @Test
  fun `rejects unsupported schema version`() {
      assertNull(ReadReceiptRecordCodec.decode("{\"version\":99}"))
  }

  @Test
  fun `rejects malformed id wxId backend endpoint and timestamp`() {
      assertNull(ReadReceiptRecordCodec.decode("{\"version\":1,\"id\":\"not-hex\",\"wxId\":\"wxid\",\"backend\":\"THIRD_PARTY\",\"endpoint\":\"https://x\",\"createdAtMillis\":1}"))
  }

  @Test
  fun `prunes records older than 180 days and retains boundary`() {
      val now = 1_800_000_000_000
      val retention = 180L * 24 * 60 * 60 * 1000
      val boundary = ReadReceiptRecord("0123456789abcdef", "wxid", ReadReceiptBackend.BUILT_IN, "builtin://local", now - retention)
      val expired = boundary.copy(id = "abcdef0123456789", createdAtMillis = now - retention - 1)
      assertEquals(setOf(boundary), ReadReceiptRecordCodec.prune(listOf(boundary, expired), now, retention))
  }

  @Test
  fun `deduplicates records by backend wxId id endpoint`() {
      val record = ReadReceiptRecord("0123456789abcdef", "wxid", ReadReceiptBackend.BUILT_IN, "builtin://local", 1_700_000_000_000)
      assertEquals(setOf(record), ReadReceiptRecordCodec.prune(listOf(record, record), 1_700_000_000_001, Long.MAX_VALUE))
  }
  ```
  Use fixed timestamps; do not use the wall clock in tests.

- [ ] **Step 2: Run the focused tests and verify failure.**

  ```bash
  ./gradlew testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.chat.ReadReceiptRecordTest'
  ```
  Expected: compilation/test failure because the new codec does not exist yet.

- [ ] **Step 3: Implement versioned JSON serialization.**

  Encode fields with an explicit integer schema version and decode only the supported version. Normalize third-party URLs by trimming trailing `/`; built-in records must use the literal logical endpoint identity selected by the implementation (for example `builtin://local`). Reject blank or overlong fields and IDs that do not match the existing lowercase hexadecimal message-ID contract.

- [ ] **Step 4: Implement deterministic pruning.**

  Use `nowMillis - retentionMillis` as the cutoff. Retain records at exactly the cutoff, remove records older than it, and return a deduplicated set.

- [ ] **Step 5: Run the focused tests and commit.**

  ```bash
  ./gradlew testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.chat.ReadReceiptRecordTest'
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRecord.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRecordTest.kt
  git commit -m "feat: persist read receipt records"
  ```
  Expected: all focused tests pass.

---

### Task 3: Add `$readReceipts` rendering semantics

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRendering.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRenderingTest.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/MessageTimeEnhancements.kt:77-225,274-281`

**Interfaces:**
- Produces:
  ```kotlin
  const val READ_RECEIPTS_PLACEHOLDER = $$"$readReceipts"
  const val READ_RECEIPTS_SUFFIX = " | 已读 "
  fun renderReadReceiptText(templateOrNativeText: String, count: Int?, enhancementActive: Boolean): String
  ```
  The function must distinguish `count == null` from `count == 0`.

- [ ] **Step 1: Write tests for placeholder and suffix behavior.**

  Include exact assertions:
  ```kotlin
  assertEquals("time · 已读 3 人 · type", renderReadReceiptText("time · $$\"$readReceipts\" · type", 3, true))
  assertEquals("time | 已读 3 人", renderReadReceiptText("time", 3, true))
  assertEquals("native | 已读 3 人", renderReadReceiptText("native", 3, false))
  assertEquals("time ·  · type", renderReadReceiptText("time · $$\"$readReceipts\" · type", null, true))
  assertEquals("time | 已读 0 人", renderReadReceiptText("time", 0, true))
  assertEquals("native", renderReadReceiptText("native", null, false))
  ```
  Add a test that a template containing the placeholder does not receive an automatic suffix.

- [ ] **Step 2: Run focused tests and verify failure.**

  ```bash
  ./gradlew testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.chat.ReadReceiptRenderingTest'
  ```
  Expected: failure before implementation.

- [ ] **Step 3: Implement the pure renderer.**

  For an active enhancement template containing `$readReceipts`, replace all placeholder occurrences with `已读 N 人` or an empty string. For active templates without it, append ` | 已读 N 人` only when the count is known. For inactive enhancement mode, append the same suffix to the native base only when known.

- [ ] **Step 4: Refactor `MessageTimeEnhancements` around a reusable render entry point.**

  Preserve its existing formatting, color, size, padding, visibility, and alignment logic. Add `$readReceipts` to placeholder replacement and the Compose chip list. Expose an internal renderer that accepts `MessageInfo`, `TextView`, `forceVisible`, and nullable count state so `ReadReceipts` can request a complete pass. Do not make `ReadReceipts` duplicate time-format/style logic.

- [ ] **Step 5: Add receipt tags and stale-state clearing.**

  Define dedicated tag keys for message ID, binding generation, and nullable count state. The message-time bind path must read them and clear them when a row is untracked/rebound. A tracked row must force `timeTV` visible even when `isAlwaysVisible` is false.

- [ ] **Step 6: Run tests, formatting, and commit.**

  ```bash
  ./gradlew testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.chat.ReadReceiptRenderingTest'
  git diff --check
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRendering.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/MessageTimeEnhancements.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRenderingTest.kt
  git commit -m "feat: integrate read receipts with message time rendering"
  ```

---

### Task 4: Refactor `ReadReceipts` around persisted records and lifecycle polling

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt:59-349`
- Consume: `ReadReceiptRecordCodec`, `WeChatMessageViewApi.IMessageViewLifecycleListener`, and the renderer from Tasks 1–3.
- Test/verification: focused pure tests from Tasks 2–3; host lifecycle is manual device verification.

**Interfaces:**
- `ReadReceipts` implements both existing `ICreateViewListener` and the new lifecycle listener.
- Internal active-view model:
  ```kotlin
  private data class ActiveReceiptView(
      val view: TextView,
      val record: ReadReceiptRecord,
      val generation: Long,
  )
  ```

- [ ] **Step 1: Replace global `server` preference with backend-mode preferences.**

  Add non-secret preferences for backend mode, third-party URL, poll interval, prefix, built-in port, tunnel mode, hostname, auto-lifecycle, selected account/tunnel metadata, and persisted serialized records. Keep token/certificate values out of `WePrefs`.

- [ ] **Step 2: Implement record-store load/save/prune helpers.**

  Decode the persisted string set on enable, discard malformed entries with a warning, prune 180-day records, and write the normalized set back. Add helpers to find a record by `(wxId, id)` and to insert only after successful registration and message emission.

- [ ] **Step 3: Make send registration synchronous and endpoint-aware.**

  Replace fire-and-forget registration with a cancellable IO call that returns success/failure. Resolve the selected backend endpoint before computing XML. On failure, show an error, retain the original `ChatFooter.lastText`, and set the runtime error state. On success, send XML, persist the record, clear the input, and set `result = null`.

- [ ] **Step 4: Split active views from cached counts.**

  Keep cached counts keyed by backend-aware record identity. `onCreateView` should parse pixel URL, match persisted metadata, stamp `timeTV`, and render cached state only. It must not add to `activeViews` or start polling.

- [ ] **Step 5: Implement attach/detach/recycle callbacks.**

  On attach, add/update the exact view binding, increment its generation, render cached state, and trigger immediate fetch. On detach/recycle, remove only the matching generation and cancel the poll coordinator when empty.

- [ ] **Step 6: Implement bounded concurrent polling.**

  Use one `CoroutineScope(Dispatchers.IO + SupervisorJob())`, a bounded semaphore/worker count, and an active snapshot. Fetch each distinct active record concurrently. Post updates to main only after checking keyed ID, generation, and active map identity. Preserve prior count on failures and use per-record backoff.

- [ ] **Step 7: Register/unregister both listener types and clean up.**

  `onEnable` registers bind and lifecycle listeners, restores records, and starts the origin/tunnel coordinator only when automatic mode requires it. `onDisable` removes both listeners, cancels jobs, clears active views, and performs configured tunnel/server shutdown without deleting persisted records.

- [ ] **Step 8: Run focused Kotlin tests and compile.**

  ```bash
  ./gradlew testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.chat.ReadReceiptRecordTest' --tests 'dev.ujhhgtg.wekit.features.items.chat.ReadReceiptRenderingTest'
  ./gradlew compileStandardDebugKotlin
  ```
  Expected: compile succeeds and both pure test classes pass. Do not claim device lifecycle behavior from this command.

- [ ] **Step 9: Commit Android polling changes.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt
  git commit -m "feat: poll visible read receipts concurrently"
  ```

---

### Task 5: Extract reusable read-receipts server core while preserving standalone CLI

**Files:**
- Modify: `services/read-receipts/Cargo.toml`
- Create: `services/read-receipts/src/lib.rs`
- Modify: `services/read-receipts/src/main.rs`
- Create/modify: `services/read-receipts/src/lib_tests.rs`
- Modify: `services/read-receipts/README.md`

**Interfaces:**
- Produces:
  ```rust
  pub struct ServerConfig {
      pub database_path: PathBuf,
      pub bind_addr: IpAddr,
      pub bind_port: u16,
      pub route_profile: RouteProfile,
  }
  pub enum RouteProfile { Standalone, Embedded }
  pub struct ServerHandle {
      pub local_addr: SocketAddr,
      pub shutdown: oneshot::Sender<()>,
  }
  pub async fn open_database(config: &ServerConfig) -> Result<libsql::Database, ServerError>
  pub fn build_router(config: &ServerConfig, state: Arc<AppState>) -> Router
  pub async fn bind_and_serve(config: ServerConfig, shutdown: impl Future<Output = ()> + Send + 'static) -> Result<BoundServer, ServerError>
  ```
  Exact field visibility may remain private behind constructors; the embedded caller must receive the bound address and a shutdown handle.

- [ ] **Step 1: Add a library target and CLI feature.**

  Keep Axum, Tokio, libSQL, serialization, hashing, and time dependencies available to the library. Gate `rustyline` and desktop-only REPL dependencies behind a `cli` feature used by the binary. Keep the workspace member unchanged.

- [ ] **Step 2: Move protocol types, ID hashing, schema, handlers, and router construction to `src/lib.rs`.**

  Preserve the current wire contract exactly:
  ```text
  sha256(wxId + NUL + content + NUL + createTime)
  POST /register {wxId, content, createTime}
  GET /pixel?wxId=<wxId>&id=<id>
  GET /count?wxId=<wxId>&id=<id>
  ```
  Keep standalone handlers and management routes available under `RouteProfile::Standalone`.

- [ ] **Step 3: Add the embedded route profile.**

  Add a metadata-free health endpoint and only register/pixel/count routes. Add request/query/body limits, message-ID validation, unknown-message rejection for pixel logging, and rate-limit state. Keep static pixel response behavior even when DB insertion fails.

- [ ] **Step 4: Separate CLI startup from reusable serving.**

  Reduce `main.rs` startup to tracing, environment parsing, database configuration, standalone router selection, REPL, and signal handling. Do not change standalone defaults or delete dashboard/REPL/Turso behavior.

- [ ] **Step 5: Add protocol and route tests.**

  Test exact message-ID vectors, register/count behavior, unknown pixel rejection, body/query validation, health response, and route-profile exclusion of management paths. Use a temporary local database and deterministic clock/test inputs where the code permits.

- [ ] **Step 6: Run the service tests and standalone smoke test.**

  ```bash
  cargo test -p wekit-read-receipts-server
  cargo run -p wekit-read-receipts-server -- --help
  ```
  Expected: library tests pass; standalone binary still compiles and exposes its existing CLI behavior.

- [ ] **Step 7: Commit the server extraction.**

  ```bash
  git add services/read-receipts/Cargo.toml services/read-receipts/src/lib.rs services/read-receipts/src/main.rs services/read-receipts/src/lib_tests.rs services/read-receipts/README.md
  git commit -m "refactor: extract reusable read receipts server core"
  ```

---

### Task 6: Add the embedded native origin runtime and JNI bridge

**Files:**
- Modify: `app/src/main/rust/wekit-native/Cargo.toml`
- Create: `app/src/main/rust/wekit-native/src/read_receipts_server.rs`
- Modify: `app/src/main/rust/wekit-native/src/lib.rs`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsNative.kt`
- Modify: `app/src/main/rust/wekit-native/build.rs` only for required linker metadata.

**Interfaces:**
- Kotlin declarations:
  ```kotlin
  internal object ReadReceiptsNative {
      external fun startServer(databasePath: String, port: Int): String?
      external fun stopServer()
      external fun serverStatus(): String
  }
  ```
  `null` start result means success only if status reports running; non-null is an error message. Replace with a typed result if the existing JNI string convention requires it, but keep all calls bounded/non-blocking.

- Rust internal API:
  ```rust
  pub fn start(database_path: &str, port: u16) -> Result<BoundServer, String>
  pub fn stop()
  pub fn status() -> ServerStatus
  ```

- [ ] **Step 1: Add the server library path dependency and minimal Tokio features.**

  Disable CLI-only server features. Verify the dependency graph does not pull `rustyline`, `crossterm`, or remote Turso behavior into Android. Keep the existing native crate features intact.

- [ ] **Step 2: Implement synchronized native lifecycle state.**

  Validate absolute database paths, reject conflicting active configurations, start a named thread with a multi-thread Tokio runtime, bind loopback, initialize schema, and send a bounded startup result through a channel. Store shutdown sender, bound port, status, and thread handle state behind a mutex/`OnceLock`.

- [ ] **Step 3: Implement graceful stop and status.**

  Stop sends a one-shot signal and returns without waiting indefinitely. A completion path updates state to stopped. Repeated stop is harmless; repeated identical start is idempotent.

- [ ] **Step 4: Add JNI exports and Kotlin declarations.**

  Follow `with_jstring` and existing `native_error_string` conventions. Do not perform database setup or listener binding on the hook/UI thread. Log failures through native logging and convert them to a bounded Kotlin error/status.

- [ ] **Step 5: Cross-check local server against standalone protocol tests.**

  Reuse the library's shared test vectors. Add a Rust unit test for start/stop idempotence on a host target; defer Android ABI validation to the build task.

- [ ] **Step 6: Run host tests and native checks.**

  ```bash
  cargo test -p wekit-native -p wekit-read-receipts-server
  cargo fmt --all -- --check
  cargo clippy -p wekit-native -p wekit-read-receipts-server -- -D warnings
  ```
  Expected: all pass on the host target. Android compilation is a later required gate.

- [ ] **Step 7: Commit the native origin.**

  ```bash
  git add app/src/main/rust/wekit-native/Cargo.toml app/src/main/rust/wekit-native/src/read_receipts_server.rs app/src/main/rust/wekit-native/src/lib.rs app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsNative.kt app/src/main/rust/wekit-native/build.rs
  git commit -m "feat: embed read receipts origin in native runtime"
  ```

---

### Task 7: Integrate built-in origin lifecycle and conditional configuration UI

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Create/modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsServerController.kt`
- Create/modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsStatus.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class ReadReceiptsServerMode { THIRD_PARTY, BUILT_IN }
  enum class ReadReceiptsRuntimeState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
  interface ReadReceiptsServerController {
      fun startBuiltIn(port: Int): Result<Int>
      fun stopBuiltIn()
      fun status(): ReadReceiptsRuntimeState
  }
  ```

- [ ] **Step 1: Add backend-mode and built-in server preferences.**

  Persist mode, port/automatic-port selection, auto-lifecycle, and non-secret status metadata through `prefOption`. Resolve the database path from `HostInfo.application.filesDir/wekit-read-receipts/read_receipts.db`, creating the directory before native start.

- [ ] **Step 2: Implement origin controller with generation-scoped callbacks.**

  Start/stop through `ReadReceiptsNative`; reject stale callbacks after a configuration change. Keep the server state independent from tunnel state. Do not expose a public/LAN bind field.

- [ ] **Step 3: Replace the existing server-only settings UI with the server-mode radio group.**

  Use Material 3 `RadioButton` inside clickable `ListItem` rows, following existing `NoCompressUploadedImages.kt` patterns. Third-party mode displays only external URL/test controls; built-in mode displays loopback state, port, database info, and manual controls.

- [ ] **Step 4: Add automatic server/tunnel lifecycle switch.**

  Display it only in built-in mode. When enabled, feature enable starts origin before tunnel and feature disable stops tunnel before origin. When disabled, the feature switch does not change manually controlled runtime components.

- [ ] **Step 5: Add selected-mode validation and atomic save.**

  Validate HTTPS third-party URL, positive poll interval, valid loopback port/automatic-port setting, and preserve empty-prefix warning. Commit preferences only after validation succeeds. Saving runtime-affecting settings performs a controlled stop/restart rather than leaving an old endpoint active.

- [ ] **Step 6: Add endpoint readiness checks to sending.**

  Third-party mode checks the configured URL. Built-in mode requires origin and tunnel readiness before emitting a pixel URL. If Android requires user action to start the tunnel service, preserve input and show the failure rather than sending a stale public URL.

- [ ] **Step 7: Run Kotlin compile and focused tests.**

  ```bash
  ./gradlew testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.chat.ReadReceiptRecordTest' --tests 'dev.ujhhgtg.wekit.features.items.chat.ReadReceiptRenderingTest'
  ./gradlew compileStandardDebugKotlin
  ```

- [ ] **Step 8: Commit origin UI/lifecycle integration.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsServerController.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsStatus.kt
  git commit -m "feat: configure embedded read receipts server"
  ```

---

### Task 8: Build a pinned Cloudflare bridge proof of concept

**Files:**
- Create: `third_party/cloudflared/` pinned source checkout or documented source archive.
- Create: `app/src/main/go/wekit-cloudflared/go.mod`
- Create: `app/src/main/go/wekit-cloudflared/main.go`
- Create: `app/src/main/go/wekit-cloudflared/bridge.h`
- Modify: `xtask/src/main.rs`
- Create: `docs/features/chat/cloudflared-bridge.md`

**Interfaces:**
- C ABI, exact exported symbols:
  ```c
  typedef void* wekit_tunnel_handle;
  wekit_tunnel_handle wekit_tunnel_start_quick(const char* origin, wekit_callback callback, void* user);
  wekit_tunnel_handle wekit_tunnel_start_token(const char* token, const char* origin, wekit_callback callback, void* user);
  int wekit_tunnel_begin_login(wekit_tunnel_handle handle, wekit_callback callback, void* user);
  int wekit_tunnel_select_existing(wekit_tunnel_handle handle, const char* tunnel_id, const char* hostname);
  int wekit_tunnel_stop(wekit_tunnel_handle handle);
  int wekit_tunnel_status(wekit_tunnel_handle handle, char* buffer, size_t buffer_len);
  ```
  Callbacks carry only status, URL, and bounded error text; never credentials.

- [ ] **Step 1: Pin the exact upstream revision and license files.**

  Record the upstream commit, local modifications, Apache license, NOTICE files, and all transitive notices. Do not copy a floating `master` checkout.

- [ ] **Step 2: Create the smallest Go facade.**

  The facade must own a cancellable context and wait group per handle. It must disable CLI parsing, auto-update, signal handling, diagnostics/metrics listeners, Sentry initialization, and desktop browser launching. It must expose Quick Tunnel request/start, token start, login-transfer URL/status, existing tunnel selection, status, and stop.

- [ ] **Step 3: Build the Quick Tunnel-only proof first.**

  The first milestone must compile and run the Quick Tunnel facade against a local HTTP origin on a development host. Verify it returns a temporary hostname, forwards an HTTP request, emits connected/disconnected callbacks, and stops without leaking goroutines.

- [ ] **Step 4: Build both Android ABIs before expanding scope.**

  Add `xtask` commands/helpers that use the pinned NDK and Go toolchain to build `libwekit_cloudflared.so` for `arm64-v8a` and `armeabi-v7a`, with output paths separate from `libwekit_native.so`. Do not modify JNI/native runtime integration until both artifacts build.

- [ ] **Step 5: Run the proof-of-concept gates.**

  ```bash
  go test ./app/src/main/go/wekit-cloudflared
  ./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a
  file app/src/main/jniLibs/arm64-v8a/libwekit_cloudflared.so app/src/main/jniLibs/armeabi-v7a/libwekit_cloudflared.so
  ```
  Expected: bridge tests pass and both outputs are valid Android shared libraries. If the upstream source cannot satisfy this gate without importing unsupported CLI/runtime behavior, stop this task and report the blocker rather than merging a partial fake connector.

- [ ] **Step 6: Document bridge scope and limitations.**

  Document Quick Tunnel's temporary/testing semantics, the authenticated token/browser-login plan, source pin, license notices, ABI artifacts, and the fact that Quick Tunnel does not guarantee SSE support.

- [ ] **Step 7: Commit only a passing bridge milestone.**

  ```bash
  git add third_party/cloudflared app/src/main/go/wekit-cloudflared xtask/src/main.rs docs/features/chat/cloudflared-bridge.md
  git commit -m "build: add pinned cloudflared bridge proof of concept"
  ```

---

### Task 9: Add the Android foreground tunnel service and token/Quick Tunnel modes

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelController.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelStatus.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Modify: `app/src/main/rust/wekit-native/src/lib.rs` only if Rust must forward bridge calls; prefer service-to-library C ABI loading if possible.

**Interfaces:**
  ```kotlin
  enum class ReadReceiptsTunnelMode { QUICK, TOKEN, BROWSER_LOGIN }
  enum class ReadReceiptsTunnelState { STOPPED, STARTING, CONNECTED, RECONNECTING, NEEDS_USER_ACTION, FAILED, STOPPING }
  data class ReadReceiptsTunnelStatus(val state: ReadReceiptsTunnelState, val publicUrl: String?, val error: String?)
  ```

- [ ] **Step 1: Add secure credential storage.**

  Implement a Keystore-backed encrypted file/key-value store for run tokens and retained login credentials. `WePrefs` stores only mode, hostname, selected tunnel/account IDs, and “credential exists” metadata.

- [ ] **Step 2: Implement foreground service startup/shutdown.**

  Use a visible-user-initiated start path, ongoing low-priority notification, bounded reconnect backoff, cancellation, and network callback/reconnect handling. The service connects to the loopback origin and never binds a public socket itself.

- [ ] **Step 3: Implement Quick Tunnel connector calls.**

  Wait for origin health, start the bridge, publish the temporary URL only after connected callback, invalidate the URL on disconnect, and stop the bridge before stopping the service.

- [ ] **Step 4: Implement token-mode startup.**

  Require token and HTTPS hostname, start the named tunnel, verify connector state and public health endpoint, and report invalid credentials/hostname failures without exposing the token.

- [ ] **Step 5: Add built-in-mode UI controls.**

  Add tunnel mode radio group, Quick Tunnel status/URL, token field with masked/reveal/delete controls, hostname field, validate action, copy/share URL, and explicit connect/disconnect controls. Keep token material out of Compose state persistence.

- [ ] **Step 6: Connect automatic lifecycle.**

  Server controller starts first, tunnel controller starts second; shutdown reverses order. Configuration generation IDs must suppress callbacks from prior tunnel sessions.

- [ ] **Step 7: Update manifests and run build checks.**

  Declare service/exported state, foreground service permissions/types required by the project’s target SDK, and notification channel setup. Run:
  ```bash
  ./gradlew compileStandardDebugKotlin
  cargo test --workspace
  ```

- [ ] **Step 8: Commit Quick/Token service integration.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelController.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelStatus.kt app/src/main/AndroidManifest.xml app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt
  git commit -m "feat: run read receipts cloudflare tunnel service"
  ```

---

### Task 10: Implement browser login and existing-tunnel selection

**Files:**
- Modify: `app/src/main/go/wekit-cloudflared/main.go`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelController.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Modify: secure credential storage from Task 9 if certificate lifecycle requires it.

**Interfaces:**
  ```kotlin
  data class CloudflareLoginState(val authorizationUrl: String?, val state: ReadReceiptsTunnelState, val error: String?)
  data class ExistingTunnel(val id: String, val name: String, val hostnames: List<String>)
  suspend fun beginBrowserLogin(): CloudflareLoginState
  suspend fun listExistingTunnels(): List<ExistingTunnel>
  suspend fun selectExistingTunnel(tunnelId: String, hostname: String): Result<Unit>
  ```

- [ ] **Step 1: Add login-transfer callback and cancellation.**

  The bridge returns an authorization URL, polls asynchronously, and reports timeout/cancel/error states. It must not launch a desktop browser internally.

- [ ] **Step 2: Launch Android browser and preserve process-level state.**

  Kotlin opens the authorization URL with `ACTION_VIEW`, offers copyable fallback, keeps a process-level coordinator across dialog recreation, and restarts safely after process death rather than persisting transient plaintext certificate material.

- [ ] **Step 3: List accounts/tunnels and display selection UI.**

  Show authorized account, refreshable existing-tunnel list, selected tunnel, and configured hostname list. Allow manual existing hostname entry only when it can be verified by the public health endpoint.

- [ ] **Step 4: Store selected tunnel credential and delete temporary account credential.**

  Retain only the selected tunnel-scoped run credential for normal startup. If refresh/list operations require account-wide material, retain it only in the encrypted store with explicit logout/delete controls and clear it after successful selection when possible.

- [ ] **Step 5: Validate selected tunnel before save.**

  Require tunnel ID and HTTPS hostname; verify the connector can start and the public hostname reaches local health. Commit selected metadata only after validation; otherwise preserve the previous working configuration.

- [ ] **Step 6: Run focused compile and manual auth smoke test.**

  ```bash
  ./gradlew compileStandardDebugKotlin
  ```
  Manual device test: browser launch, copy URL fallback, authorization completion, refresh, selection, cancellation, invalid hostname, logout, and retry.

- [ ] **Step 7: Commit authenticated browser-login flow.**

  ```bash
  git add app/src/main/go/wekit-cloudflared/main.go app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelController.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt
  git commit -m "feat: select authenticated cloudflare tunnels"
  ```

---

### Task 11: Harden protocol, secrets, documentation, and standalone compatibility

**Files:**
- Modify: `services/read-receipts/src/lib.rs`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Modify: secure connector/service files from Tasks 9–10.
- Modify: `services/read-receipts/README.md`
- Modify: `docs/features/chat/read-receipts.md`
- Modify: `docs/features/chat/cloudflared-bridge.md`
- Add/update: license and NOTICE files for pinned Cloudflare source/transitives.

**Interfaces:**
- No new public API; this task validates and tightens interfaces produced by Tasks 5, 7, 9, and 10.

- [ ] **Step 1: Add route and message field limits.**

  Enforce explicit max lengths for `wxId`, message content, endpoint URL, query parameters, and registration body. Return bounded errors and never include plaintext content or credentials in error logs.

- [ ] **Step 2: Verify trusted reader-IP boundary.**

  Accept connector-provided origin metadata only from the authenticated local bridge path; ignore arbitrary forwarded headers from direct callers. Add tests for direct-local and connector-local requests.

- [ ] **Step 3: Remove secret logging and insecure defaults.**

  Search modified code for token/certificate values, URLs containing secrets, default bearer tokens, clipboard writes, and exception interpolation. Replace with redacted identifiers and explicit user-facing status.

- [ ] **Step 4: Preserve standalone service behavior.**

  Run the existing server against a temporary local DB, verify dashboard/REPL/management routes still compile and operate, and confirm the embedded route profile does not accidentally expose them.

- [ ] **Step 5: Update documentation.**

  Document third-party mode, loopback embedded mode, Quick Tunnel limitations, token handling, browser-login existing-tunnel selection, Android foreground-service behavior, plaintext/IP privacy implications, and the standalone reference service. State that WeKit does not create Cloudflare tunnels or DNS routes.

- [ ] **Step 6: Commit hardening/docs.**

  ```bash
  git add services/read-receipts/src/lib.rs app/src/main/java/dev/ujhhgtg/wekit/features/items/chat services/read-receipts/README.md docs/features/chat/read-receipts.md docs/features/chat/cloudflared-bridge.md LICENSE NOTICE* third_party/cloudflared
  git commit -m "docs: harden read receipts server and tunnel integration"
  ```

---

### Task 12: Run complete verification and device checklist

**Files:**
- No intended source changes; only generated `dex-test-results/<run-id>/` and build artifacts.

- [ ] **Step 1: Run Rust and Kotlin checks.**

  ```bash
  cargo fmt --all -- --check
  cargo test --workspace
  cargo clippy --workspace -- -D warnings
  ./gradlew testStandardDebugUnitTest
  ./gradlew compileStandardDebugKotlin
  git diff --check
  ```
  Expected: all commands pass. If Clippy cannot cover Android target dependencies, run the affected host packages separately and record the exact limitation.

- [ ] **Step 2: Run affected Dex tests.**

  ```bash
  ./x dex-test
  ```
  Expected: bind and recycle delegates resolve on every supported 8.0.65–8.0.76 APK variant; all infrastructure, unexpected, blocked, and incomplete failures remain visible and fail the command.

- [ ] **Step 3: Build fresh native artifacts through xtask.**

  ```bash
  ./x build --native-only
  ./x build
  ```
  Verify fresh `libwekit_native.so` and `libwekit_cloudflared.so` outputs for `arm64-v8a` and `armeabi-v7a` are copied into the APK inputs and packaged.

- [ ] **Step 4: Run standalone service smoke test.**

  ```bash
  cargo test -p wekit-read-receipts-server
  BIND_ADDR=127.0.0.1 PORT=0 cargo run -p wekit-read-receipts-server --release
  ```
  Exercise register, pixel, count, dashboard, and management behavior from a local client; use a temporary DB and stop the process cleanly.

- [ ] **Step 5: Install and test on a supported Android device.**

  Verify:
  - two or more visible tracked rows poll concurrently;
  - attach starts immediate polling;
  - detach/recycle stops it;
  - row reuse never leaks a count or suffix;
  - restart restores records and polling;
  - `$readReceipts` placement, fallback suffix, unknown count, and zero count;
  - tracked times are visible while MessageTimeEnhancements is disabled;
  - third-party endpoint persistence across global URL changes;
  - loopback origin start/stop;
  - Quick Tunnel URL readiness, reconnect, invalidation, and stop;
  - token validation and invalid-token recovery;
  - browser login, tunnel selection, cancellation, logout, and retry;
  - Wi-Fi/mobile transition and WeChat background/process pressure;
  - automatic and manual lifecycle controls.

- [ ] **Step 6: Record verification evidence and final review.**

  Update the implementation PR/commit notes with exact commands, APK versions, ABI results, device model/API level, tunnel mode tested, and any limitations. Do not claim Cloudflare browser-login production readiness if only the Quick Tunnel proof-of-concept has passed.

- [ ] **Step 7: Commit generated verification reports only when repository policy requires them.**

  ```bash
  git status --short
  git diff --check
  ```
  Keep ephemeral build outputs and temporary databases untracked; retain Dex reports only under the documented report directory.

---

## Spec Coverage Self-Review

- Lifecycle API, attach/detach/recycle semantics: Task 1.
- Persistent IDs, endpoint identity, 180-day pruning: Task 2 and Task 4.
- Concurrent multi-message polling and stale-result rejection: Task 4.
- `$readReceipts`, suffix fallback, forced visibility, listener-order independence: Task 3 and Task 4.
- Standalone service retained with shared core: Task 5 and Task 11.
- Loopback native Axum/libSQL server and JNI lifecycle: Task 6 and Task 7.
- Third-party/built-in radio UI and automatic lifecycle: Task 7.
- Quick Tunnel: Task 8 proof, Task 9 runtime.
- Authenticated token mode: Tasks 8–9.
- Browser login and existing-tunnel selection: Task 10.
- Foreground service, network reconnect, Android lifecycle: Task 9.
- Security, route restrictions, secret storage, notices: Task 11.
- Automated, Dex, native, build, and device verification: Task 12.

## Plan Self-Review

- No unresolved `TBD`, `TODO`, or “implement later” placeholders remain.
- Later tasks consume exact interfaces named by earlier tasks: `ReadReceiptRecordCodec`, lifecycle listener methods, renderer functions, native status methods, and tunnel status types.
- The Cloudflare proof-of-concept is an explicit gate; the plan never assumes that upstream `cloudflared` can be embedded successfully without first proving a cancellable C ABI and both Android ABIs.
- The standalone service is retained throughout; only reusable code is extracted.
- Host-hook behavior is assigned to Dex/device verification rather than low-value JVM tests, matching project testing rules.
- The plan does not expose a public Android bind address, create Cloudflare resources, or store secrets in `WePrefs`.
