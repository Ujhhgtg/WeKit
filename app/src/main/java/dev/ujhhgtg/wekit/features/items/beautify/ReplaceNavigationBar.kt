package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Explore
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBar
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarItem
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

@Feature(name = "美化首页底部导航栏", categories = ["界面美化"], description = "将首页底部导航栏替换为 Material Design 或 Backdrop 风格")
object ReplaceNavigationBar : ClickableFeature(), IResolveDex {

    private data class NavItem(
        val outlined: ImageVector,
        val filled: ImageVector,
        val label: String
    )

    @Stable
    private val ICONS = listOf(
        NavItem(MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home, "主页"),
        NavItem(MaterialSymbols.Outlined.Contacts, MaterialSymbols.OutlinedFilled.Contacts, "联系人"),
        NavItem(MaterialSymbols.Outlined.Explore, MaterialSymbols.OutlinedFilled.Explore, "发现"),
        NavItem(MaterialSymbols.Outlined.Person, MaterialSymbols.OutlinedFilled.Person, "我")
    )

    private var useFloating by prefOption("nav_bar_use_floating", false)
    private var useBackdrop by prefOption("nav_bar_use_backdrop", false)
    private var showFinderBadge by prefOption("nav_bar_show_finder_badge", true)

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity
            val viewPager = thisObject.reflekt()
                .firstField {
                    name = "mViewPager"
                }
                .get()!! as ViewGroup
            val tabsAdapter = thisObject.reflekt()
                .firstField {
                    name = "mTabsAdapter"
                }
                .get()!!
            val methodOnTabClick = tabsAdapter.reflekt()
                .firstMethod {
                    name = "onTabClick"
                }.self

            val navigateToTab = { index: Int -> methodOnTabClick.invoke(tabsAdapter, index) }

            val viewParent = viewPager.parent as ViewGroup
            val bottomTabViewGroup = viewParent.getChildAt(1) as ViewGroup

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
            bottomTabViewGroup.setLifecycleOwner(lifecycleOwner)

            val selectedPageIndexState = mutableIntStateOf(0)
            val scrollOffsetState = mutableFloatStateOf(0f)

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageScrolled" }
                .hookBefore {
                    val position = args[0] as Int
                    val positionOffset = args[1] as Float

                    selectedPageIndexState.intValue = position
                    scrollOffsetState.floatValue = positionOffset
                }

            val useFloating = useFloating
            val useBackdrop = useBackdrop
            val showFinderBadge = showFinderBadge

