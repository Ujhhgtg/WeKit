# BeanShell Hook and Script DRM Bypass Repair Design

## Status

- Date: 2026-08-05
- State: draft for maintainer review
- Target branch: `dev`
- Implementation status: not started

This document defines the repair for WeKit's custom BeanShell hook framework and the
`BypassScriptsDrm` feature. It records the design approved in discussion, but implementation must
not begin until the maintainer approves this written specification.

## Context

WeKit's BeanShell fork exposes process-global before/after hooks for local BeanShell methods, JVM
methods, JVM fields, and BeanShell variables. `BypassScriptsDrm` uses the local-method hook to replace
selected capture-detection, authorization, and blacklist results in Java scripts.

The audit confirmed that the basic interception works, but identified six related defects:

1. `JavaEngine.executeAllOnLoad` installs one anonymous global hook per plugin and never owns or
   removes those registrations.
2. Intercepted operations return before their documented after-hook runs.
3. Variable after-hooks are never fired, while Java-field writes and one static-field read path also
   omit their after-hooks.
4. The bypass is process-global and matches only a method name, so it affects unrelated interpreters
   and same-named functions.
5. BeanShell code can reach the public global hook registry unless the interpreter security guard
   explicitly denies that access.
6. The bypass ignores the declared return type and can substitute a value incompatible with a
   same-named method.

The repair must preserve the existing BeanShell integration and target method-name coverage while
making hook behavior deterministic, scoped, testable, and resistant to modification by interpreted
scripts.

## Goals

- Install at most one DRM-bypass hook in the process.
- Make enabling and disabling `BypassScriptsDrm` take effect immediately for already-loaded scripts.
- Scope DRM interception to the exact `Interpreter` instances owned by loaded Java plugins.
- Keep plugin interpreter registration independent of the bypass feature's current enabled state.
- Preserve the current target method names without inventing undocumented parameter signatures.
- Intercept only when the declared return type can accept the replacement value.
- Fire corresponding after-hooks exactly once for intercepted operations and successful original
  operations.
- Wire the currently missing variable and Java-field after-hook paths.
- Prevent interpreted BeanShell code from reading or mutating the host-owned global hook registry.
- Make repeated registration of the same hook idempotent.
- Add focused automated regression tests for all host-independent behavior.

## Non-goals

- Do not replace the entire hook framework with a per-`Interpreter` manager. Several variable and
  field paths do not naturally carry an interpreter today, and that rewrite is unnecessary for the
  DRM bypass.
- Do not sandbox arbitrary compiled JVM or Android code running in the WeChat process. Code with
  direct in-process bytecode access cannot be made hostile-proof by a BeanShell security guard.
- Do not reverse engineer or guess parameter lists for the targeted third-party script versions.
- Do not broaden the bypass to arbitrary JVM method, field, or variable hooks.
- Do not change unrelated Java scripting APIs or script-loading behavior.
- Do not require after-hooks to run when the original operation throws. Hook parameters currently
  have no throwable channel; exception-path hooks require a separate API design.
- Do not add host- or Android-dependent unit tests merely to satisfy a testing workflow. Physical
  WeChat behavior remains a manual validation boundary.

## Confirmed Decisions

1. Retain the existing process-global `Interpreter.bshHookManager` for compatibility.
2. Add interpreter and invocation-namespace context to `LocalMethodHookParam`.
3. Use one stable hook object owned by `BypassScriptsDrm`.
4. Keep a concurrent set of loaded plugin interpreter identities in `BypassScriptsDrm`.
5. `JavaEngine` registers every plugin interpreter before evaluating its source and unregisters it
   during unload, regardless of whether the bypass is enabled.
6. `BypassScriptsDrm.onEnable` registers the stable hook; `onDisable` removes it. The interpreter
   set is retained while scripts remain loaded so re-enabling takes effect immediately.
7. `BshHookManager.addHook` becomes idempotent for the same hook object.
8. Interception is scoped by interpreter identity, exact method name, and compatible declared return
   type. Parameter arrays remain observable but are not constrained without target-version evidence.
