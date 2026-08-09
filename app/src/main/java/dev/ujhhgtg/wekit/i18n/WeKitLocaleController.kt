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
    private var initialized = false
    private var hostPreferencesAvailable = false
    private var systemLocales by mutableStateOf(emptyList<Locale>())

    var selection by mutableStateOf(LanguageSelection.SYSTEM)
        private set

    val resolvedLocale: SupportedLocale
        get() = LocaleResolver.resolve(selection, systemLocales)

    /** The standalone module UID cannot access MMKV initialized inside WeChat's UID. */
    fun initializeModuleProcess(application: Application) {
        initialize(application, useHostPreferences = false)
    }

    /** Called only after the injected host process has initialized its MMKV storage. */
    fun initializeInjectedHost(application: Application) {
        initialize(application, useHostPreferences = true)
    }

    private fun initialize(application: Application, useHostPreferences: Boolean) {
        if (initialized) return
        hostPreferencesAvailable = useHostPreferences
        selection = if (useHostPreferences) {
            LanguageSelection.fromStored(WePrefs.getString(Preferences.UI_LANGUAGE))
        } else {
            LanguageSelection.SYSTEM
        }
        systemLocales = application.resources.configuration.locales.toLocaleList()
        application.registerComponentCallbacks(this)
        initialized = true
    }

    fun updateSelection(value: LanguageSelection) {
        if (hostPreferencesAvailable) {
            WePrefs.putString(Preferences.UI_LANGUAGE, value.storedValue)
        }
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
