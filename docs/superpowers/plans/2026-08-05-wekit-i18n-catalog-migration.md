# WeKit i18n Catalog and Feature Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move WeKit's feature metadata and user-visible catalog from hard-coded Chinese Kotlin text to stable Android resources while preserving every existing preference and Dex cache identity.

**Architecture:** Introduce a stable technical feature ID and resource-name metadata at the `@Feature`/KSP boundary, then make runtime storage, Dex caching, New Features generation, navigation, search, and logs consume the technical ID while UI resolves resource IDs through the locale provider from the foundation plan. Extract strings by surface and directory, keeping host anchors, logs, protocol values, and technical identifiers in code. Add a structural `./x i18n-check` validator before CI/Weblate onboarding.

**Tech Stack:** Kotlin/KSP, Android resources, Compose, MMKV, JUnit Jupiter, Rust `xtask`, `roxmltree`, GitHub Actions

## Global Constraints

- The foundation plan `docs/superpowers/plans/2026-08-05-wekit-i18n-foundation.md` must be complete and verified first.
- Every existing feature's initial technical ID equals its current `@Feature.name` byte for byte.
- New feature IDs use namespaced ASCII identifiers and are never translated.
- Preference keys, Dex cache filenames, method-hash keys, resolver descriptors, and generated identity maps use technical IDs only.
- Feature/category UI resolves `nameRes`, `descriptionRes`, and category resource IDs at composition time.
- Missing Chinese target entries are allowed and fall back to the English default resource.
- During Tasks 1-6, add reviewed Simplified Chinese and leave newly introduced Traditional keys
  missing; Task 9 performs the only OpenCC `s2t` bootstrap and never replaces an existing
  Traditional value.
- English `values/strings.xml` is complete; target-only keys, type changes, placeholder changes, and markup changes are errors.
- Do not translate logs, SQL, protocol constants, class/member names, DexKit matchers, host anchors, URLs, package names, preference keys, cache keys, or scripting identifiers.
- Do not use a repository-wide Chinese-character regex as a migration or CI gate.
- Do not use `as?`, `getOrNull`, or fake fallbacks for values whose host/runtime contracts are known.
- Do not change DexKit declarations or resolver bodies merely to make i18n tests pass.
- Validate with focused unit tests, `./x i18n-check`, `./x build`, `git diff --check`, and real-WeChat manual checks.

## File Map

- Modify `libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/core/Feature.kt` and `FeaturesScanner.kt` for the annotation/KSP contract.
- Create `libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/core/FeatureCategoryIds.kt` for stable category IDs shared by annotation call sites and generated metadata.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/features/core/BaseFeature.kt`, `SwitchFeature.kt`, and `FeaturesLoader.kt` for runtime identity/storage/UI boundaries.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/dexkit/cache/DexCacheManager.kt` for technical cache keys.
- Modify `buildSrc/src/main/kotlin/GenerateNewFeaturesTask.kt` and generated consumers for ID-backed New Features metadata.
- Create `app/src/test/java/dev/ujhhgtg/wekit/features/core/FeatureMetadataRegistryTest.kt`,
  `app/src/test/java/dev/ujhhgtg/wekit/dexkit/cache/DexCacheCompatibilityTest.kt`, and
  `app/src/test/resources/feature-identity-compatibility.tsv` for metadata/identity checks.
- Modify `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexFeatureRunner.kt` and
  `DexResolutionRegistryTest.kt` for metadata-only desktop resolution.
- Modify all files returned by `rg -l '@Feature' app/src/main/java/dev/ujhhgtg/wekit/features/items` for annotation migration; the command output is the complete source set at implementation time.
- Modify the settings renderers under `app/src/main/java/dev/ujhhgtg/wekit/activity/settings/` and
  `app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings/` for localized category/feature UI.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/ui/content/*.kt`,
  `app/src/main/java/dev/ujhhgtg/wekit/ui/content/nukex/*.kt`, and
  `app/src/main/java/dev/ujhhgtg/wekit/ui/utils/*.kt` only where literals are user-visible and owned
  by WeKit.
- Modify the feature UI files under `features/items/{batch,beautify,chat,chat_input_bar_menu,contacts,debug,entertain,home_screen_menu,miniapps,moments,notifications,official_accounts,payment,profile,scripting_java,shortvideos,system,voip}` according to the surface tasks below.
- Create `xtask/src/i18n_check.rs`; modify `xtask/src/main.rs` and `xtask/Cargo.toml` for the validator command.
- Modify `.github/workflows/ci.yml` to run i18n checks for pull requests into both `master` and `dev`.

---

### Task 1: Migrate stable feature metadata, generated registries, and every annotation

**Files:**
- Modify: `libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/core/Feature.kt`
- Create: `libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/core/FeatureCategoryIds.kt`
- Modify: `libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/FeaturesScanner.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/core/BaseFeature.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/core/SwitchFeature.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/core/FeaturesLoader.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/dexkit/cache/DexCacheManager.kt`
- Modify: `buildSrc/src/main/kotlin/GenerateNewFeaturesTask.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/settings/SettingsActivity.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/settings/FeaturesPager.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/settings/HomePager.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings/NukeScreens.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings/NukeSecondaryScreens.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/content/DexResolver.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/core/FeatureMetadataRegistryTest.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dexkit/cache/DexCacheCompatibilityTest.kt`
- Modify: `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexFeatureRunner.kt`
- Modify: `app/src/test/java/dev/ujhhgtg/wekit/dextest/DexResolutionRegistryTest.kt`
- Create: `app/src/test/resources/feature-identity-compatibility.tsv`
- Modify: every file returned by `rg -l '^\s*@Feature\(' app/src/main/java/dev/ujhhgtg/wekit/features/items`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: every active current `@Feature(name, categories, description)` annotation and app
  namespace `dev.ujhhgtg.wekit`.
- Produces: final `@Feature(id, nameRes, categoryIds, descriptionRes)`, generated
  `FeatureMetadataRegistry`, `BaseFeature.technicalId/nameRes/categoryIds/descriptionRes`, and one
  English/Simplified metadata resource mapping for every feature, with all preference,
  cache, navigation, search, New Features, and desktop-test consumers migrated to technical IDs.

- [ ] **Step 1: Freeze the complete pre-migration identity inventory**

Run before editing any annotation:

```bash
rg -l '^\s*@Feature\(' app/src/main/java/dev/ujhhgtg/wekit/features/items | sort
rg -n '^\s*@Feature\(' app/src/main/java/dev/ujhhgtg/wekit/features/items | wc -l
```

Create `feature-identity-compatibility.tsv` with one row for every active annotation, not a sample.
Use this concrete extractor while the old `name = "..."` contract is still present:

```bash
ruby -rjson -e '
Dir["app/src/main/java/dev/ujhhgtg/wekit/features/items/**/*.kt"].sort.each do |path|
  source = File.read(path)
  package_name = source[/^package\s+([^\s]+)/, 1]
  source.scan(/^\s*@Feature\s*\((.*?)\)\s*(?:(?:public|internal|private)\s+)?object\s+([A-Za-z_]\w*)/m) do |arguments, object_name|
    encoded_name = arguments[/\bname\s*=\s*"((?:\\.|[^"\\])*)"/m, 1]
    abort "missing named feature ID in #{path}" unless encoded_name
    legacy_id = JSON.parse(%Q{"#{encoded_name}"})
    puts "#{package_name}.#{object_name}\t#{legacy_id}"
  end
