package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.SelfProfileField
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface HomeSidePanelTextStatusReader {
    fun read(wxId: String): HomeSidePanelStatusUiState
}

open class HomeSidePanelProfileRepository(
    private val statusReader: HomeSidePanelTextStatusReader,
    private val cityIndex: HomeSidePanelCityIndex,
) {

    open suspend fun loadIdentity(): HomeSidePanelProfile = withContext(Dispatchers.IO) {
        val wxId = WeApi.selfWxId
        val nickname = WeDatabaseApi.getSelfProfileField(SelfProfileField.NAME, "")
            .toString()
            .ifBlank { "微信用户" }
        HomeSidePanelProfile(
            wxId = wxId,
            nickname = nickname,
            avatarUrl = WeDatabaseApi.getAvatarUrl(wxId),
            status = statusReader.read(wxId),
        )
    }

    open suspend fun refreshStatus(): HomeSidePanelStatusUiState = withContext(Dispatchers.IO) {
        statusReader.read(WeApi.selfWxId)
    }

    open suspend fun readWeatherCityFromProfile(): WeatherCityMatchResult = withContext(Dispatchers.IO) {
        try {
            val country = WeDatabaseApi.getSelfProfileField(SelfProfileField.COUNTRY_CODE, "").toString()
            val province = WeDatabaseApi.getSelfProfileField(SelfProfileField.PROVINCE, "").toString()
                .ifBlank {
                    WeDatabaseApi.getSelfProfileField(SelfProfileField.PROVINCE_CODE, "").toString()
                }
            val city = WeDatabaseApi.getSelfProfileField(SelfProfileField.CITY, "").toString()
                .ifBlank {
                    WeDatabaseApi.getSelfProfileField(SelfProfileField.CITY_CODE, "").toString()
                }
            cityIndex.matchProfile(country, province, city)
        } catch (throwable: Throwable) {
            WeLogger.e(TAG, "failed to read weather city from WeChat profile", throwable)
            WeatherCityMatchResult.Error(WeatherCityMatchFailure.READ_ERROR)
        }
    }

    private companion object {
        const val TAG = "HomeSidePanelProfileRepository"
    }
}
