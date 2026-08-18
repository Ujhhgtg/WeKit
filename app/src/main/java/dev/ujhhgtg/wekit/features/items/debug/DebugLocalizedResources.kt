package dev.ujhhgtg.wekit.features.items.debug

import android.content.Context
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

internal fun localizedDebugString(@StringRes id: Int, vararg args: Any): String =
    HostInfo.application.debugLocalizedContext().getString(id, *args)

internal fun Context.localizedDebugString(@StringRes id: Int, vararg args: Any): String =
    debugLocalizedContext().getString(id, *args)

private fun Context.debugLocalizedContext(): Context = LocalizedContextFactory.create(
    this,
    WeKitLocaleController.resolvedLocale,
    LocaleResourceMode.InjectedHost,
)