end
' > app/src/test/resources/feature-identity-compatibility.tsv
```

Compare the TSV row count to the active-annotation count. Inspect any mismatch before proceeding;
commented-out `//@Feature` declarations are intentionally excluded.

- [ ] **Step 2: Add metadata tests before changing the scanner**

Add a metadata-only generated entry type with this contract:

```kotlin
data class FeatureMetadataEntry(
    val className: String,
    val technicalId: String,
    val nameResEntry: String,
    val categoryIds: List<String>,
    val descriptionResEntry: String?,
)
```

Write `FeatureMetadataRegistryTest` so it asserts:

```kotlin
val entries = FeatureMetadataRegistry.ALL
assertTrue(entries.isNotEmpty())
assertEquals(entries.size, entries.map { it.className }.distinct().size)
assertEquals(entries.size, entries.map { it.technicalId }.distinct().size)
assertTrue(entries.any { it.className.endsWith("DisableTypingStatusUploading") })
assertTrue(entries.any { it.className.endsWith("AntiMomentCommentsDelete") })
```

Extend the test to read every TSV row and assert `technicalId == legacyId`. Also assert the fixture
row count equals `FeatureMetadataRegistry.ALL.size`, so no existing feature can silently escape the
compatibility check.

- [ ] **Step 3: Run the new tests and confirm the new registry is absent**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests dev.ujhhgtg.wekit.features.core.FeatureMetadataRegistryTest
```

Expected: compilation fails because the new generated registry and metadata fields do not exist.

- [ ] **Step 4: Extend the annotation with the final contract**

Replace the annotation parameters with:

```kotlin
annotation class Feature(
    val id: String,
    val nameRes: String,
    val categoryIds: Array<String>,
    val descriptionRes: String = "",
)
```

Add stable `const val` IDs in `FeatureCategoryIds` for every current category:

```kotlin
const val CHAT = "chat"
const val CONTACTS_GROUPS = "contacts_groups"
const val PAYMENT = "payment"
const val MOMENTS = "moments"
const val SYSTEM_PRIVACY = "system_privacy"
const val VOIP = "voip"
const val NOTIFICATIONS = "notifications"
const val BEAUTIFY = "beautify"
const val OFFICIAL_ACCOUNTS = "official_accounts"
const val MINIAPPS = "miniapps"
const val CHANNELS = "channels"
const val PROFILE = "profile"
const val DEBUG = "debug"
const val SCRIPTING_JAVA = "scripting_java"
const val ENTERTAIN = "entertain"
const val BATCH = "batch"
const val HOME_SCREEN_MENU = "home_screen_menu"
const val CONTACT_DETAILS = "contact_details"
const val API = "api"
```

Keep `NEW_FEATURES` out of this object because it is a generated pseudo-category, not a valid
annotation category.

- [ ] **Step 5: Generate typed resource references and metadata-only registry**

Update `FeaturesScanner` to:

1. Read the final annotation names (`id`, `nameRes`, `categoryIds`, `descriptionRes`).
2. Reject duplicate IDs, empty IDs, invalid resource-entry syntax, empty category IDs, and unknown
   category IDs.
3. Generate `FeaturesProvider.ALL_HOOK_ITEMS` with typed `R.string.<nameRes>` and
   `R.string.<descriptionRes>` assignments.
4. Generate `FeatureMetadataRegistry.ALL` with strings only; it must not initialize Feature
   objects or reference resource IDs.
5. Keep `DexResolutionTestRegistry` metadata-only and use technical IDs/category IDs.
6. Sort generated runtime objects deterministically by feature base type and technical ID. Localized
   UI sorting is handled later by settings screens.

Generate resource references with KotlinPoet using the app namespace's nested `R.string` class; do
not make the annotation-scanner module depend on the Android app module.

- [ ] **Step 6: Add the runtime identity boundary**

Change `BaseFeature` to hold:

```kotlin
var technicalId: String = ""
    internal set

@StringRes
var nameRes: Int = 0
    internal set

var categoryIds: List<String> = emptyList()
    internal set

@StringRes
var descriptionRes: Int? = null
    internal set

val technicalPath: String
    get() = categoryIds.joinToString(",") + "/" + technicalId
```

Remove `name`, `categories`, `description`, and `displayName`. Change all `BaseFeature` diagnostics
in this file to use `technicalId` or `technicalPath`. Provide explicit helpers for UI callers:

```kotlin
fun localizedName(context: Context): String = context.getString(nameRes)
fun localizedDescription(context: Context): String =
    descriptionRes?.let(context::getString).orEmpty()
