package dev.ujhhgtg.wekit.features.items.debug

import com.tencent.wcdb.DatabaseErrorHandler
import com.tencent.wcdb.database.SQLiteCipherSpec
import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.int

object LogDatabaseCredentials : SwitchFeature() {

    override val technicalId = "记录数据库凭据"
    override val nameRes = R.string.feature_log_database_credentials_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_log_database_credentials_description

    private const val TAG = "LogDatabaseCredentials"

    override fun onEnable() {
        SQLiteDatabase::class.reflekt().firstMethod {
            name = "openDatabase"
            parameters(BString, ByteArray::class, SQLiteCipherSpec::class, SQLiteDatabase.CursorFactory::class, int, DatabaseErrorHandler::class, int)
        }.hookBefore {
            val cipherSpec = args[2] as SQLiteCipherSpec?
            WeLogger.d(
                TAG,
                "openDatabase() called with: name=${args[0] as String?}, password=${String(args[1] as? ByteArray? ?: return@hookBefore)}, cipherSpec=${
                    cipherSpec.run {
                        "${this?.hmacAlgorithm},${this?.hmacEnabled},${this?.kdfAlgorithm},${this?.kdfIteration},${this?.pageSize}"
                    }
                }"
            )
        }
    }
}
