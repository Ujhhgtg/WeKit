package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet

object RemoveMessageSelectionLimit : SwitchFeature() {
    override val technicalId = "解除消息多选数量限制"
    override val nameRes = R.string.feature_remove_message_selection_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_remove_message_selection_limit_description

    private const val SELECTION_LIMIT = 100

    private val methodGetSelectedMessageCount by dexMethod {
        declaredClass {
            className = WeMessageApi.classChattingDataAdapter.data.name
        }
        paramCount = 0
        returnType = "int"
        usingField {
            type {
                className = "java.util.concurrent.CopyOnWriteArraySet"
            }
        }
    }

    private val classChatItemQuickSelect by dexClass {
        searchPackages("com.tencent.mm.ui.chatting.component")
        usingEqStrings(
            "MicroMsg.ChatItemQuickSelectComponent",
            "initViews: chattingQuickSelectRootUp=",
        )
    }

    /*
     * Two structurally identical setter overloads exist in the quick-select component.
     * The matcher cannot be expressed as a single-row DexSQL query (it targets a class
     * resolved at runtime), so resolve all candidates once and assign them explicitly
     * instead of relaxing uniqueness. Ordering is the previous resolver's deterministic result order.
     */
    private val methodSetQuickSelectViewEnabled1 by dexMethodFrom {
        val candidates = DexResolutionContext.findMethods {
            declaredClass = classChatItemQuickSelect.data.name
            usingNumbers(SELECTION_LIMIT)
            paramTypes("boolean")
            returnType = "void"
        }
        check(candidates.size >= 2) {
            "DexSQL: Expected two quick-select setter candidates, found ${candidates.size}"
        }
        candidates[0]
    }
    private val methodSetQuickSelectViewEnabled2 by dexMethodFrom {
        val candidates = DexResolutionContext.findMethods {
            declaredClass = classChatItemQuickSelect.data.name
            usingNumbers(SELECTION_LIMIT)
            paramTypes("boolean")
            returnType = "void"
        }
        check(candidates.size >= 2) {
            "DexSQL: Expected two quick-select setter candidates, found ${candidates.size}"
        }
        candidates[1]
    }

    private val selectedMessagesField: Field by lazy {
        WeMessageApi.methodToggleMessageSelection.method.declaringClass.declaredFields
            .single {
                it.type == CopyOnWriteArraySet::class.java
            }
            .makeAccessible()
    }

    private data class TemporarilyRemovedSelections(
        val selectedMessages: CopyOnWriteArraySet<Any>,
        val removed: List<Any>,
    )

    private val selectedMessageCountOverride = ThreadLocal<Int>()

    override fun onEnable() {
        listOf(
                methodSetQuickSelectViewEnabled1,
                methodSetQuickSelectViewEnabled2,
            )
            .forEach {
                it.hookBefore {
                    args[0] = true
                }
            }

        methodGetSelectedMessageCount.hookBefore {
            selectedMessageCountOverride.get()?.let {
                result = it
            }
        }

        WeMessageApi.methodToggleMessageSelection.hook {
            before {
                val adapter = thisObject ?: return@before
                val message = args[0] ?: return@before

                @Suppress("UNCHECKED_CAST")
                val selectedMessages =
                    selectedMessagesField.get(adapter) as CopyOnWriteArraySet<Any>
                if (message in selectedMessages || selectedMessages.size < SELECTION_LIMIT) {
                    return@before
                }

                // Let WeChat run its original add and UI refresh path with 99 existing
                // selections.
                val removed = selectedMessages.take(selectedMessages.size - SELECTION_LIMIT + 1)
                selectedMessages.removeAll(removed.toSet())
                extra = TemporarilyRemovedSelections(selectedMessages, removed)
                selectedMessageCountOverride.set(selectedMessages.size + removed.size + 1)
            }

            after {
                val state = extra as? TemporarilyRemovedSelections ?: return@after
                val remainingAndNew = state.selectedMessages.toList()
                state.selectedMessages.clear()
                state.selectedMessages.addAll(state.removed)
                state.selectedMessages.addAll(remainingAndNew)
                selectedMessageCountOverride.remove()
            }
        }
    }
}
