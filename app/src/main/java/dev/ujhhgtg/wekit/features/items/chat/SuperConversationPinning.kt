package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import java.util.concurrent.atomic.AtomicBoolean

object SuperConversationPinning : SwitchFeature(),
    WeConversationContextMenuApi.IMenuItemsProvider,
//    WeDatabaseListenerApi.IQueryListener,
    IResolveDex {

    override val technicalId = "超级对话置顶"
    override val nameRes = R.string.feature_super_conversation_pinning_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_super_conversation_pinning_description

    private const val TAG = "SuperConversationPinning"
    private const val PRIORITIES_KEY = "super_conversation_pinning_priorities"
    private const val MENU_ITEM_ID = 777021
    private const val PLACED_TOP_BIT = 4611686018427387904L

    private var storedPriorities by prefOption(PRIORITIES_KEY, "{}")

    @Volatile
    private var priorities: Map<String, Int> = emptyMap()

    private val mvvmOrderingApplied = AtomicBoolean()

//    private val conversationOrder = Regex(
//        """order\s+by\s+(?:rconversation\.)?flag\s+desc(?:\s*,\s*(?:rconversation\.)?conversationTime\s+desc)?""",
//        RegexOption.IGNORE_CASE
//    )
//    private val homeParentRefFilter = Regex(
//        """parentRef\s+is\s+null""",
//        RegexOption.IGNORE_CASE
//    )
//    private val methodSqliteWrapperRawQuery by dexMethod(allowFailure = true) {
//        matcher {
//            modifiers = JavaModifier.PUBLIC
//            usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
//            paramTypes("java.lang.String", "java.lang.String[]", "int")
//            returnType("android.database.Cursor")
//        }
//    }
//    private val methodSqliteWrapperQuery by dexMethod(allowFailure = true) {
//        matcher {
//            usingStrings("MicroMsg.SQLiteWrapper", "Query failed.")
//            paramTypes("java.lang.String", "java.lang.String[]")
//            returnType("android.database.Cursor")
//        }
//    }

    override fun onEnable() {
        priorities = loadPriorities()
        WeConversationContextMenuApi.addProvider(this)
        WeDatabaseListenerApi.addListener(this)
//        hookSqliteWrapperQueries()
        hookMvvmConversationComparator()
        WeConversationApi.reloadConversations()
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
        WeDatabaseListenerApi.removeListener(this)
        WeConversationApi.reloadConversations()
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> = listOf(
        WeConversationContextMenuApi.MenuItem(
            id = MENU_ITEM_ID,
            text = localizedChatString(R.string.chat_pinning_set_priority),
            drawable = EditIcon,
            shouldShow = { context, _ -> context.talker.isNotEmpty() },
            onClick = { context -> showPriorityDialog(context.activity, context.talker) }
        )
    )

//    override fun onQuery(sql: String): String? {
//        val currentPriorities = priorities
//        if (currentPriorities.isEmpty()) return null
//        if (!sql.contains("from rconversation", ignoreCase = true)) return null
//        if (!homeParentRefFilter.containsMatchIn(sql)) return null
//
//        val match = conversationOrder.find(sql) ?: return null
//        val priorityOrder = buildPriorityOrder(currentPriorities)
//        val rewritten = sql.replaceRange(match.range, priorityOrder)
//        WeLogger.i(TAG, "rewrote homepage ordering for ${currentPriorities.size} prioritized conversations: $rewritten")
//        return rewritten
//    }

    private fun showPriorityDialog(context: Context, talker: String) {
        showComposeDialog(context) {
            var priority by remember(talker) { mutableIntStateOf(priorityOf(talker)) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_pinning_set_priority)) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chat_pinning_priority_value, priority))
                        Slider(
                            value = priority.toFloat(),
                            onValueChange = { priority = it.toInt() },
                            valueRange = 0f..10f,
                            steps = 9
                        )
                        Text(stringResource(R.string.chat_pinning_priority_description))
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button({
                        setPriority(talker, priority)
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                }
            )
        }
    }

    private fun priorityOf(talker: String): Int = priorities[talker] ?: 0

    private fun setPriority(talker: String, priority: Int) {
        val updated = priorities.toMutableMap()
        if (priority == 0) {
            updated.remove(talker)
        } else {
            updated[talker] = priority.coerceIn(1, 10)
        }
        priorities = updated
        storedPriorities = DefaultJson.encodeToString(updated)
        mvvmOrderingApplied.set(false)
        WeLogger.i(TAG, "saved priority $priority for $talker")
        WeConversationApi.reloadConversations()
    }

