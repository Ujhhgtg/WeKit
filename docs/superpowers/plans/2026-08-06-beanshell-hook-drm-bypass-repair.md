# BeanShell Hook and Script DRM Bypass Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair WeKit’s custom BeanShell hook contract and make `BypassScriptsDrm` lifecycle-owned, interpreter-scoped, return-type-safe, and immediately toggleable.

**Architecture:** Keep the process-global `Interpreter.bshHookManager`, but make registration idempotent and make every instrumented successful/intercepted operation follow one before/original-or-replacement/after/return sequence. Add explicit interpreter and namespace context to local-method parameters. In the parent repository, put matching and interpreter membership in a small host-independent hook class, while `BypassScriptsDrm` owns one stable instance and `JavaEngine` registers plugin interpreters independently of feature state.

**Tech Stack:** Java 8-compatible BeanShell fork, Kotlin, JUnit Jupiter, Android Gradle Plugin 9, Gradle, `CopyOnWriteArrayList`, concurrent identity membership, WeKit `SwitchFeature`, `./x` build orchestration.

## Global Constraints

- Target branch: `dev`.
- Retain the existing process-global `Interpreter.bshHookManager` for compatibility.
- Do not add parameter-count or parameter-type restrictions without target-version evidence.
- Do not run after-hooks when the original operation throws.
- Do not catch `Throwable` in `BshHookManager`; continue isolating only `Exception`.
- Do not add WeChat-, Android-runtime-, MMKV-, UI-, or host-dependent tests.
- Do not replace the hook framework with a per-interpreter manager.
- Do not add test-only interfaces, wrappers, or dependency injection.
- Work in the existing checkout on `dev`; no worktree is requested.
- Keep the BeanShell framework commit inside `libs/common/bsh`; keep the parent integration commit in WeKit and update the submodule pointer.
- Do not stage unrelated `.claude/`, `.superpowers/`, or existing untracked plan files.
- Do not push.

---

### Task 1: Repair the BeanShell hook API, execution contract, and security

**Files:**
- Modify: `libs/common/bsh/build.gradle.kts`
- Modify: `libs/common/bsh/src/main/java/bsh/BshHook.java`
- Modify: `libs/common/bsh/src/main/java/bsh/BshHookManager.java`
- Modify: `libs/common/bsh/src/main/java/bsh/LocalMethodHookParam.java`
- Modify: `libs/common/bsh/src/main/java/bsh/Name.java`
- Modify: `libs/common/bsh/src/main/java/bsh/NameSpace.java`
- Modify: `libs/common/bsh/src/main/java/bsh/LHS.java`
- Modify: `libs/common/bsh/src/main/java/bsh/BSHPrimarySuffix.java`
- Modify: `libs/common/bsh/src/main/java/bsh/security/MainSecurityGuard.java`
- Create: `libs/common/bsh/src/test/java/bsh/BshHookContractTest.java`
- Create: `libs/common/bsh/src/test/java/bsh/BshHookSecurityTest.java`

**Interfaces:**
- Consumes: `Interpreter.bshHookManager`, `BshHook`, all hook parameter classes, real `Interpreter.eval(String)`, and interpreter security dispatch.
- Produces: `LocalMethodHookParam.getInterpreter()`, `getInvocationNameSpace()`, idempotent hook registration, symmetric successful/intercepted hook execution, protected hook registry, and focused regression coverage.

- [ ] **Step 1: Configure JUnit Jupiter for the standalone BeanShell Android library**

Add the same JUnit Jupiter version/convention used by the repository’s existing JVM tests. Configure Android local unit-test tasks to use JUnit Platform and add `testImplementation`/launcher dependencies without adding production dependencies.

- [ ] **Step 2: Write failing local-method and registration tests**

In `BshHookContractTest`, install one recording hook, retain its object reference for cleanup, and use `@AfterEach` to remove it. Cover:

```java
@Test
void interceptedLocalMethodRunsBeforeAndAfterOnceAndAfterCanTransformResult() throws Exception {
    Interpreter interpreter = new Interpreter();
    AtomicInteger bodyCalls = new AtomicInteger();
    AtomicInteger beforeCalls = new AtomicInteger();
    AtomicInteger afterCalls = new AtomicInteger();

    BshHook hook = new BshHook() {
        @Override
        public void beforeLocalMethod(LocalMethodHookParam param) {
            if (param.getMethodName().equals("target")) {
                beforeCalls.incrementAndGet();
                assertSame(interpreter, param.getInterpreter());
                assertNotNull(param.getInvocationNameSpace());
                param.setReturnValue(40);
                param.setIntercepted(true);
            }
        }

        @Override
        public void afterLocalMethod(LocalMethodHookParam param) {
            if (param.getMethodName().equals("target")) {
                afterCalls.incrementAndGet();
                param.setReturnValue(((Number) param.getReturnValue()).intValue() + 2);
            }
        }
    };

    Interpreter.bshHookManager.addHook(hook);
    interpreter.set("bodyCalls", bodyCalls);
    assertEquals(42, ((Number) interpreter.eval("int target() { bodyCalls.incrementAndGet(); return 1; } target();")).intValue());
    assertEquals(0, bodyCalls.get());
    assertEquals(1, beforeCalls.get());
    assertEquals(1, afterCalls.get());
}
```

