package dev.ujhhgtg.wekit.features.items.chat

import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import java.util.WeakHashMap

/**
 * 消息进入动画 — 聊天界面中单条消息进入屏幕时播放入场动画。
 *
 * 参考闭源模块 Geek 的「新消息特效」实现:
 * - Geek 主开关 `key_chat_anim_on` (默认开), 子项 `key_slide_entrance_on` (侧滑入场, 默认关 =>
 *   默认弹跳), `key_entrance_anim_style` (1 平移滑入 / 2 重力掉落, 默认 1) 与
 *   `key_bounce_all_on_enter` (历史消息全跳动, 默认关)。
 * - Geek 在聊天适配器 ChattingDataAdapterV3 (`com.tencent.mm.ui.chatting.adapter.k`) 的逐条
 *   绑定方法 (8.0.65 B / 8.0.67-69 E / 8.0.71 F / 8.0.74-76 I, 参数为 (ViewHolder, int))
 *   after hook 中, 通过 `getItem(position).field_msgId` 判断该 itemView 是否刚换绑到一条新消息,
 *   再对 itemView 播放弹跳 (scale 0.85 + 弹簧/淡入)、平移滑入 (±120dp, 自己发出的从右侧) 或
 *   重力掉落 (-250dp) 动画。
 *
 * 触发语义说明 (与 Geek 有意的差异): Geek 默认只在进程启动后极短时间内 (或其 uptime 分支) 播放,
 * 其余情况需开启「历史消息全跳动」; 本实现默认对「进入屏幕的新消息」播放 (聊天打开时刷出的历史消息
 * 洪流除外), 打开「历史消息全跳动」后进入聊天时全部历史消息也逐条动画, 更贴合本功能的描述。
 */
@Feature(
    name = "消息进入动画",
    categories = ["聊天"],
    description = "聊天界面中单条消息进入屏幕时播放入场动画, 支持弹跳/平移滑入/重力掉落, 可让历史消息全跳动"
)
object MessageEntranceAnimation : ClickableFeature(), IResolveDex {

    /** 侧滑入场 (默认弹跳), 对应 Geek 的 `key_slide_entrance_on` */
    private var slideEntrance by prefOption("msg_entrance_slide", false)

    /** 入场动效风格, 仅在 [slideEntrance] 开启时生效, 对应 Geek 的 `key_entrance_anim_style` */
    private var entranceStyle by prefOption("msg_entrance_style", STYLE_SLIDE)

    /** 历史消息全跳动, 对应 Geek 的 `key_bounce_all_on_enter` */
    private var bounceAllOnEnter by prefOption("msg_entrance_bounce_all", false)

    private const val STYLE_SLIDE = 0
    private const val STYLE_DROP = 1

    /** itemView 上记录最近一次绑定的消息 id, 用于跳过同一条消息的原地刷新 */
    private const val VIEW_TAG_MSG_ID = 0x7E000004

    /** 进入聊天后, 此窗口内刷出的历史消息视为初始洪流, 默认不播放 (开启全跳动后除外) */
    private const val INITIAL_BURST_MS = 600L

    /** 适配器会话超时: 超过该间隔后的首次绑定视为新会话 */
    private const val SESSION_RESET_MS = 60_000L

    /** 每个聊天适配器实例的首次绑定时间 (用于识别初始历史消息洪流) */
    private val sessionStart = WeakHashMap<Any, Long>()

