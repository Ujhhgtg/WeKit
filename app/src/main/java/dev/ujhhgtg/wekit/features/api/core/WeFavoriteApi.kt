package dev.ujhhgtg.wekit.features.api.core

import android.content.Context
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.FavInfoProto
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.lang.reflect.Modifier
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

/** Reads favorite (收藏) items and sends them to chats through WeChat's own FavSendLogic. */
object WeFavoriteApi : ApiFeature(), IResolveDex {

    override val technicalId = "收藏服务"
    override val nameRes = R.string.feature_we_favorite_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_favorite_api_description

    private const val TAG = "WeFavoriteApi"

    private val classPluginFav by dexClass {
        matcher {
            usingEqStrings("MicroMsg.Fav.PluginFav", "init fav storage")
        }
    }

    /** `FavItemInfoStorage.getByLocalId(long)`; the WCDB storage implements the same interface method. */
    private val methodFavStorageGetByLocalId by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Fav.FavItemInfoStorage", "getBtLocalId, but db is null, return")
        }
    }

    /** `FavSendLogic.send(Context, toUser, extra, isBizChat, FavItemInfo, Runnable)`. */
    private val methodSendFavItem by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.FavSendLogic", "want to send fav msg, but context is null")
            }
            modifiers(Modifier.STATIC)
            paramTypes(
                "android.content.Context", "java.lang.String", "java.lang.String", "boolean",
                methodFavStorageGetByLocalId.data.returnTypeName, "java.lang.Runnable",
            )
        }
    }

    private val favStorage: Any
        get() {
            val storageClass = methodFavStorageGetByLocalId.method.declaringClass
            val getter = classPluginFav.reflekt().firstMethod {
                parameterCount = 0
                returnType { it.isInterface && it.isAssignableFrom(storageClass) }
            }
            val service = WeServiceApi.getServiceByClass(
                classPluginFav.clazz.interfaces.first { iface -> iface.methods.any { it.name == getter.name && it.parameterCount == 0 } }
            )
            return getter.self.invoke(service)!!
        }

    /** The native `FavItemInfo` with [localId], or null when there is none. */
    fun getFavItem(localId: Long): Any? =
        favStorage.reflekt().firstMethod {
            name = methodFavStorageGetByLocalId.method.name
            parameters(Long::class.javaPrimitiveType!!)
        }.invoke(localId)

    /** A cached favorite voice file with its duration. */
    data class FavoriteVoice(val filePath: String, val durationMs: Int)

    /** The cached voice of a voice favorite (type 3), or null for other favorite types. */
    @OptIn(ExperimentalSerializationApi::class)
    fun voiceOf(favItem: Any): FavoriteVoice? {
        val type = favItem.reflekt().firstField { name = "field_type"; superclass() }.get() as Int
        if (type != 3) return null
        val favProto = favItem.reflekt().firstField { name = "field_favProto"; superclass() }.get()!!
        val bytes = favProto.reflekt().firstMethod { name = "getData"; superclass() }.invoke() as ByteArray
        val voiceInfo = ProtoBuf.decodeFromByteArray<FavInfoProto>(bytes).voiceInfo
        val cacheName = voiceInfo.fileCacheName
        val bucketId = cacheName.hashCode() and 0xFF
        val cacheDir = RuntimeConfig.userDataDir / "favorite" / bucketId.toString()
        return FavoriteVoice((cacheDir / "$cacheName.${voiceInfo.fileCacheType}").absolutePathString(), voiceInfo.duration)
    }

    /**
     * Sends favorite [localId] to [talker]. WeChat's FavSendLogic can't send voice favorites, so
     * those are sent as voice messages from the local favorite cache (as Hchat does).
     */
    fun send(talker: String, localId: Long): Boolean {
        val item = getFavItem(localId) ?: run {
            WeLogger.w(TAG, "favorite $localId not found")
            return false
        }
        voiceOf(item)?.let { voice ->
            if (!File(voice.filePath).isFile) {
                WeLogger.w(TAG, "voice favorite $localId isn't downloaded yet: ${voice.filePath}")
                return false
            }
            return WeMessageApi.sendVoice(talker, voice.filePath, voice.durationMs)
        }
        val context: Context = getTopMostActivity() ?: HostInfo.application
        methodSendFavItem.method.invoke(null, context, talker, "", false, item, null)
        WeLogger.i(TAG, "sent favorite $localId to $talker")
        return true
    }
}
