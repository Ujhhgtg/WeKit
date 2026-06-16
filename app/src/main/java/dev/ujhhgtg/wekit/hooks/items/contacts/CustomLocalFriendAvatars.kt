package dev.ujhhgtg.wekit.hooks.items.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.SpinnerAdapter
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Search
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.isSubclassOf
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.models.IWeContact
import dev.ujhhgtg.wekit.hooks.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeContactPrefsScreenApi.ContactInfoItem
import dev.ujhhgtg.wekit.hooks.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.DexKit
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.fields
import dev.ujhhgtg.wekit.utils.reflection.firstField
import dev.ujhhgtg.wekit.utils.reflection.firstMethod
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import kotlinx.serialization.json.Json
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.min

@HookItem(
    name = "自定义好友本地头像", categories = ["联系人与群组", "联系人详情页面"],
    description = "为指定联系人或群组使用本地图片替换微信内显示的头像"
)
object CustomLocalFriendAvatars : ClickableHookItem(), IContactInfoProvider, IResolvesDex {

    private const val PREF_KEY = "custom_avatar"
    private const val SEP = ";"
    private const val VIEW_TAG_CUSTOM_AVATAR = 0x57434156

    private val TAG = This.Class.simpleName
    private val avatarMapFile = KnownPaths.moduleData / "custom_avatars_map.json"

    // ji1.s
    private val classAvatarGetContactServiceHelper by dexClass {
        matcher {
            usingEqStrings("MicroMsg.AvatarGetContactServiceHelper", "put stack into pool: ")
        }
    }

