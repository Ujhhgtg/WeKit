package dev.ujhhgtg.wekit.features.items.moments

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

internal fun localizedMomentsString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedMomentsString(id, *formatArgs)

internal fun Context.localizedMomentsString(@StringRes id: Int, vararg formatArgs: Any): String =
    momentsLocalizedContext().getString(id, *formatArgs)

internal fun localizedMomentsQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.momentsLocalizedContext().resources.getQuantityString(
    id,
    quantity,
    *formatArgs,
)

private fun Context.momentsLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
