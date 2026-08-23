package dev.ujhhgtg.wekit.features.items.batch

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

internal fun localizedBatchString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedBatchString(id, *formatArgs)

internal fun Context.localizedBatchString(@StringRes id: Int, vararg formatArgs: Any): String =
    batchLocalizedContext().getString(id, *formatArgs)

internal fun localizedBatchQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.localizedBatchQuantity(id, quantity, *formatArgs)

internal fun Context.localizedBatchQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = batchLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.batchLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
