package dev.ujhhgtg.wekit.features.items.moments

import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R

enum class MomentsContentType(val typeId: Int, @StringRes val nameRes: Int) {
    IMG(1, R.string.moments_content_type_image),
    TEXT(2, R.string.moments_content_type_text),
    LINK(3, R.string.moments_content_type_link),
    MUSIC(4, R.string.moments_content_type_music),
    VIDEO(5, R.string.moments_content_type_video),
    COMMODITY(9, R.string.moments_content_type_product),
    STICKER(10, R.string.moments_content_type_sticker),
    COMMODITY_OLD(12, R.string.moments_content_type_product_old),
    COUPON(13, R.string.moments_content_type_coupon),
    TV_SHOW(14, R.string.moments_content_type_channels_tv),
    LITTLE_VIDEO(15, R.string.moments_content_type_short_video),
    STREAM_VIDEO(18, R.string.moments_content_type_stream),
    ARTICLE_VIDEO(19, R.string.moments_content_type_article_video),
    NOTE(26, R.string.moments_content_type_note),
    FINDER_VIDEO(28, R.string.moments_content_type_channels_video),
    WE_APP(30, R.string.moments_content_type_miniapp_page),
    LIVE(34, R.string.moments_content_type_live),
    FINDER_LONG_VIDEO(36, R.string.moments_content_type_channels_long_video),
    LITE_APP(41, R.string.moments_content_type_lite_app),
    RICH_MUSIC(42, R.string.moments_content_type_rich_music),
    TING_AUDIO(47, R.string.moments_content_type_listen_audio),
    LIVE_PHOTO(54, R.string.moments_content_type_live_photo);

    companion object {
        // 缓存所有有效的 Type ID，避免每次重复计算
        private val validTypeSet by lazy { entries.map { it.typeId }.toHashSet() }

        /**
         * 解析整型 ID 为对应的枚举实例
         * @param id 数据库中的 type 值
         * @return 匹配成功返回枚举，否则返回 null
         */
        fun fromId(id: Int): MomentsContentType? =
            entries.firstOrNull { it.typeId == id }

        /**
         * 获取全量类型 ID 集合
         * 用于快速判断某个 type 是否属于朋友圈已知内容范畴
         */
        val allTypeIds: Set<Int>
            get() = validTypeSet
    }
}
