package dev.ujhhgtg.wekit.hooks.items.chat

import android.content.Context
import android.view.MenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/移除消息菜单项", description = "从消息的长按菜单中移除指定名称的菜单项")
object RemoveChatMessageContextMenuItems : ClickableHookItem(), IResolvesDex {

    // although there are multiple addMenuItem() methods, i only found the usage of those two in the context menu of chat messages
    private val methodAddMenuItem1 by dexMethod()
    private val methodAddMenuItem2 by dexMethod()
    private const val KEY_REMOVED_ITEM_NAMES = "removed_menu_item_names"
    private const val DEFAULT_REMOVED_ITEM_NAMES =
        "收藏,提醒,翻译,搜一搜,编辑,打开,相关表情,合拍,查看专辑,静音播放,听筒播放,背景播放"

    override fun onEnable() {
        methodAddMenuItem1.hookAfter {
            val name = args[3] as CharSequence
            val removedNames =
                WePrefs.getStringOrDef(KEY_REMOVED_ITEM_NAMES, DEFAULT_REMOVED_ITEM_NAMES)
                    .split(',')

            if (removedNames.contains(name)) {
                val list = thisObject.asResolver()
                    .firstField { type = List::class }
                    .get()!! as ArrayList<*>
                list.removeAt(list.size - 1)
            }
        }

        methodAddMenuItem2.hookAfter {
            val name = args[3] as CharSequence
            val removedNames =
                WePrefs.getStringOrDef(KEY_REMOVED_ITEM_NAMES, DEFAULT_REMOVED_ITEM_NAMES)
                    .split(',')

            if (removedNames.contains(name)) {
                val list = thisObject.asResolver()
                    .firstField { type = List::class }
                    .get()!! as ArrayList<*>
                list.removeAt(list.size - 1)
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodAddMenuItem1.find(dexKit) {
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

        methodAddMenuItem2.find(dexKit) {
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
                    Button(onClick = {
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
