package dev.ujhhgtg.wekit.features.items.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import java.util.WeakHashMap

/**
 * 消息进入动画 — 与闭源模块 Geek 的「物理引擎特效」语义和行为完全一致的移植。
 *
 * 原模块实现链 (geek.apk):
 * - `ns` case 4 (ChatHook) 对 ChattingDataAdapterV3
 *   (`com.tencent.mm.ui.chatting.adapter.k`) 的逐条绑定方法 (8.0.65 B / 8.0.67-69 E /
 *   8.0.71 F / 8.0.74-76 I, 参数 (ViewHolder, int)) hookAllMethods 挂 `k9(0)`。
 * - `k9(0)` after → `j9` (ChatUI 渲染器): `getItem(position).field_msgId` 判重,
 *   `field_createTime` 触发, `field_isSend` 决定滑入方向。
 *
 * 触发语义 (与 Geek 逐条一致, 由 j9.smali 还原):
 * 1. 消息 createTime (秒, 范围 [1e8,1e10] 时乘 1000 转毫秒) 距今 < 2000ms → 视为新消息,
 *    该行动画, 并在 rootView 上记录当前时间 (tag);
 * 2. 否则若开启「历史消息全跳动」: 距 rootView 记录的首次新消息渲染时间 >= 500ms 的所有
 *    换绑行都动画 (rootView 未记录时按 0 计, 恒成立);
 * 3. 同一行绑定到同一条消息 (tag 中的 msgId 未变) 不重复动画;
 * 4. 每次绑定都会先取消进行中的动画并复位 transform (Geek 的 `vh.j` + h40 取消)。
 *
 * 动画行为 (与 Geek 一致, 均使用 androidx.dynamicanimation 的同名弹簧参数):
 * - 弹跳入场: alpha 0→1 (250ms), scale 0.85→1.0 SpringForce(stiffness=300, dampingRatio=0.6),
 *   动画期间 HARDWARE layer;
 * - 平移滑入 / 重力掉落 (Geek 用 `key_slide_entrance_on` 开关 + `key_entrance_anim_style`,
 *   本实现合并为单一 `msg_entrance_style`: 0=弹跳 1=平移滑入 2=重力掉落, 默认 0):
 *   队列按 position 排序后每行错峰 45ms:
 *   - 平移滑入: translationX = ±120dp (自己发出的从右侧), spring 回 0
 *     (stiffness=300, dampingRatio=0.65);
 *   - 重力掉落: translationY = -250dp + scale 0.9, spring 回 0/1.0
 *     (translation stiffness=300, dampingRatio=0.5; scale stiffness=200, dampingRatio=0.6)。
 */
object MessageEntranceAnimation : ClickableFeature(), IResolveDex {

    override val technicalId = "消息进入动画"
    override val nameRes = R.string.feature_message_entrance_animation_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_message_entrance_animation_description

    /**
     * 入场动效风格: 0=弹跳入场 1=平移滑入 2=重力掉落。
     * Geek 的 `key_slide_entrance_on` + `key_entrance_anim_style` 合并为单一 key,
     * 默认弹跳; 旧双 key 配置不再读取 (无迁移)。
     */
    private var entranceStyle by prefOption("msg_entrance_style", STYLE_BOUNCE)

    /** 历史消息全跳动 (Geek `key_bounce_all_on_enter`, 默认关) */
    private var bounceAllOnEnter by prefOption("msg_entrance_bounce_all", false)

    private const val STYLE_BOUNCE = 0
    private const val STYLE_SLIDE = 1
    private const val STYLE_DROP = 2

    /** itemView 上记录最近一次绑定的消息 id (对应 Geek 0x7E060011) */
    private const val VIEW_TAG_MSG_ID = 0x7E000004

    /** rootView 上记录首次「新消息」渲染时间 (对应 Geek 0x7E120099) */
    private const val ROOT_TAG_FIRST_FRESH_RENDER = 0x7E000005

