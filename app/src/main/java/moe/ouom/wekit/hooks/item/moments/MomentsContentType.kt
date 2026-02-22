package moe.ouom.wekit.hooks.item.moments

enum class MomentsContentType(val typeId: Int, val displayName: String) {
    IMG(1, "图片"),
    TEXT(2, "文本"),
    LINK(3, "链接"),
    MUSIC(4, "音乐"),
    VIDEO(5, "视频"),
    COMMODITY(9, "商品"),
    STICKER(10, "表情"),
    COMMODITY_OLD(12, "商品 (旧)"),
    COUPON(13, "卡券"),
    TV_SHOW(14, "视频号/电视"),
    LITTLE_VIDEO(15, "微视/短视频"),
    STREAM_VIDEO(18, "直播流"),
    ARTICLE_VIDEO(19, "文章视频"),
    NOTE(26, "笔记"),
    FINDER_VIDEO(28, "视频号视频"),
    WE_APP(30, "小程序单页"),
    LIVE(34, "直播"),
    FINDER_LONG_VIDEO(36, "视频号长视频"),
    LITE_APP(41, "轻应用"),
    RICH_MUSIC(42, "富媒体音乐"),
    TING_AUDIO(47, "听歌"),
    LIVE_PHOTO(54, "动态照片");

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