package dev.ujhhgtg.wekit.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import org.luckypray.dexkit.DexKitBridge

@Feature(
    name = "圆角头像", categories = ["联系人与群组", "界面美化"],
    description = "自定义微信全局头像渲染的圆角弧度"
)
object RoundAvatars : ClickableFeature(), IResolveDex {

    private const val KEY_ROUND_AVATAR = "round_avatar_radius_factor"

    private val methodLoadAvatar by dexMethod {
        matcher {
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
                "float",
                "boolean"
            )
            usingEqStrings("MicroMsg.AvatarDrawable")
        }
    }
    private val ctorAvatarCreate by dexConstructor {
        matcher {
            usingEqStrings("workerScope", "username")
        }
    }
    private val methodAvatarModify by dexMethod()

    private val radiusFactor: Float
        get() = WePrefs.getFloatOrDef(KEY_ROUND_AVATAR, 0.5f).coerceIn(0.1f, 0.5f)

    override fun onEnable() {
        methodLoadAvatar.hookBefore {
            setFloatArg(2, radiusFactor)
        }

        ctorAvatarCreate.hookBefore {
            setFloatArg(2, radiusFactor)
        }

        if (!methodAvatarModify.isPlaceholder) {
            methodAvatarModify.hookBefore {
                setFloatArg(3, radiusFactor)
            }
        }

        notifyCustomContactAvatarChanged()
    }

    override fun onDisable() {
        notifyCustomContactAvatarChanged()
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val modifyMethods = dexKit.findMethod {
            matcher {
                usingEqStrings("workerScope", "username")
            }
        }.filter { it.methodName != "<init>" }

        val modifyMethod = modifyMethods.singleOrNull()
        if (modifyMethod == null) {
            methodAvatarModify.setPlaceholderDescriptor()
        } else {
            methodAvatarModify.setDescriptor(modifyMethod)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var value by remember { mutableFloatStateOf(radiusFactor) }

            AlertDialogContent(
                title = { Text("圆角头像") },
                text = {
                    ListItem(
                        supportingContent = {
                            Slider(
                                value = value,
                                onValueChange = { value = it.coerceIn(0.1f, 0.5f) },
                                valueRange = 0.1f..0.5f,
                                steps = 39
                            )
                        },
                        headlineContent = { Text("圆角弧度: %.2f".format(value)) },
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        WePrefs.putFloat(KEY_ROUND_AVATAR, value.coerceIn(0.1f, 0.5f))
                        notifyCustomContactAvatarChanged()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    private fun HookParam.setFloatArg(index: Int, value: Float) {
        if (index in args.indices) args[index] = value
    }

    private fun notifyCustomContactAvatarChanged() {
        runCatching {
            if (CustomLocalFriendAvatars.isActive) {
                CustomLocalFriendAvatars.onRoundAvatarConfigChanged()
            }
        }
    }
}
