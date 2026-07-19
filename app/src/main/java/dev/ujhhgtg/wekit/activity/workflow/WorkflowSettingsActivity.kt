package dev.ujhhgtg.wekit.activity.workflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import dev.ujhhgtg.wekit.ui.content.MiuixStackNavigator
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.workflow.ui.WorkflowEditScreen
import dev.ujhhgtg.wekit.workflow.ui.WorkflowListScreen

@Keep
class WorkflowSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ModuleTheme {
                WorkflowRoot(onFinish = { finish() })
            }
        }
    }
}

sealed interface WorkflowNavTarget {
    data object List : WorkflowNavTarget
    data class Edit(val workflowId: String) : WorkflowNavTarget
    data object New : WorkflowNavTarget
}

@Composable
private fun WorkflowRoot(onFinish: () -> Unit) {
    val backStack = remember { mutableStateListOf<WorkflowNavTarget>(WorkflowNavTarget.List) }
    MiuixStackNavigator(
        stack = backStack,
        onExitRoot = onFinish,
    ) { screen, push, pop ->
        when (screen) {
            WorkflowNavTarget.List -> WorkflowListScreen(
                onOpenWorkflow = { push(WorkflowNavTarget.Edit(it)) },
                onNewWorkflow = { push(WorkflowNavTarget.New) },
                onBack = pop,
            )
            is WorkflowNavTarget.Edit -> WorkflowEditScreen(
                workflowId = screen.workflowId,
                onBack = pop,
            )
            WorkflowNavTarget.New -> WorkflowEditScreen(
                workflowId = null,
                onBack = pop,
            )
        }
    }
}
