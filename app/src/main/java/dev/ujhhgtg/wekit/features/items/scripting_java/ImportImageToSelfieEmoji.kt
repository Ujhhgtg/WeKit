package dev.ujhhgtg.wekit.features.items.scripting_java

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Emoticon
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.tencent.mm.plugin.gif.MMWXGFJNI
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.EmoticonIcon
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.outputStream

@Feature(name = "自拍表情导入图片", categories = ["脚本 (Java)"], description = "在自拍表情拍摄页添加导入入口，复用微信表情相册选择与编辑流程")
object ImportImageToSelfieEmoji : SwitchFeature(), IResolveDex,
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "ImportImageToSelfieEmoji"
    private const val MENU_ITEM_TO_SELFIE = 777012
    private const val EMOJI_MEDIA_PICKER_UI = "com.tencent.mm.plugin.emoji.ui.picker.EmojiMediaPickerUI"
    private const val EXTRA_SELFIE_IMPORT = "wekit_selfie_emoji_import"
    private const val IMPORT_BUTTON_TAG = "wekit_selfie_emoji_import_button"
    private const val SELFIE_ACTIVITY_ID = "Selfie:wekit_import"
    private const val MARKER_APP_ID = "wekit_selfie_emoji_import"
    private const val EMOJI_INFO_CLASS = "com.tencent.mm.storage.emotion.EmojiInfo"
    private const val FAVORITE_CATALOG = 81

    private val classEmojiCaptureUi by dexClass()
    private val classEmojiEditorActivity by dexClass()
    private val classEmojiAddCustomDialogUi by dexClass()
    private val classEmojiStorageMgr by dexClass()
    private val classEmojiInfoStorage by dexClass()
    private val classNetSceneUploadEmoji by dexClass()
    private val classEmojiFileEncryptMgr by dexClass()
    private val methodEditorLaunchAddCustom by dexMethod()
    private val methodAddCustomSuccess by dexMethod()
    private val methodEmojiStorageMgrGetInstance by dexMethod()
    private val methodEmojiStorageMgrGetInfoStorage by dexMethod()
    private val methodEmojiInfoStorageUpdate by dexMethod()
    private val methodEmojiInfoStorageGetByMd5 by dexMethod()
    private val methodEmojiInfoStorageGetFavoriteList by dexMethod()
    private val methodEmojiFeatureServiceSendEmoji by dexMethod()
    private val methodReadEmojiBytes by dexMethod()

    private val pendingSelfieAddMd5 = ThreadLocal<String?>()
    private val runtimeFields = ConcurrentHashMap<Pair<Class<*>, String>, CachedField>()
    private val navigationBarHeightResId by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HostInfo.application.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    }

    private val emojiInfoStorage: Any? by lazy {
        val storageManager = methodEmojiStorageMgrGetInstance.method.invoke(null) ?: return@lazy null
        methodEmojiStorageMgrGetInfoStorage.method.invoke(storageManager)
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        classEmojiCaptureUi.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.emojicapture.ui")
            matcher {
                usingEqStrings("MicroMsg.EmojiCaptureUI")
            }
        }

        classEmojiEditorActivity.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.emoji.ui")
            matcher {
                usingEqStrings("MicroMsg.EmojiEditorActivity", "generateEmoji: ")
            }
        }

        classEmojiAddCustomDialogUi.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.emoji.ui")
            matcher {
                usingEqStrings("MicroMsg.emoji.EmojiAddCustomDialogUI", "start upload emoji")
            }
        }

        classEmojiStorageMgr.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.emoji.EmojiStorageMgr", "EmojiStorageMgr: %s")
                    }
                }
            }
        }

        classEmojiInfoStorage.find(dexKit) {
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

        classEmojiFileEncryptMgr.find(dexKit) {
            matcher {
                methods {
                    add {
                        usingEqStrings(
                            "MicroMsg.emoji.EmojiFileEncryptMgr",
                            "decode emoji file failed. path is no exist :%s "
                        )
                    }
                }
            }
        }

        methodEditorLaunchAddCustom.find(dexKit) {
            matcher {
                declaredClass(classEmojiEditorActivity.clazz)
                paramTypes(Intent::class.java)
                returnType(Void.TYPE)
            }
        }

        methodAddCustomSuccess.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classEmojiAddCustomDialogUi.clazz)
                modifiers = Modifier.STATIC
                paramTypes(
                    classEmojiAddCustomDialogUi.clazz.name,
                    String::class.java.name,
                    null,
                    String::class.java.name,
                    String::class.java.name
                )
                returnType(Void.TYPE)
                usingEqStrings("MicroMsg.emoji.EmojiAddCustomDialogUI", "update emoji info for %s")
            }
        }

        methodEmojiStorageMgrGetInstance.find(dexKit) {
            matcher {
                declaredClass(classEmojiStorageMgr.clazz)
                modifiers = Modifier.STATIC
                paramTypes()
                returnType(classEmojiStorageMgr.clazz)
            }
        }

        methodEmojiStorageMgrGetInfoStorage.find(dexKit) {
            matcher {
                declaredClass(classEmojiStorageMgr.clazz)
                paramTypes()
                returnType(classEmojiInfoStorage.clazz)
            }
        }

        methodEmojiInfoStorageUpdate.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classEmojiInfoStorage.clazz)
                paramTypes(EMOJI_INFO_CLASS)
                returnType(Boolean::class.java)
                usingEqStrings("update %s, temp:%s")
            }
        }

        methodEmojiInfoStorageGetByMd5.find(dexKit) {
            matcher {
                declaredClass(classEmojiInfoStorage.clazz)
                paramTypes(String::class.java)
                returnType(EMOJI_INFO_CLASS)
                usingEqStrings("md5 is null or invalue. md5:%s")
            }
        }

        methodEmojiInfoStorageGetFavoriteList.find(dexKit) {
            matcher {
                declaredClass(classEmojiInfoStorage.clazz)
                paramTypes(Boolean::class.javaPrimitiveType)
                returnType(List::class.java)
            }
        }

        methodReadEmojiBytes.find(dexKit) {
            matcher {
                declaredClass = EMOJI_INFO_CLASS
                paramTypes("int", "int")
                returnType("byte[]")
                usingEqStrings("MicroMsg.emoji.EmojiInfo", "exception:%s")
            }
        }

        methodEmojiFeatureServiceSendEmoji.find(dexKit, allowFailure = true) {
            matcher {
                paramTypes(
                    String::class.java.name,
                    EMOJI_INFO_CLASS,
                    "com.tencent.mm.storage.f8",
                    "com.tencent.mm.plugin.msg.MsgIdTalker",
                    "ou4.b",
                    "int"
                )
                returnType(Void.TYPE)
                usingEqStrings("MicroMsg.EmojiFeatureService", "NetSceneUploadEmoji: msgId = %s, md5 %s, len %s")
            }
        }

        classNetSceneUploadEmoji.find(dexKit, allowFailure = true) {
            searchPackages("xw1")
            matcher {
                usingEqStrings("MicroMsg.emoji.NetSceneUploadEmoji", "/cgi-bin/micromsg-bin/sendemoji")
            }
        }
    }

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
        hookEditorIntentBridge()
        hookAddCustomSaveAsFavorite()
        hookAddCustomBackupSaveAsFavorite()
        hookFavoriteListForSelfieAdd()
        hookEmojiFeatureServiceSendSelfieActivityId()
        hookSendSelfieActivityId()

        classEmojiCaptureUi.clazz.hookAfterOnCreate {
            val activity = thisObject as? Activity ?: return@hookAfterOnCreate
            activity.window.decorView.post {
                addImportButton(activity)
            }
        }
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                MENU_ITEM_TO_SELFIE,
                "转自拍",
                EmoticonIcon,
                MaterialSymbols.Outlined.Emoticon,
                { msgInfo ->
                    msgInfo.type?.isSticker == true &&
                        !msgInfo.imagePath.isNullOrBlank()
                }
            ) { _, chattingContext, msgInfo ->
                msgInfo.imagePath?.let { convertExistingEmojiToSelfieFavorite(chattingContext.activity, it) }
            }
        )
    }

    private fun addImportButton(activity: Activity) {
        val decorView = activity.window.decorView as? FrameLayout
        if (decorView == null) {
            WeLogger.w(TAG, "activity decorView is not a FrameLayout: ${activity.javaClass.name}")
            return
        }
        if (decorView.findViewWithTag<TextView>(IMPORT_BUTTON_TAG) != null) return

        val button = TextView(activity).apply {
            tag = IMPORT_BUTTON_TAG
            text = "导入"
            contentDescription = "导入图片"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(18.dpToPx(activity), 8.dpToPx(activity), 18.dpToPx(activity), 8.dpToPx(activity))
            minWidth = 72.dpToPx(activity)
            minHeight = 42.dpToPx(activity)
            elevation = 8.dpToPx(activity).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22.dpToPx(activity).toFloat()
                setColor(0xB3000000.toInt())
            }
            setOnClickListener {
                openEmojiMediaPicker(activity)
            }
        }

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = 22.dpToPx(activity)
            bottomMargin = 132.dpToPx(activity) + activity.navigationBarHeight()
        }

        decorView.addView(button, layoutParams)
        WeLogger.i(TAG, "added import button to ${activity.javaClass.name}")
    }

    private fun openEmojiMediaPicker(activity: Activity) {
        val intent = Intent().apply {
            setClassName(activity.packageName, EMOJI_MEDIA_PICKER_UI)
            putExtra("query_source_type", 11)
            putExtra("appId", MARKER_APP_ID)
            putExtra("page", "")
            putExtra("query", "")
            putExtra(EXTRA_SELFIE_IMPORT, true)
        }

        runCatching {
            activity.startActivity(intent)
        }.onFailure {
            WeLogger.e(TAG, "failed to open EmojiMediaPickerUI", it)
            showToast(activity, "打开导入页面失败")
        }
    }

    private fun hookEditorIntentBridge() {
        classEmojiEditorActivity.clazz.hookAfterOnCreate {
            val activity = thisObject as? Activity ?: return@hookAfterOnCreate
            if (isSelfieImportIntent(activity.intent)) {
                activity.intent.putExtra(EXTRA_SELFIE_IMPORT, true)
                WeLogger.i(TAG, "marked EmojiEditorActivity as selfie import")
            }
        }

        methodEditorLaunchAddCustom.hookBefore {
            val activity = thisObject as? Activity ?: return@hookBefore
            if (!isSelfieImportIntent(activity.intent)) return@hookBefore

            val intent = args.getOrNull(0) as? Intent ?: return@hookBefore
            val md5 = intent.getStringExtra("extra_id")
            intent.putExtra(EXTRA_SELFIE_IMPORT, true)
            intent.putExtra("key_is_selfie", true)
            intent.putExtra("is_upload_wxam", true)
            intent.putExtra("hide_added_toast", true)
            intent.putExtra("extra_move_to_top", true)
            WeLogger.i(TAG, "marked AddCustom launch as selfie import: $md5")
        }
    }

    private fun hookAddCustomSaveAsFavorite() {
        if (methodAddCustomSuccess.isPlaceholder) return
        methodAddCustomSuccess.hookAfter {
            val addCustomActivity = args.getOrNull(0) as? Activity ?: return@hookAfter
            if (!isSelfieImportIntent(addCustomActivity.intent)) return@hookAfter

            val md5 = args.getOrNull(1) as? String
                ?: addCustomActivity.intent.getStringExtra("extra_id")
                ?: return@hookAfter
            val emojiInfo = getEmojiInfoByMd5(md5) ?: return@hookAfter
            if (!markEmojiAsFavoriteSelfie(emojiInfo, md5)) return@hookAfter
            WeLogger.i(TAG, "marked imported favorite emoji with selfie activity id: $md5")
        }
    }

    private fun hookAddCustomBackupSaveAsFavorite() {
        classEmojiAddCustomDialogUi.clazz.reflekt()
            .firstMethod { name = "onSceneEnd" }
            .hookAfter {
                val addCustomActivity = thisObject as? Activity ?: return@hookAfter
                if (!isSelfieImportIntent(addCustomActivity.intent)) return@hookAfter

                val scene = args.getOrNull(3) ?: return@hookAfter
                val type = scene.reflekt()
                    .firstMethod { name = "getType" }
                    .invoke() as? Int ?: return@hookAfter
                if (type != 698) return@hookAfter

                val errType = args.getOrNull(0) as? Int ?: return@hookAfter
                val errCode = args.getOrNull(1) as? Int ?: return@hookAfter
                if (errType != 0 || errCode != 0) return@hookAfter

                val md5 = addCustomActivity.intent.getStringExtra("extra_id") ?: return@hookAfter
                val emojiInfo = getEmojiInfoByMd5(md5) ?: return@hookAfter
                if (!markEmojiAsFavoriteSelfie(emojiInfo, md5)) return@hookAfter
                WeLogger.i(TAG, "marked backup-saved favorite emoji with selfie activity id: $md5")
            }
    }

    private fun hookFavoriteListForSelfieAdd() {
        methodEmojiInfoStorageGetFavoriteList.hookAfter {
            val md5 = pendingSelfieAddMd5.get() ?: return@hookAfter
            val favoriteList = result as? List<*> ?: return@hookAfter
            result = favoriteList.filterNot {
                runCatching { it?.reflekt()?.firstMethod { name = "getMd5" }?.invoke() as? String }
                    .getOrNull()
                    .equals(md5, ignoreCase = true)
            }
        }

        classEmojiAddCustomDialogUi.clazz.hookBeforeOnCreate {
            val activity = thisObject as? Activity ?: return@hookBeforeOnCreate
            if (!isSelfieImportIntent(activity.intent)) return@hookBeforeOnCreate
            pendingSelfieAddMd5.set(activity.intent.getStringExtra("extra_id"))
        }

        classEmojiAddCustomDialogUi.clazz.hookAfterOnCreate {
            val activity = thisObject as? Activity ?: return@hookAfterOnCreate
            if (!isSelfieImportIntent(activity.intent)) return@hookAfterOnCreate
            pendingSelfieAddMd5.remove()
        }
    }

    private fun isSelfieImportIntent(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(EXTRA_SELFIE_IMPORT, false) == true ||
            intent?.getStringExtra("appId") == MARKER_APP_ID
    }

    private fun convertExistingEmojiToSelfieFavorite(activity: Activity, md5: String) {
        val emojiInfo = runCatching { getEmojiInfoByMd5(md5) }.getOrNull()
        if (emojiInfo == null) {
            showToast(activity, "未找到表情信息")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val sticker = decodeStickerFileForEditor(emojiInfo, md5)
                val tempPath = KnownPaths.moduleCache / "selfie_emoji_import_${md5}.${sticker.extension}"
                tempPath.outputStream().use { out -> out.write(sticker.bytes) }
                tempPath.absolutePathString() to sticker.extension
            }.onSuccess { (mediaPath, extension) ->
                activity.runOnUiThread {
                    openEmojiEditorForSelfieImport(activity, emojiInfo, mediaPath, extension)
                }
            }.onFailure {
                WeLogger.e(TAG, "failed to prepare sticker file for selfie import: $md5", it)
                activity.runOnUiThread {
                    showToast(activity, "未找到表情文件")
                }
            }
        }
    }

    private fun openEmojiEditorForSelfieImport(
        activity: Activity,
        emojiInfo: Any,
        mediaPath: String,
        extension: String
    ) {
        val intent = Intent(activity, classEmojiEditorActivity.clazz).apply {
            putExtra("media_path", mediaPath)
            putExtra("is_video", false)
            putExtra("is_gif", extension == "gif")
            putExtra("query_source_type", 11)
            putExtra(EXTRA_SELFIE_IMPORT, true)
            putExtra("appId", MARKER_APP_ID)
            putExtra("page", "")
            putExtra("query", "")
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        runCatching {
            activity.startActivity(intent)
        }.onFailure {
            WeLogger.e(TAG, "failed to open EmojiEditorActivity for prepared emoji: $mediaPath", it)
            showToast(activity, "打开转自拍页面失败")
        }
    }

    private fun markEmojiAsFavoriteSelfie(emojiInfo: Any, md5: String): Boolean {
        return runCatching {
            val storage = emojiInfoStorage ?: return@runCatching false
            emojiInfo.setFieldValue("field_catalog", FAVORITE_CATALOG)
            emojiInfo.setFieldValue("field_activityid", SELFIE_ACTIVITY_ID)
            patchEmojiInfoContentForSelfie(emojiInfo, SELFIE_ACTIVITY_ID)
            updateEmojiInfoStorage(storage, emojiInfo)
        }.onFailure {
            WeLogger.e(TAG, "failed to mark imported favorite emoji: $md5", it)
        }.getOrDefault(false)
    }

    private fun hookEmojiFeatureServiceSendSelfieActivityId() {
        if (!methodEmojiFeatureServiceSendEmoji.isPlaceholder) {
            methodEmojiFeatureServiceSendEmoji.hookBefore {
                prepareSelfieEmojiInfoForSend(args.getOrNull(1))
            }
            return
        }

        val sendEmojiMethod = runCatching {
            val serviceClass = Class.forName(
                "com.tencent.mm.feature.emoji.b0",
                false,
                ClassLoaders.HOST
            )
            serviceClass.declaredMethods.firstOrNull { it.isEmojiFeatureServiceSendEmojiMethod() }
        }.onFailure {
            WeLogger.w(TAG, "EmojiFeatureService send method not resolved, skipping send-time content patch", it)
        }.getOrNull() ?: return

        sendEmojiMethod.isAccessible = true
        sendEmojiMethod.hookBefore {
            prepareSelfieEmojiInfoForSend(args.getOrNull(1))
        }
    }

    private fun prepareSelfieEmojiInfoForSend(emojiInfo: Any?) {
        if (emojiInfo == null) return
        val activityId = emojiInfo.getFieldValue("field_activityid") as? String ?: return
        if (!activityId.startsWith("Selfie")) return

        emojiInfo.setFieldValue("field_activityid", activityId)
        patchEmojiInfoContentForSelfie(emojiInfo, activityId)

        val md5 = emojiInfo.getEmojiMd5()
        WeLogger.i(TAG, "prepared selfie activity id before sending emoji: $md5")
    }

    private fun hookSendSelfieActivityId() {
        val uploadEmojiClass = resolveUploadEmojiClass() ?: return
        uploadEmojiClass.declaredConstructors
            .filter { it.isUploadEmojiConstructor() }
            .forEach { constructor ->
                constructor.isAccessible = true
                constructor.hookAfter {
                    val emojiInfo = args.getOrNull(2) ?: return@hookAfter
                    val activityId = emojiInfo.getFieldValue("field_activityid") as? String ?: return@hookAfter
                    if (!activityId.startsWith("Selfie")) return@hookAfter

                    patchEmojiInfoContentForSelfie(emojiInfo, activityId)
                    patchUploadEmojiRequestContent(thisObject, emojiInfo, activityId)

                    val md5 = emojiInfo.getEmojiMd5()
                    WeLogger.i(TAG, "patched selfie activity id for outgoing emoji request: $md5")
                }
            }
    }

    private fun resolveUploadEmojiClass(): Class<*>? {
        if (!classNetSceneUploadEmoji.isPlaceholder) {
            return classNetSceneUploadEmoji.clazz
        }

        return runCatching {
            Class.forName("xw1.y", false, ClassLoaders.HOST)
        }.onFailure {
            WeLogger.w(TAG, "NetSceneUploadEmoji class not resolved, skipping outgoing activity id patch", it)
        }.getOrNull()
    }

    private fun java.lang.reflect.Constructor<*>.isUploadEmojiConstructor(): Boolean {
        val params = parameterTypes
        return params.size == 6 &&
            params[0] == String::class.java &&
            params[1] == String::class.java &&
            params[2].name == EMOJI_INFO_CLASS &&
            params[3] == Long::class.javaPrimitiveType &&
            params[4] == Int::class.javaPrimitiveType &&
            params[5] == Int::class.javaPrimitiveType
    }

    private fun java.lang.reflect.Method.isEmojiFeatureServiceSendEmojiMethod(): Boolean {
        val params = parameterTypes
        return name == "nh" &&
            params.size == 6 &&
            params[0] == String::class.java &&
            params[1].name == EMOJI_INFO_CLASS &&
            params[2].name == "com.tencent.mm.storage.f8" &&
            params[3].name == "com.tencent.mm.plugin.msg.MsgIdTalker" &&
            params[4].name == "ou4.b" &&
            params[5] == Int::class.javaPrimitiveType
    }

    private fun patchUploadEmojiRequestContent(netScene: Any, emojiInfo: Any, activityId: String) {
        val reqResp = netScene.getFieldValue("f454806d") ?: return
        val requestWrapper = reqResp.getFieldValue("f71838a") ?: return
        val request = requestWrapper.getFieldValue("f71811a") ?: return
        val items = request.getFieldValue("f323229e") as? MutableList<*> ?: return
        val uploadEmojiInfo = items.firstOrNull() ?: return
        val content = uploadEmojiInfo.getFieldValue("f332439m") as? String ?: ""
        val patchedContent = patchOrBuildSelfieEmojiXml(content, emojiInfo, activityId)
        if (patchedContent != content) {
            uploadEmojiInfo.setFieldValue("f332439m", patchedContent)
        }

        val extCommonInfo = uploadEmojiInfo.getFieldValue("f332446t") as? String ?: ""
        val patchedExtCommonInfo = patchEmojiXmlActivityId(extCommonInfo, activityId)
        if (patchedExtCommonInfo.isNotBlank() && patchedExtCommonInfo != extCommonInfo) {
            uploadEmojiInfo.setFieldValue("f332446t", patchedExtCommonInfo)
        }
    }

    private fun patchEmojiInfoContentForSelfie(emojiInfo: Any, activityId: String) {
        val content = emojiInfo.getFieldValue("field_content") as? String ?: ""
        val patchedContent = patchOrBuildSelfieEmojiXml(content, emojiInfo, activityId)
        if (patchedContent != content) {
            emojiInfo.setFieldValue("field_content", patchedContent)
        }
    }

    private fun patchOrBuildSelfieEmojiXml(xml: String, emojiInfo: Any, activityId: String): String {
        if (xml.hasNestedMsgEnvelope()) {
            return buildSelfieEmojiXml(emojiInfo, activityId)
        }
        return patchEmojiXmlActivityId(xml, activityId)
            .ifBlank { buildSelfieEmojiXml(emojiInfo, activityId) }
    }

    private fun patchEmojiXmlActivityId(xml: String, activityId: String): String {
        if (xml.isBlank()) return xml
        val doubleQuotedActivityId = Regex("""activityid="[^"]*"""")
        if (doubleQuotedActivityId.containsMatchIn(xml)) {
            return doubleQuotedActivityId.replace(
                xml,
                "activityid=\"${activityId.escapeXmlAttr()}\""
            )
        }
        val singleQuotedActivityId = Regex("""activityid='[^']*'""")
        if (singleQuotedActivityId.containsMatchIn(xml)) {
            return singleQuotedActivityId.replace(
                xml,
                "activityid='${activityId.escapeXmlAttr()}'"
            )
        }
        val emojiTagStart = xml.indexOf("<emoji")
        if (emojiTagStart < 0) return ""
        val emojiTagEnd = xml.indexOf('>', emojiTagStart)
        if (emojiTagEnd < 0) return ""
        return xml.substring(0, emojiTagEnd) +
            " activityid=\"${activityId.escapeXmlAttr()}\"" +
            xml.substring(emojiTagEnd)
    }

    private fun buildSelfieEmojiXml(emojiInfo: Any, activityId: String): String {
        val md5 = emojiInfo.getEmojiMd5()
        if (md5.isBlank()) return ""
        return buildString {
            append("<msg><emoji")
            appendXmlAttr("fromusername", "")
            appendXmlAttr("tousername", "")
            appendXmlAttr("type", emojiInfo.getIntFieldValue("field_type").toString())
            appendXmlAttr("idbuffer", emojiInfo.getStringFieldValue("field_svrid"))
            appendXmlAttr("md5", md5)
            appendXmlAttr("len", emojiInfo.getIntFieldValue("field_size").toString())
            appendXmlAttr("androidmd5", md5)
            appendXmlAttr("androidlen", emojiInfo.getIntFieldValue("field_size").toString())
            appendXmlAttr("productid", emojiInfo.getStringFieldValue("field_groupId"))
            appendXmlAttr("cdnurl", emojiInfo.getStringFieldValue("field_cdnUrl"))
            appendXmlAttr("designerid", emojiInfo.getStringFieldValue("field_designerID"))
            appendXmlAttr("thumburl", emojiInfo.getStringFieldValue("field_thumbUrl"))
            appendXmlAttr("encrypturl", emojiInfo.getStringFieldValue("field_encrypturl"))
            appendXmlAttr("aeskey", emojiInfo.getStringFieldValue("field_aeskey"))
            appendXmlAttr("externurl", emojiInfo.getStringFieldValue("field_externUrl"))
            appendXmlAttr("externmd5", emojiInfo.getStringFieldValue("field_externMd5"))
            appendXmlAttr("width", emojiInfo.getIntFieldValue("field_width").toString())
            appendXmlAttr("height", emojiInfo.getIntFieldValue("field_height").toString())
            appendXmlAttr("tpurl", emojiInfo.getStringFieldValue("field_tpurl"))
            appendXmlAttr("tpauthkey", emojiInfo.getStringFieldValue("field_tpauthkey"))
            appendXmlAttr("attachedtext", emojiInfo.getStringFieldValue("field_attachedText"))
            appendXmlAttr("attachedtextcolor", emojiInfo.getStringFieldValue("field_attachTextColor"))
            appendXmlAttr("lensid", emojiInfo.getStringFieldValue("field_lensId"))
            appendXmlAttr("activityid", activityId)
            append("></emoji>")
            append("</msg>")
        }
    }

    private fun getEmojiInfoByMd5(md5: String): Any? {
        val storage = emojiInfoStorage ?: return null
        return methodEmojiInfoStorageGetByMd5.method.invoke(storage, md5)
    }

    private data class StickerFile(
        val bytes: ByteArray,
        val extension: String,
    )

    private data class CachedField(val field: Field?)

    private fun decodeStickerFileForEditor(emojiInfo: Any, md5: String): StickerFile {
        val candidates = listOfNotNull(
            runCatching { decodeEmojiBytesByEncryptMgr(emojiInfo) }.getOrNull()?.takeIf { it.isNotEmpty() },
            readEmojiOriginalBytes(emojiInfo),
        )
        for (bytes in candidates) {
            bytes.toStickerFile()?.let { return it }
        }
        error("unsupported sticker bytes, md5=$md5, candidates=${candidates.map { it.size }}")
    }

    private fun ByteArray.toStickerFile(): StickerFile? {
        detectImageExtension(this)?.let {
            return StickerFile(this, it)
        }

        if (isWxam(this)) {
            convertWxamToSticker(this)?.let { return it }
        }

        return convertWxamToSticker(this)
    }

    private fun convertWxamToSticker(bytes: ByteArray): StickerFile? {
        val gifBytes = runCatching { MMWXGFJNI.nativeWxamToGif(bytes) }.getOrNull()
        if (gifBytes != null && gifBytes.isNotEmpty() && isGif(gifBytes)) {
            return StickerFile(gifBytes, "gif")
        }

        val imageBytes = decodeWxamToPicBytes(bytes)
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            detectImageExtension(imageBytes)?.let { extension ->
                return StickerFile(imageBytes, extension)
            }
        }

        return null
    }

    private fun isWxam(bytes: ByteArray): Boolean {
        return runCatching {
            MMWXGFJNI::class.java
                .getDeclaredMethod("isWxGF", ByteArray::class.java, Int::class.javaPrimitiveType)
                .invoke(null, bytes, bytes.size) as? Boolean
        }.getOrDefault(false) == true
    }

    private fun decodeWxamToPicBytes(bytes: ByteArray): ByteArray? {
        return runCatching {
            MMWXGFJNI::class.java
                .getDeclaredMethod(
                    "wxam2PicBuf",
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                .invoke(null, bytes, 0, 6) as? ByteArray
        }.getOrNull()
    }

    private fun readEmojiOriginalBytes(emojiInfo: Any): ByteArray? {
        val start = emojiInfo.getIntFieldValue("field_start")
        val size = emojiInfo.getIntFieldValue("field_size")
        if (size <= 0) return null
        return runCatching {
            methodReadEmojiBytes.method.invoke(emojiInfo, start, size) as? ByteArray
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeEmojiBytesByEncryptMgr(emojiInfo: Any): ByteArray {
        val emojiFileEncryptMgr = classEmojiFileEncryptMgr.clazz.reflekt()
            .firstMethod {
                modifiers(Modifiers.STATIC)
                parameterCount = 0
            }
            .invokeStatic()!!
        return emojiFileEncryptMgr.reflekt()
            .firstMethod {
                parameters("com.tencent.mm.api.IEmojiInfo")
                returnType = ByteArray::class.java
            }
            .invoke(emojiInfo) as ByteArray
    }

    private fun detectImageExtension(bytes: ByteArray): String? {
        return when {
            isGif(bytes) -> "gif"
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
            bytes.size >= 12 &&
                bytes.startsWithAscii("RIFF") &&
                bytes.matchesAscii(8, "WEBP") -> "webp"
            else -> null
        }
    }

    private fun isGif(bytes: ByteArray): Boolean {
        return bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a")
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { index -> (this[index].toInt() and 0xFF) == values[index] }
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        return matchesAscii(0, value)
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
        if (size < offset + value.length) return false
        return value.indices.all { index -> (this[offset + index].toInt() and 0xFF) == value[index].code }
    }

    private fun updateEmojiInfoStorage(storage: Any, emojiInfo: Any): Boolean {
        if (methodEmojiInfoStorageUpdate.isPlaceholder) return false
        return runCatching {
            methodEmojiInfoStorageUpdate.method.invoke(storage, emojiInfo)
            true
        }.getOrDefault(false)
    }

    private fun Any.setFieldValue(name: String, value: Any?) {
        val field = runtimeField(name) ?: return
        field.set(this, value)
    }

    private fun Any.getFieldValue(name: String): Any? {
        val field = runtimeField(name) ?: return null
        return field.get(this)
    }

    private fun Any.getStringFieldValue(name: String): String {
        return getFieldValue(name) as? String ?: ""
    }

    private fun Any.getIntFieldValue(name: String): Int {
        return when (val value = getFieldValue(name)) {
            is Int -> value
            is Number -> value.toInt()
            else -> 0
        }
    }

    private fun Any.getEmojiMd5(): String {
        return getFieldValue("field_md5") as? String
            ?: runCatching {
                reflekt().firstMethod { name = "getMd5" }.invoke() as? String
            }.getOrNull()
            ?: ""
    }

    private fun Any.runtimeField(name: String): Field? {
        return runtimeFields.getOrPut(javaClass to name) {
            CachedField(
                runCatching {
                    javaClass.reflekt()
                        .firstField {
                            this.name = name
                            superclass()
                        }
                        .self
                        .makeAccessible()
                }.onFailure {
                    WeLogger.w(TAG, "failed to resolve field $name in ${javaClass.name}", it)
                }.getOrNull()
            )
        }.field
    }

    private fun Activity.navigationBarHeight(): Int {
        val resourceId = navigationBarHeightResId
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun String.escapeXmlAttr(): String {
        return replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&apos;")
    }

    private fun StringBuilder.appendXmlAttr(name: String, value: String) {
        append(' ')
        append(name)
        append("=\"")
        append(value.escapeXmlAttr())
        append('"')
    }

    private fun String.hasNestedMsgEnvelope(): Boolean {
        val firstMsg = indexOf("<msg")
        if (firstMsg < 0) return false
        return indexOf("<msg", firstMsg + 1) >= 0 || contains("</msg></msg>")
    }
}









