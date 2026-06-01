package dev.ujhhgtg.wekit.hooks.items.beautify

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlinedfilled.Add
import com.composables.icons.materialsymbols.outlinedfilled.Camera
import com.composables.icons.materialsymbols.outlinedfilled.Cancel
import com.composables.icons.materialsymbols.outlinedfilled.Extension
import com.composables.icons.materialsymbols.outlinedfilled.Movie
import com.composables.icons.materialsymbols.outlinedfilled.Qr_code_scanner
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Update
import com.composables.icons.materialsymbols.outlinedfilled.Wallet
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.api.core.WeConversationApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.content.MainSettingsDialog
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.WeLogger.exitProcess
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "界面美化/主屏幕添加 FAB", description = "向微信主屏幕添加浮动操作按钮")
object AddMainScreenFab : SwitchHookItem() {

    interface IMenuItemsProvider {
        fun getMenuItems(activity: Activity): List<MenuItem>
    }

    data class MenuItem(val text: String, val icon: ImageVector, val onClick: () -> Unit)

    private val providers = CopyOnWriteArrayList<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        providers.remove(provider)
    }

    private val TAG = nameOf(AddMainScreenFab)

    private fun startActivityByName(context: Context, className: String) {
        val intent = Intent().apply {
            setClassName(context.packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject.asResolver()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity

            val menuItems = mutableMapOf(
                "扫一扫" to (MaterialSymbols.OutlinedFilled.Qr_code_scanner to {
                    startActivityByName(
                        activity,
                        "com.tencent.mm.plugin.scanner.ui.BaseScanUI"
                    )
                }),
                "朋友圈" to (MaterialSymbols.OutlinedFilled.Camera to {
                    startActivityByName(
                        activity,
                        "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"
                    )
                }),
                "钱包" to (MaterialSymbols.OutlinedFilled.Wallet to {
                    startActivityByName(
                        activity,
                        "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"
                    )
                }),
                "视频号" to (MaterialSymbols.OutlinedFilled.Movie to {
                    startActivityByName(
                        activity,
                        "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI"
                    )
                }),
                "设置" to (MaterialSymbols.OutlinedFilled.Settings to {
                    startActivityByName(
                        activity,
                        "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
                    )
                }),
                "模块设置" to (MaterialSymbols.OutlinedFilled.Extension to {
                    MainSettingsDialog(activity).show()
                }),
                "强制停止" to (MaterialSymbols.OutlinedFilled.Cancel to {
                    exitProcess(0)
                }),
                "全部已读" to (MaterialSymbols.OutlinedFilled.Update to {
                    WeConversationApi.markAllAsRead()
                    showToast("已将全部未读消息标为已读")
                })
            )

            for (provider in providers) {
                try {
                    for (item in provider.getMenuItems(activity)) {
                        menuItems[item.text] = item.icon to item.onClick
                    }
                } catch (ex: Exception) {
                    WeLogger.e(
                        TAG,
                        "provider ${provider.javaClass.name} threw while providing menu items",
                        ex
                    )
                }
            }

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
            val root = activity.rootView

            root.addView(
                ComposeView(activity).apply {
                    setLifecycleOwner(lifecycleOwner)

                    setContent {
                        AppTheme {
                            // TODO: we currently use a custom background color without accent to match with wechat's overall style
                            //       might change this when MonetEngine covers more UI
                            val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7)
                            val activeColor = MaterialTheme.colorScheme.primary

                            var expanded by remember { mutableStateOf(false) }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 60.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // 1. Expandable Menu Items
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        // 1. Expandable Menu Items (staggered per-item animation)
                                        menuItems.entries.forEachIndexed { index, (name, pair) ->
                                            // Stagger: items animate in bottom-to-top, out top-to-bottom
                                            val itemDelay = index * 35
                                            val reverseDelay = (menuItems.size - 1 - index) * 35

                                            AnimatedVisibility(
                                                visible = expanded,
                                                enter = fadeIn(
                                                    animationSpec = tween(
                                                        durationMillis = 160,
                                                        delayMillis = reverseDelay,
                                                        easing = EaseOut
                                                    )
                                                ) + slideInVertically(
                                                    animationSpec = tween(
                                                        durationMillis = 180,
                                                        delayMillis = reverseDelay,
                                                        easing = EaseOutCubic
                                                    ),
                                                    initialOffsetY = { it / 2 }
                                                ),
                                                exit = fadeOut(
                                                    animationSpec = tween(
                                                        durationMillis = 100,
                                                        delayMillis = itemDelay,
                                                        easing = EaseIn
                                                    )
                                                ) + slideOutVertically(
                                                    animationSpec = tween(
                                                        durationMillis = 100,
                                                        delayMillis = itemDelay,
                                                        easing = EaseInCubic
                                                    ),
                                                    targetOffsetY = { it / 2 }
                                                )
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(
                                                        12.dp
                                                    )
                                                ) {
                                                    // The Floating Label
                                                    Surface(
                                                        shape = MaterialTheme.shapes.large,
                                                        color = backgroundColor,
                                                        tonalElevation = 2.dp,
                                                        shadowElevation = 2.dp
                                                    ) {
                                                        Text(
                                                            text = name,
                                                            modifier = Modifier.padding(
                                                                horizontal = 12.dp,
                                                                vertical = 6.dp
                                                            ),
                                                            color = activeColor,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }

                                                    // The Small FAB
                                                    SmallFloatingActionButton(
                                                        onClick = {
                                                            pair.second()
                                                            expanded = false
                                                        },
                                                        containerColor = backgroundColor,
                                                        shape = CircleShape,
                                                        elevation = FloatingActionButtonDefaults.elevation(
                                                            2.dp
                                                        )
                                                    ) {
                                                        Icon(
                                                            pair.first,
                                                            contentDescription = null,
                                                            tint = activeColor
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 2. Main Toggle FAB
                                    FloatingActionButton(
                                        onClick = { expanded = !expanded },
                                        containerColor = backgroundColor,
                                        shape = CircleShape
                                    ) {
                                        val rotation by animateFloatAsState(if (expanded) 45f else 0f)
                                        Icon(
                                            MaterialSymbols.OutlinedFilled.Add,
                                            contentDescription = null,
                                            tint = activeColor,
                                            modifier = Modifier.rotate(rotation)
                                        )
                                    }
                                }
                            }
                        }
                    }
                })
        }
    }
}
