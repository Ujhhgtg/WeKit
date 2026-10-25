package dev.ujhhgtg.wekit.utils.fs

import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.strings.md5Hex
import java.io.File
import kotlin.io.path.div
import kotlin.io.path.isRegularFile

/**
 * Locates WeChat's on-disk avatar caches for a contact, group or official-account username.
 *
 * WeChat 8.0.79 keeps two caches below `<account>/avatar/<md5(username)[0:2]>/<md5[2:4]>/`:
 * - the newer URL-keyed cache in a per-user directory named after the full username md5, holding
 *   `small_<md5(smallUrl)>` and `hd_<md5(bigUrl)>` (the current avatar; matching the URL from
 *   `img_flag` makes it verifiably fresh);
 * - the legacy username-keyed files `user_<md5(username)>.png` and `user_hd_<md5(username)>.png`.
 *
 * Both caches store plain image bytes regardless of the `.png` suffix, so callers must decode by
 * content. Older hosts only have the legacy cache.
 */
object WeChatAvatarCache {
    data class CachedAvatar(val file: File, val lastModified: Long)

    /**
     * Existing local avatar files for [wxId], most preferred first: new HD, legacy HD, new normal,
     * legacy normal. [avatarUrl] and [avatarBigUrl] are the small and big CDN URLs from `img_flag`
     * (`reserved2`/`reserved1`); an empty URL skips the corresponding URL-keyed candidate.
     */
    fun candidates(wxId: String, avatarUrl: String, avatarBigUrl: String): List<CachedAvatar> {
        if (wxId.isEmpty()) return emptyList()
        val hash = md5Hex(wxId)
        val parent = RuntimeConfig.userDataDir / "avatar" / hash.take(2) / hash.substring(2, 4)
        val userDir = parent / hash
        val paths = buildList {
            if (avatarBigUrl.isNotEmpty()) add(userDir / "hd_${md5Hex(avatarBigUrl)}")
            add(parent / "user_hd_$hash.png")
            if (avatarUrl.isNotEmpty()) add(userDir / "small_${md5Hex(avatarUrl)}")
            add(parent / "user_$hash.png")
        }
        return paths.mapNotNull { path ->
            if (!path.isRegularFile()) return@mapNotNull null
            val file = path.toFile()
            CachedAvatar(file, file.lastModified())
        }
    }
}