    /**
     * ChattingDataAdapterV3 的逐条绑定方法。
     *
     * 8.0.65-8.0.76 上类名 `com.tencent.mm.ui.chatting.adapter.k` 与方法名
     * (B/E/F/I) 都随版本变化, 但绑定方法体内 `"_onBindViewHolder["` 日志常量 +
     * 双参 (holder, int) + void 返回在所有目标版本唯一, 故用字符串锚点而非方法名。
     * 该结构在所有支持版本均存在, 按项目约定不加 allowFailure。
     */
    private val methodChattingAdapterOnBindViewHolder by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ChattingDataAdapterV3")
            }
            usingEqStrings("_onBindViewHolder[")
            paramTypes(null, Int::class.java)
            returnType("void")
        }
    }

    /**
     * ChattingDataAdapterV3.getItem(int) -> 消息存储对象 (f8/d8/f9/e9, 各版本不同),
     * 用于读取稳定的 `field_msgId` / `field_isSend` 字段。所有支持版本均存在,
     * 按项目约定不加 allowFailure。
     */
    private val methodChattingAdapterGetItem by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ChattingDataAdapterV3")
            }
            name = "getItem"
            paramTypes(Int::class.java)
        }
    }

    override fun onEnable() {
        methodChattingAdapterOnBindViewHolder.hookAfter {
            val adapter = thisObject!!
            val holder = args[0]!!
            val position = args[1] as Int

            val itemView = holder.reflekt()
                .firstField { name = "itemView"; superclass() }
                .get() as View

            val item = methodChattingAdapterGetItem.method.invoke(adapter, position)!!
            val msgInfo = MessageInfo(item)
            val msgId = msgInfo.id

            val now = SystemClock.uptimeMillis()
            val previousMsgId = itemView.getTag(VIEW_TAG_MSG_ID) as Long?
            itemView.setTag(VIEW_TAG_MSG_ID, msgId)

            // 同一行原地刷新 (进度/状态更新等), 不重复播放
            if (previousMsgId == msgId) return@hookAfter

            val start = sessionStart[adapter]
            if (start == null || now - start > SESSION_RESET_MS) {
                // 新会话的首次绑定: 属于打开聊天时的历史消息洪流
                sessionStart[adapter] = now
                if (!bounceAllOnEnter) return@hookAfter
            } else if (!bounceAllOnEnter && now - start < INITIAL_BURST_MS) {
                // 初始洪流窗口内 (未开启全跳动): 跳过
                return@hookAfter
            }

            playEntrance(itemView, msgInfo.isSend != 0)
        }
    }

    override fun onDisable() {
        sessionStart.clear()
    }

    private fun playEntrance(itemView: View, isSend: Boolean) {
        // 行被回收复用时先取消并复位, 避免上一次动画残留
        itemView.animate().cancel()
        itemView.translationX = 0f
        itemView.translationY = 0f
        itemView.scaleX = 1f
        itemView.scaleY = 1f
        itemView.alpha = 1f

        val density = itemView.resources.displayMetrics.density

        if (!slideEntrance) {
            // 默认弹跳: 缩小 + 淡入, Overshoot 制造轻微回弹 (对应 Geek 默认的弹簧入场)
            itemView.alpha = 0f
            itemView.scaleX = 0.85f
            itemView.scaleY = 0.85f
            itemView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()
        } else if (entranceStyle == STYLE_DROP) {
            // 重力掉落: 从上方 250dp 掉入
            itemView.alpha = 0f
            itemView.translationY = -250f * density
            itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        } else {
            // 平移滑入: 自己发出的从右侧 (+120dp), 收到的从左侧 (-120dp), 轻微缩放
            val distance = 120f * density
            itemView.alpha = 0f
            itemView.scaleX = 0.9f
            itemView.scaleY = 0.9f
            itemView.translationX = if (isSend) distance else -distance
            itemView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .setDuration(250L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var slideInput by remember { mutableStateOf(slideEntrance) }
            var styleInput by remember { mutableStateOf(entranceStyle) }
            var bounceAllInput by remember { mutableStateOf(bounceAllOnEnter) }

            AlertDialogContent(
                title = { Text("消息进入动画") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                slideInput = !slideInput
                                slideEntrance = slideInput
                            },
                            trailingContent = {
                                Switch(checked = slideInput, onCheckedChange = null)
                            },
                            supportingContent = { Text("关闭时为弹跳入场; 开启后消息从屏幕两侧滑入") },
                            headlineContent = { Text("侧滑入场 (默认弹跳)") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                styleInput = STYLE_SLIDE
                                entranceStyle = styleInput
                            },
                            trailingContent = {
                                RadioButton(selected = styleInput == STYLE_SLIDE, onClick = null)
                            },
                            supportingContent = { Text("消息水平平移滑入, 自己发出的从右侧、收到的从左侧") },
                            headlineContent = { Text("平移滑入") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                styleInput = STYLE_DROP
                                entranceStyle = styleInput
                            },
                            trailingContent = {
                                RadioButton(selected = styleInput == STYLE_DROP, onClick = null)
                            },
                            supportingContent = { Text("消息从上方掉落进入, 仅在侧滑入场开启时生效") },
                            headlineContent = { Text("重力掉落") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                bounceAllInput = !bounceAllInput
                                bounceAllOnEnter = bounceAllInput
                            },
                            trailingContent = {
                                Switch(checked = bounceAllInput, onCheckedChange = null)
                            },
                            supportingContent = { Text("进入聊天界面时历史消息也逐条播放入场动画; 关闭时只动画新进入的消息") },
                            headlineContent = { Text("历史消息全跳动") },
                        )
                    }
                })
        }
    }
}
