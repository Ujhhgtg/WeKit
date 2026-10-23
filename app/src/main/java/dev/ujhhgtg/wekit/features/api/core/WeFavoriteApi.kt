package dev.ujhhgtg.wekit.features.api.core

import android.content.Context
import android.text.Html
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.sql.deferred
import dev.ujhhgtg.wekit.dexkit.sql.sqlClass
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.FavInfoProto
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

/** Reads favorite (收藏) items and sends them to chats through WeChat's own FavSendLogic. */
object WeFavoriteApi : ApiFeature(), IResolveDex {

    override val technicalId = "收藏服务"
    override val nameRes = R.string.feature_we_favorite_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_favorite_api_description

    private const val TAG = "WeFavoriteApi"
    private const val VOICE_DOWNLOAD_POLLS = 120
    private const val VOICE_DOWNLOAD_POLL_MS = 500L

    private val classPluginFav by dexClass(
        sqlClass {
            usingEqStrings("MicroMsg.Fav.PluginFav", "init fav storage")
        },
    )

    /** `FavItemInfoStorage.getByLocalId(long)`; the WCDB storage implements the same interface method. */
    private val methodFavStorageGetByLocalId by dexMethod(
        sqlMethod {
            usingEqStrings("MicroMsg.Fav.FavItemInfoStorage", "getBtLocalId, but db is null, return")
        },
    )

    /** `FavItemInfoStorage.getFirstPageList(type, limit, blockList, blockTypes, filter)`: newest first; type -1 is all. */
    private val methodFavStorageGetFirstPage by dexMethod(
        sqlMethod {
            declaredClass(deferred { methodFavStorageGetByLocalId.data.declaredClassName })
            usingEqStrings(
                "MicroMsg.Fav.FavItemInfoStorage",
                "getFirstPageList::block set contains target type, error, do return null",
            )
        },
    )

    /** `FavApiLogic.restartItemDownload(FavItemInfo, force)`: re-downloads a failed or done item's data. */
    private val methodRestartItemDownload by dexMethod(
        sqlMethod {
            paramTypes(deferred { methodFavStorageGetByLocalId.data.returnTypeName }, "boolean")
            modifiers = 8
            usingEqStrings("status not download failed or done!")
        },
    )

    /** `FavSendLogic.send(Context, toUser, extra, isBizChat, FavItemInfo, Runnable)`. */
    private val methodSendFavItem by dexMethod(
        sqlMethod {
            paramTypes(
                "android.content.Context",
                "java.lang.String",
                "java.lang.String",
                "boolean",
                deferred { methodFavStorageGetByLocalId.data.returnTypeName },
                "java.lang.Runnable",
            )
            modifiers = 8
            declaredClass {
                usingEqStrings("MicroMsg.FavSendLogic")
                usingEqStrings("want to send fav msg, but context is null")
            }
        },
    )

    private val favStorage: Any
        get() {
            val storageClass = methodFavStorageGetByLocalId.method.declaringClass
            val getter = classPluginFav.reflekt().firstMethod {
                parameterCount = 0
                returnType { it.isInterface && it.isAssignableFrom(storageClass) }
            }
            // PluginFav implements one service marker interface; storage getters live only on the implementation.
            val service = WeServiceApi.getServiceByClass(classPluginFav.clazz.interfaces.single())
            return getter.self.invoke(service)!!
        }

    /** The native `FavItemInfo` with [localId], or null when there is none. */
    fun getFavItem(localId: Long): Any? =
        favStorage.reflekt().firstMethod {
            name = methodFavStorageGetByLocalId.method.name
            parameters(Long::class.javaPrimitiveType!!)
        }.invoke(localId)

    /** A favorite as offered by pickers. */
    data class FavoriteSummary(val localId: Long, val type: Int, val title: String, val updateTimeMs: Long)

    /** The newest [limit] favorites of every type, newest first (WeChat's own first-page query). */
    fun listFavorites(limit: Int): List<FavoriteSummary> {
        val items = favStorage.reflekt().firstMethod {
            name = methodFavStorageGetFirstPage.method.name
            parameterCount = 5
        }.invoke(-1, limit, emptyList<Any>(), emptySet<Any>(), null) as List<*>
        return items.map { item ->
            fun field(name: String) = item!!.reflekt().firstField { this.name = name; superclass() }.get()
            val type = field("field_type") as Int
            val updateTime = field("field_updateTime") as Long
            FavoriteSummary(
                localId = field("field_localId") as Long,
                type = type,
                title = titleOf(type, field("field_xml") as String? ?: ""),
                // Older items store seconds.
                updateTimeMs = if (updateTime in 1..<10_000_000_000L) updateTime * 1000 else updateTime,
            )
        }
    }

