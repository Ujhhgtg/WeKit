package moe.ouom.wekit.hooks.items.beautify

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import moe.ouom.wekit.ui.compose.XposedLifecycleOwner
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "美化/美化首页底部导航栏", desc = "将首页底部导航栏替换为 Jetpack Compose 组件")
object BeautifyMainScreenTabBar : BaseSwitchFunctionHookItem(), IDexFind {

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
                    val methodOnPageSelected = tabsAdapter.asResolver()
                        .firstMethod {
                            name = "onTabClick"
                        }

                    val viewParent = viewPager.parent as ViewGroup
                    val bottomTabView = viewParent.getChildAt(1) as ViewGroup

                    val lifecycleOwner = XposedLifecycleOwner().apply { onCreate(); onResume() }
                    val decorView = activity.window.decorView

                    // Compose traverse up the view hierarchy to find a LifecycleOwner from the root or parent views
                    decorView.setViewTreeLifecycleOwner(lifecycleOwner)
                    decorView.setViewTreeViewModelStoreOwner(lifecycleOwner)
                    decorView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                    bottomTabView.setViewTreeLifecycleOwner(lifecycleOwner)
                    bottomTabView.setViewTreeViewModelStoreOwner(lifecycleOwner)
                    bottomTabView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                    bottomTabView.removeAllViews()
                    bottomTabView.addView(
                        ComposeView(activity).apply {
                            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                            setViewTreeLifecycleOwner(lifecycleOwner)
                            setViewTreeViewModelStoreOwner(lifecycleOwner)
                            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                            setContent {
                                // WeChat doesn't follow MaterialTheme so we don't use that too
                                // or else different color palettes clash and it's hideous
                                val isDark = isSystemInDarkTheme()
                                val backgroundColor = if (isDark) Color(0xFF191919) else Color(0xFFF7F7F7)
                                val activeColor = if (isDark) Color(0xFF07C160) else Color(0xFF07C160)
                                val inactiveColor = if (isDark) Color(0xFF999999) else Color(0xFF181818)

                                var selectedPageIndex by remember { mutableIntStateOf(0) }

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
                                        val interactionSource = remember { MutableInteractionSource() }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clickable(
                                                    interactionSource = interactionSource,
                                                    indication = ripple(
                                                        bounded = false,
                                                        radius = 28.dp,
                                                        color = activeColor
                                                    ),
                                                    onClick = {
                                                        selectedPageIndex = index
                                                        methodOnPageSelected.invoke(index)
                                                    }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = label,
                                                tint = if (index == selectedPageIndex) activeColor else inactiveColor
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