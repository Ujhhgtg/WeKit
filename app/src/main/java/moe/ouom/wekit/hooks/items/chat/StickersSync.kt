package moe.ouom.wekit.hooks.items.chat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexConstructor
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.dexkit.abc.IResolvesDex
import moe.ouom.wekit.hooks.api.core.WeDatabaseApi
import moe.ouom.wekit.hooks.api.core.WeServiceApi
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.HostInfo
import moe.ouom.wekit.utils.KnownPaths
import moe.ouom.wekit.utils.ToastUtils
import moe.ouom.wekit.utils.createDirectoriesNoThrow
import moe.ouom.wekit.utils.logging.WeLogger
import moe.ouom.wekit.utils.polyfills.convToList
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

@HookItem(
    path = "聊天/贴纸包同步",
    desc = "从指定路径将所有图片注册为贴纸包\n搭配 Telegram Xposed 模块 StickersSync 使用, 或使用自带此功能的 (例如 Nagram) 的第三方客户端\n注意: 每张贴纸第一次加载由于需要计算 MD5 速度较慢, 后续加载得益于缓存与并发速度将大大加快 (~2000 个贴纸仅需 4 秒)"
)
object StickersSync : ClickableHookItem(), IResolvesDex {

    private val TAG = nameof(StickersSync)
    private const val STICKER_PACK_ID_PREFIX = "wekit.stickers.sync"
    private val ALLOWED_STICKER_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

    private data class StickerPack(
        val appPackId: String,
        val packId: String,
        val packName: String,
        val stickers: List<Any>
    )

    @Serializable
    private data class HashCache(
        val hashes: Map<String, String> = emptyMap()
    )

    private fun loadHashCache(packPath: Path): HashCache {
        val cacheFile = packPath.resolve(".hashes.json")
        return try {
            if (cacheFile.isRegularFile()) {
                Json.decodeFromString<HashCache>(cacheFile.readText())
            } else {
                HashCache()
            }
        } catch (ex: Exception) {
            WeLogger.e(TAG, "failed to load hash cache from ${cacheFile.absolutePathString()}", ex)
            HashCache()
        }
    }

    private fun saveHashCache(packPath: Path, cache: HashCache) {
        val cacheFile = packPath.resolve(".hashes.json")
        try {
            cacheFile.writeText(Json.encodeToString(cache))
        } catch (ex: Exception) {
            WeLogger.e(TAG, "failed to save hash cache to ${cacheFile.absolutePathString()}", ex)
        }
    }

    private suspend fun showToast(message: String) =
        withContext(Dispatchers.Main) {
        ToastUtils.showToast(message)
    }

    private val stickerPacks: List<StickerPack> by lazy {
        runBlocking {
            showToast("正在加载贴纸包...")

            withContext(Dispatchers.IO) {
                val packDirs = Files.list(stickersDir).filter { Files.isDirectory(it) }.convToList()
                if (packDirs.isEmpty()) {
                    showToast("未找到任何贴纸包")
                    return@withContext emptyList<StickerPack>()
                }

                // use a semaphore to limit the max amount of sticker packs being processed at the same time
                val semaphore = Semaphore(5)

                val packs = packDirs.map { packDir ->
                    async {
                        semaphore.withPermit {
                            val packDirName = packDir.name
                            val stickers = mutableListOf<Any>()

                            val hashCache = loadHashCache(packDir)
                            val newHashes = mutableMapOf<String, String>()

                            val images = packDir.walk()
                                .filter { it.isRegularFile() && it.extension.lowercase() in ALLOWED_STICKER_EXTENSIONS }
                                .toList()

                            images.forEach { path ->
                                try {
                                    val actualPath = if (path.extension.lowercase() == "webp") {
                                        convertWebpToPng(path) ?: return@forEach
                                    } else {
                                        path
                                    }

                                    val absPath = actualPath.absolutePathString()
                                    val fileName = actualPath.fileName.toString()

                                    val md5 = hashCache.hashes[fileName]
                                        ?: getEmojiMd5FromPath(HostInfo.application, absPath)
                                    newHashes[fileName] = md5

                                    val emojiThumb = getEmojiInfoByMd5(md5)
                                    methodSaveEmojiThumb.method.invoke(emojiThumb, null, true)
                                    val groupItemInfo = ctorGroupItemInfo.newInstance(emojiThumb, 2, "", 0)
                                    stickers.add(groupItemInfo)
                                } catch (e: Exception) {
                                    WeLogger.e(TAG, "Failed to load sticker: $path", e)
                                }
                            }

                            if (newHashes.isNotEmpty()) {
                                saveHashCache(packDir, HashCache(newHashes))
                            }

                            if (stickers.isNotEmpty()) {
                                WeLogger.i(
                                    TAG,
                                    "loaded pack '$packDirName' with ${stickers.size} stickers"
                                )
                                StickerPack(
                                    appPackId = "$STICKER_PACK_ID_PREFIX.$packDirName",
                                    packId = packDirName,
                                    packName = packDirName,
                                    stickers = stickers
                                )
                            } else null
                        }
                    }
                }.awaitAll().filterNotNull()

                val totalStickers = packs.sumOf { it.stickers.size }
                showToast("成功加载 ${packs.size} 个贴纸包, 共 $totalStickers 个贴纸")

                packs
            }
        }
    }

