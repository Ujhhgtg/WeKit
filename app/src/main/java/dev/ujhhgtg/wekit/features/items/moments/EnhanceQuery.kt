package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(
    id = "朋友圈查询增强",
    nameRes = "feature_enhance_query_name",
    categoryIds = [FeatureCategoryIds.MOMENTS],
    descriptionRes = "feature_enhance_query_description",
)
object EnhanceQuery : SwitchFeature(), WeDatabaseListenerApi.IQueryListener {

    private const val TAG = "EnhanceQuery"

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onQuery(sql: String): String? {
        val rewritten = rewriteSql(sql)

        if (rewritten != sql) {
            WeLogger.i(TAG, "enhanced moments SQL query")
            return rewritten
        }

        return null
    }

    private fun rewriteSql(sql: String): String {
        if (!sql.contains("select *,rowid from SnsInfo", false)) return sql

        var newSql = sql

        if (sql.contains("WHERE SnsInfo.userName=", false)) {
            newSql = sql
                .replace(SOURCE_TYPE_FILTER, SOURCE_TYPE_FILTER_ENHANCED)
                .replace("(snsId >= ", "(1=1 or snsId >= ")
        }

        return newSql.replace("(sourceType & 2 != 0 )", "(1=1)")
    }

    private const val SOURCE_TYPE_FILTER =
        "(sourceType in (8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396,142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472,218,474,220,476,222,478))"

    private const val SOURCE_TYPE_FILTER_ENHANCED =
        "(sourceType in (0,2,8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396,142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472,218,474,220,476,222,478))"
}
