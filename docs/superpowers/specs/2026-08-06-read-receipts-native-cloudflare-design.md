# Read Receipts Native Server and Cloudflare Tunnel Design

**Date:** 2026-08-06

## Summary

This design upgrades Read Receipts in five coordinated areas:

1. Poll all visible tracked messages concurrently and stop polling as soon as their rows leave the attached RecyclerView.
2. Persist tracked outgoing message records so reopening WeChat restores polling when matching messages become visible again.
3. Integrate read-count rendering with `MessageTimeEnhancements` through a new `$readReceipts` placeholder and deterministic suffix fallback.
4. Embed the read-receipts origin server in `wekit-native` while retaining `services/read-receipts/` as the standalone reference implementation.
5. Support third-party servers or a built-in server exposed through Cloudflare Quick Tunnels and authenticated existing tunnels.

The implementation is intentionally divided into independent boundaries: message lifecycle and presentation, tracked-record persistence, reusable server core, native origin lifecycle, Cloudflare connector, credential storage, and configuration UI.

## Goals

- Poll every tracked message currently attached to the chat RecyclerView.
- Stop network work when a tracked row detaches, with recycle as idempotent cleanup.
- Restore tracked-message recognition after WeChat process restarts.
- Ensure `ReadReceipts` and `MessageTimeEnhancements` cannot overwrite each other's text.
- Preserve user control over read-receipt placement through `$readReceipts`.
- Provide a private loopback origin server on Android.
- Support login-free `trycloudflare.com` Quick Tunnels.
- Support authenticated named tunnels through either a run token or browser login, with selection of an existing tunnel and hostname.
- Allow independent manual server/tunnel control or automatic coupling to the Read Receipts feature switch.
- Retain the existing standalone `services/read-receipts/` implementation.

## Non-goals

- Creating Cloudflare tunnels, DNS routes, or ingress rules from WeKit.
- Reimplementing Cloudflare's QUIC, tunnel RPC, edge discovery, and reconnect protocol in Rust.
- Exposing the standalone dashboard, REPL, destructive management routes, or remote Turso support through the embedded origin.
- Keeping Quick Tunnel URLs stable across reconnects or process restarts.
- Treating the read count as a cryptographically verified person count; it remains a distinct-client-IP approximation.
- Running the tunnel indefinitely without Android foreground-service ownership.

## Architecture

### Components

1. **`WeChatMessageViewApi` lifecycle API**
   - Preserves the current bind callback for compatibility.
   - Adds attached, detached, and recycled callbacks with the currently bound `MessageInfo`.

2. **`ReadReceipts` Android feature**
   - Detects tracked outgoing messages during bind.
   - Tracks only attached rows as active poll targets.
   - Coordinates polling, persistence, rendering, endpoint selection, and feature-level lifecycle.

3. **`MessageTimeEnhancements` renderer**
   - Owns formatted message-time rendering.
   - Adds `$readReceipts` template support.
   - Exposes a reusable rendering entry point for forced tracked-row rendering.

4. **Tracked-record store**
   - Persists versioned records through `WePrefs` string-set storage.
   - Retains backend identity and endpoint information needed after configuration changes.

5. **Reusable read-receipts server core**
   - Extracted from `services/read-receipts` into a library target.
   - Used by both the standalone executable and `wekit-native`.

6. **Native embedded-origin runtime**
   - Runs Axum and local SQLite/libSQL on a dedicated native thread and Tokio runtime.
   - Exposes start, stop, and status through JNI.

7. **Cloudflare connector bridge**
   - Uses a pinned `cloudflared` Go revision behind a small Android-oriented C ABI.
   - Supports Quick Tunnel, named-tunnel token, browser login, existing-tunnel listing/selection, status, and cancellation.

8. **Android foreground service**
   - Owns the long-lived Cloudflare connector in the WeKit application process.
   - Reports state and public URL to settings UI and the injected feature runtime.

9. **Settings and secret storage**
   - Uses `WePrefs` for non-secret configuration.
   - Uses Android Keystore-backed encryption for tunnel tokens and certificate/credential material.

