package dev.ujhhgtg.wekit.features.items.payment

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

internal fun localizedPaymentString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.paymentLocalizedContext().getString(id, *formatArgs)

internal fun Context.localizedPaymentString(@StringRes id: Int, vararg formatArgs: Any): String =
    paymentLocalizedContext().getString(id, *formatArgs)

internal fun localizedPaymentQuantityString(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.paymentLocalizedContext().resources.getQuantityString(
    id,
    quantity,
    *formatArgs,
)

internal fun Context.localizedPaymentQuantityString(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = paymentLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.paymentLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
