package dev.ujhhgtg.wekit.features.items.system

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

object AutoApproveDeviceLogin : ClickableFeature() {

    override val technicalId = "自动批准设备登录"
    override val nameRes = R.string.feature_auto_approve_device_login_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_auto_approve_device_login_description

    private const val AUTO_SYNC_MESSAGES = 0x1
    private const val SHOW_LOGIN_DEVICE = 0x2
    private const val AUTO_LOGIN_DEVICE = 0x4

    private var syncMessages by prefOption("auto_approve_device_login_sync", true)
    private var autoLoginDevice by prefOption("auto_approve_device_login_auto_login", true)

    override fun onEnable() {
        val targetClass = ExtDeviceWXLoginUI::class.java

        targetClass.hookBeforeOnCreate {
            val activity = thisObject as Activity
            var functionControl = 0
            if (syncMessages) functionControl = functionControl or AUTO_SYNC_MESSAGES
            // 关闭自动登录时连 SHOW_LOGIN_DEVICE 一起不设, 让微信直接隐藏这一行 —— 它的勾选框在
            // 布局里默认未勾选, 微信也只在设了该位时才会去 setChecked, 所以确认时读到的就是 false
            if (autoLoginDevice) {
                functionControl = functionControl or SHOW_LOGIN_DEVICE
                functionControl = functionControl or AUTO_LOGIN_DEVICE
            }
            activity.intent.putExtra("intent.key.function.control", functionControl)
            activity.intent.putExtra("intent.key.need.show.privacy.agreement", false)
        }

        targetClass.reflekt().firstMethod { name = "initView" }.hookAfter {
            val instance = thisObject!!.reflekt()

            // 同步消息的勾选框除了 AUTO_SYNC_MESSAGES 位以外还要求 USERINFO_MSG_SYNCHRONIZE_BOOLEAN
            // 为 true, 而用户上次取消勾选时微信会把它写成 false, 于是只设置 flag 并不会让它勾上,
            // 这里直接勾选界面上可见的勾选框。
            // 自动登录的勾选框此时要么已被 AUTO_LOGIN_DEVICE 位勾上 (勾了也是空操作), 要么整行不可见,
            // 所以无需区分二者
            if (syncMessages) {
                instance.fields { type = CheckBox::class }
                    .mapNotNull { it.get() as CheckBox? }
                    .filter { it.isEffectivelyVisible() }
                    .forEach { it.isChecked = true }
            }

            val button = instance.firstField {
                type = Button::class
            }.get()!! as Button
            button.performClick()
        }
    }

    // 此时界面还未附加到窗口, isShown() 不可用
    private fun View.isEffectivelyVisible(): Boolean {
        var view: View? = this
        while (view != null) {
            if (view.visibility != View.VISIBLE) return false
            view = view.parent as? View
        }
        return true
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var syncMessagesInput by remember { mutableStateOf(syncMessages) }
            var autoLoginDeviceInput by remember { mutableStateOf(autoLoginDevice) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_approve_device_login_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.system_auto_approve_sync),
                                description = stringResource(R.string.system_auto_approve_sync_summary),
                                checked = syncMessagesInput,
                                onCheckedChange = {
                                    syncMessagesInput = it
                                    syncMessages = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.system_auto_approve_auto_login),
                                description = stringResource(R.string.system_auto_approve_auto_login_summary),
                                checked = autoLoginDeviceInput,
                                onCheckedChange = {
                                    autoLoginDeviceInput = it
                                    autoLoginDevice = it
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
