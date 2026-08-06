# Home Side Panel Wallet Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the home side-panel wallet card’s prototype balance with WeChat’s real cached balance, promote `当前余额` to the icon-bearing title, and correct the scanner button outline color.

**Architecture:** `HomeSidePanel` owns the DexKit declarations and hooks the stable wallet-cache read/write methods. `HomeSidePanelWallet.kt` owns the host bridge, a nullable fen-based balance model, and exact yuan formatting. `HomeSidePanelState` reads the bridge on preload/open and collects live write updates; `HomeSidePanelUi.kt` renders the existing card with the revised hierarchy and explicit primary scanner border.

**Tech Stack:** Kotlin, Android Jetpack Compose Material 3, Kotlin Coroutines `StateFlow`/`SharedFlow`, WeKit DexKit delegates, `reflekt`, JUnit 5.

## Global Constraints

- Support WeChat 8.0.65–8.0.76, including normal and Google Play APK variants where available.
- The host cache key is `USERINFO_NEW_BALANCE_LONG_SYNC`; the stored value is a `Long` measured in fen.
- A stored `0L` is a valid balance; only a missing cache value is unavailable.
- Do not issue wallet network requests and do not capture rendered wallet UI text.
- Dex resolver and inline matcher code must use DexKit metadata and `DexResolutionContext.host`, never host JVM reflection during desktop resolution.
- Do not use `allowFailure` for the wallet declarations; the stable cache methods are expected in every supported host.
- Keep the existing privacy masking, shortcuts, card shape, spacing, and navigation behavior.
- Do not add tests for host hooks, reflection glue, or direct UI mappings; test only the non-trivial WeKit-owned balance formatting/state semantics.
- Do not commit changes unless explicitly requested.

---

## File Map

- **Modify:** `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`
  - Make the feature implement `IResolveDex`.
  - Resolve the stable `m1.i` cache reader, `m1.j` cache writer, and `PluginWxPay` service class.
  - Configure the wallet bridge before creating sessions and forward cache-write events to it.
- **Modify:** `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelWallet.kt`
  - Replace the hard-coded string with nullable fen state.
  - Add exact currency formatting.
  - Add host service/cache access and a `SharedFlow<Long>` for authoritative updates.
- **Modify:** `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelState.kt`
  - Inject/read the wallet source during preload and panel open.
  - Collect live balance updates and copy them into the UI state.
- **Modify:** `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelUi.kt`
  - Render the wallet icon plus `当前余额` as one title row.
  - Remove the old `钱包` and secondary `当前余额` label.
  - Set the scanner outline/content color explicitly to the primary color.
- **Create:** `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelWalletTest.kt`
  - Test null/unavailable, zero, grouping, and cents formatting without host dependencies.
- **Create:** `docs/superpowers/plans/2026-08-06-home-side-panel-wallet-card.md`
  - This implementation plan.

---

### Task 1: Add the pure wallet balance model and host bridge

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelWallet.kt:1-21`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelWalletTest.kt`

**Interfaces:**
- Produces `HomeSidePanelWalletUiState(balanceFen: Long? = null, displayState: HomeSidePanelWalletDisplayState = ...)`.
- Produces `formatHomeSidePanelWalletBalance(balanceFen: Long?): String` with `null -> "¥ --"`, `0L -> "¥ 0.00"`, and fen-to-yuan conversion.
- Produces `HomeSidePanelWalletBalanceSource` with `val updates: SharedFlow<Long>`, `fun install(readBalance: () -> Long?)`, `fun clear()`, `fun read(): Long?`, and `fun onCacheWrite(key: Any?, value: Any?)`.
- The source accepts only the enum key whose runtime `Enum.name` equals `USERINFO_NEW_BALANCE_LONG_SYNC`; it emits only `Long` values.

- [ ] **Step 1: Write the failing formatter tests**

```kotlin
package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeSidePanelWalletTest {

    @Test
    fun unavailableBalanceUsesPlaceholder() {
        assertEquals("¥ --", formatHomeSidePanelWalletBalance(null))
    }

    @Test
    fun zeroBalanceRemainsAConfirmedZero() {
        assertEquals("¥ 0.00", formatHomeSidePanelWalletBalance(0L))
    }

    @Test
    fun balanceUsesFenAndGrouping() {
        assertEquals("¥ 2,480.60", formatHomeSidePanelWalletBalance(248060L))
    }

    @Test
    fun balancePreservesTwoDecimalPlaces() {
        assertEquals("¥ 12.30", formatHomeSidePanelWalletBalance(1230L))
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelWalletTest'
```

