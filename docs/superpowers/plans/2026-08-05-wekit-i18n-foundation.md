# WeKit i18n Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add process-local locale selection, isolated localized resources, live Compose recomposition, and the four-option language selector without yet migrating the full WeKit string catalog.

**Architecture:** Keep locale matching pure and unit-tested, then layer one process-local Android controller and one Compose provider on top. Injected-host compositions receive a configuration context whose resources are explicitly injected; module-app compositions receive an ordinary module configuration context. Every existing Compose root is routed through one of those two paths before the first language-backed settings labels are introduced.

**Tech Stack:** Kotlin, Android `Configuration`/`LocaleList`, Jetpack Compose snapshot state and CompositionLocals, MMKV through `WePrefs`, JUnit Jupiter, Android resources

## Global Constraints

- English is the default resource language and final fallback.
- Persist exactly `system`, `en`, `zh-Hans`, or `zh-Hant` under `ui_language`; absent/invalid values normalize to `system`.
- Follow system resolves the first supported locale in the system list; unsupported-only lists resolve to English.
- Do not add cross-process synchronization, broadcasts, providers, polling, or IPC.
- Do not call `Resources.updateConfiguration` or mutate WeChat's application resources/configuration.
- Do not restore a global `getString` hook, `ResourcesWrapper`, or fake resource fallback.
- Inject module resources into each newly created injected-host localized `Resources` instance.
- Provide both `LocalContext` and `LocalConfiguration`; rely on Compose `LocalResources` to re-resolve strings.
- Use `WePrefs.Companion.prefOption` only where it does not hide the required synchronous state update; the locale controller remains the single writer API for language changes.
- Do not add low-value UI tests. Pure locale matching is unit-tested; live injected behavior is verified manually in WeChat.
- Build through `./x build`, not Gradle assembly tasks.

## File Map

- Create `app/src/main/java/dev/ujhhgtg/wekit/i18n/LanguageSelection.kt`: persisted language choices and resource labels.
- Create `app/src/main/java/dev/ujhhgtg/wekit/i18n/SupportedLocale.kt`: effective logical and Android locale mapping.
- Create `app/src/main/java/dev/ujhhgtg/wekit/i18n/LocaleResolver.kt`: pure Follow system/manual resolution.
- Create `app/src/main/java/dev/ujhhgtg/wekit/i18n/WeKitLocaleController.kt`: process-local observable state and configuration callbacks.
- Create `app/src/main/java/dev/ujhhgtg/wekit/i18n/LocalizedContextFactory.kt`: isolated context/configuration creation.
- Create `app/src/main/java/dev/ujhhgtg/wekit/i18n/WeKitLocaleProvider.kt`: Compose CompositionLocal bridge.
- Create `app/src/test/java/dev/ujhhgtg/wekit/i18n/LocaleResolverTest.kt`: pure resolution contract.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/constants/Preferences.kt`: add the stable preference key.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/application/ModuleApplication.kt`: initialize locale state in the module process.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/loader/startup/WeLauncher.kt`: initialize locale state in the injected process.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/ui/utils/theme/InjectedUiTheme.kt`: install injected-host locale provider.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/ui/utils/theme/ModuleAppTheme.kt`: install module-app locale provider.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/activity/settings/SettingsActivity.kt`: localize both settings-engine branches once.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/activity/agent/WeAgentSettingsActivity.kt`: localize its independent `ModuleTheme` root.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/ui/utils/ComposeUtils.kt`: localize standalone Compose dialogs once.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/ui/panel/PanelShell.kt`: stop overwriting the context supplied by `InjectedUiTheme`.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/activity/settings/SettingsPager.kt`: MIUIX language selector and first resource-backed labels.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings/NukeSecondaryScreens.kt`: Nuke language selector and first resource-backed labels.
- Modify `app/src/main/res/values/strings.xml`: English source/fallback entries.
- Create `app/src/main/res/values-zh-rCN/strings.xml`: Simplified Chinese target entries.
- Create `app/src/main/res/values-zh-rTW/strings.xml`: Traditional Chinese target entries.
- Modify `app/src/main/res/values/arrays.xml`: mark Xposed scope non-translatable.

---

### Task 1: Pure language model and locale resolution

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/i18n/LanguageSelection.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/i18n/SupportedLocale.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/i18n/LocaleResolver.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/i18n/LocaleResolverTest.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/constants/Preferences.kt`

