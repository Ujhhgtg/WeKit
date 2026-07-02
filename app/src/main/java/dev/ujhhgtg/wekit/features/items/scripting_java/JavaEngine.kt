package dev.ujhhgtg.wekit.features.items.scripting_java

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import bsh.BshMethod
import bsh.NameSpace
import dalvik.system.InMemoryDexClassLoader
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeAuthApi
import dev.ujhhgtg.wekit.features.api.core.WeContactApi
import dev.ujhhgtg.wekit.features.api.core.WeContactLabelApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeGroupApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WePaymentApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.BshSnapshotDecompiler
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.DexKit
import dev.ujhhgtg.wekit.utils.reflection.asClass
import dev.ujhhgtg.wekit.utils.reflection.asConstructor
import dev.ujhhgtg.wekit.utils.reflection.asMethod
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.float
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.reflection.long
import me.hd.wauxv.data.bean.ContactLabelBean
import me.hd.wauxv.data.bean.MsgInfoBean
import me.hd.wauxv.data.bean.info.FriendInfo
import me.hd.wauxv.data.bean.info.GroupInfo
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.lang.reflect.Member
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Properties
import java.util.function.Consumer
import java.util.function.Function
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension

object JavaEngine {

    private val TAG = This.Class.simpleName
    private const val WA_MODULE_VER = 1418
    private const val WA_API = 127

    fun executeAllOnLoad(scripts: Map<String, JavaPlugin>) {
        scripts.values.forEach { plugin ->
            try {
                initPlugin(plugin)
                plugin.interpreter.eval(plugin.content)

                val bshMethod = plugin.interpreter.nameSpace.getMethod("onLoad", emptyArray())
                bshMethod?.apply {
                    invoke(arrayOf(), plugin.interpreter)
                    WeLogger.i(TAG, "onLoad executed for script ${plugin.name}")
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "onLoad execution failed for script ${plugin.name}", e)
            }
        }
    }

    fun executeAllOnUnload(scripts: Map<String, JavaPlugin>) {
        scripts.values.forEach { plugin ->
            try {
                val bshMethod = plugin.interpreter.nameSpace.getMethod("onUnload", emptyArray())
                bshMethod?.apply {
                    invoke(emptyArray(), plugin.interpreter)
                    WeLogger.i(TAG, "onUnload executed for script ${plugin.name}")
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "onUnload execution failed for script ${plugin.name}", e)
            }
        }
    }

    fun executeAllOnHandleMsg(scripts: Map<String, JavaPlugin>,
                              msgBean: MsgInfoBean) {
        scripts.values.forEach { plugin ->
            try {
                val bshMethod = plugin.interpreter.nameSpace.getMethod(
                    "onHandleMsg",
                    arrayOf(MsgInfoBean::class.java)
                )
                bshMethod?.apply {
                    invoke(arrayOf(msgBean), plugin.interpreter)
                    WeLogger.i(TAG, "onHandleMsg executed for script ${plugin.name}")
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "onHandleMsg execution failed for script ${plugin.name}", e)
            }
        }
    }

    fun executeAllOnClickSendBtn(scripts: Map<String, JavaPlugin>,
                                 param: XC_MethodHook.MethodHookParam,
                                 text: String) {
        scripts.values.forEach { plugin ->
            try {
                val bshMethod = plugin.interpreter.nameSpace.getMethod(
                    "onClickSendBtn",
                    arrayOf(BString)
                )
                bshMethod?.apply {
                    val ifIntercept = invoke(arrayOf(text), plugin.interpreter)
                    WeLogger.i(TAG, "onClickSendBtn executed for script ${plugin.name}; ifIntercept=${ifIntercept}")
                    if (ifIntercept == true) {
                        param.result = null
                    }
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "onClickSendBtn execution failed for script ${plugin.name}", e)
            }
        }
    }

    fun executeAllOnMemberChange(scripts: Map<String, JavaPlugin>,
                                 type: String,
                                 groupWxid: String,
                                 userWxid: String,
                                 userName: String
    ) {
        scripts.values.forEach { plugin ->
            try {
                val bshMethod = plugin.interpreter.nameSpace.getMethod(
                    "onMemberChange",
                    arrayOf(String::class.java, String::class.java, String::class.java, String::class.java)
                )
                bshMethod?.apply {
                    invoke(arrayOf(type, groupWxid, userWxid, userName), plugin.interpreter)
                    WeLogger.i(TAG, "onMemberChange executed for script ${plugin.name}")
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "onMemberChange execution failed for script ${plugin.name}", e)
            }
        }
    }

    fun executeAllOnNewFriend(
        scripts: Map<String, JavaPlugin>,
        wxid: String,
        ticket: String,
        scene: Int
    ) {
        scripts.values.forEach { plugin ->
            try {
                val bshMethod = plugin.interpreter.nameSpace.getMethod(
                    "onNewFriend",
                    arrayOf(String::class.java, String::class.java, Integer.TYPE)
                )
                bshMethod?.apply {
                    invoke(arrayOf<Any>(wxid, ticket, scene), plugin.interpreter)
                    WeLogger.i(TAG, "onNewFriend executed for script ${plugin.name}")
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "onNewFriend execution failed for script ${plugin.name}", e)
            }
        }
    }

    fun executeAllOnRecvPayMsg(
        scripts: Map<String, JavaPlugin>,
        payMsgBean: me.hd.wauxv.data.bean.PayMsgBean
    ) {
        scripts.values.forEach { plugin ->
            try {
                val bshMethod = plugin.interpreter.nameSpace.getMethod(
                    "onRecvPayMsg",
                    arrayOf(Any::class.java)
                )
                bshMethod?.apply {
                    invoke(arrayOf(payMsgBean), plugin.interpreter)
                    WeLogger.i(TAG, "onRecvPayMsg executed for script ${plugin.name}")
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "onRecvPayMsg execution failed for script ${plugin.name}", e)
            }
        }
    }

    fun initPlugin(plugin: JavaPlugin) {
        val interpreter = plugin.interpreter

        val classManager = interpreter.classManager
        classManager.setClassLoader(ClassLoaders.HYBRID)

        val nameSpace = interpreter.nameSpace
        initNameSpace(nameSpace, plugin)
    }

