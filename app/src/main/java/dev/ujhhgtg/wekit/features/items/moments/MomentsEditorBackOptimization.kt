package dev.ujhhgtg.wekit.features.items.moments

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

/**
 * SnsUploadUI 在 8.0.74+ 将"保留当前内容？"确认框创建为不可取消 (cancelable=false)，
 * 系统返回键/手势被对话框吞掉，既不关闭对话框也无法留在编辑页。
 * 这里在框架 [Dialog.show] 之后仅对该确认框重新放开取消，让系统返回关闭对话框并留在编辑页，
 * 并在确认框文本中追加"触发系统返回以留在当前页面"提示。
 * "保留"/"不保留"按钮语义保持不变。
 */
object MomentsEditorBackOptimization : SwitchFeature() {

    override val technicalId = "朋友圈编辑界面返回逻辑优化"
    override val nameRes = R.string.feature_moments_editor_back_optimization_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_moments_editor_back_optimization_description

    private const val TAG = "MomentsEditorBackOptimization"

    private const val SNS_UPLOAD_UI = "com.tencent.mm.plugin.sns.ui.SnsUploadUI"

    // Host-owned dialog titles used only to identify the target dialog.
    private val KEEP_CONTENT_TITLES = arrayOf("保留当前内容？", "保留此次编辑？")

    override fun onEnable() {
        Dialog::class.java.getMethod("show").hookAfter {
            val dialog = thisObject as Dialog
            if (dialog.context.unwrapActivity()?.javaClass?.name != SNS_UPLOAD_UI) return@hookAfter
            val titleView = dialog.findKeepContentTitle() ?: return@hookAfter

            dialog.setCancelable(true)
            titleView.appendBackHint()
            WeLogger.i(TAG, "SnsUploadUI exit-save dialog is now back-cancelable")
        }
    }

    private fun Dialog.findKeepContentTitle(): TextView? {
        val decor = window?.decorView ?: return null
        return decor.findTextView(KEEP_CONTENT_TITLES)
    }

    private fun View.findTextView(texts: Array<String>): TextView? {
        if (this is TextView) {
            val text = text?.toString()?.trim()
            if (texts.any { it == text || text?.startsWith("$it\n") == true }) return this
        }
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                val found = getChildAt(i).findTextView(texts)
                if (found != null) return found
            }
        }
        return null
    }

    @SuppressLint("SetTextI18n")
    private fun TextView.appendBackHint() {
        val current = text?.toString().orEmpty()
        val backHint = context.localizedMomentsString(R.string.moments_editor_back_hint)
        if (backHint !in current) {
            text = current + backHint
        }
    }

    private fun Context?.unwrapActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.unwrapActivity()
        else -> null
    }
}