9. After-hooks run once after an intercepted result is established and once after a successful
   original operation. They may transform the final return value.
10. The BeanShell security guard denies interpreted access to `Interpreter.bshHookManager` and
    denies method calls on a `BshHookManager` instance.

## Architecture

### Stable bypass hook ownership

`BypassScriptsDrm` becomes the sole owner of one anonymous or private `BshHook` instance. It also
owns a thread-safe set of `Interpreter` instances belonging to loaded Java plugins.

The object exposes internal lifecycle methods for `JavaEngine`:

```kotlin
internal fun registerInterpreter(interpreter: Interpreter)
internal fun unregisterInterpreter(interpreter: Interpreter)
```

Registration is independent of feature state. This is important because scripts can load while the
bypass is disabled and the user can enable it later. The feature lifecycle controls only whether the
stable hook is present in `Interpreter.bshHookManager`:

```kotlin
override fun onEnable() {
    Interpreter.bshHookManager.addHook(hook)
}

override fun onDisable() {
    Interpreter.bshHookManager.removeHook(hook)
}
```

The interpreter set uses concurrent membership operations. `Interpreter` has object identity
semantics, so its existing equality behavior is sufficient for set membership. `addHook` uses
`CopyOnWriteArrayList.addIfAbsent`, making a repeated enable or registration attempt harmless.

### JavaEngine lifecycle integration

Before `initPlugin` and `plugin.interpreter.eval`, `JavaEngine.executeAllOnLoad` registers the
plugin's interpreter with `BypassScriptsDrm`. Registration must occur before evaluation because DRM
checks may execute at top level while the script source is being evaluated.

`JavaEngine.executeAllOnUnload` invokes the script's `onUnload` method and unregisters the
interpreter in a `finally` block. An `onUnload` exception therefore cannot retain an interpreter in
the bypass scope. Repeated registration or unregistration is safe.

The old per-plugin anonymous hook creation is removed completely.

### Local-method hook context

`LocalMethodHookParam` gains immutable context fields:

```java
private final Interpreter interpreter;
private final NameSpace invocationNameSpace;
```

`Name.invokeLocalMethod` supplies the active interpreter and invocation namespace when constructing
the parameter. Existing method metadata and mutable result/interception fields remain available.

This context is part of the hook API rather than inferred from Java stack traces. The existing Java
stack snapshot may remain for compatibility, but it is not used for plugin identity.

### Signature-safe replacement values

The bypass continues to recognize these method groups:

- false or no-op: `isUsingVPN`, `isUsingProxy`, `hasSuspiciousCertificates`,
  `isSSLValidationBypassed`, `detectPacketCapture`, `showAntiCaptureDialog`,
  `fetchBlackListFromNetwork`, `checkBlackListSync`, `showBlackToast`
- empty collection: `getBlackFriends`
- true: `checkAuthorization`

Before intercepting, the hook verifies that `param.interpreter` is registered and then evaluates the
declared return type:

- `void` receives `Primitive.VOID`.
- primitive or boxed Boolean receives `true` or `false` as appropriate.
- an untyped method (`null` or `Object`) receives the existing intended replacement.
- `getBlackFriends` is intercepted only for an untyped method or a return type compatible with the
  empty `ArrayList` replacement.
- any incompatible declared return type is left untouched.

The current target scripts have not been version-grounded for parameter lists. The repair therefore
does not reject a method based on argument count or type. This avoids silently breaking known name
coverage through an invented signature. `paramTypes` remains available for a future evidence-based
tightening.

## Hook Execution Contract

### Before and after ordering

Every instrumented operation follows this sequence:

1. Construct one hook parameter.
2. Fire the before-hook chain.
3. If intercepted, keep the supplied replacement and skip the original operation.
4. Otherwise execute the original operation and store its successful result in the parameter.
5. Fire the after-hook chain exactly once.
6. Return the parameter's final result, allowing after-hooks to transform it.

Early returns before step 5 are prohibited for intercepted operations.

If the original operation throws, the exception propagates using the existing behavior and no
after-hook is fired. This must be stated explicitly in `BshHook` documentation so cleanup guarantees
are not overstated.

