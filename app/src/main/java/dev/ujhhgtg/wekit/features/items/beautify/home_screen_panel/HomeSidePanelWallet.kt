package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

data class HomeSidePanelWalletDisplayState(
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

data class HomeSidePanelWalletUiState(
    val balanceFen: Long? = null,
    val displayState: HomeSidePanelWalletDisplayState = HomeSidePanelWalletDisplayState(true),
) {
    fun withBalance(balanceFen: Long?): HomeSidePanelWalletUiState = copy(balanceFen = balanceFen)

    val displayBalance: String
        get() = if (displayState.isMasked) "******" else formatHomeSidePanelWalletBalance(balanceFen)
}

fun formatHomeSidePanelWalletBalance(balanceFen: Long?): String {
    if (balanceFen == null) return "¥ --"
    val formatter = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
    return "¥ ${formatter.format(BigDecimal.valueOf(balanceFen, 2))}"
}

object HomeSidePanelWalletBalanceSource {
    const val BALANCE_KEY = "USERINFO_NEW_BALANCE_LONG_SYNC"

    private val lock = Any()
    private val _updates = MutableStateFlow<Long?>(null)
    private var readBalance: (() -> Long?)? = null

    val updates: StateFlow<Long?> = _updates.asStateFlow()

    fun install(reader: () -> Long?) {
        synchronized(lock) {
            readBalance = reader
            _updates.value = null
        }
    }

    fun clear() {
        synchronized(lock) {
            readBalance = null
            _updates.value = null
        }
    }

    fun read(): Long? = synchronized(lock) {
        readBalance?.invoke()
    }

    fun refresh() {
        synchronized(lock) {
            _updates.value = readBalance?.invoke()
        }
    }

    fun onCacheWrite(key: Any?, value: Any?) {
        synchronized(lock) {
            if ((key as? Enum<*>)?.name != BALANCE_KEY) return
            if (value !is Long) return
            _updates.value = value
        }
    }
}

fun readHomeSidePanelWalletBalance(
    walletCacheReadMethod: Method,
    walletPayPluginClass: Class<*>,
): Long? {
    val walletPayService = WeServiceApi.getServiceByClass(walletPayPluginClass.interfaces[0])
    val walletCache = walletPayService.reflekt().firstMethod {
        parameters()
        returnType = walletCacheReadMethod.declaringClass
    }.invoke()!!
    val balanceKey = walletCacheReadMethod.parameterTypes[0].enumConstants!!
        .single { (it as Enum<*>).name == HomeSidePanelWalletBalanceSource.BALANCE_KEY }
    return walletCacheReadMethod.invoke(walletCache, balanceKey, null) as? Long
}
