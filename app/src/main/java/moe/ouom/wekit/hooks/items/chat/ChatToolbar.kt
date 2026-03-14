package moe.ouom.wekit.hooks.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.utils.AppTheme
import moe.ouom.wekit.ui.utils.MainActivityLifecycleOwnerProvider
import moe.ouom.wekit.ui.utils.findViewByIdStr
import moe.ouom.wekit.ui.utils.setLifecycleOwner
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge

@SuppressLint("StaticFieldLeak")
@HookItem(path = "聊天/聊天工具栏", desc = "在输入框上方添加工具栏")
object ChatToolbar : SwitchHookItem(), IDexFind {

    private val TAG = nameof(ChatToolbar)
    private const val VIEW_TAG = "wekit_chat_toolbar"

    private val methodAppPanelInitAppGrid by dexMethod()
    private val methodAppPanelLoadData by dexMethod()

    private lateinit var appPanel: LinearLayout

    // TODO: match menu item by text instead of undeterministic index
    override fun onLoad() {
        methodAppPanelInitAppGrid.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    appPanel = param.args[0] as LinearLayout
                }
            }
        }

        "com.tencent.mm.pluginsdk.ui.chat.ChatFooter".toClass().asResolver()
            .firstConstructor {
                parameters(Context::class, AttributeSet::class, Int::class)
            }
            .self
            .hookAfter { param ->
                val frameLayout = param.thisObject as FrameLayout
                // TODO: might change with WeChat version
                val linearLayout = frameLayout.findViewByIdStr<LinearLayout>("bl8") // LinearLayout
                if (linearLayout.findViewWithTag<ComposeView>(VIEW_TAG) != null) return@hookAfter

                val context = linearLayout.context
                val lifecycleOwner = MainActivityLifecycleOwnerProvider.lifecycleOwner
                linearLayout.setLifecycleOwner(lifecycleOwner)
                (context as Activity).window.decorView.setLifecycleOwner(lifecycleOwner)

                linearLayout.addView(ComposeView(context).apply {
                    tag = VIEW_TAG
                    setLifecycleOwner(lifecycleOwner)

                    setContent {
                        AppTheme {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                item {
                                    FeatureChip("相机", Icons.Default.Camera) {
                                        longClickItem(0, 0)
                                    }
                                }
                                item {
                                    FeatureChip("相册", Icons.Default.PhotoAlbum) {
                                        clickItem(0, 0)
                                    }
                                }
                                item {
                                    FeatureChip("通话", Icons.Default.Call) {
                                        clickItem(0, 2)
                                    }
                                }
                                item {
                                    // FIXME: find a more accurate material symbol
                                    FeatureChip("红包", Icons.Default.Mail) {
                                        clickItem(0, 4)
                                    }
                                }
                                item {
                                    FeatureChip("转账", Icons.Default.Payments) {
                                        clickItem(0, 6)
                                    }
                                }
                                item {
                                    // FIXME: find a more accurate material symbol
                                    FeatureChip("礼物", Icons.Default.Redeem) {
                                        clickItem(0, 5)
                                    }
                                }
                            }
                        }
                    }
                }, 0)
            }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodAppPanelInitAppGrid.find(dexKit, descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.pluginsdk.ui.chat.AppPanel"
                usingEqStrings("MicroMsg.AppPanel", "initAppGrid()")
            }
        }

        methodAppPanelLoadData.find(dexKit, descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.pluginsdk.ui.chat.AppPanel"
                usingEqStrings("MicroMsg.AppPanel", "app panel refleshed: %s")
            }
        }

        return descriptors
    }

    private fun clickItem(gridIndex: Int, itemIndex: Int) {
        try {
            val appPanelGrids = appPanel.asResolver()
                .firstField { type = "com.tencent.mm.ui.base.MMFlipper" }
                .get()!! as ViewGroup
            val appGrid = appPanelGrids.getChildAt(gridIndex) as GridView
            val view = appGrid.adapter.getView(itemIndex, null, appGrid)
            val onClick = appGrid.asResolver()
                .firstField { type = AdapterView.OnItemClickListener::class }
                .get()!! as AdapterView.OnItemClickListener
            onClick.onItemClick(appGrid, view, itemIndex, 0)
        } catch (ex: Exception) {
            WeLogger.e(TAG, "exception while clicking on menu item", ex)
        }
    }

    private fun longClickItem(gridIndex: Int, itemIndex: Int) {
        try {
            val appPanelGrids = appPanel.asResolver()
                .firstField { type = "com.tencent.mm.ui.base.MMFlipper" }
                .get()!! as ViewGroup
            val appGrid = appPanelGrids.getChildAt(gridIndex) as GridView
            val onLongClick = appGrid.asResolver()
                .firstField { type = AdapterView.OnItemLongClickListener::class }
                .get()!! as AdapterView.OnItemLongClickListener
            // arg 0,1,3 are all not used in WeChat's code
            onLongClick.onItemLongClick(null, null, itemIndex, 0)
        } catch (ex: Exception) {
            WeLogger.e(TAG, "exception while long clicking on menu item", ex)
        }
    }
}

@Composable
private fun FeatureChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                Modifier.size(AssistChipDefaults.IconSize)
            )
        }
    )
}