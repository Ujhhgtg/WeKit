package dev.ujhhgtg.wekit.features.items.debug

import android.content.Context
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.crash.NativeCrashHandler

object TriggerCrash : ClickableFeature() {

    override val technicalId = "测试崩溃"
    override val nameRes = R.string.feature_trigger_crash_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_trigger_crash_description

    private const val TAG = "TriggerCrash"

    private enum class CrashCategory(@StringRes val titleRes: Int) {
        JAVA(R.string.debug_trigger_crash_category_java),
        NATIVE(R.string.debug_trigger_crash_category_native),
    }

    override fun onClick(context: ComponentActivity) {
        showCrashCategoryDialog(context)
    }

    private fun showCrashCategoryDialog(context: Context) {
        showCrashTypeListDialog(
            context = context,
            titleRes = R.string.debug_trigger_crash_select_category,
            itemResources = CrashCategory.entries.map(CrashCategory::titleRes),
            onBack = null,
            onSelect = { index ->
                when (CrashCategory.entries[index]) {
                    CrashCategory.JAVA -> showJavaCrashTypeDialog(context)
                    CrashCategory.NATIVE -> showNativeCrashTypeDialog(context)
                }
            }
        )
    }

    private fun showJavaCrashTypeDialog(context: Context) {
        val crashTypes = listOf(
            R.string.debug_trigger_crash_java_null_pointer,
            R.string.debug_trigger_crash_java_array_bounds,
            R.string.debug_trigger_crash_java_class_cast,
            R.string.debug_trigger_crash_java_arithmetic,
            R.string.debug_trigger_crash_java_stack_overflow,
        )
        showCrashTypeListDialog(
            context = context,
            titleRes = R.string.debug_trigger_crash_select_java_type,
            itemResources = crashTypes,
            onBack = { showCrashCategoryDialog(context) },
            onSelect = { index -> confirmTriggerCrash(context, CrashCategory.JAVA, index) }
        )
    }

    private fun showNativeCrashTypeDialog(context: Context) {
        val crashTypes = listOf(
            R.string.debug_trigger_crash_native_sigsegv,
            R.string.debug_trigger_crash_native_sigabrt,
            R.string.debug_trigger_crash_native_sigfpe,
            R.string.debug_trigger_crash_native_sigill,
            R.string.debug_trigger_crash_native_sigbus,
        )
        showCrashTypeListDialog(
            context = context,
            titleRes = R.string.debug_trigger_crash_select_native_type,
            itemResources = crashTypes,
            onBack = { showCrashCategoryDialog(context) },
            onSelect = { index -> confirmTriggerCrash(context, CrashCategory.NATIVE, index) }
        )
    }

    /**
     * Shared composable list dialog for crash type selection.
     */
    private fun showCrashTypeListDialog(
        context: Context,
        @StringRes titleRes: Int,
        itemResources: List<Int>,
        onBack: (() -> Unit)?,
        onSelect: (Int) -> Unit,
    ) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(titleRes)) },
                text = {
                    Column {
                        itemResources.forEachIndexed { index, itemRes ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    onSelect(index)
                                },
                                content = {
                                    Text(
                                        text = stringResource(itemRes),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                            )
                            if (index < itemResources.lastIndex) HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                },
                confirmButton = {
                    if (onBack != null) {
                        TextButton(onClick = { onDismiss(); onBack() }) {
                            Text(stringResource(R.string.action_back))
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                    }
                }
            )
        }
    }

    private fun confirmTriggerCrash(context: Context, category: CrashCategory, crashType: Int) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.debug_trigger_crash_confirmation_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.debug_trigger_crash_confirmation_message,
                            stringResource(category.titleRes),
                        )
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        when (category) {
                            CrashCategory.JAVA -> triggerJavaCrash(crashType)
                            CrashCategory.NATIVE -> NativeCrashHandler.triggerCrash(crashType)
                        }
                    }) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
    }

    private fun triggerJavaCrash(crashType: Int) {
        WeLogger.w(TAG, "Triggering Java test crash, type: $crashType")
        when (crashType) {
            0 -> triggerNullPointerException()
            1 -> triggerArrayIndexOutOfBoundsException()
            2 -> triggerClassCastException()
            3 -> triggerArithmeticException()
            4 -> triggerStackOverflowError()
            else -> triggerNullPointerException()
        }
    }

    private fun triggerNullPointerException() {
        val obj: String? = null
        @Suppress("KotlinConstantConditions")
        obj!!.length
    }

    private fun triggerArrayIndexOutOfBoundsException() {
        val array = arrayOf(1, 2, 3)

        @Suppress("UNUSED_VARIABLE", "unused")
        val value = array[10]
    }

    private fun triggerClassCastException() {
        val obj: Any = "String"

        @Suppress("UNUSED_VARIABLE", "UNCHECKED_CAST", "unused", "KotlinConstantConditions")
        val number = obj as Int
    }

    private fun triggerArithmeticException() {
        @Suppress("UNUSED_VARIABLE", "DIVISION_BY_ZERO", "unused")
        val result = 10 / 0
    }

    private fun triggerStackOverflowError() {
        recursiveMethod()
    }

    private fun recursiveMethod() {
        recursiveMethod()
    }

    override val noSwitchWidget: Boolean
        get() = true
}
