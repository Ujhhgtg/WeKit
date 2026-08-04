# HomeSidePanel Content Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved HomeSidePanel content experience with real WeChat profile/status data, Xiaomi Weather, Hitokoto, Material 3 dark-mode UI, settings, and real shortcuts without regressing the existing drawer interaction shell.

**Architecture:** Move the existing drawer feature into `dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel`. Keep host view/gesture code in `HomeSidePanel`/`HomeSidePanelSession`, and isolate data access behind repositories owned by a session-scoped `HomeSidePanelController`. Pure matching, validation, request-building, and UI-state rules are tested on the JVM before Android/host integration.

**Tech Stack:** Kotlin, Android Views, Jetpack Compose Material 3, Coil 3 `AsyncImage`, OkHttp 5, kotlinx.serialization JSON/ProtoBuf, MMKV-backed `WePrefs`, DexKit/reflekt, JUnit Jupiter, and the existing `./x` build/dex-test orchestration.

## Global Constraints

- Target WeChat versions remain 8.0.65 through 8.0.76.
- All HomeSidePanel files use package `dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel`.
- Preserve the existing side-swipe, back interception, edge-to-edge, FAB transform, and ActionBarContainer behavior.
- Do not add a PhoneWindow hook.
- Do not use `getIdentifier`.
- Do not use obfuscated class/member names as final hook anchors; use stable strings, signatures, structural relationships, `dexClass`, `dexField`, and `reflekt`.
- Keep database/network work off the main thread and cancel work when LauncherUI is destroyed.
- Use Material 3 components and `MaterialTheme.colorScheme`; support system dark mode through `InjectedUiTheme`.
- Default weather city is Beijing (`weathercn:101010100`).
- First weather initialization attempts WeChat profile region lookup; only `CN`, `HK`, `MO`, and `TW` are eligible.
- “自动检测” requests coarse location only after an explicit user click; permission denial must leave manual search available.
- “从个人资料读取” reuses the first-initialization profile-region algorithm and reports distinct errors.
- Hitokoto endpoint is fixed to `https://v1.hitokoto.cn/`; preload before the panel is opened, cache the last success, and enforce at most one request per second with in-flight deduplication.
- Weather and Hitokoto settings persist through `WePrefs`; no other customization is added.
- Preserve unrelated dirty-worktree changes.
- Final validation uses `./x build`, `git diff --check`, and affected supported-version `./x dex-test` runs.

---

### Task 1: Move the existing drawer feature into the new package

**Files:**
- Move: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/HomeSidePanel.kt` → `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`
- Move: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/HomeSidePanelGestureState.kt` → `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelGestureState.kt`
- Move: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/HomeSidePanelGestureStateTest.kt` → `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelGestureStateTest.kt`

**Interfaces:**
- Produces no behavior changes; existing `HomeSidePanel` object and all internal gesture helpers retain their names.
- Later tasks import `HomeSidePanel` and `HomeSidePanelGestureState` from the new package.

- [ ] **Step 1: Run the existing characterization test before moving files**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.HomeSidePanelGestureStateTest'
```

Expected: PASS for the current dirty-worktree implementation.

- [ ] **Step 2: Move the three files and update package declarations**

Change each production/test package declaration to:

```kotlin
package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel
```

Keep the existing imports and implementation unchanged except for package-local references.

- [ ] **Step 3: Search for stale package references**

Run:

```bash
rg -n 'features\.items\.beautify\.(HomeSidePanel|HomeSidePanelGestureState)' app/src/main app/src/test
```

Expected: no stale imports or fully-qualified references remain.

- [ ] **Step 4: Run the moved focused test**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelGestureStateTest'
```

Expected: PASS with identical assertions.

- [ ] **Step 5: Commit the package-only refactor**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel
git commit -m "refactor: move HomeSidePanel into feature package"
```

---

### Task 2: Add shared models, settings persistence, and pure validation rules

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelModels.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelProfileRules.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelPreferences.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelRulesTest.kt`

**Interfaces:**

```kotlin
data class HomeSidePanelUiState(
    val profile: HomeSidePanelProfile,
    val weather: WeatherUiState,
    val weatherSettings: WeatherSettingsUiState,
    val hitokoto: HitokotoUiState,
    val hitokotoSettings: HitokotoSettings,
    val cardMode: HomeSidePanelCardMode,
)

enum class HomeSidePanelCardMode {
    CONTENT,
    WEATHER_SETTINGS,
    HITOKOTO_SETTINGS,
}

data class HomeSidePanelProfile(
    val wxId: String,
    val nickname: String,
    val avatarUrl: String,
    val status: HomeSidePanelStatusUiState,
)

sealed interface HomeSidePanelStatusUiState {
    data object Loading : HomeSidePanelStatusUiState
    data class Ready(val status: HomeSidePanelStatus) : HomeSidePanelStatusUiState
    data object NoStatus : HomeSidePanelStatusUiState
    data class Error(val message: String) : HomeSidePanelStatusUiState
}

data class HomeSidePanelStatus(
    val statusId: String,
    val description: String,
    val iconId: String,
    val emoji: HomeSidePanelStatusEmoji?,
)

data class HomeSidePanelStatusEmoji(
    val md5: String?,
    val url: String?,
    val thumbUrl: String?,
    val attachedText: String?,
)