    /** The first non-blank of the tags WeChat's list shows for [type] (as Hchat reads them). */
    private fun titleOf(type: Int, xml: String): String {
        val tags = when (type) {
            1 -> listOf("desc", "content", "title")
            5 -> listOf("pagetitle", "datatitle", "title", "pagedesc", "datadesc")
            6 -> listOf("label", "poiname", "locationname", "title", "desc")
            7 -> listOf("datatitle", "songname", "title", "datadesc", "desc")
            8 -> listOf("datatitle", "filename", "fileName", "title", "datadesc", "desc")
            19 -> listOf("datatitle", "title", "sourcedisplayname", "appname", "desc")
            else -> listOf("title", "desc", "datatitle", "description", "content", "filename", "fileName")
        }
        return tags.firstNotNullOfOrNull { tag ->
            Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                ?.removePrefix("<![CDATA[")?.removeSuffix("]]>")
                ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
                ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        }?.take(120).orEmpty()
    }

    /** A cached favorite voice file with its duration and the size the favorite records for it. */
    data class FavoriteVoice(val filePath: String, val durationMs: Int, val fileSize: Int) {
        /** The file has the recorded size; a missing (non-positive) size only needs the file to exist. */
        val isDownloaded: Boolean
            get() = File(filePath).let { if (fileSize > 0) it.length() == fileSize.toLong() else it.isFile }
    }

    /** The cached voice of a voice favorite (type 3), or null for other favorite types. */
    fun voiceOf(favItem: Any): FavoriteVoice? {
        val type = favItem.reflekt().firstField { name = "field_type"; superclass() }.get() as Int
        if (type != 3) return null
        val favProto = favItem.reflekt().firstField { name = "field_favProto"; superclass() }.get()!!
        val bytes = favProto.reflekt().firstMethod { name = "getData"; superclass() }.invoke() as ByteArray
        val voiceInfo = ProtoBuf.decodeFromByteArray<FavInfoProto>(bytes).voiceInfo
        val cacheName = voiceInfo.fileCacheName
        val bucketId = cacheName.hashCode() and 0xFF
        val cacheDir = RuntimeConfig.userDataDir / "favorite" / bucketId.toString()
        return FavoriteVoice((cacheDir / "$cacheName.${voiceInfo.fileCacheType}").absolutePathString(), voiceInfo.duration, voiceInfo.fileSize)
    }

    /** "talker:localId" of voice favorites waiting for their download. */
    private val pendingVoiceSends = ConcurrentHashMap.newKeySet<String>()

    /**
     * Sends favorite [localId] to [talker]. WeChat's FavSendLogic can't send voice favorites, so
     * those are sent as voice messages from the local favorite cache (as Hchat does); a voice not
     * downloaded yet is downloaded first and sent once it arrives, and counts as sent.
     */
    fun send(talker: String, localId: Long): Boolean {
        val item = getFavItem(localId) ?: run {
            WeLogger.w(TAG, "favorite $localId not found")
            return false
        }
        voiceOf(item)?.let { voice ->
            if (voice.isDownloaded) return WeMessageApi.sendVoice(talker, voice.filePath, voice.durationMs)
            sendAfterDownload(talker, localId, item, voice)
            return true
        }
        val context: Context = getTopMostActivity() ?: HostInfo.application
        methodSendFavItem.method.invoke(null, context, talker, "", false, item, null)
        WeLogger.i(TAG, "sent favorite $localId to $talker")
        return true
    }

    private fun sendAfterDownload(talker: String, localId: Long, item: Any, voice: FavoriteVoice) {
        val key = "$talker:$localId"
        if (!pendingVoiceSends.add(key)) return
        WeLogger.i(TAG, "downloading voice favorite $localId before sending it to $talker")
        // WeChat's detail page restarts the download on the main thread; it ignores items already downloading.
        runOnUiThread { methodRestartItemDownload.method.invoke(null, item, true) }
        thread(name = "$TAG-voice") {
            try {
                repeat(VOICE_DOWNLOAD_POLLS) {
                    Thread.sleep(VOICE_DOWNLOAD_POLL_MS)
                    if (voice.isDownloaded) {
                        if (!WeMessageApi.sendVoice(talker, voice.filePath, voice.durationMs)) {
                            WeLogger.w(TAG, "failed to send downloaded voice favorite $localId")
                        }
                        return@thread
                    }
                }
                WeLogger.w(TAG, "timed out downloading voice favorite $localId")
            } finally {
                pendingVoiceSends.remove(key)
            }
        }
    }
}
