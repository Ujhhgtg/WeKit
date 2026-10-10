package dev.ujhhgtg.wekit.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector

enum class LocaleResourceMode {
    InjectedHost,
    ModuleApp,
}

object LocalizedContextFactory {
    fun create(
        base: Context,
        locale: SupportedLocale,
        mode: LocaleResourceMode,
    ): Context {
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locale.androidTag))
        }
        val configured = base.createConfigurationContext(configuration)
        val localized = when (mode) {
            LocaleResourceMode.InjectedHost -> configured.also {
                ResourcesInjector.injectModuleRes(it.resources)
            }
            LocaleResourceMode.ModuleApp -> configured
        }
        return if (locale == SupportedLocale.MEOW_CHINESE) {
            MeowResourcesContext(localized)
        } else {
            localized
        }
    }
}
