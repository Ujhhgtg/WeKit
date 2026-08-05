# WeKit Internationalization and Hosted Weblate Design

## Status

- Date: 2026-08-05
- State: draft for maintainer review
- Target branch: `dev`
- Translation platform: Hosted Weblate Libre
- Implementation status: not started

This document defines the first internationalization architecture for WeKit. It records the design
approved in discussion, but implementation must not begin until the maintainer approves this
written specification.

## Context

Most WeKit UI text is currently embedded as Chinese Kotlin literals. The Android resource table has
only three strings, while feature names, descriptions, categories, settings, dialogs, injected
controls, toasts, and errors are mostly hard-coded.

WeKit is unusual because most useful UI runs inside the WeChat process. `SettingsActivity` is also
started inside that process through `ActivityProxy`; it is not a separate module-app settings
process. Module resources are made available to host `Resources` instances by
`ResourcesInjector`. The existing architecture deliberately uses explicit resource injection and
real Android lookups. It must not regress to a global `Resources.getString` hook, a fake
`ResourcesWrapper`, or host-wide configuration mutation.

The desired result is an Android-resource-based i18n system that updates existing Compose UI live,
uses English as a reliable fallback, and lets outside contributors translate the two Chinese
variants through Hosted Weblate Libre.

## Goals

- Provide these language choices:
  - Follow system, selected by default.
  - English.
  - Simplified Chinese.
  - Traditional Chinese.
- Use English as the complete source language and final fallback.
- Refresh already-composed WeKit UI immediately when the user changes language, without restarting
  WeChat or recreating `SettingsActivity`.
- Keep the effective locale as observable process-local state in the WeChat process.
- Use normal Android `string`, `plurals`, and resource fallback behavior.
- Keep `stringResource(resId)` working in Compose and localized `Context.getString(resId)` working
  outside Compose after module resources have been injected.
- Preserve existing feature preferences and Dex cache files while separating localized UI metadata
  from stable technical identity.
- Make translation contribution approachable through Hosted Weblate Libre, GitHub review, glossary,
  and translator-facing context.
- Independently validate translation XML, placeholders, markup, and source/target consistency in CI.

## Non-goals

- Do not translate WeChat itself or mutate WeChat's application-wide locale.
- Do not use Android's per-app locale APIs. WeKit is injected into WeChat and must not advertise or
  change the host application's locale.
- Do not add cross-process synchronization. The useful settings and injected Compose UI run in the
  same WeChat process. A future UI in another process may initialize from persisted settings when
  that process starts, but this design adds no locale IPC, broadcast, provider, or polling channel.
- Do not introduce runtime JSON catalogs, downloaded language packs, or a custom formatting engine.
- Do not reintroduce a global `getString` hook, `ResourcesWrapper`, or fake value on lookup failure.
- Do not run Weblate and Crowdin in parallel.
- Do not translate logs, SQL, protocol constants, class/member names, DexKit matching strings,
  host-resource anchors, preference keys, cache keys, file formats, or scripting API identifiers.
- Do not require complete Chinese translations for every development commit. Missing target entries
  intentionally fall back to English.
- Do not promise RTL layout support in the first three-language release. All three selected language
  variants are left-to-right, though resource APIs must not prevent future RTL support.

## Confirmed Decisions

1. English is the sole source language, is complete in `values/strings.xml`, and is the final
   fallback for unsupported locales and missing Chinese translations.
2. The logical supported locales are `en`, `zh-Hans`, and `zh-Hant`.
3. Android target resources use `values-zh-rCN` and `values-zh-rTW` to provide predictable Android
   and Weblate directory names. Logical `zh-Hans` resolves through an Android `zh-CN` locale;
   logical `zh-Hant` resolves through `zh-TW`.
4. Follow system is the default persisted selection.
5. Locale selection and effective locale are observable state local to the current process.
6. Existing Compose trees refresh live only when every WeKit Compose root observes that state and
   provides a rebuilt localized context/configuration to its subtree.
7. Localized resources are created per WeKit composition. WeChat's global `Resources` configuration
   is not updated.
8. Hosted Weblate Libre is the single translation platform. Git remains the final source of truth.
9. English source strings are developer-owned. Weblate contributors work on Chinese targets.
10. Weblate pushes a service branch and opens or updates a pull request into `dev`; maintainers
    review and merge it. Translation changes are never auto-merged.

