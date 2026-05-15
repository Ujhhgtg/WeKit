package dev.ujhhgtg.wekit.hooks.api.ui

import android.graphics.drawable.Drawable
import android.view.ContextMenu
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.util.LinkedList

@HookItem(path = "API/视频号分享菜单扩展", description = "为视频号分享菜单提供添加菜单项功能")
object WeShortVideosShareMenuApi : ApiHookItem(), IResolvesDex {

    interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val onClick: (XC_MethodHook.MethodHookParam, Int, List<JSONObject>) -> Unit
    )

    private val menuItems = mutableMapOf<String, List<MenuItem>>()

    fun addProvider(provider: IMenuItemsProvider) {
        menuItems[provider.javaClass.name] = provider.getMenuItems()
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItems.remove(provider.javaClass.name)
    }

    private val methodCreateMenu1 by dexMethod()
    private val methodOnSelectMenuItem1 by dexMethod()
    private val methodCreateMenu2 by dexMethod()
    private val methodOnSelectMenuItem2 by dexMethod()

    override fun onEnable() {
        methodCreateMenu1.hookBefore {
            val menu = args[0] as ContextMenu
            handleCreateMenu(menu)
        }

        methodOnSelectMenuItem1.hookBefore {
            val menuItem = args[0] as android.view.MenuItem
            val baseFinderFeed = thisObject.asResolver()
                .firstField {
                    type = "com.tencent.mm.plugin.finder.model.BaseFinderFeed"
                }
                .get()!!
            handleOnSelectMenuItem(this, menuItem, baseFinderFeed)
        }

        methodCreateMenu2.hookBefore {
            val menu = args[1] as ContextMenu
            handleCreateMenu(menu)
        }

        methodOnSelectMenuItem2.hookBefore {
            val menuItem = args[1] as android.view.MenuItem
            val baseFinderFeed = args[0]
            handleOnSelectMenuItem(this, menuItem, baseFinderFeed)
        }
    }

    private fun handleCreateMenu(
        menu: ContextMenu
    ) {
        for (item in menuItems.values.flatten()) {
            menu.asResolver()
                .firstMethod {
                    parameters(Int::class, CharSequence::class, Drawable::class)
                }
                .invoke(item.id, item.text, item.drawable)
        }
    }

    private fun handleOnSelectMenuItem(
        param: XC_MethodHook.MethodHookParam,
        menuItem: android.view.MenuItem,
        baseFinderFeed: Any
    ) {
        val itemId = menuItem.itemId
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

        for (item in menuItems.values.flatten()) {
            if (item.id == itemId) {
                item.onClick(param, mediaType, mediaJsonList)
                param.result = null
                return
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodCreateMenu1.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.finder.feed")
            matcher {
                name = "onCreateMMMenu"
                usingEqStrings("pos is error ")
            }
        }

        methodOnSelectMenuItem1.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.finder.feed")
            matcher {
                name = "onMMMenuItemSelected"
                usingEqStrings("[getMoreMenuItemSelectedListener] feed ")
            }
        }

        methodCreateMenu2.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.finder.feed")
            matcher {
                usingEqStrings("feed", "menu", "sheet", "holder", "KEY_FINDER_SELF_FLAG")
            }
        }

        methodOnSelectMenuItem2.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.finder.feed")
            matcher {
                declaredClass {
                    usingEqStrings("Finder.FinderLoaderFeedUIContract.Presenter")
                }

                usingEqStrings("getMoreMenuItemSelectedListener feed ")
            }
        }
    }
}
