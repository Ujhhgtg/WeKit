package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.icu.text.Transliterator
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

internal object AndroidIcuCityQueryTransliterator : CityQueryTransliterator {

    private val transliterator: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Transliterator.getInstance("Han-Latin; Latin-ASCII")
        } else {
            null
        }
    }

    override fun transliterate(value: String): String =
        transliterator?.let { synchronized(it) { it.transliterate(value) } } ?: value
}

internal class HomeSidePanelCityMatcher(
    private val cities: List<WeatherCity>,
    private val transliterator: CityQueryTransliterator,
) {

    fun search(query: String): List<WeatherCity> {
        val normalizedQuery = normalizeSearchValue(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        return cities.asSequence()
            .filter { city ->
                city.searchValues().any { value ->
                    normalizeSearchValue(value).contains(normalizedQuery) ||
                        normalizeSearchValue(transliterator.transliterate(value)).contains(normalizedQuery)
                }
            }
            .distinctBy { it.labelKey() }
            .toList()
    }

    fun matchProfile(
        countryCode: String,
        province: String,
        city: String,
    ): WeatherCityMatchResult {
        if (!isEligibleWeatherCountry(countryCode)) {
            return WeatherCityMatchResult.Error(WeatherCityMatchFailure.UNSUPPORTED_COUNTRY)
        }
        if (province.isBlank()) {
            return WeatherCityMatchResult.Error(WeatherCityMatchFailure.MISSING_REGION)
        }
        if (city.isBlank()) {
            return WeatherCityMatchResult.Error(WeatherCityMatchFailure.MISSING_CITY)
        }

        return match(
            countryCode = countryCode.trim().uppercase(),
            province = province,
            city = city,
        )
    }

    fun matchLocation(province: String, city: String): WeatherCityMatchResult {
        if (city.isBlank()) {
            return WeatherCityMatchResult.Error(WeatherCityMatchFailure.MISSING_CITY)
        }
        return match(countryCode = null, province = province, city = city)
    }

    private fun match(
        countryCode: String?,
        province: String,
        city: String,
    ): WeatherCityMatchResult {
        val normalizedProvince = normalizeRegionValue(province)
        val normalizedCity = normalizeRegionValue(city)
        val countryMatches: (WeatherCity) -> Boolean = { candidate ->
            countryCode == null || candidate.countryCode == countryCode
        }
        val provinceMatches: (WeatherCity) -> Boolean = { candidate ->
            normalizeRegionValue(candidate.province) == normalizedProvince
        }

        val matched = cities.firstOrNull { candidate ->
            countryMatches(candidate) && provinceMatches(candidate) &&
                candidate.normalizedCombinedCity() == normalizedCity
        } ?: cities.firstOrNull { candidate ->
            countryMatches(candidate) && provinceMatches(candidate) &&
                normalizeRegionValue(candidate.city) == normalizedCity
        } ?: cities.firstOrNull { candidate ->
            countryMatches(candidate) && candidate.matchesCityValue(normalizedCity)
        } ?: cities.firstOrNull { candidate ->
            candidate.matchesCityValue(normalizedCity)
        }

        return matched?.let(WeatherCityMatchResult::Success)
            ?: WeatherCityMatchResult.Error(WeatherCityMatchFailure.NO_MATCH)
    }

    private fun WeatherCity.matchesCityValue(value: String): Boolean =
        normalizedCombinedCity() == value ||
            normalizeRegionValue(city) == value ||
            district?.let(::normalizeRegionValue) == value

    private fun WeatherCity.normalizedCombinedCity(): String =
        normalizeRegionValue(city + district.orEmpty())

    private fun WeatherCity.searchValues(): List<String> = listOf(
        province,
        city,
        city + district.orEmpty(),
        province + city + district.orEmpty(),
    )

    private fun WeatherCity.labelKey(): String =
        listOf(countryCode, province, city, district.orEmpty()).joinToString("\u0000")
}

internal class AssetHomeSidePanelCityIndex(
    context: Context,
    transliterator: CityQueryTransliterator = AndroidIcuCityQueryTransliterator,
) : HomeSidePanelCityIndex {

    private val appContext = context.applicationContext
    private val matcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HomeSidePanelCityMatcher(loadCities(), transliterator)
    }

    override suspend fun search(query: String): List<WeatherCity> = withContext(Dispatchers.IO) {
        matcher.search(query)
    }

    override suspend fun matchProfile(
        countryCode: String,
        province: String,
        city: String,
    ): WeatherCityMatchResult = withContext(Dispatchers.IO) {
        matcher.matchProfile(countryCode, province, city)
    }

    override suspend fun matchLocation(
        province: String,
        city: String,
    ): WeatherCityMatchResult = withContext(Dispatchers.IO) {
        matcher.matchLocation(province, city)
    }

    private fun loadCities(): List<WeatherCity> {
        val databaseFile = copyDatabaseAssetOnce()
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        return database.use { db ->
            db.rawQuery(CITY_QUERY, null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val provinceId = cursor.getInt(0)
                        val rawName = cursor.getString(1)
                        val separatorIndex = rawName.indexOf('.')
                        val city = if (separatorIndex < 0) rawName else rawName.substring(0, separatorIndex)
                        val district = if (separatorIndex < 0) null else rawName.substring(separatorIndex + 1)
                        add(
                            WeatherCity(
                                countryCode = provinceId.toCountryCode(),
                                province = cursor.getString(3),
                                city = city,
                                district = district,
                                cityNum = cursor.getString(2),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun copyDatabaseAssetOnce(): File {
        val directory = File(appContext.noBackupFilesDir, ASSET_DIRECTORY).apply { mkdirs() }
        val databaseFile = File(directory, ASSET_FILE_NAME)
        if (!databaseFile.exists()) {
            appContext.assets.open(ASSET_PATH).use { input ->
                databaseFile.outputStream().use(input::copyTo)
            }
        }
        return databaseFile
    }

    private fun Int.toCountryCode(): String = when (this) {
        31 -> "HK"
        32 -> "MO"
        33 -> "TW"
        else -> "CN"
    }

    private companion object {
        const val ASSET_DIRECTORY = "home_side_panel"
        const val ASSET_FILE_NAME = "xiaomi_weather.db"
        const val ASSET_PATH = "$ASSET_DIRECTORY/$ASSET_FILE_NAME"
        const val CITY_QUERY = """
            SELECT c.province_id, c.name, c.city_num, p.name AS province
            FROM citys c
            LEFT JOIN provinces p ON p._id = c.province_id + 1
            ORDER BY c._id
        """
    }
}

private val WEATHER_REGION_SUFFIXES = listOf(
    "特别行政区",
    "维吾尔自治区",
    "壮族自治区",
    "回族自治区",
    "自治区",
    "省",
    "市",
    "区",
    "县",
)

private fun normalizeRegionValue(value: String): String {
    var normalized = value.trim().lowercase().replace(Regex("\\s+"), "")
    WEATHER_REGION_SUFFIXES.forEach { suffix ->
        normalized = normalized.replace(suffix, "")
    }
    return normalized
}

private fun normalizeSearchValue(value: String): String =
    normalizeRegionValue(value).replace(Regex("[^\\p{L}\\p{N}]"), "")