```

The generated provider is the only code allowed to assign the metadata fields. Do not expose a
translated string as a preference/cache identity.

- [ ] **Step 7: Convert every active annotation to stable IDs and resource names**

For every existing feature, preserve the current name as `id` and map its categories to the stable
constants. Use resource keys in this form:

```kotlin
@Feature(
    id = "朋友圈评论防撤回",
    nameRes = "feature_anti_moment_comments_delete_name",
    categoryIds = [FeatureCategoryIds.MOMENTS],
    descriptionRes = "feature_anti_moment_comments_delete_description",
)
```

For a feature whose description is empty, use `descriptionRes = ""` and make the generated
`descriptionRes` null. New IDs added after this migration must use an ASCII namespace such as
`chat.quick_back_to_bottom`.

Keep resource keys stable and semantic; never derive an ID from translated output. Ensure the
category list order remains the existing order for feature display and technical diagnostics.

- [ ] **Step 8: Add English source metadata**

For each `nameRes` and non-empty `descriptionRes`, add one English `<string>` to default
`values/strings.xml`. Add English title resources for every `FeatureCategoryIds` value plus the New
Features pseudo-category. Write English from the current Chinese meaning, preserving warnings,
experimental status, host-version limitations, and action semantics. Add an XML comment when a
term is ambiguous or a feature performs a non-obvious side effect.

Use indexed placeholders for values interpolated by UI code. Do not translate feature IDs or
technical labels in the resource key itself.

- [ ] **Step 9: Add Simplified Chinese target metadata**

Copy the current annotation name/category/description wording into the matching
`values-zh-rCN/strings.xml` entries, including category and New Features titles. This keeps the
first target linguistically stable while moving the source of truth to resources. Review
punctuation, newline escapes, placeholders, and Android markup as XML, not as Kotlin string syntax.

- [ ] **Step 10: Leave new Traditional metadata pending for the one-time bootstrap**

Do not run OpenCC yet. Leave newly added Traditional metadata keys absent so Android falls back to
English during the staged migration. Task 9 performs one repository-wide `s2t` bootstrap after the
Simplified catalog is complete, imports only keys still missing from Traditional, and preserves the
foundation's existing reviewed entries.

- [ ] **Step 11: Move persistence, Dex cache, loader, and desktop runner identity to technical IDs**

In `SwitchFeature`, replace every preference/log identity with the final fields:

```kotlin
_isEnabled = WePrefs.getBoolOrDef(technicalId, defaultEnabled)
WeLogger.i("SwitchFeature", "enabling $technicalPath...")
WeLogger.i("SwitchFeature", "disabling $technicalPath...")
WePrefs.putBool(technicalId, newState)
```

In `DexCacheManager`, use `item.technicalId` for cache lookup and `item.technicalPath` for
diagnostics. Extract the pure filename rule so it is testable without initializing Android paths:

```kotlin
internal fun cacheFileName(technicalId: String): String =
    technicalId.replace("/", "_") + CACHE_FILE_SUFFIX

private fun getCacheFile(technicalId: String): Path = cacheDir / cacheFileName(technicalId)
```

Create `DexCacheCompatibilityTest` with:

```kotlin
@Test
fun legacyTechnicalIdsKeepTheirExistingCacheFileNames() {
    assertEquals(
        "朋友圈评论防撤回.json",
        DexCacheManager.cacheFileName("朋友圈评论防撤回"),
    )
}
```

The test preserves the current `item.name`-derived filename byte for byte. Do not change
`calculateMethodHash` or any resolver body.

Update `FeaturesLoader`, `DexResolver`, `DexFeatureRunner`, and generated
`DexResolutionTestRegistry` consumers so desktop reports and logs use technical IDs/category IDs
without assigning removed display fields or resolving Android strings.

- [ ] **Step 12: Move New Features, category navigation, search, and cards to stable IDs**

In `GenerateNewFeaturesTask`:

- parse the `id = "..."` argument from active annotations;
- generate `ADDED_AT_BY_ID: Map<String, Long>`;
- update generated KDoc from “feature name” to “technical ID”;
- preserve the current source-file-added semantics and shallow-clone behavior.

Update `SettingsActivity.NEW_FEATURE_ITEMS` and every consumer to use `technicalId`. Replace
`FEATURE_CATEGORIES: List<Pair<String, ImageVector>>` with a data class containing `id`,
`titleRes`, and `icon`; give the New Features pseudo-category its own stable ID and title resource.
Store category IDs in `SettingsNavTarget.Category` and `NukeDestination.Category`.

In `SettingsActivity`, `FeaturesPager`, `HomePager`, `NukeScreens`, and `NukeSecondaryScreens`:

- use `technicalId` for MMKV keys, lazy-list keys, remembered state, associations, and counts;
- use `categoryIds` for category membership;
- resolve `nameRes`, `descriptionRes`, and category titles with `stringResource`/current localized
  `Context` at render/search time;
- recompute locale-dependent search and sort keys when `WeKitLocaleController.resolvedLocale`
  changes;
- use `technicalPath` only for logs and diagnostics.

Run this scoped audit and classify unrelated `.name` occurrences rather than mechanically changing
them:

```bash
rg -n 'ADDED_AT_BY_NAME|feature\.(name|categories|description|displayName)|item\.(name|categories|description|displayName)' \
  app/src/main/java app/src/test/java buildSrc libs/common/annotation-scanner
```

- [ ] **Step 13: Update generated-metadata tests**

Extend `DexResolutionRegistryTest` to assert that every resolver entry has a non-empty technical ID,
stable category IDs, and a valid resource entry name while retaining the existing no-eager-Feature-
initialization assertions. Update `DexFeatureRunner` to report metadata strings directly and never
initialize Android resources merely to identify a resolver.

- [ ] **Step 14: Run the complete atomic migration gate**

Run:

```bash
./gradlew :app:processStandardDebugResources :app:kspStandardDebugKotlin
./gradlew :app:testStandardDebugUnitTest \
  --tests dev.ujhhgtg.wekit.features.core.FeatureMetadataRegistryTest \
  --tests dev.ujhhgtg.wekit.dexkit.cache.DexCacheCompatibilityTest \
  --tests dev.ujhhgtg.wekit.dextest.DexResolutionRegistryTest
