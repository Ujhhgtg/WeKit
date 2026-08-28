@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Share
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.settings.M3ListScaffold
import dev.ujhhgtg.wekit.extensions.CloudflaredPack
import dev.ujhhgtg.wekit.extensions.ExtensionPackDialogs
import dev.ujhhgtg.wekit.extensions.ExtensionPacks
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveBackButton
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import dev.ujhhgtg.wekit.ui.content.m3.lazySegmentedItems
import dev.ujhhgtg.wekit.ui.navigation.LocalNavigator
import dev.ujhhgtg.wekit.ui.navigation.Navigator
import dev.ujhhgtg.wekit.ui.navigation.rememberM3NavEffects
import dev.ujhhgtg.wekit.ui.animation.predictiveback.weKitNavTransition
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

@Keep
class ReadReceiptsSettingsActivity : ComponentActivity() {
    private val operationCoordinator by viewModels<SettingsOperationCoordinator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme {
                    ReadReceiptsSettingsRoot(
                        activity = this@ReadReceiptsSettingsActivity,
                        onFinish = ::finish,
                        operationCoordinator = operationCoordinator,
                    )
                }
            }
        }
    }
}

@Serializable
internal sealed interface ReadReceiptsRoute : NavKey {
    @Serializable data object Home : ReadReceiptsRoute
    @Serializable data object ThirdParty : ReadReceiptsRoute
    @Serializable data object Quick : ReadReceiptsRoute
    @Serializable data object Token : ReadReceiptsRoute
    @Serializable data object Browser : ReadReceiptsRoute
}

@Composable
private fun ReadReceiptsSettingsRoot(
    activity: Activity,
    onFinish: () -> Unit,
    operationCoordinator: SettingsOperationCoordinator,
) {
    val backStack = rememberNavBackStack<ReadReceiptsRoute>(ReadReceiptsRoute.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = { if (navigator.backStackSize() <= 1) onFinish() else navigator.pop() },
            transition = weKitNavTransition(ThemeSettings.pageTransitionAnimation),
            effects = rememberM3NavEffects(),
        ) {
            entry<ReadReceiptsRoute.Home> {
                ReadReceiptsHomeScreen(activity, onFinish, operationCoordinator) { navigator.push(it) }
            }
            entry<ReadReceiptsRoute.ThirdParty>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                ThirdPartyScreen(operationCoordinator) { navigator.pop() }
            }
            entry<ReadReceiptsRoute.Quick>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                QuickTunnelScreen(operationCoordinator) { navigator.pop() }
            }
            entry<ReadReceiptsRoute.Token>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                TokenTunnelScreen(operationCoordinator) { navigator.pop() }
            }
            entry<ReadReceiptsRoute.Browser>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                BrowserTunnelScreen(operationCoordinator) { navigator.pop() }
            }
        }
    }
}

private data class RuntimeSnapshot(
    val origin: ReadReceiptsStatus,
    val originActive: Boolean,
    val tunnel: ReadReceiptsTunnelStatus,
    val credentialExists: Boolean,
    val metadataLoading: Boolean,
)

/** Reads the origin status off the main thread: an idle origin resolves through JNI on every call. */
private fun runtimeSnapshot(): RuntimeSnapshot {
    val origin = ReadReceipts.originStatus()
    return RuntimeSnapshot(
        origin = origin,
        originActive = origin.state != ReadReceiptsRuntimeState.STOPPED &&
            origin.state != ReadReceiptsRuntimeState.FAILED,
        tunnel = ReadReceiptsTunnelController.status,
        credentialExists = ReadReceiptsTunnelController.credentialExists,
        metadataLoading = ReadReceiptsTunnelController.credentialMetadataLoading,
    )
}

internal enum class FeedbackSeverity { SUCCESS, INFO, ERROR }

internal data class OperationFeedback(
    val message: String = "",
    val severity: FeedbackSeverity = FeedbackSeverity.INFO,
)

internal enum class ActiveOperation(@StringRes val progressRes: Int) {
    SAVING(R.string.read_receipts_save_in_progress),
    TESTING(R.string.read_receipts_connection_test_pending),
    CONNECTING(R.string.read_receipts_connect_in_progress),
    COMMITTING(R.string.read_receipts_browser_commit_pending),
    DISCONNECTING(R.string.read_receipts_disconnect_in_progress),
    DELETING(R.string.read_receipts_credential_delete_pending),
    LOGIN(R.string.read_receipts_login_in_progress),
    REFRESHING(R.string.read_receipts_refresh_in_progress),
    CANCELLING_LOGIN(R.string.read_receipts_cancel_in_progress),
    LOGGING_OUT(R.string.read_receipts_logout_in_progress),
    RECONNECTING(R.string.read_receipts_reconnect_in_progress),
}

internal class SettingsOperationCoordinator : ViewModel() {
    private val states = mutableMapOf<ReadReceiptsRoute, SettingsOperationState>()

    fun state(route: ReadReceiptsRoute): SettingsOperationState =
        states.getOrPut(route) { SettingsOperationState() }

    fun recoverFromController(route: ReadReceiptsRoute) {
        state(route).recover(authoritativeOperation())
    }

    fun launch(
        route: ReadReceiptsRoute,
        operation: ActiveOperation,
        block: suspend () -> OperationFeedback,
    ) {
        val owner = state(route).begin(operation) ?: return
        viewModelScope.launch { owner.complete(block()) }
    }

    val retainedScope get() = viewModelScope

    private fun authoritativeOperation(): ActiveOperation? {
        val tunnel = ReadReceiptsTunnelController.status.state
        if (tunnel == ReadReceiptsTunnelState.STOPPING) return ActiveOperation.DISCONNECTING
        if (tunnel == ReadReceiptsTunnelState.RECONNECTING) return ActiveOperation.RECONNECTING
        if (tunnel == ReadReceiptsTunnelState.STARTING) return ActiveOperation.CONNECTING
        return null
    }
}

internal class SettingsOperationState {
    var activeOperation by mutableStateOf<ActiveOperation?>(null)
        private set
    var feedback by mutableStateOf(OperationFeedback())
    private var generation = 0L
    private var recovered = false

    fun begin(operation: ActiveOperation): SettingsOperationOwner? {
        if (activeOperation != null) return null
        recovered = false
        activeOperation = operation
        return SettingsOperationOwner(this, ++generation)
    }

    fun recover(operation: ActiveOperation?) {
        if (activeOperation == null && operation != null) {
            recovered = true
            activeOperation = operation
        } else if (recovered && operation == null) {
            recovered = false
            activeOperation = null
        }
    }

    fun transition(ownerGeneration: Long, operation: ActiveOperation) {
        if (generation == ownerGeneration && !recovered) activeOperation = operation
    }

    fun complete(ownerGeneration: Long, value: OperationFeedback) {
        if (generation != ownerGeneration || recovered) return
        activeOperation = null
        feedback = value
    }
}

internal class SettingsOperationOwner(
    private val state: SettingsOperationState,
    private val generation: Long,
) {
    fun transition(operation: ActiveOperation) = state.transition(generation, operation)
    fun complete(feedback: OperationFeedback) = state.complete(generation, feedback)
}