## Message View Lifecycle

The existing `ICreateViewListener.onCreateView` is dispatched from WeChat's item bind method. It is a bind/rebind callback, not a visibility callback. It may run for RecyclerView prefetch, and a row may be rebound many times.

`WeChatMessageViewApi` will add a distinct listener contract:

```kotlin
interface IMessageViewLifecycleListener {
    fun onMessageViewAttached(view: View, message: MessageInfo) {}
    fun onMessageViewDetached(view: View, message: MessageInfo) {}
    fun onMessageViewRecycled(view: View, message: MessageInfo) {}
}
```

At bind time, the API will:

- Resolve the current `MessageInfo` once.
- Associate it with the root holder view in a synchronized weak binding map.
- Install one `View.OnAttachStateChangeListener` per root view.
- Update the binding before existing bind listeners run.
- If an already-attached row is rebound, dispatch the equivalent detach cleanup for the old binding and attach activation for the new binding.

The attach-state listener will look up the current binding at event time instead of capturing the first message assigned to the row.

A dedicated Dex target will resolve `WxRecyclerAdapter.onViewRecycled`. Its hook will run before WeChat's recycle cleanup, dispatch recycle for the current binding, and remove the binding. Detach is the authoritative “left the displayed list” signal; recycle is an idempotent resource-cleanup fallback because detached holders may remain cached without immediate recycling.

The existing listener and API names remain source-compatible. New code should document the old callback as bind behavior.

## Read Receipts Binding and Polling

### Bind

For every outgoing message bind, `ReadReceipts` will:

1. Parse the embedded `/pixel?wxId=…&id=…` URL.
2. Verify that the pair is present in the persisted tracked-record store.
3. Resolve `timeTV`.
4. Stamp keyed tags containing the message ID, binding identity, and known count state.
5. Render the cached count, if one exists.
6. Clear stale receipt tags and injected text when a recycled row is bound to an untracked message.

Bind does not activate polling because binding is not proof of attachment.

### Attach

When a tracked row attaches:

- Add its `timeTV` and tracked record to the active-view map.
- Trigger an immediate count fetch for that record.
- Ensure the poll coordinator is running.

### Detach and recycle

When the row detaches or recycles:

- Remove that exact view/binding from the active map.
- Clear keyed tags only if they still describe the same binding.
- Stop the poll coordinator when no active tracked records remain.

### Concurrent polling

The poll coordinator maintains one supervised coroutine scope and snapshots the distinct active records each interval. It fetches counts concurrently with a small fixed concurrency limit so multiple visible rows update in the same interval without opening an unbounded number of calls.

Each record has independent failure handling and backoff. A failed server must not delay another record's count. The normal polling interval is measured per record after its latest attempt; repeated failures use bounded exponential backoff and reset after success.

Before a result is posted to `timeTV`, the main-thread callback checks:

- The row's current keyed message-ID tag.
- The binding generation/identity.
- The active-view map entry.

A late result for a detached or rebound row is discarded.

Counts are cached by a backend-aware record key rather than message ID alone, preventing collisions if two historical records use different endpoints.

## Tracked-Message Persistence

All successfully sent tracked messages are stored in a versioned `WePrefs` string set. A record contains at least:

```text
schema version
message ID
sender wxId
backend type
polling endpoint identity
send timestamp
```

Backend identity is:

- **Third-party:** the normalized external server base URL used when the message was sent.
- **Built-in:** a logical local-server identifier, not the temporary Cloudflare hostname.

The serialized format must be delimiter-safe and reject malformed or unsupported records without breaking feature startup. The initial implementation will use an explicitly versioned JSON record encoded as a string-set element.

The record is persisted only after `/register` succeeds and the XML message is emitted. If registration fails, the send is rejected and the original input remains in `ChatFooter`.

Records are restored and pruned on feature startup. The initial retention period is 180 days, and pruning also runs after insertion. Pruning local metadata does not delete rows from the server database.

