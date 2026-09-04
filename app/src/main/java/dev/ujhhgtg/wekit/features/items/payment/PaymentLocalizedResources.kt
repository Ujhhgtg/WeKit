package dev.ujhhgtg.wekit.features.items.payment

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

sealed interface PaymentUiText {
    data class Resource(@StringRes val resourceId: Int) : PaymentUiText
    data class Raw(val value: String) : PaymentUiText
}

@Composable
fun PaymentUiText.resolve(): String = when (this) {
    is PaymentUiText.Resource -> stringResource(resourceId)
    is PaymentUiText.Raw -> value
}

fun localizedPaymentString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.paymentLocalizedContext().getString(id, *formatArgs)

fun Context.localizedPaymentString(@StringRes id: Int, vararg formatArgs: Any): String =
    paymentLocalizedContext().getString(id, *formatArgs)

fun localizedPaymentQuantityString(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.paymentLocalizedContext().resources.getQuantityString(
    id,
    quantity,
    *formatArgs,
)

fun Context.localizedPaymentQuantityString(
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
