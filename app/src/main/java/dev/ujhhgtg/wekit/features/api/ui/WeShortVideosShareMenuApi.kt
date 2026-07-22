package dev.ujhhgtg.wekit.features.api.ui

import android.graphics.drawable.Drawable
import android.view.ContextMenu
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.HookParam
import org.json.JSONObject
import java.util.LinkedList

@Feature(name = "视频号分享菜单扩展", categories = ["API"], description = "为视频号分享菜单提供添加菜单项功能")
object WeShortVideosShareMenuApi : ApiFeature(), IResolveDex {

    fun interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val onClick: (HookParam, Int, List<JSONObject>) -> Unit
    )

    private val menuItems = mutableMapOf<String, List<MenuItem>>()

    fun addProvider(provider: IMenuItemsProvider) {
        menuItems[provider.javaClass.name] = provider.getMenuItems()
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItems.remove(provider.javaClass.name)
    }

    private val methodCreateMenu1 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            name = "onCreateMMMenu"
            usingEqStrings("pos is error ")
        }
    }
    private val methodOnSelectMenuItem1 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            name = "onMMMenuItemSelected"
            usingEqStrings("[getMoreMenuItemSelectedListener] feed ")
        }
    }
    private val methodCreateMenu2 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            usingEqStrings("feed", "menu", "sheet", "holder", "KEY_FINDER_SELF_FLAG")
        }
    }
    private val methodOnSelectMenuItem2 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            declaredClass {
                usingEqStrings("Finder.FinderLoaderFeedUIContract.Presenter")
            }

            usingEqStrings("getMoreMenuItemSelectedListener feed ")
        }
    }
    private val methodCreateMenu3 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            name = "onCreateMMMenu"
            usingEqStrings("getCreateSecondMoreMenuListener: username=")
        }
    }
    private val methodOnSelectMenuItem3 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            name = "onMMMenuItemSelected"
            usingEqStrings("button_speedplay", "ref_eid")
        }
    }

    override fun onEnable() {
        methodCreateMenu1.hookBefore {
            val menu = args[0] as ContextMenu
            handleCreateMenu(menu)
        }

        methodOnSelectMenuItem1.hookBefore {
            val menuItem = args[0] as android.view.MenuItem
            val baseFinderFeed = thisObject!!.reflekt()
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
            val baseFinderFeed = args[0]!!
            handleOnSelectMenuItem(this, menuItem, baseFinderFeed)
        }

        methodCreateMenu3.hookBefore {
            val menu = args[0] as ContextMenu
            handleCreateMenu(menu)
        }

        methodOnSelectMenuItem3.hookBefore {
            val menuItem = args[0] as android.view.MenuItem
            val baseFinderFeed = thisObject!!.reflekt()
                .firstField {
                    type = "com.tencent.mm.plugin.finder.model.BaseFinderFeed"
                }
                .get()!!
            handleOnSelectMenuItem(this, menuItem, baseFinderFeed)
        }
    }

    private fun handleCreateMenu(
        menu: ContextMenu
    ) {
        for (item in menuItems.values.flatten()) {
            menu.reflekt()
                .firstMethod {
                    parameters(Int::class, CharSequence::class, Drawable::class)
                }
                .invoke(item.id, item.text, item.drawable)
        }
    }

    private fun handleOnSelectMenuItem(
        param: HookParam,
        menuItem: android.view.MenuItem,
        baseFinderFeed: Any
    ) {
        val itemId = menuItem.itemId
        val finderItem = baseFinderFeed.reflekt()
            .firstField {
                name = "feedObject"
                superclass()
            }
            .get()!!
        val mediaType = finderItem.reflekt()
            .firstMethod {
                name = "getMediaType"
            }
            .invoke()!! as Int
        val mediaList = finderItem.reflekt()
            .firstMethod {
                name = "getMediaList"
            }
            .invoke() as LinkedList<*>
        val mediaJsonList = mediaList.map { media ->
            media.reflekt()
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
}
