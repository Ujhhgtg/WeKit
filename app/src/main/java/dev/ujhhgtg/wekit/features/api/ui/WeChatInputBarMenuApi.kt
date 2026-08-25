package dev.ujhhgtg.wekit.features.api.ui

import android.content.Context
import android.widget.Button as AndroidButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Block
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.LocalSegmentedItemShape
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

object WeChatInputBarMenuApi : ApiFeature(), IResolveDex {

    override val technicalId = "聊天输入栏增强 API"
    override val nameRes = R.string.feature_we_chat_input_bar_menu_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_chat_input_bar_menu_api_description

    fun interface IActionItemsProvider {
        fun getActionItems(): List<ActionItem>
    }

    data class ActionItem(
        val id: String,
        val icon: ImageVector,
        val label: String,
        val isSupported: (Context, ChatFooter) -> Boolean = { _, _ -> true },
        val onClick: (Context, ChatFooter) -> Unit,
        val onLongClick: ((Context, ChatFooter) -> Unit)? = null
    )

    private const val TAG = "WeChatInputBarMenuApi"
    private val providers = mutableSetOf<IActionItemsProvider>()

    fun addProvider(provider: IActionItemsProvider) {
        providers += provider
    }

    fun removeProvider(provider: IActionItemsProvider) {
        providers -= provider
    }

    val methodSendMessage by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "send msg onClick")
        }
    }
    internal val methodAppGridGetView by dexMethod {
        matcher {
            usingStrings("MicroMsg.AppGrid", "pos:", "page:")
            name = "getView"
        }
    }

    /**
     * 在 ChatFooter 内定位原生发送按钮 (第 0 个子视图中文本为 "发送"/"send" 的 Button)。
     */
    fun findSendButton(chatFooter: ChatFooter): AndroidButton =
        chatFooter.findViewByChildIndexes(0)!!
            .findViewWhich { view ->
                view.javaClass.name == "android.widget.Button" && run {
                    val text = (view as AndroidButton).text?.toString()?.trim() ?: ""
                    text == "发送" || text.equals("send", ignoreCase = true)
                }
            }!! as AndroidButton

    /**
     * 模拟用户点击原生发送按钮: 取发送按钮上的 OnClickListener
     * (View 并不把监听器存为自身字段, 而是嵌在 View.ListenerInfo 里:
     * `mListenerInfo.mOnClickListener`), 直接调用其 onClick(View) 处理器
     * ([methodSendMessage]), 即用户点击发送键时实际执行的路径,
     * 保留引用、@ 等全部原生能力。
     */
    fun performSend(chatFooter: ChatFooter) {
        val sendBtn = findSendButton(chatFooter)
        val listenerInfo = sendBtn.reflekt().firstField {
            name = "mListenerInfo"
            superclass()
        }.get()!!
        val sendListener = listenerInfo.reflekt().firstField { name = "mOnClickListener" }.get()!!

        methodSendMessage.method.invoke(sendListener, sendBtn)
    }

    fun showMenu(context: Context, chatFooter: ChatFooter) {
        val applicableItems = providers
            .flatMap { it.getActionItems() }
            .filter { it.isSupported(context, chatFooter) }

        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.noncompose_chat_input_actions_title)) },
                text = {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        if (applicableItems.isEmpty()) {
                            item(key = "no_actions_placeholder") {
                                BaseWidget(
                                    icon = MaterialSymbols.Outlined.Block,
                                    title = stringResource(R.string.noncompose_chat_input_no_actions)
                                )
                            }
                        } else {
                            lazySegmentedItems(applicableItems, key = { it.id }) { item ->
                                ActionItemRow(
                                    item = item,
                                    context = context,
                                    chatFooter = chatFooter,
                                    onDismiss = { onDismiss() }
                                )
                            }
                        }
                    }
                },
                confirmButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ActionItemRow(
        item: ActionItem,
        context: Context,
        chatFooter: ChatFooter,
        onDismiss: () -> Unit
    ) {
        val handleClick = {
            onDismiss()
            try {
                item.onClick(context, chatFooter)
            } catch (ex: Throwable) {
                WeLogger.e(TAG, "exception occurred while handling click event for ${item.id}", ex)
            }
        }

        val handleLongClick = item.onLongClick?.let { longClick ->
            {
                try {
                    longClick(context, chatFooter)
                } catch (ex: Throwable) {
                    WeLogger.e(TAG, "exception occurred while handling long-click event for ${item.id}", ex)
                }
            }
        }

        BaseWidget(
            modifier = handleLongClick?.let { longClick ->
                Modifier
                    .clip(LocalSegmentedItemShape.current)
                    .combinedClickable(onClick = handleClick, onLongClick = longClick)
            } ?: Modifier,
            icon = item.icon,
            title = item.label,
            onClick = if (handleLongClick == null) handleClick else null
        )
    }
}
