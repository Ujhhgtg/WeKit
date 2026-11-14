package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import com.tencent.mm.ui.chatting.view.MMChattingListView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.BaseFeature
import java.lang.reflect.Method

/** 悬浮布局下，向新消息方向分页仍需按原来的消息可视区恢复位置。 */
object FloatingChatPagination {
    fun install(feature: BaseFeature, extraOffset: (View) -> Int) =
        with(feature) {
            val listClass = MMChattingListView::class.reflekt()
            val getListView = listClass.firstMethod {
                name = "getListView"
                parameters()
            }
            val hookedUpdates = HashSet<Method>()
            // Presenter 更新及其 super 调用都同步发生在 UI 线程；栈仅用于匹配嵌套调用。
            val updatingLists = ArrayDeque<MMChattingListView>()

            listClass
                .firstMethod {
                    name = "setLoadExecutor"
                    parameterCount = 1
                }
                .hookAfter {
                    // 从实际的 presenter 找 onViewUpdate(MMChattingListView, LoadResult)，
                    // 覆盖普通、搜索及公众号会话，不依赖混淆后的 presenter/方法名。
                    val update =
                        args[0]!!.reflekt().firstMethod {
                            parameters { it.size == 2 && it[0] == MMChattingListView::class.java }
                            returnType(Void.TYPE)
                            superclass()
                        }
                    if (hookedUpdates.add(update.self)) {
                        update.hookBefore {
                            updatingLists.addLast(args[0] as MMChattingListView)
                        }
                        update.hookAfter {
                            updatingLists.removeLast()
                        }
                    }
                }

            // ACTION_BOTTOM 用 height - tipsHeight - bottomHeight 作为下一页首条消息
            // 的 offset，随后 setSelectionFromTop 又会加上 paddingTop。
            // Header 扣除该顶部 padding，Footer 扣除悬浮后增加的底部留白。
            // 只在 presenter 更新内修正：自动加载边界检测也读 getBottomHeight，不能改它。
            listClass
                .firstMethod {
                    name = "getBottomHeight"
                    parameters()
                    returnType(Int::class)
                    superclass()
                }
                .hookAfter {
                    if (thisObject !== updatingLists.lastOrNull()) return@hookAfter
                    val list = thisObject as MMChattingListView
                    val recycler = getListView.invoke(list) as View
                    result = (result as Int) + extraOffset(recycler)
                }
        }
}
