package dev.ujhhgtg.wekit.features.items.payment

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

sealed interface PaymentUiText {
    data class Resource(@StringRes val resourceId: Int) : PaymentUiText

    data class Raw(val value: String) : PaymentUiText
}

@Composable
fun PaymentUiText.resolve(): String =
    when (this) {
        is PaymentUiText.Resource -> stringResource(resourceId)
        is PaymentUiText.Raw -> value
    }
