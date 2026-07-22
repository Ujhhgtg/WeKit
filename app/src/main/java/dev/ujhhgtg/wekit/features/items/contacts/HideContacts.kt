package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.collection.mutableIntSetOf
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tencent.mm.plugin.voip.widget.VoipForegroundService
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarApi
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.now
import dev.ujhhgtg.wekit.utils.reflection.BString
import java.lang.ref.WeakReference
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import java.lang.reflect.Modifier as JavaModifier


@Feature(
    name = "隐藏联系人", categories = ["联系人与群组"], description =
        """隐藏指定的联系人
隐藏位置:
1. 首页对话列表
2. 通讯录内联系人&群聊列表
3. 首页搜索界面
4. 锁屏自动关闭聊天界面
5. 摇一摇设备关闭聊天界面
6. 朋友圈信息流
7. 联系人选择页面"""
)
object HideContacts : ClickableFeature(), IResolveDex, WeChatInputBarApi.IInputBarListener,
    WeDatabaseListenerApi.IQueryListener {

    private const val TAG = "HideContacts"

    private const val KEY_CONTACTS = "hidden_contacts"

    // One-time flag: older versions hid chats by writing parentRef='hidden_conv_parent'. Once we've
    // cleared that stale marker for the current hidden set (so #show / un-hide work again), we never
    // need to re-check. New hides rely purely on the query-time filter and never set the marker.
    private const val KEY_LEGACY_MIGRATED = "hidden_parentref_migrated"

    var hiddenContacts
        get() = WePrefs.getStringSetOrDef(KEY_CONTACTS, emptySet())
        set(value) {
            for (convId in value) {
                WeConversationApi.setDnd(convId, true)
            }
            WePrefs.putStringSet(KEY_CONTACTS, value)
            WeConversationApi.reloadConversations()
        }

    private object ScreenOffReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return

            val chattingUi = chattingUi?.get() ?: return
            val wxId = chattingUi.intent.getStringExtra("Chat_User")
            if (temporarilyShown || wxId !in hiddenContacts) return

            exitToMainActivity()
        }
    }

    private var chattingUi: WeakReference<ChattingUI>? = null

    private object ShakeDetector : SensorEventListener {

        private var sensorManager: SensorManager? = null
        private var lastShakeTime: Long = 0
        private const val SHAKE_THRESHOLD = 4.5f // higher = harder shake required

        fun start(context: Context) {
            WeLogger.d(TAG, "starting shake detector")

            if (sensorManager != null) return

            sensorManager = context.getSystemService<SensorManager>()
            val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        fun stop() {
            WeLogger.d(TAG, "stopping shake detector")

            sensorManager?.unregisterListener(this)
            sensorManager = null
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH

            if (gForce > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (lastShakeTime + 1000 > now) return // 1-second debounce
                lastShakeTime = now

                exitToMainActivity()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // unused
        }
    }

    private fun exitToMainActivity() {
        WeLogger.d(TAG, "leaving conversation page")
        val ctx = HostInfo.application
        val intent = Intent(ctx, LauncherUI::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ctx.startActivity(intent)
    }

    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    override fun onEnable() {
        // --- home screen conversation list ---

        // Hide conversations at query time: inject `username NOT IN (...)` into WeChat's
        // conversation-list query so hidden contacts are filtered on every full read.
        hookConversationListQuery()

        // Block the per-row live-update notification that WeChat fires (type 3) when a new
        // message arrives. Without this the native ConversationStorage dispatcher pushes the
        // hidden contact's row directly to the list adapter — bypassing the SQL hook above —
        // and the contact reappears until the next full query. Cancelling the notification at
        // source means the adapter never sees the row, so there is no flash at all.
        hookNewMessageNotification()

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            migrateLegacyHiddenParentRef()

            val context = thisObject!!.reflekt()
                .firstField { type { it isSubclassOf Activity::class } }
                .get()!! as Activity
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(ScreenOffReceiver, filter)
            WeLogger.d(TAG, "registered screen off receiver")

            // Triple-click on the main-screen title to toggle temporary show/hide.
            val titleView = context.window?.decorView
                ?.findViewById<TextView>(android.R.id.text1) ?: return@hookAfter
            var clickCount = 0
            var lastClickTime = Instant.DISTANT_PAST
            titleView.setOnClickListener {
                if (!tripleClickTitle) return@setOnClickListener
                val now = now()
                if (now - lastClickTime > TRIPLE_TAP_WINDOW) clickCount = 1 else clickCount++
                lastClickTime = now
                if (clickCount >= 3) {
                    clickCount = 0
                    toggleTemporarilyShown(context)
                }
            }
        }

        // --- shake to leave ---

        ChattingUI::class.reflekt().apply {
            firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as ChattingUI

                chattingUi = WeakReference(activity)

                val wxId = activity.intent.getStringExtra("Chat_User")
                if (temporarilyShown || wxId !in hiddenContacts) return@hookAfter

                ShakeDetector.start(activity)
            }

            firstMethod { name = "onPause" }.hookAfter {
                chattingUi?.clear()
                chattingUi = null
                ShakeDetector.stop()
            }
        }

        // --- friends & groups list ---

        methodAddressMvvmListPreprocessList.hookBefore {
            if (temporarilyShown) return@hookBefore

            val contacts = args[0] as MutableList<*>

            val contactInfoField = contacts[0]!!.reflekt()
                .firstField { type { it.name.startsWith("${PackageNames.WECHAT}.storage") } }
                .self
            val usernameField = contactInfoField.type.reflekt()
                .firstField {
                    name = "field_username"
                    superclass()
                }.self.makeAccessible()

            val hiddenContacts = hiddenContacts

            contacts.removeAll { contact ->
                val contactInfo = contactInfoField.get(contact!!)
                val username = usernameField.get(contactInfo) as String
                username in hiddenContacts
            }
        }

        methodChatroomContactAdapterInitCursor.hookAfter {
            if (temporarilyShown) return@hookAfter

            val cursor = thisObject!!.reflekt()
                .firstMethod {
                    parameterCount = 0
                    returnType = Cursor::class
                    superclass()
                }.invoke()!! as Cursor

            hiddenPositions.clear()

            val hiddenContacts = hiddenContacts

            if (cursor.moveToFirst()) {
                var index = 0
                val usernameCol: Int = cursor.getColumnIndex("username")
                do {
                    val wxId: String? = cursor.getString(usernameCol)
                    WeLogger.d(TAG, wxId ?: "null")
                    if (wxId in hiddenContacts) {
                        hiddenPositions.add(index)
                    }
                    index++
                } while (cursor.moveToNext())
            }
        }

        methodChatroomContactAdapterInitCursor.method.declaringClass.reflekt().apply {
            firstMethod { name = "getCount" }.hookAfter {
                result = result as Int - hiddenPositions.size
            }

            firstMethod { name = "getView" }.hookBefore {
                val requestedPos = args[0] as Int
                var actualPos = requestedPos
                hiddenPositions.forEach {
                    if (actualPos >= it) {
                        actualPos++
                    }
                }
                args[0] = actualPos
            }
        }

        // --- fts ---

        SQLiteDatabase::class.reflekt().firstMethod {
            name = "rawQueryWithFactory"
            parameters(SQLiteDatabase.CursorFactory::class, BString, Array<Any>::class, BString)
        }.hookBefore {
            if (temporarilyShown) return@hookBefore

            val sql = args[1] as String
            if (FTS_SQL_REGEX.containsMatchIn(sql) || sql.startsWith(SQL_SELECT_MESSAGE) || sql.startsWith(SQL_SELECT_MESSAGES_BY_KEYWORD)) {
                val hideValueText = hiddenContacts.joinToString(",") { "\"$it\"" }

                val newSql = if (sql.endsWith(";")) {
                    sql.dropLast(1)
                } else {
                    sql
                }.let { "SELECT * FROM ($it) AS a WHERE aux_index NOT IN ($hideValueText);" }

                args[1] = newSql
            }
        }

        // --- voip ---

        methodVoipLaunchIncomingCardAsync.hookBefore {
            val wxId = String(args[5] as ByteArray)
            if (!temporarilyShown && wxId in hiddenContacts) {
                pendingVoipUser = wxId
                result = null
            }
        }

        listOf(
            methodVoipAcceptIncomingCall, methodVoipStartAcceptVoip
        ).forEach {
            it.hookBefore {
                val callerWxId = args[0]!!.reflekt().firstField { type = BString }.get()!! as String
                if (!temporarilyShown && callerWxId in hiddenContacts) {
                    pendingVoipUser = callerWxId
                    result = null
                }
            }
        }

        methodVoipShowFloatingCard.hookBefore {
            val wxId = args[5] as? String? ?: return@hookBefore
            if (!temporarilyShown && wxId in hiddenContacts) {
                pendingVoipUser = wxId
                result = null
            }
        }

        // VoipServiceEx.reject() requires status==3 and roomId!=0, both of which are written
        // by setInviteContent. When NOT auto-rejecting we cancel setInviteContent in hookBefore
        // so no state is established and no network packet fires. When auto-rejecting we let
        // setInviteContent run (hookBefore does not cancel it), then call reject() in hookAfter
        // once status==3 and roomId are in place so the rejection actually reaches the caller.
        methodVoipServiceExSetInviteContent.hookBefore {
            if (temporarilyShown) return@hookBefore
            val wxId = args[0]!!.reflekt().firstField { type = BString }.get()!! as String
            if (wxId !in hiddenContacts) return@hookBefore
            pendingVoipUser = wxId
            if (!autoRejectVoip) {
                result = null  // hide only — cancel before state is set up
            }
            // autoRejectVoip=true: let the method run so status → 3 and roomId are written
        }

        methodVoipServiceExSetInviteContent.hookAfter {
            if (!autoRejectVoip) return@hookAfter
            if (temporarilyShown) return@hookAfter
            val wxId = runCatching {
                args[0]!!.reflekt().firstField { type = BString }.get()!! as String
            }.getOrNull() ?: return@hookAfter
            if (wxId !in hiddenContacts) return@hookAfter
            WeLogger.i(TAG, "auto-rejecting call from $wxId")
            runCatching {
                methodVoipServiceExReject.method.invoke(thisObject)
            }.onFailure {
                WeLogger.w(TAG, "failed to auto-reject call from $wxId", it)
            }
        }

        methodVoipBubbleHelperInsertMsg.hookBefore {
            val wxId = args[0] as String
            if (!temporarilyShown && wxId in hiddenContacts) {
                result = null
            }
        }

        VoipForegroundService::class.reflekt().firstMethod { name = "onStartCommand" }.hookBefore {
            val self = thisObject as VoipForegroundService
            val intent = args[0] as? Intent? ?: return@hookBefore
            val wxId = intent.getStringExtra("Voip_User") ?: return@hookBefore
            if (!temporarilyShown && wxId in hiddenContacts) {
                pendingVoipUser = wxId
                self.stopSelf()
                result = Service.START_NOT_STICKY
            }
        }

        methodVoipPlaySound.hookBefore {
            if (pendingVoipUser != null) {
                pendingVoipUser = null
                result = null
            }
        }

        // --- command ---

        WeChatInputBarApi.addListener(this)

        // --- moments feed ---

        WeDatabaseListenerApi.addListener(this)

        // --- notification ---

        methodDealNotify.hookBefore(100) {
            val talker = args[1] as? String? ?: return@hookBefore
            if (talker in hiddenContacts) {
                result = null
            }
        }

        WeConversationApi.reloadConversations()
    }

    override fun onDisable() {
        runCatching { HostInfo.application.unregisterReceiver(ScreenOffReceiver) }
        ShakeDetector.stop()
        chattingUi?.clear()
        chattingUi = null
        WeChatInputBarApi.removeListener(this)
        WeDatabaseListenerApi.removeListener(this)
        temporarilyShown = false
        WeConversationApi.reloadConversations()
    }

    /**
     * Toggles the temporary-show state. Mirrors the `#show` / `#hide` input-bar commands for
     * use by gesture-based triggers (e.g. triple-clicking the main-screen title).
     */
    internal fun toggleTemporarilyShown(context: Context) {
        if (temporarilyShown) {
            temporarilyShown = false
            showToast(context, "已恢复隐藏联系人")
        } else {
            temporarilyShown = true
            showToast(context, "已临时显示所有隐藏的联系人")
        }
        WeConversationApi.reloadConversations()
    }

    override fun onTextChanged(chatFooter: ChatFooter, text: String) {
        when (text) {
            "#show" -> {
                chatFooter.lastText = ""
                if (temporarilyShown) {
                    showToast(chatFooter.context, "已经是临时显示状态")
                    return
                }
                temporarilyShown = true
                showToast(chatFooter.context, "已临时显示所有隐藏的联系人, 输入 #hide 恢复隐藏")
                WeConversationApi.reloadConversations()
            }

            "#hide" -> {
                chatFooter.lastText = ""
                if (!temporarilyShown) {
                    showToast(chatFooter.context, "没有需要恢复的隐藏联系人")
                    return
                }
                temporarilyShown = false
                showToast(chatFooter.context, "已恢复隐藏联系人")
                WeConversationApi.reloadConversations()
            }
        }
    }

    // 在朋友圈信息流中隐藏被隐藏联系人发布的朋友圈; EnhanceQuery 会把信息流标记替换为 (1=1)
    private const val FEED_MARKER_RAW = "(sourceType & 2 != 0 )"
    private const val FEED_MARKER_ENHANCED = "(1=1)"

    override fun onQuery(sql: String): String? {
        if (temporarilyShown) return null

        val hidden = hiddenContacts
        if (hidden.isEmpty()) return null

        // 只处理主信息流查询: 排除个人主页 (userName=) 与已注入的查询
        if (!sql.contains("from SnsInfo", false)) return null
        if (sql.contains("SnsInfo.userName=", false)) return null
        if (sql.contains("SnsInfo.userName not in", true)) return null

        val filter = " AND SnsInfo.userName NOT IN (" +
                hidden.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } + ") "

        val rewritten = when {
            sql.contains(FEED_MARKER_RAW) ->
                sql.replaceFirst(FEED_MARKER_RAW, FEED_MARKER_RAW + filter)

            // EnhanceQuery 先执行时, 信息流标记已变为 (1=1); 个人主页不会出现该精确形式
            sql.contains(FEED_MARKER_ENHANCED) ->
                sql.replaceFirst(FEED_MARKER_ENHANCED, FEED_MARKER_ENHANCED + filter)

            else -> return null
        }

        WeLogger.i(TAG, "hid ${hidden.size} contacts from moments feed")
        return rewritten
    }

    // The homepage conversation-list cursor and the native contact-selector list (SelectContactUI /
    // AlphabetContactAdapter) both flow through WeChat's SQLite wrapper d95.b0.f(sql, args, int)
    // rather than the standard SQLiteDatabase.rawQuery path that WeDatabaseListenerApi hooks.
    // We hook that wrapper once and chain two rewriters:
    //   • rewriteConversationListSql — appends `rconversation.username NOT IN (...)` for the
    //     homepage list (same chokepoint as ConversationGrouping/AggregateChats)
    //   • rewriteContactSelectorSql — appends `username NOT IN (...)` for rcontact list queries
    //     from the contact selector (forward/group-create/etc.) so hidden contacts don't appear there
    private fun hookConversationListQuery() {
        if (methodSqliteWrapperRawQuery.isPlaceholder) {
            WeLogger.w(TAG, "SQLite wrapper query method not resolved; conversation-list and contact-selector hiding disabled")
            return
        }
        methodSqliteWrapperRawQuery.hookBefore {
            val sql = args.firstOrNull() as? String ?: return@hookBefore
            (rewriteConversationListSql(sql) ?: rewriteContactSelectorSql(sql))
                ?.let { args[0] = it }
        }
    }

    // Returns the rewritten SQL, or null to leave it untouched.
    private fun rewriteConversationListSql(sql: String): String? {
        if (temporarilyShown) return null

        val hidden = hiddenContacts
        if (hidden.isEmpty()) return null

        if (!looksLikeConversationListQuery(sql)) return null

        val condition = "rconversation.username NOT IN (" +
                hidden.joinToString(",") { "'${it.replace("'", "''")}'" } + ")"
        return injectCondition(sql, condition)
    }

    private fun looksLikeConversationListQuery(sql: String): Boolean {
        val lower = sql.lowercase()
        if (!lower.contains("select")) return false
        if (!lower.contains("from rconversation")) return false
        // Match only the homepage list query, which spells out per-conversation display columns.
        // Folder-container / single-row lookups use `select *` (no such columns) and aggregate/count
        // reads lack them too, so they're skipped and left untouched. NB: we deliberately do NOT bail
        // on the substring "wekit_folder_" — when AggregateChats is enabled it appends its own
        // `NOT LIKE 'wekit_folder_%'` clause to this very query, and bailing on it would skip hiding.
        return lower.contains("conversationtime") &&
                lower.contains("unreadcount") &&
                lower.contains("digestuser")
    }

    // Rewrites contact-selector rcontact queries to exclude hidden contacts.
    //
    // The native contact selector (SelectContactUI / AlphabetContactAdapter) builds its list
    // via ContactStorage.k4.B(sql, null) → d95.b0.f(sql, args, 0), the same WeChat SQLite
    // wrapper already hooked by methodSqliteWrapperRawQuery. We only need to recognize the
    // query shape and inject a `username NOT IN (...)` predicate.
    private fun rewriteContactSelectorSql(sql: String): String? {
        if (temporarilyShown) return null

        val hidden = hiddenContacts
        if (hidden.isEmpty()) return null

        if (!looksLikeContactSelectorQuery(sql)) return null

        // Qualify the column as rcontact.username to avoid "ambiguous column name" errors
        // when the query is a JOIN (e.g. "from rcontact, bizinfo" in BrandServiceIndexUI's
        // subscriber list — both tables have a username column). rewriteConversationListSql
        // already qualifies its condition; mirror that here.
        val condition = "rcontact.username NOT IN (" +
                hidden.joinToString(",") { "'${it.replace("'", "''")}'" } + ")"
        return injectCondition(sql, condition)
    }

    // Recognises full contact-list queries (contact selector, search results) by the presence of
    // pyinitial / quanpin — columns only selected in list-building queries that need alphabetical
    // sorting, never in single-row lookups or profile fetches. The table must be rcontact.
    private fun looksLikeContactSelectorQuery(sql: String): Boolean {
        val lower = sql.lowercase()
        if (!lower.contains("select")) return false
        if (!lower.contains("from rcontact")) return false
        return lower.contains("pyinitial") || lower.contains("quanpin")
    }

    // Insert an extra WHERE predicate before any ORDER BY / GROUP BY / LIMIT tail, joining with the
    // existing WHERE when present. Mirrors ConversationGrouping.injectCondition.
    private fun injectCondition(sql: String, condition: String): String {
        val insertionPoint = listOf(" order by ", " group by ", " limit ")
            .map { sql.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: sql.length
        val head = sql.substring(0, insertionPoint)
        val tail = sql.substring(insertionPoint)
        val connector = if (head.contains(" where ", ignoreCase = true)) " AND " else " WHERE "
        return "$head$connector$condition$tail"
    }

    // The parentRef marker older versions wrote via WeConversationApi.setConversationsVisibility to
    // hide a chat. WeChat's native list filter (m4.O) hides rows whose parentRef isn't null/empty.
    private const val LEGACY_HIDDEN_PARENT_REF = "hidden_conv_parent"

    // One-time cleanup for users upgrading from the parentRef-based hiding: clear the stale marker
    // for our currently-hidden chats. Without this, WeChat's own filter keeps hiding a chat (until
    // its next message resets parentRef) even after the user un-hides it, since un-hiding only drops
    // it from our set and never touched parentRef. Scoped to our hidden set so we don't disturb rows
    // hidden by 显隐全部对话 (ToggleAllConversationsVisibility), which shares the same marker.
    private fun migrateLegacyHiddenParentRef() {
        if (WePrefs.getBoolOrFalse(KEY_LEGACY_MIGRATED)) return

        val hidden = hiddenContacts
        if (hidden.isEmpty()) {
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            return
        }

        // DB not ready yet: leave the flag unset so we retry on the next launch.
        if (!WeDatabaseApi.isReady) return

        try {
            val inClause = hidden.joinToString(",") { "'${it.replace("'", "''")}'" }
            WeDatabaseApi.execStatement(
                "UPDATE rconversation SET parentRef = '' " +
                        "WHERE parentRef = '$LEGACY_HIDDEN_PARENT_REF' " +
                        "AND username IN ($inClause)"
            )
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            WeLogger.d(TAG, "cleared legacy hidden parentRef markers for ${hidden.size} chats")
        } catch (ex: Exception) {
            WeLogger.w(TAG, "failed to clear legacy hidden parentRef markers", ex)
        }
    }

    private var temporarilyShown = false

    private var pendingVoipUser: String? = null

    private const val SQL_SELECT_MESSAGE =
        "SELECT type, subtype, entity_id, aux_index, MAX(timestamp) as maxTime, count(aux_index) as msgCount, talker FROM FTS5MetaMessage"

    private const val SQL_SELECT_MESSAGES_BY_KEYWORD =
        "SELECT FTS5MetaMessage.docid, type, subtype, entity_id, aux_index, timestamp, talker FROM FTS5MetaMessage"

    private val FTS_SQL_REGEX =
        Regex("^SELECT (FTS5MetaContact|FTS5MetaTopHits|FTS5MetaKefuContact|FTS5MetaFeature|FTS5MetaWeApp|FTS5MetaFinderFollow|FTS5MetaFavorite)\\.docid, type, subtype, entity_id, aux_index,.*")

    private val hiddenPositions = mutableIntSetOf()

    private var autoRejectVoip by prefOption("hide_auto_reject", false)
    private var tripleClickTitle by prefOption("hide_triple_click_title", false)

    // Three taps within this window on the main-screen title register as a triple-click.
    // Matches WeChat's own double-tap detection threshold (f8/r8 tab listener, 300 ms),
    // with a slightly wider window so the gesture stays comfortable.
    private val TRIPLE_TAP_WINDOW = 500L.milliseconds

    // Hooks the ConversationStorage notify dispatcher to cancel per-row update events (type 3)
    // for hidden contacts before they reach list adapters. WeChat fires b(3, storage, talker)
    // synchronously after every new message, pin, or unread-state change; without this hook the
    // adapter sees the row immediately — before any SQL query runs — so the contact reappears
    // regardless of the query-rewrite filter. Cancelling the notification at source is
    // race-free: the hidden contact never reaches the adapter at all.
    //
    // Event type 5 (global reload) is not suppressed — that is the path reloadConversations() uses to
    // trigger a full re-query (which our SQL hook then filters correctly). The empty-talker check
    // additionally guards the "" sentinel used by reloadConversations().
    private fun hookNewMessageNotification() {
        val method = WeConversationApi.methodNotifyConversationChanged
        if (method.isPlaceholder) {
            WeLogger.w(TAG, "conversation notify method not resolved; new-message suppression unavailable")
            return
        }

        method.hookBefore {
            val eventType = args[0] as? Int ?: return@hookBefore
            if (eventType != 3) return@hookBefore
            if (temporarilyShown) return@hookBefore
            val talker = args[2] as? String ?: return@hookBefore
            if (talker.isEmpty()) return@hookBefore
            if (talker !in hiddenContacts) return@hookBefore
            WeConversationApi.markAsRead(talker)
            result = null
        }
    }

    override fun onClick(context: ComponentActivity) {
        val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("隐藏联系人") },
                text = {
                    DefaultColumn {
                        var autoRejectVoipInput by remember { mutableStateOf(autoRejectVoip) }
                        var tripleClickTitleInput by remember { mutableStateOf(tripleClickTitle) }

                        ListItem(
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = "选择要隐藏的联系人",
                                        contacts = regularContacts,
                                        initialSelectedWxIds = hiddenContacts,
                                        onDismiss = onDismiss
                                    ) {
                                        showToast("已保存 ${it.size} 个联系人")
                                        hiddenContacts = it
                                        onDismiss()
                                    }
                                }
                            },
                            supportingContent = { Text("点击配置联系人隐藏列表") },
                            headlineContent = { Text("配置隐藏列表") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                autoRejectVoipInput = !autoRejectVoipInput
                                autoRejectVoip = autoRejectVoipInput
                            },
                            trailingContent = {
                                Switch(checked = autoRejectVoipInput, onCheckedChange = null)
                            },
                            supportingContent = { Text("不保证有效") },
                            headlineContent = { Text("自动拒绝音视频通话") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                tripleClickTitleInput = !tripleClickTitleInput
                                tripleClickTitle = tripleClickTitleInput
                            },
                            trailingContent = {
                                Switch(checked = tripleClickTitleInput, onCheckedChange = null)
                            },
                            supportingContent = { Text("连续三击主页顶部标题栏, 可临时显示或恢复隐藏联系人") },
                            headlineContent = { Text("三击标题切换显隐") },
                        )
                    }
                })
        }
    }

    //    private val methodMainAdapterPerformSearch by dexMethod()

    // WeChat's SQLite wrapper query: d95.b0.f(String sql, String[] args, int) -> Cursor. The
    // homepage conversation-list cursor (com.tencent.mm.storage.m4.A/B) is built through this
    // wrapper, NOT the standard SQLiteDatabase.rawQuery path WeDatabaseListenerApi hooks, so we
    // intercept it directly — the same chokepoint ConversationGrouping/AggregateChats use.
    private val methodSqliteWrapperRawQuery by dexMethod(allowFailure = true) {
        matcher {
            modifiers = JavaModifier.PUBLIC
            usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
            paramTypes("java.lang.String", "java.lang.String[]", "int")
            returnType("android.database.Cursor")
        }
    }
    private val methodAddressMvvmListPreprocessList by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.address.AddressLiveList"
            usingEqStrings("snapshotList")
        }
    }
    private val methodChatroomContactAdapterInitCursor by dexMethod {
        searchPackages("com.tencent.mm.ui.contact")
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ChatroomContactAdapter", "get display show head return null, user[%s] pos[%d]")
            }

            invokeMethods {
                add {
                    declaredClass = "android.widget.BaseAdapter"
                    name = "notifyDataSetChanged"
                }
            }
        }
    }

    //    private val methodVoipLaunchNotify by dexMethod {