private fun String.successFeedback() = OperationFeedback(this, FeedbackSeverity.SUCCESS)
private fun String.infoFeedback() = OperationFeedback(this, FeedbackSeverity.INFO)
private fun String.errorFeedback() = OperationFeedback(this, FeedbackSeverity.ERROR)

@Composable
private fun rememberRuntimeSnapshot(
    route: ReadReceiptsRoute,
    operationCoordinator: SettingsOperationCoordinator,
): RuntimeSnapshot {
    var value by remember { mutableStateOf(runtimeSnapshot()) }
    LaunchedEffect(Unit) {
        ReadReceiptsTunnelController.refresh()
        while (true) {
            operationCoordinator.recoverFromController(route)
            value = withContext(Dispatchers.IO) { runtimeSnapshot() }
            delay(500.milliseconds)
        }
    }
    return value
}

@Composable
private fun ReadReceiptsHomeScreen(
    activity: Activity,
    onFinish: () -> Unit,
    operationCoordinator: SettingsOperationCoordinator,
    onOpen: (ReadReceiptsRoute) -> Unit,
) {
    val context = LocalContext.current
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val initial = remember { ReadReceipts.configuration() }
    var sendMode by rememberSaveable { mutableIntStateOf(ReadReceipts.sendMode) }
    var triggerPrefix by rememberSaveable { mutableStateOf(ReadReceipts.triggerPrefix) }
    var intervalSecs by rememberSaveable { mutableIntStateOf(initial.pollIntervalSecs.coerceIn(1, 60)) }
    var automaticLifecycle by rememberSaveable { mutableStateOf(initial.automaticLifecycle) }
    val operationState = operationCoordinator.state(ReadReceiptsRoute.Home)
    val activeOperation = operationState.activeOperation
    val runtime = rememberRuntimeSnapshot(ReadReceiptsRoute.Home, operationCoordinator)
    val current = ReadReceipts.configuration()

    fun requireCloudflared(): Boolean {
        if (CloudflaredPack.libraryFile() != null) return true
        ExtensionPacks.refresh(CloudflaredPack)
        ExtensionPackDialogs.requireInstall(activity, CloudflaredPack)
        return false
    }

    fun saveGeneral() {
        saveOnly(
            localizedContext,
            ReadReceipts.configuration().copy(
                pollIntervalSecs = intervalSecs,
                automaticLifecycle = automaticLifecycle,
            ),
            operationState,
        )
    }

    /** Radio-side mode switch: applies the committed mode change through the save-action classifier. */
    fun selectServerMode(mode: ReadReceiptsServerMode, tunnelMode: ReadReceiptsTunnelMode?) {
        if (mode == ReadReceiptsServerMode.BUILT_IN && !requireCloudflared()) return
        val currentConfiguration = ReadReceipts.configuration()
        val candidate = when (mode) {
            ReadReceiptsServerMode.THIRD_PARTY -> {
                if (normalizeThirdPartyReadReceiptEndpoint(currentConfiguration.thirdPartyUrl) == null) {
                    operationState.feedback = localizedContext.getString(R.string.read_receipts_invalid_third_party_url).errorFeedback()
                    return
                }
                currentConfiguration.copy(mode = ReadReceiptsServerMode.THIRD_PARTY)
            }

            ReadReceiptsServerMode.BUILT_IN -> when (tunnelMode) {
                ReadReceiptsTunnelMode.QUICK -> currentConfiguration.copy(
                    mode = ReadReceiptsServerMode.BUILT_IN,
                    tunnelMode = ReadReceiptsTunnelMode.QUICK.name,
                )

                ReadReceiptsTunnelMode.TOKEN -> {
                    if (ReadReceiptsTunnelHostnames.canonicalPublicRoot(currentConfiguration.hostname) == null) {
                        operationState.feedback = localizedContext.getString(R.string.read_receipts_managed_tunnel_requires_hostname).errorFeedback()
                        return
                    }
                    currentConfiguration.copy(
                        mode = ReadReceiptsServerMode.BUILT_IN,
                        tunnelMode = ReadReceiptsTunnelMode.TOKEN.name,
                        automaticPort = false,
                    )
                }

                ReadReceiptsTunnelMode.BROWSER_LOGIN ->
                    ReadReceipts.authoritativeBrowserConfiguration(currentConfiguration) ?: run {
                        operationState.feedback = localizedContext.getString(R.string.read_receipts_select_browser_tunnel_first).errorFeedback()
                        return
                    }

                null -> return
            }
        }
        saveOnly(localizedContext, candidate, operationState)
    }

    M3ListScaffold(
        title = stringResource(R.string.feature_read_receipts_name),
        navigationIcon = { ExpressiveBackButton(onClick = onFinish) },
    ) {
        item {
            SegmentedColumn(title = stringResource(R.string.read_receipts_section_runtime)) {
                item {
                    StatusWidget(
                        title = stringResource(R.string.read_receipts_built_in_server),
                        description = originDescription(runtime.origin),
                        error = runtime.origin.state == ReadReceiptsRuntimeState.FAILED,
                    )
                }
                item {
                    StatusWidget(
                        title = stringResource(R.string.read_receipts_public_tunnel),
                        description = tunnelDescription(runtime.tunnel),
                        error = runtime.tunnel.state in setOf(
                            ReadReceiptsTunnelState.FAILED,
                            ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                        ),
                    )
                }
                runtime.tunnel.publicUrl?.let { url ->
                    item {
                        BaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.read_receipts_verified_public_url, url),
                            description = stringResource(R.string.read_receipts_copy_or_share_url),
                            onClick = {
                                copyToClipboard(context, url)
                                operationState.feedback = localizedContext.getString(R.string.read_receipts_public_url_copied).successFeedback()
                            },
                            trailingContent = {
                                IconButton(onClick = { shareUrl(context, localizedContext, url) }) {
                                    Icon(
                                        MaterialSymbols.Outlined.Share,
                                        stringResource(R.string.read_receipts_share_public_url),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.chat_read_receipts_server)) {
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_third_party_server),
                        description = stringResource(R.string.read_receipts_third_party_description),
                        selected = current.mode == ReadReceiptsServerMode.THIRD_PARTY,
                        enabled = activeOperation == null,
                        trailingDivider = true,
                        onClick = { onOpen(ReadReceiptsRoute.ThirdParty) },
                        onSelect = { selectServerMode(ReadReceiptsServerMode.THIRD_PARTY, null) },
                    )
                }
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_quick_tunnel),
                        description = stringResource(R.string.read_receipts_quick_tunnel_description),
                        selected = current.mode == ReadReceiptsServerMode.BUILT_IN &&
                            current.tunnelMode() == ReadReceiptsTunnelMode.QUICK,
                        enabled = activeOperation == null,
                        trailingDivider = true,
                        onClick = {
                            if (requireCloudflared()) onOpen(ReadReceiptsRoute.Quick)
                        },
                        onSelect = { selectServerMode(ReadReceiptsServerMode.BUILT_IN, ReadReceiptsTunnelMode.QUICK) },
                    )
                }
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_tunnel_token),
                        description = stringResource(R.string.read_receipts_tunnel_token_description),
                        selected = current.mode == ReadReceiptsServerMode.BUILT_IN &&
                            current.tunnelMode() == ReadReceiptsTunnelMode.TOKEN,
                        enabled = activeOperation == null,
                        trailingDivider = true,
                        onClick = {
                            if (requireCloudflared()) onOpen(ReadReceiptsRoute.Token)
                        },
                        onSelect = { selectServerMode(ReadReceiptsServerMode.BUILT_IN, ReadReceiptsTunnelMode.TOKEN) },
                    )
                }
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_browser_login),
                        description = stringResource(R.string.read_receipts_browser_login_description),
                        selected = current.mode == ReadReceiptsServerMode.BUILT_IN &&
                            current.tunnelMode() == ReadReceiptsTunnelMode.BROWSER_LOGIN,
                        enabled = activeOperation == null,
                        trailingDivider = true,
                        onClick = {
                            if (requireCloudflared()) onOpen(ReadReceiptsRoute.Browser)
                        },
                        onSelect = { selectServerMode(ReadReceiptsServerMode.BUILT_IN, ReadReceiptsTunnelMode.BROWSER_LOGIN) },
                    )
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.read_receipts_section_send_mode)) {
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_mode_passive),
                        description = stringResource(R.string.read_receipts_mode_passive_description),
                        selected = sendMode == ReadReceipts.MODE_PASSIVE,
                        onClick = {
                            sendMode = ReadReceipts.MODE_PASSIVE
                            ReadReceipts.sendMode = ReadReceipts.MODE_PASSIVE
                        },
                    )
                }
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_mode_active_menu),
                        description = stringResource(R.string.read_receipts_mode_active_menu_description),
                        selected = sendMode == ReadReceipts.MODE_ACTIVE_MENU,
                        onClick = {
                            sendMode = ReadReceipts.MODE_ACTIVE_MENU
                            ReadReceipts.sendMode = ReadReceipts.MODE_ACTIVE_MENU
                        },
                    )
                }
                item {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_mode_active_prefix),
                        description = stringResource(R.string.read_receipts_mode_active_prefix_description),
                        selected = sendMode == ReadReceipts.MODE_ACTIVE_PREFIX,
                        onClick = {
                            sendMode = ReadReceipts.MODE_ACTIVE_PREFIX
                            ReadReceipts.sendMode = ReadReceipts.MODE_ACTIVE_PREFIX
                        },
                    )
                }
                item(key = "trigger_prefix", animatedVisibility = sendMode == ReadReceipts.MODE_ACTIVE_PREFIX) {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.chat_read_receipts_prefix),
                        value = triggerPrefix,
                        onValueChange = {
                            triggerPrefix = it
                            ReadReceipts.triggerPrefix = it
                        },
                        dialogTitle = stringResource(R.string.chat_read_receipts_prefix),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                    )
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.settings_section_configuration)) {
                item {
                    SwitchWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_automatic_lifecycle),
                        description = stringResource(R.string.read_receipts_automatic_lifecycle_description),
                        enabled = activeOperation == null,
                        checked = automaticLifecycle,
                        onCheckedChange = { automaticLifecycle = it },
                    )
                }
                item {
                    BaseItemContainer {
                        IntNumberPickerWidget(
                            title = stringResource(R.string.chat_read_receipts_poll_interval),
                            value = intervalSecs,
                            startInt = 1,
                            endInt = 60,
                            stepSize = 1,
                            enabled = activeOperation == null,
                            onValueChange = { intervalSecs = it },
                        )
                    }
                }
            }
        }
        item { OperationProgress(activeOperation) }
        item { PersistentFeedback(operationState.feedback) }
        item {
            ActionColumn {
                ActionRow {
                    OutlinedButton(onClick = ::saveGeneral, enabled = activeOperation == null, modifier = Modifier.weight(1f)) {
                        ActionLabel(R.string.action_save, activeOperation == ActiveOperation.SAVING)
                    }
                    OutlinedButton(
                        onClick = {
                            val owner = operationState.begin(ActiveOperation.DISCONNECTING)
                                ?: return@OutlinedButton
                            ReadReceipts.disconnectBuiltInStack { terminal ->
                                owner.complete(terminalFeedback(localizedContext, terminal, R.string.read_receipts_stack_stopped))
                            }
                        },
                        enabled = activeOperation == null && (runtime.originActive || runtime.tunnel.state != ReadReceiptsTunnelState.STOPPED),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) { ActionLabel(R.string.read_receipts_disconnect, activeOperation == ActiveOperation.DISCONNECTING) }
                }
            }
        }
    }
}

