package dev.ujhhgtg.wekit.bootstrap.ui

import android.app.Activity
import android.graphics.Color
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import dev.ujhhgtg.wekit.bootstrap.BootstrapResources
import dev.ujhhgtg.wekit.bootstrap.ui.utils.theme.ModuleAppTheme
import java.io.File

data class BootstrapDialogAction(val label: String, val dismissAfter: Boolean = true, val action: () -> Unit = {})

/** ComponentDialog supplies its own lifecycle; the theme does not forward AndroidX owners. */
object BootstrapDialogs {
    fun create(
        owner: Activity, bootstrapApk: File, title: String, message: String,
        actions: List<BootstrapDialogAction>, onDismiss: () -> Unit = {},
    ): ComponentDialog {
        BootstrapResources.initialize(owner, bootstrapApk)
        val dialog = ComponentDialog(owner)
        val view = ComposeView(dialog.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SystemLocaleResources {
                    ModuleAppTheme {
                        Surface(
                            modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
                            shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                            tonalElevation = AlertDialogDefaults.TonalElevation,
                        ) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(title, style = MaterialTheme.typography.headlineSmall)
                                SelectionContainer { Text(message, style = MaterialTheme.typography.bodyMedium) }
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                                    actions.forEach { button ->
                                        TextButton(onClick = {
                                            if (button.dismissAfter) dialog.dismiss()
                                            button.action()
                                        }) { Text(button.label) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setOnDismissListener { onDismiss() }
        return dialog
    }
}

/** Keep the platform context and dialog owners; override only language-sensitive resources. */
@Composable
private fun SystemLocaleResources(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = remember(context, configuration) { BootstrapResources.systemContext(context).resources }
    if (resources === context.resources) {
        content()
    } else {
        CompositionLocalProvider(
            LocalResources provides resources,
            LocalConfiguration provides resources.configuration,
            content = content,
        )
    }
}
