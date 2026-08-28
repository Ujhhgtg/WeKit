package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

internal fun localizedChatInputString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedChatInputString(id, *formatArgs)

internal fun Context.localizedChatInputString(
    @StringRes id: Int,
    vararg formatArgs: Any,
): String = chatInputLocalizedContext().getString(id, *formatArgs)

internal fun Context.localizedChatInputQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = chatInputLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.chatInputLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
