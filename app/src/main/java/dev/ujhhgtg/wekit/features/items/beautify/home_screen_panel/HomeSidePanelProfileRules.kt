package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

internal fun isEligibleWeatherCountry(code: String): Boolean =
    code.trim().uppercase() in setOf("CN", "HK", "MO", "TW")

internal fun validateHitokotoSettings(
    minLength: Int?,
    maxLength: Int?,
    categories: Set<String> = HITOKOTO_CATEGORY_CODES,
    charset: String = "utf-8",
): String? = when {
    minLength != null && minLength < 0 || maxLength != null && maxLength < 0 ->
        "长度不能为负数"
    categories.isEmpty() -> "至少选择一个分类"
    categories.any { it !in HITOKOTO_CATEGORY_CODES } -> "包含不支持的一言分类"
    charset !in setOf("utf-8", "gbk") -> "不支持的字符编码"
    minLength != null && maxLength != null && maxLength < minLength ->
        "最大长度不能小于最小长度"
    else -> null
}
