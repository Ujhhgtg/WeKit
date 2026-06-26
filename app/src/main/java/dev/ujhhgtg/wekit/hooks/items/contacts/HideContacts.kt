package dev.ujhhgtg.wekit.hooks.items.contacts

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
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeConversationApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.BString
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import kotlin.math.sqrt


@HookItem(
    name = "隐藏联系人", categories = ["联系人与群组"], description =
        """隐藏指定的联系人
隐藏位置:
1. 首页对话列表
2. 通讯录内联系人&群聊列表
3. 首页搜索界面
4. 锁屏自动关闭聊天界面
5. 摇一摇设备关闭聊天界面"""
)
object HideContacts : ClickableHookItem(), IResolveDex {

    private val TAG = This.Class.simpleName

    private const val KEY_CONTACTS = "hidden_contacts"

    var hiddenContacts
        get() = WePrefs.getStringSetOrDef(KEY_CONTACTS, emptySet())
        set(value) {
            for (convId in value) {
                WeConversationApi.setIfNotifyNewMessages(convId, false)
            }
            WePrefs.putStringSet(KEY_CONTACTS, value)
        }

    private object ScreenOffReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return

            if (chattingUi == null) return

            val wxId = chattingUi!!.intent.getStringExtra("Chat_User")
            if (wxId !in hiddenContacts) return