@Composable
private fun ThirdPartyScreen(
    operationCoordinator: SettingsOperationCoordinator,
    onBack: () -> Unit,
) {
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val initial = remember { ReadReceipts.configuration() }
    var url by rememberSaveable { mutableStateOf(initial.thirdPartyUrl) }
    val operationState = operationCoordinator.state(ReadReceiptsRoute.ThirdParty)
    val activeOperation = operationState.activeOperation

    fun candidate(): ReadReceiptsConfiguration? {
        val normalized = normalizeThirdPartyReadReceiptEndpoint(url) ?: run {
            operationState.feedback = localizedContext.getString(R.string.read_receipts_invalid_third_party_url).errorFeedback()
            return null
        }
        return ReadReceipts.configuration().copy(
            mode = ReadReceiptsServerMode.THIRD_PARTY,
            thirdPartyUrl = normalized,
        )
    }

    DetailScaffold(R.string.read_receipts_third_party_server, onBack) {
        item {
            SegmentedColumn(title = stringResource(R.string.read_receipts_section_connection)) {
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.read_receipts_server_url),
                        value = url,
                        onValueChange = { url = it },
                        dialogTitle = stringResource(R.string.read_receipts_server_url),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        enabled = activeOperation == null,
                        keyboardType = KeyboardType.Uri,
                    )
                }
            }
        }
        item { OperationProgress(activeOperation) }
        item { PersistentFeedback(operationState.feedback) }
        item {
            ActionColumn {
                ActionRow {
                    OutlinedButton(
                        onClick = {
                            if (normalizeThirdPartyReadReceiptEndpoint(url) == null) {
                                operationState.feedback = localizedContext.getString(R.string.read_receipts_invalid_third_party_url).errorFeedback()
                                return@OutlinedButton
                            }
                            val owner = operationState.begin(ActiveOperation.TESTING)
                                ?: return@OutlinedButton
                            ReadReceipts.testThirdPartyEndpoint(url, operationCoordinator.retainedScope) { result ->
                                val message = localizedContext.getString(
                                    if (result.isSuccess) R.string.read_receipts_server_connection_succeeded
                                    else R.string.read_receipts_server_connection_failed,
                                )
                                owner.complete(if (result.isSuccess) message.successFeedback() else message.errorFeedback())
                            } ?: owner.complete(localizedContext.getString(R.string.read_receipts_invalid_third_party_url).errorFeedback())
                        },
                        enabled = activeOperation == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (activeOperation == ActiveOperation.TESTING) LoadingIcon() else Icon(MaterialSymbols.Outlined.Refresh, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.read_receipts_test_connection))
                    }
                    Button(
                        onClick = { candidate()?.let { saveOnly(localizedContext, it, operationState) } },
                        enabled = activeOperation == null,
                        modifier = Modifier.weight(1f),
                    ) { ActionLabel(R.string.action_save, activeOperation == ActiveOperation.SAVING) }
                }
            }
        }
    }
}

