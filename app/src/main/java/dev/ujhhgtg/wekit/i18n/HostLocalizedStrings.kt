package dev.ujhhgtg.wekit.i18n

import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.utils.HostInfo

/**
 * Localized module strings for code that runs in the injected host process
 * outside Compose composition (loader entry points, hook bridges).
 */
object HostLocalizedStrings {
    @JvmStatic
    fun get(@StringRes id: Int, vararg formatArgs: Any): String =
        LocalizedContextFactory.create(
            HostInfo.application,
            WeKitLocaleController.resolvedLocale,
            LocaleResourceMode.InjectedHost,
        ).getString(id, *formatArgs)
}