Expected: compilation or test failure because the formatter and/or updated state model does not yet exist.

- [ ] **Step 3: Replace the prototype model and add the bridge**

Keep the existing masking model and replace the file contents with the following implementation shape:

```kotlin
package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.lang.reflect.Method
import java.util.Locale

internal data class HomeSidePanelWalletDisplayState(
    val defaultMaskEnabled: Boolean,
    val isMasked: Boolean = defaultMaskEnabled,
) {
    fun toggleFromCard(): HomeSidePanelWalletDisplayState = if (defaultMaskEnabled) {
        copy(isMasked = !isMasked)
    } else {
        this
    }

    fun reset() = copy(isMasked = defaultMaskEnabled)
}

internal data class HomeSidePanelWalletUiState(
    val balanceFen: Long? = null,
    val displayState: HomeSidePanelWalletDisplayState = HomeSidePanelWalletDisplayState(true),
) {
    val displayBalance: String
        get() = if (displayState.isMasked) "******" else formatHomeSidePanelWalletBalance(balanceFen)
}

internal fun formatHomeSidePanelWalletBalance(balanceFen: Long?): String {
    if (balanceFen == null) return "¥ --"
    val formatter = DecimalFormat(",##0.00", DecimalFormatSymbols(Locale.US))
    return "¥ ${formatter.format(BigDecimal.valueOf(balanceFen, 2))}"
}

internal object HomeSidePanelWalletBalanceSource {
    const val BALANCE_KEY = "USERINFO_NEW_BALANCE_LONG_SYNC"

    private val _updates = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    private var readBalance: (() -> Long?)? = null

    val updates: SharedFlow<Long> = _updates.asSharedFlow()

    fun install(reader: () -> Long?) {
        readBalance = reader
    }

    fun clear() {
        readBalance = null
    }

    fun read(): Long? = readBalance?.invoke()

    fun onCacheWrite(key: Any?, value: Any?) {
        if ((key as? Enum<*>)?.name != BALANCE_KEY) return
        if (value !is Long) return
        _updates.tryEmit(value)
    }
}

internal fun readHomeSidePanelWalletBalance(
    walletCacheReadMethod: Method,
    walletPayPluginClass: Class<*>,
): Long? {
    val walletPayService = WeServiceApi.getServiceByClass(walletPayPluginClass.interfaces[0])
    val walletCache = walletPayService.reflekt().firstMethod {
        parameters()
        returnType = walletCacheReadMethod.declaringClass
    }.invoke(walletPayService)!!
    val balanceKey = walletCacheReadMethod.parameterTypes[0].enumConstants
        .single { (it as Enum<*>).name == HomeSidePanelWalletBalanceSource.BALANCE_KEY }
    return walletCacheReadMethod.invoke(walletCache, balanceKey, null) as? Long
}
```

Use `DecimalFormat("#,##0.00", ...)` (with the leading `#`) in the actual implementation; the exact expected output must retain grouping for any number of yuan digits. Do not cache the enum instance because the runtime enum class differs between supported host versions.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same Gradle test command. Expected: all four tests pass.

- [ ] **Step 5: Review the pure boundary**

Confirm by static review that `0L` is not converted to null, null is formatted only as `¥ --`, and the bridge does not perform a network request or inspect UI strings.

---

### Task 2: Resolve and hook the stable WeChat wallet cache methods

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt:32-46,94-201`

**Interfaces:**
- Consumes `readHomeSidePanelWalletBalance`, `HomeSidePanelWalletBalanceSource.install`, and `.onCacheWrite` from Task 1.
- Produces three registered Dex delegates: `classWalletPayPlugin`, `methodWalletCacheRead`, and `methodWalletCacheWrite`.
- `methodWalletCacheRead` resolves `com.tencent.mm.wallet_core.model.m1.i`, two parameters, `java.lang.Object` return.
- `methodWalletCacheWrite` resolves `com.tencent.mm.wallet_core.model.m1.j`, two parameters, `void` return.

- [ ] **Step 1: Add the DexKit imports and declarations**

Add:

```kotlin
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.utils.reflection.BString
```

Make the object declaration `object HomeSidePanel : SwitchFeature(), IResolveDex` and add these delegates inside the object:

```kotlin
private val classWalletPayPlugin by dexClass {
    matcher {
        usingEqStrings("MicroMsg.PluginWxPay")
    }
}