**Interfaces:**
- Consumes: `java.util.Locale`, `R.string.*`, `Preferences.UI_LANGUAGE`
- Produces: `LanguageSelection.fromStored(String?)`, `SupportedLocale`, and `LocaleResolver.resolve(LanguageSelection, List<Locale>)`

- [ ] **Step 1: Add the failing locale contract tests**

Create `LocaleResolverTest.kt` with the full resolution matrix:

```kotlin
package dev.ujhhgtg.wekit.i18n

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocaleResolverTest {
    @Test
    fun storedValuesRoundTripAndInvalidValuesUseSystem() {
        LanguageSelection.entries.forEach { selection ->
            assertEquals(selection, LanguageSelection.fromStored(selection.storedValue))
        }
        assertEquals(LanguageSelection.SYSTEM, LanguageSelection.fromStored(null))
        assertEquals(LanguageSelection.SYSTEM, LanguageSelection.fromStored("invalid"))
    }

    @Test
    fun manualSelectionIgnoresSystemLocales() {
        val system = listOf(Locale.forLanguageTag("zh-CN"))
        assertEquals(SupportedLocale.ENGLISH, LocaleResolver.resolve(LanguageSelection.ENGLISH, system))
        assertEquals(SupportedLocale.SIMPLIFIED_CHINESE, LocaleResolver.resolve(LanguageSelection.SIMPLIFIED_CHINESE, emptyList()))
        assertEquals(SupportedLocale.TRADITIONAL_CHINESE, LocaleResolver.resolve(LanguageSelection.TRADITIONAL_CHINESE, system))
    }

    @Test
    fun followSystemUsesTheFirstSupportedLocale() {
        assertEquals(
            SupportedLocale.TRADITIONAL_CHINESE,
            LocaleResolver.resolve(
                LanguageSelection.SYSTEM,
                listOf(Locale.JAPAN, Locale.forLanguageTag("zh-HK"), Locale.ENGLISH),
            ),
        )
        assertEquals(
            SupportedLocale.ENGLISH,
            LocaleResolver.resolve(
                LanguageSelection.SYSTEM,
                listOf(Locale.JAPAN, Locale.US),
            ),
        )
    }

    @Test
    fun chineseScriptAndRegionMappingIsExplicit() {
        val simplified = listOf("zh-Hans", "zh-CN", "zh-SG", "zh-MY", "zh")
        val traditional = listOf("zh-Hant", "zh-TW", "zh-HK", "zh-MO")

        simplified.forEach { tag ->
            assertEquals(
                SupportedLocale.SIMPLIFIED_CHINESE,
                LocaleResolver.resolve(LanguageSelection.SYSTEM, listOf(Locale.forLanguageTag(tag))),
                tag,
            )
        }
        traditional.forEach { tag ->
            assertEquals(
                SupportedLocale.TRADITIONAL_CHINESE,
                LocaleResolver.resolve(LanguageSelection.SYSTEM, listOf(Locale.forLanguageTag(tag))),
                tag,
            )
        }
    }

    @Test
    fun unsupportedOrEmptySystemListFallsBackToEnglish() {
        assertEquals(
            SupportedLocale.ENGLISH,
            LocaleResolver.resolve(LanguageSelection.SYSTEM, listOf(Locale.JAPAN)),
        )
        assertEquals(
            SupportedLocale.ENGLISH,
            LocaleResolver.resolve(LanguageSelection.SYSTEM, emptyList()),
        )
    }
}
```

- [ ] **Step 2: Run the test and confirm the model is absent**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests dev.ujhhgtg.wekit.i18n.LocaleResolverTest
```

Expected: compilation fails because `LanguageSelection`, `SupportedLocale`, and `LocaleResolver` do not exist.

- [ ] **Step 3: Add the stable preference key and domain enums**

Add to `Preferences`:

```kotlin
const val UI_LANGUAGE = "ui_language"
```

Implement `LanguageSelection`:

```kotlin
enum class LanguageSelection(
    val storedValue: String,
    @StringRes val labelRes: Int,
) {
    SYSTEM("system", R.string.language_follow_system),
    ENGLISH("en", R.string.language_english),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.language_simplified_chinese),
    TRADITIONAL_CHINESE("zh-Hant", R.string.language_traditional_chinese);

    companion object {
        fun fromStored(value: String?): LanguageSelection =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}
