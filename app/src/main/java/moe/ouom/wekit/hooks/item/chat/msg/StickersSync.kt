package moe.ouom.wekit.hooks.item.chat.msg

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.toClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.ui.compose.showComposeDialog
import moe.ouom.wekit.utils.common.ToastUtils
import moe.ouom.wekit.utils.io.PathUtils
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

@HookItem(path = "聊天与消息/贴纸包同步", desc = "从指定路径将所有图片注册为贴纸包\n(搭配 Telegram Xposed 模块 StickersSync 使用, 或使用自带此功能的 (例如 Nagram) 的第三方客户端)")
class StickersSync : BaseClickableFunctionHookItem(), IDexFind {
    companion object {
        private const val TAG = "StickersSync"
        private const val STICKER_PACK_ID_PREFIX = "wekit.stickers.sync"
        private val ALLOWED_STICKER_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
    }

    private data class StickerPack(
        val appPackId: String,
        val packId: String,
        val packName: String,
        val stickers: MutableList<Any>
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

    private val stickerPacks: List<StickerPack> by lazy {
        // so that showToast() works
        Looper.prepare()

        val packs = mutableListOf<StickerPack>()
        val dir = stickersDir
        if (dir == null) {
            WeLogger.e(TAG, "could not get stickers directory, skipped")
            return@lazy packs
        }

        try {
            dir.createDirectories()
        }
        catch (_: java.nio.file.FileAlreadyExistsException) {
            WeLogger.i(TAG, "stickers directory is a symbolic link and already exists, ignoring exception")
        }
        catch (ex: Exception) {
            WeLogger.e(TAG, "failed to create stickers directory, skipped", ex)
            return@lazy packs
        }

        ToastUtils.showToast("正在加载贴纸包, 请稍候...")

        // Get all subdirectories as pack IDs
        val packDirs = dir.toFile().listFiles()?.filter { it.isDirectory } ?: emptyList()

        if (packDirs.isEmpty()) {
            WeLogger.w(TAG, "no pack directories found in ${dir.absolutePathString()}")
            ToastUtils.showToast("未找到任何贴纸包")
            return@lazy packs
        }

        var totalProcessed = 0
        packDirs.forEach { packDir ->
            val packId = packDir.name
            val packPath = packDir.toPath()
            val stickerList = mutableListOf<Any>()

            WeLogger.d(TAG, "processing pack: $packId")
            // ToastUtils.showToast("正在加载 '$packId'...")

            // Load existing hash cache
            val hashCache = loadHashCache(packPath)
            val newHashes = mutableMapOf<String, String>()

            val images = packPath.walk()
                .filter { path ->
                    path.isRegularFile() && path.extension.lowercase() in ALLOWED_STICKER_EXTENSIONS
                }
                .toList()

            images.forEach { path ->
                val actualPath = if (path.extension.lowercase() == "webp") {
                    convertWebpToPng(path) ?: return@forEach
                } else {
                    path
                }

                val absPath = actualPath.absolutePathString()
                val fileName = actualPath.fileName.toString()

                // Use cached hash if available, otherwise compute
                val md5 = hashCache.hashes[fileName] ?: run {
                    val computed = getEmojiMd5FromPath(HostInfo.getApplication(), absPath)
                    // WeLogger.d(TAG, "computed new hash for $fileName: $computed")
                    computed
                }
                newHashes[fileName] = md5

                val emojiThumb = getEmojiThumbByMd5(md5)
                methodSaveEmojiThumb.method.invoke(emojiThumb, null, true)
                val groupItemInfo = classGroupItemInfo.clazz
                    .getDeclaredConstructor("com.tencent.mm.api.IEmojiInfo".toClass(),
                        Int::class.java, String::class.java, Int::class.java)
                    .newInstance(emojiThumb, 2, "", 0)
                stickerList.add(groupItemInfo)
            }

            // Save updated hash cache
            if (newHashes.isNotEmpty()) {
                saveHashCache(packPath, HashCache(newHashes))
            }

            if (stickerList.isNotEmpty()) {
                packs.add(StickerPack(
                    appPackId = "$STICKER_PACK_ID_PREFIX.$packId",
                    packId = packId,
                    packName = packId,
                    stickers = stickerList
                ))
                totalProcessed += images.size
                WeLogger.i(TAG, "loaded pack '$packId' with ${images.size} stickers")
                // ToastUtils.showToast("成功加载 '${packId.take(10)}...', 含 ${images.size} 个表情, 共 $totalProcessed 个表情")
            }
        }

        ToastUtils.showToast("成功加载 ${packs.size} 个贴纸包, 共 $totalProcessed 个贴纸")
        WeLogger.i(TAG, "processed ${packs.size} packs with total $totalProcessed stickers")
        return@lazy packs
    }

    private fun convertWebpToPng(webpPath: Path): Path? {
        return try {
            val pngPath = webpPath.resolveSibling("${webpPath.nameWithoutExtension}.png")

            if (pngPath.isRegularFile()) {
                // prevent logcat io bottleneck
                // WeLogger.d(TAG, "PNG already exists, using: ${pngPath.absolutePathString()}")
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
            // prevent logcat io bottleneck
            // WeLogger.d(TAG, "converted WebP to PNG: ${pngPath.absolutePathString()}")
            pngPath
        } catch (ex: Exception) {
            WeLogger.e(TAG, "failed to convert WebP to PNG: ${webpPath.absolutePathString()}", ex)
            null
        }
    }

    private val methodGetEmojiGroupInfo by dexMethod()
    private val methodAddAllGroupItems by dexMethod()
    private val methodServiceManagerGetService by dexMethod()
    // this module doesn't provide a builtin dexConstructor, so i have to use dexClass, and then use .createInstance()
    private val classGroupItemInfo by dexClass()
    private val classEmojiFeatureService by dexClass()
    private val classEmojiMgrImpl by dexClass()
    private val classEmojiStorageMgr by dexClass()
    private val classEmojiInfoStorage by dexClass()
    private val methodSaveEmojiThumb by dexMethod()
    private val classSqliteDb by dexClass()
    private val classMmKernel by dexClass()
    private val classCoreStorage by dexClass()

    private val stickersDir: Path?
        get() = PathUtils.moduleDataPath?.resolve("stickers")

    private fun getServiceByClass(clazz: Class<*>): Any {
        return methodServiceManagerGetService.method.invoke(null, clazz)!!
    }

    private val emojiFeatureService: Any by lazy {
        val service = getServiceByClass(classEmojiFeatureService.clazz)
        service.asResolver()
            .firstMethod {
                returnType = classEmojiMgrImpl.clazz
            }
            .invoke()!!
    }

    private fun getEmojiMd5FromPath(context: Context, path: String): String {
        return emojiFeatureService
            .asResolver()
            .firstMethod {
                parameters(Context::class.java, String::class.java)
                returnType = String::class.java
            }
            .invoke(context, path) as String
    }

    private val emojiInfoStorage: Any by lazy {
        val emojiStorageMgr = classEmojiStorageMgr.clazz.asResolver()
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

    private fun getEmojiThumbByMd5(md5: String): Any {
        val emojiThumb = emojiInfoStorage.asResolver()
            .firstMethod {
                parameters(String::class)
                returnType = "com.tencent.mm.storage.emotion.EmojiInfo"
            }
            .invoke(md5)!!
        return emojiThumb
    }

    private fun getCoreStorage(): Any {
        val mmKernel = classMmKernel.clazz
        return mmKernel.asResolver()
            .firstMethod {
                returnType = classCoreStorage.clazz
                parameterCount = 0
            }
            .invoke()!!
    }

    private fun getSqliteDatabase(): Any {
        val db = getCoreStorage().asResolver()
            .firstField {
                type(classSqliteDb.clazz)
            }
            .get()!!
        return db.asResolver()
            .firstMethod {
                returnType = "com.tencent.wcdb.database.SQLiteDatabase"
                parameterCount = 0
            }
            .invoke()!!
    }

    override fun entry(classLoader: ClassLoader) {
        val emojiGroupInfoCls = "com.tencent.mm.storage.emotion.EmojiGroupInfo".toClass(classLoader)

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UNCHECKED_CAST")
        methodGetEmojiGroupInfo.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    WeLogger.i(TAG, "getEmojiGroupInfo called, result: ${param.result.javaClass.name}")

                    if (param.result !is java.util.List<*>) {
                        WeLogger.d(TAG, "param result is not list, skipped")
                        return@afterIfEnabled
                    }

                    // Inject each sticker pack
                    stickerPacks.forEachIndexed { index, pack ->
                        val stickersPackData = ContentValues()
                        stickersPackData.put(
                            "packGrayIconUrl",
                            "https://avatars.githubusercontent.com/u/49312623"
                        )
                        stickersPackData.put(
                            "packIconUrl",
                            "https://avatars.githubusercontent.com/u/49312623"
                        )
                        stickersPackData.put("packName", pack.packName)
                        stickersPackData.put("packStatus", 1)
                        stickersPackData.put("productID", pack.appPackId)
                        stickersPackData.put("status", 7)
                        stickersPackData.put("sync", 2)

                        val emojiGroupInfo = emojiGroupInfoCls.createInstance()
                        emojiGroupInfoCls.getMethod("convertFrom",
                            ContentValues::class.java, Boolean::class.java)
                            .invoke(emojiGroupInfo, stickersPackData, true)

                        (param.result as java.util.List<Any?>).add(index, emojiGroupInfo)
                    }
                    WeLogger.i(TAG, "injected ${stickerPacks.size} sticker packs")
                }
            }
        }

        methodAddAllGroupItems.toDexMethod {
            hook {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UNCHECKED_CAST")
                beforeIfEnabled { param ->
                    val manager = param.args[0]
                    if (manager == null) {
                        WeLogger.w(TAG, "args[0] is null, skipped")
                        return@beforeIfEnabled
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
                            type("com.tencent.mm.storage.emotion.EmojiGroupInfo".toClass(classLoader))
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
                        WeLogger.d(TAG, "current pack name: $packId, stickers count: ${matchingPack.stickers.size}")
                        val stickerList = manager.asResolver().firstMethod {
                            superclass()
                            returnType = List::class.java
                        }.invoke() as java.util.List<Any>
                        stickerList.addAll(matchingPack.stickers)
                    }
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
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

        classGroupItemInfo.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("emojiInfo", "sosDocId")
                    }
                }
            }
        }

