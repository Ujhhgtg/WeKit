package dev.ujhhgtg.wekit.features.items.scripting_java

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import bsh.Interpreter
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.extensions.ExtensionPackDialogs
import dev.ujhhgtg.wekit.extensions.ScriptDepsPack
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlAttr
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import me.hd.wauxv.data.bean.MsgInfoBean
import me.hd.wauxv.data.bean.PayMsgBean
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

object JavaScriptingHook : ClickableFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener, WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "脚本引擎 (Java)"
    override val nameRes = R.string.feature_java_scripting_hook_name
    override val categoryIds = listOf(FeatureCategoryIds.SCRIPTING_JAVA)
    override val descriptionRes = R.string.feature_java_scripting_hook_description

    private const val TAG = "JavaScriptingHook"
    private const val DISABLED_FLAG = "disabled.flag"

    private val SCRIPTS_DIR by lazy { (KnownPaths.moduleData / "scripts_java").createDirsSafe() }

    val scripts = ConcurrentHashMap<String, JavaPlugin>()
    private val lifecycleLock = Any()
    private var loadJob: Job? = null
    private var lifecycleGeneration = 0L

    private data class ScriptEntry(
        val dir: Path,
        val info: JavaPluginInfo,
        val enabled: Boolean,
    )

    private val methodPayMsg by dexMethod {
        matcher {
            usingEqStrings("[onRecv PayerMsg]，newMsg.msgType：%s")
        }
    }

    override fun onEnable() {
        val generation = synchronized(lifecycleLock) {
            lifecycleGeneration += 1
            lifecycleGeneration
        }
        WeDatabaseListenerApi.addListener(this)

        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookAfter {
            val msgObj = args[0] ?: return@hookAfter
            val msgBean = MsgInfoBean(msgObj)
            JavaEngine.executeAllOnHandleMsg(scripts, msgBean)
        }

        WeChatInputBarMenuApi.methodSendMessage.hookBefore {
            val chatFooter = thisObject!!.reflekt().firstField {
                type = ChatFooter::class
            }.get()!! as ChatFooter
            val text = chatFooter.lastText
            JavaEngine.executeAllOnClickSendBtn(scripts, this, text)
        }

        methodPayMsg.hookBefore {
            val g2Var = args[0] ?: return@hookBefore
            val payMsgBean = PayMsgBean(g2Var)
            JavaEngine.executeAllOnRecvPayMsg(scripts, payMsgBean)
        }

        loadJob = CoroutineScope(Dispatchers.IO).launch {
            WeLogger.d(TAG, "loading java scripts...")
            val loadedScripts = ConcurrentHashMap<String, JavaPlugin>()
            for (scriptDir in SCRIPTS_DIR.listDirectoryEntries().filter { it.isDirectory() }) {
                currentCoroutineContext().ensureActive()
                val dirName = scriptDir.name
                if (!isScriptEnabled(scriptDir)) {
                    WeLogger.d(TAG, "skipping '$dirName': disabled")
                    continue
                }

                val mainFile = scriptDir / "main.java"
                val infoFile = scriptDir / "info.prop"
                if (!mainFile.exists() || !infoFile.exists()) {
                    WeLogger.w(TAG, "skipping '$dirName': missing main.java or info.prop")
                    continue
                }

                val content = runCatching { mainFile.readText() }.getOrElse { continue }
                val infoPropContent = runCatching { infoFile.readText() }.getOrElse { continue }
                val info = JavaPlugin.parseInfoProp(infoPropContent)
                WeLogger.d(TAG, "loaded script, name='${info.name}', length=${content.length}")

                val plugin = JavaPlugin(
                    name = dirName,
                    dir = scriptDir,
                    info = info,
                    content = content,
                    interpreter = Interpreter(null, "")
                )
                loadedScripts[dirName] = plugin
            }

            currentCoroutineContext().ensureActive()
            synchronized(lifecycleLock) {
                check(lifecycleGeneration == generation)
                scripts.clear()
                scripts.putAll(loadedScripts)
                JavaEngine.executeAllOnLoad(scripts)
            }
        }

        if (!ScriptDepsPack.isInstalled()) {
            getTopMostActivity(allowPaused = true)?.let { activity ->
                ExtensionPackDialogs.suggestInstall(activity, ScriptDepsPack)
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        val entries = listScriptEntries()
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.java_scripts_dialog_title)) },
                text = {
                    if (entries.isEmpty()) {
                        Text(stringResource(R.string.java_scripts_empty))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                        ) {
                            lazySegmentedItems(entries, key = { it.dir.name }) { entry ->
                                var enabled by remember(entry.dir) { mutableStateOf(entry.enabled) }
                                val statusText = stringResource(
                                    if (enabled) R.string.java_script_status_enabled
                                    else R.string.java_script_status_disabled
                                )
                                val versionText =
                                    entry.info.version?.let { stringResource(R.string.java_script_version, it) }
                                val authorText =
                                    entry.info.author?.let { stringResource(R.string.java_script_author, it) }

                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = if (entry.info.name == "unnamed") {
                                        stringResource(R.string.java_script_unnamed)
                                    } else {
                                        entry.info.name
                                    },
                                    description = buildList {
                                        add(entry.dir.name)
                                        add(statusText)
                                        versionText?.let { add(it) }
                                        authorText?.let { add(it) }
                                    }.joinToString(" · "),
                                    checked = enabled,
                                    onCheckedChange = { newState ->
                                        if (setScriptEnabled(entry.dir, newState)) {
                                            enabled = newState
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                },
            )
        }
    }

    private fun listScriptEntries(): List<ScriptEntry> =
        SCRIPTS_DIR.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .mapNotNull { scriptDir ->
                val mainFile = scriptDir / "main.java"
                val infoFile = scriptDir / "info.prop"
                if (!mainFile.exists() || !infoFile.exists()) return@mapNotNull null

                val info = runCatching {
                    JavaPlugin.parseInfoProp(infoFile.readText())
                }.getOrNull() ?: return@mapNotNull null
                ScriptEntry(
                    dir = scriptDir,
                    info = info,
                    enabled = isScriptEnabled(scriptDir),
                )
            }

    private fun isScriptEnabled(scriptDir: Path): Boolean =
        !(scriptDir / DISABLED_FLAG).exists()

    private fun setScriptEnabled(scriptDir: Path, enabled: Boolean): Boolean = runCatching {
        val disabledFlag = scriptDir / DISABLED_FLAG
        if (enabled) {
            disabledFlag.deleteIfExists()
        } else {
            disabledFlag.writeText("")
        }
        true
    }.onFailure {
        WeLogger.w(TAG, "failed to ${if (enabled) "enable" else "disable"} script '${scriptDir.name}'", it)
    }.getOrDefault(false)

    override fun onDisable() {
        synchronized(lifecycleLock) {
            lifecycleGeneration += 1
        }
        loadJob?.cancel()
        loadJob = null
        WeDatabaseListenerApi.removeListener(this)
        JavaHookApi.unhookEverything()
        synchronized(lifecycleLock) {
            JavaEngine.executeAllOnUnload(scripts)
            scripts.clear()
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table == "fmessage_msginfo") {
            val isSend = values.getAsInteger("isSend") ?: 0
            if (isSend == 0) {
                val msgContent = values.getAsString("msgContent") ?: ""
                val fromusername = extractXmlAttr(msgContent, "encryptusername").takeIf { it.isNotEmpty() }
                    ?: extractXmlAttr(msgContent, "fromusername").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "fromusername")
                val ticket = extractXmlAttr(msgContent, "ticket").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "ticket")
                val sceneStr = extractXmlAttr(msgContent, "scene").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "scene")
                val scene = sceneStr.toIntOrNull() ?: 0

                JavaEngine.executeAllOnNewFriend(scripts, fromusername, ticket, scene)
            }
        }
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "chatroom") return
        val chatroomName = values.getAsString("chatroomname") ?: return
        val memberCount = values.getAsInteger("memberCount") ?: return
        val memberlist = values.getAsString("memberlist") ?: return
        if (memberlist.isBlank()) return

        val cursor = WeDatabaseApi.rawQuery(
            "SELECT memberlist, memberCount FROM chatroom WHERE chatroomname = ?",
            arrayOf(chatroomName)
        )
        if (cursor.moveToFirst()) {
            val oldMemberCount = cursor.getInt(cursor.getColumnIndexOrThrow("memberCount"))
            val oldMemberListStr = cursor.getString(cursor.getColumnIndexOrThrow("memberlist"))
            cursor.close()

            if (oldMemberCount == 0 || oldMemberListStr.isNullOrBlank()) return

            val oldMembers = oldMemberListStr.split(";").filter { it.isNotBlank() }.toSet()
            val newMembers = memberlist.split(";").filter { it.isNotBlank() }.toSet()

            if (memberCount > oldMemberCount) {
                val joined = newMembers - oldMembers
                joined.forEach { userWxid ->
                    val nickname = WeDatabaseApi.getDisplayName(userWxid)
                    JavaEngine.executeAllOnMemberChange(scripts, "join", chatroomName, userWxid, nickname)
                }
            } else if (memberCount < oldMemberCount) {
                val left = oldMembers - newMembers
                left.forEach { userWxid ->
                    val nickname = WeDatabaseApi.getDisplayName(userWxid)
                    JavaEngine.executeAllOnMemberChange(scripts, "left", chatroomName, userWxid, nickname)
                }
            }
        }
    }
}