./gradlew :app:generateNewFeatures
rg -n 'ADDED_AT_BY_NAME|feature\.(name|categories|description|displayName)|item\.(name|categories|description|displayName)' \
  app/src/main/java app/src/test/java buildSrc libs/common/annotation-scanner
./x build
git diff --check
```

Expected: all active annotations compile, duplicate IDs fail the build if introduced, the metadata
registry count matches the source inventory, every compatibility fixture ID equals its old name,
the generated New Features map is ID-based, and no BaseFeature storage/cache/UI consumer uses the
removed localized fields. Review any regex hit and keep only occurrences belonging to unrelated
types.

- [ ] **Step 15: Commit the complete atomic migration**

```bash
git add libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/core/Feature.kt \
  libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/core/FeatureCategoryIds.kt \
  libs/common/annotation-scanner/src/main/java/dev/ujhhgtg/wekit/features/FeaturesScanner.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/core/BaseFeature.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/core/SwitchFeature.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/core/FeaturesLoader.kt \
  app/src/main/java/dev/ujhhgtg/wekit/dexkit/cache/DexCacheManager.kt \
  buildSrc/src/main/kotlin/GenerateNewFeaturesTask.kt \
  app/src/main/java/dev/ujhhgtg/wekit/activity/settings \
  app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings \
  app/src/main/java/dev/ujhhgtg/wekit/ui/content/DexResolver.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items \
  app/src/test/java/dev/ujhhgtg/wekit/features/core/FeatureMetadataRegistryTest.kt \
  app/src/test/java/dev/ujhhgtg/wekit/dexkit/cache/DexCacheCompatibilityTest.kt \
  app/src/test/java/dev/ujhhgtg/wekit/dextest/DexFeatureRunner.kt \
  app/src/test/java/dev/ujhhgtg/wekit/dextest/DexResolutionRegistryTest.kt \
  app/src/test/resources/feature-identity-compatibility.tsv \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat: localize feature metadata"
```

The annotation contract, KSP output, runtime fields, all annotation sites, identity consumers,
settings metadata UI, tests, and metadata resources are one review boundary because no smaller
buildable intermediate state preserves both compatibility and final resource-backed metadata.

---

### Task 2: Extract shared settings and module-app catalog strings

**Files:**
- Modify: user-visible Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/activity/settings`
- Modify: user-visible Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/MainActivity.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/PipVoipActivity.kt`
- Modify: user-visible Kotlin files directly under `app/src/main/java/dev/ujhhgtg/wekit/ui/content`
- Modify: user-visible Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/ui/content/nukex`
- Modify: user-visible Kotlin files directly under `app/src/main/java/dev/ujhhgtg/wekit/ui/utils`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `stringResource`, `LocalContext`, `BaseFeature.localizedName/Description`, and the foundation selector.
- Produces: localized settings shell, tabs, dialogs, module-app labels, and accessibility descriptions.

- [ ] **Step 1: Create semantic keys for shared navigation and settings chrome**

Inventory the exact files first:

```bash
rg -l 'Text\(|title\s*=|summary\s*=|description\s*=|contentDescription\s*=|showToast|showToastSuspend' \
  app/src/main/java/dev/ujhhgtg/wekit/activity/settings \
  app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings \
  app/src/main/java/dev/ujhhgtg/wekit/ui/content \
  app/src/main/java/dev/ujhhgtg/wekit/ui/utils \
  | sort
```

Classify each hit as user-visible/accessibility or technical/host-owned. Add English source entries
for every owned user-visible value in the listed files. Use names such as
`nav_home`, `nav_features`, `nav_logs`, `nav_settings`, `settings_section_debug`,
`settings_section_compatibility`, `settings_section_configuration`, `settings_section_updates`,
`settings_section_about`, `dialog_cancel`, `dialog_confirm`, `dialog_close`, and
`module_app_about_title`. Do not use the old Chinese sentence as the key.

For each string with a dynamic value, use indexed placeholders and keep the complete sentence in
one resource. Add XML comments for warnings, update-state wording, and any string whose length is
important to a compact row.

- [ ] **Step 2: Replace Compose literals with `stringResource`**

Change `Text("...")`, `MiuixListScaffold(title = "...")`, `NukePageScaffold(title = "...")`,
button labels, content descriptions, and navigation labels to `stringResource(R.string.*)` calls.
For strings assembled from runtime values, replace concatenation with a formatted resource:

```kotlin
stringResource(
    R.string.settings_update_available_message,
    BuildConfig.VERSION_NAME,
    info.info.versionName,
)
```

Do not move `WeLogger` text, GitHub URLs, host-version matching literals, or technical protocol
values into resources.

- [ ] **Step 3: Add reviewed Simplified Chinese targets**

Add reviewed Simplified Chinese values for every key added in this task. Leave newly added
Traditional values missing until Task 9's one-time bootstrap; fallback is expected and validated.

- [ ] **Step 4: Build and inspect the settings surfaces**

```bash
./gradlew :app:testStandardDebugUnitTest
./x build
git diff --check
```

Then manually switch all four language options in both settings engines and verify that navigation,
dialogs, and module-app labels update without recreating the Activity.

- [ ] **Step 5: Commit shared catalog extraction**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/activity/settings \
  app/src/main/java/dev/ujhhgtg/wekit/activity/testsettings \
  app/src/main/java/dev/ujhhgtg/wekit/activity/MainActivity.kt \
  app/src/main/java/dev/ujhhgtg/wekit/activity/PipVoipActivity.kt \
  app/src/main/java/dev/ujhhgtg/wekit/ui/content \
  app/src/main/java/dev/ujhhgtg/wekit/ui/utils \
  app/src/main/res