private val methodWalletCacheRead by dexMethod {
    matcher {
        declaredClass = "com.tencent.mm.wallet_core.model.m1"
        name = "i"
        paramCount = 2
        returnType = Any::class.java
    }
}

private val methodWalletCacheWrite by dexMethod {
    matcher {
        declaredClass = "com.tencent.mm.wallet_core.model.m1"
        name = "j"
        paramCount = 2
        returnType = Void.TYPE
    }
}
```

Do not use `methodWalletCacheRead.method` or a reflected `Class` inside a resolver matcher; those are runtime-only values. The method matcher’s declared class/name/parameter count/return type are the stable cross-version constraints.

- [ ] **Step 2: Add the runtime installation and write hook**

At the beginning of `onEnable`, before any `LauncherUI` session can be created, install the reader:

```kotlin
HomeSidePanelWalletBalanceSource.install {
    readHomeSidePanelWalletBalance(
        walletCacheReadMethod = methodWalletCacheRead.method,
        walletPayPluginClass = classWalletPayPlugin.clazz,
    )
}
methodWalletCacheWrite.hookAfter {
    HomeSidePanelWalletBalanceSource.onCacheWrite(args[0], args[1])
}
```

At the start of `onDisable`, clear the reader after detaching sessions:

```kotlin
HomeSidePanelWalletBalanceSource.clear()
```

Keep the hook outside `try/catch` and `runCatching`; normal hook failures must be handled by the project hook infrastructure. The cache writer receives the runtime enum and value, so the bridge filters the enum name and `Long` type.

- [ ] **Step 3: Run the desktop-safe resolver validation**

Run the resolver validation task that executes during prebuild:

```bash
./gradlew :app:validateDesktopDexResolvers
```

Expected: `BUILD SUCCESSFUL`; if the resolver fails, inspect the matcher against DexKit metadata rather than loosening it or adding `allowFailure`.

- [ ] **Step 4: Run the affected supported APK resolution pass**

Run all six supported APK inputs, preserving separate normal/Play metadata:

```bash
./x dex-test \
  --apk ~/coding/wechat_8065.apk \
  --apk ~/coding/wechat_8067.apk \
  --apk ~/coding/wechat_8069.apk \
  --apk ~/coding/wechat_8069_3020_play.apk \
  --apk ~/coding/wechat_8074.apk \
  --apk ~/coding/wechat_8076.apk \
  --output-dir dex-test-results/home-side-panel-wallet-balance
```

Expected: exit code 0 with no `UNEXPECTED_FAILURE`, `BLOCKED`, or `INCOMPLETE` classifications. Preserve the generated per-APK reports and aggregate summary.

---

### Task 3: Wire balance reads and live updates into panel state

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelState.kt:42-110,241-332`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt:293-309`

**Interfaces:**
- Consumes `HomeSidePanelWalletBalanceSource.read()` and `.updates`.
- Produces state behavior where preload and panel-open refreshes set `wallet.balanceFen`, while observed writes update the same field without changing masking state.

- [ ] **Step 1: Pass the source into `HomeSidePanelState`**

Add a constructor parameter immediately after `hitokoto`:

```kotlin
private val walletBalance: HomeSidePanelWalletBalanceSource,
```

Pass it from `HomeSidePanelSession`:

```kotlin
hitokoto = HomeSidePanelHitokoto(homeSidePanelHttpClient),
walletBalance = HomeSidePanelWalletBalanceSource,
location = HomeSidePanelLocation(cityIndex),
```

- [ ] **Step 2: Start the source collector and perform the preload read**

In `startPreload`, after the `started.compareAndSet` guard and before unrelated network preload jobs:

```kotlin
refreshWalletBalance()
scope.launch {
    walletBalance.updates.collect { balanceFen ->
        _uiState.update { state ->
            state.copy(wallet = state.wallet.copy(balanceFen = balanceFen))
        }
    }
}
```

Add:

```kotlin
private fun refreshWalletBalance() {
    val balanceFen = walletBalance.read()
    _uiState.update { state ->
        state.copy(wallet = state.wallet.copy(balanceFen = balanceFen))
    }
}
```

Do not apply `takeIf { it != 0L }`; zero is valid. Do not reset the balance when masking is reset.

- [ ] **Step 3: Refresh on panel open**

Update `onPanelOpened` to retain the existing reset and identity sync, then invoke `refreshWalletBalance()`:

```kotlin
fun onPanelOpened() {
    resetWalletDisplay()
    refreshWalletBalance()
    scheduleIdentitySync(
        waitForChange = false,
        maxAttempts = PANEL_OPEN_STATUS_SYNC_ATTEMPTS,
    )
}
```

- [ ] **Step 4: Run the focused wallet tests again**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelWalletTest'
```

