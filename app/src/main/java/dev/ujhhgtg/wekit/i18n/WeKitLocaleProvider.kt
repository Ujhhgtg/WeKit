package dev.ujhhgtg.wekit.i18n

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
fun WeKitLocaleProvider(
    mode: LocaleResourceMode,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val activity = LocalActivity.current
    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
    val parentConfiguration = LocalConfiguration.current
    val locale = WeKitLocaleController.resolvedLocale
    val localizedContext = remember(baseContext, parentConfiguration, locale, mode) {
        LocalizedContextFactory.create(baseContext, locale, mode)
    }
    val localizedConfiguration = remember(localizedContext, locale) {
        Configuration(localizedContext.resources.configuration)
    }

    val values = buildList<ProvidedValue<*>> {
        add(LocalContext provides localizedContext)
        add(LocalConfiguration provides localizedConfiguration)
        activity?.let { add(LocalActivity provides it) }
        activityResultRegistryOwner?.let { add(LocalActivityResultRegistryOwner provides it) }
    }
    CompositionLocalProvider(*values.toTypedArray(), content = content)
}
