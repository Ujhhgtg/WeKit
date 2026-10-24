package dev.ujhhgtg.wekit.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector

object LocalizedContextFactory {
    fun create(base: Context, locale: SupportedLocale): Context {
        val configuration =
            Configuration(base.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locale.androidTag))
            }
        val localized =
            base.createConfigurationContext(configuration).also {
                ResourcesInjector.injectModuleRes(it.resources)
            }
        return if (locale == SupportedLocale.MEOW_CHINESE) {
            MeowResourcesContext(localized)
        } else {
            localized
        }
    }
}