    private fun convertWebpToPng(webpPath: Path): Path? {
        return try {
            val pngPath = webpPath.resolveSibling("${webpPath.nameWithoutExtension}.png")

            if (pngPath.isRegularFile()) {
                return pngPath
            }

            val webpBitmap = BitmapFactory.decodeFile(webpPath.absolutePathString())
            if (webpBitmap == null) {
                WeLogger.e(TAG, "failed to decode WebP: ${webpPath.absolutePathString()}")
                return null
            }
            pngPath.toFile().outputStream().use { output ->
                webpBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            webpBitmap.recycle()
            pngPath
        } catch (ex: Exception) {
            WeLogger.e(TAG, "failed to convert WebP to PNG: ${webpPath.absolutePathString()}", ex)
            null
        }
    }

    private val methodGetEmojiGroupInfo by dexMethod()
    private val methodAddAllGroupItems by dexMethod()
    private val ctorGroupItemInfo by dexConstructor()
    private val classEmojiMgrImpl by dexClass()
    private val classEmojiStorageMgr by dexClass()
    private val classEmojiInfoStorage by dexClass()
    private val methodSaveEmojiThumb by dexMethod()
    private val ctorResourceLoadOptions by dexConstructor()

    private val stickersDir: Path by lazy { (KnownPaths.modulePata / "stickers")
        .createDirectoriesNoThrow() }

    private val emojiMgrImpl: Any by lazy {
        WeServiceApi.emojiFeatureService.asResolver()
            .firstMethod {
                returnType = classEmojiMgrImpl.clazz
            }
            .invoke()!!
    }

    fun getEmojiMd5FromPath(context: Context, path: String): String {
        return emojiMgrImpl
            .asResolver()
            .firstMethod {
                parameters(Context::class.java, String::class.java)
                returnType = String::class.java
            }
            .invoke(context, path) as String
    }

    private val emojiInfoStorage by lazy {
        val emojiStorageMgr = classEmojiStorageMgr.asResolver()
            .firstMethod {
                modifiers(Modifiers.STATIC)
                returnType = classEmojiStorageMgr.clazz
            }
            .invoke()!!
        emojiStorageMgr.asResolver()
            .firstMethod {
                returnType = classEmojiInfoStorage.clazz
            }
            .invoke()!!
    }

    fun getEmojiInfoByMd5(md5: String): Any {
        return emojiInfoStorage.asResolver()
            .firstMethod {
                parameters(String::class)
                returnType = "com.tencent.mm.storage.emotion.EmojiInfo"
            }
            .invoke(md5)!!
    }

    private const val PLACEHOLDER_PACK_URL = "https://example.com"
    private const val SEPERATOR = ";"

    override fun onEnable() {
        val emojiGroupInfoCls = "com.tencent.mm.storage.emotion.EmojiGroupInfo".toClass()

        @Suppress("UNCHECKED_CAST")
        methodGetEmojiGroupInfo.hookAfter { param ->
            if (param.result !is List<*>) {
                WeLogger.d(TAG, "param result is not list, skipped")
                return@hookAfter
            }

            // Inject each sticker pack
            stickerPacks.forEachIndexed { index, pack ->
                val stickersPackData = ContentValues()
                stickersPackData.put(
                    "packGrayIconUrl",
                    "$PLACEHOLDER_PACK_URL$SEPERATOR${pack.packName}"
                )
                stickersPackData.put(
                    "packIconUrl",
                    "$PLACEHOLDER_PACK_URL$SEPERATOR${pack.packName}"
                )
                stickersPackData.put("packName", pack.packName)
                stickersPackData.put("packStatus", 1)
                stickersPackData.put("productID", pack.appPackId)
                stickersPackData.put("status", 7)
                stickersPackData.put("sync", 2)

                val emojiGroupInfo = emojiGroupInfoCls.createInstance()
                emojiGroupInfoCls.getMethod(
                    "convertFrom",
                    ContentValues::class.java,
                    Boolean::class.java
                )
                .invoke(emojiGroupInfo, stickersPackData, true)

                (param.result as MutableList<Any?>).add(index, emojiGroupInfo)
            }
            WeLogger.i(TAG, "injected ${stickerPacks.size} sticker packs")
        }

        @Suppress("UNCHECKED_CAST")
        methodAddAllGroupItems.hookBefore { param ->
            val manager = param.args[0]
            if (manager == null) {
                WeLogger.w(TAG, "args[0] is null, skipped")
                return@hookBefore
            }

            val packConfig = manager.asResolver()
                .firstMethod {
                    superclass()
                    modifiers(Modifiers.FINAL)
                    returnType {
                        it != Boolean::class.java
                    }
                }
                .invoke()
            val emojiGroupInfo = packConfig!!.asResolver()
                .firstField {
                    type = "com.tencent.mm.storage.emotion.EmojiGroupInfo"
                }.get()!!
            val packId = emojiGroupInfo.asResolver()
                .firstField {
                    superclass()
                    name = "field_packName"
                }
                .get()!! as String

            // Find matching sticker pack
            val matchingPack = stickerPacks.find { it.packId == packId }
            if (matchingPack != null) {
                val stickerList = manager.asResolver().firstMethod {
                    superclass()
                    returnType = List::class
                }.invoke() as MutableList<Any?>
                stickerList.addAll(matchingPack.stickers)
            }
        }

        ctorResourceLoadOptions.hookAfter { param ->
            val url = param.args[0] as String
            if (url.startsWith(PLACEHOLDER_PACK_URL)) {
                val fResSource = param.thisObject.asResolver()
                    .firstField {
                        type { it isSubclassOf Enum::class }
                    }
                val newResSource = enumValueOfClass(fResSource.get()!!.javaClass, "LOCAL_PATH")
                fResSource.set(newResSource)
                param.thisObject.asResolver()
                    .firstField { type = Any::class }
                    .set((stickersDir / url.substringAfter(SEPERATOR) / ".pack_icon.png").absolutePathString())
                WeLogger.d(TAG, "intercepted")
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodGetEmojiGroupInfo.find(dexKit, descriptors) {
            matcher {
                paramTypes(Int::class.java)
                usingEqStrings("MicroMsg.emoji.EmojiGroupInfoStorage", "get Panel EmojiGroupInfo.")
            }
        }

        methodAddAllGroupItems.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("data")
                addInvoke {
                    usingEqStrings("checkScrollToPosition: ")
                }
            }
        }

        ctorGroupItemInfo.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("emojiInfo", "sosDocId")
            }
        }