## Locale Model

### Persisted selection

Define a stable preference model with serialized values that are independent of display text:

| Selection | Stored value | Meaning |
| --- | --- | --- |
| Follow system | `system` | Resolve against the current system locale list. |
| English | `en` | Always use English. |
| Simplified Chinese | `zh-Hans` | Always use Simplified Chinese. |
| Traditional Chinese | `zh-Hant` | Always use Traditional Chinese. |

The preference key is the stable technical constant `ui_language`. An absent value selects
`system`. An unknown or malformed stored value also normalizes to `system`; it must not crash the
settings screen or silently become a new language.

The selector displays language names as autonyms so they remain recognizable after switching:

- the Follow system label is localized;
- `English` remains `English`;
- Simplified Chinese is displayed as `简体中文`;
- Traditional Chinese is displayed as `繁體中文`.

For Follow system, the settings summary should include the resolved language, for example
`Follow system · English`, so fallback behavior is visible rather than surprising.

### Effective locale resolution

The locale controller scans the system `LocaleList` in order and maps the first supported locale:

1. English (`en`) maps to `en`.
2. Chinese with explicit `Hans` script maps to `zh-Hans`.
3. Chinese with explicit `Hant` script maps to `zh-Hant`.
4. Chinese without a script but with region `TW`, `HK`, or `MO` maps to `zh-Hant`.
5. Any other Chinese locale, including `CN`, `SG`, `MY`, and an unqualified `zh`, maps to
   `zh-Hans`.
6. Other languages are skipped while scanning the list.
7. If the list contains no supported language, resolve to English.

Manual selections bypass system matching. The result is a `SupportedLocale`, not an arbitrary
`java.util.Locale`, so every state transition has a known resource directory and fallback path.

Examples:

| Selection | System locale list | Effective locale |
| --- | --- | --- |
| Follow system | `zh-HK, en-US` | `zh-Hant` |
| Follow system | `ja-JP, en-US` | `en` |
| Follow system | `ja-JP` | `en` |
| Follow system | `zh, en-US` | `zh-Hans` |
| English | `zh-CN` | `en` |
| Traditional Chinese | `en-US` | `zh-Hant` |

### Observable process state

Introduce one process-local locale controller with these responsibilities:

- read and normalize the persisted selection;
- expose the selection as observable Compose snapshot state;
- track the current system locale list;
- expose `resolvedLocale` as derived observable state;
- persist and publish a manual selection synchronously;
- update the system locale list from application configuration callbacks while Follow system is
  active;
- build localized contexts for Compose and non-Compose consumers.

Register `ComponentCallbacks` once against the WeChat-process application context so a real system
configuration change updates the controller. Do not register one callback per screen or Compose
root. Manual selection changes update state immediately after the preference write and do not wait
for an Android configuration callback.

The controller contains no cross-process transport. If another process later initializes it, that
process owns an independent state instance seeded from the persisted preference.

## Android Resource Layout

Use the standard Android resource tree:

```text
app/src/main/res/
├── values/
│   ├── strings.xml              # complete English source and fallback
│   └── arrays.xml
├── values-zh-rCN/
│   └── strings.xml              # Simplified Chinese target
└── values-zh-rTW/
    └── strings.xml              # Traditional Chinese target
```

Do not add `values-en`; the default `values/strings.xml` is English. Android can therefore use the
default resource both for English and when a target language omits a key.

The current `xposed_scope` array contains only `com.tencent.mm`. Mark it non-translatable. Weblate's
Android handler treats a `string-array` as one translation unit, so future user-visible arrays
should normally reference individually named `<string>` resources rather than embedding
translatable item text directly in the array.

`app_name`, package-like tokens, the resource-injection sentinel, and similar technical values must
be marked `translatable="false"` when they are not intended for translators. `app_description` is
user-facing and should be translated unless product policy explicitly changes it.

## Localized Context and Compose Architecture

### Localized context creation

Create a fresh `Configuration` copy from the caller's current resources, replace only its locale
list, and call `createConfigurationContext`. Never call `Resources.updateConfiguration` and never
modify `HostInfo.application.resources.configuration` in place.

For a host/injected context, wrap the configured context with `CommonContextWrapper` and explicitly
ensure `ResourcesInjector.injectModuleRes(localizedContext.resources)` has run on the resulting
localized `Resources` instance. A new configuration context can own a new `Resources` object, so
successful injection of the original host resources is not sufficient evidence that the localized
resources can see module IDs.

