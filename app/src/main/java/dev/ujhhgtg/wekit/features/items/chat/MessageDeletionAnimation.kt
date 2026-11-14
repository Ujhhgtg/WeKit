package dev.ujhhgtg.wekit.features.items.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Picture
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import androidx.core.graphics.drawable.toDrawable
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.animation.ThanosEffect
import java.util.WeakHashMap

/**
 * 消息删除动画 — Telegram 风格 (Telegram `ChatListItemAnimator` + `ThanosEffect`)。
 *
 * 宿主 MMChattingListView 把聊天 RecyclerView 的 ItemAnimator 置为 null, 删除消息时该行 直接消失、其余行瞬间跳位。宿主
 * RecyclerView/ItemAnimator 均被混淆, 模块无法继承实现 ItemAnimator, 因此采用「快照 + 覆盖层」:
 * 1. MsgInfoStorageLogic 按 msgId 删除单条消息 (长按删除 / 多选删除 / 重发等共用) 调用前, 在主线程对仍在屏幕上的对应行截图, 并记录列表内所有行的可视
 *    top;
 * 2. 之后列表的首个 pre-draw 中若这些行都已离开列表 (被回收或换绑到其他消息), 即宿主已完成 刷新, 在同一帧开始动画:
 *     - 被删行: 支持 GL 时为 Telegram 的灭霸粒子消散, 否则 120ms 淡出 (Telegram 移除时长);
 *     - 其余行: 从旧位置平移归位, 普通为 250ms + Telegram `DEFAULT_INTERPOLATOR`, 灭霸时为 475ms + `EASE_OUT`
 *       (`ChatListItemAnimator.animateMoveImpl`)。
 */
object MessageDeletionAnimation : SwitchFeature() {
    override val technicalId = "消息删除动画"
    override val nameRes = R.string.feature_message_deletion_animation_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_message_deletion_animation_description

    /** itemView 上记录当前绑定的消息 id */
    private const val VIEW_TAG_MSG_ID = 0x7E000006

    /** 截图后宿主迟迟不刷新列表 (如删除失败) 时放弃本次动画 */
    private const val REFRESH_TIMEOUT_MS = 1500L

    private const val FADE_DURATION_MS = 120L
    private const val MOVE_DURATION_MS = 250L
    private const val THANOS_MOVE_DURATION_MS = 475L

    /** Telegram `ChatListItemAnimator.DEFAULT_INTERPOLATOR` */
    private val MOVE_INTERPOLATOR =
        PathInterpolator(0.19919473f, 0.010644531f, 0.27920938f, 0.9102539f)

    /** Telegram `CubicBezierInterpolator.EASE_OUT` */
    private val THANOS_MOVE_INTERPOLATOR = PathInterpolator(0f, 0f, 0.58f, 1f)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 绑定过消息的行, 删除时从中找出仍显示的对应行 */
    private val boundRows = WeakHashMap<View, Unit>()

    /** 非主线程 (多选删除) 触发的待截图 msgId, 合并后在主线程统一处理 */
    private val pendingMsgIds = LinkedHashSet<Long>()
    private val drainPendingRunnable = Runnable {
        val msgIds =
            synchronized(pendingMsgIds) {
                pendingMsgIds.toSet().also { pendingMsgIds.clear() }
            }
        captureRemovals(msgIds)
    }

    private val sessions = WeakHashMap<ViewGroup, Session>()
    private val thanosEffects = WeakHashMap<ViewGroup, ThanosEffect>()

    /** 同 [MessageEntranceAnimation]: ChattingDataAdapterV3.getItem(int) -> 消息存储对象 */
    private val methodChattingAdapterGetItem by dexMethod {
        name = "getItem"
        paramTypes("int")
        declaredClass {
            usingEqStrings("MicroMsg.ChattingDataAdapterV3")
        }
    }

    /**
     * MsgInfoStorageLogic 的 `static int (long msgId, String talker)`: 查出消息、发布 DeleteMsgEvent 后按 id
     * 删除
     */
    private val methodDeleteMsgById by dexMethod {
        returnType = "int"
        paramTypes("long", "java.lang.String")
        modifiers = 8
        declaredClass {
            usingEqStrings("MicroMsg.MsgInfoStorageLogic")
            usingEqStrings("deleteMsgByTalkerSync ")
        }
    }

    override fun onEnable() {
        WeMessageApi.methodChattingDataAdapterOnBindViewHolder.hookAfter {
            // RecyclerView.ViewHolder.itemView
            val itemView =
                args[0]!!
                    .reflekt()
                    .firstField {
                        name = "itemView"
                        superclass()
                    }
                    .get() as View
            val item = methodChattingAdapterGetItem.method.invoke(thisObject, args[1] as Int)!!
            itemView.setTag(VIEW_TAG_MSG_ID, MessageInfo(item).id)
            boundRows[itemView] = Unit
        }

        methodDeleteMsgById.hookBefore {
            val msgId = args[0] as Long
            if (Looper.myLooper() == Looper.getMainLooper()) {
                captureRemovals(setOf(msgId))
            } else {
                // 宿主的列表刷新晚于删除本身才会投递到主线程, 这里先投递可保证截图时行仍在
                synchronized(pendingMsgIds) {
                    if (pendingMsgIds.isEmpty()) mainHandler.post(drainPendingRunnable)
                    pendingMsgIds += msgId
                }
            }
        }
    }

    override fun onDisable() {
        mainHandler.removeCallbacks(drainPendingRunnable)
        synchronized(pendingMsgIds) { pendingMsgIds.clear() }
        sessions.values.toList().forEach { it.end() }
        thanosEffects.values.toList().forEach { it.kill() }
        boundRows.clear()
    }

