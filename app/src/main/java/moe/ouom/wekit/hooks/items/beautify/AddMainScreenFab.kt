package moe.ouom.wekit.hooks.items.beautify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process
import android.view.ViewGroup
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
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
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.constants.PackageNames
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.core.WeConversationApi
import moe.ouom.wekit.hooks.api.ui.WeMainActivityBeautifyApi
import moe.ouom.wekit.ui.content.MainSettingsDialog
import moe.ouom.wekit.ui.utils.MainActivityLifecycleOwnerProvider
import moe.ouom.wekit.ui.utils.setLifecycleOwner
import moe.ouom.wekit.utils.ToastUtils
import moe.ouom.wekit.utils.logging.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@HookItem(path = "界面美化/主屏幕添加 FAB", desc = "向应用主屏幕添加浮动操作按钮")
object AddMainScreenFab : SwitchHookItem() {

    interface IMenuItemsProvider {
        fun getMenuItems(activity: Activity): List<MenuItem>
    }

    // TODO: do not force other features to use ImageVector
    data class MenuItem(val text: String, val icon: ImageVector, val onClick: () -> Unit)

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

    private val TAG = nameof(AddMainScreenFab)

    private fun startActivityByName(context: Context, className: String) {
        val intent = Intent().apply {
            setClassName(PackageNames.WECHAT, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val activity = param.thisObject.asResolver()
                        .firstField {
                            type = "com.tencent.mm.ui.MMFragmentActivity"
                        }
                        .get()!! as Activity

                    val menuItems = mutableMapOf(
                        "扫一扫" to (Icons.Default.QrCodeScanner to {
                            startActivityByName(
                                activity,
                                "com.tencent.mm.plugin.scanner.ui.BaseScanUI"
                            )
                        }),
                        "朋友圈" to (Icons.Default.Camera to {
                            startActivityByName(
                                activity,
                                "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"
                            )
                        }),
                        "钱包" to (Icons.Default.Wallet to {
                            startActivityByName(
                                activity,
                                "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"
                            )
                        }),
                        "视频号" to (Icons.Default.Movie to {
                            startActivityByName(
                                activity,
                                "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI"
                            )
                        }),
                        "设置" to (Icons.Default.Settings to {
                            startActivityByName(
                                activity,
                                "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
                            )
                        }),
                        "模块设置" to (Icons.Default.Extension to {
                            MainSettingsDialog(activity).show()
                        }),
                        "强制停止" to (Icons.Default.Cancel to {
                            Process.killProcess(Process.myPid())
                        }),
                        "全部已读" to (Icons.Default.Update to {
                            WeConversationApi.markAllAsRead()
                            ToastUtils.showToast("已将全部未读消息标为已读")
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

                    val lifecycleOwner = MainActivityLifecycleOwnerProvider.lifecycleOwner

                    val decorView = activity.window.decorView
                    decorView.setLifecycleOwner(lifecycleOwner)
                    val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                    rootView.setLifecycleOwner(lifecycleOwner)

                    rootView.addView(
                        ComposeView(activity).apply {
                            setLifecycleOwner(lifecycleOwner)

                            setContent {
                                // WeChat doesn't follow MaterialTheme so we don't use that too
                                // or else different color palettes clash and it's hideous
                                val isDark = isSystemInDarkTheme()
                                val backgroundColor =
                                    if (isDark) Color(0xFF191919) else Color(0xFFF7F7F7)
                                val activeColor =
                                    if (isDark) Color(0xFF06A854) else Color(0xFF09A854)

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
                                                            shape = RoundedCornerShape(8.dp),
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
                                                Icons.Filled.Add,
                                                contentDescription = null,
                                                tint = activeColor,
                                                modifier = Modifier.rotate(rotation)
                                            )
                                        }
                                    }
                                }
                            }
                        })
                }
            }
        }
    }
}