data class WeatherCity(
    val countryCode: String,
    val province: String,
    val city: String,
    val district: String?,
    val cityNum: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

internal val DEFAULT_WEATHER_CITY = WeatherCity(
    countryCode = "CN",
    province = "北京",
    city = "北京",
    district = null,
    cityNum = "101010100",
)

data class WeatherSnapshot(
    val city: WeatherCity,
    val weatherCode: String,
    val temperature: String,
    val feelsLike: String,
    val high: String,
    val low: String,
    val humidity: String,
    val windSpeed: String,
    val publishedAt: String,
    val fetchedAt: Long,
)

sealed interface WeatherUiState {
    data object Loading : WeatherUiState
    data class Ready(
        val snapshot: WeatherSnapshot,
        val refreshing: Boolean = false,
    ) : WeatherUiState
    data class Error(
        val message: String,
        val cached: WeatherSnapshot?,
    ) : WeatherUiState
}

sealed interface WeatherResult {
    data class Success(val snapshot: WeatherSnapshot) : WeatherResult
    data class Error(val message: String, val cached: WeatherSnapshot?) : WeatherResult
}

sealed interface WeatherCityMatchResult {
    data class Success(val city: WeatherCity) : WeatherCityMatchResult
    data class Error(val reason: WeatherCityMatchFailure) : WeatherCityMatchResult
}

enum class WeatherCityMatchFailure(val message: String) {
    UNSUPPORTED_COUNTRY("不支持的资料地区"),
    MISSING_REGION("个人资料中没有地区"),
    MISSING_CITY("个人资料中没有城市"),
    NO_MATCH("无法在天气城市库中匹配该城市"),
    READ_ERROR("读取个人资料失败"),
}

data class WeatherSettingsUiState(
    val selectedCity: WeatherCity,
    val searchQuery: String = "",
    val searchResults: List<WeatherCity> = emptyList(),
    val actionInProgress: Boolean = false,
    val message: String? = null,
)

data class HitokotoSnapshot(
    val uuid: String,
    val text: String,
    val type: String?,
    val source: String?,
    val author: String?,
    val creator: String?,
    val createdAt: String?,
    val fetchedAt: Long,
)

data class HitokotoSettings(
    val categories: Set<String> = HITOKOTO_CATEGORY_CODES,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val charset: String = "utf-8",
    val showSource: Boolean = true,
    val showAuthor: Boolean = true,
)

sealed interface HitokotoUiState {
    data object Loading : HitokotoUiState
    data class Ready(
        val snapshot: HitokotoSnapshot,
        val refreshing: Boolean = false,
    ) : HitokotoUiState
    data class Error(
        val message: String,
        val cached: HitokotoSnapshot?,
    ) : HitokotoUiState
}

sealed interface HitokotoResult {
    data class Success(val snapshot: HitokotoSnapshot) : HitokotoResult
    data class Error(val message: String, val cached: HitokotoSnapshot?) : HitokotoResult
}

internal val HITOKOTO_CATEGORY_CODES = ('a'..'l').map(Char::toString).toSet()

internal object HomeSidePanelPreferences {
    var selectedWeatherCity: WeatherCity
    var weatherLastSuccess: WeatherSnapshot?
    var weatherProfileInitialized: Boolean
    var weatherLastError: String?
    var hitokotoSettings: HitokotoSettings
    var hitokotoLastSuccess: HitokotoSnapshot?
}
```

- [ ] **Step 1: Write failing tests for region eligibility and settings validation**

```kotlin
@Test
fun onlySupportedWechatCountryCodesAreEligible() {
    assertTrue(isEligibleWeatherCountry("CN"))
    assertTrue(isEligibleWeatherCountry("HK"))
    assertTrue(isEligibleWeatherCountry("MO"))
    assertTrue(isEligibleWeatherCountry("TW"))
    assertFalse(isEligibleWeatherCountry("US"))
    assertFalse(isEligibleWeatherCountry(""))
}

@Test
fun invalidHitokotoLengthsAreRejected() {
    assertEquals("长度不能为负数", validateHitokotoSettings(minLength = -1, maxLength = null))
    assertEquals("最大长度不能小于最小长度", validateHitokotoSettings(minLength = 20, maxLength = 10))
    assertEquals("至少选择一个分类", validateHitokotoSettings(minLength = null, maxLength = null, categories = emptySet()))
    assertNull(validateHitokotoSettings(minLength = 10, maxLength = 20, categories = setOf("a")))
}
```

Run the focused test and verify RED because the rule functions do not exist.

- [ ] **Step 2: Implement the models and pure rules**

Add the exact model set in the Interfaces block, plus these pure rules:

```kotlin
internal fun isEligibleWeatherCountry(code: String): Boolean =
    code.uppercase() in setOf("CN", "HK", "MO", "TW")

internal fun validateHitokotoSettings(
    minLength: Int?,
    maxLength: Int?,
    categories: Set<String> = HITOKOTO_CATEGORY_CODES,
    charset: String = "utf-8",
): String? = when {
    minLength != null && minLength < 0 || maxLength != null && maxLength < 0 ->
        "长度不能为负数"
    categories.isEmpty() -> "至少选择一个分类"
    categories.any { it !in HITOKOTO_CATEGORY_CODES } -> "包含不支持的一言分类"
    charset !in setOf("utf-8", "gbk") -> "不支持的字符编码"
    minLength != null && maxLength != null && maxLength < minLength ->
        "最大长度不能小于最小长度"
    else -> null
}
```

- [ ] **Step 3: Add WePrefs keys and typed accessors**

Use stable feature-prefixed keys, for example:

```kotlin
internal object HomeSidePanelPreferenceKeys {
    const val WEATHER_CITY_NUM = "home_side_panel_weather_city_num"
    const val WEATHER_CITY_LABEL = "home_side_panel_weather_city_label"
    const val WEATHER_LAST_SUCCESS = "home_side_panel_weather_last_success"
    const val WEATHER_PROFILE_INITIALIZED = "home_side_panel_weather_profile_initialized"
    const val WEATHER_LAST_ERROR = "home_side_panel_weather_last_error"
    const val HITOKOTO_SETTINGS = "home_side_panel_hitokoto_settings"
    const val HITOKOTO_LAST_SUCCESS = "home_side_panel_hitokoto_last_success"
}
```

Expose `var selectedWeatherCity`, `var weatherLastSuccess`, `var weatherProfileInitialized`,
`var weatherLastError`, `var hitokotoSettings`, and `var hitokotoLastSuccess`. Back primitive
values with `WePrefs.Companion.prefOption`; encode/decode structured values as strings with the
existing `DefaultJson`. Never store host objects in MMKV.

- [ ] **Step 4: Run the focused rules test and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelRulesTest'
git diff --check
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelModels.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelProfileRules.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelPreferences.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelRulesTest.kt
git commit -m "feat: add HomeSidePanel content models and preferences"
```

Expected: PASS.

---

### Task 3: Add the Xiaomi Weather city index

**Files:**
- Create: `app/src/main/assets/home_side_panel/xiaomi_weather.db`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelCityIndex.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelCityIndexTest.kt`

**Interfaces:**

```kotlin
interface HomeSidePanelCityIndex {
    suspend fun search(query: String): List<WeatherCity>
    suspend fun matchProfile(
        countryCode: String,
        province: String,
        city: String,
    ): WeatherCityMatchResult
    suspend fun matchLocation(province: String, city: String): WeatherCityMatchResult
}

internal fun interface CityQueryTransliterator {
    fun transliterate(value: String): String
}

internal class AssetHomeSidePanelCityIndex(
    context: Context,
    transliterator: CityQueryTransliterator = AndroidIcuCityQueryTransliterator,
) : HomeSidePanelCityIndex
```

- [ ] **Step 1: Add and validate the source database asset**

Copy the upstream Xiaomi city database into the module asset path:

```bash
mkdir -p app/src/main/assets/home_side_panel
curl --fail --location \
  https://raw.githubusercontent.com/huanghui0906/API/master/xiaomi_weather.db \
  --output app/src/main/assets/home_side_panel/xiaomi_weather.db
echo '14d8ba2f018bd43447b5b20f8174f06562608879f4bec151b124fbab5a718493  app/src/main/assets/home_side_panel/xiaomi_weather.db' \
  | sha256sum --check
```

Validate the exact schema before implementation:

```bash
sqlite3 app/src/main/assets/home_side_panel/xiaomi_weather.db '.tables'
sqlite3 app/src/main/assets/home_side_panel/xiaomi_weather.db '.schema citys'
sqlite3 app/src/main/assets/home_side_panel/xiaomi_weather.db \
  "SELECT _id,province_id,name,city_num FROM citys WHERE city_num IN ('101010100','101320101','101330101','101340101');"
```

Expected tables are `provinces` and `citys`, with Beijing, Hong Kong, Macao, and Taipei present.

- [ ] **Step 2: Write failing city-index tests**

Use an in-memory fixture implementation or a temporary SQLite copy so tests do not depend on Android assets. Cover normalization and matching:

```kotlin
@Test
fun profileMatchingNormalizesProvinceAndCitySuffixes() {
    val index = InMemoryCityIndex(
        listOf(WeatherCity("CN", "北京", "北京", "海淀", "101010200"))
    )
    val result = runBlocking { index.matchProfile("CN", "北京市", "北京市海淀区") }
    assertEquals(
        "101010200",
        (result as WeatherCityMatchResult.Success).city.cityNum,
    )
}

@Test
fun unsupportedProfileCountryReturnsExplicitFailure() {
    val result = runBlocking { fixtureIndex.matchProfile("US", "", "New York") }
    assertEquals(
        WeatherCityMatchFailure.UNSUPPORTED_COUNTRY,
        (result as WeatherCityMatchResult.Error).reason,
    )
}

@Test
fun matchPriorityPrefersCountryProvinceAndDistrict() {
    val result = runBlocking { fixtureIndex.matchProfile("CN", "北京市", "海淀区") }
    assertEquals(
        "101010200",
        (result as WeatherCityMatchResult.Success).city.cityNum,
    )
}
```

Run the test and verify RED.

- [ ] **Step 3: Implement the asset-backed index**

Copy the asset once into `context.noBackupFilesDir/home_side_panel/xiaomi_weather.db`, then open it read-only. Use the source schema’s province offset exactly:

```sql
SELECT c.province_id, c.name, c.city_num, p.name AS province
FROM citys c
LEFT JOIN provinces p ON p._id = c.province_id + 1
ORDER BY c._id
```

Normalize `citys.name` by splitting at the first `.` into base city and optional district. Map `province_id` `31`, `32`, and `33` to `HK`, `MO`, and `TW`; all earlier IDs map to `CN`. Normalize `省`, `市`, `区`, `县`, `自治区`, `特别行政区`, whitespace, and case before matching. Put pinyin conversion behind a `CityQueryTransliterator` interface: Android production uses ICU `Han-Latin; Latin-ASCII`, while JVM tests inject an identity/fake transliterator.

- [ ] **Step 4: Implement deterministic match priority and search**

Match in this order and return `WeatherCityMatchResult.Error` with the exact typed reason from Task 2:

1. country + province + base city + district;
2. country + province + base city;
3. country + base city/district;
4. normalized base city/district;
5. fail with `NO_MATCH`.

Search must return stable database order, deduplicate identical city labels, and include the `cityNum` needed by the weather request.

- [ ] **Step 5: Run tests, validate the asset, and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelCityIndexTest'
git diff --check
git add app/src/main/assets/home_side_panel/xiaomi_weather.db app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelCityIndex.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelCityIndexTest.kt
git commit -m "feat: add HomeSidePanel weather city index"
```

---

### Task 4: Add profile fields and the cross-version TextStatus repository

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/models/SelfProfileField.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelProfileRepository.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelTextStatusApi.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelStatusProto.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelStatusMappingTest.kt`

**Interfaces:**

```kotlin
interface HomeSidePanelTextStatusReader {
    fun read(wxId: String): HomeSidePanelStatusUiState
}

internal class HomeSidePanelTextStatusApi(
    private val serviceClass: Class<*>,
    private val storageAccessor: Method,
    private val latestStatusMethod: Method,
    private val recordClass: Class<*>,
) : HomeSidePanelTextStatusReader

class HomeSidePanelProfileRepository(
    private val statusReader: HomeSidePanelTextStatusReader,
    private val cityIndex: HomeSidePanelCityIndex,
) {
    suspend fun loadIdentity(): HomeSidePanelProfile
    suspend fun refreshStatus(): HomeSidePanelStatusUiState
    suspend fun readWeatherCityFromProfile(): WeatherCityMatchResult
}
```

- [ ] **Step 1: Write failing status mapping tests**

Test stable record values rather than obfuscated host types:

```kotlin
@Test
fun missingStatusMapsToOnlineState() {
    assertEquals(HomeSidePanelStatusUiState.NoStatus, mapStatusRecord(null))
}

@Test
fun expiredStatusMapsToOnlineState() {
    assertEquals(
        HomeSidePanelStatusUiState.NoStatus,
        mapStatusRecord(
            StatusRecordValues(
                statusId = "expired",
                description = "old",
                iconId = "1",
                expireTime = 100L,
            ),
            nowEpochSeconds = 101L,
        ),
    )
}

@Test
fun statusRecordMapsDescriptionIconAndEmoji() {
    val emojiInfo = ProtoBuf.encodeToByteArray(
        HomeSidePanelStatusEmojiProto(
            thumbUrl = "https://example.invalid/thumb.webp",
        )
    )
    val state = mapStatusRecord(
        StatusRecordValues(
            statusId = "status-1",
            description = "忙碌中",
            iconId = "1065",
            emojiInfo = emojiInfo,
        )
    ) as HomeSidePanelStatusUiState.Ready
    assertEquals("忙碌中", state.status.description)
    assertEquals("1065", state.status.iconId)
    assertEquals("https://example.invalid/thumb.webp", state.status.emoji?.thumbUrl)
}

@Test
fun malformedEmojiInfoDoesNotHideAnOtherwiseValidStatus() {
    val state = mapStatusRecord(
        StatusRecordValues(
            statusId = "status-1",
            description = "忙碌中",
            iconId = "1065",
            emojiInfo = byteArrayOf(0x7f),
        )
    ) as HomeSidePanelStatusUiState.Ready
    assertNull(state.status.emoji)
}
```

Run and verify RED.

- [ ] **Step 2: Extend `SelfProfileField` with the three region codes**

Add `COUNTRY_CODE(12324)`, `PROVINCE_CODE(12325)`, and `CITY_CODE(12326)` without changing existing enum codes or `WeDatabaseApi.getSelfProfileField` behavior.

- [ ] **Step 3: Implement the profile repository on `Dispatchers.IO`**

Read `WeApi.selfWxId`, nickname, avatar URL, and status through the repository. Return a non-empty fallback nickname only after the database lookup fails; preserve a status `Error` instead of converting it to `NoStatus`.

Implement `readWeatherCityFromProfile()` with exactly this mapping:

```kotlin
val country = WeDatabaseApi.getSelfProfileField(SelfProfileField.COUNTRY_CODE, "").toString()
val province = WeDatabaseApi.getSelfProfileField(SelfProfileField.PROVINCE, "").toString()
    .ifBlank { WeDatabaseApi.getSelfProfileField(SelfProfileField.PROVINCE_CODE, "").toString() }
val city = WeDatabaseApi.getSelfProfileField(SelfProfileField.CITY, "").toString()
    .ifBlank { WeDatabaseApi.getSelfProfileField(SelfProfileField.CITY_CODE, "").toString() }
```

Return `UNSUPPORTED_COUNTRY`, `MISSING_REGION`, `MISSING_CITY`, or `NO_MATCH` without changing the selected weather city. Unexpected host/database failures are logged and returned as `READ_ERROR`; Controller displays its exact message.

- [ ] **Step 4: Implement `HomeSidePanelTextStatusApi` with stable DexKit anchors**

Make `HomeSidePanel` implement `IResolveDex`; keep the required delegates on that already-enabled feature so they are resolved only when this feature is loaded. Resolve the TextStatus storage/service using:

- Exact stable strings such as `MicroMsg.TextStatus.TextStatusStorage` and table name `TextStatus`.
- The storage method reached from the service accessor and whose signature accepts one `String`; in 8.0.65 and 8.0.76 source this is the semantic `getLatestStatusByUserName` call (decompiled as `G().b(wxId)`).
- Structural relationships from `TextStatusDoWhatActivity`, `TextStatusDoWhatActivityV2`, and `TextStatusOtherTopicFriendsActivity` to the service accessor.
- Stable record fields `field_UserName`, `field_StatusID`, `field_IconID`, `field_Description`, `field_ExpireTime`, and `field_EmojiInfo`.

Use the delegate names `classTextStatusService`, `classTextStatusRecord`, `methodTextStatusStorageAccessor`, and `methodLatestStatusByUsername` consistently in Task 10. Resolve the service class structurally from the TextStatus UI call chain; at runtime select its static self-typed singleton field with `reflekt`. The record resolver requires the complete stable `field_*` set above; the storage resolver requires `MicroMsg.TextStatus.StatusInfoAffStorage` plus `getLatestStatusByUserName: failed`; the latest-status method takes one `String` and is declared by that resolved storage type.

Resolve the record class by the complete stable field set, then classify the lookup return value at runtime:

```kotlin
private fun unwrapStatusRecord(value: Any): Any =
    if (value.javaClass == resolvedRecordClass) value
    else value.reflekt().firstField { type = resolvedRecordClass }.get()!!
```

Do not encode `q54.f0`, `dj4.m0`, `K1`, `H0`, or generated field names as final anchors. Use `reflekt` for runtime field access. Treat the 8.0.65 concrete record and 8.0.76 wrapper as two adapters behind `HomeSidePanelTextStatusReader`. None of these delegates use `allowFailure`, because TextStatus exists throughout the supported 8.0.65–8.0.76 range.

- [ ] **Step 5: Implement the minimal status EmojiInfo protobuf parser**

Define the exact wire model and decode string fields 1 (`md5`), 2 (`url`), 3 (`thumbUrl`), and 11 (`attachedText`) from the `field_EmojiInfo` byte array using the existing kotlinx.serialization protobuf support:

```kotlin
@Serializable
internal data class HomeSidePanelStatusEmojiProto(
    @ProtoNumber(1) val md5: String = "",
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val thumbUrl: String = "",
    @ProtoNumber(11) val attachedText: String = "",
)
```

Unknown fields are ignored. Catch decode failures inside the optional protobuf parser, log them, and return `null` emoji without failing the whole status.

If a parsed emoji has an `md5` but both protobuf URLs are blank, query `WeServiceApi.getEmojiInfoByMd5(md5)` and read the stable `field_thumbUrl` and `field_cdnUrl` fields through `reflekt`. Use those values as the final image fallback; lookup failure is logged and leaves the status text visible. Map a null record, blank `field_StatusID`, or `field_ExpireTime <= nowEpochSeconds` to `NoStatus`.

- [ ] **Step 6: Run tests and affected DexKit validation**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelStatusMappingTest'
./x dex-test \
  --apk /home/ujhhgtg/coding/wechat_8065.apk \
  --apk /home/ujhhgtg/coding/wechat_8067.apk \
  --apk /home/ujhhgtg/coding/wechat_8069.apk \
  --apk /home/ujhhgtg/coding/wechat_8069_3020_play.apk \
  --apk /home/ujhhgtg/coding/wechat_8074.apk \
  --apk /home/ujhhgtg/coding/wechat_8076.apk
git diff --check
```

The DexKit report must show no unexpected, blocked, or incomplete resolution for each available supported APK. At plan-writing time only 8.0.69 normal/Google Play and 8.0.74 APKs are present at those paths; obtain the missing 8.0.65, 8.0.67, and 8.0.76 APKs before calling the resolver change complete.

- [ ] **Step 7: Commit the profile/status API**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/api/core/models/SelfProfileField.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelProfileRepository.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelTextStatusApi.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelStatusProto.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelStatusMappingTest.kt
git commit -m "feat: expose HomeSidePanel profile and status data"
```

---

### Task 5: Implement Xiaomi Weather networking and cached state

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelWeatherRepository.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelWeatherRepositoryTest.kt`

**Interfaces:**

```kotlin
interface HomeSidePanelWeatherRepository {
    suspend fun loadCached(): WeatherSnapshot?
    suspend fun refresh(city: WeatherCity): WeatherResult
    suspend fun searchCities(query: String): List<WeatherCity>
    fun selectedCity(): WeatherCity
    fun selectCity(city: WeatherCity)
}

internal class DefaultHomeSidePanelWeatherRepository(
    private val preferences: HomeSidePanelPreferences,
    private val cityIndex: HomeSidePanelCityIndex,
    private val client: OkHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : HomeSidePanelWeatherRepository
```

- [ ] **Step 1: Write failing request and parser tests**

```kotlin
@Test
fun weatherRequestUsesXiaomiLocationKeyAndRequiredParameters() {
    val url = buildWeatherUrl(WeatherCity("CN", "北京市", "北京市", null, "101010100"))
    assertEquals("weathercn:101010100", url.queryParameter("locationKey"))
    assertEquals("zUFJoAR2ZVrDy1vF3D07", url.queryParameter("sign"))
    assertEquals("false", url.queryParameter("isGlobal"))
    assertEquals("zh_cn", url.queryParameter("locale"))
    assertEquals("weather20151024", url.queryParameter("appKey"))
    assertEquals("5", url.queryParameter("days"))
}

@Test
fun weatherJsonMapsCurrentAndDailyValues() {
    val snapshot = parseWeatherPayload(BEIJING_CITY, fixtureWeatherJson, fetchedAt = 1234L)
    assertEquals("21", snapshot.temperature)
    assertEquals("22", snapshot.feelsLike)
    assertEquals("1", snapshot.weatherCode)
    assertEquals("25", snapshot.high)
    assertEquals("16", snapshot.low)
    assertEquals(1234L, snapshot.fetchedAt)
}

@Test
fun repeatedWeatherRefreshesShareOneInFlightCall() = runTest {
    val gate = CompletableDeferred<Unit>()
    var requestCount = 0
    val repository = fixtureWeatherRepository(
        responseBody = {
            requestCount++
            gate.await()
            fixtureWeatherJson
        },
    )
    val first = async { repository.refresh(BEIJING_CITY) }
    val second = async { repository.refresh(BEIJING_CITY) }
    runCurrent()
    assertEquals(1, requestCount)
    gate.complete(Unit)
    assertEquals(first.await(), second.await())
}
```

Run and verify RED.

- [ ] **Step 2: Implement DTOs and request construction**

Use `DefaultJson`, which already has `ignoreUnknownKeys = true`. Build the Xiaomi request with latitude/longitude `0`, `locationKey=weathercn:<cityNum>`, `sign=zUFJoAR2ZVrDy1vF3D07`, `isGlobal=false`, `locale=zh_cn`, `days=5`, and `appKey=weather20151024`. Use one lazily-created OkHttp client with 10-second connect/read/call timeouts.

- [ ] **Step 3: Map weather JSON into a stable `WeatherSnapshot`**

Read `current.temperature.value`, `current.feelsLike.value`, `current.humidity.value`, `current.weather`, `current.wind.speed.value`, `current.pubTime`, and the first daily temperature/weather values. Preserve the raw weather code. Map it through a pure `WeatherIconKind` enum (`SUNNY`, `CLOUDY`, `RAIN`, `SNOW`, `FOG`, `THUNDER`, `UNKNOWN`); Task 9 maps that enum to concrete Material Symbols.

- [ ] **Step 4: Add cache and single-flight refresh behavior**

Store the last successful snapshot and selected city in `WePrefs` as JSON. Use an atomic in-flight reference and a one-second minimum refresh timestamp so repeated card clicks share one request and cannot exceed the requested rate.

- [ ] **Step 5: Implement explicit error results**

Return `WeatherResult.Error(message, cachedSnapshot)` for HTTP failure, malformed JSON, empty current data, timeout, or missing city. Never delete a previously selected city on failure.

- [ ] **Step 6: Run tests and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelWeatherRepositoryTest'
git diff --check
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelWeatherRepository.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelWeatherRepositoryTest.kt
git commit -m "feat: add HomeSidePanel weather repository"
```

---

### Task 6: Implement explicit location detection and profile-city actions

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelLocationResolver.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelLocationResolverTest.kt`

**Interfaces:**

```kotlin
sealed interface LocationResolution {
    data object NeedPermission : LocationResolution
    data object LocationDisabled : LocationResolution
    data object Timeout : LocationResolution
    data object GeocoderFailed : LocationResolution
    data object CityNotFound : LocationResolution
    data class Success(val city: WeatherCity) : LocationResolution
    data class Error(val message: String) : LocationResolution
}

interface HomeSidePanelLocationResolver {
    fun hasCoarsePermission(activity: Activity): Boolean
    suspend fun resolve(activity: Activity): LocationResolution
}

internal class AndroidHomeSidePanelLocationResolver(
    private val cityIndex: HomeSidePanelCityIndex,
    private val timeoutMs: Long = 12_000L,
) : HomeSidePanelLocationResolver
```

- [ ] **Step 1: Write failing location-error mapping tests**

```kotlin
@Test
fun missingPermissionRequiresAnExplicitRequest() = runTest {
    assertEquals(LocationResolution.NeedPermission, fixture.resolve(permissionGranted = false))
}

@Test
fun locationFailuresHaveDistinctChineseMessages() {
    assertEquals("请先开启系统定位服务", locationResolutionMessage(LocationResolution.LocationDisabled))
    assertEquals("定位超时，请重试或手动选择城市", locationResolutionMessage(LocationResolution.Timeout))
    assertEquals("无法将当前位置转换为城市", locationResolutionMessage(LocationResolution.GeocoderFailed))
    assertEquals("天气城市库中找不到当前城市", locationResolutionMessage(LocationResolution.CityNotFound))
}

@Test
fun geocodedCityIsMatchedAgainstTheLocalIndex() = runTest {
    val result = fixture.resolve(
        permissionGranted = true,
        province = "北京市",
        city = "北京市",
    )
    assertEquals("101010100", (result as LocationResolution.Success).city.cityNum)
}
```

Run the focused test and verify RED.

- [ ] **Step 2: Implement host permission checks**

Do not add a module manifest permission and assume it applies to WeChat. Check the host package’s declared permissions and `ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)`. If the permission is not declared, return `Error("当前微信版本未声明粗略定位权限，请手动选择城市")` instead of throwing.

- [ ] **Step 3: Implement API-level location retrieval**

Use `LocationManager.getCurrentLocation` on API 30+ and `requestSingleUpdate` with a 12-second timeout on API 28–29. The resolver never opens permission UI itself. Use `Geocoder` on `Dispatchers.IO`, then `HomeSidePanelCityIndex.matchLocation`; translate `WeatherCityMatchResult.Error` into `CityNotFound`.

- [ ] **Step 4: Connect permission resumption without a broad hook**

When `detectWeatherLocation(activity)` receives `NeedPermission`, set `pendingLocationPermission = true` and call:

```kotlin
activity.requestPermissions(
    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
    HOME_SIDE_PANEL_LOCATION_REQUEST_CODE,
)
```

Add `fun resumePendingLocationDetection(activity: Activity)` to the Controller. The existing `LauncherUI.onResume` hook calls it; if permission is now granted it retries resolution, otherwise it clears the pending flag and publishes `"定位权限已拒绝，仍可搜索或手动选择城市"`. Do not add a global Activity or PhoneWindow hook.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelLocationResolverTest'
git diff --check
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelLocationResolver.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelLocationResolverTest.kt
git commit -m "feat: add explicit HomeSidePanel location detection"
```

---

### Task 7: Implement Hitokoto repository, settings, cache, and limiter

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelHitokotoRepository.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelHitokotoRepositoryTest.kt`

**Interfaces:**

```kotlin
interface HomeSidePanelHitokotoRepository {
    fun loadSettings(): HitokotoSettings
    fun saveSettings(settings: HitokotoSettings)
    suspend fun loadCached(): HitokotoSnapshot?
    suspend fun preload(): HitokotoResult
    suspend fun fetchRandom(): HitokotoResult
}

internal class DefaultHomeSidePanelHitokotoRepository(
    private val preferences: HomeSidePanelPreferences,
    private val client: OkHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : HomeSidePanelHitokotoRepository
```

- [ ] **Step 1: Write failing request, parser, and limiter tests**

```kotlin
@Test
fun hitokotoRequestContainsConfiguredCategoriesAndLengths() {
    val url = buildHitokotoUrl(
        HitokotoSettings(
            categories = setOf("a", "d"),
            minLength = 8,
            maxLength = 24,
            charset = "utf-8",
        )
    )
    assertEquals(listOf("a", "d"), url.queryParameterValues("c"))
    assertEquals("json", url.queryParameter("encode"))
    assertEquals("8", url.queryParameter("min_length"))
    assertEquals("24", url.queryParameter("max_length"))
    assertEquals("utf-8", url.queryParameter("charset"))
}

@Test
fun malformedHitokotoResponseReturnsError() = runTest {
    val repository = fixtureRepository(responseBody = "{not-json")
    val result = repository.fetchRandom()
    assertEquals("一言数据解析失败", (result as HitokotoResult.Error).message)
}

@Test
fun repeatedFetchesWithinOneSecondShareTheInFlightRequest() = runTest {
    val gate = CompletableDeferred<Unit>()
    var requestCount = 0
    val repository = fixtureRepository(
        nowMs = { 1_000L },
        responseBody = {
            requestCount++
            gate.await()
            validHitokotoJson
        },
    )
    val first = async { repository.fetchRandom() }
    val second = async { repository.fetchRandom() }
    runCurrent()
    assertEquals(1, requestCount)
    gate.complete(Unit)
    assertEquals(first.await(), second.await())
}
```

Use a fake HTTP transport so tests do not contact the public service. Run and verify RED.

- [ ] **Step 2: Implement settings serialization and validation**

Persist `HitokotoSettings` through the preference key from Task 2. Reject empty/unknown categories, negative lengths, unsupported charsets, and `maxLength < minLength` before any network request. Category order in the URL is always `a` through `l`, independent of Set iteration order.

- [ ] **Step 3: Implement the official JSON request**

Build `https://v1.hitokoto.cn/?encode=json` with repeated `c` parameters and optional `min_length`, `max_length`, and `charset`. Decode `uuid`, `hitokoto`, `type`, `from`, `from_who`, `creator`, `created_at`, and `length` with `DefaultJson`; map `hitokoto` to `HitokotoSnapshot.text`, `from` to `source`, and `from_who` to `author`.

- [ ] **Step 4: Implement preload, cache, single-flight, and one-second limiter**

Inject the HTTP call and monotonic clock into the repository constructor for JVM tests. Load the last successful snapshot first. `preload()` always attempts one new request after the cache is made available. Calls while a request is running await the same `Deferred`; calls before `lastStartedAt + 1_000` return the cached success (or the most recent error if no cache) without creating another HTTP call. A successful response updates `HITOKOTO_LAST_SUCCESS` before completing the shared deferred.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelHitokotoRepositoryTest'
git diff --check
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelHitokotoRepository.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelHitokotoRepositoryTest.kt
git commit -m "feat: add HomeSidePanel Hitokoto repository"
```

---

### Task 8: Add the session-scoped controller and navigation actions

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelController.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelNavigator.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelControllerRulesTest.kt`

**Interfaces:**

```kotlin
enum class HomeSidePanelShortcut {
    SCAN,
    PAYMENTS,
    FAVORITES,
    MOMENTS,
    VIDEO_CHANNELS,
    MARK_ALL_READ,
    WEKIT_SETTINGS,
}

interface HomeSidePanelNavigator {
    fun closePanel()
    fun openShortcut(shortcut: HomeSidePanelShortcut)
}

class HomeSidePanelController(
    private val profileRepository: HomeSidePanelProfileRepository,
    private val weatherRepository: HomeSidePanelWeatherRepository,
    private val hitokotoRepository: HomeSidePanelHitokotoRepository,
    private val locationResolver: HomeSidePanelLocationResolver,
    private val navigator: HomeSidePanelNavigator,
    private val scope: CoroutineScope,
) {
    val uiState: StateFlow<HomeSidePanelUiState>
    fun startPreload()
    fun onPanelOpened()
    fun refreshStatus()
    fun refreshWeather()
    fun readWeatherFromProfile()
    fun searchWeatherCities(query: String)
    fun selectWeatherCity(city: WeatherCity)
    fun detectWeatherLocation(activity: Activity)
    fun resumePendingLocationDetection(activity: Activity)
    fun openWeatherSettings()
    fun openHitokotoSettings()
    fun closeCardSettings()
    fun fetchAnotherHitokoto()
    fun saveHitokotoSettings(settings: HitokotoSettings)
    fun runShortcut(shortcut: HomeSidePanelShortcut)
    fun close()
}
```

- [ ] **Step 1: Write failing pure controller-state tests**

```kotlin
@Test
fun profileCityFailureKeepsBeijingAndPublishesTheExactMessage() = runTest {
    val controller = fixtureController(
        profileCityResult = WeatherCityMatchResult.Error(WeatherCityMatchFailure.UNSUPPORTED_COUNTRY)
    )
    controller.startPreload()
    advanceUntilIdle()
    assertEquals("101010100", controller.uiState.value.weatherSettings.selectedCity.cityNum)
    assertEquals("不支持的资料地区", controller.uiState.value.weatherSettings.message)
}

@Test
fun statusFailureIsNeverConvertedToOnline() = runTest {
    val controller = fixtureController(
        status = HomeSidePanelStatusUiState.Error("boom")
    )
    controller.startPreload()
    advanceUntilIdle()
    assertIs<HomeSidePanelStatusUiState.Error>(controller.uiState.value.profile.status)
}

@Test
fun cardModesAndShortcutNavigationAreDeterministic() {
    val navigator = RecordingNavigator()
    val controller = fixtureController(navigator = navigator)
    controller.openWeatherSettings()
    assertEquals(HomeSidePanelCardMode.WEATHER_SETTINGS, controller.uiState.value.cardMode)
    controller.openHitokotoSettings()
    assertEquals(HomeSidePanelCardMode.HITOKOTO_SETTINGS, controller.uiState.value.cardMode)
    controller.runShortcut(HomeSidePanelShortcut.SCAN)
    assertEquals(listOf("close", "SCAN"), navigator.events)
}
```

Run and verify RED.

- [ ] **Step 2: Implement the reducer/state holder**

Use a `MutableStateFlow<HomeSidePanelUiState>` initialized with fallback profile (`wxId=""`, nickname `"微信用户"`, empty avatar, status `Loading`), `DEFAULT_WEATHER_CITY`, and independent weather/Hitokoto loading states. Keep repository calls in child coroutines under `SupervisorJob`; one repository failure updates only its own state branch.

- [ ] **Step 3: Implement initialization sequencing**

`startPreload()` must:

1. Load cached weather and Hitokoto values.
2. Publish them immediately if present.
3. Launch profile/status refresh.
4. If the profile-initialized preference is false, attempt `profileRepository.readWeatherCityFromProfile()`, preserve Beijing on failure, save the exact message, and set the preference only after either a successful selection or a recorded terminal failure.
5. Launch fresh weather and Hitokoto requests without blocking panel attachment.

`onPanelOpened()` starts one fresh identity/status load for that opening transition; it does not discard visible weather/Hitokoto cache or restart their requests.

- [ ] **Step 4: Implement settings and shortcut actions**

Long-press changes `cardMode`; save returns to `CONTENT` only after validation and persistence. `runShortcut` calls `navigator.closePanel()` before the target action.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelControllerRulesTest'
git diff --check
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelController.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelNavigator.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelControllerRulesTest.kt
git commit -m "feat: orchestrate HomeSidePanel content state"
```

---

### Task 9: Build the Material 3 Compose content and settings pages

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelContent.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelSettingsContent.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelShortcutMappingTest.kt`
- Visual reference (read-only): `/home/ujhhgtg/Downloads/photo_2026-07-23_15-13-37.jpg`

**Interfaces:**

```kotlin
enum class HomeSidePanelIconKind {
    QR_CODE_SCANNER,
    PAYMENTS,
    COLLECTIONS_BOOKMARK,
    PHOTO_LIBRARY,
    VIDEO_LIBRARY,
    MARK_EMAIL_READ,
    SETTINGS,
}

data class HomeSidePanelShortcutSpec(
    val shortcut: HomeSidePanelShortcut,
    val label: String,
    val icon: HomeSidePanelIconKind,
)

internal fun shortcutSpec(shortcut: HomeSidePanelShortcut): HomeSidePanelShortcutSpec

@Composable
fun HomeSidePanelContent(
    state: HomeSidePanelUiState,
    controller: HomeSidePanelController,
)
```

- [ ] **Step 1: Write failing shortcut/icon mapping tests**

```kotlin
@Test
fun everyShortcutHasTheApprovedLabelAndSemanticIcon() {
    assertEquals(
        listOf(
            Triple(HomeSidePanelShortcut.SCAN, "扫一扫", HomeSidePanelIconKind.QR_CODE_SCANNER),
            Triple(HomeSidePanelShortcut.PAYMENTS, "收付款", HomeSidePanelIconKind.PAYMENTS),
            Triple(HomeSidePanelShortcut.FAVORITES, "收藏", HomeSidePanelIconKind.COLLECTIONS_BOOKMARK),
            Triple(HomeSidePanelShortcut.MOMENTS, "朋友圈", HomeSidePanelIconKind.PHOTO_LIBRARY),
            Triple(HomeSidePanelShortcut.VIDEO_CHANNELS, "视频号", HomeSidePanelIconKind.VIDEO_LIBRARY),
            Triple(HomeSidePanelShortcut.MARK_ALL_READ, "清空未读", HomeSidePanelIconKind.MARK_EMAIL_READ),
            Triple(HomeSidePanelShortcut.WEKIT_SETTINGS, "WeKit 设置", HomeSidePanelIconKind.SETTINGS),
        ),
        HomeSidePanelShortcut.entries.map {
            shortcutSpec(it).let { spec -> Triple(spec.shortcut, spec.label, spec.icon) }
        },
    )
}
```

Run and verify RED.

- [ ] **Step 2: Implement the profile header**

Use Coil 3 `AsyncImage` for a non-empty avatar URL and a rounded first-character placeholder otherwise. Show the nickname as the primary line and `wxId` as the secondary line. Render status as follows:

- `NoStatus`: green dot + `在线`.
- `Ready`: optional emoji image + description.
- `Error`: red close icon + `获取失败` + trailing refresh `IconButton`.
- `Loading`: compact progress indicator.

For `Ready`, use `emoji.thumbUrl`, then `emoji.url`; if neither exists, display the status description without reserving an empty image slot. The text-status refresh icon is always available only in `Error`.

- [ ] **Step 3: Implement the weather card**

Keep the supplied reference image’s time/date/greeting hierarchy while showing real city, weather, temperature, feels-like, high/low, humidity, wind, update time, loading, cache, and error states. Make the whole card clickable for refresh and `combinedClickable` long-click switch to weather settings. Map `WeatherIconKind` to `MaterialSymbols.OutlinedFilled.Sunny`, `Cloudy`, `Rainy`, `Weather_snowy`, `Foggy`, `Thunderstorm`, or `Question_mark`.

- [ ] **Step 4: Implement real Tile/ListItem actions**

Use Material 3 `Card`, `ListItem`, `Icon`, `IconButton`, and `Surface`. Map the pure icon kinds to the exact available Material Symbols imports: `Qr_code_scanner`, `Payments`, `Collections_bookmark`, `Photo_library`, `Video_library`, `Mark_email_read`, and `Settings`. Wire the seven `HomeSidePanelShortcut` values through the navigator. Remove the old placeholder note entirely.

- [ ] **Step 5: Implement the Hitokoto card and in-card settings mode**

Normal mode displays quote text, optional source/author, and refresh affordance, using the same visual reference as the weather card. Long press switches to a settings layout with back, category `FilterChip`s (`a` 动画, `b` 漫画, `c` 游戏, `d` 文学, `e` 原创, `f` 网络, `g` 其他, `h` 影视, `i` 诗词, `j` 网易云, `k` 哲学, `l` 抖机灵), length fields, an `utf-8`/`gbk` charset selector, source/author switches, restore-default, and save. Weather settings show search results plus adjacent `自动检测` and `从个人资料读取` buttons. Use exact available symbols `Format_quote`, `Refresh`, `Settings`, `Arrow_back`, `My_location`, and `Person_pin`.

- [ ] **Step 6: Apply `InjectedUiTheme` and dark-mode semantics**

Read all colors from `MaterialTheme.colorScheme`, use Material 3 error containers for failures, and apply `WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues()` because LauncherUI is edge-to-edge. Do not hard-code separate light/dark palettes inside the feature.

- [ ] **Step 7: Run UI mapping tests and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.HomeSidePanelShortcutMappingTest'
git diff --check
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelContent.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelSettingsContent.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelShortcutMappingTest.kt
git commit -m "feat: add HomeSidePanel Material 3 content"
```

---

### Task 10: Integrate Controller/UI into the existing drawer Session

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`
- Create or modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelSession.kt`
- Modify: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelGestureStateTest.kt`

**Interfaces:**
- Existing `HomeSidePanel` Hook object remains the feature entrypoint.
- `HomeSidePanelSession` owns one `HomeSidePanelController` and passes it to `HomeSidePanelContent`.

- [ ] **Step 1: Extract Session-only code without behavior changes**

Move the current nested Session, overlay, transform, and external-chrome helpers into `HomeSidePanelSession.kt`. Keep all existing helper names and tests green before connecting new data.

- [ ] **Step 2: Add Session-owned Controller construction**

Construct repositories and a `SupervisorJob + Dispatchers.Main.immediate` scope when the Session attaches. Create a `HomeSidePanelNavigator` that closes the drawer and launches the verified shortcut targets:

```kotlin
val cityIndex = AssetHomeSidePanelCityIndex(activity)
val textStatusReader = HomeSidePanelTextStatusApi(
    serviceClass = classTextStatusService.clazz,
    storageAccessor = methodTextStatusStorageAccessor.method,
    latestStatusMethod = methodLatestStatusByUsername.method,
    recordClass = classTextStatusRecord.clazz,
)
val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.SECONDS)
    .build()
val profileRepository = HomeSidePanelProfileRepository(textStatusReader, cityIndex)
val weatherRepository = DefaultHomeSidePanelWeatherRepository(HomeSidePanelPreferences, cityIndex, client)
val hitokotoRepository = DefaultHomeSidePanelHitokotoRepository(HomeSidePanelPreferences, client)
val locationResolver = AndroidHomeSidePanelLocationResolver(cityIndex)
```

Pass those instances, the navigator, and the Session scope to `HomeSidePanelController`.

- `SCAN`: `Intent.setClassName(activity.packageName, "com.tencent.mm.plugin.scanner.ui.BaseScanUI")`.
- `PAYMENTS`: first `com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI`; if the host package cannot resolve it, use `com.tencent.mm.plugin.mall.ui.MallIndexUIv2`.
- `FAVORITES`: `com.tencent.mm.plugin.fav.ui.FavoriteIndexUI`.
- `MOMENTS`: `WeApi.openMoments(activity, WeApi.selfWxId)`.
- `VIDEO_CHANNELS`: `com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI`.
- `MARK_ALL_READ`: `WeConversationApi.markAllAsRead()`.
- `WEKIT_SETTINGS`: `activity.startActivity(Intent(activity, SettingsActivity::class.java))`.

For explicit host activities, use `Intent.setClassName`; do not call `getIdentifier`, infer a class from a resource ID, or add a new DexKit resolver.

- [ ] **Step 3: Start preloading before Compose content is first shown**

Call `controller.startPreload()` before `panelView.setContent`. Inside the existing `InjectedUiTheme`, collect `controller.uiState` with lifecycle awareness and pass the current value to `HomeSidePanelContent(state, controller)`.

- [ ] **Step 4: Route drawer-open and panel callbacks**

Track `wasPanelVisible`. Call `controller.onPanelOpened()` on each transition from `progress <= CLOSED_EPSILON` to `progress > CLOSED_EPSILON`, and reset the flag only after the panel fully closes. Route status refresh, weather refresh, profile-city action, location action, city selection, Hitokoto click, long press, settings save, and shortcut callbacks through the Controller.

- [ ] **Step 5: Resume pending location permission checks**

Add a direct `LauncherUI.onResume()` hook next to the existing `onCreate`, `onBackPressed`, and `onDestroy` hooks. Find the Session owned by that Activity and call `controller.resumePendingLocationDetection(activity)`. Do not add a global Activity or PhoneWindow hook.

- [ ] **Step 6: Dispose all Controller work on detach**

Call `controller.close()` before disposing the Compose composition, then remove callbacks, restore the ActionBar/FAB transforms and original parents, restore wrapped content, and remove the overlay. Preserve the current Session cleanup order for host views.

- [ ] **Step 7: Run the complete feature test set and commit**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.*'
git diff --check
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelSession.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelGestureStateTest.kt
git commit -m "feat: connect HomeSidePanel content to drawer session"
```

---

### Task 11: Full verification and handoff

**Files:**
- Modify only files required by failing verification output.
- Preserve all unrelated dirty files and previous commits.

- [ ] **Step 1: Run focused JVM tests without filters**

```bash
./gradlew :app:testStandardDebugUnitTest
```

Expected: all existing and new tests pass with no warnings that indicate a feature failure.

- [ ] **Step 2: Run `git diff --check` and inspect the complete worktree**

```bash
git diff --check
git status --short
git log --oneline 33c1f6d9..HEAD
git diff --stat 33c1f6d9..HEAD
```

Confirm every post-spec commit belongs to one plan task and that the pre-existing dirty `AddMainScreenFab.kt` and `DexKitUtils.kt` changes were neither overwritten nor accidentally staged with unrelated work.

- [ ] **Step 3: Run affected supported-version DexKit tests**

```bash
./x dex-test \
  --apk /home/ujhhgtg/coding/wechat_8065.apk \
  --apk /home/ujhhgtg/coding/wechat_8067.apk \
  --apk /home/ujhhgtg/coding/wechat_8069.apk \
  --apk /home/ujhhgtg/coding/wechat_8069_3020_play.apk \
  --apk /home/ujhhgtg/coding/wechat_8074.apk \
  --apk /home/ujhhgtg/coding/wechat_8076.apk
```

Confirm 8.0.65, 8.0.67, 8.0.69 normal/Google Play, 8.0.74, and 8.0.76 reports have no unexpected, blocked, or incomplete failures. Missing supported APKs are a release blocker for the TextStatus resolver, not a reason to silently skip a version.

- [ ] **Step 4: Run the canonical build**

```bash
./x build
```

Do not substitute a direct Gradle build because it can package a stale native library.

- [ ] **Step 5: Perform device acceptance checks**

On each available supported WeChat build, verify drawer interaction first, then:

1. Real avatar/nickname/status; no-status green dot + `在线`; failure red close + `获取失败` + refresh button.
2. Dark and light themes.
3. Hitokoto preload before opening, click refresh, long-press settings, validation, save, cache, offline retry.
4. Weather cache/default Beijing, profile-city initialization, “从个人资料读取”, city search, explicit location permission, denial fallback, refresh, long-press settings.
5. Real shortcut navigation and panel close-before-navigation.
6. No regression to return-key interception, edge-to-edge, FAB dim/transform, or ActionBarContainer layering.

- [ ] **Step 6: Commit only verification-driven fixes and report evidence**

For any required fix, add a focused failing test first, implement the smallest change, rerun the relevant test/build, and commit it separately. Report exact commands and results before claiming completion.