```

Implement `SupportedLocale`:

```kotlin
enum class SupportedLocale(
    val logicalTag: String,
    val androidTag: String,
    @StringRes val labelRes: Int,
) {
    ENGLISH("en", "en", R.string.language_english),
    SIMPLIFIED_CHINESE("zh-Hans", "zh-CN", R.string.language_simplified_chinese),
    TRADITIONAL_CHINESE("zh-Hant", "zh-TW", R.string.language_traditional_chinese),
}
```

- [ ] **Step 4: Implement the pure resolver**

Implement `LocaleResolver.resolve` without Android APIs:

```kotlin
object LocaleResolver {
    fun resolve(
        selection: LanguageSelection,
        systemLocales: List<Locale>,
    ): SupportedLocale = when (selection) {
        LanguageSelection.ENGLISH -> SupportedLocale.ENGLISH
        LanguageSelection.SIMPLIFIED_CHINESE -> SupportedLocale.SIMPLIFIED_CHINESE
        LanguageSelection.TRADITIONAL_CHINESE -> SupportedLocale.TRADITIONAL_CHINESE
        LanguageSelection.SYSTEM -> systemLocales.firstNotNullOfOrNull(::mapSystemLocale)
            ?: SupportedLocale.ENGLISH
    }

    private fun mapSystemLocale(locale: Locale): SupportedLocale? = when (locale.language) {
        "en" -> SupportedLocale.ENGLISH
        "zh" -> when {
            locale.script.equals("Hant", ignoreCase = true) -> SupportedLocale.TRADITIONAL_CHINESE
            locale.script.equals("Hans", ignoreCase = true) -> SupportedLocale.SIMPLIFIED_CHINESE
            locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO") ->
                SupportedLocale.TRADITIONAL_CHINESE
            else -> SupportedLocale.SIMPLIFIED_CHINESE
        }
        else -> null
    }
}
```

- [ ] **Step 5: Run the focused tests**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests dev.ujhhgtg.wekit.i18n.LocaleResolverTest
```

Expected: all five tests pass.

- [ ] **Step 6: Commit the pure locale model**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/i18n/LanguageSelection.kt \
  app/src/main/java/dev/ujhhgtg/wekit/i18n/SupportedLocale.kt \
  app/src/main/java/dev/ujhhgtg/wekit/i18n/LocaleResolver.kt \
  app/src/test/java/dev/ujhhgtg/wekit/i18n/LocaleResolverTest.kt \
  app/src/main/java/dev/ujhhgtg/wekit/constants/Preferences.kt
git commit -m "feat: add locale selection model"
```

---

### Task 2: Process-local controller and localized context factory

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/i18n/WeKitLocaleController.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/i18n/LocalizedContextFactory.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/i18n/WeKitLocaleProvider.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/application/ModuleApplication.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/loader/startup/WeLauncher.kt`

**Interfaces:**
- Consumes: `LanguageSelection`, `SupportedLocale`, `LocaleResolver`, `Preferences.UI_LANGUAGE`, `CommonContextWrapper`, `ResourcesInjector`
- Produces: `WeKitLocaleController.initialize(Application)`, `updateSelection(LanguageSelection)`, observable `selection`/`resolvedLocale`, `LocaleResourceMode`, `LocalizedContextFactory.create(Context, SupportedLocale, LocaleResourceMode)`, and `WeKitLocaleProvider`

- [ ] **Step 1: Implement the process-local observable controller**

Create an idempotently initialized `ComponentCallbacks` object:

```kotlin
object WeKitLocaleController : ComponentCallbacks {
    private lateinit var application: Application
    private var initialized = false
    private var systemLocales by mutableStateOf(emptyList<Locale>())

    var selection by mutableStateOf(LanguageSelection.SYSTEM)
        private set

    val resolvedLocale: SupportedLocale
        get() = LocaleResolver.resolve(selection, systemLocales)

    fun initialize(application: Application) {
        if (initialized) return
        this.application = application
        selection = LanguageSelection.fromStored(WePrefs.getString(Preferences.UI_LANGUAGE))
        systemLocales = application.resources.configuration.locales.toLocaleList()
        application.registerComponentCallbacks(this)
        initialized = true
    }

    fun updateSelection(value: LanguageSelection) {
        WePrefs.putString(Preferences.UI_LANGUAGE, value.storedValue)
        selection = value
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        systemLocales = newConfig.locales.toLocaleList()
    }

    override fun onLowMemory() = Unit
}

private fun LocaleList.toLocaleList(): List<Locale> =
    List(size()) { index -> get(index) }
```