//    private fun hookSqliteWrapperQueries() {
//        if (methodSqliteWrapperRawQuery.isPlaceholder) {
//            WeLogger.w(TAG, "legacy conversation SQLite wrapper query method was not resolved")
//        } else {
//            methodSqliteWrapperRawQuery.method.hookBefore {
//                val sql = args.firstOrNull() as? String ?: return@hookBefore
//                onQuery(sql)?.let { args[0] = it }
//            }
//            WeLogger.i(TAG, "hooked legacy conversation SQLite wrapper query")
//        }
//
//        if (methodSqliteWrapperQuery.isPlaceholder) {
//            WeLogger.w(TAG, "standard conversation SQLite wrapper query method was not resolved")
//        } else {
//            methodSqliteWrapperQuery.method.hookBefore {
//                val sql = args.firstOrNull() as? String ?: return@hookBefore
//                onQuery(sql)?.let { args[0] = it }
//            }
//            WeLogger.i(TAG, "hooked standard conversation SQLite wrapper query")
//        }
//    }

    private val classConversation by dexClass {
        matcher {
            usingEqStrings("MicroMsg.Conversation", "[%s}]get mmkv editmsg is [%s],get conv editmsg is [%s]")
        }
    }

    private val classMvvmConversationAdapter by dexClass {
        matcher {
            superClass {
                usingEqStrings("null cannot be cast to non-null type T of com.tencent.mm.plugin.mvvmlist.BaseMvvmListItem")
            }

            addField {
                type(classConversation.data.name)
            }
        }
    }

    private fun hookMvvmConversationComparator() {
        if (classMvvmConversationAdapter.isPlaceholder) {
            WeLogger.w(TAG, "mvvm conversation comparator unavailable")
            return
        }

        classMvvmConversationAdapter.reflekt().firstMethod("compareTo").hookBefore {
            val currentPriorities = priorities
            if (currentPriorities.isEmpty()) return@hookBefore

            val currentModel = thisObject ?: return@hookBefore
            val otherModel = args.firstOrNull() ?: return@hookBefore

            fun Any.getConversation(): Any {
                return reflekt().firstField {
                    type = classConversation.clazz
                }.get()!!
            }

            fun Any.getUsername(): String? {
                return reflekt().firstField {
                    name = "field_username"
                    superclass()
                }.get() as? String?
            }

            fun Any.getFlag(): Long {
                return (reflekt().firstField {
                    name = "field_flag"
                    superclass()
                }.get() as Number).toLong()
            }

            val currentConversation = currentModel.getConversation()
            val otherConversation = otherModel.getConversation()
            val currentUsername = currentConversation.getUsername()
            val otherUsername = otherConversation.getUsername()
            val currentFlag = currentConversation.getFlag()
            val otherFlag = otherConversation.getFlag()
            val currentPinned = currentFlag and PLACED_TOP_BIT != 0L
            val otherPinned = otherFlag and PLACED_TOP_BIT != 0L

            if (currentPinned != otherPinned) {
                result = if (currentPinned) -1 else 1
                return@hookBefore
            }

            val currentPriority = currentPriorities[currentUsername] ?: 0
            val otherPriority = currentPriorities[otherUsername] ?: 0
            if (currentPriority == otherPriority) return@hookBefore

            result = otherPriority.compareTo(currentPriority)

            if (mvvmOrderingApplied.compareAndSet(false, true)) {
                WeLogger.i(
                    TAG,
                    "applied mvvm homepage ordering: $currentUsername=$currentPriority, " +
                            "$otherUsername=$otherPriority, pinned=$currentPinned"
                )
            }
        }
    }

    private fun loadPriorities(): Map<String, Int> = runCatching {
        DefaultJson.decodeFromString<Map<String, Int>>(storedPriorities)
            .filter { (talker, priority) -> talker.isNotBlank() && priority in 1..10 }
    }.getOrDefault(emptyMap())

//    private fun buildPriorityOrder(priorities: Map<String, Int>): String {
//        val cases = priorities.entries
//            .sortedByDescending { it.value }
//            .joinToString(separator = " ") { (talker, priority) ->
//                "WHEN '${talker.replace("'", "''")}' THEN $priority"
//            }
//        return """
//            ORDER BY (rconversation.flag & $PLACED_TOP_BIT) DESC,
//            CASE rconversation.username $cases ELSE 0 END DESC,
//            rconversation.flag DESC, rconversation.conversationTime DESC
//        """.trimIndent().replace('\n', ' ')
//    }
}