@Composable
private fun QuickTunnelScreen(
    operationCoordinator: SettingsOperationCoordinator,
    onBack: () -> Unit,
) {
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val initial = remember { ReadReceipts.configuration() }
    var automaticPort by rememberSaveable { mutableStateOf(initial.automaticPort) }
    var port by rememberSaveable { mutableStateOf(initial.builtInPort.toString()) }
    val operationState = operationCoordinator.state(ReadReceiptsRoute.Quick)
    val activeOperation = operationState.activeOperation
    val runtime = rememberRuntimeSnapshot(ReadReceiptsRoute.Quick, operationCoordinator)

    fun candidate(): ReadReceiptsConfiguration? = builtInCandidate(
        localizedContext, automaticPort, port, ReadReceiptsTunnelMode.QUICK, "", feedback = { operationState.feedback = it },
    )

    DetailScaffold(R.string.read_receipts_quick_tunnel, onBack) {
        item { PortSection(automaticPort, port, activeOperation == null, { automaticPort = it }, { port = it }) }
        item { RuntimeSection(runtime) }
        item { OperationProgress(activeOperation) }
        item { PersistentFeedback(operationState.feedback) }
        item {
            RuntimeActions(
                activeOperation = activeOperation,
                originActive = runtime.originActive,
                connected = runtime.tunnel.state == ReadReceiptsTunnelState.CONNECTED,
                onSave = { candidate()?.let { saveOnly(localizedContext, it, operationState) } },
                onConnect = {
                    candidate()?.let { value ->
                        val owner = operationState.begin(ActiveOperation.CONNECTING)
                            ?: return@let
                        ReadReceipts.applyAndStartBuiltInStack(value, null) {
                            owner.complete(terminalFeedback(localizedContext, it, R.string.read_receipts_connection_succeeded_persistent))
                        }
                    }
                },
                onDisconnect = { disconnect(localizedContext, operationState) },
            )
        }
    }
}