git commit -m "feat: localize shared settings UI"
```

---

### Task 3: Extract agent, panel, and injected control strings

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/agent/WeAgentSettingsActivity.kt`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/system/agent`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/AddMainScreenFab.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/ReplaceNavigationBar.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ChatToolbar.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ConversationGrouping.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/panel/*.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `InjectedUiTheme`, `ModuleTheme`, `WeKitLocaleProvider`, and localized context access rules.
- Produces: localized injected menus, panels, overlay controls, accessibility labels, and agent settings.

- [ ] **Step 1: Classify every literal in the exact surface set**

For each literal, classify it as one of `USER_VISIBLE`, `ACCESSIBILITY`, `LOG_ONLY`, `HOST_ANCHOR`,
`PROTOCOL`, or `TECHNICAL_ID`. Only the first two become resources. Record the classification in the
review notes for the commit; do not use a repository-wide Chinese regex.

- [ ] **Step 2: Extract Compose-owned strings**

Replace user-visible labels and descriptions with `stringResource`. Use the current Compose
`LocalContext` for code that constructs a callback label outside the immediate `Text` call. Key any
remembered derived labels by `WeKitLocaleController.resolvedLocale`.

- [ ] **Step 3: Keep host matching and diagnostics untouched**

Leave stable WeChat strings used by DexKit, `WeLogger` messages, SQL/protobuf values, and overlay
technical names in Kotlin. Add a comment only when a nearby user-visible literal could be mistaken
for a host anchor.

- [ ] **Step 4: Add reviewed Simplified targets and run build**

Add reviewed Simplified Chinese for the extracted strings. Leave new Traditional keys missing until
Task 9 rather than repeatedly converting and overwriting the target file.

```bash
./gradlew :app:testStandardDebugUnitTest
./x i18n-check
./x build
git diff --check
```

- [ ] **Step 5: Commit injected/agent surfaces**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/activity/agent \
  app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/system/agent \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/AddMainScreenFab.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/ReplaceNavigationBar.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ChatToolbar.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ConversationGrouping.kt \
  app/src/main/java/dev/ujhhgtg/wekit/ui/panel \
  app/src/main/res
git commit -m "feat: localize injected controls and panels"
```

---

### Task 4: Extract chat and chat-input-bar-menu strings

**Files:**
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat_input_bar_menu`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core`
  only where a user-visible string is returned to a WeKit-owned UI
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: technical identity fields and localized context from previous tasks.
- Produces: localized chat panels, context menus, message details, batch actions, sticker/voice
  controls, and chat input actions.

- [ ] **Step 1: Extract feature-owned UI labels and descriptions**

Migrate every `@Composable` label, dialog title/message/button, menu item, tooltip, content
description, and user-facing toast owned by WeKit. Use semantic resource keys grouped by feature
technical ID, for example `feature_chat_remove_quote_title`.

- [ ] **Step 2: Preserve host strings and wire values**

Leave strings used to identify WeChat classes/views, SQL/protobuf fields, message types, scripts,
and logs untouched. A message copied from WeChat is not a WeKit-owned translation candidate.

- [ ] **Step 3: Validate placeholders and pluralization**

Convert counts and file/message quantities to `<plurals>` where the complete sentence is user-facing.
Use `%1$d`/`%1$s` consistently across English and both target files. Do not assume the Chinese files
need the same quantity names as English.

- [ ] **Step 4: Run focused verification and commit**

```bash
./gradlew :app:testStandardDebugUnitTest
./x i18n-check
./x build
git diff --check
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat_input_bar_menu \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/core \
  app/src/main/res
git commit -m "feat: localize chat surfaces"
```

---

### Task 5: Extract remaining feature-domain strings

**Files:**
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/batch`
- Modify: remaining user-visible Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify` not completed in Task 3
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/debug`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/entertain`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/home_screen_menu`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/miniapps`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/moments`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/notifications`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/official_accounts`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/payment`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/profile`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/shortvideos`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/system`
- Modify: all Kotlin files under `app/src/main/java/dev/ujhhgtg/wekit/features/items/voip`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/AutomationSettings.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: stable feature metadata and the classification rules from Tasks 1-4.
- Produces: complete English default catalog plus translated target coverage for all remaining WeKit-owned UI.

- [ ] **Step 1: Migrate domains in the listed order**

Process domains in this order so each commit has a coherent review surface:

1. `batch`, `contacts`, and `home_screen_menu`;
2. remaining `beautify`, plus `moments`, `official_accounts`, and `shortvideos`;
3. `miniapps`, `payment`, `profile`, `voip`, and shared `AutomationSettings.kt`;
4. `debug`, `entertain`, `notifications`, `scripting_java`, and `system` except the agent files
   already completed in Task 3.

For each domain, update annotations already using the final metadata, then migrate its UI literals.
Do not leave a feature with a localized annotation name or a resource-backed description mixed with
hard-coded category text.

- [ ] **Step 2: Translate target values and preserve technical text**

Use reviewed Simplified Chinese wording from the current UI. Leave new Traditional keys missing for
Task 9's one-time OpenCC bootstrap. Keep API/class names, host-version strings, URLs, regexes, SQL,
protocol data, and logs out of the catalog.

- [ ] **Step 3: Verify each domain before continuing**

After each domain group:

```bash
./x i18n-check
./gradlew :app:testStandardDebugUnitTest
./x build
git diff --check
```

Inspect the generated resource diff to ensure no target-only keys or accidental translation of
technical values entered the files.

- [ ] **Step 4: Commit each domain group separately**

Use these commit messages:

```text
feat: localize batch contacts and home menu
feat: localize beautify moments and short-video settings
feat: localize miniapp payment profile and voip settings
feat: localize debug and system settings
```

Use these exact staging boundaries:

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/batch \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/home_screen_menu \
  app/src/main/res
git commit -m "feat: localize batch contacts and home menu"

git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/moments \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/official_accounts \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/shortvideos \
  app/src/main/res
git commit -m "feat: localize beautify moments and short-video settings"

git add app/src/main/java/dev/ujhhgtg/wekit/features/items/miniapps \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/payment \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/profile \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/voip \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/AutomationSettings.kt \
  app/src/main/res
git commit -m "feat: localize miniapp payment profile and voip settings"

git add app/src/main/java/dev/ujhhgtg/wekit/features/items/debug \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/entertain \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/notifications \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/scripting_java \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/system \
  app/src/main/res
git commit -m "feat: localize debug and system settings"
```