    // ji1.s.og, most of com.tencent.mm.feature.avatar.w calls this,
    // e.g. Cg, ig, cg, og, rg
    private val methodMvvmLoadAvatar1 by dexMethod {
        matcher {
            declaredClass(classAvatarGetContactServiceHelper.clazz)
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
                "java.lang.String",
                "float"
            )
            returnType(Void.TYPE)
            usingEqStrings("MicroMsg.AvatarGetContactServiceHelper", "put stack into pool: ")
        }
    }

    // ji1.s.pg: another exception
    private val methodMvvmLoadAvatar2 by dexMethod {
        matcher {
            declaredClass(classAvatarGetContactServiceHelper.clazz)
            usingEqStrings("imageView")
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
            )
            returnType(Void.TYPE)
            usingNumbers(30000)
        }
    }

    // com.tencent.mm.feature.avatar.w.pg; an exception: this doesn't call methodMvvmLoadAvatar
    private val methodFeatureAvatarSimple1 by dexMethod {
        // com.tencent.mm.feature.avatar.w
        val avatarDrawableClass = DexKit.findClass {
            searchPackages("com.tencent.mm.feature.avatar")
            matcher {
                usingEqStrings("MicroMsg.AvatarDrawable", "imageView is null", "?access_token=")
            }
        }.single().name

        matcher {
            declaredClass = avatarDrawableClass
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String"
            )
            returnType(Void.TYPE)

            addInvoke {
                declaredClass = "android.view.View"
                name = "invalidate"
            }
        }
    }

    private val methodHdGallerySetUsername by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.setting.ui.setting.view.GetHdHeadImageGalleryView"
            name = "setUsername"
            paramTypes("java.lang.String")
            returnType(Void.TYPE)
        }
    }

    private val methodRoundBitmap by dexMethod {
        searchPackages("com.tencent.mm.sdk.platformtools")
        matcher {
            paramTypes("android.graphics.Bitmap", "boolean", "float")
            returnType = "android.graphics.Bitmap"

            addInvoke {
                usingEqStrings("MicroMsg.BitmapUtil", "getRoundedCornerBitmap in bitmap is null")
            }
        }
    }

    @Volatile
    private var avatarMapCache: Map<String, String>? = null

    private val displayNameCache = ConcurrentHashMap<String, String>()
    private val roundedBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val originalBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val boundAvatarViews = Collections.synchronizedMap(WeakHashMap<ImageView, BoundAvatar>())

    private lateinit var hdGalleryUsernameField: Field
    private lateinit var hdGalleryThumbBitmapField: Field
    private lateinit var hdGalleryHdBitmapField: Field
    private lateinit var hdGalleryLoadedField: Field
    private lateinit var hdGalleryAdapterField: Field
    private lateinit var hdGallerySetAdapterMethod: Method

    private var avatarMap: Map<String, String>
        get() = avatarMapCache ?: loadAvatarMap().also { avatarMapCache = it }
        set(value) {
            val normalized = value
                .mapKeys { it.key.trim() }
                .mapValues { it.value.trim() }
                .filterKeys { it.isNotEmpty() }
                .filterValues { it.isNotEmpty() }
            avatarMapCache = normalized
            saveAvatarMap(normalized)
        }

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)

        listOf(
            methodConversationAvatar,
            methodMvvmLoadAvatar1,
            methodMvvmLoadAvatar2,
            methodFeatureAvatarSimple1
        ).forEach { it.method.hookBefore {
            val imageView = args.getOrNull(0) as? ImageView ?: return@hookBefore
            val wxId = args.getOrNull(1) as? String ?: return@hookBefore
            if (applyCustomAvatar(imageView, wxId, roundAvatarRadiusFactor)) {
                result = null
            }
        } }

        methodHdGallerySetUsername.hookBefore {
            val username = args.getOrNull(0) as? String ?: return@hookBefore
            val gallery = thisObject
            if (applyCustomHdAvatar(gallery, username)) {
                result = null
                (gallery as? View)?.let { view ->
                    view.post { applyCustomHdAvatar(gallery, username) }
                    view.postDelayed({ applyCustomHdAvatar(gallery, username) }, 300L)
                }
            }
        }
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
        avatarMapCache = null
    }

    override fun getContactInfoItem(activity: Activity): List<ContactInfoItem> {
        val wxId = activity.currentWxId ?: return emptyList()
        val hasCustomAvatar = avatarMap.containsKey(wxId)
        return listOf(ContactInfoItem(
            key = PREF_KEY,
            title = if (hasCustomAvatar) "更换自定义头像" else "设置自定义头像",
            position = 1
        ))
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false
        val wxId = activity.currentWxId ?: return true

        if (avatarMap.containsKey(wxId)) {
            showContactAvatarDialog(activity, wxId)
        } else {
            selectAvatarImage(activity, wxId)
        }

        return true
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            CustomAvatarManagerDialog(
                contacts = remember { loadContacts() },
                entries = avatarMap,
                onDismiss = onDismiss,
                onSelectImage = { wxId ->
                    onDismiss()
                    selectAvatarImage(context, wxId)
                },
                onRemove = { wxId ->
                    removeAvatar(wxId)
                    showToast("已清除自定义头像")
                    onDismiss()
                }
            )
        }
    }

    private var roundAvatarRadiusFactor by prefOption("custom_avatar_round_radius", 0.5f)

    private fun effectiveRadiusFactor(loaderRadiusFactor: Float): Float {
        return if (RoundAvatars.hasEnabled) roundAvatarRadiusFactor else loaderRadiusFactor
    }

    // com.tencent.mm.pluginsdk.ui.u.b
    private val methodConversationAvatar by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui")
        matcher {
            usingEqStrings("MicroMsg.AvatarDrawable", "imageView is null")
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
                "float",
                "boolean"
            )
            returnType = "void"

            addInvoke {
                declaredClass = "android.view.View"
                name = "invalidate"
            }
        }
    }

    private fun applyCustomAvatar(imageView: ImageView, username: String, radiusFactor: Float): Boolean {
        val uri = avatarMap[username]?.takeIf { it.isNotBlank() } ?: return false
        val effectiveRadiusFactor = effectiveRadiusFactor(radiusFactor)
        val tag = "$username$SEP$uri$SEP$effectiveRadiusFactor"
        imageView.setTag(VIEW_TAG_CUSTOM_AVATAR, tag)
        boundAvatarViews[imageView] = BoundAvatar(username, uri, radiusFactor)
        loadAvatarInto(imageView, uri, effectiveRadiusFactor)
        imageView.post {
            if (imageView.getTag(VIEW_TAG_CUSTOM_AVATAR) == tag) {
                loadAvatarInto(imageView, uri, effectiveRadiusFactor)
            }
        }
        return true
    }

    private fun loadAvatarInto(imageView: ImageView, uri: String, radiusFactor: Float) {
        val targetSize = imageView.width
            .takeIf { it > 0 }
            ?: imageView.layoutParams?.width?.takeIf { it > 0 }
            ?: 156
        val bitmap = decodeAvatarBitmap(uri, targetSize, round = true, radiusFactor = radiusFactor) ?: run {
            imageView.load(uri) {
                allowHardware(false)
                crossfade(false)
            }
            return
        }

        imageView.scaleType = ImageView.ScaleType.FIT_XY
        imageView.setImageDrawable(bitmap.toDrawable(imageView.resources))
        imageView.invalidate()
    }

    private fun applyCustomHdAvatar(gallery: Any?, username: String): Boolean {
        val uri = avatarMap[username]?.takeIf { it.isNotBlank() } ?: return false
        val view = gallery as? View ?: return false
        val width = view.resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val bitmap = decodeAvatarBitmap(uri, width, round = false, radiusFactor = 0f) ?: return false

        runCatching {
            ensureReflection()

            hdGalleryUsernameField.set(gallery, username)
            hdGalleryThumbBitmapField.set(gallery, bitmap)
            hdGalleryHdBitmapField.set(gallery, bitmap)
            hdGalleryLoadedField.setBoolean(gallery, true)
            @Suppress("UNCHECKED_CAST")
            val adapter = hdGalleryAdapterField.get(gallery) as? SpinnerAdapter
            if (adapter is BaseAdapter) {
                adapter.notifyDataSetChanged()
            } else if (adapter != null) {
                hdGallerySetAdapterMethod.invoke(gallery, adapter)
            }
            view.invalidate()
        }.onFailure {
            WeLogger.e(TAG, "failed to apply custom HD avatar for $username", it)
            return false
        }
        return true
    }

    private fun decodeAvatarBitmap(
        uri: String,
        targetSize: Int,
        round: Boolean,
        radiusFactor: Float
    ): Bitmap? {
        val cacheKey = "$uri|$targetSize|$round|$radiusFactor"
        val cache = if (round) roundedBitmapCache else originalBitmapCache
        cache[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }

        val bitmap = runCatching {
            HostInfo.application.contentResolver.openInputStream(uri.toUri())?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return null

        val cropped = centerCrop(bitmap, targetSize, targetSize)
        if (cropped !== bitmap && !bitmap.isRecycled) bitmap.recycle()

        val result = if (round) {
            roundBitmap(cropped, radiusFactor)
        } else {
            cropped
        }
        if (round && result !== cropped && !cropped.isRecycled) cropped.recycle()

        cache[cacheKey] = result
        trimBitmapCache(cache)
        return result
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val srcWidth = source.width
        val srcHeight = source.height
        if (srcWidth <= 0 || srcHeight <= 0) return source

        val srcRatio = srcWidth.toFloat() / srcHeight
        val dstRatio = width.toFloat() / height
        val rect = if (srcRatio > dstRatio) {
            val cropWidth = (srcHeight * dstRatio).toInt().coerceAtLeast(1)
            val left = (srcWidth - cropWidth) / 2
            Rect(left, 0, left + cropWidth, srcHeight)
        } else {
            val cropHeight = (srcWidth / dstRatio).toInt().coerceAtLeast(1)
            val top = (srcHeight - cropHeight) / 2
            Rect(0, top, srcWidth, top + cropHeight)
        }

        return createBitmap(width, height).also { out ->
            Canvas(out).drawBitmap(source, rect, Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                isDither = true
            })
        }
    }

    private fun roundBitmap(source: Bitmap, radiusFactor: Float): Bitmap {
        val radius = (min(source.width, source.height) * radiusFactor).coerceAtLeast(0f)
        roundBitmapWithWeChat(source, radius)?.let { return it }
        return roundBitmapFallback(source, radius)
    }

    private fun roundBitmapWithWeChat(source: Bitmap, radius: Float): Bitmap? {
        return runCatching {
            methodRoundBitmap.method.invoke(null, source, false, radius) as? Bitmap?
        }.getOrNull()
    }

    private fun roundBitmapFallback(source: Bitmap, radius: Float): Bitmap {
        val out = createBitmap(source.width, source.height)
        val rect = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            isFilterBitmap = true
            color = -0x3f3f40
        }

        val path = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        Canvas(out).apply {
            drawARGB(0, 0, 0, 0)
            drawPath(path, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            drawBitmap(source, 0f, 0f, paint)
            paint.xfermode = null
        }
        return out
    }

    private fun trimBitmapCache(cache: ConcurrentHashMap<String, Bitmap>) {
        if (cache.size <= 24) return
        cache.keys.take(cache.size - 24).forEach { key ->
            cache.remove(key)
        }
    }

    private fun showContactAvatarDialog(context: Context, wxId: String) {
        val displayName = getDisplayName(wxId)
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("自定义头像") },
                text = {
                    DefaultColumn {
                        Text(displayName)
                        Text(
                            text = wxId,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            removeAvatar(wxId)
                            showToast("已清除自定义头像，重新进入页面后生效")
                            onDismiss()
                        }) {
                            Text("清除")
                        }
                        Button(onClick = {
                            onDismiss()
                            selectAvatarImage(context, wxId)
                        }) {
                            Text("更换")
                        }
                    }
                }
            )
        }
    }

    private fun selectAvatarImage(context: Context, wxId: String) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult

                persistReadPermission(uri)
                setAvatar(wxId, uri.toString())
                showToast("自定义头像已设置, 重新进入页面或重启微信后生效")
            }

            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            HostInfo.application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure {
            WeLogger.w(TAG, "failed to persist avatar uri permission: $uri", it)
        }
    }

    private fun setAvatar(wxId: String, uri: String) {
        avatarMap = avatarMap + (wxId to uri)
        clearBitmapCaches()
    }

    private fun removeAvatar(wxId: String) {
        avatarMap = avatarMap - wxId
        clearBitmapCaches()
    }

    private fun clearBitmapCaches() {
        roundedBitmapCache.clear()
        originalBitmapCache.clear()
    }

    fun onRoundAvatarConfigChanged() {
        clearBitmapCaches()
        boundAvatarViews.entries.toList().forEach { (imageView, binding) ->
            if (avatarMap[binding.username] != binding.uri) return@forEach
            val radiusFactor = effectiveRadiusFactor(binding.loaderRadiusFactor)
            val tag = "${binding.username}$SEP${binding.uri}$SEP$radiusFactor"
            imageView.setTag(VIEW_TAG_CUSTOM_AVATAR, tag)
            loadAvatarInto(imageView, binding.uri, radiusFactor)
            imageView.post {
                if (imageView.getTag(VIEW_TAG_CUSTOM_AVATAR) == tag) {
                    loadAvatarInto(imageView, binding.uri, radiusFactor)
                }
            }
        }
    }

    private fun ensureReflection() {
        if (::hdGalleryUsernameField.isInitialized) return

        val galleryClass =
            "${PackageNames.WECHAT}.plugin.setting.ui.setting.view.GetHdHeadImageGalleryView".toClass()

        val mutableBitmapFields = galleryClass.fields {
            type = Bitmap::class.java
            modifiers { !it.contains(Modifiers.FINAL) }
        }

        hdGalleryThumbBitmapField = mutableBitmapFields[0].self
        hdGalleryHdBitmapField = mutableBitmapFields[1].self

        hdGalleryUsernameField = galleryClass.firstField {
            type = BString
            modifiers { !it.contains(Modifiers.FINAL) }
        }.self.makeAccessible()

        hdGalleryLoadedField = galleryClass.firstField {
            type = bool
        }.self.makeAccessible()

        hdGalleryAdapterField = galleryClass.firstField {
            type { it isSubclassOf SpinnerAdapter::class }
        }.self.makeAccessible()

        hdGallerySetAdapterMethod = galleryClass.firstMethod {
            name = "setAdapter"
            parameters(SpinnerAdapter::class.java)
        }.self.makeAccessible()
    }

    private fun loadAvatarMap(): Map<String, String> {
        if (!avatarMapFile.exists()) return emptyMap()
        return runCatching {
            val raw = avatarMapFile.readText()
            Json.decodeFromString<Map<String, String>>(raw)
                .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }.getOrElse {
            WeLogger.e(TAG, "failed to parse custom avatar map", it)
            emptyMap()
        }
    }

    private fun saveAvatarMap(value: Map<String, String>) {
        runCatching {
            val raw = Json.encodeToString(value)
            avatarMapFile.writeText(raw)
        }.onFailure {
            WeLogger.e(TAG, "failed to save custom avatar map", it)
        }
    }

    private fun loadContacts(): List<IWeContact> {
        return runCatching {
            (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups())
                .sortedBy { it.displayName.ifBlank { it.wxId } }
        }.getOrElse {
            WeLogger.e(TAG, "failed to load contacts for custom avatar manager", it)
            emptyList()
        }
    }

    private fun getDisplayName(wxId: String): String {
        return displayNameCache.getOrPut(wxId) {
            runCatching { WeDatabaseApi.getDisplayName(wxId) }.getOrDefault(wxId)
        }
    }

    @Composable
    private fun CustomAvatarManagerDialog(
        contacts: List<IWeContact>,
        entries: Map<String, String>,
        onDismiss: () -> Unit,
        onSelectImage: (String) -> Unit,
        onRemove: (String) -> Unit
    ) {
        var searchQuery by remember { mutableStateOf("") }
        val configuredWxIds = entries.keys

        val filteredContacts = remember(searchQuery, contacts, entries) {
            val entryContacts = entries.keys.map { wxId ->
                contacts.firstOrNull { it.wxId == wxId } ?: SimpleContact(wxId, getDisplayName(wxId))
            }
            (entryContacts + contacts)
                .distinctBy { it.wxId }
                .filter {
                    it.displayName.contains(searchQuery, ignoreCase = true) ||
                            it.wxId.contains(searchQuery, ignoreCase = true)
                }
                .sortedWith(
                    compareByDescending<IWeContact> { it.wxId in configuredWxIds }
                        .thenBy { it.displayName.isBlank() }
                        .thenBy { it.displayName }
                )
        }

        AlertDialogContent(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            title = { Text("自定义好友本地头像") },
            text = {
                Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        placeholder = { Text("搜索昵称或微信号") },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = "Search") },
                        singleLine = true
                    )

                    Text(
                        text = "已设置 ${entries.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(
                            items = filteredContacts,
                            key = { it.wxId }
                        ) { contact ->
                            val hasAvatar = contact.wxId in entries
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .clickable { onSelectImage(contact.wxId) }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.displayName.ifBlank { contact.wxId },
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = if (hasAvatar) "已设置 - ${contact.wxId}" else contact.wxId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                if (hasAvatar) {
                                    TextButton(onClick = { onRemove(contact.wxId) }) {
                                        Text("清除")
                                    }
                                } else {
                                    TextButton(onClick = { onSelectImage(contact.wxId) }) {
                                        Text("选择")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onDismiss) { Text("关闭") }
            }
        )
    }

    private data class SimpleContact(
        override val wxId: String,
        override val nickname: String
    ) : IWeContact {
        override val displayName: String
            get() = nickname
        override val avatarUrl: String
            get() = ""
    }

    private data class BoundAvatar(
        val username: String,
        val uri: String,
        val loaderRadiusFactor: Float
    )
}