@Composable
private fun TokenTunnelScreen(
    operationCoordinator: SettingsOperationCoordinator,
    onBack: () -> Unit,
) {
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val initial = remember { ReadReceipts.configuration() }
    var automaticPort by rememberSaveable { mutableStateOf(initial.automaticPort) }
    var port by rememberSaveable { mutableStateOf(initial.builtInPort.toString()) }
    var hostname by rememberSaveable { mutableStateOf(initial.hostname) }
    var token by rememberSaveable { mutableStateOf("") }
    val operationState = operationCoordinator.state(ReadReceiptsRoute.Token)
    val activeOperation = operationState.activeOperation
    var confirmDelete by remember { mutableStateOf(false) }
    val runtime = rememberRuntimeSnapshot(ReadReceiptsRoute.Token, operationCoordinator)
    val tokenSaved = runtime.credentialExists &&
        ReadReceiptsTunnelController.committedCredentialMetadata?.source == TunnelCredentialSource.TOKEN

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.read_receipts_delete_token_confirm_title)) },
            text = { Text(stringResource(R.string.read_receipts_delete_token_confirm_message)) },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.dialog_cancel)) }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        val owner = operationState.begin(ActiveOperation.DELETING)
                            ?: return@Button
                        ReadReceipts.disconnectBuiltInStack { terminal ->
                            if (terminal is OriginRequestTerminal.Completed && terminal.result.isSuccess) {
                                ReadReceiptsTunnelController.deleteCredential()
                                ReadReceiptsTunnelController.refresh()
                                owner.complete(localizedContext.getString(R.string.read_receipts_saved_token_deleted).infoFeedback())
                            } else {
                                owner.complete(terminalFeedback(localizedContext, terminal, R.string.read_receipts_saved_token_deleted))
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.dialog_confirm)) }
            },
        )
    }

    fun candidate(): ReadReceiptsConfiguration? = builtInCandidate(
        localizedContext, automaticPort, port, ReadReceiptsTunnelMode.TOKEN, hostname, feedback = { operationState.feedback = it },
    )

    DetailScaffold(R.string.read_receipts_tunnel_token, onBack) {
        item {
            SegmentedColumn(title = stringResource(R.string.read_receipts_section_credential)) {
                item {
                    StatusWidget(
                        title = if (runtime.metadataLoading) stringResource(R.string.common_loading) else stringResource(if (tokenSaved) R.string.read_receipts_tunnel_token_saved else R.string.read_receipts_no_saved_token),
                        description = stringResource(
                            if (token.isBlank()) R.string.read_receipts_token_saved_state_description
                            else R.string.read_receipts_token_replace_description,
                        ),
                        error = false,
                        loading = runtime.metadataLoading,
                    )
                }
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.read_receipts_tunnel_token),
                        value = token,
                        onValueChange = { token = it },
                        dialogTitle = stringResource(R.string.read_receipts_tunnel_token),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        enabled = activeOperation == null,
                        keyboardType = KeyboardType.Password,
                        password = true,
                        valueHint = stringResource(
                            if (tokenSaved) R.string.read_receipts_replace_saved_token else R.string.read_receipts_enter_token,
                        ),
                    )
                }
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.read_receipts_public_hostname),
                        value = hostname,
                        onValueChange = { hostname = it },
                        dialogTitle = stringResource(R.string.read_receipts_public_hostname),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        enabled = activeOperation == null,
                        keyboardType = KeyboardType.Uri,
                    )
                }
            }
        }
        item { PortSection(automaticPort, port, activeOperation == null, { automaticPort = it }, { port = it }, managed = true) }
        item { RuntimeSection(runtime) }
        item { OperationProgress(activeOperation) }
        item { PersistentFeedback(operationState.feedback) }
        item {
            ActionColumn {
                ActionRow {
                    Button(
                        onClick = {
                            val value = candidate() ?: return@Button
                            if (token.isBlank() && !tokenSaved) {
                                operationState.feedback = localizedContext.getString(R.string.read_receipts_error_token_required).errorFeedback()
                                return@Button
                            }
                            val owner = operationState.begin(ActiveOperation.CONNECTING)
                                ?: return@Button
                            ReadReceipts.applyAndStartBuiltInStack(value, token.takeIf(String::isNotBlank)) {
                                if (it is OriginRequestTerminal.Completed && it.result.isSuccess) token = ""
                                owner.complete(terminalFeedback(localizedContext, it, R.string.read_receipts_connection_succeeded_persistent))
                            }
                        },
                        enabled = activeOperation == null && (!runtime.metadataLoading || token.isNotBlank()),
                        modifier = Modifier.weight(1f),
                    ) { ActionLabel(if (runtime.tunnel.state == ReadReceiptsTunnelState.CONNECTED) R.string.read_receipts_reconnect else R.string.read_receipts_verify_and_connect, activeOperation == ActiveOperation.CONNECTING) }
                    OutlinedButton(
                        onClick = { disconnect(localizedContext, operationState) },
                        enabled = activeOperation == null && (runtime.originActive || runtime.tunnel.state != ReadReceiptsTunnelState.STOPPED),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) { ActionLabel(R.string.read_receipts_disconnect, activeOperation == ActiveOperation.DISCONNECTING) }
                }
                ActionRow {
                    OutlinedButton(
                        onClick = { candidate()?.let { saveOnly(localizedContext, it, operationState) } },
                        enabled = activeOperation == null,
                        modifier = Modifier.weight(1f),
                    ) { ActionLabel(R.string.action_save, activeOperation == ActiveOperation.SAVING) }
                    if (tokenSaved) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = activeOperation == null,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (activeOperation == ActiveOperation.DELETING) LoadingIcon() else Icon(MaterialSymbols.Outlined.Delete, null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.read_receipts_delete_saved_token))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserTunnelScreen(
    operationCoordinator: SettingsOperationCoordinator,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val initial = remember { ReadReceipts.configuration() }
    var port by rememberSaveable { mutableStateOf(initial.builtInPort.toString()) }
    var selectedTunnelId by rememberSaveable { mutableStateOf(initial.selectedTunnelId) }
    var selectedHostname by rememberSaveable { mutableStateOf(initial.hostname) }
    var manualHostname by rememberSaveable { mutableStateOf(false) }
    val operationState = operationCoordinator.state(ReadReceiptsRoute.Browser)
    val activeOperation = operationState.activeOperation
    var confirmLogout by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf(ReadReceiptsTunnelController.browserLoginState) }
    var tunnels by remember { mutableStateOf(ReadReceiptsTunnelController.browserExistingTunnels) }
    val runtime = rememberRuntimeSnapshot(ReadReceiptsRoute.Browser, operationCoordinator)

    var hydratedAuthority by remember { mutableStateOf<CommittedBrowserTunnelMetadata?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            login = ReadReceiptsTunnelController.browserLoginState
            tunnels = ReadReceiptsTunnelController.browserExistingTunnels
            val authority = when (val decision = ReadReceiptsTunnelController.browserMetadataRebindDecision) {
                BrowserMetadataRebindDecision.Keep -> null
                is BrowserMetadataRebindDecision.Replace -> decision.metadata
            }
            if (
                ReadReceipts.configuration().tunnelMode() == ReadReceiptsTunnelMode.BROWSER_LOGIN &&
                authority != null &&
                authority != hydratedAuthority
            ) {
                port = authority.fixedOriginPort.toString()
                selectedTunnelId = authority.tunnelId
                selectedHostname = authority.canonicalHostname
                manualHostname = tunnels
                    .firstOrNull { it.id == authority.tunnelId }
                    ?.hostnames
                    ?.any { "https://$it" == authority.canonicalHostname } != true
                hydratedAuthority = authority
            }
            delay(500.milliseconds)
        }
    }
    val selectedTunnel = tunnels.firstOrNull { it.id == selectedTunnelId }
    val selectedHostnameListed = selectedTunnel?.hostnames?.any { "https://$it" == selectedHostname } == true
    val loginBusy = login.state == ReadReceiptsTunnelState.STOPPING
    val actionsBusy = activeOperation != null || loginBusy
    val commitPending = activeOperation == ActiveOperation.COMMITTING

    fun runBrowserAction(operation: ActiveOperation, block: suspend () -> Result<Unit>) {
        operationCoordinator.launch(ReadReceiptsRoute.Browser, operation) {
            val result = block()
            result.fold(
                onSuccess = { localizedContext.getString(R.string.read_receipts_operation_completed).successFeedback() },
                onFailure = { ReadReceiptsUiText.from(it, R.string.read_receipts_unknown_error).resolve(localizedContext).errorFeedback() },
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.read_receipts_logout_confirm_title)) },
            text = { Text(stringResource(R.string.read_receipts_logout_confirm_message)) },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.dialog_cancel)) }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmLogout = false
                        runBrowserAction(ActiveOperation.LOGGING_OUT) { ReadReceiptsTunnelController.logoutBrowserLogin() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.dialog_confirm)) }
            },
        )
    }

    DetailScaffold(R.string.read_receipts_browser_login, onBack) {
        item {
            SegmentedColumn(title = stringResource(R.string.read_receipts_section_account)) {
                item {
                    StatusWidget(
                        title = stringResource(R.string.read_receipts_cloudflare_login),
                        description = buildString {
                            append(browserLoginStateLabel(login.state, ReadReceiptsTunnelController.browserLoginRestartRequired))
                            ReadReceiptsTunnelController.browserAccountId.takeIf(String::isNotBlank)?.let {
                                append("\n"); append(stringResource(R.string.read_receipts_account_id, it))
                            }
                        },
                        error = login.state == ReadReceiptsTunnelState.FAILED,
                    )
                }
                login.error?.let { error -> item { ErrorWidget(browserProtocolErrorMessage(error)) } }
                if (ReadReceiptsTunnelController.credentialMetadataLoading) {
                    item { StatusWidget(stringResource(R.string.read_receipts_authoritative_metadata), stringResource(R.string.common_loading), false, true) }
                }
                item {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = stringResource(if (ReadReceiptsTunnelController.browserLoginRestartRequired) R.string.read_receipts_restart_login else R.string.read_receipts_login),
                        description = stringResource(R.string.read_receipts_browser_login_description),
                        enabled = !actionsBusy,
                        onClick = {
                            operationCoordinator.launch(ReadReceiptsRoute.Browser, ActiveOperation.LOGIN) {
                                runCatching { ReadReceiptsTunnelController.beginBrowserLogin() }.fold(
                                    onSuccess = { state ->
                                        login = state
                                        val url = state.authorizationUrl
                                        if (url == null) {
                                            localizedContext.getString(R.string.read_receipts_authorization_required).infoFeedback()
                                        } else {
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                                .fold(
                                                    onSuccess = { OperationFeedback() },
                                                    onFailure = {
                                                        operationState.feedback = localizedContext.getString(R.string.read_receipts_authorization_open_failed).errorFeedback()
                                                        localizedContext.getString(R.string.read_receipts_authorization_required).infoFeedback()
                                                    },
                                                )
                                        }
                                    },
                                    onFailure = {
                                        ReadReceiptsUiText.from(it, R.string.read_receipts_browser_login_start_failed)
                                            .resolve(localizedContext).errorFeedback()
                                    },
                                )
                            }
                        },
                        trailingContent = {
                            if (activeOperation == ActiveOperation.LOGIN) LoadingIcon()
                            else Icon(MaterialSymbols.Outlined.Open_in_new, null)
                        },
                    )
                }
                login.authorizationUrl?.let { authorizationUrl ->
                    item {
                        BaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.read_receipts_open_authorization_page),
                            description = authorizationUrl,
                            enabled = !actionsBusy,
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl))) }
                                    .onFailure { operationState.feedback = localizedContext.getString(R.string.read_receipts_authorization_open_failed).errorFeedback() }
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        copyToClipboard(context, authorizationUrl)
                                        operationState.feedback = localizedContext.getString(R.string.read_receipts_authorization_link_copied).successFeedback()
                                    },
                                ) {
                                    Icon(
                                        MaterialSymbols.Outlined.Content_copy,
                                        stringResource(R.string.read_receipts_copy_authorization_link),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            ActionColumn(bottomPadding = 8.dp) {
                ActionRow {
                    OutlinedButton(
                        onClick = {
                            operationCoordinator.launch(ReadReceiptsRoute.Browser, ActiveOperation.REFRESHING) {
                                runCatching { ReadReceiptsTunnelController.listExistingTunnels() }.fold(
                                    onSuccess = {
                                        tunnels = it
                                        localizedContext.getString(R.string.read_receipts_tunnel_list_refreshed).successFeedback()
                                    },
                                    onFailure = {
                                        ReadReceiptsUiText.from(it, R.string.read_receipts_tunnel_list_refresh_failed)
                                            .resolve(localizedContext).errorFeedback()
                                    },
                                )
                            }
                        },
                        enabled = login.state == ReadReceiptsTunnelState.CONNECTED && !actionsBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (activeOperation == ActiveOperation.REFRESHING) LoadingIcon() else Icon(MaterialSymbols.Outlined.Refresh, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.read_receipts_refresh_tunnels))
                    }
                    if (login.state == ReadReceiptsTunnelState.STARTING) {
                        TextButton(
                            onClick = { runBrowserAction(ActiveOperation.CANCELLING_LOGIN) { ReadReceiptsTunnelController.cancelBrowserLogin() } },
                            enabled = activeOperation == null,
                            modifier = Modifier.weight(1f),
                        ) { ActionLabel(R.string.read_receipts_cancel_login, activeOperation == ActiveOperation.CANCELLING_LOGIN) }
                    }
                    if (login.state == ReadReceiptsTunnelState.CONNECTED) {
                        TextButton(
                            onClick = { confirmLogout = true },
                            enabled = !actionsBusy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                        ) { ActionLabel(R.string.read_receipts_logout, activeOperation == ActiveOperation.LOGGING_OUT) }
                    }
                }
            }
        }
        if (tunnels.isEmpty()) {
            item { EmptyState(stringResource(R.string.read_receipts_tunnel_list_empty)) }
        } else {
            item { SectionTitle(stringResource(R.string.read_receipts_section_tunnels)) }
            lazySegmentedItems(tunnels, key = ExistingTunnel::id) { tunnel ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    RadioButtonWidget(
                        iconPlaceholder = false,
                        title = tunnel.name,
                        description = stringResource(R.string.read_receipts_tunnel_id, tunnel.id),
                        selected = selectedTunnelId == tunnel.id,
                        enabled = !actionsBusy,
                        onClick = {
                            selectedTunnelId = tunnel.id
                            manualHostname = tunnel.hostnames.isEmpty()
                            selectedHostname = tunnel.hostnames.firstOrNull()?.let { "https://$it" }.orEmpty()
                        },
                    )
                }
            }
        }
        selectedTunnel?.let { tunnel ->
            item {
                SegmentedColumn(title = stringResource(R.string.read_receipts_section_hostname)) {
                    tunnel.hostnames.forEach { host ->
                        val root = "https://$host"
                        item(key = host) {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = host,
                                selected = !manualHostname && selectedHostname == root,
                                enabled = !actionsBusy,
                                onClick = { manualHostname = false; selectedHostname = root },
                            )
                        }
                    }
                    item {
                        RadioButtonWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.read_receipts_manual_hostname),
                            description = stringResource(R.string.read_receipts_manual_hostname_description),
                            selected = manualHostname || !selectedHostnameListed,
                            enabled = !actionsBusy,
                            onClick = { manualHostname = true; selectedHostname = "" },
                        )
                    }
                    if (manualHostname || !selectedHostnameListed) {
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.read_receipts_public_hostname),
                                value = selectedHostname,
                                onValueChange = { selectedHostname = it },
                                dialogTitle = stringResource(R.string.read_receipts_public_hostname),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                enabled = !actionsBusy,
                                keyboardType = KeyboardType.Uri,
                            )
                        }
                    }
                }
            }
        }
        item {
            SegmentedColumn(title = stringResource(R.string.read_receipts_section_port)) {
                item {
                    BaseWidget(
                        iconPlaceholder = false,
                        title = stringResource(R.string.read_receipts_automatic_port),
                        description = stringResource(R.string.read_receipts_browser_fixed_port_description),
                    )
                }
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.read_receipts_loopback_port),
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        dialogTitle = stringResource(R.string.read_receipts_loopback_port),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        enabled = !actionsBusy,
                        keyboardType = KeyboardType.Number,
                    )
                }
                item { ErrorWidget(stringResource(R.string.read_receipts_fixed_port_warning)) }
            }
        }
        item { RuntimeSection(runtime) }
        if (commitPending) item { PersistentFeedback(localizedContext.getString(R.string.read_receipts_browser_commit_pending).infoFeedback(), loading = true) }
        item { OperationProgress(activeOperation, hideWhen = setOf(ActiveOperation.CONNECTING, ActiveOperation.RECONNECTING)) }
        item { PersistentFeedback(operationState.feedback) }
        item {
            ActionColumn {
                ActionRow {
                    Button(
                        onClick = {
                            val tunnel = selectedTunnel ?: run { operationState.feedback = localizedContext.getString(R.string.read_receipts_invalid_cloudflare_tunnel).errorFeedback(); return@Button }
                            val fixedPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: run { operationState.feedback = localizedContext.getString(R.string.read_receipts_invalid_loopback_port).errorFeedback(); return@Button }
                            val root = ReadReceiptsTunnelHostnames.canonicalPublicRoot(selectedHostname) ?: run { operationState.feedback = localizedContext.getString(R.string.read_receipts_invalid_public_hostname).errorFeedback(); return@Button }
                            val value = ReadReceipts.configuration().copy(
                                mode = ReadReceiptsServerMode.BUILT_IN,
                                automaticPort = false,
                                builtInPort = fixedPort,
                                tunnelMode = ReadReceiptsTunnelMode.BROWSER_LOGIN.name,
                                hostname = root,
                                selectedTunnelId = tunnel.id,
                                selectedTunnelName = tunnel.name,
                            )
                            val owner = operationState.begin(ActiveOperation.CONNECTING)
                                ?: return@Button
                            ReadReceipts.applyAndSelectBrowserStack(value, {
                                owner.transition(ActiveOperation.COMMITTING)
                            }) {
                                owner.complete(terminalFeedback(localizedContext, it, R.string.read_receipts_browser_tunnel_connected))
                            }
                        },
                        enabled = login.state == ReadReceiptsTunnelState.CONNECTED && selectedTunnel != null && !actionsBusy,
                        modifier = Modifier.weight(1f),
                    ) { ActionLabel(R.string.read_receipts_select_and_verify, activeOperation == ActiveOperation.CONNECTING) }
                    Button(
                        onClick = {
                            val owner = operationState.begin(ActiveOperation.RECONNECTING)
                                ?: return@Button
                            ReadReceipts.reconnectAuthoritativeBrowserStack(ReadReceipts.configuration()) {
                                owner.complete(terminalFeedback(localizedContext, it, R.string.read_receipts_browser_tunnel_connected))
                            }
                        },
                        enabled = ReadReceipts.configuration().selectedTunnelId.isNotBlank() && !actionsBusy,
                        modifier = Modifier.weight(1f),
                    ) { ActionLabel(R.string.read_receipts_reconnect_saved, activeOperation == ActiveOperation.RECONNECTING) }
                }
                ActionRow {
                    OutlinedButton(
                        onClick = { disconnect(localizedContext, operationState) },
                        enabled = !actionsBusy && (runtime.originActive || runtime.tunnel.state != ReadReceiptsTunnelState.STOPPED),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) { ActionLabel(R.string.read_receipts_disconnect, activeOperation == ActiveOperation.DISCONNECTING) }
                }
            }
        }
    }
}

