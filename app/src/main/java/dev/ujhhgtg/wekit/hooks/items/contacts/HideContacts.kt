package dev.ujhhgtg.wekit.hooks.items.contacts

import android.app.Activity
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
import com.highcapable.kavaref.extension.isSubclassOf
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeConversationApi
import dev.ujhhgtg.wekit.hooks.api.core.WeCurrentActivityApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.ContactsSelectionDialog
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import dev.ujhhgtg.wekit.utils.reflection.resolve
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import kotlin.math.sqrt


@HookItem(
    path = "联系人与群组/隐藏联系人", description =
        """隐藏指定的联系人
隐藏位置:
1. 首页对话列表
2. 通讯录内联系人&群聊列表
3. 搜索界面 (没写完)
4. 锁屏自动关闭聊天界面
5. 摇一摇设备关闭聊天界面"""
)
object HideContacts : ClickableHookItem(), IResolvesDex {

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

            val activity = WeCurrentActivityApi.activity ?: return
            if (activity !is ChattingUI) return

            val wxId = activity.intent.getStringExtra("Chat_User")
            if (wxId !in hiddenContacts) return

            exitToMainActivity(activity)
        }
    }

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

                exitToMainActivity(WeCurrentActivityApi.activity ?: return)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // unused
        }
    }

    private fun exitToMainActivity(activity: Activity) {
        WeLogger.d(TAG, "leaving conversation page")
        val intent = Intent(activity, LauncherUI::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)
    }

    private lateinit var contactInfoField: Field
    private lateinit var usernameField: Field

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            WeConversationApi.setConversationsVisibility(false, hiddenContacts.also {
                WeLogger.d(TAG, "hid ${it.size} contacts in conversation list")
            }.toTypedArray())

            val context = thisObject.asResolver()
                .firstField { type { it isSubclassOf Activity::class } }
                .get()!! as Activity
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(ScreenOffReceiver, filter)
            WeLogger.d(TAG, "registered screen off receiver")
        }

        ChattingUI::class.resolve().apply {
            firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as Activity
                val wxId = activity.intent.getStringExtra("Chat_User")
                if (wxId !in hiddenContacts) return@hookAfter

                ShakeDetector.start(activity)
            }

            firstMethod { name = "onPause" }.hookAfter {
                ShakeDetector.stop()
            }
        }

        methodAddressMvvmListPreprocessList.hookBefore {
            val contacts = args[0] as MutableList<*>

            if (!::contactInfoField.isInitialized) {
                contactInfoField = contacts[0]!!.asResolver()
                    .firstField { type { it.name.startsWith("com.tencent.mm.storage") } }
                    .self
                usernameField = contactInfoField.type.asResolver()
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
            val cursor = thisObject.asResolver()
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

        methodChatroomContactAdapterInitCursor.method.declaringClass.resolve()
            .firstMethod { name = "getCount" }.hookAfter {
                result = (result as Int) - hiddenPositions.size
            }

        methodChatroomContactAdapterInitCursor.method.declaringClass.resolve()
            .firstMethod { name = "getView" }.hookBefore {
                val requestedPos = args[0] as Int
                var actualPos = requestedPos
                hiddenPositions.forEach {
                    if (actualPos >= it) {
                        actualPos++
                    }
                }
                args[0] = actualPos
            }

        // TODO:
//        methodMainAdapterPreformSearch.hookAfter {
//            val queryString = args[1] as String
//            val searchUnit = args[0]
//            val searchResults = (searchUnit.asResolver()
//                .optional()
//                .firstFieldOrNull {
//                    type = java.util.List::class
//                    superclass()
//                } ?: return@hookAfter)
//                .get()!! as MutableList<*>
//
//            val res = searchResults.firstOrNull() ?: return@hookAfter
//            WeLogger.d(TAG, "queryString=$queryString, results.size=${searchResults.size}, elem=\n${describeContent(res)}")
//            val elems = (res.asResolver().optional().firstFieldOrNull { name = "f" } ?: return@hookAfter).get()!!.cast<List<*>>()
//            if (elems.first()?.javaClass?.name != "tx2.z") return@hookAfter
//            val elem = elems.first()!!
//            WeLogger.d(TAG, describeContent(elem))
//            WeLogger.d(TAG, describeContent(elem.asResolver().firstField { name = "n"; superclass() }.get()!!.cast<List<*>>().first()))
//        }
    }

    private val hiddenPositions = mutableIntSetOf()

    override fun onClick(context: Context) {
        val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        showComposeDialog(context) {
            ContactsSelectionDialog(
                title = "选择要隐藏的联系人",
                contacts = regularContacts,
                initialSelectedWxIds = hiddenContacts,
                onDismiss = onDismiss
            ) {
                showToast("已保存 ${it.size} 个联系人, 隐藏状态可能需要重启微信生效")
                hiddenContacts = it
                onDismiss()
            }
        }
    }

    private val methodMainAdapterPreformSearch by dexMethod()
    private val methodAddressMvvmListPreprocessList by dexMethod()
    private val methodChatroomContactAdapterInitCursor by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodMainAdapterPreformSearch.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.fts.ui")
            matcher {
                usingEqStrings("MicroMsg.FTS.FTSMainAdapter", "tryReSortUIUnit, relevantSearchUIUnitIdx: (%d)<->chatRoomUIUnitIdx: (%d)")
            }
        }

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
    }
}
