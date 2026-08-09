# Task 9 Verifiable Session and Origin Terminal Design

## Goal

Close the final two Task 9 review gaps without beginning Task 10: a network-invalidated native
session must remain unverifiable until a genuinely new native start succeeds, and every origin
request that owns a transaction callback must deliver exactly one explicit terminal result.

## Native verification state

`TunnelNativeLease` owns `verifiableNativeSessionEpoch: Long?` in the same monitor as generation,
active-request generation, native owner, network epoch, and native-session epoch.

- A successful `startIfCurrent` creates a new native-session epoch and marks that exact epoch
  verifiable.
- `invalidateNetwork`, `stopIfOwner`, and `stopForReplacement` synchronously clear verifiability.
- `captureVerification` returns a ticket only when generation, active request, owner, and the current
  native-session epoch all match and that session is marked verifiable.
- `commitVerification` repeats the same verifiability check before credential write,
  `pendingToken` clear, and CONNECTED publication while holding the lease monitor.
- `activateRequest` defaults to invalidating any inherited owner session. An explicitly administrative
  generation may preserve the current marker, but preservation never turns a null marker back on.
  Quick-mode credential deletion is the only planned preserving caller and receives a focused test.

Therefore a default-network available, lost, or replacement callback immediately makes both old
tickets and new post-event capture attempts unusable. Async native stop cannot reopen verification;
only a later successful native start can do so.

## Origin terminal model

Origin lifecycle callbacks use a sealed terminal:

```kotlin
sealed interface OriginRequestTerminal<out T> {
    data class Completed<T>(val result: Result<T>) : OriginRequestTerminal<T>
    data object Superseded : OriginRequestTerminal<Nothing>
}
```

`submitOriginRequest` separates execution from delivery. Its worker computes exactly one terminal:
every current path produces `Completed(result)`, while every stale checkpoint produces
`Superseded`. It then performs one Main-thread delivery. A final generation check on Main converts a
just-staled `Completed` to `Superseded`; no other path calls the owner callback.

`Superseded` contains no exception message, credential, token, configuration, or other request data.
It is control flow, not a logged failure.

## Call-chain propagation

The typed terminal is preserved through the real transaction chain:

```text
submitOriginRequest
  -> startOrigin / stopOrigin
  -> startBuiltInStack / stopBuiltInStack
  -> applyAndStartBuiltInStack
  -> connection UI completion
```

- `Completed(Result.success)` continues the current request.
- `Completed(Result.failure)` runs only the existing failure handling owned by that still-current
  transaction.
- `Superseded` releases the caller's completion ownership exactly once and propagates upward without
  saving configuration, rolling back, starting or stopping another runtime, clearing a token, or
  publishing a stale success/failure.
- The notification-rejection restore path obeys the same rule: a superseded origin stop completes the
  old connection transaction as superseded and performs no old `saveConfiguration` or restart.
- UI handling always clears `connectionTransactionActive`. It clears the token only for a current
  successful `Completed` terminal. Superseded shows at most a generic replacement notice and never
  carries or logs the token.
- Fire-and-forget origin requests may omit a terminal owner; transaction-owning requests must supply
  one and receive exactly one Main-thread terminal.

## Tests

Focused JVM tests cover:

1. Successful owner start, network invalidation, capture before async stop, stop without restart, and
   new successful start. Capture is null and old commit has zero side effects until the new start.
2. Available, lost, and replacement events through the same invalidation contract.
3. Administrative generation preservation both before and after invalidation; it preserves a valid
   session but cannot reactivate an invalidated one.
4. Every stale execution checkpoint for old start and stop requests. Each old owner receives one
   `Superseded`, rollback/save/start/stop counters stay zero, and the new request completes normally.
5. Completed success/failure versus Superseded transaction handling, including notification restore
   and UI ownership release. Only current Completed paths may perform their associated side effects.

An internal read-only reviewer must trace the production call chain above rather than approving only
the coordination helpers. Existing closed Task 9 behavior and device acceptance gates remain intact.