For the module application's own UI, use the configured module context without host resource
injection. This path exists for completeness; it does not create cross-process live synchronization
with the WeChat process.

Resource lookup failure remains a real failure. Do not catch `Resources.NotFoundException` to return
the key, `"null"`, an empty string, or a hard-coded Chinese value. Locale fallback and resource
injection failure are different conditions: English handles the former, while the latter must stay
visible in logs and fail loudly at the call site as the existing resource architecture intends.

### Compose locale provider

Add a composable provider named `WeKitLocaleProvider`, with an explicit resource mode (`InjectedHost`
or `ModuleApp`). Its package placement may follow the existing UI utility layout, but this provider
name and the following contract are fixed:

1. Read `resolvedLocale` as Compose state.
2. Read the current base `LocalContext` and configuration inputs.
3. Rebuild and remember a localized context keyed by the base context, relevant non-locale
   configuration, resource mode, and effective locale.
4. For the injected-host mode, inject module resources into the localized resources.
5. Provide both the localized `LocalContext` and its `LocalConfiguration` to the subtree.

In the Compose version pinned by WeKit, `LocalResources` is computed from `LocalContext` and is
invalidated through `LocalConfiguration`. Providing both values is therefore required: changing a
standalone state variable without changing the resource-bearing composition locals is not enough
to make `stringResource` return a new language.

The provider must preserve all non-locale configuration values, including `uiMode`, density,
font scale, screen dimensions, and orientation. Locale changes must not accidentally freeze dark
mode or overwrite current host configuration.

Do not cache localized strings in top-level properties or `remember { ... }` without a locale key.
Call `stringResource` during composition, or key derived remembered data by the effective locale.

### Compose root integration

Every independent `setContent` or `ComposeView.setContent` root that can render WeKit text must be
covered exactly once. Merely making `resolvedLocale` observable does not update a tree that never
reads it.

Required integration points:

- `InjectedUiTheme` becomes the standard injected-host entry. It must apply the locale provider
  before reading theme resources and remove its current fixed override that directly provides
  `HostInfo.application.resources.configuration`. The localized configuration copy already retains
  the host's dark-mode and device configuration.
- `SettingsActivity` wraps the theme-engine switch once, outside both branches. The MIUIX branch
  (`ModuleTheme`) and the Nuke branch (`NukeSettingsContent` / `NukeModuleTheme`) therefore receive
  the same localized context and update together.
- `showComposeDialog` applies the injected-host locale provider outside its Module/Nuke theme
  composition. This avoids double providers when Nuke and Material themes are nested.
- `showPanelDialog` remains covered through `InjectedUiTheme`; its explicit `LocalContext` must not
  overwrite the localized context with the earlier unlocalized wrapper.
- `ModuleAppTheme` applies the module-app locale path for the small standalone module UI.
- All other roots found by auditing `setContent`, `ComposeView`, and injected view creation must use
  one of these approved wrappers. A bare Compose root is a migration blocker.

This design covers settings, feature dialogs, injected panels, navigation replacements, overlay UI,
and other existing Compose trees. A language change while one of those trees is visible triggers
recomposition and updates every string that is read correctly from resources. It does not recreate
Android `View` text that was previously assigned once outside Compose; those call sites need the
non-Compose rules below.

## Non-Compose String Access

Non-Compose UI must resolve strings from the same locale controller:

- when a localized context is already in scope, call `context.getString(resId, ...)`;
- otherwise ask the locale controller for a current localized injected context derived from the
  relevant host context, then call `getString`;
- resolve the string at the moment it is shown; do not retain a localized `String` in a singleton or
  long-lived cache;
- use resource formatting and plurals rather than concatenating translated fragments.

Examples include toasts, Android menu items, view content descriptions, dialog titles created from
non-Compose code, and user-visible error notifications. Internal logs remain technical English or
their existing literals and do not use localized resources merely because `getString` is available.

## Settings Experience

Add a Language dropdown/select preference to the general settings area in both settings engines.
Both renderers bind to the same preference/state model and must expose the same four options in the
same order:

1. Follow system
2. English
3. 简体中文
4. 繁體中文

