package moe.ouom.wekit.hooks.api.ui

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.logging.WeLogger
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.util.LinkedList
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("StaticFieldLeak")
@HookItem(path = "API/视频号分享菜单扩展", desc = "为视频号分享菜单提供添加菜单项功能")
object WeShortVideosShareMenuApi : ApiHookItem(), IResolvesDex {

    interface IMenuItemsProvider {
        fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Lazy<Drawable?>,
        val onClick: (XC_MethodHook.MethodHookParam, Int, List<JSONObject>) -> Unit
    )

    private const val TAG: String = "WeShortVideosShareMenuApi"

    private val providers = CopyOnWriteArrayList<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        if (!providers.contains(provider)) {
            providers.add(provider)
            WeLogger.i(TAG, "provider added, current provider count: ${providers.size}")
        } else {
            WeLogger.w(TAG, "provider already exists, ignored")
        }
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        val removed = providers.remove(provider)
        WeLogger.i(
            TAG,
            "provider remove ${if (removed) "succeeded" else "failed"}, current provider count: ${providers.size}"
        )
    }

    private val methodCreateMenu by dexMethod()
    private val methodSelectMenuItem by dexMethod()

    override fun onEnable() {
        methodCreateMenu.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val menu = param.args[0]
                    for (provider in providers) {
                        try {
                            for (item in provider.getMenuItems(param)) {
                                menu.asResolver()
                                    .firstMethod {
                                        parameters(Int::class, CharSequence::class, Drawable::class)
                                    }
                                    .invoke(item.id, item.text, item.drawable.value)
                            }
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while providing menu items",
                                ex
                            )
                        }
                    }
                }
            }
        }

        methodSelectMenuItem.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val menuItem = param.args[0] as android.view.MenuItem
                    val itemId = menuItem.itemId

                    val baseFinderFeed = param.thisObject.asResolver()
                        .firstField {
                            type = "com.tencent.mm.plugin.finder.model.BaseFinderFeed"
                        }
                        .get()!!
                    val finderItem = baseFinderFeed.asResolver()
                        .firstField {
                            name = "feedObject"
                            superclass()
                        }
                        .get()!!
                    val mediaType = finderItem.asResolver()
                        .firstMethod {
                            name = "getMediaType"
                        }
                        .invoke()!! as Int
                    val mediaList = finderItem.asResolver()
                        .firstMethod {
                            name = "getMediaList"
                        }
                        .invoke() as LinkedList<*>
                    val mediaJsonList = mediaList.map { media ->
                        media.asResolver()
                            .firstMethod {
                                name = "toJSON"
                                superclass()
                            }.invoke()!! as JSONObject
                    }

                    for (provider in providers) {
                        try {
                            for (item in provider.getMenuItems(param)) {
                                if (item.id == itemId) {
                                    item.onClick(param, mediaType, mediaJsonList)
                                    param.result = null
                                    return@beforeIfEnabled
                                }
                            }
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while handling click event",
                                ex
                            )
                        }
                    }
                }
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodCreateMenu.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.finder.feed")
            matcher {
                name = "onCreateMMMenu"
                usingEqStrings("pos is error ")
            }
        }

        methodSelectMenuItem.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.finder.feed")
            matcher {
                name = "onMMMenuItemSelected"
                usingEqStrings("[getMoreMenuItemSelectedListener] feed ")
            }
        }

        return descriptors
    }
}