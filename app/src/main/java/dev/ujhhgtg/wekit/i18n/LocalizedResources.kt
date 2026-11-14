package dev.ujhhgtg.wekit.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.utils.HostInfo

// Use these helpers to read WeKit resources outside Compose composition. Compose content keeps
// using the standard resource APIs and LocalLocalizedContext.
//
// Resolve strings where they are shown: every call reads LocaleController.resolvedLocale again, so
// a runtime language switch is reflected immediately. Never cache the returned strings in
// long-lived state; carry resource ids, format arguments, or semantic state across async boundaries
// instead.

/** Reads a WeKit string, using the application of the current process as the base context. */
fun localizedString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedString(id, *formatArgs)

/** Reads a WeKit string, using [this] as the platform base context. */
fun Context.localizedString(@StringRes id: Int, vararg formatArgs: Any): String =
    localizedContext().getString(id, *formatArgs)

/** Reads a WeKit plural, using the application of the current process as the base context. */
fun localizedQuantity(@PluralsRes id: Int, quantity: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedQuantity(id, quantity, *formatArgs)

/** Reads a WeKit plural, using [this] as the platform base context. */
fun Context.localizedQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = localizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.localizedContext(): Context =
    LocalizedContextFactory.create(this, LocaleController.resolvedLocale)