        classEmojiMgrImpl.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.emoji.EmojiMgrImpl", "sendEmoji: context is null")
                    }
                }
            }
        }

        classEmojiStorageMgr.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.emoji.EmojiStorageMgr", "EmojiStorageMgr: %s")
                    }
                }
            }
        }

        classEmojiInfoStorage.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings(
                            "MicroMsg.emoji.EmojiInfoStorage",
                            "md5 is null or invalue. md5:%s"
                        )
                    }
                }
            }
        }

        methodSaveEmojiThumb.find(dexKit, descriptors) {
            matcher {
                declaredClass("com.tencent.mm.storage.emotion.EmojiInfo")
                usingEqStrings("save emoji thumb error")
            }
        }

        ctorResourceLoadOptions.find(dexKit, descriptors) {
            matcher {
                declaredClass {
                    modifiers = Modifier.FINAL
                    addFieldForType(Any::class.java)
                    addField {
                        type {
                            superClass("java.lang.Enum")
                        }
                    }
                    usingEqStrings("")
                }

                paramTypes(String::class.java)
            }
        }

        return descriptors
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onClick(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("贴纸包同步") },
                text = {
                    Column {
                        Row(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .clickable {
                                    stickerPacks.forEach { pack ->
                                        WeDatabaseApi.dbInstance.asResolver()
                                            .firstMethod {
                                                name = "delete"
                                                parameters(
                                                    String::class,
                                                    String::class,
                                                    Array<String>::class
                                                )
                                            }
                                            .invoke(
                                                "EmojiGroupInfo",
                                                "productID = ?",
                                                arrayOf(pack.appPackId)
                                            )
                                    }
                                    ToastUtils.showToast("已清除 ${stickerPacks.size} 个贴纸包缓存!")
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "清除应用数据库贴纸包缓存",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = dismiss) { Text("关闭") }
                })
        }
    }
}