Expected: all wallet model/formatting tests pass; no host classes are loaded by the test.

---

### Task 4: Apply the card hierarchy and scanner outline fix

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelUi.kt:1-40,555-616`

**Interfaces:**
- Consumes `HomeSidePanelWalletUiState.displayBalance`; no new callbacks or navigation behavior.
- Produces the existing card with one title row: wallet icon plus `当前余额`.

- [ ] **Step 1: Add the border import**

Add:

```kotlin
import androidx.compose.foundation.BorderStroke
```

- [ ] **Step 2: Replace the header and balance column**

Replace the current header row and balance column with:

```kotlin
Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    Icon(
        MaterialSymbols.Outlined.Wallet,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = contentColor,
    )
    Text(
        "当前余额",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
    )
}
Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
        text = wallet.displayBalance,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = if (wallet.displayState.isMasked) 4.sp else 0.sp,
        color = contentColor,
    )
}
```

This removes both the `钱包` text and the old secondary `当前余额` label while preserving the amount typography and masking.

- [ ] **Step 3: Set the scanner button’s explicit primary border and content color**

Update the scanner `OutlinedButton` call to:

```kotlin
OutlinedButton(
    onClick = { panelState.runShortcut(HomeSidePanelShortcut.SCAN) },
    modifier = Modifier.weight(1f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    colors = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
    ),
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
) {
```

Leave the filled `付款码` button unchanged.

- [ ] **Step 4: Perform a static UI review**

Confirm that the wallet card contains exactly one `当前余额` string, no `钱包` header string, the wallet icon is immediately before the title, and the scanner button has a primary `BorderStroke`.

---

### Task 5: Run complete verification

**Files:**
- Verify: all modified source files and generated `dex-test-results/home-side-panel-wallet-balance/` reports.

- [ ] **Step 1: Run all existing app unit tests**

```bash
./gradlew :app:testStandardDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` and all tests pass.

- [ ] **Step 2: Run the full supported DexKit pass again after all resolver edits**

```bash
./x dex-test \
  --apk ~/coding/wechat_8065.apk \
  --apk ~/coding/wechat_8067.apk \
  --apk ~/coding/wechat_8069.apk \
  --apk ~/coding/wechat_8069_3020_play.apk \
  --apk ~/coding/wechat_8074.apk \
  --apk ~/coding/wechat_8076.apk \
  --output-dir dex-test-results/home-side-panel-wallet-balance-final
```

Expected: all six reports resolve successfully and the command exits 0.

- [ ] **Step 3: Build through xtask**

```bash
./x build
```

Expected: `BUILD SUCCESSFUL`; use `./x`, not Gradle alone, so the native library is refreshed and packaged correctly.

- [ ] **Step 4: Check the diff for whitespace errors**

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 5: Review the final diff against the spec**

Confirm that the final diff contains only the wallet model/bridge, HomeSidePanel resolver and lifecycle wiring, card UI changes, focused formatter tests, and generated Dex reports. Confirm no active wallet request, rendered-text capture, unrelated refactor, or `allowFailure` was introduced.

- [ ] **Step 6: Record device verification as required follow-up**

On a supported real WeChat device, verify: the icon/title hierarchy, primary scanner outline in light/dark/custom injected themes, unavailable placeholder, real zero, current balance after panel open, update after WeChat receives a balance write, and existing mask/shortcut behavior. Do not treat desktop DexKit or Gradle tests as a substitute for this host check.
