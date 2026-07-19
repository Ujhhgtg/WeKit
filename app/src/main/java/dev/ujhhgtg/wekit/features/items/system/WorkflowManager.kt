package dev.ujhhgtg.wekit.features.items.system

import android.content.Intent
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.activity.workflow.WorkflowSettingsActivity
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature

/**
 * Settings entry that opens [WorkflowSettingsActivity] — the Workflow management UI.
 *
 * Workflows let users build automation pipelines (Apple Shortcuts-style) that can be bound to
 * any auto-processing feature (AutoLikeMoments, AutoAcceptTransfers, …) or triggered by custom
 * message / schedule events. Actions within a workflow can call any [@WeKitOperation] function,
 * including [dev.ujhhgtg.wekit.features.api.agent.WeAgentWorkflowToolBindings.callWeAgent].
 */
@Feature(
    name = "工作流",
    categories = ["系统与隐私"],
    description = "自动化工作流管理器：类似苹果「快捷指令」，支持条件分支、循环、变量、触发器。点击进入。",
)
object WorkflowManager : ClickableFeature() {

    override fun onClick(context: ComponentActivity) {
        context.startActivity(
            Intent(context, WorkflowSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