Follow system is selected for new and existing users who have no language preference. Selecting an
item performs one synchronous state transaction: persist the stable value, update observable state,
and dismiss the selector. The visible settings page, navigation labels, feature metadata, and any
other active WeKit Compose roots refresh without Activity recreation or WeChat restart.

The selector must be reachable and understandable in every supported language. It must not use a
translated display label as its persisted value.

## Feature Metadata and Compatibility Migration

### Existing coupling

The current `@Feature(name, categories, description)` values are Chinese UI text, but `name` also
acts as:

- the `SwitchFeature` MMKV preference key;
- the Dex cache filename key;
- the generated New Features map key;
- a sort and association key;
- part of log/debug identity;
- user-visible feature metadata.

Replacing `name` directly with an English or runtime-localized string would reset feature toggles,
miss every existing Dex cache file, and make technical identity change when the UI language changes.
The migration must separate those responsibilities first.

### New annotation contract

Change feature metadata to contain a stable technical ID plus resource names and stable category
IDs. Use resource entry names as annotation strings instead of integer Android resource IDs; this
keeps the annotation independent of non-final/generated `R` constants while allowing KSP to emit
typed `R.string.*` references into app source.

Conceptual form:

```kotlin
@Feature(
    id = "朋友圈评论防撤回",
    nameRes = "feature_anti_moment_comments_delete_name",
    categoryIds = [FeatureCategoryIds.MOMENTS],
    descriptionRes = "feature_anti_moment_comments_delete_description",
)
```

The example's Chinese `id` is intentional compatibility data, not display text. During the initial
migration, every existing feature's ID must exactly equal its current `@Feature.name`, preserving
the existing MMKV and Dex cache keys byte for byte. It is frozen afterward and is never translated.
New features created after the migration should use a namespaced ASCII ID such as
`moments.anti_comment_delete`.

Resource entry names use stable semantic snake_case keys. They must not be generated from or equal
to the English sentence text. Renaming a resource key is a source migration requiring corresponding
target changes; editing its English value is an ordinary copy change.

Category IDs live in a central constant set shared by the annotation and UI mapping. They are stable
technical values such as `chat`, `moments`, and `miniapps`, not translated titles.

### Generated and runtime metadata

KSP validates duplicate feature IDs, unknown category IDs, valid Android resource-name syntax, and
the existing object/base-class constraints. It generates typed `R.string` references and initializes
runtime metadata with:

- `id`: stable technical identity;
- `nameRes`: localized display-name resource ID;
- `descriptionRes`: localized description resource ID or an explicit no-description value;
- `categoryIds`: stable category identities.

The app's category registry maps each stable category ID to a title resource and icon. Navigation
state stores category IDs, never translated titles. The New Features pseudo-category also receives a
stable ID and a title resource.

`BaseFeature` must distinguish a technical path used for logs/diagnostics from UI labels resolved
through a localized context. UI search, sort, cards, Nuke pages, MIUIX pages, and content descriptions
resolve `nameRes`, `descriptionRes`, and category title resources at composition time. Locale-aware UI
sorting is performed after resolution and is recomputed when the effective locale changes; KSP's
registry order remains deterministic by technical ID and feature type.

### Compatibility requirements

- `SwitchFeature` reads and writes the stable feature ID, not the localized name.
- `DexCacheManager` uses the stable feature ID for cache filenames. Existing cache filenames must be
  unchanged by the migration.
- Generated method hashes remain keyed by class name and must not be changed merely to accommodate
  localization.
- `GenerateNewFeaturesTask` extracts `id` and generates `ADDED_AT_BY_ID`; it no longer parses display
  names.
- `DexResolutionTestRegistry` stores technical IDs/category IDs and resource entry names or IDs. The
  desktop runner must not require a device locale merely to identify a resolver.
- Logs and error paths use technical identity. They are not translated and must not depend on a
  current Compose context.
- No one-time preference rewrite is needed for existing features because their initial IDs equal
  the old keys. Verification must compare a representative set of pre-migration preference keys and
  Dex cache paths with their post-migration values.

## Translation Scope

Translate user-visible text owned by WeKit, including:

- settings navigation, rows, summaries, dialogs, buttons, and empty states;
- feature names, descriptions, categories, and user-facing configuration UI;
- injected Compose controls, panels, menus, badges, accessibility descriptions, and hints;
- toasts and user-visible errors;
- Nuke and MIUIX settings variants;
- the standalone module app's small user-facing surface;
- user-facing date/count/size text where Android formatting is appropriate.