Also cover a non-intercepted local method and registering the exact same hook object twice. Assert one before callback and one after callback.

- [ ] **Step 3: Write failing variable and Java-field symmetry tests**

Use real interpreter execution and filter events by access type/name so assignment-expression follow-up reads do not make the test ambiguous. Cover successful and intercepted reads/writes for variables, instance fields, and static fields. Assert each explicitly instrumented operation receives one before and one after callback and that after-hooks can transform read results.

Use a nested public fixture class with a public instance field and public static field so no Android or host class is involved.

- [ ] **Step 4: Write failing security tests**

In `BshHookSecurityTest`, verify interpreted code cannot:

```java
interpreter.eval("bsh.Interpreter.bshHookManager;");
interpreter.set("manager", Interpreter.bshHookManager);
interpreter.eval("manager.clearHooks();");
```

Assert the interpreter reports the security denial for both direct static-field access and method calls on an exposed manager. Preserve host-side Java access in the test fixture so the restriction is proven to apply through the BeanShell security guard, not Java visibility.

- [ ] **Step 5: Run the new focused tests and observe the expected failures**

Run from `libs/common/bsh`:

```bash
./gradlew testDebugUnitTest --tests 'bsh.BshHookContractTest' --tests 'bsh.BshHookSecurityTest'
```

Expected: compilation fails first because `LocalMethodHookParam.getInterpreter()` and `getInvocationNameSpace()` do not exist, or tests fail on skipped after-hooks, duplicate registration, and unguarded registry access after temporarily limiting compilation to existing API tests.

- [ ] **Step 6: Add immutable local-method invocation context**

Extend `LocalMethodHookParam` with:

```java
private final Interpreter interpreter;
private final NameSpace invocationNameSpace;

public Interpreter getInterpreter() {
    return interpreter;
}

public NameSpace getInvocationNameSpace() {
    return invocationNameSpace;
}
```

Add both values to its constructor. Update `Name.invokeLocalMethod` to pass the active `Interpreter` and the invocation namespace used to resolve the method. Keep all existing metadata and mutable result/interception behavior.

- [ ] **Step 7: Make duplicate registration idempotent**

Change only `BshHookManager.addHook`:

```java
public void addHook(BshHook hook) {
    hooks.addIfAbsent(hook);
}
```

Keep ordered `CopyOnWriteArrayList` dispatch, `removeHook`, `clearHooks`, and per-hook `Exception` isolation unchanged.

- [ ] **Step 8: Repair local and Java method ordering**

For every current local/Java method path in `Name` and `BSHPrimarySuffix`, use this exact control flow:

```java
manager.fireBeforeJavaMethod(param);
if (!param.isIntercepted()) {
    param.setReturnValue(invokeOriginal());
}
manager.fireAfterJavaMethod(param);
return param.getReturnValue();
```

Use the local-method equivalents in `Name.invokeLocalMethod`. Do not place `fireAfter*` in `finally`: an exception from `invokeOriginal()` must propagate without an after callback.

Cover all current paths: cached static method, superclass method, instance method, normal static method, primary-suffix object method, and local BeanShell method.

- [ ] **Step 9: Repair Java-field read/write ordering**

In `LHS`, `Name`, and `BSHPrimarySuffix`, remove intercepted-read early returns before after dispatch. For successful reads, set the parameter result, fire after once, and return its final mutable result.

For field writes in `LHS.assign`, fire before, use the possibly modified `param.getValue()`, skip the actual write if intercepted, and fire after once for an intercepted or successful write. Do not give write `returnValue` new semantics; preserve the method’s existing assignment result behavior.

Cover `LHS` reads/writes, static and object reads through `Name`, and object reads through `BSHPrimarySuffix`.

- [ ] **Step 10: Repair variable read/write ordering**

In `NameSpace.getVariable`, fire after once after an intercepted result is established or an existing variable is read, then return the final `returnValue`.

In both `setVariable` and `setVariableOrProperty`, fire after once on interception or after each successful underlying variable/property write path. Use the possibly modified write value. Do not run after when an original write throws and do not create hook events for the existing absent-variable early return that currently has no hook parameter.

