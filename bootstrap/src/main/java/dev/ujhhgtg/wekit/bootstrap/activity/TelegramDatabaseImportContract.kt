package dev.ujhhgtg.wekit.bootstrap.activity

/** Primitive Intent/JSON contract; no business feature or model definitions cross this boundary. */
object TelegramDatabaseImportContract {
    const val ACTION_PICK_ROOT_STICKER_SETS = "dev.ujhhgtg.wekit.action.PICK_ROOT_TELEGRAM_STICKER_SETS"
    const val EXTRA_STICKER_SETS = "telegram_sticker_sets"
    const val EXTRA_ERROR = "telegram_database_error"
}
