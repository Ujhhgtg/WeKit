package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

object AutoRefresh : ClickableFeature(), IResolveDex {

    override val technicalId = "自动刷新"
    override val nameRes = R.string.feature_auto_refresh_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_auto_refresh_description

    private const val TAG = "AutoRefresh"
    private const val DEFAULT_INTERVAL_MINUTES = 30L
    private const val MIN_INTERVAL_MINUTES = 1
    private const val MAX_INTERVAL_MINUTES = 120
    private const val REFRESH_TIMEOUT_MS = 60_000L

    private var intervalMinutes by WePrefs.prefOption("moments_auto_refresh_interval_minutes", DEFAULT_INTERVAL_MINUTES)

    fun interface IRefreshListener {
        /** The automatic timeline request has finished processing a valid response. */
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

    private class RefreshRequest(val account: String) {
        @Volatile
        var scene: Any? = null

        @Volatile
        var handledValidResponse = false

        val completion = CompletableDeferred<Boolean>()
    }

    private val pendingRefresh = AtomicReference<RefreshRequest?>()
    private val submittingRefresh = ThreadLocal<RefreshRequest>()

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

    private val ctorTimelineRequest by dexConstructor {
        matcher {
            declaredClass {
                usingEqStrings("/cgi-bin/micromsg-bin/mmsnstimeline")
            }
            paramTypes("long", "long", "int")
        }
    }

    private val methodHandleNormalResponse by dexMethod {
        matcher {
            declaredClass(ctorTimelineRequest.data.declaredClassName)
            usingEqStrings("handleNormalResp", "com.tencent.mm.plugin.sns.model.NetSceneSnsTimeLine")
            paramTypes("int", "int", "java.lang.String", null)
            returnType("void")
        }
    }

    private val methodTimelineResponseEnd by dexMethod {
        matcher {
            declaredClass(ctorTimelineRequest.data.declaredClassName)
            name = "onGYNetEnd"
            paramCount = 6
            returnType("void")
        }
    }

    override fun onEnable() {
        // The type=1 doFpList branch constructs its scene synchronously before enqueueing it.
        ctorTimelineRequest.hookAfter {
            val request = submittingRefresh.get() ?: return@hookAfter
            if (throwable == null && pendingRefresh.get() === request) {
                request.scene = thisObject!!
            }
        }
        methodHandleNormalResponse.hookAfter {
            val request = pendingRefresh.get() ?: return@hookAfter
            if (request.scene !== thisObject || throwable != null) return@hookAfter
            val errType = args[0] as Int
            val errCode = args[1] as Int
            // The host also handles the timeline's 207 response as a valid result.
            request.handledValidResponse = (errType == 0 && errCode == 0) ||
                    (errType == 4 && errCode == 207)
        }
        methodTimelineResponseEnd.hookAfter {
            val request = pendingRefresh.get() ?: return@hookAfter
            if (request.scene !== thisObject) return@hookAfter
            // Includes failures which never enter handleNormalResp. List writes have completed
            // before the normal-response handler returns; this does not wait for media downloads.
            request.completion.complete(throwable == null && request.handledValidResponse)
        }
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

    private suspend fun refreshMoments() {
        val account = WeApi.selfWxId
        if (account.isEmpty()) return
        val request = RefreshRequest(account)
        if (!pendingRefresh.compareAndSet(null, request)) return
        try {
            WeLogger.d(TAG, "refreshing moments")
            val snsCore = methodGetSnsCore.method.invoke(null)!!
            val server = snsCore.reflekt().firstField {
                type = methodDoFpList.method.declaringClass
            }.get()!!
            submittingRefresh.set(request)
            try {
                methodDoFpList.method.invoke(server, 1, "@__weixintimtline", false, false, 0)
            } finally {
                submittingRefresh.remove()
            }
            if (request.scene == null) {
                WeLogger.d(TAG, "timeline request was skipped by the host")
                return
            }
            val completed = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { request.completion.await() }
            when (completed) {
                true -> {
                    if (WeApi.selfWxId != request.account) return
                    WeLogger.d(TAG, "timeline response processed; notifying refresh listeners")
                    refreshListeners.forEach { listener ->
                        currentCoroutineContext().ensureActive()
                        try {
                            listener.onRefresh()
                        } catch (e: Exception) {
                            WeLogger.w(TAG, "refresh listener failed: ${listener.javaClass.name}", e)
                        }
                    }
                }
                false -> WeLogger.w(TAG, "timeline request did not finish with a valid response")
                null -> WeLogger.w(TAG, "timed out waiting for the timeline response")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WeLogger.w(TAG, "exception during refreshing", e)
        } finally {
            pendingRefresh.compareAndSet(request, null)
            request.completion.cancel()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val initialInterval = remember {
                intervalMinutes.coerceIn(
                    MIN_INTERVAL_MINUTES.toLong(),
                    MAX_INTERVAL_MINUTES.toLong(),
                ).toInt()
            }
            var sliderPosition by remember {
                mutableFloatStateOf(minutesToSliderPosition(initialInterval))
            }
            var intervalInput by remember { mutableIntStateOf(initialInterval) }
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

            AlertDialogContent(
                title = { Text(stringResource(R.string.moments_auto_refresh_title)) },
                text = {
                    BaseSupportingWidget(
                        title = stringResource(R.string.moments_auto_refresh_interval),
                        description = stringResource(R.string.moments_auto_refresh_interval_summary),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Slider(
                                value = sliderPosition,
                                onValueChange = {
                                    sliderPosition = it
                                    intervalInput = sliderPositionToMinutes(it)
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = intervalInput.toString(),
                                textAlign = TextAlign.End,
                                modifier = Modifier.defaultMinSize(minWidth = 36.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        intervalMinutes = intervalInput.toLong()
                        if (isEnabled) startRefreshingJob()
                        showToast(localizedContext.getString(R.string.settings_saved))
                        onDismiss()
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
            )
        }
    }

    private fun minutesToSliderPosition(minutes: Int): Float = when (minutes) {
        MIN_INTERVAL_MINUTES -> 0f
        MAX_INTERVAL_MINUTES -> 1f
        else -> (ln(minutes.toDouble()) / ln(MAX_INTERVAL_MINUTES.toDouble())).toFloat()
    }

    private fun sliderPositionToMinutes(position: Float): Int = when {
        position <= 0f -> MIN_INTERVAL_MINUTES
        position >= 1f -> MAX_INTERVAL_MINUTES
        else -> exp(position * ln(MAX_INTERVAL_MINUTES.toDouble()))
            .roundToInt()
            .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
    }
}