@Composable
private fun DetailScaffold(@StringRes title: Int, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    M3ListScaffold(
        title = stringResource(title),
        navigationIcon = { ExpressiveBackButton(onClick = onBack) },
        content = content,
    )
}

@Composable
private fun StatusWidget(title: String, description: String, error: Boolean, loading: Boolean = false) {
    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = description,
        isError = error,
        trailingContent = { if (loading) LoadingIcon() },
    )
}

@Composable
private fun ErrorWidget(message: String) {
    BaseWidget(iconPlaceholder = false, title = message, isError = true)
}

@Composable
private fun PortSection(
    automatic: Boolean,
    port: String,
    enabled: Boolean,
    onAutomaticChange: (Boolean) -> Unit,
    onPortChange: (String) -> Unit,
    managed: Boolean = false,
) {
    SegmentedColumn(title = stringResource(R.string.read_receipts_section_port)) {
        item {
            SwitchWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.read_receipts_automatic_port),
                description = stringResource(R.string.read_receipts_automatic_port_description),
                enabled = enabled,
                checked = automatic,
                onCheckedChange = onAutomaticChange,
                isError = managed && automatic,
            )
        }
        if (!automatic) {
            item {
                TextFieldDialogWidget(
                    title = stringResource(R.string.read_receipts_loopback_port),
                    value = port,
                    onValueChange = { onPortChange(it.filter(Char::isDigit).take(5)) },
                    dialogTitle = stringResource(R.string.read_receipts_loopback_port),
                    confirmLabel = stringResource(R.string.dialog_confirm),
                    dismissLabel = stringResource(R.string.dialog_cancel),
                    enabled = enabled,
                    keyboardType = KeyboardType.Number,
                )
            }
        }
        if (managed) item { ErrorWidget(stringResource(R.string.read_receipts_fixed_port_warning)) }
    }
}

