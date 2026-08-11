package dev.ujhhgtg.wekit.features.items.home_screen_menu

import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

internal fun localizedHomeMenuString(@StringRes id: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(id, *formatArgs)