Before each commit, inspect `git diff --cached --name-only` and unstage unrelated paths.

---

### Task 6: Convert non-Compose user-visible access to localized contexts

**Files:**
- Modify: files identified by `rg -l 'showToast|showToastSuspend|TextView|MenuItem|contentDescription|setTitle' app/src/main/java/dev/ujhhgtg/wekit`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/utils/ComposeUtils.kt` where non-Compose dialog setup resolves text
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `LocalizedContextFactory`, `LocaleResourceMode.InjectedHost`, `Context.getString`.
- Produces: current-language toasts, Android view labels, menu titles, and non-Compose dialog text.

- [ ] **Step 1: Inventory only WeKit-owned user-visible calls**

Run:

```bash
rg -l 'showToast|showToastSuspend|setText\(|text\s*=|contentDescription\s*=|setTitle\(|title\s*=' \
  app/src/main/java/dev/ujhhgtg/wekit \
  | sort > /tmp/wekit-i18n-noncompose-files
while IFS= read -r path; do
  rg -n 'showToast|showToastSuspend|setText\(|text\s*=|contentDescription\s*=|setTitle\(|title\s*=' "$path"
done < /tmp/wekit-i18n-noncompose-files
```

Classify each result. Exclude logs, host UI text passed through unchanged, SQL/protocol values, and
technical identifiers. Remove from `/tmp/wekit-i18n-noncompose-files` every file that needs no
`USER_VISIBLE` or `ACCESSIBILITY` edit; the remaining manifest is the exact staging scope.

- [ ] **Step 2: Resolve at use time from the current context**

For a callback with a `Context`, use:

```kotlin
context.getString(R.string.some_key, argument)
```

For code that only has a host `View`/`Activity`, use its current context as the base:

```kotlin
LocalizedContextFactory.create(
    base = view.context,
    locale = WeKitLocaleController.resolvedLocale,
    mode = LocaleResourceMode.InjectedHost,
).getString(R.string.some_key)
```

Do not cache the returned `String` in a singleton, feature identity field, preference, or long-lived
state object. Do not localize `WeLogger` output.

- [ ] **Step 3: Verify non-Compose fallback and persistence**

```bash
./x i18n-check
./x build
git diff --check
```

On device, switch language, trigger one toast/menu/dialog from each changed subsystem, then restart
WeChat and repeat with the persisted selection.

- [ ] **Step 4: Commit non-Compose access changes**

```bash
while IFS= read -r path; do
  git add -- "$path"
done < /tmp/wekit-i18n-noncompose-files
git add app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml
git diff --cached --name-only
git commit -m "feat: localize non-compose messages"
```

Confirm the cached file list equals the reviewed manifest plus the three resource files before
committing; unstage any unrelated path rather than widening the task.

---

### Task 7: Implement `./x i18n-check`

**Files:**
- Create: `xtask/src/i18n_check.rs`
- Modify: `xtask/src/main.rs`
- Modify: `xtask/Cargo.toml`

**Interfaces:**
- Consumes: repository root and the three Android string files.
- Produces: zero exit code for valid catalogs and a precise error list for invalid catalogs.

- [ ] **Step 1: Add failing Rust unit tests**

Add `roxmltree = "0.20"` and `regex = "1"` to `xtask/Cargo.toml`. Create
`xtask/src/i18n_check.rs`, add `mod i18n_check;` beside `mod dex_test;`, and add these tests against
the pure `parse_catalog` and `validate_pair` functions that Step 3 will implement:

```rust
#[cfg(test)]
mod tests {
    use super::{parse_catalog, validate_pair};

    const SOURCE: &str = r#"<resources>
        <string name="hello">Hello</string>
        <string name="bye">Bye</string>
    </resources>"#;

    #[test]
    fn accepts_missing_target_keys_and_rejects_target_only_keys() {
        let missing = r#"<resources><string name="hello">你好</string></resources>"#;
        validate_pair(SOURCE, missing, "zh-rCN").unwrap();

        let target_only = r#"<resources>
            <string name="hello">你好</string>
            <string name="extra">额外</string>
        </resources>"#;
        let error = validate_pair(SOURCE, target_only, "zh-rCN").unwrap_err().to_string();
        assert!(error.contains("target-only resource: extra"), "{error}");
    }

    #[test]
    fn rejects_placeholder_index_or_type_changes() {
        let source = r#"<resources><string name="welcome">Hello %1$s</string></resources>"#;
        let target = r#"<resources><string name="welcome">你好 %2$d</string></resources>"#;
        let error = validate_pair(source, target, "zh-rCN").unwrap_err().to_string();
        assert!(error.contains("placeholder mismatch: welcome"), "{error}");
    }

    #[test]
    fn accepts_different_legal_plural_quantity_sets() {
        let source = r#"<resources><plurals name="message_count">
            <item quantity="one">%1$d message</item>
            <item quantity="other">%1$d messages</item>
        </plurals></resources>"#;
        let target = r#"<resources><plurals name="message_count">
            <item quantity="other">%1$d 条消息</item>
        </plurals></resources>"#;
        validate_pair(source, target, "zh-rCN").unwrap();
    }

    #[test]
    fn rejects_resource_kind_changes_and_duplicate_keys() {
        let source = r#"<resources><string-array name="modes">
            <item>One</item>
        </string-array></resources>"#;
        let changed_kind = r#"<resources><string name="modes">一</string></resources>"#;
        let error = validate_pair(source, changed_kind, "zh-rCN").unwrap_err().to_string();
        assert!(error.contains("resource kind mismatch: modes"), "{error}");

        let duplicate = r#"<resources>
            <string name="same">One</string>
            <string name="same">Two</string>
        </resources>"#;
        let error = parse_catalog(duplicate).unwrap_err().to_string();
        assert!(error.contains("duplicate resource: same"), "{error}");
    }

