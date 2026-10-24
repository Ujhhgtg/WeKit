package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethodQuery
import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.matcher.FieldSpec
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameSpec
import dev.ujhhgtg.wekit.dexkit.matcher.TypeSpec
import dev.ujhhgtg.wekit.dexkit.matcher.UsingFieldSpec
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.dexkit.sql.sqlClass
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookCallback
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.hookDirectly
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.reflection.void
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet

object RemoveMessageSelectionLimit : SwitchFeature(), IResolveDex {

    override val technicalId = "解除消息多选数量限制"
    override val nameRes = R.string.feature_remove_message_selection_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_remove_message_selection_limit_description

    private const val SELECTION_LIMIT = 100

    private val methodGetSelectedMessageCount by dexMethodQuery {
        uniqueMethod(
            MethodSpec(
                declaredClass = ClassSpec(
                    className = NameSpec(WeMessageApi.classChattingDataAdapter.data.name),
                ),
                paramCount = 0..0,
                returnType = ClassSpec(className = NameSpec("int")),
                usingFields = listOf(
                    UsingFieldSpec(
                        field = FieldSpec(
                            type = ClassSpec(
                                className = NameSpec("java.util.concurrent.CopyOnWriteArraySet"),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private val classChatItemQuickSelect by dexClass(
        sqlClass {
            searchPackages("com.tencent.mm.ui.chatting.component")
            usingEqStrings("MicroMsg.ChatItemQuickSelectComponent", "initViews: chattingQuickSelectRootUp=")
        },
    )

    /*
     * Two structurally identical setter overloads exist in the quick-select component.
     * The matcher cannot be expressed as a single-row DexSQL query (it targets a class
     * resolved at runtime), so resolve all candidates once and assign them explicitly
     * instead of relaxing uniqueness. Ordering is the previous resolver's deterministic result order.
     */
    private val methodSetQuickSelectViewEnabled1 by dexMethod()
    private val methodSetQuickSelectViewEnabled2 by dexMethod()

    private val selectedMessagesField: Field by lazy {
        WeMessageApi.methodToggleMessageSelection.method.declaringClass.declaredFields.single {
            it.type == CopyOnWriteArraySet::class.java
        }.makeAccessible()
    }

    private data class TemporarilyRemovedSelections(
        val selectedMessages: CopyOnWriteArraySet<Any>,
        val removed: List<Any>
    )

    private val selectedMessageCountOverride = ThreadLocal<Int>()

    override fun resolveDex() {
        val candidates = DexResolutionContext.matcher.findMethods(
            MethodSpec(
                declaredClass = ClassSpec(className = NameSpec(classChatItemQuickSelect.data.name)),
                usingNumbers = listOf<Number>(SELECTION_LIMIT),
                paramTypes = listOf(TypeSpec("boolean")),
                returnType = ClassSpec(className = NameSpec("void")),
            ),
        )
        check(candidates.size >= 2) {
            "DexSQL: Expected two quick-select setter candidates, found ${candidates.size}"
        }
        methodSetQuickSelectViewEnabled1.setDescriptor(candidates[0].descriptor)
        methodSetQuickSelectViewEnabled2.setDescriptor(candidates[1].descriptor)
    }

    override fun onEnable() {
        listOf(
            methodSetQuickSelectViewEnabled1,
            methodSetQuickSelectViewEnabled2
        ).forEach {
            it.hookBefore {
                args[0] = true
            }
        }

        methodGetSelectedMessageCount.hookBefore {
            selectedMessageCountOverride.get()?.let {
                result = it
            }
        }

        val hook = object : HookCallback() {
            override fun beforeHookedMethod(param: HookParam) {
                val adapter = param.thisObject ?: return
                val message = param.args[0] ?: return
                @Suppress("UNCHECKED_CAST")
                val selectedMessages = selectedMessagesField.get(adapter) as CopyOnWriteArraySet<Any>
                if (message in selectedMessages || selectedMessages.size < SELECTION_LIMIT) return

                // Let WeChat run its original add and UI refresh path with 99 existing selections.
                val removed = selectedMessages.take(selectedMessages.size - SELECTION_LIMIT + 1)
                selectedMessages.removeAll(removed.toSet())
                param.extra = TemporarilyRemovedSelections(selectedMessages, removed)
                selectedMessageCountOverride.set(selectedMessages.size + removed.size + 1)
            }

            override fun afterHookedMethod(param: HookParam) {
                val state = param.extra as? TemporarilyRemovedSelections ?: return
                val remainingAndNew = state.selectedMessages.toList()
                state.selectedMessages.clear()
                state.selectedMessages.addAll(state.removed)
                state.selectedMessages.addAll(remainingAndNew)
                selectedMessageCountOverride.remove()
            }
        }

        registerUnhook(WeMessageApi.methodToggleMessageSelection.method.hookDirectly(hook))
    }
}