package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.wekit.R
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes

@Feature(
    id = "自动刷新",
    nameRes = "feature_auto_refresh_name",
    categoryIds = [FeatureCategoryIds.MOMENTS],
    descriptionRes = "feature_auto_refresh_description",
)
object AutoRefresh : ClickableFeature(), IResolveDex {

    private const val TAG = "AutoRefresh"
    private const val DEFAULT_INTERVAL_MINUTES = 30L

    private var intervalMinutes by WePrefs.prefOption("moments_auto_refresh_interval_minutes", DEFAULT_INTERVAL_MINUTES)

    fun interface IRefreshListener {
        fun onRefresh()
    }

    private val refreshListeners = CopyOnWriteArrayList<IRefreshListener>()

    fun addListener(listener: IRefreshListener) {
        refreshListeners.add(listener)
    }

    fun removeListener(listener: IRefreshListener) {
        refreshListeners.remove(listener)
    }

    private var refreshJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val methodGetSnsCore by dexMethod {
        matcher {
            usingEqStrings("getCore", "com.tencent.mm.plugin.sns.model.SnsCore")
        }
    }

    private val methodDoFpList by dexMethod {
        matcher {
            usingEqStrings("doFpList", $$"com.tencent.mm.plugin.sns.model.SnsLogic$SnsServer")
        }
    }

    private val snsLogicSnsServer by lazy {
        val snsCore = methodGetSnsCore.method.invoke(null)
        snsCore.reflekt().firstField {
            type = methodDoFpList.method.declaringClass
        }.get()!!
    }

    override fun onEnable() {
        startRefreshingJob()
    }

    override fun onDisable() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun startRefreshingJob() {
        refreshJob?.cancel()
        val interval = intervalMinutes.coerceAtLeast(1L)
        refreshJob = scope.launch {
            while (isActive) {
                delay(interval.minutes)
                refreshMoments()
            }
        }
    }

    private fun refreshMoments() {
        try {
            WeLogger.d(TAG, "refreshing moments")
            methodDoFpList.method.invoke(
                snsLogicSnsServer,
                1, "@__weixintimtline", false, false, 0
            )
            refreshListeners.forEach { it.onRefresh() }
        } catch (e: Exception) {
            WeLogger.w(TAG, "exception during refreshing: ${e.message}")
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var intervalInput by remember { mutableStateOf(intervalMinutes.toString()) }
            val localizedContext = LocalContext.current

            AlertDialogContent(
                title = { Text(stringResource(R.string.moments_auto_refresh_title)) },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        TextField(
                            value = intervalInput,
                            onValueChange = { intervalInput = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text(stringResource(R.string.moments_auto_refresh_interval)) },
                            supportingText = { Text(stringResource(R.string.moments_auto_refresh_interval_summary)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        intervalMinutes = (intervalInput.toLongOrNull() ?: DEFAULT_INTERVAL_MINUTES).coerceAtLeast(1L)
                        if (isEnabled) startRefreshingJob()
                        showToast(localizedContext.getString(R.string.settings_saved))
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
            )
        }
    }
}
