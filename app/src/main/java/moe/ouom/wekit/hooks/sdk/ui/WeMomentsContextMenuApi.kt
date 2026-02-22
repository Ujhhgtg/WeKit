package moe.ouom.wekit.hooks.sdk.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.view.ContextMenu
import android.view.MenuItem
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "API/朋友圈右键菜单增强扩展", desc = "为朋友圈消息长按菜单提供自定义菜单项功能")
object WeMomentsContextMenuApi : ApiHookItem(), IDexFind {

    private val dexMethodOnCreateMenu by dexMethod()
    private val dexMethodOnItemSelected by dexMethod()
    private val dexMethodSnsInfoStorage by dexMethod()
    private val dexMethodGetSnsInfoStorage by dexMethod()

    private const val TAG = "WeMomentsContextMenuApi"

    val onCreateCallbacks = CopyOnWriteArrayList<IOnCreateListener>()
    val onSelectCallbacks = CopyOnWriteArrayList<IOnSelectListener>()

    fun addOnCreateListener(listener: IOnCreateListener) {
        onCreateCallbacks.add(listener)
    }
    fun removeOnCreateListener(listener: IOnCreateListener) {
        onCreateCallbacks.remove(listener)
    }

    fun addOnSelectListener(listener: IOnSelectListener) {
        onSelectCallbacks.add(listener)
    }
    fun removeOnSelectListener(listener: IOnSelectListener) {
        onSelectCallbacks.remove(listener)
    }

    /**
     * 接口：创建菜单时触发
     */
    fun interface IOnCreateListener {
        fun onCreate(contextMenu: ContextMenu)
    }

    /**
     * 接口：选中菜单时触发
     */
    fun interface IOnSelectListener {
        fun onSelect(context: MomentsContext, itemId: Int): Boolean
    }

    data class MomentsContext(
        val activity: Activity,
        val snsInfo: Any?,
        val timeLineObject: Any?
    )

    @SuppressLint("NonUniqueDexKitData")
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        dexMethodOnCreateMenu.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.sns.ui.listener")
            matcher {
                usingStrings("MicroMsg.TimelineOnCreateContextMenuListener", "onMMCreateContextMenu error")
            }
        }

        dexMethodOnItemSelected.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.sns.ui.listener")
            matcher {
                usingStrings(
                    "delete comment fail!!! snsInfo is null",
                    "send photo fail, mediaObj is null",
                    "mediaObj is null, send failed!"
                )
            }
        }

        dexMethodSnsInfoStorage.find(dexKit, descriptors) {
            matcher {
                paramCount(1)
                paramTypes("java.lang.String")
                usingStrings(
                    "getByLocalId",
                    "com.tencent.mm.plugin.sns.storage.SnsInfoStorage"
                )
                returnType("com.tencent.mm.plugin.sns.storage.SnsInfo")
            }
        }

        dexMethodGetSnsInfoStorage.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.sns.model")
            matcher {
                // 必须是静态方法
                modifiers = Modifier.STATIC
                returnType(dexMethodSnsInfoStorage.method.declaringClass)
                // 无参数
                paramCount(0)
                // 同时包含两个特征字符串
                usingStrings(
                    "com.tencent.mm.plugin.sns.model.SnsCore",
                    "getSnsInfoStorage"
                )
            }
        }

        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        hookAfter(dexMethodOnCreateMenu.method) { param ->
            handleCreateMenu(param)
        }

        hookAfter(dexMethodOnItemSelected.method) { param ->
            handleSelectMenu(param)
        }
    }

    private fun handleCreateMenu(param: XC_MethodHook.MethodHookParam) {
        try {
            val contextMenu = param.args.getOrNull(0) as? ContextMenu ?: return

            for (listener in onCreateCallbacks) {
                try {
                    listener.onCreate(contextMenu)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "OnCreate 回调执行异常", e)
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "handleCreateMenu 失败", e)
        }
    }

    private fun handleSelectMenu(param: XC_MethodHook.MethodHookParam) {
        try {
            val menuItem = param.args.getOrNull(0) as? MenuItem ?: return
            val hookedObject = param.thisObject
            val fields = hookedObject.javaClass.declaredFields
            fields.forEach { field ->
                field.isAccessible = true
                val value = field.get(hookedObject)
                WeLogger.d(TAG, "字段: ${field.name} (${field.type.name}) = $value")
            }

            val activity = fields.firstOrNull { it.type == Activity::class.java }
                ?.apply { isAccessible = true }?.get(hookedObject) as Activity

            val timeLineObject = fields.firstOrNull {
                it.type.name == "com.tencent.mm.protocal.protobuf.TimeLineObject"
            }?.apply { isAccessible = true }?.get(hookedObject)

            val snsID = fields.firstOrNull {
                it.type == String::class.java && !Modifier.isFinal(it.modifiers)
            }?.apply { isAccessible = true }?.get(hookedObject) as String
            val targetMethod = dexMethodSnsInfoStorage.method
            val instance = dexMethodGetSnsInfoStorage.method.invoke(null)
            val snsInfo = targetMethod.invoke(instance, snsID)

            val context = MomentsContext(activity, snsInfo, timeLineObject)
            val clickedId = menuItem.itemId

            for (listener in onSelectCallbacks) {
                try {
                    val handled = listener.onSelect(context, clickedId)
                    if (handled) {
                        WeLogger.d(TAG, "菜单项 $clickedId 已被动态回调处理")
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "OnSelect 回调执行异常", e)
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "handleSelectMenu 失败", e)
        }
    }
}