- [ ] **Step 11: Protect the registry from interpreted access**

In `MainSecurityGuard.BasicSecurityGuard`, import `BshHookManager` and deny:

```java
@Override
public boolean canGetStaticField(Class<?> clazz, String fieldName) {
    return !(clazz == Interpreter.class && fieldName.equals("bshHookManager"));
}

@Override
public boolean canSetStaticField(Class<?> clazz, String fieldName) {
    return !(clazz == Interpreter.class && fieldName.equals("bshHookManager"));
}

@Override
public boolean canInvokeMethod(Object thisArg, String methodName, Object[] args) {
    return !(thisArg instanceof MainSecurityGuard)
        && !(thisArg instanceof BshHookManager);
}

@Override
public boolean canSetField(Object thisArg, String fieldName, Object value) {
    return !(thisArg instanceof SecurityGuard)
        && !(thisArg instanceof BshHookManager);
}
```

Merge these checks with the existing restrictions rather than replacing them. Reflection paths must continue delegating to the same guards.

- [ ] **Step 12: Clarify exception-path documentation**

Update `BshHook` Javadoc to state that corresponding after-hooks run for intercepted operations and successful originals, but do not run when the original operation throws.

- [ ] **Step 13: Run focused tests to green**

```bash
cd libs/common/bsh
./gradlew testDebugUnitTest --tests 'bsh.BshHookContractTest' --tests 'bsh.BshHookSecurityTest'
```

Expected: all focused tests pass.

- [ ] **Step 14: Run the complete standalone BeanShell build**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 15: Commit the submodule change**

Review `git status --short` inside `libs/common/bsh`, stage only the files listed in Tasks 1–2, and commit:

```bash
git commit -m "fix: repair BeanShell hook contract"
```

Do not push.

---

### Task 2: Implement interpreter-scoped DRM hook ownership and lifecycle

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java/ScriptsDrmBypassHook.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java/BypassScriptsDrm.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java/JavaEngine.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/scripting_java/ScriptsDrmBypassHookTest.kt`

**Interfaces:**
- Consumes: repaired `BshHook`, `LocalMethodHookParam.getInterpreter()`, declared return type, `Primitive.VOID`.
- Produces: `internal class ScriptsDrmBypassHook : BshHook`, interpreter registration APIs, one stable process hook owned by `BypassScriptsDrm`, registration independent of feature state, and unregister-on-unload behavior.

- [ ] **Step 1: Write failing tests for interpreter scope**

Instantiate one hook and two real interpreters. Register only the first, install the hook globally for the duration of each test, define the same target method in both, and assert only the registered interpreter is intercepted. Use `try/finally` or `@AfterEach` to remove the test hook and unregister interpreters.

- [ ] **Step 2: Write failing tests for return-compatible replacements**

Cover these declarations through real interpreter calls or direct hook parameters where declaration syntax cannot express the case cleanly:

```java
boolean checkAuthorization()  // true
Boolean isUsingVPN()          // false
void showBlackToast()         // Primitive.VOID and body skipped
Object detectPacketCapture()  // false
List getBlackFriends()        // empty ArrayList
String checkAuthorization()   // not intercepted
String getBlackFriends()      // not intercepted
```

Also assert untyped methods retain the existing intended Boolean/empty-list replacements and repeated register/unregister calls are harmless.

- [ ] **Step 3: Run the focused parent tests and observe failure**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.scripting_java.ScriptsDrmBypassHookTest'
```

Expected: compilation fails because `ScriptsDrmBypassHook` does not yet exist, followed by behavior failures until matching is implemented.

- [ ] **Step 4: Implement concurrent interpreter identity membership**

In `ScriptsDrmBypassHook`, use a concurrent set with identity semantics. Since `Interpreter` does not override equality, a concurrent key set is sufficient:

```kotlin
private val interpreters = ConcurrentHashMap.newKeySet<Interpreter>()

fun registerInterpreter(interpreter: Interpreter) {
    interpreters += interpreter
}

fun unregisterInterpreter(interpreter: Interpreter) {
    interpreters -= interpreter
}
```

Make registration APIs `internal` unless tests require package-visible access through the existing Kotlin module.

- [ ] **Step 5: Implement exact-name and declared-return compatibility matching**

In `beforeLocalMethod`, return immediately unless `param.interpreter` is registered. Match exactly the existing method-name groups.

For false/no-op targets:

- `void` → `Primitive.VOID`;
- primitive/boxed Boolean → `false`;
- `null` or `Object::class.java` → existing intended `false` (except void is already handled);
- any other declared type → do not intercept.

For `checkAuthorization`:

- primitive/boxed Boolean → `true`;
- `null` or `Object::class.java` → `true`;
- incompatible or void → do not intercept.

For `getBlackFriends`:

```kotlin
val replacement = arrayListOf<Any>()
val compatible = returnType == null ||
    returnType == Any::class.java ||
    returnType.isAssignableFrom(ArrayList::class.java)
```

Intercept only when compatible. Set `returnValue` before `isIntercepted = true`.

- [ ] **Step 6: Move lifecycle ownership to `BypassScriptsDrm`**

Give the feature one stable hook field:

```kotlin
private val hook = ScriptsDrmBypassHook()

internal fun registerInterpreter(interpreter: Interpreter) = hook.registerInterpreter(interpreter)
internal fun unregisterInterpreter(interpreter: Interpreter) = hook.unregisterInterpreter(interpreter)

override fun onEnable() {
    Interpreter.bshHookManager.addHook(hook)
}

override fun onDisable() {
    Interpreter.bshHookManager.removeHook(hook)
}
```

Do not clear interpreter membership on feature disable; loaded plugins must become active immediately when re-enabled.

- [ ] **Step 7: Register plugin interpreters before initialization/evaluation**

In `JavaEngine.executeAllOnLoad`, remove the old per-plugin anonymous `BshHook` block completely. For every plugin, call:

```kotlin
BypassScriptsDrm.registerInterpreter(plugin.interpreter)
initPlugin(plugin)
plugin.interpreter.eval(plugin.content)
```

Registration must happen whether or not the bypass is currently enabled and before both `initPlugin` and source evaluation.

- [ ] **Step 8: Unregister plugin interpreters in unload `finally`**

Wrap each plugin’s existing optional `onUnload` invocation and exception logging so cleanup always happens:

```kotlin
try {
    // existing onUnload lookup/invocation
} catch (e: Exception) {
    // existing logging
} finally {
    BypassScriptsDrm.unregisterInterpreter(plugin.interpreter)
}
```

Repeated unload/unregister must remain harmless.

- [ ] **Step 9: Run focused DRM hook tests to green**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.scripting_java.ScriptsDrmBypassHookTest'
```

Expected: all focused tests pass.

---

### Task 3: Review and validate both repositories

**Files:**
- Review all files changed in Tasks 1–4.
- Update parent submodule pointer: `libs/common/bsh`.

**Interfaces:**
- Consumes: completed BeanShell and parent implementation.
- Produces: reviewed, build-verified change set with device-validation boundary documented.

- [ ] **Step 1: Request a correctness/security review**

Review specifically for duplicate/missed after callbacks, exception-path behavior, assignment-expression double counting, security-guard bypasses, strong-reference leaks, identity scoping, return compatibility direction, and feature lifecycle races. Fix only confirmed findings and rerun affected focused tests.

- [ ] **Step 2: Run all focused tests**

```bash
cd libs/common/bsh
./gradlew testDebugUnitTest --tests 'bsh.BshHookContractTest' --tests 'bsh.BshHookSecurityTest'
cd ../../..
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.scripting_java.ScriptsDrmBypassHookTest'
```

Expected: all tests pass.

- [ ] **Step 3: Run the complete BeanShell build from its own repository**

```bash
cd libs/common/bsh
./gradlew build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run relevant parent Gradle tests**

Run the focused app unit-test target containing the DRM helper tests. No DexKit resolver declarations changed, so `./x dex-test` is not required.

- [ ] **Step 5: Run the required full WeKit build**

From the parent repository:

```bash
./x build
```

Expected: both debug APK variants are built with the updated BeanShell submodule and refreshed native libraries.

- [ ] **Step 6: Check whitespace/errors in both repositories**

```bash
cd libs/common/bsh
git diff --check
cd ../../..
git diff --check
```

Expected: no output and exit status 0 from both commands.

- [ ] **Step 7: Review repository state and commit parent integration**

Verify the submodule commit exists, the parent shows the intended `libs/common/bsh` pointer update, and unrelated untracked files remain unstaged. Stage only:

- `app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java/BypassScriptsDrm.kt`
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java/JavaEngine.kt`
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java/ScriptsDrmBypassHook.kt`
- `app/src/test/java/dev/ujhhgtg/wekit/features/items/scripting_java/ScriptsDrmBypassHookTest.kt`
- `libs/common/bsh`

Commit:

```bash
git commit -m "fix: scope script DRM bypass hooks"
```

Do not push.

- [ ] **Step 8: Report validation boundaries**

Report focused interpreter tests, standalone BeanShell build, parent unit tests, `./x build`, and both `git diff --check` results separately. State explicitly that no physical WeChat/Android target-script behavior was tested and remains a manual validation boundary.