//        matcher {
//            usingEqStrings("MicroMsg.VoIPMP.CoreV2", "launchNotify")
//        }
//    }
    private val methodVoipLaunchIncomingCardAsync by dexMethod {
        matcher {
            // 8.0.76 changed from "launchInComingCardAsync: " to "[volume report] launchInComingCardAsync: "
            usingStrings("MicroMsg.VoIPMP.CoreV2", "launchInComingCardAsync: ")
        }
    }
    private val methodVoipAcceptIncomingCall by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "acceptIncomingCal, roomInfo:")
        }
    }
    private val methodVoipStartAcceptVoip by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "startAcceptVoIP, roomInfo:")
        }
    }
    private val methodVoipPlaySound by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.RingPlayer", "playSound, type: %s, changeStreamType: %s, shake: %s")
        }
    }
    private val methodVoipShowFloatingCard by dexMethod {
        matcher {
            usingEqStrings(".ui.voip.VoipFloatView")
            paramCount = 8
        }
    }
    private val methodVoipServiceExSetInviteContent by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to setInviteContent during calling, status =")
        }
    }
    private val methodVoipServiceExReject by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to reject with calling, status =")
        }
    }
    private val methodVoipBubbleHelperInsertMsg by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPBubbleHelper", "insertMsg() called with: voipInfo = ")
        }
    }

//    private val classVoipService by dexClass()
//    private val classVoipManager by dexClass()
//    private val classIncomingVoipInvite by dexClass()
//    private val classIncomingVoipILinkInvite by dexClass()
//    private val classMultiTalkInvite by dexClass()
//    private val classVoipFloatCard by dexClass()
//    private val classRecentForwardInfoHelperV3 by dexClass()
//    private val classContactRecommendHelperV3 by dexClass()
}
