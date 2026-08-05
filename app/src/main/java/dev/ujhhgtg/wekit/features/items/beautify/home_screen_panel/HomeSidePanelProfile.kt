package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.features.api.core.TextStatus
import dev.ujhhgtg.wekit.features.api.core.TextStatusResult
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeTextStatusApi
import dev.ujhhgtg.wekit.features.api.core.models.SelfProfileField
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HomeSidePanelProfile(
    val wxId: String,
    val nickname: String,
    val avatarUrl: String,
    val status: HomeSidePanelStatusUiState,
)

internal sealed interface HomeSidePanelStatusUiState {
    data object Loading : HomeSidePanelStatusUiState
    data class Ready(val status: TextStatus) : HomeSidePanelStatusUiState
    data object NoStatus : HomeSidePanelStatusUiState
    data class Error(val message: String) : HomeSidePanelStatusUiState
}

internal class HomeSidePanelProfileLoader(
    private val cityIndex: HomeSidePanelCityIndex,
) {

    suspend fun loadAccountId(): String = withContext(Dispatchers.IO) {
        WeApi.selfWxId
    }

    suspend fun loadIdentity(): HomeSidePanelProfile = withContext(Dispatchers.IO) {
        val wxId = WeApi.selfWxId
        val nickname = WeDatabaseApi.getSelfProfileField(SelfProfileField.NAME, "")
            .toString()
            .ifBlank { "微信用户" }
        HomeSidePanelProfile(
            wxId = wxId,
            nickname = nickname,
            avatarUrl = WeDatabaseApi.getAvatarUrl(wxId),
            status = WeTextStatusApi.read(wxId).toUiState(),
        )
    }

    suspend fun refreshStatus(): HomeSidePanelStatusUiState = withContext(Dispatchers.IO) {
        WeTextStatusApi.read(WeApi.selfWxId).toUiState()
    }

    suspend fun readWeatherCityFromProfile(): WeatherCityMatchResult = withContext(Dispatchers.IO) {
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
        } catch (error: CancellationException) {
            throw error
        } catch (throwable: Throwable) {
            WeLogger.e(TAG, "failed to read weather city from WeChat profile", throwable)
            WeatherCityMatchResult.Error(WeatherCityMatchFailure.READ_ERROR)
        }
    }

    private companion object {
        const val TAG = "HomeSidePanelProfile"
    }
}

private fun TextStatusResult.toUiState(): HomeSidePanelStatusUiState = when (this) {
    is TextStatusResult.Ready -> HomeSidePanelStatusUiState.Ready(status)
    TextStatusResult.NoStatus -> HomeSidePanelStatusUiState.NoStatus
    is TextStatusResult.Error -> HomeSidePanelStatusUiState.Error("获取失败")
}