Exclude from translation resources:

- `WeLogger` messages and diagnostic-only report labels;
- SQL fragments, database column/table names, JSON keys, protobuf fields, CGI paths, and protocol
  constants;
- DexKit strings, class names, method names, signatures, descriptors, host log anchors, and WeChat
  UI strings used for matching;
- feature technical IDs, preference keys, cache keys, filenames, URLs, package names, and native/JNI
  symbols;
- script API names and source-language tokens;
- text copied from the host when WeKit does not own its wording.

A repository-wide Chinese-character regex is not an acceptable i18n gate. This codebase legitimately
contains Chinese host anchors, logs, comments, and protocol-adjacent constants. Migration review must
classify literals by ownership and user visibility, and future enforcement should target known UI
APIs or use a reviewed allowlist rather than blindly moving every Chinese literal into resources.

## String Authoring Rules

1. The default resource value is grammatical English, not an identifier or machine-generated
   transliteration.
2. Use semantic snake_case keys grouped by surface, for example
   `settings_language_title` and `feature_moments_anti_delete_description`.
3. Use indexed placeholders (`%1$s`, `%2$d`) whenever arguments exist. Translators may need to
   reorder them.
4. Escape literal percent signs correctly or use `formatted="false"` only when the whole entry is
   intentionally not formatted.
5. Use `<plurals>` for user-visible quantities. Do not construct English plural rules in Kotlin.
6. Do not require every language to contain the same plural quantity set. Validate only quantities
   that are legal and present for that language, while preserving compatible placeholders for
   corresponding messages.
7. Keep complete sentences in one resource. Do not concatenate translated sentence fragments.
8. Preserve supported Android markup and escapes. CI compares tag structure and placeholders, not
   merely plain text.
9. Add XML comments for ambiguous domain terms, feature behavior, button destination, placeholder
   meaning, or strict length constraints. Comments are translator context and must contain no
   secrets or unstable reverse-engineering details.
10. Mark non-translatable entries explicitly with `translatable="false"`.
11. Do not use `tools:ignore` or Weblate flags to hide a real placeholder/markup mismatch.
12. Do not store a localized sentence in MMKV or use a translated value as an enum, navigation,
    analytics, preference, or cache key.

## Hosted Weblate Libre

### Why this platform

Hosted Weblate Libre fits WeKit's public GPLv3 workflow: it is Git-native, exposes translation and
review workflows in a browser, understands Android string resources, and offers gratis hosting to
qualifying libre projects. Crowdin is a capable alternative, but using both would create two edit
queues, conflicting translations, duplicate contributor accounts, and an unclear source of truth.

Hosted Libre is an application-based service rather than an entitlement. At the time of this
design, its published conditions include a publicly available project, an approved free/open-source
license, community support, a translation size below 10,000 source characters, annual project
income below EUR 1,000,000, and visible Weblate attribution. Before onboarding, the maintainer must
confirm WeKit still satisfies the then-current conditions and submit the hosting request. If the
application is rejected or its conditions materially change, stop and choose a new hosting plan
explicitly; do not silently add Crowdin or another second platform.

### Project and component setup

Create one Hosted Weblate project and one initial Android component:

| Setting | Value |
| --- | --- |
| Project | `WeKit` |
| Component | `Android app` |
| Repository | `Ujhhgtg/WeKit` |
| Source branch | `dev` |
| Push/service branch | `weblate/dev` |
| Source language | English |
| Source file | `app/src/main/res/values/strings.xml` |
| File mask | `app/src/main/res/values-*/strings.xml` |
| Simplified target | `app/src/main/res/values-zh-rCN/strings.xml` |
| Traditional target | `app/src/main/res/values-zh-rTW/strings.xml` |
| Allowed languages | English source, Simplified Chinese, Traditional Chinese |

Seed `weblate/dev` from `dev` before connecting it. Configure Weblate's repository access with the
minimum permission needed to fetch `dev` and push only the service branch. Protect `dev` in GitHub
and do not grant Weblate direct merge permission.

The initial setup must perform a round-trip test that changes one disposable target value and
confirms Weblate preserves the exact `values-zh-rCN` and `values-zh-rTW` directories. If Weblate's
language-code normalization proposes different directory names, configure aliases/mappings before
real translation begins; do not accept duplicate Chinese resource directories.

### Git and pull-request model

