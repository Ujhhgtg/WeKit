package dev.ujhhgtg.wekit.features.items.beautify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import coil3.load
import coil3.request.crossfade
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.nul
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

@Feature(
    name = "应用全局背景", categories = ["界面美化"],
    description = "将微信背景全局替换为图片"
)
object ApplyGlobalBackground : ClickableFeature(), IResolveDex {

    private const val TAG = "ApplyGlobalBackground"

    private val methodInitImageView by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.base.MultiTouchImageView"
            modifiers(Modifiers.FINAL)
            returnType = "void"
            addInvoke {
                declaredClass = "android.widget.ImageView"
                name = "setScaleType"
            }
        }
    }

    private var backgroundUri by prefOption("global_bg_uri", nul<String>())
    private var transparentStatusBar by prefOption("global_bg_transparent_status_bar", false)
    private var opacity by prefOption("global_bg_opacity", 0.10f)

    private const val OVERLAY_TAG = "wekit_global_bg_overlay"
    private const val APPLIED_URI_TAG_KEY = 0x55020001
    private const val APPLY_STATUS_BAR_DELAY_MS = 80L

    /**
     * Activities that must never receive the background overlay — full-screen media viewers,
     * video recorders, scanners, and other UIs where a tinted overlay would be wrong.
     *
     * Note: ThumbPlayerViewContainer / ThumbPlayerVideoView are Views (FrameLayout / TextureView),
     * not Activities, so they were removed from this list — they would never match here.
     */
    private val blacklistedActivities = setOf(
        "${PackageNames.WECHAT}.plugin.sns.ui.SnsOnlineVideoActivity",
        "${PackageNames.WECHAT}.plugin.recordvideo.activity.MMRecordUI",
        "${PackageNames.WECHAT}.plugin.fav.ui.detail.FavoriteImgDetailUI",
        "${PackageNames.WECHAT}.plugin.scanner.ui.BaseScanUI",
        "${PackageNames.WECHAT}.plugin.finder.ui.FinderHomeAffinityUI",
        "${PackageNames.WECHAT}.plugin.lite.ui.WxaLiteAppLiteUI",
        "${PackageNames.WECHAT}.ui.chatting.gallery.ImageGalleryUI",
        "${PackageNames.WECHAT}.ui.chatting.gallery.ImageGalleryGridUI",
        "${PackageNames.WECHAT}.ui.chatting.gallery.MediaHistoryGalleryUI",
        "${PackageNames.WECHAT}.plugin.subapp.ui.gallery.GestureGalleryUI",
        "${PackageNames.WECHAT}.plugin.gallery.picker.view.ImageCropUI",
        "${PackageNames.WECHAT}.plugin.sns.ui.SnsBrowseUI",
        "${PackageNames.WECHAT}.plugin.finder.ui.FinderShareFeedRelUI",
        "${PackageNames.WECHAT}.plugin.gallery.ui.ImagePreviewUI",
        "${PackageNames.WECHAT}.plugin.gallery.ui.AlbumPreviewUI",
        "${PackageNames.WECHAT}.plugin.luckymoney.ui.LuckyMoneyBeforeDetailUI",
        "${PackageNames.WECHAT}.plugin.location_soso.SoSoProxyUI",
        "${PackageNames.WECHAT}.plugin.finder.feed.ui.FinderProfileTimeLineUI",
        "${PackageNames.WECHAT}.plugin.sns.ui.SnsGalleryUI",
        "${PackageNames.WECHAT}.pluginsdk.ui.ProfileHdHeadImg",
        "${PackageNames.WECHAT}.plugin.brandservice.ui.timeline.preload.ui.TmplWebViewMMUI",
        "${PackageNames.WECHAT}.plugin.voip.ui.VideoActivity"
    )

    override fun onEnable() {
        Activity::class.reflekt().apply {
            firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }

            firstMethod {
                name = "onStart"
                parameterCount = 0
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }

            firstMethod {
                name = "onResume"
                parameterCount = 0
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
                applyBackground(activity)
            }

            firstMethod {
                name = "onWindowFocusChanged"
                parameters(Boolean::class)
            }.hookAfter {
                val activity = thisObject as Activity
                applyTransparentStatusBarIfEnabled(activity)
            }
        }

        methodInitImageView.hookBefore {
            val view = thisObject as ImageView
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val activity = activityOf(v.context) ?: return
                    synchronized(activityAttachedViews) {
                        activityAttachedViews.getOrPut(activity) { mutableSetOf() }.add(v)
                    }
                    WeLogger.d(TAG, "view attached to ${activity.javaClass.simpleName}")
                    overlayFromActivity(activity)?.isVisible = false
                }

                override fun onViewDetachedFromWindow(v: View) {
                    val activity = activityOf(v.context) ?: return
                    val empty = synchronized(activityAttachedViews) {
                        val set = activityAttachedViews[activity] ?: return
                        set.remove(v)
                        set.isEmpty()
                    }
                    if (empty) {
                        WeLogger.d(TAG, "all views detached from ${activity.javaClass.simpleName}")
                        overlayFromActivity(activity)?.isVisible = true
                    }
                }
            })
        }
    }

    // Per-activity set of currently-attached MultiTouchImageViews.
    // Using a Set means duplicate OnAttachStateChangeListener registrations (caused by
    // t() being re-triggered via onMeasure after each setImageBitmap call on a recycled
    // ViewPager page) are harmless: add/remove are idempotent on a Set, so the counter
    // never goes negative regardless of how many listeners fire per attach/detach cycle.
    private val activityAttachedViews = WeakHashMap<Activity, MutableSet<View>>()

    private fun activityOf(ctx: Context): Activity? {
        var c = ctx
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun overlayFromActivity(activity: Activity): ImageView? =
        findOverlay(activity.window?.decorView as? ViewGroup ?: return null)

    private const val MIN = 0.01f
    private const val MAX = 0.80f
    private val MINIMAX = MIN..MAX
    private fun Float.miniMaxed() = this.coerceIn(MINIMAX)

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var hasImage by remember { mutableStateOf(backgroundUri != null) }
            var opacityInput by remember { mutableFloatStateOf(opacity) }
            var transparentStatusBarInput by remember { mutableStateOf(transparentStatusBar) }

            AlertDialogContent(
                title = { Text("应用全局背景") },
                text = {
                    DefaultColumn {
                        Text(
                            text = if (hasImage) {
                                "已设置背景图片"
                            } else {
                                "未设置背景图片"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = {
                                opacity = opacityInput.miniMaxed()
                                transparentStatusBar = transparentStatusBarInput
                                onDismiss()
                                selectBackgroundImage(context)
                            }) {
                                Text("选择图片")
                            }
                            TextButton(
                                enabled = hasImage,
                                onClick = {
                                    backgroundUri = null
                                    hasImage = false
                                    showToast("已清除背景图片, 重启微信生效")
                                }
                            ) {
                                Text("清除图片")
                            }
                        }
                        Text(
                            text = "透明度: ${(opacityInput * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Slider(
                            value = opacityInput,
                            onValueChange = { opacityInput = it.miniMaxed() },
                            valueRange = MINIMAX
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                transparentStatusBarInput = !transparentStatusBarInput
                            },
                            trailingContent = {
                                Switch(
                                    checked = transparentStatusBarInput,
                                    onCheckedChange = null
                                )
                            },
                            supportingContent = { Text("设置状态栏背景为透明") },
                            headlineContent = { Text("状态栏背景透明") },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        opacity = opacityInput.miniMaxed()
                        transparentStatusBar = transparentStatusBarInput
                        showToast("已保存, 重启微信生效")
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                }
            )
        }
    }

    private fun applyTransparentStatusBarIfEnabled(activity: Activity) {
        if (!transparentStatusBar) return
        applyTransparentStatusBar(activity)
    }

    @Suppress("DEPRECATION")
    private fun applyTransparentStatusBar(activity: Activity) {
        runCatching {
            val window = activity.window ?: return
            val decor = window.decorView as? ViewGroup ?: return

            window.statusBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = decor.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }

            clearStatusBarBackground(activity, decor)
            decor.postDelayed(APPLY_STATUS_BAR_DELAY_MS) {
                clearStatusBarBackground(activity, decor)
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to apply transparent status bar", it)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun clearStatusBarBackground(activity: Activity, decor: ViewGroup) {
        val statusBarBackgroundId = activity.resources.getIdentifier(
            "statusBarBackground",
            "id",
            "android"
        )

        if (statusBarBackgroundId != 0) {
            decor.findViewById<View>(statusBarBackgroundId)?.makeTransparent()
        }

        setLastViewsTransparent(decor, 3)
    }

    private fun setLastViewsTransparent(viewGroup: ViewGroup, count: Int) {
        val start = max(0, viewGroup.childCount - count)
        for (index in start until viewGroup.childCount) {
            val child = viewGroup.getChildAt(index)
            val name = child.resourceEntryName().orEmpty()
            if (name == "statusBarBackground" || child.height <= statusBarHeightGuess(child)) {
                child.makeTransparent()
            }
        }
    }

    private fun applyBackground(activity: Activity) {
        if (backgroundUri == null) return
        if (activity.javaClass.name in blacklistedActivities) return

        val uri = backgroundUri ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val overlay = findOverlay(decor) ?: createOverlay(activity, decor)

        overlay.visibility = View.VISIBLE
        overlay.alpha = opacity
        overlay.bringToFront()

        if (overlay.getTag(APPLIED_URI_TAG_KEY) != uri) {
            overlay.setTag(APPLIED_URI_TAG_KEY, uri)
            overlay.load(uri) {
                crossfade(true)
            }
        }
    }

    private fun createOverlay(context: Context, decor: ViewGroup): ImageView {
        return ImageView(context).apply {
            tag = OVERLAY_TAG
            background = null
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = 100f
            decor.addView(this)
        }
    }

    private fun findOverlay(decor: ViewGroup): ImageView? {
        for (index in 0 until decor.childCount) {
            val child = decor.getChildAt(index)
            if (child is ImageView && child.tag == OVERLAY_TAG) {
                return child
            }
        }
        return null
    }

    private fun View.makeTransparent() {
        setBackgroundColor(Color.TRANSPARENT)
        setBackgroundResource(0)
    }

    private fun View.resourceEntryName(): String? {
        val viewId = id
        if (viewId == View.NO_ID) return null
        return runCatching {
            resources.getResourceEntryName(viewId)
        }.getOrNull()
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeightGuess(view: View): Int {
        val resourceId = view.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            view.resources.getDimensionPixelSize(resourceId)
        } else {
            (32f * view.resources.displayMetrics.density).toInt()
        }
    }

    private fun selectBackgroundImage(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult

                val contentResolver = HostInfo.application.contentResolver
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }.onFailure {
                    WeLogger.w(TAG, "failed to take persistable uri permission", it)
                }

                backgroundUri = uri.toString()
                showToast("背景图片已设置, 重启微信生效")
            }

            launcher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
    }
}