A matching embedded pixel URL remains the source of the message ID on rebind; persistence confirms that WeKit originally sent and tracks that ID and supplies its historical backend.

## `MessageTimeEnhancements` Integration

### New placeholder

The display template gains:

```text
$readReceipts
```

The settings dialog includes it in the placeholder chips.

### Rendering rules

For a tracked message with a known count:

1. **`MessageTimeEnhancements` active and template contains `$readReceipts`:** replace the placeholder exactly where the user placed it with `已读 x 人`. Add no automatic separator or suffix.
2. **`MessageTimeEnhancements` active and template lacks `$readReceipts`:** append ` | 已读 x 人` to the formatted template result.
3. **`MessageTimeEnhancements` inactive:** preserve WeChat's native current time text and append ` | 已读 x 人`.

When a count is not yet available:

- Replace `$readReceipts` with an empty string.
- Do not append an automatic suffix.
- Force `timeTV` visible for the tracked row.
- Rerender the complete text when the first count arrives.

A real server count of zero is known data and renders as `已读 0 人`; only “no successful response yet” is empty.

### Ownership and cooperation

`MessageTimeEnhancements` remains the owner of template formatting, text style, padding, visibility policy, and alignment. It will expose a reusable renderer that accepts the current `MessageInfo`, `TextView`, force-visible flag, and optional known read count.

Receipt state and binding identity live in keyed `timeTV` tags. During its normal bind render, `MessageTimeEnhancements` reads that state. When a count changes:

- If `MessageTimeEnhancements.isActive`, `ReadReceipts` requests a full renderer pass.
- Otherwise, `ReadReceipts` updates only its own marker-based suffix on the preserved native time text.

For inactive enhancement mode, the receipt-specific marker is used solely to replace/remove WeKit's previously injected suffix; the implementation never uses another feature's arbitrary text as its formatting source.

This removes listener-order dependence, prevents duplicate suffixes, forces tracked time visibility, and prevents stale receipt text on recycled rows.

## Server Core and Standalone Service

`services/read-receipts/` remains in the repository and continues to provide the standalone reference server.

Reusable protocol, database, and router behavior will be extracted into a library target within that crate. The standalone binary retains:

- CLI/environment configuration.
- Dashboard and management endpoints.
- Interactive REPL.
- Signal handling.
- Optional remote Turso support.

Desktop-only dependencies and behavior will be gated behind the binary/CLI feature so they are not linked into Android.

The reusable server core exposes configuration that controls:

- Database path/backend.
- Bind address and port.
- Enabled route profile.
- Trusted proxy/header behavior.
- Request limits and rate limits.
- Graceful shutdown.

The embedded route profile includes only:

- `POST /register`
- `GET /pixel`
- `GET /count`
- A metadata-free health endpoint

It excludes the dashboard, message/read listing, delete routes, REPL, and remote Turso configuration.

## Embedded Native Origin

The embedded origin is hosted inside `wekit-native` and exposes JNI operations conceptually equivalent to:

```kotlin
startReadReceiptsServer(databasePath, port): ServerStartResult
stopReadReceiptsServer()
getReadReceiptsServerStatus(): ServerStatus
```

The Rust implementation will:

- Validate the absolute database path.
- Start a named native thread.
- Build a dedicated Tokio runtime on that thread.
- Open the local database and initialize schema.
- Bind only to `127.0.0.1`.
- Publish success only after the listener is ready.
- Return the actual bound port.
- Hold shutdown and lifecycle state behind synchronized global state.
- Support idempotent start/stop and reject conflicting active configuration.
- Stop asynchronously without unbounded blocking on a JNI/UI caller.

The database path is:

```text
HostInfo.application.filesDir/wekit-read-receipts/read_receipts.db
```

The server and tunnel are separate state machines. Startup order is origin then tunnel; shutdown order is tunnel then origin.

### Embedded route constraints

