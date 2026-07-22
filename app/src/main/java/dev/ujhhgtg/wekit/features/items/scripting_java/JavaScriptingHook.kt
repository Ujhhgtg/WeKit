package dev.ujhhgtg.wekit.features.items.scripting_java

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bsh.Interpreter
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.chat.ChatInputBarEnhancements
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlAttr
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

@Feature(name = "脚本引擎 (Java)", categories = ["脚本 (Java)"], description = "执行 Java 脚本")
object JavaScriptingHook : ClickableFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener, WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "JavaScriptingHook"
    private const val DISABLED_FLAG = "disabled.flag"

    private val SCRIPTS_DIR = (KnownPaths.moduleData / "scripts_java").createDirsSafe()

    val scripts = ConcurrentHashMap<String, JavaPlugin>()

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
        WeDatabaseListenerApi.addListener(this)

        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookAfter {
            val msgObj = args[0] ?: return@hookAfter
            val msgBean = MsgInfoBean(msgObj)
            JavaEngine.executeAllOnHandleMsg(scripts, msgBean)
        }

        ChatInputBarEnhancements.methodSendMessage.hookBefore {
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

        CoroutineScope(Dispatchers.IO).launch {
            WeLogger.d(TAG, "loading java scripts...")
            for (scriptDir in SCRIPTS_DIR.listDirectoryEntries().filter { it.isDirectory() }) {
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
                scripts[dirName] = plugin
            }

            JavaEngine.executeAllOnLoad(scripts)
        }
    }

    override fun onClick(context: ComponentActivity) {
        val entries = listScriptEntries()
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("Java 脚本") },
                text = {
                    if (entries.isEmpty()) {
                        Text("暂无脚本")
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                        ) {
                            items(entries, key = { it.dir.name }) { entry ->
                                var enabled by remember(entry.dir) { mutableStateOf(entry.enabled) }
                                fun toggle() {
                                    val newState = !enabled
                                    if (setScriptEnabled(entry.dir, newState)) {
                                        enabled = newState
                                    }
                                }

                                ListItem(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { toggle() },
                                    headlineContent = { Text(entry.info.name) },
                                    supportingContent = {
                                        Text(
                                            buildList {
                                                add(entry.dir.name)
                                                add(if (enabled) "已启用" else "已禁用")
                                                entry.info.version?.let { add("版本 $it") }
                                                entry.info.author?.let { add("作者 $it") }
                                            }.joinToString(" · ")
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = enabled,
                                            onCheckedChange = null,
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("完成") }
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
        WeDatabaseListenerApi.removeListener(this)
        JavaHookApi.unhookEverything()
        JavaEngine.executeAllOnUnload(scripts)
        scripts.clear()
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table == "fmessage_msginfo") {
            val isSend = values.getAsInteger("isSend") ?: 0
            if (isSend == 0) {
                val msgContent = values.getAsString("msgContent") ?: ""
                val fromusername = extractXmlAttr(msgContent, "fromusername").takeIf { it.isNotEmpty() }
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
