package moe.ouom.wekit.hooks.items.beautify

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.utils.XposedLifecycleOwner
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "美化/美化首页底部导航栏", desc = "将首页底部导航栏替换为 Jetpack Compose 组件")
object BeautifyMainScreenTabBar : BaseSwitchFunctionHookItem(), IDexFind {

    private const val TAG = "BeautifyMainScreenTabBar"

    private val methodDoOnCreate by dexMethod()

    override fun entry(classLoader: ClassLoader) {
        methodDoOnCreate.toDexMethod {
            hook {
                afterIfEnabled { param ->
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

                    val lifecycleOwner = XposedLifecycleOwner().apply { onCreate(); onResume() }
                    val decorView = activity.window.decorView

                    // Compose traverse up the view hierarchy to find a LifecycleOwner from the root or parent views
                    decorView.setViewTreeLifecycleOwner(lifecycleOwner)
                    decorView.setViewTreeViewModelStoreOwner(lifecycleOwner)
                    decorView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                    bottomTabViewGroup.setViewTreeLifecycleOwner(lifecycleOwner)
                    bottomTabViewGroup.setViewTreeViewModelStoreOwner(lifecycleOwner)
                    bottomTabViewGroup.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                    val selectedPageIndexState = mutableIntStateOf(0)
                    val scrollOffsetState = mutableFloatStateOf(0f)

                    tabsAdapter.asResolver()
                        .firstMethod { name = "onPageScrolled" }
                        .self.hookBefore { param ->
                            val position = param.args[0] as Int
                            val positionOffset = param.args[1] as Float

                            selectedPageIndexState.intValue = position
                            scrollOffsetState.floatValue = positionOffset
                        }

                    bottomTabViewGroup.removeAllViews()
                    WeLogger.i(TAG, "injected ComposeView into tab bar")
                    bottomTabViewGroup.addView(
                        ComposeView(activity).apply {
                            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)

                            setViewTreeLifecycleOwner(lifecycleOwner)
                            setViewTreeViewModelStoreOwner(lifecycleOwner)
                            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                            setContent {
                                val currentIndex by selectedPageIndexState
                                val offset by scrollOffsetState

                                // WeChat doesn't follow MaterialTheme so we don't use that too
                                // or else different color palettes clash and it's hideous
                                val isDark = isSystemInDarkTheme()
                                val backgroundColor = if (isDark) Color(0xFF191919) else Color(0xFFF7F7F7)
                                val activeColor = if (isDark) Color(0xFF07C160) else Color(0xFF07C160)
                                val inactiveColor = if (isDark) Color(0xFF999999) else Color(0xFF181818)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .background(backgroundColor),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val icons = listOf(
                                        Icons.Default.Home to "Home",
                                        Icons.Default.Contacts to "Contacts",
                                        Icons.Default.Explore to "Explore",
                                        Icons.Default.Person to "Me"
                                    )

                                    icons.forEachIndexed { index, (icon, label) ->
                                        val tint = when (index) {
                                            currentIndex -> {
                                                lerpColor(activeColor, inactiveColor, offset)
                                            }
                                            currentIndex + 1 -> {
                                                lerpColor(inactiveColor, activeColor, offset)
                                            }
                                            else -> inactiveColor
                                        }

                                        IconButton(
                                            onClick = {
                                                methodOnTabClick.invoke(index)
                                            },
                                            modifier = Modifier.weight(1f).fillMaxHeight()
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = label,
                                                tint = tint
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

    // 页面滑动颜色渐变
    private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            red = start.red + (stop.red - start.red) * f,
            green = start.green + (stop.green - start.green) * f,
            blue = start.blue + (stop.blue - start.blue) * f,
            alpha = start.alpha + (stop.alpha - start.alpha) * f
        )
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodDoOnCreate.find(dexKit, descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.ui.MainTabUI"
                usingEqStrings("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
            }
        }

        return descriptors
    }
}