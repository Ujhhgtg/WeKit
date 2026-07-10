package dev.ujhhgtg.wekit.features.items.contacts

import android.view.MenuItem
import androidx.activity.ComponentActivity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.NearbyFriendProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.WeProto
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.reflection.int
import java.util.LinkedList

@Feature(name = "自动添加附近的人", categories = ["联系人与群组"], description = "在附近的人菜单中添加菜单项, 可全自动向附近的人按模板发送消息 (没写完)")
object AutoAddNearbyFriends : ClickableFeature(), IResolveDex {

    private val methodCreateMenu by dexMethod {
        matcher {
            usingEqStrings("NearbyPersonUIC", "showLiveBottomSheet create menu.")
        }
    }

    private val methodMenuOnClick by dexMethod {
        matcher {
            usingEqStrings("com.tencent.mm.plugin.nearby.ui.NearbySayHiListUI")
            name = "onMMMenuItemSelected"
        }
    }

    override fun onEnable() {
        methodCreateMenu.hookBefore {
            args[0].reflekt().firstMethod {
                parameters(int, CharSequence::class)
            }.invoke(6, "自动加好友")
        }

        methodMenuOnClick.hookBefore {
            val menuItem = args[0] as MenuItem
            val itemId = menuItem.itemId
            if (itemId != 6) return@hookBefore

            val controller = thisObject.reflekt().firstField().get()!!
            val friends = controller.reflekt().firstField {
                type = List::class
            }.get()!! as LinkedList<*>

            val friendProtos = friends.map { WeProto.decode<NearbyFriendProto>(
                it.reflekt().invokeMethod("toByteArray", superclass = true) as ByteArray) }

            result = null
        }
    }

    override fun onClick(context: ComponentActivity) {

    }
}