Do not read a process name, register a receiver, or poll MMKV. `resolvedLocale` must read Compose
state (`selection` and `systemLocales`) so callers observing it recompose.

- [ ] **Step 2: Initialize the controller in both application paths**

In `ModuleApplication.onCreate`, call:

```kotlin
WeKitLocaleController.initialize(this)
```

In `WeLauncher.init`, after obtaining `appContext` and injecting its base resources, call:

```kotlin
WeKitLocaleController.initialize(HostInfo.application)
```

Keep initialization before any feature can create Compose UI. Do not add process synchronization.

- [ ] **Step 3: Implement isolated context creation**

Create:

```kotlin
enum class LocaleResourceMode {
    InjectedHost,
    ModuleApp,
}

object LocalizedContextFactory {
    fun create(
        base: Context,
        locale: SupportedLocale,
        mode: LocaleResourceMode,
    ): Context {
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locale.androidTag))
        }
        val configured = base.createConfigurationContext(configuration)
        return when (mode) {
            LocaleResourceMode.InjectedHost -> CommonContextWrapper(configured).also {
                ResourcesInjector.injectModuleRes(it.resources)
            }
            LocaleResourceMode.ModuleApp -> configured
        }
    }
}
```

Do not catch `Resources.NotFoundException` and do not mutate `base.resources.configuration`.

- [ ] **Step 4: Implement the Compose provider**

Create:

```kotlin
@Composable
fun WeKitLocaleProvider(
    mode: LocaleResourceMode,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val locale = WeKitLocaleController.resolvedLocale
    val localizedContext = remember(baseContext, parentConfiguration, locale, mode) {
        LocalizedContextFactory.create(baseContext, locale, mode)
    }
    val localizedConfiguration = remember(localizedContext, locale) {
        Configuration(localizedContext.resources.configuration)
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration,
        content = content,
    )
}
```

Do not provide `LocalResources` explicitly; the pinned Compose implementation derives it from the
two provided locals and invalidates readers through `LocalConfiguration`.

- [ ] **Step 5: Compile the new infrastructure**

Run:

```bash
./gradlew :app:compileStandardDebugKotlin
git diff --check
```

Expected: Kotlin compilation succeeds and no whitespace errors are reported. This compilation does
not prove injected runtime behavior.

- [ ] **Step 6: Commit controller and provider infrastructure**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/i18n \
  app/src/main/java/dev/ujhhgtg/wekit/application/ModuleApplication.kt \
  app/src/main/java/dev/ujhhgtg/wekit/loader/startup/WeLauncher.kt
git commit -m "feat: add observable localized contexts"
```

---

### Task 3: Cover every existing Compose root

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/utils/theme/InjectedUiTheme.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/utils/theme/ModuleAppTheme.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/settings/SettingsActivity.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/agent/WeAgentSettingsActivity.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/utils/ComposeUtils.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/panel/PanelShell.kt`

**Interfaces:**
- Consumes: `WeKitLocaleProvider(LocaleResourceMode, content)`
- Produces: locale-aware injected themes, module-app themes, settings activities, dialogs, and panel roots

- [ ] **Step 1: Make `InjectedUiTheme` own injected-host localization**

Replace the current outer `CompositionLocalProvider` with the locale provider while preserving the
existing theme calculation:

```kotlin
WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    val applyCustom = ThemeSettings.applyToWechat && ThemeSettings.customColor
    val seed = SeedResolver.injectedSeed(HostInfo.application, dark)

    val materialScheme = if (!applyCustom) {
        if (dark) darkScheme else lightScheme
    } else {
        SeedResolver.materialScheme(seed, dark)
    }

    MaterialExpressiveTheme(
        colorScheme = materialScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        content()
    }
}
```

Delete the current `CompositionLocalProvider(LocalConfiguration provides
HostInfo.application.resources.configuration)`. Remove the now-unused `HostInfo` and
`LocalConfiguration` imports. The localized configuration already preserves host dark mode and
screen metrics.

- [ ] **Step 2: Make `ModuleAppTheme` own module-app localization**

Move the current color-scheme calculation inside the provider and keep the existing theme
arguments:

