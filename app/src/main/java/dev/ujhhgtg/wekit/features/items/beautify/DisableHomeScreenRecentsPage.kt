package dev.ujhhgtg.wekit.features.items.beautify

import android.annotation.SuppressLint
import android.view.View
import android.widget.ListView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.DexClassDelegate
import dev.ujhhgtg.wekit.dex.dsl.DexFieldDelegate
import dev.ujhhgtg.wekit.dex.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexField
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object DisableHomeScreenRecentsPage : SwitchFeature() {
    override val technicalId = "隐藏主页下滑「最近」页"
    override val nameRes = R.string.feature_disable_home_screen_recents_page_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_disable_home_screen_recents_page_description

    private const val CONVERSATION_LIST_VIEW = "com.tencent.mm.ui.conversation.ConversationListView"
    private val classConversationListView by dexClass {
        className = CONVERSATION_LIST_VIEW
    }
    private val fieldListShowHeader by dexField {
        declaredClass = CONVERSATION_LIST_VIEW
        type = "boolean"
        readMethod {
            name = "getShowHeaderContainer"
            paramCount = 0
            returnType = "boolean"
        }
    }
    private val methodListHeaderUpdate by dexMethod {
        declaredClass = CONVERSATION_LIST_VIEW
        paramTypes("boolean")
        returnType = "void"
        usingString("updateHeaderContainer showHeaderContainer:")
    }
    private val methodListSelection by dexMethod {
        declaredClass = CONVERSATION_LIST_VIEW
        name = "setSelection"
        paramTypes("int")
        returnType = "void"
    }
    private val methodListOffsetUpdate by dexMethod {
        declaredClass = CONVERSATION_LIST_VIEW
        paramCount = 0
        returnType = "void"
        usingString("updateScrollOffset statusBarHeight:")
    }
    private val fieldListScrollOffset by dexField {
        declaredClass = CONVERSATION_LIST_VIEW
        type = "int"
        readMethod {
            name = "setSelection"
            paramTypes("int")
            returnType = "void"
        }
    }
    private val methodListActionBarLayout by dexMethod {
        declaredClass = CONVERSATION_LIST_VIEW
        paramTypes("int", "int")
        returnType = "void"
        usingString("initActionBarView statusBarHeight:")
    }
    private val methodListLayout by dexMethod {
        declaredClass = CONVERSATION_LIST_VIEW
        name = "onLayout"
        paramTypes("boolean", "int", "int", "int", "int")
        returnType = "void"
    }
    private val classConversationRecyclerView by
        dexClass(allowFailure = true) {
            usingEqStrings(
                "MicroMsg.ConversationRecyclerView",
                "[flushPendingHeaders] flushing %d headers",
            )
        }
    private val fieldRecyclerShowHeader by classConversationRecyclerView.dexField {
        declaredClass = classConversationRecyclerView.data.name
        type = "boolean"
        readMethod {
            name = "getShowHeaderContainer"
            paramCount = 0
            returnType = "boolean"
        }
    }
    private val methodRecyclerSelection by classConversationRecyclerView.dexMethod {
        declaredClass = classConversationRecyclerView.data.name
        name = "setSelectionWithOffset"
        paramTypes("int")
        returnType = "void"
    }
    private val methodRecyclerOffsetUpdate by classConversationRecyclerView.dexMethod {
        declaredClass = classConversationRecyclerView.data.name
        paramCount = 0
        returnType = "void"
        usingString("[updateScrollOffset] weUIStatusBar:")
    }
    private val fieldRecyclerScrollOffset by classConversationRecyclerView.dexField {
        declaredClass = classConversationRecyclerView.data.name
        type = "int"
        readMethod {
            name = "getScrollOffset"
            paramCount = 0
            returnType = "int"
        }
    }
    private val methodRecyclerLayout by classConversationRecyclerView.dexMethod {
        declaredClass = classConversationRecyclerView.data.name
        name = "onLayout"
        paramTypes("boolean", "int", "int", "int", "int")
        returnType = "void"
    }
    private val methodRecyclerScrollToPosition by classConversationRecyclerView.dexMethodFrom {
        methodRecyclerLayout.data.invokes
            .distinctBy { it.descriptor }
            .single {
                it.declaredClassName == "androidx.recyclerview.widget.LinearLayoutManager" &&
                    it.paramTypeNames == listOf("int", "int") &&
                    it.returnTypeName == "void"
            }
    }
    private val methodOpenHeader by dexMethod {
        paramTypes("boolean", "long", "int")
        returnType = "void"
        usingEqString("MicroMsg.TaskBarAnimController")
        usingString("openHeader allowOpenWhenHeaderHide:")
    }
    private val methodControllerTouch by dexMethod {
        declaredClass = methodOpenHeader.data.declaredClassName
        name = "onTouch"
        paramTypes("android.view.View", "android.view.MotionEvent")
        returnType = "boolean"
    }

    @SuppressLint("StaticFieldLeak") private var layingOut: View? = null

    override fun onEnable() {
        methodListHeaderUpdate.hookBefore {
            fieldListShowHeader.field.setBoolean(thisObject, false)
            result = null
        }
        methodListActionBarLayout.hookBefore {
            args[1] = 0
        }
        hookConversationView(
            classConversationListView,
            fieldListShowHeader,
            fieldListScrollOffset,
            methodListOffsetUpdate,
            methodListSelection,
            methodListLayout,
        )
        ListView::class.reflekt()
            .firstMethod {
                name = "setSelectionFromTop"
                parameters(Int::class, Int::class)
                superclass()
            }
            .hookBefore {
                if (thisObject === layingOut && args[0] == 1) args[0] = 0
            }
        if (classConversationRecyclerView.isFound) {
            hookConversationView(
                classConversationRecyclerView,
                fieldRecyclerShowHeader,
                fieldRecyclerScrollOffset,
                methodRecyclerOffsetUpdate,
                methodRecyclerSelection,
                methodRecyclerLayout,
            )
            methodRecyclerScrollToPosition.hookBefore {
                val view = layingOut ?: return@hookBefore
                if (view is ListView) return@hookBefore
                val layoutManager =
                    view
                        .reflekt()
                        .firstMethod {
                            name = "getLayoutManager"
                            parameters()
                            superclass()
                        }
                        .invoke()
                if (thisObject === layoutManager && args[0] == 1) args[0] = 0
            }
        }
        methodOpenHeader.hookBefore { result = null }
        methodControllerTouch.hookBefore { result = false }
    }

    private fun hookConversationView(
        viewClass: DexClassDelegate,
        showHeader: DexFieldDelegate,
        scrollOffset: DexFieldDelegate,
        updateOffset: DexMethodDelegate,
        selection: DexMethodDelegate,
        layout: DexMethodDelegate,
    ) {
        fun updatePadding(view: View) {
            val offset = scrollOffset.field.getInt(view)
            if (offset > 0) {
                view.setPadding(view.paddingLeft, offset, view.paddingRight, view.paddingBottom)
                scrollOffset.field.setInt(view, 0)
            }
        }

        viewClass.clazz.reflekt().constructors().forEach { constructor ->
            constructor.hookAfter {
                showHeader.field.setBoolean(thisObject, false)
                updatePadding(thisObject as View)
            }
        }
        updateOffset.hookAfter { updatePadding(thisObject as View) }
        selection.hookBefore {
            updatePadding(thisObject as View)
            if (args[0] == 1) args[0] = 0
        }
        layout.hook {
            before {
                extra = layingOut
                layingOut = thisObject as View
            }
            after { layingOut = extra as View? }
        }
        viewClass.clazz
            .reflekt()
            .firstMethod {
                name = "canScrollHorizontally"
                parameters(Int::class)
            }
            .hookBefore { result = false }
    }
}
