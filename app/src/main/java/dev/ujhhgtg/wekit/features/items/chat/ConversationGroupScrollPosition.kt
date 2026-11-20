package dev.ujhhgtg.wekit.features.items.chat

import android.os.Parcel
import android.os.Parcelable
import android.view.View
import android.widget.ListView
import dev.ujhhgtg.reflekt.reflekt

/** Only the vertical viewport; never saves row views, checked items or conversation data. */
sealed interface ConversationGroupScrollPosition {
    fun restore(view: View)

    data class ListPosition(val position: Int, val offset: Int) : ConversationGroupScrollPosition {
        override fun restore(view: View) {
            val list = view as ListView
            list.setSelectionFromTop(position.coerceAtMost((list.count - 1).coerceAtLeast(0)), offset)
        }
    }

    class RecyclerPosition(private val state: Parcelable) : ConversationGroupScrollPosition {
        override fun restore(view: View) {
            // The host layout manager retains and can mutate the supplied SavedState. Keep our
            // copy reusable when a preview is cancelled or this group is visited again.
            val parcel = Parcel.obtain()
            val copy = try {
                parcel.writeParcelable(state, 0)
                parcel.setDataPosition(0)
                @Suppress("DEPRECATION")
                parcel.readParcelable<Parcelable>(state.javaClass.classLoader)!!
            } finally {
                parcel.recycle()
            }
            layoutManager(view).reflekt().firstMethod {
                name = "onRestoreInstanceState"
                parameters(Parcelable::class)
                superclass()
            }.invoke(copy)
        }
    }

    companion object {
        fun stopScrolling(view: View) {
            if (view is ListView) view.smoothScrollBy(0, 0)
            else view.reflekt().firstMethod {
                name = "setScrollState"
                parameters(Int::class)
                superclass()
            }.invoke(0)
        }

        fun capture(view: View): ConversationGroupScrollPosition? {
            if (view is ListView) {
                val first = view.getChildAt(0) ?: return null
                return ListPosition(view.firstVisiblePosition, first.top - view.paddingTop)
            }
            val state = layoutManager(view).reflekt().firstMethod {
                name = "onSaveInstanceState"
                parameters()
                superclass()
            }.invoke() as Parcelable
            return RecyclerPosition(state)
        }

        fun reset(view: View) {
            // These host entry points hide the recent-mini-program header and use its normal
            // action-bar offset. Scrolling raw adapter position zero would expose that header.
            if (view is ListView) view.setSelection(0)
            else view.reflekt().firstMethod {
                name = "setSelectionWithOffset"
                parameters(Int::class)
            }.invoke(0)
        }

        private fun layoutManager(view: View): Any = view.reflekt().firstMethod {
            name = "getLayoutManager"
            parameters()
            superclass()
        }.invoke()!!
    }
}