```kotlin
WeKitLocaleProvider(mode = LocaleResourceMode.ModuleApp) {
    val colorScheme = if (darkTheme) darkScheme else lightScheme
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        content()
    }
}
```

This covers both `MainActivity` roots and `PipVoipActivity` without editing those Activities.

- [ ] **Step 3: Wrap settings and agent Activities outside their theme branches**

In `SettingsActivity.setContent`, keep `LocalComponentActivity` outermost and place one provider
around the MIUIX/Nuke `when`:

```kotlin
CompositionLocalProvider(LocalComponentActivity provides this) {
    WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
        when (ThemeSettings.uiEngine) {
            SettingsUiEngine.MIUIX -> ModuleTheme { SettingsRoot(onFinish = { finish() }) }
            SettingsUiEngine.NUKE -> NukeSettingsContent()
        }
    }
}
```

In `WeAgentSettingsActivity`, wrap the existing `ModuleTheme` call with the same injected-host
provider. Do not put localization inside `ModuleTheme`; its only independent roots are explicitly
covered here and in `showComposeDialog`.

- [ ] **Step 4: Wrap standalone Compose dialogs exactly once**

In `showComposeDialog`, place one injected-host provider outside the existing Module/Nuke theme
selection:

```kotlin
setContent {
    WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
        if (ThemeSettings.uiEngine == SettingsUiEngine.NUKE) {
            NukeModuleTheme(content = themedContent)
        } else {
            themedContent()
        }
    }
}
```

Keep `CommonContextWrapper` as the `ComponentDialog`/`ComposeView` construction context; the provider
creates the locale-specific wrapper and reinjects its resources.

- [ ] **Step 5: Stop `PanelShell` from replacing the localized context**

Change the current nested provider from:

```kotlin
CompositionLocalProvider(
    LocalContext provides wrapped,
    LocalPanelDialogScope provides scope,
) { InjectedUiTheme { ... } }
```

to:

```kotlin
CompositionLocalProvider(LocalPanelDialogScope provides scope) {
    InjectedUiTheme { ... }
}
```

`InjectedUiTheme` now owns `LocalContext`; restoring `wrapped` below it would defeat live locale
changes.

- [ ] **Step 6: Audit all Compose roots**

Run:

```bash
rg -n 'setContent\s*\{|ComposeView\(' app/src/main/java/dev/ujhhgtg/wekit
rg -n 'InjectedUiTheme\(|ModuleAppTheme\(|WeKitLocaleProvider\(' app/src/main/java/dev/ujhhgtg/wekit
```

Verify each current root is covered:

- `MainActivity` and `PipVoipActivity` through `ModuleAppTheme`;
- `SettingsActivity` and `WeAgentSettingsActivity` through an explicit injected provider;
- `ComposeUtils` through an explicit injected provider;
- `PanelShell`, `ConversationGrouping`, `ChatToolbar`, `WeAgentOverlayController`,
  `ReplaceNavigationBar`, `AddMainScreenFab`, and both `HomeSidePanel` roots through
  `InjectedUiTheme`.

Do not add providers to leaf composables.

- [ ] **Step 7: Compile and commit root integration**

Run:

```bash
./gradlew :app:compileStandardDebugKotlin
git diff --check
```

Then commit:

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/ui/utils/theme/InjectedUiTheme.kt \
  app/src/main/java/dev/ujhhgtg/wekit/ui/utils/theme/ModuleAppTheme.kt \
  app/src/main/java/dev/ujhhgtg/wekit/activity/settings/SettingsActivity.kt \
  app/src/main/java/dev/ujhhgtg/wekit/activity/agent/WeAgentSettingsActivity.kt \
  app/src/main/java/dev/ujhhgtg/wekit/ui/utils/ComposeUtils.kt \
  app/src/main/java/dev/ujhhgtg/wekit/ui/panel/PanelShell.kt
