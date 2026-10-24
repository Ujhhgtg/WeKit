package dev.ujhhgtg.wekit.features.items.beautify

import android.content.Context
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.localizedString

sealed interface BeautifyText {
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) :
        BeautifyText

    data class Raw(val value: String) : BeautifyText
}

fun beautifyText(@StringRes id: Int, vararg args: Any): BeautifyText =
    BeautifyText.Resource(id, args.toList())

fun Context.resolveBeautifyText(text: BeautifyText): String =
    when (text) {
        is BeautifyText.Resource -> localizedString(text.id, *text.args.toTypedArray())
        is BeautifyText.Raw -> text.value
    }
