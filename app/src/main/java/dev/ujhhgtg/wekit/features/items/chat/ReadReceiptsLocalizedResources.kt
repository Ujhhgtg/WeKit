package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController

@get:StringRes
internal val ReadReceiptsTunnelErrorCode.messageRes: Int
    get() = when (this) {
        ReadReceiptsTunnelErrorCode.VISIBLE_SETTINGS_REQUIRED ->
            R.string.read_receipts_error_visible_settings_required
        ReadReceiptsTunnelErrorCode.TOKEN_REQUIRED ->
            R.string.read_receipts_error_token_required
        ReadReceiptsTunnelErrorCode.TOKEN_INVALID ->
            R.string.read_receipts_error_token_invalid
        ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID ->
            R.string.read_receipts_error_browser_credential_invalid
        ReadReceiptsTunnelErrorCode.CREDENTIAL_SAVE_FAILED ->
            R.string.read_receipts_error_credential_save_failed
        ReadReceiptsTunnelErrorCode.START_HANDOFF_TIMEOUT ->
            R.string.read_receipts_error_start_handoff_timeout
        ReadReceiptsTunnelErrorCode.STOP_TIMEOUT ->
            R.string.read_receipts_error_stop_timeout
        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE ->
            R.string.read_receipts_error_service_unavailable
        ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED ->
            R.string.read_receipts_error_health_check_failed
        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE ->
            R.string.read_receipts_error_unexpected_failure
    }

@get:StringRes
internal val ReadReceiptsTunnelState.notificationDetailRes: Int
    get() = when (this) {
        ReadReceiptsTunnelState.STOPPED -> R.string.read_receipts_state_stopped
        ReadReceiptsTunnelState.STARTING -> R.string.read_receipts_state_starting
        ReadReceiptsTunnelState.CONNECTED -> R.string.read_receipts_tunnel_state_connected
        ReadReceiptsTunnelState.RECONNECTING -> R.string.read_receipts_tunnel_state_reconnecting
        ReadReceiptsTunnelState.NEEDS_USER_ACTION ->
            R.string.read_receipts_state_needs_user_action
        ReadReceiptsTunnelState.FAILED -> R.string.read_receipts_state_failed
        ReadReceiptsTunnelState.STOPPING -> R.string.read_receipts_state_stopping
    }

internal fun Context.readReceiptsModuleLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.ModuleApp,
    )