git commit -m "feat: provide locale to all Compose roots"
```

---

### Task 4: Bootstrap resources and add both language selectors

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values-zh-rCN/strings.xml`
- Create: `app/src/main/res/values-zh-rTW/strings.xml`
- Modify: `app/src/main/res/values/arrays.xml`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/settings/SettingsPager.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings/NukeSecondaryScreens.kt`

**Interfaces:**
- Consumes: `LanguageSelection.entries`, `WeKitLocaleController.selection`, `updateSelection`, existing `EnumDropdown`, and `NukeSelectPreference`
- Produces: English fallback catalog bootstrap and identical MIUIX/Nuke language selectors

- [ ] **Step 1: Replace the base resource file with English source entries**

Keep the XML declaration and add these exact entries to `values/strings.xml`:

```xml
<resources>
    <string name="app_name" translatable="false">WeKit</string>
    <string name="app_description">WeChat, now with superpowers\nUjhhgtg@github</string>
    <string name="res_inject_success" translatable="false">Resources injection success</string>

    <string name="settings_title">Settings</string>
    <string name="settings_section_interface">Interface</string>
    <string name="settings_general_title">General settings</string>
    <string name="settings_language_title">Language</string>
    <string name="settings_language_summary">%1$s · %2$s</string>
    <string name="language_follow_system">Follow system</string>
    <string name="language_english" translatable="false">English</string>
    <string name="language_simplified_chinese" translatable="false">简体中文</string>
    <string name="language_traditional_chinese" translatable="false">繁體中文</string>
</resources>
```

The language autonyms are deliberately non-translatable and must not appear in target files.

- [ ] **Step 2: Add the two initial Chinese target files**

Create `values-zh-rCN/strings.xml`:

```xml
<resources>
    <string name="app_description">微信增强模块\nUjhhgtg@github</string>
    <string name="settings_title">设置</string>
    <string name="settings_section_interface">界面</string>
    <string name="settings_general_title">通用设置</string>
    <string name="settings_language_title">语言</string>
    <string name="settings_language_summary">%1$s · %2$s</string>
    <string name="language_follow_system">跟随系统</string>
</resources>
```

Create `values-zh-rTW/strings.xml`:

```xml
<resources>
    <string name="app_description">微信增強模組\nUjhhgtg@github</string>
    <string name="settings_title">設定</string>
    <string name="settings_section_interface">介面</string>
    <string name="settings_general_title">一般設定</string>
    <string name="settings_language_title">語言</string>
    <string name="settings_language_summary">%1$s · %2$s</string>
    <string name="language_follow_system">跟隨系統</string>
</resources>
```

These are the bootstrap values only; the catalog migration plan replaces the remaining Kotlin UI
literals and expands all three files.

- [ ] **Step 3: Mark the Xposed scope array non-translatable**

Change:

```xml
<string-array name="xposed_scope" translatable="false">
    <item>com.tencent.mm</item>
</string-array>
```

- [ ] **Step 4: Add the MIUIX language row**

In `SettingsPager`, replace the page title and interface section title with `stringResource` calls.
Add this as the first row in `ThemeSection`:

```kotlin
val selectedLanguage = WeKitLocaleController.selection
val resolvedLanguage = WeKitLocaleController.resolvedLocale
val languageLabels = mapOf(
    LanguageSelection.SYSTEM to stringResource(R.string.language_follow_system),
    LanguageSelection.ENGLISH to stringResource(R.string.language_english),
    LanguageSelection.SIMPLIFIED_CHINESE to stringResource(R.string.language_simplified_chinese),
    LanguageSelection.TRADITIONAL_CHINESE to stringResource(R.string.language_traditional_chinese),
)
val languageSummary = if (selectedLanguage == LanguageSelection.SYSTEM) {
    stringResource(
        R.string.settings_language_summary,
        stringResource(selectedLanguage.labelRes),
        stringResource(resolvedLanguage.labelRes),
    )
} else {
    stringResource(selectedLanguage.labelRes)
}
EnumDropdown(
    title = stringResource(R.string.settings_language_title),
    entries = LanguageSelection.entries,
    selected = selectedLanguage,
    labelOf = languageLabels::getValue,
    onSelected = WeKitLocaleController::updateSelection,
    summary = languageSummary,
    icon = MaterialSymbols.Outlined.Language,
)
```

Use:

```kotlin
MiuixListScaffold(title = stringResource(R.string.settings_title))
MiuixSmallTitle(text = stringResource(R.string.settings_section_interface), ...)
```

Do not wrap `selection` in a separate remembered state; it already is observable state.

- [ ] **Step 5: Add the Nuke language row**

At the start of `NukeGeneralSettingsPage`, add a group keyed `language`:

```kotlin
item(key = "language") {
    val selectedLanguage = WeKitLocaleController.selection
    val resolvedLanguage = WeKitLocaleController.resolvedLocale
    val languageLabels = mapOf(
        LanguageSelection.SYSTEM to stringResource(R.string.language_follow_system),
        LanguageSelection.ENGLISH to stringResource(R.string.language_english),
        LanguageSelection.SIMPLIFIED_CHINESE to stringResource(R.string.language_simplified_chinese),
        LanguageSelection.TRADITIONAL_CHINESE to stringResource(R.string.language_traditional_chinese),
    )
    val languageSummary = if (selectedLanguage == LanguageSelection.SYSTEM) {
        stringResource(
            R.string.settings_language_summary,
            stringResource(selectedLanguage.labelRes),
            stringResource(resolvedLanguage.labelRes),
        )
    } else {
        stringResource(selectedLanguage.labelRes)
    }
    NukeSettingGroup(title = null) {
        NukeSelectPreference(
            title = stringResource(R.string.settings_language_title),
            description = languageSummary,
            options = LanguageSelection.entries,
            selected = selectedLanguage,
            optionLabel = languageLabels::getValue,
            onSelected = WeKitLocaleController::updateSelection,
        )
    }
}
```

Replace the Nuke page title with `stringResource(R.string.settings_general_title)`. Keep the exact
option order from `LanguageSelection.entries`.

- [ ] **Step 6: Run resource compilation and focused tests**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests dev.ujhhgtg.wekit.i18n.LocaleResolverTest
./gradlew :app:processStandardDebugResources :app:compileStandardDebugKotlin
git diff --check
```