### Variable operations

All successful variable reads fire before and after hooks. An intercepted read still fires its
after-hook and returns the final mutable `returnValue`.

Variable writes fire before and after hooks whether the write is intercepted or successfully
performed. The write `value` remains mutable before execution. The existing API documents write
`returnValue` as unused, so the repair does not invent a new assignment-result contract.

### Java-field operations

All currently instrumented successful field reads and writes fire symmetric before/after hooks,
including:

- `LHS` field reads;
- `LHS` field writes;
- static field reads through `Name`;
- object field reads through `Name`;
- object field reads through `BSHPrimarySuffix`.

An intercepted field operation also fires its after-hook. Write hooks observe the final possibly
modified input value.

### Java and local methods

All current local- and Java-method interception paths eliminate the early return before the
after-hook. After-hooks receive the original result or the intercepted replacement and may replace
that result before it is returned to the script.

## Registry Security

The registry remains public for host-side WeKit integration, but interpreted BeanShell access is
denied by `MainSecurityGuard.BasicSecurityGuard`:

- `canGetStaticField` returns false for `Interpreter.bshHookManager`.
- `canSetStaticField` also rejects that field for completeness.
- `canInvokeMethod` returns false when the receiver is a `BshHookManager`.
- `canSetField` rejects mutation of a `BshHookManager` instance.

The existing reflection-aware security checks delegate field and method reflection back through
these guards, so interpreted reflective access follows the same policy.

This protects against ordinary BeanShell code clearing the registry, removing the bypass, or adding
a later hook. It does not claim protection from arbitrary compiled code executing directly in the
same process.

## Error Handling and Logging

`BshHookManager` continues isolating one hook's `Exception` so it does not break the interpreter or
prevent later hooks from running. This repair does not broaden the catch to `Throwable`, because VM
errors should not be silently converted into hook failures.

The existing message-only `System.err` logging is retained in this scoped repair. Replacing it with
an Android-aware logger would add an application dependency to the standalone BeanShell library and
is outside the audited correctness defects.

## Testing Strategy

The BeanShell framework behavior is host-independent and qualifies for automated tests under the
project testing policy. Add focused tests in `libs/common/bsh` that use real `Interpreter` execution:

1. An intercepted local method skips its body, fires before and after exactly once, and permits the
   after-hook to transform the replacement.
2. A successful non-intercepted local method fires before and after exactly once.
3. `LocalMethodHookParam` carries the exact active interpreter and invocation namespace.
4. Variable reads and writes fire symmetric before/after hooks.
5. Java-field reads and writes, including static access, fire symmetric before/after hooks.
6. Registering the same hook object twice results in one callback.
7. Interpreted access to `Interpreter.bshHookManager` and calls on an exposed manager object are
   rejected by the security guard.

The DRM matching and scoping logic should live in a small host-independent hook class or helper in
the scripting package so app JVM tests can cover:

1. An unregistered interpreter is never intercepted.
2. A registered interpreter receives the intended Boolean, void, and collection replacements.
3. An incompatible declared return type is not intercepted.
4. Register/unregister operations are idempotent.

Do not add tests that require WeChat classes, runtime state, UI, MMKV, or Android hook execution.

## Validation

Implementation is complete only after all of the following pass:

1. New focused BeanShell and pure DRM-hook tests, including observed red-to-green cycles.
2. The complete `libs/common/bsh` build.
3. Relevant existing Gradle tests that qualify under the project testing policy.
4. `./x build`, ensuring the current BeanShell submodule is packaged into both debug APK variants.
5. `git diff --check` in both the BeanShell submodule and parent WeKit repository.

Physical WeChat validation remains separate. The implementation report must distinguish automated
interpreter/build validation from real target-script behavior on an Android device.

## Commit Boundaries

Keep review and rollback boundaries explicit:

1. Commit BeanShell framework tests and framework/security fixes inside `libs/common/bsh`.
2. Commit the scoped DRM bypass and JavaEngine lifecycle integration in the parent repository while
   updating the submodule pointer.

Do not stage or rewrite unrelated commits in either repository, and do not push unless requested.
