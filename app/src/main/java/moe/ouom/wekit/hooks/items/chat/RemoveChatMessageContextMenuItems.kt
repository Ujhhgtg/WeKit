package moe.ouom.wekit.hooks.items.chat

import android.content.Context
import android.view.MenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.config.WePrefs
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.utils.showComposeDialog
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/移除消息菜单项", desc = "从消息的长按菜单中移除指定名称的菜单项")
object RemoveChatMessageContextMenuItems : ClickableHookItem(), IDexFind {

    // although there are multiple addMenuItem() methods, i only found the usage of those two in the context menu of chat messages
    private val methodAddMenuItem1 by dexMethod()
    private val methodAddMenuItem2 by dexMethod()
    private const val KEY_REMOVED_ITEM_NAMES = "removed_menu_item_names"
    private const val DEFAULT_REMOVED_ITEM_NAMES =
        "收藏,提醒,翻译,搜一搜,编辑,打开,相关表情,合拍,查看专辑,静音播放,听筒播放,背景播放"

    override fun onLoad() {
        methodAddMenuItem1.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val name = param.args[3] as CharSequence
                    val removedNames =
                        WePrefs.getStringOrDef(KEY_REMOVED_ITEM_NAMES, DEFAULT_REMOVED_ITEM_NAMES)
                            .split(',')

                    if (removedNames.contains(name)) {
                        val list = param.thisObject.asResolver()
                            .firstField { type = List::class }
                            .get()!! as ArrayList<*>
                        list.removeAt(list.size - 1)
                    }
                }
            }
        }

        methodAddMenuItem2.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val name = param.args[3] as CharSequence
                    val removedNames =
                        WePrefs.getStringOrDef(KEY_REMOVED_ITEM_NAMES, DEFAULT_REMOVED_ITEM_NAMES)
                            .split(',')

                    if (removedNames.contains(name)) {
                        val list = param.thisObject.asResolver()
                            .firstField { type = List::class }
                            .get()!! as ArrayList<*>
                        list.removeAt(list.size - 1)
                    }
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodAddMenuItem1.find(dexKit, descriptors) {
            matcher {
                declaredClass {
                    addFieldForType(List::class.javaObjectType)
                    addFieldForType(CharSequence::class.java)
                    addFieldForType(Context::class.java)
                }

                name = "add"
                paramTypes(
                    Int::class.java,
                    Int::class.java,
                    Int::class.java,
                    CharSequence::class.java
                )
                returnType(MenuItem::class.java)
            }
        }

        methodAddMenuItem2.find(dexKit, descriptors) {
            matcher {
                declaredClass(methodAddMenuItem1.method.declaringClass)
                paramTypes(
                    Int::class.java,
                    Int::class.java,
                    Int::class.java,
                    CharSequence::class.java,
                    Int::class.java
                )
                returnType(MenuItem::class.java)
            }
        }

        return descriptors
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var removedNames by remember {
                mutableStateOf(
                    WePrefs.getStringOrDef(
                        KEY_REMOVED_ITEM_NAMES,
                        DEFAULT_REMOVED_ITEM_NAMES
                    )
                )
            }
            AlertDialogContent(
                title = { Text("移除消息菜单项") },
                text = {
                    TextField(
                        value = removedNames,
                        onValueChange = { removedNames = it },
                        label = { Text("要移除的菜单项名称 (以逗号分割):") })
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                confirmButton = {
                    TextButton(onClick = {
                        WePrefs.putString(
                            KEY_REMOVED_ITEM_NAMES,
                            removedNames
                        )
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }
}