            exitToMainActivity()
        }
    }

    private var chattingUi: ChattingUI? = null

    private object ShakeDetector : SensorEventListener {

        private var sensorManager: SensorManager? = null
        private var lastShakeTime: Long = 0
        private const val SHAKE_THRESHOLD = 4.5f // higher = harder shake required

        fun start(context: Context) {
            WeLogger.d(TAG, "starting shake detector")

            if (sensorManager != null) return

            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
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

    private lateinit var contactInfoField: Field
    private lateinit var usernameField: Field

    override fun onEnable() {
        // --- home screen conversation list ---

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            WeConversationApi.setConversationsVisibility(false, hiddenContacts.also {
                WeLogger.d(TAG, "hid ${it.size} contacts in conversation list")
            }.toTypedArray())

            val context = thisObject.reflekt()
                .firstField { type { it isSubclassOf Activity::class } }
                .get()!! as Activity
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(ScreenOffReceiver, filter)
            WeLogger.d(TAG, "registered screen off receiver")
        }

        // --- shake to leave ---

        ChattingUI::class.reflekt().apply {
            firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as ChattingUI

                chattingUi = activity

                val wxId = activity.intent.getStringExtra("Chat_User")
                if (wxId !in hiddenContacts) return@hookAfter

                ShakeDetector.start(activity)
            }

            firstMethod { name = "onPause" }.hookAfter {
                chattingUi = null
                ShakeDetector.stop()
            }
        }

        // --- friends & groups list ---

        methodAddressMvvmListPreprocessList.hookBefore {
            val contacts = args[0] as MutableList<*>

            if (!::contactInfoField.isInitialized) {
                contactInfoField = contacts[0]!!.reflekt()
                    .firstField { type { it.name.startsWith("com.tencent.mm.storage") } }
                    .self
                usernameField = contactInfoField.type.reflekt()
                    .firstField {
                        name = "field_username"
                        superclass()
                    }.self.makeAccessible()
            }

            val hiddenContacts = hiddenContacts

            contacts.removeAll { contact ->
                val contactInfo = contactInfoField.get(contact!!)
                val username = usernameField.get(contactInfo) as String
                username in hiddenContacts
            }
        }

        methodChatroomContactAdapterInitCursor.hookAfter {
            val cursor = thisObject.reflekt()
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
            if (wxId in hiddenContacts) {
                pendingVoipUser = wxId
                result = null
            }
        }

        listOf(
            methodVoipAcceptIncomingCall, methodVoipStartAcceptVoip
        ).forEach { it.hookBefore {
            val callerWxId = args[0].reflekt().firstField { type = BString }.get()!! as String
            if (callerWxId in hiddenContacts) {
                pendingVoipUser = callerWxId
                result = null
            }
        } }

        methodVoipShowFloatingCard.hookBefore {
            val wxId = args[5] as? String? ?: return@hookBefore
            if (wxId in hiddenContacts) {
                pendingVoipUser = wxId
                result = null
            }
        }

        methodVoipServiceExSetInviteContent.hookBefore {
            val wxId = args[0].reflekt().firstField { type = BString }.get()!! as String
            if (wxId in hiddenContacts) {
                pendingVoipUser = wxId
                if (autoRejectVoip) {
                    WeLogger.i(TAG, "rejecting call")
                    methodVoipServiceExReject.method.invoke(thisObject)
                }
                result = null
            }
        }

        methodVoipBubbleHelperInsertMsg.hookBefore {
            val wxId = args[0] as String
            if (wxId in hiddenContacts) {
                result = null
            }
        }

        VoipForegroundService::class.reflekt().firstMethod { name = "onStartCommand" }.hookBefore {
            val self = thisObject as VoipForegroundService
            val intent = args[0] as? Intent? ?: return@hookBefore
            val wxId = intent.getStringExtra("Voip_User") ?: return@hookBefore
            if (wxId in hiddenContacts) {
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
    }

    private var pendingVoipUser: String? = null

    private const val SQL_SELECT_MESSAGE =
        "SELECT type, subtype, entity_id, aux_index, MAX(timestamp) as maxTime, count(aux_index) as msgCount, talker FROM FTS5MetaMessage"

    private const val SQL_SELECT_MESSAGES_BY_KEYWORD =
        "SELECT FTS5MetaMessage.docid, type, subtype, entity_id, aux_index, timestamp, talker FROM FTS5MetaMessage"

    private val FTS_SQL_REGEX =
        Regex("^SELECT (FTS5MetaContact|FTS5MetaTopHits|FTS5MetaKefuContact|FTS5MetaFeature|FTS5MetaWeApp|FTS5MetaFinderFollow|FTS5MetaFavorite)\\.docid, type, subtype, entity_id, aux_index,.*")

    private val hiddenPositions = mutableIntSetOf()

    private var autoRejectVoip by prefOption("hide_auto_reject", false)

    override fun onClick(context: Context) {
        val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            AlertDialogContent(title = { Text("隐藏联系人") },
                text = {
                    DefaultColumn {
                        var autoRejectVoipInput by remember { mutableStateOf(autoRejectVoip) }

                        ListItem(
                            headlineContent = { Text("配置隐藏列表") },
                            supportingContent = { Text("点击配置联系人隐藏列表") },
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = "选择要隐藏的联系人",
                                        contacts = regularContacts,
                                        initialSelectedWxIds = hiddenContacts,
                                        onDismiss = onDismiss
                                    ) {
                                        showToast("已保存 ${it.size} 个联系人, 重启微信以使更改生效")
                                        hiddenContacts = it
                                        onDismiss()
                                    }
                                }
                            }
                        )

                        ListItem(
                            headlineContent = { Text("自动拒绝音视频通话") },
                            supportingContent = { Text("不保证有效") },
                            trailingContent = {
                                Switch(checked = autoRejectVoipInput, onCheckedChange = null)
                            },
                            modifier = Modifier.clickable {
                                autoRejectVoipInput = !autoRejectVoipInput
                                autoRejectVoip = autoRejectVoipInput
                            }
                        )
                    }
                })
        }
    }

//    private val methodMainAdapterPerformSearch by dexMethod()
    private val methodAddressMvvmListPreprocessList by dexMethod()
    private val methodChatroomContactAdapterInitCursor by dexMethod()
    private val methodVoipLaunchNotify by dexMethod()
    private val methodVoipLaunchIncomingCardAsync by dexMethod()
    private val methodVoipAcceptIncomingCall by dexMethod()
    private val methodVoipStartAcceptVoip by dexMethod()
    private val methodVoipPlaySound by dexMethod()
    private val methodVoipShowFloatingCard by dexMethod()
    private val methodVoipServiceExSetInviteContent by dexMethod()
    private val methodVoipServiceExReject by dexMethod()
    private val methodVoipBubbleHelperInsertMsg by dexMethod()