Weblate fetches source changes from `dev`, commits target-file changes to `weblate/dev`, and updates a
pull request from `weblate/dev` into `dev`. The PR may remain open as a rolling translation PR or be
recreated after each merge, depending on maintainer preference, but each merge must pass normal
review and CI.

Merge Weblate PRs with a regular merge commit. Do not squash or rebase them. Weblate tracks Git
history and its own commits; removing those commits through squash/rebase creates avoidable
synchronization and duplicate-commit problems. After merge, Weblate fetches the updated `dev` before
new target work is pushed.

Weblate must not automatically merge PRs, force-push `dev`, or edit arbitrary source files. Component
configuration restricts translation changes to the target Android XML files and any explicitly
approved translator-credit artifact.

### Permissions and review

Use these roles:

- Visitors can view translation status.
- Logged-in community members can submit suggestions.
- Trusted translators can enter Chinese translations.
- Language reviewers can approve or reject translations.
- Maintainers control source English, component configuration, service-branch merges, and releases.

Enable a review workflow for both Chinese languages. A new or machine-generated translation is not
considered release-ready merely because the XML compiles. Reviewers check terminology, placeholders,
meaning, tone, and UI length. Suggestions remain attributable to their authors and do not bypass
review.

Maintain a glossary for project-specific terms such as WeKit, WeChat, Xposed, Zygisk, DexKit,
Moments, Channels, group/chatroom terminology, and names that must remain untranslated. Source XML
comments provide string-specific context; the glossary provides cross-project consistency.

### Ownership and contribution workflow

Developers add or edit English source keys in normal PRs against `dev`. Those PRs may also include
maintainer-authored Simplified Chinese during the initial migration, but outside contributors should
normally use Weblate for target translations. After English keys land, Weblate detects them and
presents them as untranslated targets.

Translation contributors:

1. Sign in to Hosted Weblate.
2. Choose Simplified or Traditional Chinese.
3. Read the source string, comment, glossary, screenshots/context, and existing translation memory.
4. Submit a translation or suggestion.
5. Address automated format/placeholder checks.
6. A reviewer approves the entry.
7. Weblate commits approved target changes to `weblate/dev`.
8. Maintainers merge the generated PR after repository CI passes.

Document this flow in the repository and link it from the main README and GitHub contribution guide.
Do not tell casual translators to edit hundreds of XML lines manually unless Weblate is unavailable
and the contributor explicitly prefers a Git PR.

### Traditional Chinese bootstrap

Traditional Chinese may be bootstrapped once from reviewed Simplified Chinese using OpenCC `s2t`.
The generated values must be imported as needing review/unapproved. Script conversion cannot decide
regional wording, product terminology, punctuation, or UI fit.

After the bootstrap, Weblate's Traditional Chinese file is independently human-maintained. Do not
run recurring OpenCC synchronization that overwrites reviewer changes or assumes every Simplified
Chinese edit has a mechanically correct Traditional equivalent.

### License, attribution, and credits

Translation contributions become part of the GPLv3 repository and are distributed under the same
project license. The Weblate landing page and repository contribution documentation must state this
clearly before submission. Do not add a separate translation CLA in this design.

Satisfy Hosted Weblate Libre's attribution requirement with a visible Weblate badge/link in the
README or translation documentation. Add a translation credits entry in the app's About UI that
links to a repository-maintained translators page or Weblate contributor view. Do not make app
startup depend on a network request for contributor names. The repository artifact is the durable
credit record and can be updated through reviewed translation PRs.

## Repository and CI Validation

Weblate's checks improve translator feedback, but repository CI remains authoritative. Add a focused
translation validator that parses XML structurally and fails on:

- malformed XML or Android resource compilation failure;
- duplicate keys within a file;
- a key appearing only in a target file and not in the English source;
- type mismatch between source and target (`string`, `plurals`, or `string-array`);
- incompatible formatting placeholders, including index/type mismatches;
- invalid or structurally incompatible supported markup;
- illegal plural quantities for the target language;
- a translatable source entry that has no English default value;
- an entry marked non-translatable in the source appearing as a translated target entry;
- unexpected Chinese target directories or a Weblate-created duplicate locale file.

The validator must allow:

- a source key missing from either Chinese file;
- different legal plural quantity sets between English and Chinese;
- intentional wording and punctuation differences;
- target ordering that differs from the source, provided keys and structure are valid.