@Composable
private fun RuntimeSection(runtime: RuntimeSnapshot) {
    SegmentedColumn(title = stringResource(R.string.read_receipts_section_runtime)) {
        item {
            StatusWidget(
                stringResource(R.string.read_receipts_built_in_server),
                originDescription(runtime.origin, includeDetails = true),
                runtime.origin.state == ReadReceiptsRuntimeState.FAILED,
            )
        }
        item {
            StatusWidget(
                stringResource(R.string.read_receipts_public_tunnel),
                tunnelDescription(runtime.tunnel),
                runtime.tunnel.state in setOf(ReadReceiptsTunnelState.FAILED, ReadReceiptsTunnelState.NEEDS_USER_ACTION),
            )
        }
    }
}

@Composable
private fun PersistentFeedback(feedback: OperationFeedback, loading: Boolean = false) {
    if (feedback.message.isEmpty()) return
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) LoadingIcon()
            Text(
                text = feedback.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (feedback.severity == FeedbackSeverity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ActionColumn(bottomPadding: androidx.compose.ui.unit.Dp = 32.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun RuntimeActions(
    activeOperation: ActiveOperation?,
    originActive: Boolean,
    connected: Boolean,
    onSave: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    ActionColumn {
        ActionRow {
            Button(onClick = onConnect, enabled = activeOperation == null, modifier = Modifier.weight(1f)) {
                ActionLabel(
                    if (connected) R.string.read_receipts_reconnect else R.string.read_receipts_verify_and_connect,
                    activeOperation == ActiveOperation.CONNECTING,
                )
            }
            OutlinedButton(
                onClick = onDisconnect,
                enabled = activeOperation == null && (originActive || connected),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f),
            ) { ActionLabel(R.string.read_receipts_disconnect, activeOperation == ActiveOperation.DISCONNECTING) }
        }
        ActionRow {
            OutlinedButton(onClick = onSave, enabled = activeOperation == null, modifier = Modifier.weight(1f)) {
                ActionLabel(R.string.action_save, activeOperation == ActiveOperation.SAVING)
            }
        }
    }
}

@Composable
private fun ActionLabel(@StringRes label: Int, loading: Boolean) {
    if (loading) {
        LoadingIcon()
        Spacer(Modifier.size(8.dp))
    }
    Text(stringResource(label))
}

@Composable
private fun OperationProgress(
    operation: ActiveOperation?,
    hideWhen: Set<ActiveOperation> = emptySet(),
) {
    if (operation == null || operation in hideWhen) return
    PersistentFeedback(stringResource(operation.progressRes).infoFeedback(), loading = true)
}

@Composable private fun LoadingIcon() = CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 16.dp),
    )
}

private fun builtInCandidate(
    localizedContext: android.content.Context,
    automaticPort: Boolean,
    port: String,
    mode: ReadReceiptsTunnelMode,
    hostname: String,
    feedback: (OperationFeedback) -> Unit,
): ReadReceiptsConfiguration? {
    val current = ReadReceipts.configuration()
    if (mode != ReadReceiptsTunnelMode.QUICK && automaticPort) {
        feedback(localizedContext.getString(R.string.read_receipts_tunnel_mode_requires_fixed_port).errorFeedback())
        return null
    }
    val portValue = if (automaticPort) {
        current.builtInPort
    } else {
        port.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
            feedback(localizedContext.getString(R.string.read_receipts_invalid_loopback_port).errorFeedback())
            return null
        }
    }
    val canonicalHostname = if (mode == ReadReceiptsTunnelMode.QUICK) current.hostname else {
        ReadReceiptsTunnelHostnames.canonicalPublicRoot(hostname) ?: run {
            feedback(localizedContext.getString(R.string.read_receipts_invalid_public_hostname).errorFeedback())
            return null
        }
    }
    return current.copy(
        mode = ReadReceiptsServerMode.BUILT_IN,
        automaticPort = automaticPort,
        builtInPort = portValue,
        tunnelMode = mode.name,
        hostname = canonicalHostname,
    )
}

