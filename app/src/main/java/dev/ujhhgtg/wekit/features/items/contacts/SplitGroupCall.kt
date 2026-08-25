package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeUnsafeApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.contacts.SplitGroupCall.resolveDex
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.int
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import androidx.compose.ui.Modifier as UiModifier
import java.lang.reflect.Modifier as ReflectModifier

object SplitGroupCall : ClickableFeature(), IContactInfoProvider, IResolveDex {

    override val technicalId = "分裂群组通话"
    override val nameRes = R.string.feature_split_group_call_name
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val descriptionRes = R.string.feature_split_group_call_description

    private const val TAG = "SplitGroupCall"
    private const val PREF_KEY = "split_group_call"
    private const val OPERATION_DURATION_MS = 3000L

    private val batchRunning = AtomicBoolean(false)

    private enum class OperationMode(@StringRes val labelRes: Int) {
        VOIP(R.string.contacts_split_call_voip_mode),
        WALKIE_TALKIE(R.string.contacts_split_call_walkie_talkie_mode),
    }

    /** com.tencent.mm.plugin.multitalk.model.e3 —— SubCoreMultiTalk. */
    private val classSubCoreMultiTalk by dexClass()

    /** com.tencent.mm.plugin.multitalk.model.v0 —— MultiTalkManager. */
    internal val methodExitMultiTalk by dexMethod()

    /** com.tencent.mm.plugin.multitalk.ilinkservice.i4 —— ILinkService (enum, 单例 INSTANCE). */
    private val classILinkService by dexClass()

    /** com.tencent.mm.plugin.multitalk.ilinkservice.w —— ILinkMember. */
    private val classILinkMember by dexClass()

    /** com.tencent.mm.plugin.multitalk.ilinkservice.n1 —— 邀请任务 (Runnable). */
    private val classInviteTask by dexClass()

    /** e3.Ri() —— 获取 MultiTalkManager 单例. */
    internal val methodGetMultiTalkManager by dexMethod()

    /** v0.D(e4) —— 设置通话状态 (onChangeMultiTalkStatus). */
    private val methodSetStatus by dexMethod()

    /** v0.O(String, int) —— setCurrentMTSDKMode, 记录群 -> 通话模式. */
    private val methodSetMtSdkMode by dexMethod()

    /** i4.N(long, String) —— 设置自身 uin 与用户名 (set name). */
    private val methodSetName by dexMethod()

    /** i4.J(Runnable) —— 投递任务到 ILink 串行工作线程. */
    private val methodPostTask by dexMethod()

    /** n1(i4, ArrayList<w>, String) —— 邀请任务构造器. */
    private val ctorInviteTask by dexConstructor()

    /**
     * c1(i4, int) —— 挂断任务 (Runnable), run() 调用 native Hangup(int)。
     * c1 与 i4 都含有字符串 "Hangup ret:", 但 i4 (enum) 的构造器签名是 (String, int),
     * 因此用 (i4, int) 的参数签名即可唯一命中 c1 的构造器。
     */
    private val ctorHangupTask by dexConstructor()

    /** i4.INSTANCE —— ILinkService 单例. */
    private val fieldILinkInstance by dexField()

    /**
     * i4.f166883p1 —— 房间 ID (chatroom username) 字符串字段, 进入 native Invite。
     * i4 上有多个 String 字段, 无法按名字/顺序命中; 通过 "唯一读取该字段的方法" 反查:
     * 方法 p(b) 含字符串 "start audio device failed", 且其中唯一被读取的 String 字段即 f166883p1。
     * 由 [resolveDex] 手动填充。
     */
    private val fieldRoomId by dexField()

    /** TalkRoomServer.enterTalkRoom(String, int) —— 发起「实时对讲机」. */
    private val methodEnterTalkRoom by dexMethod {
        matcher {
            usingStrings("enterTalkRoom %s scene %d")
            paramTypes("java.lang.String", "int")
            returnType("void")
        }
    }

    /** TalkRoomServer.exitTalkRoom() —— 终止当前「实时对讲机」. */
    private val methodExitTalkRoom by dexMethod {
        matcher {
            declaredClass = methodEnterTalkRoom.data.declaredClassName
            usingStrings("exitTalkRoom", "exitTalkRoom: has exited")
            paramCount = 0
            returnType("void")
        }
    }

    /** SubCoreTalkRoom 上返回 TalkRoomServer 单例的静态方法. */
    private val methodGetTalkRoomServer by dexMethod {
        matcher {
            modifiers = ReflectModifier.PUBLIC or ReflectModifier.STATIC
            paramCount = 0
            returnType = methodEnterTalkRoom.data.declaredClassName
        }
    }