Android resource compilation remains part of `./x build`. Local completion checks for the eventual
implementation are:

```bash
./x i18n-check
./x build
git diff --check
```

`./x i18n-check` is the concrete repository entry point. Its implementation location (xtask,
Gradle task, or a small Kotlin/JVM validator invoked by xtask) is an implementation-plan choice, but
the user-facing command and its validation contract are fixed by this spec.

Current GitHub pull-request CI targets only `master`, although push CI also targets `dev`. Before
Weblate opens translation PRs, change pull-request coverage to include `dev`. Translation XML must
not be excluded by path filters. A translation PR into `dev` must run the focused translation checks
and the normal Android build path used for code PRs; branch protection must require those checks
before merge.

Do not add a naive repository-wide Chinese regex check. If a future static check looks for newly
hard-coded user-facing strings, scope it to known UI calls/packages and maintain an explicit reviewed
allowlist for technical literals.

## Migration Plan Boundaries

This is one feature program but should be implemented in reviewable phases. Each phase must leave the
tree buildable; Weblate onboarding waits until source key churn has stabilized.

### Phase 1: locale infrastructure

- Add the persisted language model, system-locale resolution, process-local observable controller,
  and localized context factory.
- Add `WeKitLocaleProvider` and integrate every Compose root.
- Add the language selector to both settings engines.
- Prove live switching with a small shared set of resource-backed labels before bulk extraction.

### Phase 2: feature identity and category migration

- Introduce stable feature IDs, resource entry names, category IDs, and KSP generation.
- Preserve all existing preference and Dex cache keys.
- Update New Features generation, settings navigation, search/sort, and desktop resolver metadata.
- Migrate feature name/description/category UI to English source resources and Chinese targets.

### Phase 3: complete UI extraction

- Inventory all Compose roots and non-Compose user-visible surfaces.
- Migrate strings subsystem by subsystem: shared settings chrome, Nuke, feature settings/dialogs,
  injected panels/controls, menus/toasts/errors, then standalone module UI.
- Classify every touched literal; leave logs, host anchors, protocol values, and technical IDs in
  code.
- Add translator comments and glossary candidates during extraction rather than afterward.

### Phase 4: validation and contributor documentation

- Add the structural translation validator and CI coverage for PRs into `dev`.
- Document string rules, contributor workflow, GPLv3 terms, review roles, and translator credits.
- Add the Hosted Weblate attribution link/badge.

### Phase 5: Hosted Weblate onboarding

- Confirm Hosted Libre eligibility and obtain approval.
- Freeze the initial English key set long enough for a clean import.
- Create the component, languages, glossary, permissions, service branch, and pull request.
- Perform the Chinese directory round-trip test.
- Import existing reviewed Simplified Chinese and the unapproved OpenCC Traditional bootstrap.
- Merge the first Weblate PR normally after CI and human review.

## Verification Matrix

Automated checks establish resource and build correctness. Real WeChat device testing remains
required for injected behavior.

| Scenario | Expected result |
| --- | --- |
| Fresh install / no preference | Follow system selected. |
| System `en-US` | English UI. |
| System `zh-CN`, `zh-SG`, or unqualified `zh` | Simplified Chinese UI. |
| System `zh-TW`, `zh-HK`, or `zh-MO` | Traditional Chinese UI. |
| Unsupported-only system locale such as `ja-JP` | English UI. |
| Unsupported first, supported second (`ja-JP, zh-TW`) | Traditional Chinese UI. |
| Manual language under any system locale | Manual language wins. |
| Change language with MIUIX settings open | Existing screen refreshes immediately. |
| Change language with Nuke settings open | Existing screen refreshes immediately. |
| Change language with injected panel/dialog/overlay visible | Existing Compose tree refreshes immediately. |
| Open a new dialog after changing language | New localized context uses the selected language. |
| Non-Compose toast/menu after changing language | Text resolves in the selected language. |
| Missing Chinese target key | English default is shown. |
| Missing English default key referenced by code | Build/validation fails. |
| Restart WeChat | Persisted selection is restored. |
| Change system locale while Follow system is active | Effective locale and visible Compose UI update. |
| Change system locale while a manual language is active | Manual language remains active. |
| API 28/29 resource injection path | Localized module resources resolve in host UI. |
| API 30+ resource-loader path | Localized module resources resolve in host UI. |
| Existing enabled feature preference | State is unchanged after metadata migration. |
| Existing Dex resolver cache | Cache path remains valid after metadata migration. |

