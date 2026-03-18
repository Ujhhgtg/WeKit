package moe.ouom.wekit.hooks.items.beautify

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.hooks.api.ui.WeMainActivityBeautifyApi
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.LiquidBottomTab
import moe.ouom.wekit.ui.content.LiquidBottomTabs
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.MainActivityLifecycleOwnerProvider
import moe.ouom.wekit.ui.utils.setLifecycleOwner
import moe.ouom.wekit.ui.utils.showComposeDialog

@HookItem(
    path = "界面美化/美化首页底部导航栏",
    desc = "将首页底部导航栏替换为 Jetpack Compose 组件"
)
object ReplaceNavigationBar : ClickableHookItem() {

    private val ICONS = listOf(
        Icons.Default.Home to "主页",
        Icons.Default.Contacts to "联系人",
        Icons.Default.Explore to "发现",
        Icons.Default.Person to "我"
    )

    private const val KEY_USE_BACKDROP = "tab_bar_use_backdrop"

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter { param ->
            val activity = param.thisObject.asResolver()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity
            val viewPager = param.thisObject.asResolver()
                .firstField {
                    name = "mViewPager"
                }
                .get()!! as ViewGroup
            val tabsAdapter = param.thisObject.asResolver()
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

            val lifecycleOwner = MainActivityLifecycleOwnerProvider.lifecycleOwner
            val decorView = activity.window.decorView

            decorView.setLifecycleOwner(lifecycleOwner)
            bottomTabViewGroup.setLifecycleOwner(lifecycleOwner)

            val selectedPageIndexState = mutableIntStateOf(0)
            val scrollOffsetState = mutableFloatStateOf(0f)

            tabsAdapter.asResolver()
                .firstMethod { name = "onPageScrolled" }
                .hookBefore { param ->
                    val position = param.args[0] as Int
                    val positionOffset = param.args[1] as Float

                    selectedPageIndexState.intValue = position
                    scrollOffsetState.floatValue = positionOffset
                }

            val useBackdrop = WePrefs.getBoolOrFalse(KEY_USE_BACKDROP)

            bottomTabViewGroup.removeAllViews()
            bottomTabViewGroup.addView(
                ComposeView(activity).apply {
                    setLifecycleOwner(lifecycleOwner)

                    setContent {
                        var currentIndex by selectedPageIndexState

                        // WeChat doesn't follow MaterialTheme so we don't use that too
                        // or else different color palettes clash and it's hideous
                        val isDark = isSystemInDarkTheme()
                        val backgroundColor =
                            if (isDark) Color(0xFF191919) else Color(0xFFF7F7F7)
                        val activeColor = Color(0xFF07C160)
                        val inactiveColor =
                            if (isDark) Color(0xFF999999) else Color(0xFF181818)

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
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = label,
                                                tint = tint
                                            )
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
                                        Box(
                                            Modifier
                                                .size(28f.dp)
                                                .paint(
                                                    rememberVectorPainter(icon),
                                                    colorFilter = ColorFilter.tint(color)
                                                )
                                        )
                                        BasicText(
                                            label,
                                            style = TextStyle(color, 12f.sp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                })
        }
    }

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
                dismissButton = { TextButton(dismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        WePrefs.putBool(KEY_USE_BACKDROP, useBackdrop)
                        dismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}