    #[test]
    fn compares_markup_tag_structure_and_ignores_translation_text() {
        let source = r#"<resources><string name="styled"><b>Hello</b> <i>%1$s</i></string></resources>"#;
        let compatible = r#"<resources><string name="styled"><b>你好</b> <i>%1$s</i></string></resources>"#;
        validate_pair(source, compatible, "zh-rTW").unwrap();

        let reordered = r#"<resources><string name="styled"><i>%1$s</i> <b>你好</b></string></resources>"#;
        let error = validate_pair(source, reordered, "zh-rTW").unwrap_err().to_string();
        assert!(error.contains("markup mismatch: styled"), "{error}");
    }
}
```

The tests must call pure parser/comparison functions and use inline XML strings; no Android or
network runtime is involved.

- [ ] **Step 2: Run the tests and confirm the module is absent**

```bash
cargo test -p xtask i18n_check
```

Expected: compilation fails because `parse_catalog` and `validate_pair` do not exist yet.

- [ ] **Step 3: Implement resource parsing and comparison**

Define these Rust types:

```rust
#[derive(Clone, Copy, Eq, PartialEq)]
enum ResourceKind { String, Plurals, StringArray }

struct ResourceEntry {
    name: String,
    kind: ResourceKind,
    translatable: bool,
    placeholders: BTreeMap<String, String>,
    markup_signature: Vec<String>,
    plural_quantities: BTreeSet<String>,
}

struct Catalog {
    entries: BTreeMap<String, ResourceEntry>,
}

fn parse_catalog(xml: &str) -> Result<Catalog>;
fn validate_pair(source_xml: &str, target_xml: &str, locale: &str) -> Result<()>;
pub fn check_repository(root: &Path) -> Result<()>;
```

Parse exactly:

- `app/src/main/res/values/strings.xml` as the English source;
- `app/src/main/res/values-zh-rCN/strings.xml` as Simplified target;
- `app/src/main/res/values-zh-rTW/strings.xml` as Traditional target.

Fail on malformed XML, duplicate source/target names, target-only names, kind changes, source
entries without an English default, placeholder index/type changes, markup-signature changes,
illegal plural quantities, non-translatable source entries appearing as translated targets, and
unexpected `values-zh-*` directories. Allow missing target keys and target ordering differences.

Extract placeholders as indexed format tokens (`%1$s`, `%2$d`, `%1$f`) while treating `%%` as a
literal percent. Compare markup as an ordered tag-name/attribute structure, not translated text.
For plural entries, validate each target quantity against the Android/CLDR legal set and compare
placeholders for quantities present in both source and target; do not require equal quantity sets.

- [ ] **Step 4: Add the CLI subcommand**

Add to `Cmd`:

```rust
/// Validate the Android English and Chinese resource catalogs.
I18nCheck,
```

Dispatch it with:

```rust
Cmd::I18nCheck => i18n_check::check_repository(&workspace_root())?,
```

Update the `xtask` module banner/help text to list `i18n-check`. The user-facing command is exactly
`./x i18n-check` and takes no required flags.

- [ ] **Step 5: Run Rust tests and the real catalog check**

```bash
cargo test -p xtask i18n_check
./x i18n-check
```

Expected: all parser tests pass and the repository's current three files pass once the catalog has
been migrated to the validator's required structure.

- [ ] **Step 6: Commit the validator**

```bash
git add xtask/src/i18n_check.rs xtask/src/main.rs xtask/Cargo.toml
git commit -m "feat: validate i18n resource catalogs"
```

---

### Task 8: Enforce i18n validation for pull requests into `dev`

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `./x i18n-check`, the existing GitHub Actions Rust setup, and normal Android release
  build job.
- Produces: required catalog validation and normal build coverage for translation PRs targeting
  `dev`.

- [ ] **Step 1: Expand pull-request branch coverage**

Change the workflow trigger to:

```yaml
pull_request:
  branches:
    - master
    - dev
  paths-ignore:
    - '**/*.md'
    - '**/*.txt'
    - '.editorconfig'
    - '.gitignore'
    - '.idea/**'
    - 'docs/**'
```

Do not add an ignore rule for `app/src/main/res/**/*.xml`; translation-only PRs must run CI.

- [ ] **Step 2: Add the focused validation step**

After the existing Rust toolchain/cache setup and before native/Android compilation, add:

```yaml
- name: Validate i18n catalogs
  run: |
    cargo test -p xtask i18n_check
    ./x i18n-check
```

Keep the existing unsigned release build for pull requests. The focused validator supplements that
build; it does not replace it.

- [ ] **Step 3: Validate the workflow diff and commit**

```bash
./x i18n-check
git diff --check
git diff -- .github/workflows/ci.yml
git add .github/workflows/ci.yml
git commit -m "ci: validate translations on dev pull requests"
```

Push the branch and confirm a pull request targeting `dev` starts both the i18n validation step and
the existing Android build before proceeding to Hosted Weblate onboarding.

---

### Task 9: Final catalog gate and supported-host verification

**Files:**
- Verify all catalog, metadata, and identity files from Tasks 1-8
- Modify: `app/src/main/res/values-zh-rTW/strings.xml` for the single OpenCC bootstrap
- Modify other files only when a verification failure identifies a concrete scoped defect

**Interfaces:**
- Consumes: complete English/default catalog, both target catalogs, technical feature IDs, validator, and locale provider.
- Produces: a buildable catalog migration ready for CI/Weblate onboarding.

- [ ] **Step 1: Perform the one-time Traditional Chinese bootstrap**

Convert the now-complete reviewed Simplified catalog to a temporary file:

```bash
opencc -c s2t.json \
  -i app/src/main/res/values-zh-rCN/strings.xml \
  -o /tmp/wekit-zh-rTW-opencc.xml
```

Generate a patch source containing only resource entries that are still absent from the committed
Traditional target:

```bash
ruby -rset -e '
pattern = /<(string|plurals|string-array)\b[^>]*\bname="([^"]+)"[^>]*(?:\/>|>.*?<\/\1>)/m
target = File.read("app/src/main/res/values-zh-rTW/strings.xml")
existing = target.scan(pattern).map { |_, name| name }.to_set
converted = File.read("/tmp/wekit-zh-rTW-opencc.xml")
converted.scan(pattern) do |_, name|
  puts Regexp.last_match[0] unless existing.include?(name)