- Validate message-ID format and all field lengths.
- Apply request-body and query limits.
- Reject pixel logging for unknown message IDs.
- Apply lightweight limits to registration and count routes.
- Always return the static pixel even if read logging fails.
- Preserve `COUNT(DISTINCT ip)` behavior.
- Use the original client address supplied by the trusted local Cloudflare connector only after the connector-origin boundary has authenticated/validated it; direct local requests use the socket peer.

The exact trusted-client-IP mechanism will be selected from the pinned connector's supported origin headers and fixed in the implementation plan. It must not trust arbitrary forwarded headers from non-connector callers.

## Cloudflare Connector

### Boundary

Cloudflare support is isolated behind a narrow connector interface. The implementation will use a pinned `cloudflared` Go revision rather than porting Cloudflare's transport protocol to Rust.

A maintained bridge/fork exposes a stable C ABI for:

```text
start quick tunnel
start named tunnel from run token
begin/cancel browser login
list authorized accounts and existing tunnels
select an existing tunnel and hostname
start the selected tunnel
stop tunnel
query status and public URL
```

The bridge disables CLI parsing, desktop browser launching, auto-update, process signal handlers, standalone metrics listeners, diagnostics servers, and unrelated commands. It retains the upstream transport, edge discovery, reconnect, Quick Tunnel, login-transfer, and existing-tunnel credential logic required by this feature.

It is packaged separately for both supported ABIs:

```text
arm64-v8a/libwekit_cloudflared.so
armeabi-v7a/libwekit_cloudflared.so
```

`xtask` pins and builds the bridge before `wekit-native` and packages both fresh artifacts. The bridge revision, source modifications, and license notices are documented.

### Runtime ownership

An Android foreground service in the WeKit application process owns the connector. The origin remains in WeChat's main process and is reachable over device loopback.

The service owns long-lived transport state, reconnect handling, notification state, and tunnel callbacks. It reports:

```text
STOPPED
AUTHORIZING
STARTING
CONNECTED
RECONNECTING
NEEDS_USER_ACTION
FAILED
STOPPING
```

The connector must tolerate Android network changes and reconnect with bounded backoff. A public URL is active only while the connector reports a valid session.

### Quick Tunnel

Quick Tunnel startup:

1. Wait for the loopback origin health check.
2. Request ephemeral tunnel credentials from Cloudflare.
3. Start the Cloudflare transport.
4. Publish the generated `https://*.trycloudflare.com` URL after readiness.
5. Use that URL for registration and outgoing pixel URLs.
6. Invalidate it immediately when the session stops.

The UI warns that Quick Tunnels are temporary/testing-only, carry no availability guarantee, and change hostname after session loss. Messages already sent with an expired Quick Tunnel cannot record new reads, although existing data remains queryable in the local database.

### Named tunnel token

Token mode accepts:

- An existing named-tunnel run token.
- An existing configured public HTTPS hostname.

The connector starts the existing tunnel; WeKit does not create or modify tunnels, DNS records, or ingress configuration.

The mode is considered ready only after:

- Cloudflare accepts the token.
- The connector reaches a connected state.
- The configured public hostname reaches the embedded health endpoint.

### Browser login and existing-tunnel selection

Browser-login mode:

1. Begin Cloudflare's authorization-transfer operation and return the authorization URL.
2. Open it through an Android browser intent and provide a copyable fallback.
3. Poll asynchronously for completion, with cancellation and timeout.
4. List authorized accounts and their existing tunnels.
5. Let the user select or manually provide an existing tunnel and one of its existing configured public hostnames.
6. Obtain/store the selected tunnel's narrowly scoped run credential for normal startup.
7. Discard account-wide authorization material when no longer required for listing/refresh, unless the user explicitly retains login to refresh tunnel selection later.
8. Validate the selected public hostname against the embedded health endpoint.

The app does not create tunnels, DNS routes, hostnames, or ingress rules.

Browser-login state survives dialog recreation through a process-level coordinator. If the owning process dies, the operation is restarted rather than persisting transient account-wide authorization material in plaintext.

## Configuration UI

The Read Receipts dialog remains a single scrollable settings surface with conditional sections.

### Common controls

Always visible:

- Trigger prefix.
- Polling interval.
- Runtime status summary.
- Number of active polled records/views.
- Last relevant server/tunnel error.

### Server mode radio group

1. **Third-party server**
2. **Built-in server + Cloudflare Tunnel**

#### Third-party mode

Show:

- External server URL.
- Test-connection action.

Hide all built-in server and tunnel controls. The URL must be normalized and HTTPS. Third-party services are never started or stopped by WeKit.

Each sent record retains this exact normalized polling endpoint so a later global configuration change does not redirect historical polling.

#### Built-in mode

Show:

- Local server state.
- Loopback port.
- Database path and size.
- Manual start/stop controls.
- Tunnel state.
- Manual connect/disconnect controls.
- Active public URL with copy/share actions.
- `Automatically start/stop server and Tunnel with this feature` switch.

The local polling endpoint is always loopback. Persisted built-in records use a logical local backend identity, so count polling remains valid if a Quick Tunnel URL changes.

### Tunnel mode radio group

1. **Quick Tunnel**
2. **Authenticated tunnel**

Quick mode shows its active temporary URL and limitations.

Authenticated mode contains:

1. **Tunnel token**
2. **Browser login**

Token controls:

- Masked token field.
- Existing public hostname.
- Saved/invalid state.
- Validate, replace, and delete actions.

Browser-login controls:

- Log in with Cloudflare.
- Authorization progress/cancel.
- Selected account.
- Refreshable existing-tunnel list.
- Selected tunnel.
- Existing hostname selection with manual fallback.
- Log out/delete credentials.

### Manual and automatic lifecycle

When automatic lifecycle is enabled:

- Enabling Read Receipts starts the embedded origin then the configured tunnel.
- Disabling Read Receipts stops the tunnel then the origin.
- Saving runtime-affecting changes performs a controlled tunnel-first restart.
- Sending is blocked until the active endpoint is verified reachable.

When automatic lifecycle is disabled:

- The feature switch controls message hooks/view tracking only.
- Server and tunnel retain their current states.
- Explicit buttons control origin and connector.
- Sending through a stopped, stale, reconnecting, or unverified endpoint is rejected and the original input remains intact.

If Android rejects a background foreground-service start, runtime state becomes `NEEDS_USER_ACTION`; WeKit does not send a tracked message with a stale URL.

### Save validation

Only selected-mode fields are validated:

- Third-party mode requires an HTTPS URL.
- Built-in mode requires a valid available loopback port or explicit automatic-port selection.
- Token mode requires a token and HTTPS hostname.
- Browser-login mode requires an existing tunnel and hostname selection.
- Poll interval must be positive.
- Empty prefix remains allowed after warning.

Preferences are committed only after all selected-mode validation succeeds.

## Runtime Data Flow

### Third-party send

1. User sends text with the configured prefix.
2. Resolve and verify the configured external endpoint.
3. Compute the deterministic message ID.
4. Register the plaintext message with that endpoint.
5. On registration success, emit XML containing that endpoint's pixel URL.
6. Persist the tracked record with its external endpoint.
7. Clear the input and show success.

### Built-in send

1. User sends text with the configured prefix.
2. Confirm origin and tunnel are connected and the public URL is current.
3. Register through the loopback origin.
4. Compute/confirm the deterministic message ID.
5. Emit XML containing the current public tunnel pixel URL.
6. Persist a built-in tracked record.
7. Clear the input and show success.

### Historical bind and polling

1. WeChat binds a message row.
2. Parse its pixel message ID.
3. Match persisted tracked metadata.
4. Render cached state, if available.
5. When attached, activate the record in the poll coordinator.
6. Poll its historical third-party endpoint or the current local built-in origin.
7. On detach/recycle, deactivate the exact row.

## Failure Handling

