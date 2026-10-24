package dev.ujhhgtg.wekit.features.api.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.contacts.CustomLocalFriendAvatars
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcess
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.collections.LruCache

/** Conversation avatars for notifications, through WeChat's own notification avatar loader. */
object WeNotificationAvatarApi : ApiFeature(), IResolveDex {
    override val technicalId = "通知头像服务"
    override val nameRes = R.string.feature_we_notification_avatar_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_notification_avatar_api_description

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    private const val TAG = "WeNotificationAvatarApi"
    private const val CACHE_TTL_MILLIS = 5 * 60 * 1000L

    // WeChat's notification avatar loader. The declaring class is obfuscated and changes between
    // host versions, so it is resolved by the stable NotificationAvatar implementation markers.
    private val methodLoadNotificationAvatar by dexMethod {
        returnType = "android.graphics.Bitmap"
        paramTypes("android.content.Context", "java.lang.String", "java.lang.String")
        usingEqStrings("MicroMsg.NotificationAvatar", "wcf://avatar/")
    }

    private data class CachedAvatar(
        val bitmap: Bitmap,
        val expiresAt: Long,
        val customAvatarKey: String,
    )

    private val lock = Any()
    private val cache = LruCache<String, CachedAvatar>(maxLimit = 64)

    private val loader by lazy {
        methodLoadNotificationAvatar.method.declaringClass
            .reflekt()
            .firstConstructor { parameters(Context::class) }
            .newInstance(HostInfo.application)
    }

    override fun onDisable() {
        synchronized(lock) { cache.clear() }
    }

    /** The avatar of [wxId] (custom local avatars included), or null when there is none. */
    fun loadBitmap(wxId: String): Bitmap? {
        if (wxId.isEmpty() || wxId == "system") return null

        val now = System.currentTimeMillis()
        val customAvatarKey = CustomLocalFriendAvatars.notificationAvatarCacheKey(wxId)
        synchronized(lock) {
            cache[wxId]?.let { cached ->
                if (cached.expiresAt > now && cached.customAvatarKey == customAvatarKey) {
                    return cached.bitmap
                }
                cache.remove(wxId)
            }
        }

        val bitmap =
            runCatching {
                methodLoadNotificationAvatar.method.invoke(loader, HostInfo.application, wxId, "")
                    as Bitmap?
            }
                .onFailure {
                    WeLogger.w(TAG, "failed to load notification avatar for $wxId", it)
                }
                .getOrNull() ?: return null

        synchronized(lock) {
            cache[wxId] = CachedAvatar(bitmap, now + CACHE_TTL_MILLIS, customAvatarKey)
        }
        return bitmap
    }

    fun loadIcon(wxId: String): Icon? = loadBitmap(wxId)?.let(Icon::createWithBitmap)
}
