package dev.ujhhgtg.wekit.hooks.items.beautify

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.LiquidBottomTab
import dev.ujhhgtg.wekit.ui.content.LiquidBottomTabs
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge

@HookItem(
    path = "界面美化/美化首页底部导航栏",
    description = "将首页底部导航栏替换为 Material Design 风格"
)
object ReplaceNavigationBar : ClickableHookItem(), IResolvesDex {

    private val ICONS = listOf(
        MaterialSymbols.OutlinedFilled.Home to "主页",
        MaterialSymbols.OutlinedFilled.Contacts to "联系人",
        MaterialSymbols.OutlinedFilled.Explore to "发现",
        MaterialSymbols.OutlinedFilled.Person to "我"
    )

    private const val KEY_USE_BACKDROP = "nav_bar_use_backdrop"

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject.asResolver()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity
            val viewPager = thisObject.asResolver()
                .firstField {
                    name = "mViewPager"
                }
                .get()!! as ViewGroup
            val tabsAdapter = thisObject.asResolver()
                .firstField {
                    name = "mTabsAdapter"
                }
                .get()!!
            val methodOnTabClick = tabsAdapter.asResolver()
                .firstMethod {
                    name = "onTabClick"
                }

            val viewParent = viewPager.parent as ViewGroup
            val bottomTabViewGroup = viewParent.getChildAt(1) as ViewGroup

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
            bottomTabViewGroup.setLifecycleOwner(lifecycleOwner)

            val selectedPageIndexState = mutableIntStateOf(0)
            val scrollOffsetState = mutableFloatStateOf(0f)

            tabsAdapter.asResolver()
                .firstMethod { name = "onPageScrolled" }
                .hookBefore {
                    val position = args[0] as Int
                    val positionOffset = args[1] as Float

                    selectedPageIndexState.intValue = position
                    scrollOffsetState.floatValue = positionOffset
                }

            val useBackdrop = WePrefs.getBoolOrFalse(KEY_USE_BACKDROP)

            bottomTabViewGroup.removeAllViews()
            bottomTabViewGroup.addView(
                ComposeView(activity).apply {
                    setLifecycleOwner(lifecycleOwner)

                    setContent {
                        AppTheme {
                            var currentIndex by selectedPageIndexState
                            val unreadCount by unreadCountState

                            // TODO: we currently use a custom background color without accent to match with wechat's overall style
                            //       might change this when MonetEngine covers more UI
                            val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7)
                            val activeColor = MaterialTheme.colorScheme.primary
                            val inactiveColor = MaterialTheme.colorScheme.outline

                            if (!useBackdrop) {
                                val offset by scrollOffsetState
                                NavigationBar(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    containerColor = backgroundColor
                                ) {
                                    ICONS.forEachIndexed { index, (icon, label) ->
                                        val isSelected = index == currentIndex
                                        val isNext = index == currentIndex + 1

                                        val tint = when {
                                            isSelected -> lerpColor(
                                                activeColor,
                                                inactiveColor,
                                                offset
                                            )

                                            isNext -> lerpColor(
                                                inactiveColor,
                                                activeColor,
                                                offset
                                            )

                                            else -> inactiveColor
                                        }

                                        NavigationBarItem(
                                            selected = isSelected && offset < 0.5f,
                                            onClick = { methodOnTabClick.invoke(index) },
                                            icon = {
                                                BadgedBox(
                                                    badge = {
                                                        if (index == 0 && unreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (unreadCount <= 99) unreadCount.toString() else "99+",
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = label,
                                                        tint = tint
                                                    )
                                                }
                                            },
                                            label = null,
                                            alwaysShowLabel = false,
                                            colors = NavigationBarItemDefaults.colors(
                                                indicatorColor = activeColor.copy(alpha = 0.15f),
                                                selectedIconColor = activeColor,
                                                unselectedIconColor = inactiveColor,
                                                selectedTextColor = activeColor,
                                                unselectedTextColor = inactiveColor
                                            )
                                        )
                                    }
                                }
                            } else {
                                LiquidBottomTabs(
                                    { currentIndex },
                                    { methodOnTabClick.invoke(it) },
                                    rememberLayerBackdrop(),
                                    4,
                                    activeColor
                                ) {
                                    ICONS.forEachIndexed { index, (icon, label) ->
                                        val color =
                                            if (currentIndex == index) activeColor else inactiveColor
                                        LiquidBottomTab({ currentIndex = index }) {
                                            BadgedBox(
                                                badge = {
                                                    if (index == 0 && unreadCount > 0) {
                                                        Badge(containerColor = Color(0xFFFF3B30)) {
                                                            Text(
                                                                if (unreadCount <= 99) unreadCount.toString() else "99+",
                                                                color = Color.White, fontSize = 10.sp
                                                            )
                                                        }
                                                    }
                                                }
                                            ) {
                                                Box(
                                                    Modifier
                                                        .size(28f.dp)
                                                        .paint(rememberVectorPainter(icon), colorFilter = ColorFilter.tint(color))
                                                )
                                            }
                                            BasicText(
                                                label,
                                                style = TextStyle(color, 12f.sp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                })
        }

        methodUpdateTabUnread.hookBefore {
            val count = args[0] as Int
            unreadCountState.intValue = count
            result = null
        }
    }

    private val unreadCountState = mutableIntStateOf(0)

    private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            red = start.red + (stop.red - start.red) * f,
            green = start.green + (stop.green - start.green) * f,
            blue = start.blue + (stop.blue - start.blue) * f,
            alpha = start.alpha + (stop.alpha - start.alpha) * f
        )
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var useBackdrop by remember {
                mutableStateOf(
                    WePrefs.getBoolOrFalse(KEY_USE_BACKDROP)
                )
            }

            AlertDialogContent(
                title = { Text("美化首页底部导航栏") },
                text = {
                    ListItem(
                        headlineContent = { Text("启用液态玻璃效果") },
                        trailingContent = {
                            Switch(
                                useBackdrop,
                                { useBackdrop = it })
                        }
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        WePrefs.putBool(KEY_USE_BACKDROP, useBackdrop)
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    private val methodUpdateTabUnread by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodUpdateTabUnread.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
                usingEqStrings("MicroMsg.LauncherUITabView", "updateMainTabUnread %d")
            }
        }
    }
}
