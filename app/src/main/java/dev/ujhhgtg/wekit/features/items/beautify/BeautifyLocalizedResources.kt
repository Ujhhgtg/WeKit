package dev.ujhhgtg.wekit.features.items.beautify

import android.content.Context
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

fun localizedBeautifyString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedBeautifyString(id, *formatArgs)

fun Context.localizedBeautifyString(@StringRes id: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(id, *formatArgs)

sealed interface BeautifyText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : BeautifyText

    data class Raw(val value: String) : BeautifyText
}

fun beautifyText(@StringRes id: Int, vararg args: Any): BeautifyText =
    BeautifyText.Resource(id, args.toList())

fun Context.resolveBeautifyText(text: BeautifyText): String = when (text) {
    is BeautifyText.Resource -> localizedBeautifyString(text.id, *text.args.toTypedArray())
    is BeautifyText.Raw -> text.value
}
