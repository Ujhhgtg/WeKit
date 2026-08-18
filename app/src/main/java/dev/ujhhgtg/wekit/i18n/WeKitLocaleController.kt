package dev.ujhhgtg.wekit.i18n

import android.app.Application
import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.preferences.WePrefs
import java.util.Locale

object WeKitLocaleController : ComponentCallbacks {
    private var initialized = false
    private var hostPreferencesAvailable = false
    private lateinit var application: Application
    private var systemLocales by mutableStateOf(emptyList<Locale>())

    var selection by mutableStateOf(LanguageSelection.SYSTEM)
        private set

    val resolvedLocale: SupportedLocale
        get() = LocaleResolver.resolve(selection, systemLocales)

    private val systemLocaleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshSystemLocales()
        }
    }

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
        this.application = application
        hostPreferencesAvailable = useHostPreferences
        selection = if (useHostPreferences) {
            LanguageSelection.fromStored(WePrefs.getString(Preferences.UI_LANGUAGE))
        } else {
            LanguageSelection.SYSTEM
        }
        refreshSystemLocales()
        application.registerComponentCallbacks(this)
        ContextCompat.registerReceiver(
            application,
            systemLocaleReceiver,
            IntentFilter(Intent.ACTION_LOCALE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        initialized = true
    }

    fun updateSelection(value: LanguageSelection) {
        if (hostPreferencesAvailable) {
            WePrefs.putString(Preferences.UI_LANGUAGE, value.storedValue)
        }
        selection = value
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        refreshSystemLocales()
    }

    private fun refreshSystemLocales() {
        // WeChat can retain or rewrite its own Application resource configuration. The system
        // locale service remains authoritative and is already current when locale callbacks run.
        // Resources.getSystem() is the fallback for Android releases before LocaleManager.
        val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            Resources.getSystem().configuration.locales
        }
        systemLocales = locales.toLocaleList()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() = Unit
}

private fun LocaleList.toLocaleList(): List<Locale> =
    List(size()) { index -> get(index) }