    /** TalkRoomServer 当前房间 ID; 空值表示没有正在进行的实时对讲. */
    private val fieldCurrentTalkRoom by dexField {
        matcher {
            declaredClass = methodEnterTalkRoom.data.declaredClassName
            type = "java.lang.String"
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val subCoreMultiTalkClasses = dexKit.findClass {
            matcher {
                usingStrings("MicroMsg.SubCoreMultiTalk", "add , is running , forbid add")
            }
        }

        when (subCoreMultiTalkClasses.size) {
            1 -> classSubCoreMultiTalk.setDescriptor(subCoreMultiTalkClasses.single())
            0 -> {
                val reason = "legacy MultiTalk and ILink architecture is absent"
                classSubCoreMultiTalk.setPlaceholderDescriptor(true, reason)
                methodExitMultiTalk.setPlaceholderDescriptor(true, reason)
                classILinkService.setPlaceholderDescriptor(true, reason)
                classILinkMember.setPlaceholderDescriptor(true, reason)
                classInviteTask.setPlaceholderDescriptor(true, reason)
                methodGetMultiTalkManager.setPlaceholderDescriptor(true, reason)
                methodSetStatus.setPlaceholderDescriptor(true, reason)
                methodSetMtSdkMode.setPlaceholderDescriptor(true, reason)
                methodSetName.setPlaceholderDescriptor(true, reason)
                methodPostTask.setPlaceholderDescriptor(true, reason)
                ctorInviteTask.setPlaceholderDescriptor(true, reason)
                ctorHangupTask.setPlaceholderDescriptor(true, reason)
                fieldILinkInstance.setPlaceholderDescriptor(true, reason)
                fieldRoomId.setPlaceholderDescriptor(true, reason)
                return
            }

            else -> error(
                "multiple SubCoreMultiTalk classes found: " +
                    subCoreMultiTalkClasses.joinToString { it.name }
            )
        }

        methodExitMultiTalk.find(dexKit) {
            matcher {
                usingStrings(
                    "exitCurrentMultiTalk: isReject %b isMissCall %b isPhoneCall %b isNetworkError %b"
                )
            }
        }

        classILinkService.find(dexKit) {
            matcher {
                usingStrings(
                    "steve: initsession : mIsInitedEngine :%b mIsInitingEngine %b " +
                        "mCurrentStatus %d mIsJoiningRoom %b"
                )
            }
        }

        classILinkMember.find(dexKit) {
            matcher {
                usingStrings("ILinkMember{memberId=")
            }
        }

        classInviteTask.find(dexKit) {
            matcher {
                usingStrings("enter inviteSync. %s, %s, %d, %b")
            }
        }

        methodGetMultiTalkManager.find(dexKit) {
            matcher {
                declaredClass = classSubCoreMultiTalk.data.name
                modifiers = ReflectModifier.STATIC or ReflectModifier.PUBLIC
                returnType = methodExitMultiTalk.data.declaredClassName
            }
        }

        methodSetStatus.find(dexKit) {
            matcher {
                declaredClass = methodExitMultiTalk.data.declaredClassName
                paramCount = 1
                usingStrings("onChangeMultiTalkStatus is %s")
            }
        }

        methodSetMtSdkMode.find(dexKit) {
            matcher {
                declaredClass = methodExitMultiTalk.data.declaredClassName
                paramCount = 2
                usingStrings("setCurrentMTSDKMode groupid:%s, mode:%d")
            }
        }

        methodSetName.find(dexKit) {
            matcher {
                declaredClass = classILinkService.data.name
                paramCount = 2
                usingStrings("set name=%s, uin=%d")
            }
        }

        methodPostTask.find(dexKit) {
            matcher {
                declaredClass = classILinkService.data.name
                paramCount = 1
                paramTypes("java.lang.Runnable")
            }
        }

        ctorInviteTask.find(dexKit) {
            matcher {
                declaredClass = classInviteTask.data.name
                paramCount = 3
                paramTypes(
                    classILinkService.data.name,
                    "java.util.ArrayList",
                    "java.lang.String",
                )
            }
        }

        ctorHangupTask.find(dexKit) {
            matcher {
                declaredClass {
                    usingStrings("Hangup ret:")
                }
                paramCount = 2
                paramTypes(classILinkService.data.name, "int")
            }
        }

        fieldILinkInstance.find(dexKit) {
            matcher {
                declaredClass = classILinkService.data.name
                type = classILinkService.data.name
                modifiers = ReflectModifier.PUBLIC or ReflectModifier.STATIC or ReflectModifier.FINAL
            }
        }

        val iLinkServiceName = classILinkService.data.name
        val readerMethod = dexKit.findMethod {
            matcher {
                declaredClass = classILinkService.data.name
                usingStrings("start audio device failed")
            }
        }.single()
        val roomIdField = readerMethod.usingFields
            .map { it.field }
            .single { it.className == iLinkServiceName && it.typeName == "java.lang.String" }
        fieldRoomId.setDescriptor(roomIdField)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            SingleContactSelector(
                context.localizedContactsString(R.string.feature_split_group_call_name),
                WeDatabaseApi.getGroups(),
                initialSelectedWxId = null,
                onDismiss = onDismiss,
            ) { wxId ->
                onDismiss()
                showSplitCallDialog(context, wxId)
            }
        }
    }

    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val wxId = activity.currentWxId ?: return emptyList()
        if (!wxId.endsWith("@chatroom")) return emptyList()

        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = activity.localizedContactsString(R.string.feature_split_group_call_name),
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false
        val wxId = activity.currentWxId ?: return true
        showSplitCallDialog(activity, wxId)
        return true
    }

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    private fun generateFakeGroupId(wxId: String): String {
        val rawId = wxId.substringBefore("@")
        val randomCount = Random.nextInt(1, 4)
        val cjkChars = (0 until randomCount).map {
            (0x4E00..0x9FA5).random().toChar()
        }.joinToString("")
        return "${rawId}${cjkChars}@chatroom"
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private fun showSplitCallDialog(context: Activity, wxId: String) {
        showComposeDialog(context) {
            var repeatCount by remember { mutableStateOf("1") }
            var mode by remember { mutableStateOf(OperationMode.WALKIE_TALKIE) }
            val availableModes = if (classSubCoreMultiTalk.isPlaceholder) {
                listOf(OperationMode.WALKIE_TALKIE)
            } else {
                OperationMode.entries
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_split_group_call_name)) },
                text = {
                    Column(
                        modifier = UiModifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = repeatCount,
                            onValueChange = { value ->
                                repeatCount = value.filter(Char::isDigit)
                            },
                            label = { Text(stringResource(R.string.contacts_split_call_repeat_count)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = UiModifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = UiModifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        ) {
                            availableModes.forEachIndexed { index, option ->
                                ToggleButton(
                                    checked = mode == option,
                                    onCheckedChange = { mode = option },
                                    shapes = when {
                                        availableModes.size == 1 ->
                                            ButtonGroupDefaults.connectedLeadingButtonShapes(
                                                shape = ToggleButtonDefaults.shape,
                                                pressedShape = ToggleButtonDefaults.pressedShape,
                                            )
                                        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                        index == availableModes.lastIndex ->
                                            ButtonGroupDefaults.connectedTrailingButtonShapes()
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                    },
                                    modifier = UiModifier
                                        .weight(1f)
                                        .semantics { role = Role.RadioButton },
                                ) {
                                    Text(stringResource(option.labelRes), maxLines = 1)
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
                        val count = repeatCount.toIntOrNull()
                        if (count == null || count <= 0) {
                            showToast(
                                context.localizedContactsString(R.string.contacts_split_call_invalid_repeat),
                            )
                            return@Button
                        }
                        if (!batchRunning.compareAndSet(false, true)) {
                            showToast(
                                context.localizedContactsString(R.string.contacts_split_call_already_running),
                            )
                            return@Button
                        }

                        onDismiss()
                        startBatch(context, wxId, count, mode)
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                }
            )
        }
    }

    private fun startBatch(
        context: Context,
        originalGroupId: String,
        repeatCount: Int,
        mode: OperationMode,
    ) {
        check(mode != OperationMode.VOIP || !classSubCoreMultiTalk.isPlaceholder) {
            "legacy MultiTalk and ILink architecture is unavailable"
        }
        thread(name = "SplitGroupCallBatchThread") {
            val generatedIds = mutableListOf<String>()
            var completed = 0
            var failed = 0
            var cleanupFailed = false

            try {
                val reservedIds = WeDatabaseApi.getGroups()
                    .mapTo(mutableSetOf()) { it.wxId }

                repeat(repeatCount) { index ->
                    val fakeGroupId = generateUniqueFakeGroupId(originalGroupId, reservedIds)
                    reservedIds += fakeGroupId
                    generatedIds += fakeGroupId

                    WeLogger.i(
                        TAG,
                        "batch ${index + 1}/$repeatCount: ${mode.name}, fakeGroupId=$fakeGroupId",
                    )
                    runCatching {
                        when (mode) {
                            OperationMode.VOIP ->
                                startAndStopVoip(context, originalGroupId, fakeGroupId)

                            OperationMode.WALKIE_TALKIE ->
                                startAndStopWalkieTalkie(fakeGroupId)
                        }
                    }.onSuccess {
                        completed++
                    }.onFailure { e ->
                        failed++
                        WeLogger.e(
                            TAG,
                            "batch ${index + 1}/$repeatCount failed for $fakeGroupId",
                            e,
                        )
                    }
                }
            } catch (e: Throwable) {
                failed += repeatCount - completed - failed
                WeLogger.e(TAG, "split group call batch aborted", e)
            } finally {
                runCatching {
                    DeleteFakeGroups.deleteFakeGroups(generatedIds)
                }.onFailure { e ->
                    cleanupFailed = true
                    WeLogger.e(TAG, "failed to clean generated fake groups", e)
                }
                batchRunning.set(false)

                runOnUiThread {
                    showToast(
                        context.localizedContactsQuantity(
                            if (cleanupFailed) R.plurals.contacts_split_call_done_cleanup_failed
                            else R.plurals.contacts_split_call_done_cleaned,
                            generatedIds.size,
                            completed,
                            failed,
                            generatedIds.size,
                        ),
                    )
                }
            }
        }
    }

    private fun generateUniqueFakeGroupId(
        originalGroupId: String,
        reservedIds: Set<String>,
    ): String {
        repeat(1000) {
            val candidate = generateFakeGroupId(originalGroupId)
            if (candidate !in reservedIds) return candidate
        }
        error("failed to generate a unique fake group ID")
    }

    /**
     * 复刻 TalkRoomUI 的进入/退出流程, 使用旧「实时对讲机」协议栈而非 MultiTalk 语音通话:
     *   TalkRoomServer.enterTalkRoom(fakeGroupId, 0) -> 等待请求下发 -> exitTalkRoom()。
     */
    private fun startAndStopWalkieTalkie(fakeGroupId: String) {
        val server = methodGetTalkRoomServer.method.invoke(null)
            ?: error("TalkRoomServer instance is null")
        var enterAttempted = false

        try {
            runOnUiThreadAndWait {
                val currentRoom = fieldCurrentTalkRoom.field.get(server) as? String
                check(currentRoom.isNullOrEmpty()) {
                    "another talk room is already active: $currentRoom"
                }

                WeLogger.i(TAG, "entering fake talk room: $fakeGroupId")
                enterAttempted = true
                methodEnterTalkRoom.method.invoke(server, fakeGroupId, 0)
            }

            Thread.sleep(OPERATION_DURATION_MS)
        } finally {
            if (enterAttempted) {
                runOnUiThreadAndWait {
                    val activeRoom = fieldCurrentTalkRoom.field.get(server) as? String
                    when {
                        activeRoom == fakeGroupId -> {
                            methodExitTalkRoom.method.invoke(server)
                            WeLogger.i(TAG, "fake talk room terminated: $fakeGroupId")
                        }

                        activeRoom.isNullOrEmpty() ->
                            WeLogger.i(TAG, "fake talk room already terminated: $fakeGroupId")

                        else -> error(
                            "talk room changed before exit: " +
                                    "expected=$fakeGroupId, active=$activeRoom",
                        )
                    }
                }
            }
        }
    }

    /**
     * 复刻 WeChat 发起群通话的真实流程 (com.tencent.mm.plugin.multitalk.ui.u#onMenuItemClick):
     *   1. v0.D(e4.Creating)          —— 通话状态置为「创建中」
     *   2. i4.N(selfUin, selfWxId)     —— 设置自身身份 (e2 据此把自己从被邀请者中剔除)
     *   3. i4.f166883p1 = fakeGroupId  —— 房间 ID
     *   4. i4.J(new n1(i4, members, fakeGroupId)) —— 投递邀请任务 -> 引擎初始化 -> native Invite (对方响铃)
     *   5. v0.O(fakeGroupId, 2)        —— 记录群 -> 通话模式
     * 之后延时若干秒 (让邀请下发、对方响铃), 再投递 c1(i4, 1) 触发 native Hangup 挂断。
     */
    private fun startAndStopVoip(context: Context, originalGroupId: String, fakeGroupId: String) {
        WeLogger.i(TAG, "initiating fake group call: $fakeGroupId (original: $originalGroupId)")

        val iLink = fieldILinkInstance.field.get(null)
            ?: error("ILinkService instance is null")
        val mgr = methodGetMultiTalkManager.method.invoke(null)
            ?: error("MultiTalkManager instance is null")

        // e4 状态枚举: [Init, Inviting, Creating, Starting, Talking]
        val statusEnumClass = methodSetStatus.method.parameterTypes[0]
        val statusValues = statusEnumClass.enumConstants
            ?: error("multitalk status is not an enum")
        check(statusValues.size >= 3) { "unexpected multitalk status enum: ${statusValues.size}" }
        val statusInit = statusValues[0]
        val statusCreating = statusValues[2]

        val statusField = mgr.javaClass.declaredFields
            .first { it.type == statusEnumClass }
            .apply { isAccessible = true }

        check(statusField.get(mgr) == statusInit) {
            "multitalk is not idle"
        }

        // 被邀请成员 = 原群真实成员 + 自己 (自己会在 e2 中被剔除, 不会响铃自身)
        val selfWxId = RuntimeConfig.loggedInWxId
        val selfUin = context
            .getSharedPreferences("system_config_prefs", Context.MODE_PRIVATE)
            .getInt("default_uin", 0)
            .toLong()

        val memberWxIds = WeDatabaseApi.getGroupMembers(originalGroupId)
            .map { it.wxId }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (selfWxId.isNotEmpty() && selfWxId !in memberWxIds) memberWxIds += selfWxId

        check(memberWxIds.isNotEmpty()) { "group has no members" }

        val memberList = ArrayList<Any>(memberWxIds.size)
        for (memberWxId in memberWxIds) {
            val member = WeUnsafeApi.allocateInstance(classILinkMember.clazz)!!
            member.reflekt().apply {
                // w 的 String 字段顺序: [openId, mUserName, mInviteUserName] -> [1] = mUserName
                fields { type = BString }[1].set(memberWxId)
                // w 的 int 字段顺序: [memberId, mStatus, mScreenStatus] -> [1] = mStatus
                fields { type = int }[1].set(2)
            }
            memberList.add(member)
        }

        var statusChangeAttempted = false
        var invitePostAttempted = false
        try {
            runOnUiThreadAndWait {
                statusChangeAttempted = true
                methodSetStatus.method.invoke(mgr, statusCreating)
                methodSetName.method.invoke(iLink, selfUin, selfWxId)
                fieldRoomId.field.set(iLink, fakeGroupId)

                val inviteTask =
                    ctorInviteTask.constructor.newInstance(iLink, memberList, fakeGroupId) as Runnable
                invitePostAttempted = true
                methodPostTask.method.invoke(iLink, inviteTask)

                methodSetMtSdkMode.method.invoke(mgr, fakeGroupId, 2)
                WeLogger.i(TAG, "invite posted for ${memberList.size} members")
            }

            // 等待邀请下发并让对方响铃, 再挂断
            Thread.sleep(OPERATION_DURATION_MS)
        } finally {
            if (statusChangeAttempted || invitePostAttempted) {
                runOnUiThreadAndWait {
                    val hangupError = if (invitePostAttempted) {
                        runCatching {
                            // native Hangup —— 停止响铃/结束通话
                            val hangupTask =
                                ctorHangupTask.constructor.newInstance(iLink, 1) as Runnable
                            methodPostTask.method.invoke(iLink, hangupTask)
                        }.exceptionOrNull()
                    } else {
                        null
                    }

                    // 复位 MultiTalkManager 状态 (等价于 v0.f(false, false)).
                    if (statusField.get(mgr) != statusInit) {
                        runCatching {
                            methodExitMultiTalk.method.invoke(
                                mgr,
                                false,
                                false,
                                false,
                                false,
                                true,
                                false,
                            )
                        }.onFailure { e ->
                            WeLogger.w(TAG, "exitCurrentMultiTalk failed, resetting status directly", e)
                            statusField.set(mgr, statusInit)
                        }
                    }
                    hangupError?.let { throw it }
                }
            }
        }
    }

    private fun runOnUiThreadAndWait(action: () -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        runOnUiThread {
            try {
                action()
            } catch (e: Throwable) {
                failure = e
            } finally {
                latch.countDown()
            }
        }

        latch.await()
        failure?.let { throw it }
    }
}