Expected: locale tests pass, all three resource files compile, and both settings engines compile.

- [ ] **Step 7: Commit the bootstrap catalog and selectors**

```bash
git add app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml \
  app/src/main/res/values/arrays.xml \
  app/src/main/java/dev/ujhhgtg/wekit/activity/settings/SettingsPager.kt \
  app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings/NukeSecondaryScreens.kt
git commit -m "feat: add live language selector"
```

---

### Task 5: Foundation verification gate

**Files:**
- Verify only; no planned source changes

**Interfaces:**
- Consumes: all outputs from Tasks 1-4
- Produces: a build-validated foundation ready for the catalog migration plan

- [ ] **Step 1: Run all relevant unit tests**

```bash
./gradlew :app:testStandardDebugUnitTest --tests dev.ujhhgtg.wekit.i18n.LocaleResolverTest
```

Expected: all locale tests pass with zero failures.

- [ ] **Step 2: Run the project build and whitespace validation**

```bash
./x build
git diff --check
```

Expected: debug build succeeds for the normal project outputs and `git diff --check` is silent.

- [ ] **Step 3: Inspect the root and global-mutation invariants**

Run:

```bash
rg -n 'Resources\.updateConfiguration|hook.*getString|ResourcesWrapper' app/src/main/java/dev/ujhhgtg/wekit
rg -n 'setContent\s*\{|ComposeView\(' app/src/main/java/dev/ujhhgtg/wekit
rg -n 'WeKitLocaleProvider\(|InjectedUiTheme\(|ModuleAppTheme\(' app/src/main/java/dev/ujhhgtg/wekit
```

Expected:

- no newly introduced global resource mutation/hook/wrapper;
- every root is accounted for by the Task 3 audit;
- no locale broadcast, provider, service, or polling code exists.

- [ ] **Step 4: Perform real-WeChat manual verification**

Record Android version, WeChat version, settings engine, starting language, selected language, and
result for each case:

1. Fresh preference: Follow system selected.
2. System `en-US`: English bootstrap labels.
3. System `zh-CN`: Simplified Chinese bootstrap labels.
4. System `zh-TW` or `zh-HK`: Traditional Chinese bootstrap labels.
5. System `ja-JP`: English fallback.
6. Switch language with MIUIX settings visible: page/section/language row update immediately.
7. Switch language with Nuke settings visible: page/language row update immediately.
8. Open a `showComposeDialog` dialog after switching: localized context resolves the selected
   bootstrap resources.
9. Open an injected panel using `InjectedUiTheme` and confirm module resources still resolve on the
   API 30+ loader path; repeat on API 28/29 when a device is available.
10. Restart WeChat and confirm the stored selection is restored.
11. While Follow system is selected and a settings page is visible, change the system locale and
    confirm the effective language and visible Compose strings update.
12. While a manual language is selected, change the system locale and confirm the manual language
    remains active.

Do not claim device behavior from compilation alone.