    /** 消息 createTime 距今小于该值视为新消息 */
    private const val FRESH_WINDOW_MS = 2000L

    /** 历史消息全跳动: 距首次新消息渲染至少该值才动画 */
    private const val BOUNCE_ALL_GAP_MS = 500L

    private const val FADE_DURATION_MS = 250L
    private const val SLIDE_STAGGER_MS = 45L
    private const val SLIDE_DISTANCE_DP = 120f
    private const val DROP_DISTANCE_DP = 250f

    private const val SPRING_STIFFNESS_BOUNCE = 300f
    private const val SPRING_DAMPING_BOUNCE = 0.6f
    private const val SPRING_STIFFNESS_TRANSLATION = 300f
    private const val SPRING_DAMPING_DROP = 0.5f
    private const val SPRING_DAMPING_SLIDE = 0.65f
    private const val SPRING_STIFFNESS_SCALE = 200f
    private const val SPRING_DAMPING_SCALE = 0.6f

    /** 进行中的弹簧动画, 绑定/复位时取消 (对应 Geek 的 h40 tag 集合) */
    private val activeSprings = WeakHashMap<View, MutableList<SpringAnimation>>()
    private val activeFades = WeakHashMap<View, ObjectAnimator>()
    private val animationGenerations = WeakHashMap<View, Int>()

    /** 侧滑队列 (对应 Geek 的 `ob0.a`), 下一帧按 position 排序错峰播放 */
    private val pendingSlides = ArrayList<SlideEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var drainPosted = false
    private val drainRunnable = Runnable { drainPendingSlides() }

    private class SlideEntry(
        val view: View,
        val position: Int,
        val msgId: Long,
        val style: Int,
        val generation: Int,
    )

    /**
     * ChattingDataAdapterV3 的逐条绑定方法。
     *
     * 8.0.65-8.0.76 上类名 `com.tencent.mm.ui.chatting.adapter.k` 与方法名
     * (B/E/F/I) 都随版本变化, 但绑定方法体内 `"_onBindViewHolder["` 日志常量 +
     * 双参 (holder, int) + void 返回在所有目标版本唯一, 故用字符串锚点而非方法名。
     * 该结构在所有支持版本均存在, 按项目约定不加 allowFailure。
     */
    /**
     * ChattingDataAdapterV3.getItem(int) -> 消息存储对象 (f8/d8/f9/e9, 各版本不同),
     * 用于读取稳定的 `field_msgId` / `field_createTime` / `field_isSend` 字段。
     * 所有支持版本均存在, 按项目约定不加 allowFailure。
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
        WeMessageApi.methodChattingDataAdapterOnBindViewHolder.hookAfter {
            val adapter = thisObject!!
            // 绑定方法参数在所有目标版本均为 (ViewHolder, int)
            val holder = args[0]!!
            val position = args[1] as Int

            // RecyclerView.ViewHolder.itemView (公开字段, 位于父类), 跨版本稳定
            val itemView = holder.reflekt()
                .firstField { name = "itemView"; superclass() }
                .get() as View

            val item = methodChattingAdapterGetItem.method.invoke(adapter, position)!!
            val msgInfo = MessageInfo(item)
            val msgId = msgInfo.id

            val now = System.currentTimeMillis()
            // Geek: createTime 为秒 (范围 [1e8,1e10]) 时转毫秒, 距今 < 2000ms 视为新消息
            val createTime = msgInfo.createTime
            val createTimeMillis =
                if (createTime in 100_000_000L..10_000_000_000L) createTime * 1000 else createTime
            val isFresh = now - createTimeMillis < FRESH_WINDOW_MS
            if (isFresh) {
                itemView.rootView.setTag(ROOT_TAG_FIRST_FRESH_RENDER, now)
            }

            // 首次绑定时 tag 尚未设置, 该值合法地为 null
            val previousMsgId = itemView.getTag(VIEW_TAG_MSG_ID) as Long?
            itemView.setTag(VIEW_TAG_MSG_ID, msgId)

            // 对应 Geek `vh.j` + h40 取消: 每次绑定都先复位行状态
            resetRow(itemView)

            // 同一行原地刷新 (进度/状态更新等), 不重复播放
            if (previousMsgId == msgId) return@hookAfter

            val shouldAnimate =
                isFresh || bounceAllOnEnter && now - (itemView.rootView.getTag(ROOT_TAG_FIRST_FRESH_RENDER) as? Long ?: 0L) >=
                        BOUNCE_ALL_GAP_MS
            if (!shouldAnimate) return@hookAfter

            if (entranceStyle == STYLE_BOUNCE) {
                playBounce(itemView)
            } else {
                val density = itemView.resources.displayMetrics.density
                val direction = SLIDE_DISTANCE_DP * density * if (msgInfo.isSend != 0) 1f else -1f
                queueSlideEntrance(itemView, direction, position, msgId)
            }
        }
    }

    override fun onDisable() {
        mainHandler.removeCallbacks(drainRunnable)
        drainPosted = false
        pendingSlides.clear()
        (activeSprings.keys + activeFades.keys + animationGenerations.keys)
            .toSet()
            .forEach(::resetRow)
        animationGenerations.clear()
        activeSprings.clear()
        activeFades.clear()
    }

    /** 对应 Geek `vh.j`: 取消动画并复位全部 transform */
    private fun resetRow(view: View) {
        animationGenerations[view] = (animationGenerations[view] ?: 0) + 1
        activeFades.remove(view)?.cancel()
        view.animate().cancel()
        cancelSprings(view)
        view.setLayerType(View.LAYER_TYPE_NONE, null)
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        view.rotationX = 0f
        view.rotationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        view.translationZ = 0f
    }

