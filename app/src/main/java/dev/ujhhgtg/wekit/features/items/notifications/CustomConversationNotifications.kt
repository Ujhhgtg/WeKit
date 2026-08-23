// TODO
// Claude has been going insane while writing this
// needs more review
//@file:Suppress("DEPRECATION")
//
//package dev.ujhhgtg.wekit.features.items.notifications
//
//import android.app.Notification
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.content.Intent
//import android.media.AudioAttributes
//import android.media.RingtoneManager
//import android.net.Uri
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.rememberLauncherForActivityResult
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.foundation.clickable
//import androidx.compose.foundation.layout.Arrangement
//import androidx.compose.foundation.layout.Row
//import androidx.compose.foundation.layout.Spacer
//import androidx.compose.foundation.layout.fillMaxHeight
//import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.padding
//import androidx.compose.foundation.layout.size
//import androidx.compose.foundation.layout.width
//import androidx.compose.foundation.lazy.LazyColumn
//import androidx.compose.foundation.lazy.items
//import androidx.compose.material3.HorizontalDivider
//import androidx.compose.material3.Icon
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.SegmentedButton
//import androidx.compose.material3.SegmentedButtonDefaults
//import androidx.compose.material3.SingleChoiceSegmentedButtonRow
//import androidx.compose.material3.Text
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.LaunchedEffect
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateListOf
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.rememberCoroutineScope
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.unit.dp
//import androidx.core.net.toUri
//import com.composables.icons.materialsymbols.MaterialSymbols
//import com.composables.icons.materialsymbols.outlined.Add
//import com.composables.icons.materialsymbols.outlined.Delete
//import com.composables.icons.materialsymbols.outlined.Music_note
//import dev.ujhhgtg.reflekt.reflekt
//import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
//import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
//import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
//import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
//import dev.ujhhgtg.wekit.features.core.ClickableFeature
//import dev.ujhhgtg.wekit.preferences.WePrefs
//import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
//import dev.ujhhgtg.wekit.ui.content.Button
//import dev.ujhhgtg.wekit.ui.content.DefaultColumn
//import dev.ujhhgtg.wekit.ui.content.IconButton
//import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
//import dev.ujhhgtg.wekit.ui.content.TextButton
//import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
//import dev.ujhhgtg.wekit.utils.HostInfo
//import dev.ujhhgtg.wekit.utils.TargetProcesses
//import dev.ujhhgtg.wekit.utils.android.getSystemService
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import java.io.Serializable
//
// Disabled feature metadata (
//    name = "自定义对话通知",
//    categories = ["通知"],
//    description = "为每个对话单独设定通知方式\n• 声音:跟随全局 / 无声 / 自定义铃声\n• 振动: 跟随全局 / 短/ 长 / 禁用\n• 优先级: 跟随全局 / 低 / 中 / 高 / 紧急\n• 遵守免打扰: 跟随全局 / 关 / 开"
//)
//object CustomConversationNotifications : ClickableFeature(), IResolveDex {
//
//    // WeKit-managed notification channels for priority overrides.
//    // These are created once in onEnable(); the user can further tune them in system settings.
//    private const val CHANNEL_SILENT = "wekit_msg_silent"   // IMPORTANCE_LOW, no sound
//    private const val CHANNEL_LOW = "wekit_msg_low"      // IMPORTANCE_LOW, no sound
//    private const val CHANNEL_DEFAULT = "wekit_msg_default"  // IMPORTANCE_DEFAULT
//    private const val CHANNEL_HIGH = "wekit_msg_high"     // IMPORTANCE_HIGH
//    private const val CHANNEL_URGENT = "wekit_msg_max"      // IMPORTANCE_MAX
//
//    // WeChat's own channel IDs (from iv4.a in the decompiled code)
//    private const val WECHAT_CHANNEL_NORMAL = "message_channel_new_id"
//
//    // Used during WeChat's background-inactive quiet hours:
//    private const val WECHAT_CHANNEL_DND = "message_dnd_mode_channel_id"
//
//    // com.tencent.mm.booter.notification.x.d — dealNotify(x, talker, content, int, int, boolean)
//    private val methodDealNotify by dexMethod {
//        searchPackages("com.tencent.mm.booter.notification")
//        matcher {
//            paramCount(6)
//            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
//        }
//    }
//
//    // Own ThreadLocal — never shared with NotificationsEvolved.
//    private val currentTalker = ThreadLocal<String?>()
//
//    override val shouldLoadInCurrentProcess
//        get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_PUSH
//
//    override val alwaysEnabled = true
//    override val noSwitchWidget = true
//
//    // ---------- Global defaults (stored as individual WePrefs keys) ----------
//
//    private var globalSoundModeStr by WePrefs.prefOption("ccn_global_sound", NotifSoundMode.GLOBAL.name)
//    var globalSoundMode: NotifSoundMode
//        get() = runCatching { NotifSoundMode.valueOf(globalSoundModeStr) }.getOrDefault(NotifSoundMode.GLOBAL)
//        set(v) {
//            globalSoundModeStr = v.name
//        }
//
//    var globalSoundUri by WePrefs.prefOption("ccn_global_sound_uri", null as String?)
//
//    private var globalVibrationModeStr by WePrefs.prefOption("ccn_global_vibration", NotifVibrationMode.GLOBAL.name)
//    var globalVibrationMode: NotifVibrationMode
//        get() = runCatching { NotifVibrationMode.valueOf(globalVibrationModeStr) }.getOrDefault(NotifVibrationMode.GLOBAL)
//        set(v) {
//            globalVibrationModeStr = v.name
//        }
//
//    private var globalPriorityModeStr by WePrefs.prefOption("ccn_global_priority", NotifPriorityMode.GLOBAL.name)
//    var globalPriorityMode: NotifPriorityMode
//        get() = runCatching { NotifPriorityMode.valueOf(globalPriorityModeStr) }.getOrDefault(NotifPriorityMode.GLOBAL)
//        set(v) {
//            globalPriorityModeStr = v.name
//        }
//
//    private var globalDndModeStr by WePrefs.prefOption("ccn_global_dnd", NotifDndMode.IGNORE.name)
//    var globalDndMode: NotifDndMode
//        get() = runCatching { NotifDndMode.valueOf(globalDndModeStr) }.getOrDefault(NotifDndMode.IGNORE)
//        set(v) {
//            globalDndModeStr = v.name
//        }
//
//    // ---------- Per-conversation overrides ----------
//
//    private fun convPrefsKey(wxId: String) = "ccn_conv_$wxId"
//
//    fun getConvPrefs(wxId: String): ConvNotifPrefs {
//        return WePrefs.default.getObject(convPrefsKey(wxId)) as? ConvNotifPrefs ?: ConvNotifPrefs()
//    }
//
//    fun setConvPrefs(wxId: String, prefs: ConvNotifPrefs) {
//        if (prefs.isAllGlobal) {
//            WePrefs.remove(convPrefsKey(wxId))
//        } else {
//            WePrefs.default.putObject(convPrefsKey(wxId), prefs)
//        }
//    }
//
//    /** Wx IDs of conversations that have any non-GLOBAL override. */
//    fun listConvOverrides(): Set<String> {
//        val prefix = "ccn_conv_"
//        return WePrefs.default.getAll()
//            .keys
//            .filter { it.startsWith(prefix) }
//            .map { it.removePrefix(prefix) }
//            .toSet()
//    }
//
//    // ---------- Resolve effective settings ----------
//
//    /** Resolve the effective sound mode, falling through GLOBAL to the global setting. */
//    private fun ConvNotifPrefs.effectiveSound(): NotifSoundMode =
//        if (sound == NotifSoundMode.GLOBAL) globalSoundMode else sound
//
//    private fun ConvNotifPrefs.effectiveSoundUri(): String? =
//        if (sound == NotifSoundMode.GLOBAL) globalSoundUri else soundUri
//
//    private fun ConvNotifPrefs.effectiveVibration(): NotifVibrationMode =
//        if (vibration == NotifVibrationMode.GLOBAL) globalVibrationMode else vibration
//
//    private fun ConvNotifPrefs.effectivePriority(): NotifPriorityMode =
//        if (priority == NotifPriorityMode.GLOBAL) globalPriorityMode else priority
//
//    private fun ConvNotifPrefs.effectiveDnd(): NotifDndMode =
//        if (dnd == NotifDndMode.GLOBAL) globalDndMode else dnd
//
//    // ---------- onEnable ----------
//
//    override fun onEnable() {
//        ensureChannels()
//
//        // Capture talker BEFORE dealNotify runs.Priority49: runs after NotificationsEvolved (50)
//        // so both ThreadLocals are set before Notification.Builder.build() is hooked.
//        methodDealNotify.hookBefore(priority = 49) {
//            currentTalker.set(args[1] as? String)
//        }
//
//        // Hook build() after NotificationsEvolved (priority 49< 50) so MessagingStyle is
//        // already applied and we only adjust the channel / sound / vibration on top.
//        Notification.Builder::class.reflekt()
//            .firstMethod { name = "build" }
//            .hookBefore(priority = 49) {
//                applyOverrides(thisObject as Notification.Builder)
//            }
//    }
//
//    private fun applyOverrides(builder: Notification.Builder) {
//        // Read the partially-built Notification to get the channel ID.
//        val notif = builder.reflekt().firstField { type = Notification::class }.get() as Notification
//        val channelId = notif.channelId
//
//        val isNormalChannel = channelId == WECHAT_CHANNEL_NORMAL || (channelId != null && channelId.startsWith("message_channel")
//                && channelId != WECHAT_CHANNEL_DND)
//        val isDndChannel = channelId == WECHAT_CHANNEL_DND
//
//        if (!isNormalChannel && !isDndChannel) return
//
//        val talker = currentTalker.get() ?: return
//        currentTalker.remove()
//
//        val prefs = getConvPrefs(talker)
//        val effectiveDnd = prefs.effectiveDnd()
//        val effectiveSound = prefs.effectiveSound()
//        val effectiveVibration = prefs.effectiveVibration()
//        val effectivePriority = prefs.effectivePriority()
//
//        // --- DND channel handling ---
//        //
//        // WECHAT_CHANNEL_DND is used during WeChat's own background-deactive quiet hours.
//        // If DndMode.IGNORE: redirect to the normal channel so sound/vibration fire.
//        // If DndMode.OBEY (or GLOBAL→IGNORE default): respect the quiet-hours intent.
//        //
//        // Note: for conversations that WeChat has muted (isDnd=true), WeChat typically
//        // does NOT build a notification at all — those never reach this hook.
//        if (isDndChannel) {
//            if (effectiveDnd == NotifDndMode.IGNORE) {
//                builder.setChannelId(WECHAT_CHANNEL_NORMAL)
//                // Fall through to apply remaining overrides as if it were the normal channel.
//            } else {
//                // Obey the quiet-hours channel; apply only vibration/sound silencing if needed.
//                applyVibrationOverride(builder, effectiveVibration)
//                return
//            }
//        }
//
//        // If a conversation is currently muted in WeChat (isDnd=true) and our policy says OBEY,
//        // suppress sound and vibration on whatever notification WeChat does post.
//        val weChatDnd = runCatching {
//            WeDatabaseApi.isReady && WeConversationApi.isDnd(talker)
//        }.getOrDefault(false)
//
//        if (weChatDnd && effectiveDnd == NotifDndMode.OBEY) {
//            // Force onto the silent channel — no sound, no heads-up popup.
//            builder.setChannelId(CHANNEL_SILENT)
//            builder.setVibrate(longArrayOf())
//            return
//        }
//
//        // --- Priority / channel selection ---
//        // Sound=SILENT always overrides to the silent channel, regardless of priority.
//        val targetChannel: String? = when {
//            effectiveSound == NotifSoundMode.SILENT -> CHANNEL_SILENT
//            effectivePriority == NotifPriorityMode.LOW -> CHANNEL_LOW
//            effectivePriority == NotifPriorityMode.MEDIUM -> CHANNEL_DEFAULT
//            effectivePriority == NotifPriorityMode.HIGH -> CHANNEL_HIGH
//            effectivePriority == NotifPriorityMode.URGENT -> CHANNEL_URGENT
//            effectiveSound == NotifSoundMode.CUSTOM -> {
//                // Lazily create a channel for this specific ringtone URI.
//                val uri = prefs.effectiveSoundUri()?.toUri()
//                if (uri != null) ensureCustomSoundChannel(uri) else null
//            }
//
//            else -> null // GLOBAL sound + GLOBAL priority → keep WeChat's channel
//        }
//
//        if (targetChannel != null) {
//            builder.setChannelId(targetChannel)
//        }
//
//        // --- Vibration override (works per-notification even on API 26+) ---
//        applyVibrationOverride(builder, effectiveVibration)
//    }
//
//    private fun applyVibrationOverride(builder: Notification.Builder, mode: NotifVibrationMode) {
//        when (mode) {
//            NotifVibrationMode.SHORT -> builder.setVibrate(longArrayOf(0, 250))
//            NotifVibrationMode.LONG -> builder.setVibrate(longArrayOf(0, 500, 200, 500))
//            NotifVibrationMode.DISABLED -> builder.setVibrate(longArrayOf())
//            NotifVibrationMode.GLOBAL -> Unit // no-op
//        }
//    }
//
//    // ---------- Notification channels ----------
//
//    private fun ensureChannels() {
//        val nm = HostInfo.application.getSystemService<NotificationManager>()
//
//        fun createChannel(id: String, name: String, importance: Int, sound: Uri? = null, vibrate: Boolean = true) {
//            if (nm.getNotificationChannel(id) != null) return
//            val ch = NotificationChannel(id, name, importance).apply {
//                if (sound != null) {
//                    setSound(
//                        sound, AudioAttributes.Builder()
//                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
//                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
//                            .build()
//                    )
//                } else {
//                    setSound(null, null)
//                }
//                enableVibration(vibrate)
//                if (!vibrate) vibrationPattern = longArrayOf()
//            }
//            nm.createNotificationChannel(ch)
//        }
//
//        createChannel(CHANNEL_SILENT, "WeKit静音通知", NotificationManager.IMPORTANCE_LOW, sound = null, vibrate = false)
//        createChannel(CHANNEL_LOW, "WeKit 低优先级通知", NotificationManager.IMPORTANCE_LOW, sound = null, vibrate = false)
//        createChannel(CHANNEL_DEFAULT, "WeKit 默认通知", NotificationManager.IMPORTANCE_DEFAULT)
//        createChannel(CHANNEL_HIGH, "WeKit 高优先级通知", NotificationManager.IMPORTANCE_HIGH)
//        createChannel(CHANNEL_URGENT, "WeKit 紧急通知", NotificationManager.IMPORTANCE_MAX)
//    }
//
//    private fun ensureCustomSoundChannel(soundUri: Uri): String {
//        val id = "wekit_msg_custom_${soundUri.hashCode()}"
//        val nm = HostInfo.application.getSystemService<NotificationManager>()
//        if (nm.getNotificationChannel(id) == null) {
//            val ch = NotificationChannel(id, "WeKit 自定义铃声通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
//                setSound(
//                    soundUri, AudioAttributes.Builder()
//                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
//                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
//                        .build()
//                )
//                enableVibration(true)
//            }
//            nm.createNotificationChannel(ch)
//        }
//        return id
//    }
//
//    // ---------- Settings UI ----------
//
//    override fun onClick(context: ComponentActivity) {
//        showComposeDialog(context) {
//            SettingsDialog(onDismiss = onDismiss)
//        }
//    }
//}
//
//@Composable
//private fun SettingsDialog(onDismiss: () -> Unit) {
//    val feature = CustomConversationNotifications
//    val scope = rememberCoroutineScope()
//
//    // Global prefs state (read once; saved on confirm)
//    var globalSound by remember { mutableStateOf(feature.globalSoundMode) }
//    var globalSoundUri by remember { mutableStateOf(feature.globalSoundUri) }
//    var globalVibration by remember { mutableStateOf(feature.globalVibrationMode) }
//    var globalPriority by remember { mutableStateOf(feature.globalPriorityMode) }
//    var globalDnd by remember { mutableStateOf(feature.globalDndMode) }
//
//    // Per-conversation override list (mutable so we can add/remove)
//    val overrideWxIds = remember {
//        mutableStateListOf<String>().also { it.addAll(feature.listConvOverrides()) }
//    }
//    // Display names loaded async
//    val displayNames = remember { mutableStateOf(mapOf<String, String>()) }
//    LaunchedEffect(Unit) {
//        withContext(Dispatchers.IO) {
//            if (WeDatabaseApi.isReady) {
//                val map = overrideWxIds.associateWith { wxId ->
//                    WeDatabaseApi.getDisplayName(wxId)
//                }
//                withContext(Dispatchers.Main) { displayNames.value = map }
//            }
//        }
//    }
//
//    // Ringtone picker for global sound URI
//    val ringtonePicker = rememberLauncherForActivityResult(
//        ActivityResultContracts.StartActivityForResult()
//    ) { result ->
//        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
//        globalSoundUri = uri?.toString()
//    }
//
//    // Sub-dialog state: which wxId is being edited per-conversation
//    var editingWxId by remember { mutableStateOf<String?>(null) }
//    var showContactPicker by remember { mutableStateOf(false) }
//    var allContacts by remember { mutableStateOf(listOf<dev.ujhhgtg.wekit.features.api.core.models.IWeContact>()) }
//
//    // Load contacts when picker is about to open
//    LaunchedEffect(showContactPicker) {
//        if (showContactPicker && allContacts.isEmpty()) {
//            withContext(Dispatchers.IO) {
//                if (WeDatabaseApi.isReady) {
//                    val contacts = runCatching {
//                        WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups() +
//                                WeDatabaseApi.getOfficialAccounts()
//                    }.getOrDefault(emptyList())
//                    withContext(Dispatchers.Main) { allContacts = contacts }
//                }
//            }
//        }
//    }
//
//    // --- Contact picker overlay ---
//    if (showContactPicker) {
//        SingleContactSelector(
//            title = "选择要自定义通知的对话",
//            contacts = allContacts,
//            initialSelectedWxId = null,
//            onDismiss = { showContactPicker = false },
//            onConfirm = { wxId ->
//                showContactPicker = false
//                editingWxId = wxId
//            }
//        )
//        return
//    }
//
//    // --- Per-conversation settings sub-dialog ---
//    editingWxId?.let { wxId ->
//        val name = displayNames.value[wxId] ?: wxId
//        ConvPrefsDialog(
//            wxId = wxId,
//            displayName = name,
//            onDismiss = { editingWxId = null },
//            onSave = { prefs ->
//                feature.setConvPrefs(wxId, prefs)
//                // Refresh list: remove if all-global, keep/add if not
//                if (prefs.isAllGlobal) {
//                    overrideWxIds.remove(wxId)
//                } else if (!overrideWxIds.contains(wxId)) {
//                    overrideWxIds.add(wxId)
//                    scope.launch {
//                        val name2 = withContext(Dispatchers.IO) {
//                            WeDatabaseApi.getDisplayName(wxId)
//                        }
//                        displayNames.value += wxId to name2
//                    }
//                }
//                editingWxId = null
//            }
//        )
//        return
//    }
//
//    // --- Main settings dialog ---
//    AlertDialogContent(
//        modifier = Modifier.fillMaxHeight(0.85f),
//        title = { Text("自定义对话通知设置") },
//        text = {
//            LazyColumn(
//                verticalArrangement = Arrangement.spacedBy(16.dp)
//            ) {
//                //---- Global defaults section ----
//                item {
//                    Text(
//                        "全局设置",
//                        style = MaterialTheme.typography.titleSmall,
//                        color = MaterialTheme.colorScheme.primary
//                    )
//                }
//                item {
//                    NotifSoundRow(
//                        label = "声音",
//                        mode = globalSound,
//                        soundUri = globalSoundUri,
//                        onModeChange = { globalSound = it },
//                        onPickRingtone = {
//                            ringtonePicker.launch(
//                                Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
//                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
//                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
//                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
//                                    globalSoundUri?.let {
//                                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, it.toUri())
//                                    }
//                                }
//                            )
//                        },
//                        showGlobalOption = false// at global level there's no "跟随全局"
//                    )
//                }
//                item {
//                    NotifVibrationRow(
//                        label = "振动",
//                        mode = globalVibration,
//                        onModeChange = { globalVibration = it },
//                        showGlobalOption = false
//                    )
//                }
//                item {
//                    NotifPriorityRow(
//                        label = "优先级",
//                        mode = globalPriority,
//                        onModeChange = { globalPriority = it },
//                        showGlobalOption = false
//                    )
//                }
//                item {
//                    NotifDndRow(
//                        label = "遵守免打扰",
//                        mode = globalDnd,
//                        onModeChange = { globalDnd = it },
//                        showGlobalOption = false
//                    )
//                }
//
//                item { HorizontalDivider() }
//
//                // ---- Per-conversation overrides section ----
//                item {
//                    Row(
//                        modifier = Modifier.fillMaxWidth(),
//                        horizontalArrangement = Arrangement.SpaceBetween,
//                        verticalAlignment = Alignment.CenterVertically
//                    ) {
//                        Text(
//                            "对话覆盖",
//                            style = MaterialTheme.typography.titleSmall,
//                            color = MaterialTheme.colorScheme.primary
//                        )
//                        IconButton(onClick = { showContactPicker = true }) {
//                            Icon(MaterialSymbols.Outlined.Add, contentDescription = "添加覆盖")
//                        }
//                    }
//                }
//
//                if (overrideWxIds.isEmpty()) {
//                    item {
//                        Text(
//                            "暂无对话覆盖，点击右上角 + 添加",
//                            style = MaterialTheme.typography.bodySmall,
//                            color = MaterialTheme.colorScheme.onSurfaceVariant
//                        )
//                    }
//                } else {
//                    items(overrideWxIds) { wxId ->
//                        val name = displayNames.value[wxId] ?: wxId
//                        val prefs = feature.getConvPrefs(wxId)
//                        Row(
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .clickable { editingWxId = wxId }
//                                .padding(vertical = 8.dp),
//                            verticalAlignment = Alignment.CenterVertically,
//                            horizontalArrangement = Arrangement.SpaceBetween
//                        ) {
//                            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
//                                Text(name, style = MaterialTheme.typography.bodyMedium)
//                                Text(
//                                    prefs.summaryText(),
//                                    style = MaterialTheme.typography.bodySmall,
//                                    color = MaterialTheme.colorScheme.onSurfaceVariant
//                                )
//                            }
//                            IconButton(onClick = {
//                                feature.setConvPrefs(wxId, ConvNotifPrefs())// reset = remove
//                                overrideWxIds.remove(wxId)
//                            }) {
//                                Icon(
//                                    MaterialSymbols.Outlined.Delete,
//                                    contentDescription = "删除覆盖",
//                                    tint = MaterialTheme.colorScheme.error
//                                )
//                            }
//                        }
//                    }
//                }
//            }
//        },
//        confirmButton = {
//            Button(onClick = {
//                // Persist global settings
//                feature.globalSoundMode = globalSound
//                feature.globalSoundUri = globalSoundUri
//                feature.globalVibrationMode = globalVibration
//                feature.globalPriorityMode = globalPriority
//                feature.globalDndMode = globalDnd
//                onDismiss()
//            }) { Text("保存") }
//        },
//        dismissButton = {
//            TextButton(onDismiss) { Text("取消") }
//        }
//    )
//}
//
///** Human-readable summary of a ConvNotifPrefs for display in the override list. */
//private fun ConvNotifPrefs.summaryText(): String = buildString {
//    if (sound != NotifSoundMode.GLOBAL) append("声音:${sound.label} ")
//    if (vibration != NotifVibrationMode.GLOBAL) append("振动:${vibration.label} ")
//    if (priority != NotifPriorityMode.GLOBAL) append("优先级:${priority.label} ")
//    if (dnd != NotifDndMode.GLOBAL) append("免打扰:${dnd.label}")
//    if (isEmpty()) append("(已全部设为跟随全局)")
//}
//
//// ---------------------------------------------------------------------------
//// Per-conversation settings sub-dialog
//// ---------------------------------------------------------------------------
//
//@Composable
//private fun ConvPrefsDialog(
//    wxId: String,
//    displayName: String,
//    onDismiss: () -> Unit,
//    onSave: (ConvNotifPrefs) -> Unit,
//) {
//    val feature = CustomConversationNotifications
//    val initial = feature.getConvPrefs(wxId)
//
//    var sound by remember { mutableStateOf(initial.sound) }
//    var soundUri by remember { mutableStateOf(initial.soundUri) }
//    var vibration by remember { mutableStateOf(initial.vibration) }
//    var priority by remember { mutableStateOf(initial.priority) }
//    var dnd by remember { mutableStateOf(initial.dnd) }
//
//    val ringtonePicker = rememberLauncherForActivityResult(
//        ActivityResultContracts.StartActivityForResult()
//    ) { result ->
//        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
//        soundUri = uri?.toString()
//    }
//
//    AlertDialogContent(
//        title = { Text("「$displayName」通知设置") },
//        text = {
//            DefaultColumn {
//                NotifSoundRow(
//                    label = "声音",
//                    mode = sound,
//                    soundUri = soundUri,
//                    onModeChange = { sound = it },
//                    onPickRingtone = {
//                        ringtonePicker.launch(
//                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
//                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
//                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
//                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
//                                soundUri?.let {
//                                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, it.toUri())
//                                }
//                            }
//                        )
//                    },
//                    showGlobalOption = true
//                )
//                NotifVibrationRow(
//                    label = "振动",
//                    mode = vibration,
//                    onModeChange = { vibration = it },
//                    showGlobalOption = true
//                )
//                NotifPriorityRow(
//                    label = "优先级",
//                    mode = priority,
//                    onModeChange = { priority = it },
//                    showGlobalOption = true
//                )
//                NotifDndRow(
//                    label = "遵守免打扰",
//                    mode = dnd,
//                    onModeChange = { dnd = it },
//                    showGlobalOption = true
//                )
//            }
//        },
//        confirmButton = {
//            Button(onClick = {
//                onSave(ConvNotifPrefs(sound, soundUri, vibration, priority, dnd))
//            }) { Text("保存") }
//        },
//        dismissButton = {
//            TextButton(onDismiss) { Text("取消") }
//        }
//    )
//}
//
//// ---------------------------------------------------------------------------
//// Reusable setting rows
//// ---------------------------------------------------------------------------
//
//@Composable
//private fun NotifSoundRow(
//    label: String,
//    mode: NotifSoundMode,
//    soundUri: String?,
//    onModeChange: (NotifSoundMode) -> Unit,
//    onPickRingtone: () -> Unit,
//    showGlobalOption: Boolean,
//) {
//    val options = if (showGlobalOption) NotifSoundMode.entries else NotifSoundMode.entries.filter { it != NotifSoundMode.GLOBAL }
//    DefaultColumn {
//        Row(
//            modifier = Modifier.fillMaxWidth(),
//            verticalAlignment = Alignment.CenterVertically,
//            horizontalArrangement = Arrangement.SpaceBetween
//        ) {
//            Text(label, modifier = Modifier.weight(1f))
//            SingleChoiceSegmentedButtonRow {
//                options.forEachIndexed { idx, opt ->
//                    SegmentedButton(
//                        selected = mode == opt,
//                        onClick = { onModeChange(opt) },
//                        shape = SegmentedButtonDefaults.itemShape(idx, options.size),
//                        label = { Text(opt.label, maxLines = 1) }
//                    )
//                }
//            }
//        }
//        if (mode == NotifSoundMode.CUSTOM) {
//            Row(
//                modifier = Modifier.fillMaxWidth(),
//                verticalAlignment = Alignment.CenterVertically,
//                horizontalArrangement = Arrangement.spacedBy(8.dp)
//            ) {
//                Text(
//                    text = if (soundUri != null) {
//                        runCatching {
//                            RingtoneManager.getRingtone(null, soundUri.toUri())?.getTitle(null)
//                                ?: soundUri
//                        }.getOrDefault(soundUri)
//                    } else "未选择",
//                    style = MaterialTheme.typography.bodySmall,
//                    color = MaterialTheme.colorScheme.onSurfaceVariant,
//                    modifier = Modifier.weight(1f)
//                )
//                Button(onClick = onPickRingtone) {
//                    Icon(MaterialSymbols.Outlined.Music_note, contentDescription = null, modifier = Modifier.size(16.dp))
//                    Spacer(Modifier.width(4.dp))
//                    Text("选择铃声")
//                }
//            }
//        }
//    }
//}
//
//@Composable
//private fun NotifVibrationRow(
//    label: String,
//    mode: NotifVibrationMode,
//    onModeChange: (NotifVibrationMode) -> Unit,
//    showGlobalOption: Boolean,
//) {
//    val options = if (showGlobalOption) NotifVibrationMode.entries else NotifVibrationMode.entries.filter { it != NotifVibrationMode.GLOBAL }
//    Row(
//        modifier = Modifier.fillMaxWidth(),
//        verticalAlignment = Alignment.CenterVertically,
//        horizontalArrangement = Arrangement.SpaceBetween
//    ) {
//        Text(label, modifier = Modifier.weight(1f))
//        SingleChoiceSegmentedButtonRow {
//            options.forEachIndexed { idx, opt ->
//                SegmentedButton(
//                    selected = mode == opt,
//                    onClick = { onModeChange(opt) },
//                    shape = SegmentedButtonDefaults.itemShape(idx, options.size),
//                    label = { Text(opt.label, maxLines = 1) }
//                )
//            }
//        }
//    }
//}
//
//@Composable
//private fun NotifPriorityRow(
//    label: String,
//    mode: NotifPriorityMode,
//    onModeChange: (NotifPriorityMode) -> Unit,
//    showGlobalOption: Boolean,
//) {
//    val options = if (showGlobalOption) NotifPriorityMode.entries else NotifPriorityMode.entries.filter { it != NotifPriorityMode.GLOBAL }
//    Row(
//        modifier = Modifier.fillMaxWidth(),
//        verticalAlignment = Alignment.CenterVertically,
//        horizontalArrangement = Arrangement.SpaceBetween
//    ) {
//        Text(label, modifier = Modifier.weight(1f))
//        SingleChoiceSegmentedButtonRow {
//            options.forEachIndexed { idx, opt ->
//                SegmentedButton(
//                    selected = mode == opt,
//                    onClick = { onModeChange(opt) },
//                    shape = SegmentedButtonDefaults.itemShape(idx, options.size),
//                    label = { Text(opt.label, maxLines = 1) }
//                )
//            }
//        }
//    }
//}
//
//@Composable
//private fun NotifDndRow(
//    label: String,
//    mode: NotifDndMode,
//    onModeChange: (NotifDndMode) -> Unit,
//    showGlobalOption: Boolean,
//) {
//    val options = if (showGlobalOption) NotifDndMode.entries else NotifDndMode.entries.filter { it != NotifDndMode.GLOBAL }
//    Row(
//        modifier = Modifier.fillMaxWidth(),
//        verticalAlignment = Alignment.CenterVertically,
//        horizontalArrangement = Arrangement.SpaceBetween
//    ) {
//        Text(label, modifier = Modifier.weight(1f))
//        SingleChoiceSegmentedButtonRow {
//            options.forEachIndexed { idx, opt ->
//                SegmentedButton(
//                    selected = mode == opt,
//                    onClick = { onModeChange(opt) },
//                    shape = SegmentedButtonDefaults.itemShape(idx, options.size),
//                    label = { Text(opt.label, maxLines = 1) }
//                )
//            }
//        }
//    }
//}
//
//// ---------------------------------------------------------------------------
//// Data model
//// ---------------------------------------------------------------------------
//
//enum class NotifSoundMode(val label: String) {
//    GLOBAL("跟随全局"),
//    SILENT("无声"), CUSTOM("自定义铃声"),
//}
//
//enum class NotifVibrationMode(val label: String) {
//    GLOBAL("跟随全局"),
//    SHORT("短"),
//    LONG("长"),
//    DISABLED("禁用"),
//}
//
//enum class NotifPriorityMode(val label: String) {
//    GLOBAL("跟随全局"),
//    LOW("低"),
//    MEDIUM("中"),
//    HIGH("高"),
//    URGENT("紧急"),
//}
//
///**遵守免打扰: whether to respect WeChat's per-conversation mute flag (isDnd). */
//enum class NotifDndMode(val label: String) {
//    GLOBAL("跟随全局"),
//
//    /** Ignore WeChat's mute flag: always treat this conversation as un-muted. */
//    IGNORE("关"),
//
//    /** Obey WeChat's mute flag: show a silent/low notification if isDnd=true. */
//    OBEY("开"),
//}
//
///**
// * Per-conversation override prefs.GLOBAL for any field means "fall through to the global setting".
// */
//data class ConvNotifPrefs(
//    val sound: NotifSoundMode = NotifSoundMode.GLOBAL,
//    val soundUri: String? = null,
//    val vibration: NotifVibrationMode = NotifVibrationMode.GLOBAL,
//    val priority: NotifPriorityMode = NotifPriorityMode.GLOBAL,
//    val dnd: NotifDndMode = NotifDndMode.GLOBAL,
//) : Serializable {
//    val isAllGlobal: Boolean
//        get() = sound == NotifSoundMode.GLOBAL && vibration == NotifVibrationMode.GLOBAL
//                && priority == NotifPriorityMode.GLOBAL
//                && dnd == NotifDndMode.GLOBAL
//}
