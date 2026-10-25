package dev.ujhhgtg.wekit.ui.content.contacts

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.ui.content.GlobalImageLoader
import dev.ujhhgtg.wekit.utils.fs.WeChatAvatarCache

/**
 * 联系人 / 群聊头像。
 *
 * 显示优先级：[overrideModel]（例如自定义好友头像的字节）→ 微信 URL 键高清缓存 → 旧高清缓存 → 微信 URL 键普通缓存 → 旧普通缓存 → [avatarUrl] 网络
 * → 空白。 任一候选缺失或解码失败会继续尝试下一个；[overrideModel] 与 URL 由调用方提供， 组件自身只额外读取微信磁盘缓存。
 */
@Composable
fun ContactAvatar(
    wxId: String,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    avatarUrl: String = "",
    avatarBigUrl: String = "",
    overrideModel: Any? = null,
) {
    val context = LocalContext.current
    val candidates =
        remember(wxId, avatarUrl, avatarBigUrl, overrideModel) {
            buildList {
                overrideModel?.let(::add)
                WeChatAvatarCache.candidates(wxId, avatarUrl, avatarBigUrl).forEach { cached ->
                    add(
                        ImageRequest.Builder(context)
                            .data(cached.file)
                            .memoryCacheKey(
                                "wechat-avatar/${cached.file.path}/${cached.lastModified}",
                            )
                            .build(),
                    )
                }
                if (avatarUrl.isNotEmpty()) add(avatarUrl)
            }
        }
    var failedCandidates by remember(candidates) { mutableIntStateOf(0) }
    AsyncImage(
        model = candidates.getOrNull(failedCandidates),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.1875f)),
        imageLoader = GlobalImageLoader,
        onError = {
            if (failedCandidates < candidates.size) failedCandidates += 1
        },
    )
}

/** [ContactAvatar] for an already-loaded contact row. */
@Composable
fun ContactAvatar(
    contact: IWeContact,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    overrideModel: Any? = null,
) =
    ContactAvatar(
        wxId = contact.wxId,
        size = size,
        modifier = modifier,
        avatarUrl = contact.avatarUrl,
        avatarBigUrl = contact.avatarBigUrl,
        overrideModel = overrideModel,
    )
