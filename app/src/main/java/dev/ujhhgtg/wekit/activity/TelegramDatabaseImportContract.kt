package dev.ujhhgtg.wekit.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramInstalledStickerSet
import dev.ujhhgtg.wekit.utils.android.Intent
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson

object TelegramDatabaseImportContract {
    const val ACTION_PICK_ROOT_STICKER_SETS =
        "${PackageNames.MODULE}.action.PICK_ROOT_TELEGRAM_STICKER_SETS"
    const val EXTRA_STICKER_SETS = "telegram_sticker_sets"
    const val EXTRA_ERROR = "telegram_database_error"
}

class PickRootTelegramStickerSetsContract :
    ActivityResultContract<Unit, RootTelegramStickerSetsResult>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent {
        setClassName(PackageNames.MODULE, "${PackageNames.MODULE}.activity.MainActivity")
        action = TelegramDatabaseImportContract.ACTION_PICK_ROOT_STICKER_SETS
    }

    override fun parseResult(resultCode: Int, intent: Intent?): RootTelegramStickerSetsResult =
        when {
            resultCode == Activity.RESULT_OK &&
                intent?.hasExtra(TelegramDatabaseImportContract.EXTRA_STICKER_SETS) == true -> {
                runCatching {
                        DefaultJson.decodeFromString<List<TelegramInstalledStickerSet>>(
                            intent
                                .getStringExtra(TelegramDatabaseImportContract.EXTRA_STICKER_SETS)
                                .orEmpty(),
                        )
                    }
                    .fold(
                        onSuccess = RootTelegramStickerSetsResult::Success,
                        onFailure = { RootTelegramStickerSetsResult.Failure("无法读取模块应用返回的表情包列表") },
                    )
            }

            intent?.hasExtra(TelegramDatabaseImportContract.EXTRA_ERROR) == true -> {
                RootTelegramStickerSetsResult.Failure(
                    intent.getStringExtra(TelegramDatabaseImportContract.EXTRA_ERROR).orEmpty(),
                )
            }

            else -> {
                RootTelegramStickerSetsResult.Cancelled
            }
        }
}

sealed interface RootTelegramStickerSetsResult {
    data class Success(val stickerSets: List<TelegramInstalledStickerSet>) :
        RootTelegramStickerSetsResult

    data class Failure(val message: String) : RootTelegramStickerSetsResult

    data object Cancelled : RootTelegramStickerSetsResult
}