    /** 默认弹跳 (对应 Geek m9: scale 0.85 弹簧 + 250ms 淡入, HARDWARE layer) */
    private fun playBounce(view: View) {
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.alpha = 0f
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        startSpring(view, DynamicAnimation.SCALE_X, 1f, SPRING_STIFFNESS_BOUNCE, SPRING_DAMPING_BOUNCE)
        startSpring(view, DynamicAnimation.SCALE_Y, 1f, SPRING_STIFFNESS_BOUNCE, SPRING_DAMPING_BOUNCE)
        startFade(view, 0L)
    }

    /** 侧滑/掉落路径 (对应 Geek ob0.a + od 队列 + lb0 弹簧) */
    private fun queueSlideEntrance(view: View, direction: Float, position: Int, msgId: Long) {
        val style = entranceStyle
        if (style == STYLE_DROP) {
            // 重力掉落: 初始态为上方 -250dp + scale 0.9
            view.translationX = 0f
            view.translationY = -DROP_DISTANCE_DP * view.resources.displayMetrics.density
            view.scaleX = 0.9f
            view.scaleY = 0.9f
        } else {
            // 平移滑入: 初始态为两侧 ±120dp, scale 保持 1.0
            view.translationX = direction
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
        view.alpha = 0f
        pendingSlides += SlideEntry(
            view = view,
            position = position,
            msgId = msgId,
            style = style,
            generation = animationGenerations[view] ?: 0,
        )
        if (!drainPosted) {
            drainPosted = true
            mainHandler.post(drainRunnable)
        }
    }

    private fun drainPendingSlides() {
        drainPosted = false
        if (pendingSlides.isEmpty()) return

        val entries = pendingSlides.sortedBy { it.position }
        pendingSlides.clear()

        entries.forEachIndexed { index, entry ->
            // 行在入队后已被换绑到其他消息, 丢弃过期条目
            if (!isCurrent(entry)) return@forEachIndexed

            val delay = index * SLIDE_STAGGER_MS
            entry.view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            startFade(entry.view, delay)

            mainHandler.postDelayed({
                if (!isCurrent(entry)) return@postDelayed
                if (entry.style == STYLE_DROP) {
                    startSpring(entry.view, DynamicAnimation.TRANSLATION_Y, 0f,
                        SPRING_STIFFNESS_TRANSLATION, SPRING_DAMPING_DROP)
                    startSpring(entry.view, DynamicAnimation.SCALE_X, 1f,
                        SPRING_STIFFNESS_SCALE, SPRING_DAMPING_SCALE)
                    startSpring(entry.view, DynamicAnimation.SCALE_Y, 1f,
                        SPRING_STIFFNESS_SCALE, SPRING_DAMPING_SCALE)
                } else {
                    startSpring(entry.view, DynamicAnimation.TRANSLATION_X, 0f,
                        SPRING_STIFFNESS_TRANSLATION, SPRING_DAMPING_SLIDE)
                }
            }, delay)

            // 对应 Geek `p1(14, mb0)` 的 400ms 延迟清理
            mainHandler.postDelayed({
                if (isCurrent(entry)) {
                    entry.view.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            }, delay + 400L)
        }
    }

    private fun startFade(view: View, delay: Long) {
        val animator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f).apply {
            startDelay = delay
            duration = FADE_DURATION_MS
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) = finishFade(view, animator)
            override fun onAnimationEnd(animation: Animator) = finishFade(view, animator)
        })
        activeFades[view] = animator
        animator.start()
    }

    private fun finishFade(view: View, animator: ObjectAnimator) {
        if (activeFades[view] === animator) {
            activeFades.remove(view)
            view.alpha = 1f
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun isCurrent(entry: SlideEntry): Boolean =
        entry.view.getTag(VIEW_TAG_MSG_ID) == entry.msgId &&
                animationGenerations[entry.view] == entry.generation

    private fun startSpring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        finalPosition: Float,
        stiffness: Float,
        dampingRatio: Float,
    ) {
        val spring = SpringAnimation(view, property, finalPosition)
        spring.spring = SpringForce(finalPosition).apply {
            this.stiffness = stiffness
            this.dampingRatio = dampingRatio
        }
        spring.addEndListener { _, _, _, _ ->
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        activeSprings.getOrPut(view) { mutableListOf() }.add(spring)
        spring.start()
    }

    private fun cancelSprings(view: View) {
        activeSprings.remove(view)?.forEach { it.cancel() }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var styleInput by remember { mutableIntStateOf(entranceStyle) }
            var bounceAllInput by remember { mutableStateOf(bounceAllOnEnter) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_message_entrance_animation_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "bounce") {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_message_animation_bounce),
                                description = stringResource(R.string.chat_message_animation_bounce_description),
                                selected = styleInput == STYLE_BOUNCE,
                                onClick = {
                                    styleInput = STYLE_BOUNCE
                                    entranceStyle = STYLE_BOUNCE
                                },
                            )
                        }
                        item(key = "slide") {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_message_animation_slide),
                                description = stringResource(R.string.chat_message_animation_slide_description),
                                selected = styleInput == STYLE_SLIDE,
                                onClick = {
                                    styleInput = STYLE_SLIDE
                                    entranceStyle = STYLE_SLIDE
                                },
                            )
                        }
                        item(key = "drop") {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_message_animation_drop),
                                description = stringResource(R.string.chat_message_animation_drop_description),
                                selected = styleInput == STYLE_DROP,
                                onClick = {
                                    styleInput = STYLE_DROP
                                    entranceStyle = STYLE_DROP
                                },
                            )
                        }
                        item(key = "bounce_all") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_message_animation_history),
                                description = stringResource(R.string.chat_message_animation_history_description),
                                checked = bounceAllInput,
                                onCheckedChange = {
                                    bounceAllInput = it
                                    bounceAllOnEnter = it
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