    fun initNameSpace(nameSpace: NameSpace, plugin: JavaPlugin) {
        nameSpace.apply {
            // ===== Host Info =====

            setVariable("hostContext", HostInfo.application)
            setVariable("hostVerName", HostInfo.versionName)
            setVariable("hostVerCode", HostInfo.versionCode.toInt())
            setVariable("hostVerClient", com.tencent.mm.boot.BuildConfig.CLIENT_VERSION_ARM64)

            // ===== Compat Info =====

            setVariable("moduleVer", WA_MODULE_VER)

            // ===== FileSystem Info =====

            setVariable("cacheDir", KnownPaths.moduleCache.absolutePathString())

            // ===== Plugin Info =====

            setVariable("pluginDir", plugin.dir.toFile())
            setVariable("pluginId", plugin.name)
            setVariable("pluginName", plugin.info.name)
            setVariable("pluginAuthor", plugin.info.author)
            setVariable("pluginVersion", plugin.info.version)
            setVariable("pluginUpdateTime", plugin.info.updateTime)

            // ===== Engine Info =====

            setVariable("engineId", BuildConfig.TAG)
            setVariable("engineVerCode", BuildConfig.VERSION_CODE)
            setVariable("engineVerName", BuildConfig.VERSION_NAME)
            setVariable("engineSupportedLatestApi", WA_API)

            // ===== Audio Utils =====

            setMethod(BshMethod(
                "mp3ToSilk", arrayOf(BString, BString, int)
            ) {
                AudioUtils.mp3ToSilk(it[0] as String, it[1] as String)
            })
            setMethod(BshMethod(
                "mp3ToSilk", arrayOf(BString, BString)
            ) {
                AudioUtils.mp3ToSilk(it[0] as String, it[1] as String)
            })
            setMethod(BshMethod(
                "silkToMp3", arrayOf(BString, BString, int)
            ) {
                val pcm = it[1] as String + ".tmp"
                AudioUtils.silkToPcm(it[0] as String, pcm)
                AudioUtils.pcmToMp3(pcm, it[1] as String)
                runCatching { pcm.asPath.deleteExisting() }
            })
            setMethod(BshMethod(
                "silkToMp3", arrayOf(BString, BString)
            ) {
                val pcm = it[1] as String + ".tmp"
                AudioUtils.silkToPcm(it[0] as String, pcm)
                AudioUtils.pcmToMp3(pcm, it[1] as String)
                runCatching { pcm.asPath.deleteExisting() }
            })
            setMethod(BshMethod(
                "getDuration", arrayOf(BString)
            ) { return@BshMethod AudioUtils.getDurationMs(it[0] as String) })

            // ===== Config: Properties-based persistent storage =====

            // getString(key, default) — already ported, kept for compatibility
            setMethod(BshMethod(
                "getString", arrayOf(BString, BString)
            ) {
                val key = it[0] as String
                val def = it[1] as String
                return@BshMethod loadConfig(plugin).getProperty(key) ?: def
            })

            // putString(key, value)
            setMethod(BshMethod(
                "putString", arrayOf(BString, BString)
            ) {
                val key = it[0] as String
                val value = it[1] as String
                val props = loadConfig(plugin)
                props.setProperty(key, value)
                saveConfig(plugin, props)
            })

            // getStringSet(key, defaultSet)
            setMethod(BshMethod(
                "getStringSet", arrayOf(BString, Set::class.java)
            ) {
                val key = it[0] as String
                val def = it[1] as Set<*>
                val raw = loadConfig(plugin).getProperty(key) ?: return@BshMethod def
                return@BshMethod try {
                    val arr = JSONArray(raw)
                    val result = LinkedHashSet<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        result.add(arr.optString(i))
                    }
                    result
                } catch (_: Exception) {
                    def
                }
            })

            // putStringSet(key, set)
            setMethod(BshMethod(
                "putStringSet", arrayOf(BString, Set::class.java)
            ) {
                val key = it[0] as String
                val value = it[1] as Set<*>
                val props = loadConfig(plugin)
                props.setProperty(key, JSONArray(value.toList()).toString())
                saveConfig(plugin, props)
            })

            // getBoolean(key, default)
            setMethod(BshMethod(
                "getBoolean", arrayOf(BString, java.lang.Boolean.TYPE)
            ) {
                val key = it[0] as String
                val def = it[1] as Boolean
                val raw = loadConfig(plugin).getProperty(key)
                if (raw != null) {
                    when (raw) {
                        "true" -> return@BshMethod true
                        "false" -> return@BshMethod false
                    }
                }
                return@BshMethod def
            })

            // putBoolean(key, value)
            setMethod(BshMethod(
                "putBoolean", arrayOf(BString, java.lang.Boolean.TYPE)
            ) {
                val key = it[0] as String
                val value = it[1] as Boolean
                val props = loadConfig(plugin)
                props.setProperty(key, value.toString())
                saveConfig(plugin, props)
            })

            // getInt(key, default)
            setMethod(BshMethod(
                "getInt", arrayOf(BString, int)
            ) {
                val key = it[0] as String
                val def = it[1] as Int
                val raw = loadConfig(plugin).getProperty(key)
                if (raw != null) {
                    raw.toIntOrNull()?.let { value -> return@BshMethod value }
                }
                return@BshMethod def
            })

            // putInt(key, value)
            setMethod(BshMethod(
                "putInt", arrayOf(BString, int)
            ) {
                val key = it[0] as String
                val value = it[1] as Int
                val props = loadConfig(plugin)
                props.setProperty(key, value.toString())
                saveConfig(plugin, props)
            })

            // getFloat(key, default)
            setMethod(BshMethod(
                "getFloat", arrayOf(BString, float)
            ) {
                val key = it[0] as String
                val def = it[1] as Float
                val raw = loadConfig(plugin).getProperty(key)
                if (raw != null) {
                    raw.toFloatOrNull()?.let { value -> return@BshMethod value }
                }
                return@BshMethod def
            })

            // putFloat(key, value)
            setMethod(BshMethod(
                "putFloat", arrayOf(BString, float)
            ) {
                val key = it[0] as String
                val value = it[1] as Float
                val props = loadConfig(plugin)
                props.setProperty(key, value.toString())
                saveConfig(plugin, props)
            })

            // getLong(key, default)
            setMethod(BshMethod(
                "getLong", arrayOf(BString, long)
            ) { args ->
                val key = args[0] as String
                val def = args[1] as Long
                val raw = loadConfig(plugin).getProperty(key)
                if (raw != null) {
                    raw.toLongOrNull()?.let { return@BshMethod it }
                }
                return@BshMethod def
            })

            // putLong(key, value)
            setMethod(BshMethod(
                "putLong", arrayOf(BString, long)
            ) {
                val key = it[0] as String
                val value = it[1] as Long
                val props = loadConfig(plugin)
                props.setProperty(key, value.toString())
                saveConfig(plugin, props)
            })

            // ===== Logging =====

            // log(message) — logs via WeLogger with plugin prefix
            setMethod(BshMethod(
                "log", arrayOf(Any::class.java)
            ) {
                val message = it[0]
                WeLogger.i(plugin.name, message.toString())
            })

            // ===== Toast =====

            // toast(message) — shows a short Android Toast
            setMethod(BshMethod(
                "toast", arrayOf(BString)
            ) {
                Handler(Looper.getMainLooper()).post {
                    val message = it[0] as String
                    showToast("${plugin.name}: $message")
                }
            })

            // ===== Notification =====

            // notify(title, content) — posts a system notification
            setMethod(BshMethod(
                "notify", arrayOf(BString, BString)
            ) {
                val title = it[0] as String
                val content = it[1] as String
                val context = HostInfo.application
                val nm = context.getSystemService<NotificationManager>()
                val channelId = "script_${plugin.name}"
                val channel = NotificationChannel(
                    channelId,
                    "Script: ${plugin.info.name}",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
                val notification = android.app.Notification.Builder(context, channelId)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .build()
                nm.notify(channelId.hashCode(), notification)
            })

            // ===== Scripting =====

            // eval(source) — evaluates a BeanShell expression/script
            setMethod(BshMethod(
                "eval", arrayOf(BString)
            ) {
                val source = it[0] as String
                return@BshMethod plugin.interpreter.eval(source)
            })

            // loadJava(path) — sources a Java file into the interpreter
            setMethod(BshMethod(
                "loadJava", arrayOf(BString)
            ) {
                val path = plugin.dir / it[0] as String
                val absPath = if (path.exists()) {
                    path.absolutePathString()
                } else {
                    val withExt = path.parent / "${path.nameWithoutExtension}.java"
                    if (withExt.exists()) {
                        withExt.absolutePathString()
                    } else {
                        WeLogger.e(TAG, "failed to load java; ${path.absolutePathString()} and ${withExt.absolutePathString()} does not exist")
                        return@BshMethod null
                    }
                }
                plugin.interpreter.source(absPath)
            })

            // loadJar(path) — adds a JAR to the interpreter's classloader
            setMethod(BshMethod(
                "loadJar", arrayOf(BString)
            ) {
                val path = it[0] as String
                val resolved = if (File(path).isAbsolute) {
                    File(path).canonicalPath
                } else {
                    plugin.dir.resolve(path).toFile().canonicalPath
                }
                val url = File(resolved).toURI().toURL()
                val loader = java.net.URLClassLoader(arrayOf(url), ClassLoaders.MODULE)
                plugin.interpreter.classManager.addClassLoader(loader)
            })

            // loadDex(path) — loads a DEX into the interpreter's classloader
            setMethod(BshMethod(
                "loadDex", arrayOf(BString)
            ) {
                val path = it[0] as String
                val resolved = if (File(path).isAbsolute) {
                    File(path).canonicalPath
                } else {
                    plugin.dir.resolve(path).toFile().canonicalPath
                }
                val dexBytes = Files.readAllBytes(File(resolved).toPath())
                val loader = InMemoryDexClassLoader(
                    ByteBuffer.wrap(dexBytes), ClassLoaders.MODULE
                )
                plugin.interpreter.classManager.addClassLoader(loader)
            })

            // compileSnapshot(path) — compiles a BeanShell script to a .bshs snapshot
            setMethod(BshMethod(
                "compileSnapshot", arrayOf(BString)
            ) {
                val path = it[0] as String
                val resolved = if (File(path).isAbsolute) {
                    File(path).canonicalPath
                } else {
                    plugin.dir.resolve(path).toFile().canonicalPath
                }
                val snapPath = "$resolved.bshs"
                runCatching {
                    plugin.interpreter.compileSnapshot(resolved, snapPath, null)
                }.onFailure { e ->
                    WeLogger.e(TAG, "compileSnapshot failed for $resolved", e)
                }
            })

            // evalSnapshot(path) — evaluates a compiled .bshs snapshot
            setMethod(BshMethod(
                "evalSnapshot", arrayOf(BString)
            ) {
                val path = it[0] as String
                val resolved = if (File(path).isAbsolute) {
                    File(path).canonicalPath
                } else {
                    plugin.dir.resolve(path).toFile().canonicalPath
                }
                val snapFile = File("$resolved.bshs")
                if (!snapFile.exists()) {
                    WeLogger.w(TAG, "snapshot not found: ${snapFile.canonicalPath}")
                    return@BshMethod null
                }
                runCatching {
                    plugin.interpreter.evalSnapshot(snapFile.absolutePath, BshSnapshotDecompiler.SECRET_KEY)
                }.onFailure { e ->
                    WeLogger.e(TAG, "evalSnapshot failed for $resolved", e)
                }.getOrNull()
            })

            // ===== WeChat Identity =====

            // getTargetTalker() → current chat partner wxid
            // WAuxv original: hooks ChatFooter.setUserName | WeKit: same approach via XposedBridge
            setMethod(BshMethod(
                "getTargetTalker", emptyArray<Class<*>>()
            ) {
                return@BshMethod WeCurrentConversationApi.value
            })

            // setTargetTalker(wxId) → manually set current talker
            setMethod(BshMethod(
                "setTargetTalker", arrayOf(BString)
            ) {
                WeCurrentConversationApi.value = it[0] as String
            })

            // getLoginWxid() → current logged-in wxid
            setMethod(BshMethod(
                "getLoginWxid", emptyArray<Class<*>>()
            ) {
                return@BshMethod runCatchingBsh("getLoginWxid") { WeApi.selfWxId }.getOrDefault("")
            })

            // getLoginAlias() → current logged-in custom alias
            setMethod(BshMethod(
                "getLoginAlias", emptyArray<Class<*>>()
            ) {
                return@BshMethod runCatchingBsh("getLoginAlias") { WeApi.selfCustomWxId }.getOrDefault("")
            })

            // ===== Contacts — Lists =====

            // getFriendList() → list of FriendInfo objects
            setMethod(BshMethod(
                "getFriendList", emptyArray<Class<*>>()
            ) {
                return@BshMethod runCatchingBsh("getFriendList") {
                    WeDatabaseApi.getFriends().map { FriendInfo(it) }
                }.getOrDefault(emptyList<Any>())
            })

            // getGroupList() → list of GroupInfo objects
            setMethod(BshMethod(
                "getGroupList", emptyArray<Class<*>>()
            ) {
                return@BshMethod runCatchingBsh("getGroupList") {
                    WeDatabaseApi.getGroups().map { GroupInfo(it) }
                }.getOrDefault(emptyList<Any>())
            })

            // getOfficialList() → list of FriendInfo objects
            setMethod(BshMethod(
                "getOfficialList", emptyArray<Class<*>>()
            ) {
                return@BshMethod runCatchingBsh("getOfficialList") {
                    WeDatabaseApi.getOfficialAccounts().map {
                        FriendInfo(it.wxId, "", "", it.nickname, 0, "", 0L)
                    }
                }.getOrDefault(emptyList<Any>())
            })

            // getGroupMemberList(groupId) → list of member wxid strings
            // WAuxv original: returns list of member objects | WeKit: returns List<WeContact>; choose: return wxId strings for simpler scripting
            setMethod(BshMethod(
                "getGroupMemberList", arrayOf(BString)
            ) {
                val groupId = it[0] as String
                return@BshMethod runCatchingBsh("getGroupMemberList") {
                    WeDatabaseApi.getGroupMembers(groupId).map { m -> m.wxId }
                }.getOrDefault(emptyList<Any>())
            })

            // ===== Contact Detail =====

            // getFriendNickName(wxId) → contact's nickname
            setMethod(BshMethod(
                "getFriendNickName", arrayOf(BString)
            ) {
                val wxId = it[0] as String
                return@BshMethod runCatchingBsh("getFriendNickName") {
                    WeDatabaseApi.getFriend(wxId)?.nickname ?: ""
                }.getOrDefault("")
            })

            // getFriendRemarkName(wxId) → contact's remark name
            setMethod(BshMethod(
                "getFriendRemarkName", arrayOf(BString)
            ) {
                val wxId = it[0] as String
                return@BshMethod runCatchingBsh("getFriendRemarkName") {
                    WeDatabaseApi.getFriend(wxId)?.remarkName ?: ""
                }.getOrDefault("")
            })

            // getFriendName(wxId) → display name for a user (single param)
            setMethod(BshMethod(
                "getFriendName", arrayOf(BString)
            ) {
                val wxId = it[0] as String
                return@BshMethod runCatchingBsh("getFriendName") { WeDatabaseApi.getDisplayName(wxId) }.getOrDefault(wxId)
            })

            // getFriendName(wxId, groupId) → display name within a group
            setMethod(BshMethod(
                "getFriendName", arrayOf(BString, BString)
            ) {
                val wxId = it[0] as String
                val groupId = it[1] as String
                return@BshMethod runCatchingBsh("getFriendName") {
                    WeDatabaseApi.getGroupMemberDisplayName(groupId, wxId)
                }.getOrDefault(wxId)
            })

            // getFriendDisplayName(groupId, memberId) → display name within a group
            setMethod(BshMethod(
                "getFriendDisplayName", arrayOf(BString, BString)
            ) {
                val groupId = it[0] as String
                val memberId = it[1] as String
                return@BshMethod runCatchingBsh("getFriendDisplayName") {
                    WeDatabaseApi.getGroupMemberDisplayName(groupId, memberId)
                }.getOrDefault(memberId)
            })

            // getAvatarUrl(wxId) → contact's avatar CDN URL
            setMethod(BshMethod(
                "getAvatarUrl", arrayOf(BString)
            ) {
                val wxId = it[0] as String
                return@BshMethod runCatchingBsh("getAvatarUrl") { WeDatabaseApi.getAvatarUrl(wxId) }.getOrDefault("")
            })

            // getAvatarUrl(wxId, big) → 'big' param not supported by WeKit; defaults to same URL
            setMethod(BshMethod(
                "getAvatarUrl", arrayOf(BString, java.lang.Boolean.TYPE)
            ) {
                val wxId = it[0] as String
                return@BshMethod runCatchingBsh("getAvatarUrl") { WeDatabaseApi.getAvatarUrl(wxId) }.getOrDefault("")
            })

            // getDisplayName(convId) → conversation display name (remark > nickname > convId)
            setMethod(BshMethod(
                "getDisplayName", arrayOf(BString)
            ) {
                val convId = it[0] as String
                return@BshMethod runCatchingBsh("getDisplayName") { WeDatabaseApi.getDisplayName(convId) }.getOrDefault(convId)
            })

            // ===== Messaging =====
            // WAuxv original: uses NetSceneSendMsg directly via C0452.m1780
            // WeKit: uses methodGetSendMsgObject + methodPostToQueue (same NetSceneQueue, different entry); equivalent behavior

            // sendText(toUser, text) → Boolean
            setMethod(BshMethod(
                "sendText", arrayOf(BString, BString)
            ) {
                val toUser = it[0] as String
                val text = it[1] as String
                return@BshMethod runCatchingBsh("sendText") {
                    WeMessageApi.sendText(toUser, text)
                }.getOrDefault(false)
            })

            // sendText(toUser, text, callback)
            setMethod(BshMethod(
                "sendText", arrayOf(BString, BString, Consumer::class.java)
            ) {
                val toUser = it[0] as String
                val text = it[1] as String
                @Suppress("UNCHECKED_CAST")
                val cb = it[2] as Consumer<Long?>
                thread {
                    runCatching {
//                        val sendMsgObject = WeMessageApi.methodGetSendMsgObject.method.invoke(null) ?: return@thread
                        val msgObj = WeMessageApi.classNetSceneSendMsg.clazz.createInstance(toUser, text, 1, 0, null)

                        val queue = WeNetSceneApi.classMmKernel.clazz.reflekt()
                            .firstMethod {
                                returnType = WeNetSceneApi.methodAddNetSceneToQueue.method.declaringClass
                            }.invokeStatic()!!

                        val getDispatcherMethod = queue.javaClass.methods.first { method ->
                            method.parameterCount == 0 &&
                            method.returnType.isInterface &&
                            method.returnType.name.startsWith("com.tencent.mm.")
                        }
                        val dispatcher = getDispatcherMethod.invoke(queue)

                        val doSceneMethod = msgObj.javaClass.reflekt().firstMethod {
                            name = "doScene"
                        }
                        val callbackInterface = doSceneMethod.parameterTypes[1]

                        val callbackProxy = Proxy.newProxyInstance(
                            ClassLoaders.HOST,
                            arrayOf(callbackInterface)
                        ) { _, method, args ->
                            if (method.name == "onSceneEnd") {
                                try {
                                    val errType = args[0] as Int
                                    val errCode = args[1] as Int
                                    val scene = args[3]
                                    if (errType == 0 && errCode == 0 && scene != null) {
                                        val lastMsgSvrId = WeDatabaseApi.getMessages(toUser, 1, 1).firstOrNull()?.msgId
                                        cb.accept(lastMsgSvrId)
                                    } else {
                                        cb.accept(null)
                                    }
                                } catch (_: Throwable) {
                                    cb.accept(null)
                                }
                            }
                            null
                        }

                        doSceneMethod.invoke(msgObj, dispatcher, callbackProxy)
                    }.onFailure { cb.accept(null) }
                }
                return@BshMethod null
            })

            // sendImage(toUser, imgPath) → Boolean
            setMethod(BshMethod(
                "sendImage", arrayOf(BString, BString)
            ) {
                val toUser = it[0] as String
                val imgPath = it[1] as String
                return@BshMethod runCatchingBsh("sendImage") {
                    WeMessageApi.sendImage(toUser, imgPath)
                }.getOrDefault(false)
            })

            // sendImage(toUser, imgPath, appId) → Boolean
            setMethod(BshMethod(
                "sendImage", arrayOf(BString, BString, BString)
            ) {
                val toUser = it[0] as String
                val imgPath = it[1] as String
                return@BshMethod runCatchingBsh("sendImage") {
                    WeMessageApi.sendImage(toUser, imgPath)
                }.getOrDefault(false)
            })

            // sendImage(toUser, imgPath, msgId) → Boolean
            setMethod(BshMethod(
                "sendImage", arrayOf(BString, BString, java.lang.Long.TYPE)
            ) {
                val toUser = it[0] as String
                val imgPath = it[1] as String
                return@BshMethod runCatchingBsh("sendImage") {
                    WeMessageApi.sendImage(toUser, imgPath)
                }.getOrDefault(false)
            })

            // sendVoice(toUser, path) → Boolean (default duration 0)
            setMethod(BshMethod(
                "sendVoice", arrayOf(BString, BString)
            ) {
                val toUser = it[0] as String
                val path = it[1] as String
                return@BshMethod runCatchingBsh("sendVoice") {
                    WeMessageApi.sendVoice(toUser, path, 0)
                }.getOrDefault(false)
            })

            // sendVoice(toUser, path, durationMs) → Boolean
            setMethod(BshMethod(
                "sendVoice", arrayOf(BString, BString, int)
            ) {
                val toUser = it[0] as String
                val path = it[1] as String
                val durationMs = it[2] as Int
                return@BshMethod runCatchingBsh("sendVoice") {
                    WeMessageApi.sendVoice(toUser, path, durationMs)
                }.getOrDefault(false)
            })

            // sendFile(talker, filePath, title) → Boolean
            setMethod(BshMethod(
                "sendFile", arrayOf(BString, BString, BString)
            ) {
                val talker = it[0] as String
                val filePath = it[1] as String
                val title = it[2] as String
                return@BshMethod runCatchingBsh("sendFile") {
                    WeMessageApi.sendFile(talker, filePath, title)
                }.getOrDefault(false)
            })

            // sendXmlAppMsg(toUser, xmlContent) → Boolean
            setMethod(BshMethod(
                "sendXmlAppMsg", arrayOf(BString, BString)
            ) {
                val toUser = it[0] as String
                val xmlContent = it[1] as String
                return@BshMethod runCatchingBsh("sendXmlAppMsg") {
                    WeMessageApi.sendXmlAppMsg(toUser, xmlContent)
                }.getOrDefault(false)
            })

            // insertSystemMsg(talker, content, time) → insert a system message in chat
            setMethod(BshMethod(
                "insertSystemMsg", arrayOf(BString, BString, java.lang.Long.TYPE)
            ) {
                val talker = it[0] as String
                val content = it[1] as String
                val time = it[2] as Long
                runCatchingBsh("insertSystemMsg") {
                    WeMessageApi.createSimpleMsgInfoAndInsert(MessageType.SYSTEM.code, talker, content, time)
                }
            })

            // revokeMsg(msgSvrId) → revoke a sent message
            setMethod(BshMethod(
                "revokeMsg", arrayOf(java.lang.Long.TYPE)
            ) {
                val msgSvrId = it[0] as Long
                return@BshMethod runCatchingBsh("revokeMsg") {
                    WeMessageApi.revokeMsg(MessageInfo(WeMessageApi.getMsgInfoInstanceBySvrId(msgSvrId)))
                }.getOrDefault(false)
            })

            // sendQuoteMsg(talker, msgSvrId, content) → send a quote-reply message
            setMethod(BshMethod(
                "sendQuoteMsg", arrayOf(BString, java.lang.Long.TYPE, BString)
            ) {
                val talker = it[0] as String
                val msgSvrId = it[1] as Long
                val content = it[2] as String
                return@BshMethod runCatchingBsh("sendQuoteMsg") {
                    WeMessageApi.sendQuoteMsg(talker, msgSvrId, content)
                }.getOrDefault(false)
            })

            // ===== Database =====

            // queryHistoryMsg(talker, msgSvrId, limit) → list of WeMessage objects
            setMethod(BshMethod(
                "queryHistoryMsg", arrayOf(BString, java.lang.Long.TYPE, int)
            ) {
                val talker = it[0] as String
                val limit = it[2] as Int
                return@BshMethod runCatchingBsh("queryHistoryMsg") {
                    WeDatabaseApi.getMessages(talker, 1, limit)
                }.getOrDefault(emptyList<Any>())
            })

            // ===== Extended Messaging (Step 3d) =====

            // sendEmoji(toUser, md5) → Boolean
            setMethod(BshMethod(
                "sendEmoji", arrayOf(BString, BString)
            ) {
                val toUser = it[0] as String
                val path = it[1] as String
                return@BshMethod runCatchingBsh("sendEmoji") {
                    WeMessageApi.sendEmoji(toUser, path)
                }.getOrDefault(false)
            })

            // sendEmoji(toUser, md5, msgId) → Boolean
            setMethod(BshMethod(
                "sendEmoji", arrayOf(BString, BString, java.lang.Long.TYPE)
            ) {
                val toUser = it[0] as String
                val path = it[1] as String
                return@BshMethod runCatchingBsh("sendEmoji") {
                    WeMessageApi.sendEmoji(toUser, path)
                }.getOrDefault(false)
            })

            // sendPat(toUser, patTarget) → Boolean
            setMethod(BshMethod(
                "sendPat", arrayOf(BString, BString)
            ) {
                val toUser = it[0] as String; val patTarget = it[1] as String
                return@BshMethod runCatchingBsh("sendPat") {
                    WeMessageApi.sendPat(toUser, patTarget)
                }.getOrDefault(false)
            })

            // sendLocation(talker, poiName, label, x, y, scale) → Boolean
            setMethod(BshMethod(
                "sendLocation", arrayOf(BString, BString, BString, BString, BString, BString)
            ) {
                return@BshMethod runCatchingBsh("sendLocation") {
                    WeMessageApi.sendLocation(
                         it[0] as String,
                         it[1] as String,
                         it[2] as String,
                         it[3] as String,
                         it[4] as String,
                         it[5] as String
                    )
                }.getOrDefault(false)
            })

            // sendShareCard(talker, cardWxId) → Boolean
            setMethod(BshMethod(
                "sendShareCard", arrayOf(BString, BString)
            ) {
                val toUser = it[0] as String; val cardWxId = it[1] as String
                return@BshMethod runCatchingBsh("sendShareCard") {
                    WeMessageApi.sendShareCard(toUser, cardWxId)
                }.getOrDefault(false)
            })

            // sendVideo(toUser, path) → Boolean
            setMethod(BshMethod(
                "sendVideo", arrayOf(BString, BString)
            ) {
                val toUser = it[0] as String; val path = it[1] as String
                return@BshMethod runCatchingBsh("sendVideo") {
                    WeMessageApi.sendVideo(toUser, path)
                }.getOrDefault(false)
            })

            // ===== Sharing/Media Messaging =====

            // shareFile(talker, title, filePath, appId)
            setMethod(BshMethod("shareFile", arrayOf(BString, BString, BString, BString)) {
                val talker = it[0] as String
                val title = it[1] as String
                val filePath = it[2] as String
                val appId = it[3] as String
                return@BshMethod runCatchingBsh("shareFile") {
                    WeMessageApi.sendFile(talker, filePath, title, appId)
                }.getOrDefault(false)
            })

            // sendMediaMsg(talker, mediaMessage, appId)
            setMethod(BshMethod("sendMediaMsg", arrayOf(BString, Any::class.java, BString)) {
                val talker = it[0] as String
                val mediaMessage = it[1]
                val appId = it[2] as String
                return@BshMethod runCatchingBsh("sendMediaMsg") {
                    WeMessageApi.sendMediaMsg(talker, mediaMessage, appId)
                }.getOrDefault(false)
            })

            // shareWebpage(talker, title, description, webpageUrl, thumbData, appId)
            setMethod(BshMethod("shareWebpage", arrayOf(BString, BString, BString, BString, ByteArray::class.java, BString)) {
                val talker = it[0] as String
                val title = it[1] as String
                val description = it[2] as String
                val webpageUrl = it[3] as String
                val thumbData = it[4] as? ByteArray
                val appId = it[5] as String
                return@BshMethod runCatchingBsh("shareWebpage") {
                    WeMessageApi.shareWebpage(talker, title, description, webpageUrl, thumbData, appId)
                }.getOrDefault(false)
            })

            // shareVideo(talker, title, description, videoUrl, thumbData, appId)
            setMethod(BshMethod("shareVideo", arrayOf(BString, BString, BString, BString, ByteArray::class.java, BString)) {
                val talker = it[0] as String
                val title = it[1] as String
                val description = it[2] as String
                val videoUrl = it[3] as String
                val thumbData = it[4] as? ByteArray
                val appId = it[5] as String
                return@BshMethod runCatchingBsh("shareVideo") {
                    WeMessageApi.shareVideo(talker, title, description, videoUrl, thumbData, appId)
                }.getOrDefault(false)
            })

            // shareText(talker, text, appId)
            setMethod(BshMethod("shareText", arrayOf(BString, BString, BString)) {
                val talker = it[0] as String
                val text = it[1] as String
                val appId = it[2] as String
                return@BshMethod runCatchingBsh("shareText") {
                    WeMessageApi.shareText(talker, text, appId)
                }.getOrDefault(false)
            })

            // shareMusic(talker, title, description, musicUrl, musicDataUrl, thumbData, appId)
            setMethod(BshMethod("shareMusic", arrayOf(BString, BString, BString, BString, BString, ByteArray::class.java, BString)) {
                val talker = it[0] as String
                val title = it[1] as String
                val description = it[2] as String
                val musicUrl = it[3] as String
                val musicDataUrl = it[4] as String
                val thumbData = it[5] as? ByteArray
                val appId = it[6] as String
                return@BshMethod runCatchingBsh("shareMusic") {
                    WeMessageApi.shareMusic(talker, title, description, musicUrl, musicDataUrl, thumbData, appId)
                }.getOrDefault(false)
            })

            // shareMusicVideo(talker, title, description, musicUrl, musicDataUrl, singerName, duration, songLyric, thumbData, appId)
            setMethod(BshMethod("shareMusicVideo", arrayOf(
                BString, BString, BString, BString, BString, BString, int, BString, ByteArray::class.java, BString
            )) {
                val talker = it[0] as String
                val title = it[1] as String
                val description = it[2] as String
                val musicUrl = it[3] as String
                val musicDataUrl = it[4] as String
                val singerName = it[5] as String
                val duration = it[6] as Int
                val songLyric = it[7] as String
                val thumbData = it[8] as? ByteArray
                val appId = it[9] as String
                return@BshMethod runCatchingBsh("shareMusicVideo") {
                    WeMessageApi.shareMusicVideo(talker, title, description, musicUrl, musicDataUrl, singerName, duration, songLyric, thumbData, appId)
                }.getOrDefault(false)
            })

            // shareMiniProgram(talker, title, description, userName, path, thumbData, appId)
            setMethod(BshMethod("shareMiniProgram", arrayOf(
                BString, BString, BString, BString, BString, ByteArray::class.java, BString
            )) {
                val talker = it[0] as String
                val title = it[1] as String
                val description = it[2] as String
                val userName = it[3] as String
                val path = it[4] as String
                val thumbData = it[5] as? ByteArray
                val appId = it[6] as String
                return@BshMethod runCatchingBsh("shareMiniProgram") {
                    WeMessageApi.shareMiniProgram(talker, title, description, userName, path, thumbData, appId)
                }.getOrDefault(false)
            })

            // ===== Contact Labels (Step 3c) =====

            // getContactLabelList() → list of ContactLabelBean
            setMethod(BshMethod(
                "getContactLabelList", emptyArray<Class<*>>()
            ) {
                return@BshMethod runCatchingBsh("getContactLabelList") {
                    WeContactLabelApi.getAllLabels().map { label -> ContactLabelBean(label) }
                }.getOrDefault(emptyList<Any>())
            })

            // getContactByLabelId(id) → list of wxid strings (Integer label ID)
            setMethod(BshMethod(
                "getContactByLabelId", arrayOf(int)
            ) {
                val labelId = it[0] as Int
                return@BshMethod runCatchingBsh("getContactByLabelId") {
                    WeContactLabelApi.getContactsByLabelId(labelId)
                }.getOrDefault(emptyList<Any>())
            })

            // getContactByLabelId(id) → list of wxid strings (String label ID)
            setMethod(BshMethod(
                "getContactByLabelId", arrayOf(BString)
            ) {
                val labelIdStr = it[0] as String
                val labelId = labelIdStr.toIntOrNull() ?: 0
                return@BshMethod runCatchingBsh("getContactByLabelId") {
                    WeContactLabelApi.getContactsByLabelId(labelId)
                }.getOrDefault(emptyList<Any>())
            })

            // getContactByLabelName(name) → list of wxid strings
            setMethod(BshMethod(
                "getContactByLabelName", arrayOf(BString)
            ) {
                val labelName = it[0] as String
                return@BshMethod runCatchingBsh("getContactByLabelName") {
                    WeContactLabelApi.getContactsByLabelName(labelName)
                }.getOrDefault(emptyList<Any>())
            })

            // ===== Security (Step 3b) =====

            // verifyUser(userId, ticket, scene) → opens verify UI
            setMethod(BshMethod(
                "verifyUser", arrayOf(BString, BString, int)
            ) {
                val userId = it[0] as String; val ticket = it[1] as String; val scene = it[2] as Int
                runCatchingBsh("verifyUser") { WeContactApi.verifyUser(userId, ticket, scene) }
            })

            // verifyUser(userId, ticket, scene, privacy) → opens verify UI
            setMethod(BshMethod(
                "verifyUser", arrayOf(BString, BString, int, int)
            ) {
                val userId = it[0] as String; val ticket = it[1] as String; val scene = it[2] as Int; val privacy = it[3] as Int
                runCatchingBsh("verifyUser") { WeContactApi.verifyUser(userId, ticket, scene, privacy) }
            })

            // ===== Chatroom Management =====

            // addChatroomMember(groupId, memberWxId) — add single member
            setMethod(BshMethod(
                "addChatroomMember", arrayOf(BString, BString)
            ) {
                val groupId = it[0] as String
                val memberWxId = it[1] as String
                runCatchingBsh("addChatroomMember") { WeGroupApi.addMember(groupId, memberWxId) }
            })

            // addChatroomMember(groupId, memberList) — add multiple members
            setMethod(BshMethod(
                "addChatroomMember", arrayOf(BString, List::class.java)
            ) {
                val groupId = it[0] as String
                @Suppress("UNCHECKED_CAST")
                val memberList = it[1] as List<String>
                runCatchingBsh("addChatroomMember") { WeGroupApi.addMembers(groupId, memberList) }
            })

            // delChatroomMember(groupId, memberWxId) — remove single member
            setMethod(BshMethod(
                "delChatroomMember", arrayOf(BString, BString)
            ) {
                val groupId = it[0] as String
                val memberWxId = it[1] as String
                runCatchingBsh("delChatroomMember") { WeGroupApi.delMember(groupId, memberWxId) }
            })

            // delChatroomMember(groupId, memberList) — remove multiple members
            setMethod(BshMethod(
                "delChatroomMember", arrayOf(BString, List::class.java)
            ) {
                val groupId = it[0] as String
                @Suppress("UNCHECKED_CAST")
                val memberList = it[1] as List<String>
                runCatchingBsh("delChatroomMember") { WeGroupApi.delMembers(groupId, memberList) }
            })

            // inviteChatroomMember(groupId, memberWxId) — invite single member
            setMethod(BshMethod(
                "inviteChatroomMember", arrayOf(BString, BString)
            ) {
                val groupId = it[0] as String
                val memberWxId = it[1] as String
                runCatchingBsh("inviteChatroomMember") { WeGroupApi.inviteMember(groupId, memberWxId) }
            })

            // inviteChatroomMember(groupId, memberList) — invite multiple members
            setMethod(BshMethod(
                "inviteChatroomMember", arrayOf(BString, List::class.java)
            ) {
                val groupId = it[0] as String
                @Suppress("UNCHECKED_CAST")
                val memberList = it[1] as List<String>
                runCatchingBsh("inviteChatroomMember") { WeGroupApi.inviteMembers(groupId, memberList) }
            })

            // ===== Hooks =====

            // hookBefore(member, consumer) → hook handle id
            setMethod(BshMethod(
                "hookBefore", arrayOf(Member::class.java, Consumer::class.java)
            ) {
                val member = it[0] as Member
                @Suppress("UNCHECKED_CAST")
                val consumer = it[1] as Consumer<XC_MethodHook.MethodHookParam>
                return@BshMethod JavaHookApi.hookBefore(member, consumer)
            })

            // hookAfter(member, consumer) → hook handle id
            setMethod(BshMethod(
                "hookAfter", arrayOf(Member::class.java, Consumer::class.java)
            ) {
                val member = it[0] as Member
                @Suppress("UNCHECKED_CAST")
                val consumer = it[1] as Consumer<XC_MethodHook.MethodHookParam>
                return@BshMethod JavaHookApi.hookAfter(member, consumer)
            })

            // hookReplace(member, function) → hook handle id
            setMethod(BshMethod(
                "hookReplace", arrayOf(Member::class.java, Function::class.java)
            ) {
                val member = it[0] as Member
                @Suppress("UNCHECKED_CAST")
                val function = it[1] as Function<XC_MethodHook.MethodHookParam, Any?>
                return@BshMethod JavaHookApi.hookReplace(member, function)
            })

            // unhook(handle) → remove a hook
            setMethod(BshMethod(
                "unhook", arrayOf(me.hd.wauxv.hook.HookHandle::class.java)
            ) {
                val handle = it[0] as me.hd.wauxv.hook.HookHandle
                JavaHookApi.unhook(handle)
            })

            // === DexKit Search APIs ===
            setMethod(BshMethod("findClassList", arrayOf(List::class.java)) {
                @Suppress("UNCHECKED_CAST")
                val usingStrings = it[0] as List<String>
                return@BshMethod runCatchingBsh("findClassList") {
                    DexKit.findClass {
                        matcher {
                            usingStrings(usingStrings)
                        }
                    }.map { data -> data.asClass }
                }.getOrDefault(emptyList<Any>())
            })
            setMethod(BshMethod("findMemberList", arrayOf(List::class.java)) {
                @Suppress("UNCHECKED_CAST")
                val usingStrings = it[0] as List<String>
                return@BshMethod runCatchingBsh("findMemberList") {
                    DexKit.findMethod {
                        matcher {
                            usingStrings(usingStrings)
                        }
                    }.mapNotNull { data ->
                        val name = data.name
                        if (name == "<init>" || name == "<clinit>") {
                            data.asConstructor
                        } else {
                            data.asMethod
                        }
                    }
                }.getOrDefault(emptyList<Any>())
            })

            // ===== Extended WAuxv Methods =====

            // delay(ms, runnable)
            setMethod(BshMethod("delay", arrayOf(java.lang.Long.TYPE, Runnable::class.java)) {
                val ms = it[0] as Long; val action = it[1] as Runnable
                thread { try { Thread.sleep(ms); action.run() } catch (_: InterruptedException) {} }
            })

            // === HTTP (OkHttp) ===
            setMethod(BshMethod("get", arrayOf(BString, Map::class.java, Consumer::class.java)) {
                val url = it[0] as String
                @Suppress("UNCHECKED_CAST")
                val headers = it[1] as? Map<String, String>
                @Suppress("UNCHECKED_CAST")
                val cb = it[2] as Consumer<String?>
                thread {
                    runCatching {
                        val req = okhttp3.Request.Builder().url(url).apply {
                            headers?.forEach { (k, v) -> addHeader(k, v) }
                        }.build()
                        val resp = okhttp3.OkHttpClient().newCall(req).execute()
                        cb.accept(resp.body.string())
                    }.onFailure { cb.accept(null) }
                }
            })
            setMethod(BshMethod("get", arrayOf(BString, Map::class.java, java.lang.Long.TYPE, Consumer::class.java)) {
                val url = it[0] as String
                @Suppress("UNCHECKED_CAST")
                val headers = it[1] as? Map<String, String>
                val timeout = it[2] as Long
                @Suppress("UNCHECKED_CAST")
                val cb = it[3] as Consumer<String?>
                thread {
                    runCatching {
                        val req = okhttp3.Request.Builder().url(url).apply {
                            headers?.forEach { (k, v) -> addHeader(k, v) }
                        }.build()
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val resp = client.newCall(req).execute()
                        cb.accept(resp.body.string())
                    }.onFailure { cb.accept(null) }
                }
            })
            setMethod(BshMethod("post", arrayOf(BString, Map::class.java, Map::class.java, Consumer::class.java)) {
                val url = it[0] as String
                @Suppress("UNCHECKED_CAST")
                val params = it[1] as? Map<String, String>
                @Suppress("UNCHECKED_CAST")
                val headers = it[2] as? Map<String, String>
                @Suppress("UNCHECKED_CAST")
                val cb = it[3] as Consumer<String?>
                thread {
                    runCatching {
                        val form = okhttp3.FormBody.Builder()
                        params?.forEach { (k, v) -> form.add(k, v) }
                        val req = okhttp3.Request.Builder().url(url).post(form.build()).apply {
                            headers?.forEach { (k, v) -> addHeader(k, v) }
                        }.build()
                        val resp = okhttp3.OkHttpClient().newCall(req).execute()
                        cb.accept(resp.body.string())
                    }.onFailure { cb.accept(null) }
                }
            })
            setMethod(BshMethod("post", arrayOf(BString, Map::class.java, Map::class.java, java.lang.Long.TYPE, Consumer::class.java)) {
                val url = it[0] as String
                @Suppress("UNCHECKED_CAST")
                val params = it[1] as? Map<String, String>
                @Suppress("UNCHECKED_CAST")
                val headers = it[2] as? Map<String, String>
                val timeout = it[3] as Long
                @Suppress("UNCHECKED_CAST")
                val cb = it[4] as Consumer<String?>
                thread {
                    runCatching {
                        val form = okhttp3.FormBody.Builder()
                        params?.forEach { (k, v) -> form.add(k, v) }
                        val req = okhttp3.Request.Builder().url(url).post(form.build()).apply {
                            headers?.forEach { (k, v) -> addHeader(k, v) }
                        }.build()
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val resp = client.newCall(req).execute()
                        cb.accept(resp.body.string())
                    }.onFailure { cb.accept(null) }
                }
            })
            setMethod(BshMethod("download", arrayOf(BString, BString, Map::class.java, Consumer::class.java)) {
                val url = it[0] as String; val path = it[1] as String
                @Suppress("UNCHECKED_CAST")
                val headers = it[2] as? Map<String, String>
                @Suppress("UNCHECKED_CAST")
                val cb = it[3] as Consumer<File?>
                thread {
                    runCatching {
                        val req = okhttp3.Request.Builder().url(url).apply {
                            headers?.forEach { (k, v) -> addHeader(k, v) }
                        }.build()
                        val resp = okhttp3.OkHttpClient().newCall(req).execute()
                        val file = File(path)
                        Files.copy(resp.body.byteStream(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        cb.accept(file)
                    }.onFailure { cb.accept(null) }
                }
            })
            setMethod(BshMethod("download", arrayOf(BString, BString, Map::class.java, java.lang.Long.TYPE, Consumer::class.java)) {
                val url = it[0] as String; val path = it[1] as String
                @Suppress("UNCHECKED_CAST")
                val headers = it[2] as? Map<String, String>
                val timeout = it[3] as Long
                @Suppress("UNCHECKED_CAST")
                val cb = it[4] as Consumer<File?>
                thread {
                    runCatching {
                        val req = okhttp3.Request.Builder().url(url).apply {
                            headers?.forEach { (k, v) -> addHeader(k, v) }
                        }.build()
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val resp = client.newCall(req).execute()
                        val file = File(path)
                        Files.copy(resp.body.byteStream(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        cb.accept(file)
                    }.onFailure { cb.accept(null) }
                }
            })

            // === Reflection Helpers ===
            setMethod(BshMethod("firstMethod", arrayOf(Any::class.java, BString)) {
                val clazz = if (it[0] is Class<*>) it[0] as Class<*> else it[0].javaClass
                return@BshMethod runCatching { clazz.methods.find { m -> m.name == it[1] as String } }.getOrNull()
            })
            setMethod(BshMethod("firstMethod", arrayOf(Any::class.java, BString, int)) {
                val clazz = if (it[0] is Class<*>) it[0] as Class<*> else it[0].javaClass
                return@BshMethod runCatching { clazz.methods.find { m -> m.name == it[1] as String && m.parameterCount == it[2] as Int } }.getOrNull()
            })
            setMethod(BshMethod("firstConstructor", arrayOf(Any::class.java, int)) {
                val clazz = if (it[0] is Class<*>) it[0] as Class<*> else it[0].javaClass
                return@BshMethod runCatching { clazz.constructors.find { c -> c.parameterCount == it[1] as Int } }.getOrNull()
            })
            setMethod(BshMethod("firstField", arrayOf(Any::class.java, BString)) {
                val clazz = if (it[0] is Class<*>) it[0] as Class<*> else it[0].javaClass
                return@BshMethod runCatching { clazz.getDeclaredField(it[1] as String).apply { isAccessible = true } }.getOrNull()
            })

            // === Reflection Accessors ===
            setMethod(BshMethod("getField", arrayOf(Any::class.java, BString)) {
                val obj = it[0]
                val fieldName = it[1] as String
                return@BshMethod if (obj is Class<*>) {
                    obj.reflekt().getField(fieldName)
                } else {
                    obj.reflekt().getField(fieldName)
                }
            })
            setMethod(BshMethod("getField", arrayOf(Any::class.java, BString, bool)) {
                val obj = it[0]
                val fieldName = it[1] as String
                val superclass = it[2] as Boolean
                return@BshMethod if (obj is Class<*>) {
                    obj.reflekt().getField(fieldName, superclass)
                } else {
                    obj.reflekt().getField(fieldName, superclass)
                }
            })
            setMethod(BshMethod("setField", arrayOf(Any::class.java, BString, Any::class.java)) {
                val obj = it[0]
                val fieldName = it[1] as String
                val value = it[2]
                if (obj is Class<*>) {
                    obj.reflekt().setField(fieldName, value)
                } else {
                    obj.reflekt().setField(fieldName, value)
                }
                return@BshMethod null
            })

            // === Reflection Execution ===
            setMethod(BshMethod("invokeMethod", arrayOf(Any::class.java, BString)) {
                val instance = it[0]
                val methodName = it[1] as String
                val clazz = instance as? Class<*> ?: instance.javaClass
                val method = clazz.reflekt().firstMethod { name = methodName; parameterCount = 0 }.self
                method.isAccessible = true
                val target = if (instance is Class<*>) null else instance
                return@BshMethod method.invoke(target)
            })
            setMethod(BshMethod("invokeMethod", arrayOf(Any::class.java, BString, Array::class.java)) {
                val instance = it[0]
                val methodName = it[1] as String
                val params = it[2] as Array<*>
                val clazz = instance as? Class<*> ?: instance.javaClass
                val method = clazz.reflekt().firstMethod { name = methodName; parameterCount = params.size }.self
                method.isAccessible = true
                val target = if (instance is Class<*>) null else instance
                return@BshMethod method.invoke(target, *params)
            })
            setMethod(BshMethod("invokeMethod", arrayOf(Any::class.java, BString, int)) {
                val instance = it[0]
                val methodName = it[1] as String
                val paramCount = it[2] as Int
                val clazz = instance as? Class<*> ?: instance.javaClass
                val method = clazz.reflekt().firstMethod { name = methodName; parameterCount = paramCount }.self
                method.isAccessible = true
                val target = if (instance is Class<*>) null else instance
                return@BshMethod method.invoke(target)
            })
            setMethod(BshMethod("invokeMethod", arrayOf(Any::class.java, BString, int, Array::class.java)) {
                val instance = it[0]
                val methodName = it[1] as String
                val paramCount = it[2] as Int
                val params = it[3] as Array<*>
                val clazz = instance as? Class<*> ?: instance.javaClass
                val method = clazz.reflekt().firstMethod { name = methodName; parameterCount = paramCount }.self
                method.isAccessible = true
                val target = if (instance is Class<*>) null else instance
                return@BshMethod method.invoke(target, *params)
            })

            setMethod(BshMethod("createInstance", arrayOf(Any::class.java, int)) {
                val instance = it[0]
                val paramCount = it[1] as Int
                val clazz = instance as? Class<*> ?: instance.javaClass
                val ctor = clazz.reflekt().firstConstructor { parameterCount = paramCount }.self
                ctor.isAccessible = true
                return@BshMethod ctor.newInstance()
            })
            setMethod(BshMethod("createInstance", arrayOf(Any::class.java, int, Array::class.java)) {
                val instance = it[0]
                val paramCount = it[1] as Int
                val params = it[2] as Array<*>
                val clazz = instance as? Class<*> ?: instance.javaClass
                val ctor = clazz.reflekt().firstConstructor { parameterCount = paramCount }.self
                ctor.isAccessible = true
                return@BshMethod ctor.newInstance(*params)
            })

            // === Payments ===
            setMethod(BshMethod("confirmTransfer", arrayOf(BString, BString, BString, Integer.TYPE)) {
                return@BshMethod WePaymentApi.confirmTransfer(it[0] as String, it[1] as String, it[2] as String, it[3] as Int)
            })
            setMethod(BshMethod("refuseTransfer", arrayOf(BString, BString, BString)) {
                return@BshMethod WePaymentApi.refuseTransfer(it[0] as String, it[1] as String, it[2] as String, 0)
            })

            // === JSLogin ===
            setMethod(BshMethod("jsLogin", arrayOf(BString, Consumer::class.java)) { args ->
                @Suppress("UNCHECKED_CAST")
                val callback = args[1] as Consumer<String?>
                WeAuthApi.jsLogin(args[0] as String) { callback.accept(it) }
                return@BshMethod null
            })

            // === SNS Moments ===
            setMethod(BshMethod("uploadText", arrayOf(BString)) {
                return@BshMethod WeMomentsApi.postText(it[0] as String)
            })
            setMethod(BshMethod("uploadText", arrayOf(BString, BString, BString)) {
                return@BshMethod WeMomentsApi.postText(it[0] as String, it[1] as String, it[2] as String)
            })
            setMethod(BshMethod("uploadText", arrayOf(org.json.JSONObject::class.java)) {
                val jo = it[0] as org.json.JSONObject
                @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                return@BshMethod WeMomentsApi.postText(
                    jo.optString("content", ""),
                    jo.optString("sdkId", null),
                    jo.optString("sdkAppName", null)
                )
            })
            setMethod(BshMethod("uploadTextAndPicList", arrayOf(BString, BString)) {
                return@BshMethod WeMomentsApi.postTextAndImages2(it[0] as String, listOf(it[1] as String))
            })
            setMethod(BshMethod("uploadTextAndPicList", arrayOf(BString, BString, BString, BString)) {
                return@BshMethod WeMomentsApi.postTextAndImages2(it[0] as String, listOf(it[1] as String), it[2] as String, it[3] as String)
            })
            setMethod(BshMethod("uploadTextAndPicList", arrayOf(BString, List::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return@BshMethod WeMomentsApi.postTextAndImages2(it[0] as String, it[1] as List<String>)
            })
            setMethod(BshMethod("uploadTextAndPicList", arrayOf(BString, List::class.java, BString, BString)) {
                @Suppress("UNCHECKED_CAST")
                return@BshMethod WeMomentsApi.postTextAndImages2(it[0] as String, it[1] as List<String>, it[2] as String, it[3] as String)
            })
            setMethod(BshMethod("uploadTextAndPicList", arrayOf(org.json.JSONObject::class.java)) {
                val jo = it[0] as org.json.JSONObject
                val picList = ArrayList<String>()
                val ja = jo.optJSONArray("picPathList")
                if (ja != null) {
                    for (i in 0 until ja.length()) {
                        picList.add(ja.optString(i))
                    }
                }
                @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                return@BshMethod WeMomentsApi.postTextAndImages2(
                    jo.optString("content", ""),
                    picList,
                    jo.optString("sdkId", null),
                    jo.optString("sdkAppName", null)
                )
            })

            // === Network Queue and XML Messages ===
            setMethod(BshMethod("addToQueue", arrayOf(Any::class.java)) {
                WeNetSceneApi.sendNetScene(it[0])
                return@BshMethod null
            })
            setMethod(BshMethod("sendXmlMsg", arrayOf(BString, BString)) {
                return@BshMethod WeMessageApi.sendXmlAppMsg(it[0] as String, it[1] as String)
            })

            // === Messaging variants ===
            setMethod(BshMethod("sendText", arrayOf(BString, BString, Consumer::class.java)) {
                val ok = WeMessageApi.sendText(it[0] as String, it[1] as String)
                @Suppress("UNCHECKED_CAST")
                (it[2] as Consumer<Any>).accept(ok)
            })
            setMethod(BshMethod("sendImage", arrayOf(BString, BString, BString)) {
                return@BshMethod WeMessageApi.sendImage(it[0] as String, it[1] as String)
            })
            setMethod(BshMethod("sendLocation", arrayOf(BString, org.json.JSONObject::class.java)) {
                val jo = it[1] as org.json.JSONObject
                return@BshMethod WeMessageApi.sendLocation(it[0] as String, jo.optString("poiName",""), jo.optString("label",""), jo.optString("x","0"), jo.optString("y","0"), jo.optString("scale","0"))
            })
            setMethod(BshMethod("sendMediaMsg", arrayOf(BString, Any::class.java, BString)) {
                val toUser = it[0] as String
                val mediaMsg = it[1]
                val appId = it[2] as String
                return@BshMethod runCatchingBsh("sendMediaMsg") {
                    WeMessageApi.sendMediaMsg(toUser, mediaMsg, appId)
                    true
                }.getOrDefault(false)
            })
            setMethod(BshMethod("sendCipherMsg", arrayOf(BString, BString, BString)) {
                val toUser = it[0] as String
                val title = it[1] as String
                val content = it[2] as String
                val encodedContent = android.text.Html.escapeHtml(content)
                val xml = """<msg><appmsg type="1"><title>$title</title><des>$title</des><content>|WA|$encodedContent</content></appmsg></msg>"""
                return@BshMethod runCatchingBsh("sendCipherMsg") {
                    WeMessageApi.sendXmlAppMsg(toUser, xml)
                }.getOrDefault(false)
            })
            setMethod(BshMethod("sendNoteMsg", arrayOf(BString, BString)) {
                val toUser = it[0] as String
                val noteXml = it[1] as String
                val xml = """<msg><appmsg type="53"><title>$noteXml</title><extinfo><solitaire_info></solitaire_info></extinfo></appmsg></msg>"""
                return@BshMethod runCatchingBsh("sendNoteMsg") {
                    WeMessageApi.sendXmlAppMsg(toUser, xml)
                }.getOrDefault(false)
            })
            setMethod(BshMethod("sendAppBrandMsg", arrayOf(BString, BString, BString, BString)) {
                val toUser = it[0] as String
                val title = it[1] as String
                val pagePath = it[2] as String
                val username = it[3] as String
                val xml = """<msg><appmsg type="33"><title>$title</title><weappinfo><item><pagepath><![CDATA[$pagePath]]></pagepath><username>$username</username></item></weappinfo></appmsg></msg>"""
                return@BshMethod runCatchingBsh("sendAppBrandMsg") {
                    WeMessageApi.sendXmlAppMsg(toUser, xml)
                }.getOrDefault(false)
            })
            setMethod(BshMethod("modifyContactLabelList", arrayOf(BString, BString)) {
                WeContactLabelApi.modifyLabel(it[0] as String, listOf(it[1] as String))
            })
            setMethod(BshMethod("modifyContactLabelList", arrayOf(BString, List::class.java)) {
                @Suppress("UNCHECKED_CAST")
                WeContactLabelApi.modifyLabel(it[0] as String, it[1] as List<String>)
            })
            setMethod(BshMethod("downloadImg", arrayOf(BString, BString, BString, BString)) { args ->
//                val talker = it[0] as String
                val content = args[1] as String
                val savePath = args[3] as String
                thread {
                    runCatching {
                        val url = content.replaceFirst("\\[AtWx=([^]]+)]".toRegex(), "$1")
                        val resp = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute()
                        Files.copy(resp.body.byteStream(), java.nio.file.Paths.get(savePath), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }.onFailure { WeLogger.e(TAG, "downloadImg failed", it) }
                }
            })
            setMethod(BshMethod("evalSnapshot", arrayOf(InputStream::class.java)) { args ->
                runCatching {
                    plugin.interpreter.evalSnapshot(args[0] as InputStream, BshSnapshotDecompiler.SECRET_KEY)
                }.onFailure { WeLogger.e(TAG, "evalSnapshot failed", it) }
            })
            setMethod(BshMethod("uploadDeviceStep", arrayOf(java.lang.Long.TYPE)) { args ->
                val stepCount = args[0] as Long
                runCatching {
                    val devStepMgrClazz = "com.tencent.mm.plugin.sport.model.DeviceStepManager".toClass()
                    val uploadMethod = devStepMgrClazz.reflekt()
                        .firstMethod { parameters(Long::class.java, Long::class.java) }
                        .self
                    val getInstance = devStepMgrClazz.reflekt()
                        .firstMethod { modifiers(Modifiers.STATIC); parameters() }
                        .self
                    uploadMethod.invoke(getInstance.invoke(null), System.currentTimeMillis() / 1000, stepCount)
                }.onFailure { WeLogger.e(TAG, "uploadDeviceStep failed", it) }
            })
            setMethod(BshMethod("reloadPlugin", emptyArray<Class<*>>()) {
                val pluginName = plugin.name
                WeLogger.i(TAG, "reloading plugin: $pluginName")
                initPlugin(plugin)
            })

            // ===== Utility =====

            // getTopActivity() — returns the current top-most Activity
            setMethod(BshMethod(
                "getTopActivity", emptyArray<Class<*>>()
            ) {
                return@BshMethod getTopMostActivity()
            })
        }
    }

    // ========== Config Helpers ==========

    private fun configFile(plugin: JavaPlugin): File =
        plugin.dir.resolve("config.prop").toFile()

    private fun loadConfig(plugin: JavaPlugin): Properties {
        val props = Properties()
        val file = configFile(plugin)
        if (file.exists()) {
            file.reader(Charsets.UTF_8).use { reader ->
                props.load(reader)
            }
        }
        return props
    }

    private fun saveConfig(plugin: JavaPlugin, props: Properties) {
        configFile(plugin).writer(Charsets.UTF_8).use { writer ->
            props.store(writer, null)
        }
    }

    private inline fun <T> runCatchingBsh(methodName: String, block: () -> T): Result<T> {
        return runCatching(block).onFailure {
            WeLogger.e(TAG, "Error executing BSH method $methodName", it)
        }
    }
}
