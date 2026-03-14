package moe.ouom.wekit.hooks.items.chat

import android.os.Handler
import android.os.Looper
import android.widget.ListView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeConversationApi
import moe.ouom.wekit.ui.utils.AppTheme
import moe.ouom.wekit.ui.utils.MainActivityLifecycleOwnerProvider
import moe.ouom.wekit.ui.utils.setLifecycleOwner
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/对话分组", desc = "向主页顶部添加 Tab 栏, 将对话分组")
object ConversationGrouping : SwitchHookItem(), IDexFind {

    override fun onLoad() {
        methodOnTabCreate.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val listView = param.thisObject.asResolver()
                        .firstField {
                            type = "com.tencent.mm.ui.conversation.ConversationListView"
                        }
                        .get()!! as ListView

                    val composeView = ComposeView(listView.context).apply {
                        val lifecycleOwner = MainActivityLifecycleOwnerProvider.lifecycleOwner
                        setLifecycleOwner(lifecycleOwner)

                        // the value gets lost when ComposeView becomes invisible,
                        // so we have to lift it out of the Composable
                        val selectedIndexState = mutableIntStateOf(0)
                        setContent {
                            AppTheme {
                                var selectedIndex by selectedIndexState

                                ConversationTabs(
                                    listOf("全部", "未读", "群聊", "好友", "公众号"),
                                    selectedIndex,
                                    {
                                        selectedIndex = it
                                        Handler(Looper.getMainLooper()).post {
                                            when (selectedIndex) {
                                                0 -> {
                                                    WeConversationApi.setAllConversationVisibility(
                                                        true
                                                    )
                                                }

                                                1 -> {
                                                    WeConversationApi.onlyShowFilteredConversations(
                                                        "WHERE unReadCount>0 OR unReadMuteCount>0"
                                                    )
                                                }

                                                2 -> {
                                                    WeConversationApi.onlyShowFilteredConversations(
                                                        "WHERE username LIKE '%@chatroom'"
                                                    )
                                                }

                                                3 -> {
                                                    WeConversationApi.onlyShowFilteredConversations(
                                                        "WHERE username LIKE 'wxid_%'"
                                                    )
                                                }

                                                4 -> {
                                                    WeConversationApi.onlyShowFilteredConversations(
                                                        "WHERE username LIKE 'gh_%'"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    listView.addHeaderView(composeView)
                }
            }
        }
    }

    private val methodOnTabCreate by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodOnTabCreate.find(dexKit, descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.ui.conversation.MainUI"
                usingEqStrings("MicroMsg.MainUI", "onTabCreate, %d")
            }
        }

        return descriptors
    }
}

@Composable
private fun ConversationTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7),
    selectedContentColor: Color = MaterialTheme.colorScheme.primary,
    unselectedContentColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
) {
    val density = LocalDensity.current
    val indicatorOffsets = remember { mutableStateListOf<Dp>() }
    val indicatorWidths = remember { mutableStateListOf<Dp>() }
    val indicatorOffset by animateDpAsState(
        targetValue = indicatorOffsets.getOrElse(selectedIndex) { 0.dp },
        animationSpec = tween(durationMillis = 250), label = "indicator_offset"
    )
    val indicatorWidth by animateDpAsState(
        targetValue = indicatorWidths.getOrElse(selectedIndex) { 0.dp },
        animationSpec = tween(durationMillis = 250), label = "indicator_width"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, title ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTabSelected(index) }
                        .padding(vertical = 14.dp)
                        .onGloballyPositioned { coords ->
                            val offsetDp = with(density) { coords.positionInParent().x.toDp() }
                            val widthDp = with(density) { coords.size.width.toDp() }
                            if (indicatorOffsets.size <= index) {
                                indicatorOffsets.add(offsetDp)
                                indicatorWidths.add(widthDp)
                            } else {
                                indicatorOffsets[index] = offsetDp
                                indicatorWidths[index] = widthDp
                            }
                        }
                ) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == selectedIndex) selectedContentColor else unselectedContentColor
                        )
                    )
                }
            }
        }

        // Indicator
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .width(indicatorWidth)
                .height(3.dp)
                .background(indicatorColor)
        )
    }
}