- Native start returns a bounded readiness/error result.
- Tunnel state is asynchronous and generation-scoped; callbacks from an obsolete configuration cannot publish stale state.
- Registration failure prevents message emission and preserves input.
- Count failure retains the last known count.
- Each backend/record has isolated retries and bounded exponential backoff.
- Polling stops when no tracked rows are attached.
- Origin/tunnel start and stop are idempotent.
- Controlled shutdown is tunnel first, then origin.
- Configuration changes cannot leave old URLs marked active.
- Quick Tunnel session loss immediately blocks new tracked sends.
- Browser login supports timeout, cancellation, browser-launch failure, process-loss restart, and credential cleanup.

## Security and Privacy

- The embedded origin binds only to `127.0.0.1`.
- Only register, pixel, count, and metadata-free health routes are tunneled.
- Dashboard and management/delete routes remain standalone-only.
- Tokens and certificate material never enter `WePrefs`, logs, crash text, clipboard, or saved-instance state.
- Android Keystore-backed encryption stores retained tunnel-scoped credentials.
- Account-wide login material is minimized and deletable.
- Cloudflare TLS verification remains strict.
- IDs, fields, requests, and queries are bounded.
- Registration/count routes are rate-limited; pixel delivery remains cheap and resilient.
- Unknown message IDs are not logged as reads.
- The database remains in private internal storage.
- UI warns that plaintext tracked content and reader IP data are stored.
- Apache 2.0 and transitive notices are included, and WeKit does not imply official Cloudflare endorsement.

## Testing and Verification

### Automated tests

Add tests only for meaningful project-owned, low-coupling logic:

- Tracked-record serialization, unsupported/malformed records, migration, and 180-day pruning.
- Historical endpoint selection.
- `$readReceipts` placement, suffix fallback, unknown count, and known zero count.
- Kotlin/Rust message-ID compatibility using shared test vectors.
- Server request validation and embedded route profile.
- Desktop server start/stop idempotence and database persistence.
- Connector ABI state transitions with a fake connector.
- Selected-mode preference validation.

Do not add low-value tests for constants, direct mappings, Compose layout, JNI declarations, or host-dependent hooks.

### Build and desktop verification

- Build/test the standalone `services/read-receipts` binary and shared library.
- Build the Cloudflare bridge for `arm64-v8a` and `armeabi-v7a`.
- Run Rust formatting, tests, and Clippy for affected crates.
- Run relevant Kotlin tests.
- Run affected `./x dex-test` cases for supported WeChat 8.0.65–8.0.76 variants after lifecycle resolver changes.
- Run `./x build` to compile/package fresh native artifacts.
- Run `git diff --check`.

### Real-device verification

- Multiple tracked rows poll concurrently.
- Polling begins on attach, stops on detach, and remains cleaned through recycle/rebind.
- Reopening WeChat restores historical recognition and polling.
- Tracked-to-untracked row reuse never leaks suffix/count state.
- `$readReceipts` placement and suffix fallback work in every enhancement state.
- Tracked rows force time visibility when enhancements are disabled.
- Third-party server mode works across global endpoint changes.
- Quick Tunnel starts, reconnects, invalidates stale URLs, and stops.
- Named token mode connects and rejects invalid credentials/hostnames.
- Browser login supports selection, cancellation, process recreation, and retry.
- Wi-Fi/mobile transitions recover correctly.
- Foreground-service and WeChat process lifecycle behavior is correct.
- Manual and automatic lifecycle modes obey their contracts.
- Credential deletion prevents subsequent automatic startup.

## Implementation Staging

The implementation plan should preserve these reviewable stages:

1. Message-view lifecycle API and Dex validation.
2. Tracked-record persistence and concurrent polling.
3. `$readReceipts` renderer integration.
4. Reusable server-core extraction while preserving standalone behavior.
5. Native embedded-origin runtime and JNI bridge.
6. Configuration UI and origin lifecycle.
7. Pinned Cloudflare bridge proof of concept for both ABIs.
8. Foreground-service connector runtime.
9. Quick Tunnel UI/flow.
10. Named-token and browser-login flows for existing tunnels.
11. Security hardening, documentation, and full verification.

Cloudflare integration must not begin by entangling upstream transport internals with `ReadReceipts`; the connector interface and both-ABI build proof come first.