//    private val classVoipService by dexClass()
//    private val classVoipManager by dexClass()
//    private val classIncomingVoipInvite by dexClass()
//    private val classIncomingVoipILinkInvite by dexClass()
//    private val classMultiTalkInvite by dexClass()
//    private val classVoipFloatCard by dexClass()
//    private val classRecentForwardInfoHelperV3 by dexClass()
//    private val classContactRecommendHelperV3 by dexClass()

    override fun resolveDex(dexKit: DexKitBridge) {
//        methodMainAdapterPerformSearch.find(dexKit) {
//            searchPackages("com.tencent.mm.plugin.fts.ui")
//            matcher {
//                usingEqStrings("MicroMsg.FTS.FTSMainAdapter", "tryReSortUIUnit, relevantSearchUIUnitIdx: (%d)<->chatRoomUIUnitIdx: (%d)")
//            }
//        }

        methodAddressMvvmListPreprocessList.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.ui.contact.address.AddressLiveList"
                usingEqStrings("snapshotList")
            }
        }

        methodChatroomContactAdapterInitCursor.find(dexKit) {
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

        methodVoipLaunchNotify.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.VoIPMP.CoreV2", "launchNotify")
            }
        }

        methodVoipLaunchIncomingCardAsync.find(dexKit) {
            matcher {
                // 8.0.76 changed from "launchInComingCardAsync: " to "[volume report] launchInComingCardAsync: "
                usingStrings("MicroMsg.VoIPMP.CoreV2", "launchInComingCardAsync: ")
            }
        }

        methodVoipAcceptIncomingCall.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.voip")
            matcher {
                usingEqStrings("MicroMsg.VoipIncomingCallManager", "acceptIncomingCal, roomInfo:")
            }
        }

        methodVoipStartAcceptVoip.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.voip")
            matcher {
                usingEqStrings("MicroMsg.VoipIncomingCallManager", "startAcceptVoIP, roomInfo:")
            }
        }

        methodVoipPlaySound.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.RingPlayer", "playSound, type: %s, changeStreamType: %s, shake: %s")
            }
        }

        methodVoipShowFloatingCard.find(dexKit) {
            matcher {
                usingEqStrings(".ui.voip.VoipFloatView")
                paramCount = 8
            }
        }

        methodVoipServiceExSetInviteContent.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to setInviteContent during calling, status =")
            }
        }

        methodVoipServiceExReject.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to reject with calling, status =")
            }
        }

        methodVoipBubbleHelperInsertMsg.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.VoIPBubbleHelper", "insertMsg() called with: voipInfo = ")
            }
        }

//        classVoipService.find(dexKit) {
//            searchPackages("com.tencent.mm.plugin.voip")
//            matcher {
//                usingStrings("MicroMsg.Voip.VoipService")
//            }
//        }

//        classVoipManager.find(dexKit) {
//            searchPackages("com.tencent.mm.plugin.voip")
//            matcher {
//                usingStrings("MicroMsg.Voip.VoipMgr")
//            }
//        }
//
//        classIncomingVoipInvite.find(dexKit) {
//            searchPackages("com.tencent.mm.plugin.voip")
//            matcher {
//                usingStrings("/cgi-bin/micromsg-bin/voipinvite")
//            }
//        }
//
//        classIncomingVoipILinkInvite.find(dexKit) {
//            searchPackages("com.tencent.mm.plugin.voip")
//            matcher {
//                usingStrings("/cgi-bin/micromsg-bin/voipilinkinvite")
//            }
//        }
//
//        classMultiTalkInvite.find(dexKit) {
//            searchPackages("com.tencent.mm.plugin.multitalk")
//            matcher {
//                usingStrings("MicroMsg.MT.MultiTalkManager")
//            }
//        }
//
//        classVoipFloatCard.find(dexKit) {
//            matcher {
//                usingStrings("voip_content_voice", "voip_content_video")
//            }
//        }
//
//        classRecentForwardInfoHelperV3.find(dexKit) {
//            searchPackages("com.tencent.mm.ui.contact")
//            matcher {
//                usingStrings("MicroMsg.RecentForwardInfoStorage", "[query] list size=")
//            }
//        }
//
//        classContactRecommendHelperV3.find(dexKit) {
//            searchPackages("com.tencent.mm.ui.contact")
//            matcher {
//                usingStrings("MicroMsg.ContactRecommendHelper", "getChatroomByMembername cnt:%d time:%d")
//            }
//        }
    }
}
