package dev.ujhhgtg.wekit.i18n

import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.preferences.WePrefs
import java.util.Locale

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

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() = Unit
}

private fun LocaleList.toLocaleList(): List<Locale> =
    List(size()) { index -> get(index) }