        methodServiceManagerGetService.find(dexKit, descriptors) {
            matcher {
                modifiers(Modifier.STATIC)
                paramTypes(Class::class.java)
                usingEqStrings("calling getService(...)")
            }
        }

        classEmojiFeatureService.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.feature.emoji")
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.EmojiFeatureService", "[onAccountInitialized]")
                    }
                }
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
                        usingEqStrings("MicroMsg.emoji.EmojiInfoStorage", "md5 is null or invalue. md5:%s")
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

        classSqliteDb.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.DBInit", "initSysDB checkini:%b exist:%b db:%s ")
                    }
                }
            }
        }

        classMmKernel.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
                    }
                }
            }
        }

        classCoreStorage.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MMKernel.CoreStorage",
                            "CheckData path[%s] blocksize:%s blockcount:%s availcount:%s")
                    }
                }
            }
        }

        return descriptors
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onClick(context: Context?) {
        showComposeDialog(context) { onDismiss ->
            AlertDialog(onDismissRequest = onDismiss,
                title = { Text("贴纸表情同步") },
                text = {
                    Column {
                        Row(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val db = getSqliteDatabase()
                                    var deletedCount = 0
                                    stickerPacks.forEach { pack ->
                                        db.asResolver()
                                            .firstMethod {
                                                name = "delete"
                                                parameters(String::class, String::class, Array<String>::class)
                                            }
                                            .invoke("EmojiGroupInfo", "productID = ?", arrayOf(pack.appPackId))
                                        deletedCount++
                                    }
                                    ToastUtils.showToast("已清除 $deletedCount 个贴纸包缓存!")
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("清除应用数据库贴纸包缓存", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                })
        }
    }
}