end
' > /tmp/wekit-zh-rTW-missing.xml
```

Review `/tmp/wekit-zh-rTW-missing.xml`, then use `apply_patch` to append those entries immediately
before `</resources>` in `values-zh-rTW/strings.xml`. Do not replace any existing Traditional entry.
Record that every appended entry is OpenCC-generated and must enter Hosted Weblate as unapproved.
Run `./x i18n-check` immediately after applying the patch.

- [ ] **Step 2: Run focused metadata/catalog tests**

```bash
./gradlew :app:testStandardDebugUnitTest --tests dev.ujhhgtg.wekit.features.core.FeatureMetadataRegistryTest --tests dev.ujhhgtg.wekit.dextest.DexResolutionRegistryTest
cargo test -p xtask i18n_check
./x i18n-check
```

- [ ] **Step 3: Run the full debug build and diff check**

```bash
./x build
git diff --check
```

- [ ] **Step 4: Confirm Dex cache and preference compatibility in source**

```bash
rg -n 'WePrefs\.(getBoolOrDef|putBool)\(|getCacheFile\(|ADDED_AT_BY_NAME|\.name\b|\.categories\b|\.description\b' app/src/main/java buildSrc libs/common/annotation-scanner
```

Expected: technical storage paths use `technicalId`; remaining `.name/.categories/.description`
uses are resource-backed UI accessors or unrelated types, and `ADDED_AT_BY_NAME` is absent.

- [ ] **Step 5: Run desktop DexKit only if resolver declarations changed**

Do not rerun `./x dex-test` for resource-only extraction. If the metadata migration touched a
resolver declaration or resolver body, run the affected supported WeChat 8.0.65–8.0.76 APK matrix
according to the repository's DexKit rules before claiming compatibility.

- [ ] **Step 6: Commit the one-time Traditional bootstrap**

```bash
git add app/src/main/res/values-zh-rTW/strings.xml
git diff --cached --check
git diff --cached -- app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat: bootstrap traditional chinese catalog"
```

If an earlier verification step required a source fix, commit that fix separately with only its
scoped files; do not fold unrelated corrections into the machine-bootstrap commit.

- [ ] **Step 7: Complete device verification**

On a real WeChat host, test each settings engine and representative injected surfaces for:

- system English, Simplified Chinese, Traditional Chinese, and unsupported-only fallback;
- manual override under each system locale;
- live switching while a settings screen, dialog, panel, overlay, and feature UI are visible;
- newly opened non-Compose toast/menu/dialog after switching;
- persisted selection after WeChat restart;
- existing enabled-feature preferences and existing Dex cache files after metadata migration.

Record failures with the exact Android/WeChat version and surface. A desktop build or unit test does
not prove these host behaviors.

### Task 10: Preserve the injected locale inside Miuix window compositions

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/ui/content/WeKitWindowDialog.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/activity/settings/SettingsPager.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings/PromptsScreen.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings/McpServersScreen.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings/ModelProviderDetailScreen.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings/ModelProvidersScreen.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings/TriggersScreen.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings/WorkspacesScreen.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings/SkillsScreen.kt`

**Interfaces:**
- Consumes: `WeKitLocaleProvider(LocaleResourceMode.InjectedHost)` and Miuix `WindowDialog`.
- Produces: `@Composable fun WeKitWindowDialog(show: Boolean, title: String, onDismissRequest: () -> Unit, content: @Composable () -> Unit)`.

- [ ] **Step 1: Record the device RED case**

With Android system English, select WeKit Traditional Chinese, open Settings -> Clear configuration,
then observe the dialog after a live switch. Expected RED on `git+63eb16b6`: title, message, and
confirm action are Traditional Chinese, while the dialog-internal default dismiss label is `Cancel`.
Record the WeChat PID and do not confirm the destructive action.

- [ ] **Step 2: Add the shared dialog locale boundary**

Create `WeKitWindowDialog.kt` with this API and behavior:

```kotlin
@Composable
fun WeKitWindowDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    WindowDialog(show = show, title = title, onDismissRequest = onDismissRequest) {
        WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost, content = content)
    }
}
```

- [ ] **Step 3: Replace every raw Miuix window call**

Replace all 12 repository `WindowDialog` call sites and imports with `WeKitWindowDialog`. Do not
change dialog state, callbacks, copy, layout, or business behavior.

- [ ] **Step 4: Verify structural coverage and compile**

```bash
rg -n 'import top\.yukonga\.miuix\.kmp\.window\.WindowDialog|WindowDialog\('
  app/src/main/java/dev/ujhhgtg/wekit
./gradlew :app:compileStandardDebugKotlin
```

Expected: the only raw Miuix import/call is in `WeKitWindowDialog.kt`; Kotlin compilation succeeds.
No JVM UI test is added because the failure depends on Android window composition and injected-host
resource state, which the repository testing rules reserve for real-host verification.

- [ ] **Step 5: Run full verification and install the exact build**

```bash
./gradlew :app:testStandardDebugUnitTest --tests dev.ujhhgtg.wekit.i18n.LocaleResolverTest
./x i18n-check
./x build
git diff --check
adb -s 192.168.1.9:5555 install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

- [ ] **Step 6: Verify device GREEN and dialog families**

Enter the hosted settings through the module home shortcut. Under Android system English and WeKit
Traditional Chinese, open the Clear configuration dialog without confirming it. Expected GREEN:
title, message, dismiss label, and confirm label are all Traditional Chinese. Repeat one WeAgent
window dialog containing an internal `stringResource`, then switch WeKit language while it is open
and confirm dialog content refreshes without restarting WeChat.

- [ ] **Step 7: Commit the scoped fix**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/ui/content/WeKitWindowDialog.kt \
  app/src/main/java/dev/ujhhgtg/wekit/activity/settings/SettingsPager.kt \
  app/src/main/java/dev/ujhhgtg/wekit/ui/agent/settings
git diff --cached --check
git commit -m "fix: localize window dialog compositions"
```