Test both supported settings engines and representative injected roots rather than assuming one
successful settings screen proves every independent `ComposeView` is wrapped. Desktop builds cannot
prove live behavior in WeChat; device verification must record the tested Android version, WeChat
version, UI surface, language transition, and result.

## Failure Handling

- Invalid language preference: normalize to Follow system and continue.
- Unsupported system locale: resolve to English.
- Missing target translation: use Android's English default resource.
- Missing English default or incompatible resource type: fail build/CI.
- Placeholder or markup mismatch: block the translation PR.
- Module resource injection failure: log the existing detailed injection error and allow the real
  resource lookup failure to remain visible; do not synthesize fallback text.
- Weblate branch conflict: lock/pause target edits, update the service branch from `dev` using the
  documented Weblate flow, and preserve authored translation commits. Do not force-push translated
  work away.
- Hosted Libre rejection: stop onboarding and request a new explicit platform/hosting decision.

## Alternatives Rejected

### Android per-app locales

Rejected because WeKit's useful UI is injected into WeChat. Registering or changing an application
locale would target the host package and could alter WeChat's own resources rather than only WeKit
content.

### Mutating global host resources

Rejected because `Resources.updateConfiguration`, host-wide locale mutation, and global `getString`
hooks affect WeChat behavior and hide resource-injection errors. Localized configuration contexts
provide the required isolation.

### Runtime JSON catalogs or downloaded packs

Rejected because they duplicate Android's qualifier, formatting, plural, lint, and fallback system;
they also make resource-ID-based injected UI harder and add runtime/network failure modes.

### Crowdin or dual-platform synchronization

Crowdin is viable, but Hosted Weblate Libre was selected for this project. A second platform would
split review state and create conflicting target-file writers. Reconsidering the platform requires a
separate explicit decision and migration, not simultaneous use.

### Machine-converted Traditional Chinese as final translation

Rejected because OpenCC conversion is a useful bootstrap, not linguistic or product review.

## Acceptance Criteria

The eventual implementation is accepted only when all of the following are true:

- The language selector offers exactly Follow system, English, Simplified Chinese, and Traditional
  Chinese, with Follow system as the default.
- Unsupported system locales and missing Chinese entries show English.
- Every WeKit Compose root uses the locale provider and visible Compose UI changes language without
  Activity recreation or WeChat restart.
- Both `LocalContext` and `LocalConfiguration` reflect the effective locale, allowing
  `stringResource`/`LocalResources` to re-resolve.
- Non-Compose user-facing text uses the current localized context.
- WeChat's global configuration is not mutated and no global `getString` hook or resource proxy is
  introduced.
- Existing feature preference keys and Dex cache filenames are unchanged.
- Feature/category UI metadata uses resources while technical identities remain stable and
  untranslated.
- English default resources are complete; Chinese target incompleteness is allowed and tested.
- Placeholder, markup, type, duplicate-key, target-only-key, and plural validation runs in CI.
- Pull requests into `dev`, including Weblate PRs, run required translation checks and normal build
  validation.
- Hosted Weblate uses English source, only the two Chinese targets, a protected service branch,
  human review, glossary/context, and no automatic merge.
- Weblate translation PRs are regular-merged, not squashed/rebased.
- The repository documents translation contribution, GPLv3 terms, and credits translators.
- Traditional Chinese machine bootstrap remains unapproved until human review.
- `./x build` and `git diff --check` pass, followed by the device verification matrix for relevant
  injected UI.

## Official References

- Hosted Weblate and Libre hosting conditions: <https://weblate.org/en/hosting/>
- Weblate Android string format: <https://docs.weblate.org/en/latest/formats/android.html>
- Weblate continuous localization and Git integration:
  <https://docs.weblate.org/en/latest/admin/continuous.html>
- Weblate translation workflow and review states:
  <https://docs.weblate.org/en/latest/admin/workflows.html>
- Android localization and default-resource fallback:
  <https://developer.android.com/guide/topics/resources/localization>
- Android `Context.createConfigurationContext`:
  <https://developer.android.com/reference/android/content/Context#createConfigurationContext(android.content.res.Configuration)>
- Compose resource access:
  <https://developer.android.com/develop/ui/compose/resources>
