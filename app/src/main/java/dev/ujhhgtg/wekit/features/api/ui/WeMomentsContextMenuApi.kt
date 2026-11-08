package dev.ujhhgtg.wekit.features.api.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.ContextMenu
import android.view.View
import com.tencent.mm.plugin.sns.storage.SnsInfo
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.data
import dev.ujhhgtg.wekit.dex.dsl.dexField
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger

@SuppressLint("StaticFieldLeak")
object WeMomentsContextMenuApi : ApiFeature(), IResolveDex {
    override val technicalId = "朋友圈菜单增强扩展"
    override val nameRes = R.string.feature_we_moments_context_menu_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_moments_context_menu_api_description

    private const val TAG = "WeMomentsContextMenuApi"

    fun interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    private val menuItems = mutableMapOf<IMenuItemsProvider, List<MenuItem>>()
    private var creatingMenuView: View? = null
    private var creatingMenuContext: MomentsContext? = null

    fun addProvider(provider: IMenuItemsProvider) {
        menuItems[provider] = provider.getMenuItems()
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItems.remove(provider)
    }

    data class MenuItem(
        val id: Int,
        val text: String,
        val drawable: Drawable,
        val shouldShow: (context: MomentsContext, itemId: Int) -> Boolean,
        val onClick: (context: MomentsContext) -> Unit,
    )

    data class MomentsContext(
        val activity: Activity,
        val snsInfo: SnsInfo?,
        val timelineObject: Any?,
        val source: Any? = null,
    )

    private val methodOnCreateMenu by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui.listener")
        usingStrings("MicroMsg.TimelineOnCreateContextMenuListener", "onMMCreateContextMenu error")
    }

    private val methodOnItemSelected by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui.listener")
        usingStrings(
            "delete comment fail!!! snsInfo is null",
            "send photo fail, mediaObj is null",
            "mediaObj is null, send failed!",
        )
    }

    private val methodBindSelectedView by dexMethod {
        declaredClass(methodOnItemSelected.data.declaredClassName)
        returnType = "void"
        paramTypes(
            "android.view.View",
            "java.lang.String",
            "com.tencent.mm.protocal.protobuf.TimeLineObject",
        )
        usingEqStrings("bindOnSelectedView")
    }

    private val fieldSelectedLocalId by dexField {
        declaredClass(methodOnItemSelected.data.declaredClassName)
        type = "java.lang.String"
        writeMethod { descriptor = methodBindSelectedView.data.descriptor }
    }

    private val fieldSelectedTimeline by dexField {
        declaredClass(methodOnItemSelected.data.declaredClassName)
        type = "com.tencent.mm.protocal.protobuf.TimeLineObject"
        writeMethod { descriptor = methodBindSelectedView.data.descriptor }
    }

    private val fieldActivity by dexField {
        declaredClass(methodOnItemSelected.data.declaredClassName)
        type = "android.app.Activity"
    }

    private val methodSnsInfoStorage by dexMethod {
        returnType = "com.tencent.mm.plugin.sns.storage.SnsInfo"
        paramCount = 1
        paramTypes("java.lang.String")
        usingStrings("getByLocalId", "com.tencent.mm.plugin.sns.storage.SnsInfoStorage")
    }

    override fun onEnable() {
        methodOnCreateMenu.hookBefore {
            creatingMenuView = args[1] as View
            creatingMenuContext = null
        }
        methodBindSelectedView.hookAfter {
            if (args[0] === creatingMenuView) creatingMenuContext = resolveContext(thisObject!!)
        }
        methodOnCreateMenu.hookAfter {
            val context = creatingMenuContext
            creatingMenuView = null
            creatingMenuContext = null
            if (context == null) return@hookAfter
            val menu = args[0] as ContextMenu
            for (item in menuItems.values.flatten()) {
                try {
                    if (!item.shouldShow(context, item.id)) continue
                    menu
                        .reflekt()
                        .firstMethod {
                            parameters(Int::class, CharSequence::class, Drawable::class)
                        }
                        .invoke(item.id, item.text, item.drawable)
                } catch (error: Throwable) {
                    WeLogger.e(TAG, "shouldShow/add callback failed", error)
                }
            }
        }
        methodOnItemSelected.hookBefore {
            val clickedId = (args[0] as android.view.MenuItem).itemId
            val item =
                menuItems.values.flatten().firstOrNull { it.id == clickedId } ?: return@hookBefore
            result = null
            try {
                item.onClick(resolveContext(thisObject!!))
            } catch (error: Throwable) {
                WeLogger.e(TAG, "onSelect callback failed", error)
            }
        }
    }

    private fun resolveContext(listener: Any): MomentsContext {
        val localId = fieldSelectedLocalId.field.get(listener) as String
        val storage = WeMomentsApi.methodGetSnsInfoStorage.method.invoke(null)
        return MomentsContext(
            activity = fieldActivity.field.get(listener) as Activity,
            snsInfo = methodSnsInfoStorage.method.invoke(storage, localId) as SnsInfo?,
            timelineObject = fieldSelectedTimeline.field.get(listener),
            source = listener,
        )
    }
}
