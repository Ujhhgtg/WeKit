package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

internal fun localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedChatString(id, *formatArgs)

internal fun Context.localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    chatLocalizedContext().getString(id, *formatArgs)

internal fun localizedChatQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.localizedChatQuantity(id, quantity, *formatArgs)

internal fun Context.localizedChatQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = chatLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.chatLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