    private fun captureRemovals(msgIds: Set<Long>) {
        val rows =
            boundRows.keys.filter {
                it.getTag(VIEW_TAG_MSG_ID) in msgIds &&
                    it.isAttachedToWindow &&
                    it.isShown &&
                    it.width > 0 &&
                    it.height > 0
            }
        for ((parent, listRows) in rows.groupBy { it.parent }) {
            val list = parent as ViewGroup
            val session = sessions.getOrPut(list) { Session(list).also { it.begin() } }
            listRows.forEach(session::add)
        }
    }

    /** 已绑定消息的行按 msgId 跨换绑对应, 其余子 View (头部/加载行等) 按自身对应 */
    private fun keyOf(child: View): Any = child.getTag(VIEW_TAG_MSG_ID) ?: child

    private fun snapshot(view: View): Bitmap {
        // 经 Picture 由硬件渲染, 行内可能含 HARDWARE 位图, 软件 Canvas 无法绘制
        val picture = Picture()
        view.draw(picture.beginRecording(view.width, view.height))
        picture.endRecording()
        return Bitmap.createBitmap(picture)
    }

    private class Removal(
        val msgId: Long,
        val view: View,
        val bitmap: Bitmap,
        val left: Int,
        val top: Int,
    )

    private class Session(val list: ViewGroup) : ViewTreeObserver.OnPreDrawListener {
        private val removals = ArrayList<Removal>()
        private val oldTops = HashMap<Any, Float>()
        private val timeout = Runnable { end() }

        fun begin() {
            recordTops()
            list.viewTreeObserver.addOnPreDrawListener(this)
            mainHandler.postDelayed(timeout, REFRESH_TIMEOUT_MS)
        }

        fun add(row: View) {
            if (removals.any { it.view === row }) return
            removals +=
                Removal(
                    msgId = row.getTag(VIEW_TAG_MSG_ID) as Long,
                    view = row,
                    bitmap = snapshot(row),
                    left = (row.left + row.translationX).toInt(),
                    top = (row.top + row.translationY).toInt(),
                )
            // 同一批删除中途若已发生过布局, 补记新出现的行
            recordTops()
        }

        private fun recordTops() {
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                oldTops.putIfAbsent(keyOf(child), child.top + child.translationY)
            }
        }

        fun end() {
            mainHandler.removeCallbacks(timeout)
            list.viewTreeObserver.removeOnPreDrawListener(this)
            if (sessions[list] === this) sessions.remove(list)
        }

        override fun onPreDraw(): Boolean {
            val refreshed = removals.none {
                it.view.parent === list && it.view.getTag(VIEW_TAG_MSG_ID) == it.msgId
            }
            if (refreshed) {
                end()
                val thanos = ThanosEffect.supported
                removals.forEach { animateRemoval(it, thanos) }
                animateMoves(thanos)
            }
            return true
        }

        private fun animateRemoval(removal: Removal, thanos: Boolean) {
            val bitmap = removal.bitmap
            val placeholder = bitmap.toDrawable(list.resources)
            placeholder.setBounds(
                removal.left,
                removal.top,
                removal.left + bitmap.width,
                removal.top + bitmap.height,
            )
            list.overlay.add(placeholder)

            if (thanos) {
                val root = list.rootView as ViewGroup
                val listLocation = IntArray(2).also(list::getLocationInWindow)
                val rootLocation = IntArray(2).also(root::getLocationInWindow)
                // 粒子首帧上屏后再撤掉覆盖层上的静态快照, 避免中间闪空
                obtainThanosEffect(root)
                    .animate(
                        bitmap = bitmap,
                        left = (listLocation[0] - rootLocation[0] + removal.left).toFloat(),
                        top = (listLocation[1] - rootLocation[1] + removal.top).toFloat(),
                        onStart = { list.overlay.remove(placeholder) },
                        onDone = {},
                    )
            } else {
                ValueAnimator.ofInt(255, 0).apply {
                    duration = FADE_DURATION_MS
                    addUpdateListener { placeholder.alpha = it.animatedValue as Int }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) =
                                list.overlay.remove(placeholder)
                        },
                    )
                    start()
                }
            }
        }

        private fun animateMoves(thanos: Boolean) {
            val children = (0 until list.childCount).map(list::getChildAt)
            val deltas = children.map { child -> oldTops[keyOf(child)]?.minus(child.top) }
            val known = deltas.indices.filter { deltas[it] != null }
            if (known.isEmpty()) return

            children.forEachIndexed { index, child ->
                // 刷新后新进入列表的行 (如填补空位的上方历史消息) 跟随最近的已知行一起移动
                val delta = deltas[index] ?: deltas[known.minBy { kotlin.math.abs(it - index) }]!!
                child.animate().cancel()
                if (delta == 0f) {
                    child.translationY = 0f
                    return@forEachIndexed
                }
                child.translationY = delta
                child
                    .animate()
                    .translationY(0f)
                    .setDuration(if (thanos) THANOS_MOVE_DURATION_MS else MOVE_DURATION_MS)
                    .setInterpolator(if (thanos) THANOS_MOVE_INTERPOLATOR else MOVE_INTERPOLATOR)
                    .setListener(null)
                    .start()
            }
        }
    }

    private fun obtainThanosEffect(root: ViewGroup): ThanosEffect {
        thanosEffects[root]?.let {
            return it
        }
        lateinit var effect: ThanosEffect
        effect =
            ThanosEffect(root.context) {
                if (thanosEffects[root] === effect) thanosEffects.remove(root)
                root.removeView(effect)
            }
        root.addView(
            effect,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        thanosEffects[root] = effect
        return effect
    }
}