            val composeView = ComposeView(activity).apply {
                setLifecycleOwner(lifecycleOwner)

                setContent {
                        AppTheme {
                            var selectedIndex by selectedPageIndexState
                            val unreadCount by unreadCountState
                            val finderUnreadCount by finderUnreadCountState
                            val showFinderDot by showFinderDotState

                            val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7)
                            val activeColor = MaterialTheme.colorScheme.primary
                            val inactiveColor = MaterialTheme.colorScheme.outline

                            if (!useFloating) {
                                val offset by scrollOffsetState
                                NavigationBar(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    containerColor = backgroundColor
                                ) {
                                    ICONS.forEachIndexed { index, item ->
                                        val isSelected = index == selectedIndex
                                        val isNext = index == selectedIndex + 1

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

                                        val showFilled = if (offset < 0.5f) isSelected else isNext
                                        val currentIcon = if (showFilled) item.filled else item.outlined

                                        NavigationBarItem(
                                            selected = isSelected && offset < 0.5f,
                                            onClick = { navigateToTab(index) },
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
                                                        } else if (index == 2 && showFinderBadge) {
                                                            if (finderUnreadCount > 0) {
                                                                Badge(containerColor = Color(0xFFFF3B30)) {
                                                                    Text(
                                                                        if (finderUnreadCount <= 99) finderUnreadCount.toString() else "99+",
                                                                        color = Color.White, fontSize = 10.sp
                                                                    )
                                                                }
                                                            } else if (showFinderDot) {
                                                                Badge(containerColor = Color(0xFFFF3B30))
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = currentIcon,
                                                        contentDescription = item.label,
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
                                Box(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    FloatingBottomBar(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = {},
                                            )
                                            .padding(
                                                bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues()
                                                    .calculateBottomPadding()
                                            ),
                                        selectedIndex = { selectedIndex },
                                        onSelected = { navigateToTab(it) },
                                        backdrop = rememberLayerBackdrop(),
                                        tabsCount = ICONS.size,
                                        isBlurEnabled = useBackdrop
                                    ) {
                                        ICONS.forEachIndexed { index, item ->
                                            val isSelected = index == selectedIndex
                                            val currentIcon = if (isSelected) item.filled else item.outlined

                                            FloatingBottomBarItem(
                                                onClick = { navigateToTab(index) },
                                                modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                                            ) {
                                                BadgedBox(
                                                    badge = {
                                                        if (index == 0 && unreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (unreadCount <= 99) unreadCount.toString() else "99+",
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (index == 2 && showFinderBadge) {
                                                            if (finderUnreadCount > 0) {
                                                                Badge(containerColor = Color(0xFFFF3B30)) {
                                                                    Text(
                                                                        if (finderUnreadCount <= 99) finderUnreadCount.toString() else "99+",
                                                                        color = Color.White, fontSize = 10.sp
                                                                    )
                                                                }
                                                            } else if (showFinderDot) {
                                                                Badge(containerColor = Color(0xFFFF3B30))
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = currentIcon,
                                                        contentDescription = item.label
                                                    )
                                                }
                                                Text(
                                                    text = item.label,
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Visible
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }

            if (useFloating) {
                // In floating mode, hide the original tab bar container so that WeChat's
                // FrostedContentView reads its height as 0 and doesn't draw a frosted gray
                // overlay behind it. Instead, attach the ComposeView directly to the parent
                // FrameLayout as an overlay on top of the content.
                bottomTabViewGroup.removeAllViews()
                bottomTabViewGroup.visibility = View.GONE

                viewParent.addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    )
                )
            } else {
                bottomTabViewGroup.removeAllViews()
                bottomTabViewGroup.addView(composeView)
            }
        }

        methodUpdateTabUnread.hookBefore {
            val count = args[0] as Int
            unreadCountState.intValue = count
            result = null
        }

        methodUpdateFriendTabUnread.hookBefore {
            val count = args[0] as Int
            finderUnreadCountState.intValue = count
            result = null
        }

        methodShowFriendPoint.hookBefore {
            val show = args[0] as Boolean
            showFinderDotState.value = show
            result = null
        }
    }

    private val unreadCountState = mutableIntStateOf(0)
    private val finderUnreadCountState = mutableIntStateOf(0)
    private val showFinderDotState = mutableStateOf(false)

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
            var useFloatingInput by remember { mutableStateOf(useFloating) }
            var useBackdropInput by remember { mutableStateOf(useBackdrop) }
            var showFinderBadgeInput by remember { mutableStateOf(showFinderBadge) }

            AlertDialogContent(
                title = { Text("美化首页底部导航栏") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("使用悬浮底栏") },
                            trailingContent = {
                                Switch(
                                    useFloatingInput,
                                    { useFloatingInput = it })
                            }
                        )
                        ListItem(
                            headlineContent = { Text("启用液态玻璃效果") },
                            supportingContent = { Text("需启用「使用悬浮底栏」") },
                            trailingContent = {
                                Switch(
                                    useBackdropInput,
                                    { useBackdropInput = it })
                            }
                        )
                        ListItem(
                            headlineContent = { Text("显示「发现」标签角标") },
                            supportingContent = { Text("包含朋友圈新通知数量等") },
                            trailingContent = {
                                Switch(
                                    showFinderBadgeInput,
                                    { showFinderBadgeInput = it })
                            }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        useFloating = useFloatingInput
                        useBackdrop = useBackdropInput
                        showFinderBadge = showFinderBadgeInput
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    private val methodUpdateTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("MicroMsg.LauncherUITabView", "updateMainTabUnread %d")
        }
    }

    private val methodUpdateFriendTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[updateFriendTabUnread] unread : ")
        }
    }

    private val methodShowFriendPoint by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[showFriendPoint] show : ")
        }
    }
}