private fun saveOnly(
    localizedContext: android.content.Context,
    candidate: ReadReceiptsConfiguration,
    operationState: SettingsOperationState,
) {
    val previous = ReadReceipts.configuration()
    when (
        readReceiptsConfigurationSaveAction(
            previous = previous,
            candidate = candidate,
            originWasActive = ReadReceipts.originActive(),
            featureActive = ReadReceipts.isActive,
        )
    ) {
        ReadReceiptsConfigurationSaveAction.COMMIT -> {
            ReadReceipts.saveConfiguration(candidate)
            operationState.feedback = localizedContext.getString(R.string.read_receipts_settings_saved).successFeedback()
        }

        ReadReceiptsConfigurationSaveAction.STOP_THEN_COMMIT -> {
            val owner = operationState.begin(ActiveOperation.SAVING) ?: return
            ReadReceipts.applyConfigurationAfterStoppingStack(candidate) { terminal ->
                owner.complete(terminalFeedback(localizedContext, terminal, R.string.read_receipts_settings_saved))
            }
        }

        ReadReceiptsConfigurationSaveAction.TRANSACTIONAL_START,
        ReadReceiptsConfigurationSaveAction.TRANSACTIONAL_REPLACE,
        -> {
            val owner = operationState.begin(ActiveOperation.SAVING) ?: return
            if (candidate.tunnelMode() == ReadReceiptsTunnelMode.BROWSER_LOGIN) {
                ReadReceipts.reconnectAuthoritativeBrowserStack(candidate) { terminal ->
                    owner.complete(terminalFeedback(localizedContext, terminal, R.string.read_receipts_settings_saved))
                }
            } else {
                ReadReceipts.applyAndStartBuiltInStack(candidate, null) { terminal ->
                    owner.complete(terminalFeedback(localizedContext, terminal, R.string.read_receipts_settings_saved))
                }
            }
        }
    }
}

private fun disconnect(
    localizedContext: android.content.Context,
    operationState: SettingsOperationState,
) {
    val owner = operationState.begin(ActiveOperation.DISCONNECTING) ?: return
    ReadReceipts.disconnectBuiltInStack { terminal ->
        owner.complete(terminalFeedback(localizedContext, terminal, R.string.read_receipts_stack_stopped))
    }
}

private fun terminalFeedback(
    localizedContext: android.content.Context,
    terminal: OriginRequestTerminal<Unit>,
    @StringRes success: Int,
): OperationFeedback = when (terminal) {
    is OriginRequestTerminal.Completed -> terminal.result.fold(
        onSuccess = { localizedContext.getString(success).successFeedback() },
        onFailure = { ReadReceiptsUiText.from(it, R.string.read_receipts_unknown_error).resolve(localizedContext).errorFeedback() },
    )
    OriginRequestTerminal.Superseded -> localizedContext.getString(R.string.read_receipts_connection_superseded).errorFeedback()
}

@Composable
private fun originDescription(
    status: ReadReceiptsStatus,
    includeDetails: Boolean = false,
): String {
    val state = stringResource(status.state.labelRes())
    val description = buildList {
        add(state)
        if (includeDetails) {
            add(
                status.port?.let { stringResource(R.string.read_receipts_local_address, "http://127.0.0.1:$it") }
                    ?: stringResource(R.string.read_receipts_local_address, stringResource(R.string.read_receipts_not_ready)),
            )
            val database = NativeReadReceiptsServerController.databaseFile()
            add(
                stringResource(R.string.read_receipts_database_path, database.absolutePath) + "\n" +
                    stringResource(R.string.read_receipts_database_size, database.takeIf(File::exists)?.length() ?: 0L),
            )
        }
        status.error?.let { add(stringResource(R.string.read_receipts_built_in_server_error)) }
    }
    return description.joinToString("\n")
}

@Composable
private fun tunnelDescription(status: ReadReceiptsTunnelStatus): String = listOfNotNull(
    stringResource(status.state.labelRes()),
    status.errorCode?.let { stringResource(R.string.read_receipts_tunnel_error, stringResource(it.messageRes)) },
).joinToString("\n")

@Composable
private fun browserLoginStateLabel(state: ReadReceiptsTunnelState, restartRequired: Boolean): String = stringResource(
    if (restartRequired) R.string.read_receipts_browser_login_session_lost else when (state) {
        ReadReceiptsTunnelState.STOPPED -> R.string.read_receipts_browser_login_state_signed_out
        ReadReceiptsTunnelState.STARTING -> R.string.read_receipts_browser_login_state_waiting
        ReadReceiptsTunnelState.CONNECTED -> R.string.read_receipts_browser_login_state_authorized
        ReadReceiptsTunnelState.FAILED -> R.string.read_receipts_browser_login_state_failed
        ReadReceiptsTunnelState.RECONNECTING -> R.string.read_receipts_browser_login_state_restoring
        ReadReceiptsTunnelState.NEEDS_USER_ACTION -> R.string.read_receipts_state_needs_user_action
        ReadReceiptsTunnelState.STOPPING -> R.string.read_receipts_browser_login_state_cancelling
    },
)

@Composable
private fun browserProtocolErrorMessage(error: String): String {
    val messageRes = ReadReceiptsTunnelErrorCode.entries.firstOrNull { it.name == error }?.messageRes
        ?: R.string.read_receipts_unknown_error
    return stringResource(R.string.read_receipts_login_error, stringResource(messageRes))
}

@StringRes
private fun ReadReceiptsRuntimeState.labelRes(): Int = when (this) {
    ReadReceiptsRuntimeState.STOPPED -> R.string.read_receipts_state_stopped
    ReadReceiptsRuntimeState.STARTING -> R.string.read_receipts_state_starting
    ReadReceiptsRuntimeState.RUNNING -> R.string.read_receipts_state_running
    ReadReceiptsRuntimeState.STOPPING -> R.string.read_receipts_state_stopping
    ReadReceiptsRuntimeState.FAILED -> R.string.read_receipts_state_failed
}

@StringRes
private fun ReadReceiptsTunnelState.labelRes(): Int = when (this) {
    ReadReceiptsTunnelState.STOPPED -> R.string.read_receipts_state_stopped
    ReadReceiptsTunnelState.STARTING -> R.string.read_receipts_state_starting
    ReadReceiptsTunnelState.CONNECTED -> R.string.read_receipts_tunnel_state_connected
    ReadReceiptsTunnelState.RECONNECTING -> R.string.read_receipts_tunnel_state_reconnecting
    ReadReceiptsTunnelState.NEEDS_USER_ACTION -> R.string.read_receipts_state_needs_user_action
    ReadReceiptsTunnelState.FAILED -> R.string.read_receipts_state_failed
    ReadReceiptsTunnelState.STOPPING -> R.string.read_receipts_state_stopping
}

private fun shareUrl(context: android.content.Context, localizedContext: android.content.Context, url: String) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            },
            localizedContext.getString(R.string.read_receipts_share_url),
        ),
    )
}
