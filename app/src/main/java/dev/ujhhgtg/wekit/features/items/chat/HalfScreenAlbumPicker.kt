package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.bool
import dev.ujhhgtg.wekit.utils.reflection.int

object HalfScreenAlbumPicker : ClickableFeature() {

    override val technicalId = "半屏相册选择器"
    override val nameRes = R.string.feature_half_screen_album_picker_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_half_screen_album_picker_description

    private const val TAG = "HalfScreenAlbumPicker"

    private const val ALBUM_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
    private const val IMAGE_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
    private const val SMART_GALLERY_UI = "com.tencent.mm.plugin.gallery.ui.SmartGalleryUI"
    private const val GALLERY_ENTRY_UI = "com.tencent.mm.plugin.gallery.ui.GalleryEntryUI"
    private const val MM_FRAGMENT_ACTIVITY = "com.tencent.mm.ui.MMFragmentActivity"

    /** The grid picker plus the two screens pushed on top of it. All three become sheets. */
    private val SHEET_ACTIVITIES = setOf(ALBUM_PREVIEW_UI, IMAGE_PREVIEW_UI, SMART_GALLERY_UI)

    /**
     * The two sheets the picker pushes on top of itself: the photo preview and the search screen.
     *
     * They share two traits the picker does not, and both branches below key off this set:
     *  - Neither gives itself the chat push-up animation, so they inherit the picker's captured
     *    pair instead of WeChat's default scale-and-fade.
     *  - Both are pinned with `android:screenOrientation="portrait"` in the manifest, which makes
     *    WeChat's redundant runtime setRequestedOrientation call safe to skip — see
     *    [hookOrientationRequests] for why skipping matters.
     */
    private val PUSHED_SHEETS = setOf(IMAGE_PREVIEW_UI, SMART_GALLERY_UI)

    /**
     * `query_source_type` values the chat "+" panel passes, both from ChattingFooterEventImpl.c(1):
     *
     *  - **3** — ordinary conversations, `l7.g(fragment, requestCode, 9, 3, …)`.
     *  - **12** — the branch for `@app` conversations (`z3.z3(talker)`, i.e. the talker ends with
     *    "@app"), `l7.g(fragment, requestCode, 9, 12, 1, …)`. Still a chat with ChattingUI behind
     *    it, and 12 occurs at that single call site in the whole APK.
     *
     * AlbumPreviewUI is shared by ~15 entry points and only the chat ones have a conversation
     * behind them worth revealing. The others are well clear of these two values: Moments 9,
     * emoji editor 11, notes 13, contacts/avatar 19, webview chooseImage 7/8/43, appbrand 45.
     * (Finder's picker is a different activity, FinderAlbumUI, so it never reaches this gate.)
     *
     * These must always be read with a default that is *not* one of them. WeChat's own reads use
     * `getIntExtra("query_source_type", 3)`, so a route that never sets the extra still looks like
     * the chat one to WeChat; reading with -1 keeps this gate to the routes that set a value
     * explicitly.
     *
     * The legacy `gallery == "0"` fallback path is deliberately still not covered — it passes 0
     * and is dead on modern builds.
     */
    private val CHAT_QUERY_SOURCE_TYPES = setOf(3, 12)

    /**
     * WeChat's generic close-animation extras, read by MMActivity.finish():
     *
     *     int enter = c2.g(getIntent(), "MMActivity.OverrideEnterAnimation", -1);
     *     int exit  = c2.g(getIntent(), "MMActivity.OverrideExitAnimation", -1);
     *     if (enter != -1) super.overridePendingTransition(enter, exit);
     */
    private const val EXTRA_ENTER_ANIMATION = "MMActivity.OverrideEnterAnimation"
    private const val EXTRA_EXIT_ANIMATION = "MMActivity.OverrideExitAnimation"

    private const val DEFAULT_HEIGHT_PERCENT = 70
    private const val MIN_HEIGHT_PERCENT = 40
    private const val MAX_HEIGHT_PERCENT = 90

    private const val CORNER_RADIUS_DP = 18f
    private const val DIM_AMOUNT = 0.3f

    private var heightPercent by prefOption(
        "half_screen_album_height_percent",
        DEFAULT_HEIGHT_PERCENT
    )

    /**
     * The enter/exit animation pair the picker gives itself, captured at runtime.
     *
     * AlbumPreviewUI.onCreate does `if (query_source_type == 3) overridePendingTransition(...)` —
     * the push-up slide the chat "+" panel uses. ImagePreviewUI makes no such call, so it keeps
     * WeChat's default open animation (the scale-and-fade from the screen center) that
     * MMFragmentActivity.initActivityOpenAnimation applies caller-side.
     *
     * Recording whichever pair the picker actually asks for beats hardcoding the obfuscated anim
     * resource name (`R.anim.df`), which is exactly the sort of thing that silently changes
     * between WeChat builds — and it makes the two animations match by construction. The picker
     * is always created before a preview can open, so the value is there in time.
     */
    @Volatile
    private var slideInTransition: Pair<Int, Int>? = null

    /** Live only while the picker is inside onCreate; see [installTransitionCapture]. */
    private var transitionCaptureUnhook: HookHandle? = null

    /**
     * The close animation pair the picker gives itself, read straight out of its intent.
     *
     * AlbumPreviewUI.finish() writes `(R.anim.p, R.anim.dd)` — the slide-down — into the two
     * extras below before calling super, so this one needs no framework hook to observe: its own
     * declared finish() is the whole story. ImagePreviewUI sets nothing, so it falls back to
     * WeChat's default close (uc.f228324g/h, the scale-and-fade).
     *
     * Unlike [slideInTransition] this cannot be captured before it is first needed — the picker
     * only emits it when it closes, which can happen after a preview has already been dismissed.
     * See the note on [applySlideOutTransition].
     */
    @Volatile
    private var slideOutTransition: Pair<Int, Int>? = null

    override fun onEnable() {
        // The image editor behind the 编辑 button is a separate activity —
        // com.tencent.mm.plugin.recordvideo.activity.MMRecordUI, started with
        // startActivityForResult (request code 4372) — so simply never hooking it is all it
        // takes to keep the editor full-screen.
        SHEET_ACTIVITIES.forEach { className ->
            val activityClass = className.toClass()

            // AlbumPreviewUI and ImagePreviewUI each declare their own onCreate, so these hooks
            // stay scoped to those two rather than landing on the inherited MMActivity.onCreate
            // and firing for every screen in WeChat.
            activityClass.hookBeforeOnCreate {
                val activity = thisObject as? Activity ?: return@hookBeforeOnCreate
                if (!isChatSheet(activity)) return@hookBeforeOnCreate
                if (activity.javaClass.name == ALBUM_PREVIEW_UI) {
                    installTransitionCapture(activity)
                }
                suppressStatusBarPadding(activity)
            }

            activityClass.hookAfterOnCreate {
                val activity = thisObject as? Activity ?: return@hookAfterOnCreate
                if (!isChatSheet(activity)) return@hookAfterOnCreate
                removeTransitionCapture()
                applySheetWindow(activity)
                makeWindowTranslucent(activity)
                if (activity.javaClass.name in PUSHED_SHEETS) {
                    applySlideInTransition(activity)
                    applySlideOutTransition(activity)
                }
            }
        }

        hookOrientationRequests()
        hookCloseTransitionCapture()
        hookTrampolineRedirectAfterFirstFrame()
    }

    /**
     * Records the picker's close animation. AlbumPreviewUI.finish() writes the pair into its own
     * intent before calling super, so reading it back needs no framework hook at all — its own
     * declared finish() is enough.
     *
     * AlbumPreviewUI declares finish(), so this resolves there rather than to MMActivity.finish;
     * the subclasses that merely inherit it are filtered out by [isChatSheet].
     */
    private fun hookCloseTransitionCapture() {
        ALBUM_PREVIEW_UI.toClass().reflekt().firstMethod {
            name = "finish"
            parameters()
        }.hookAfter {
            val activity = thisObject as? Activity ?: return@hookAfter
            if (!isChatSheet(activity)) return@hookAfter
            val intent = activity.intent ?: return@hookAfter
            val enter = intent.getIntExtra(EXTRA_ENTER_ANIMATION, -1)
            val exit = intent.getIntExtra(EXTRA_EXIT_ANIMATION, -1)
            if (enter == -1) return@hookAfter
            slideOutTransition = enter to exit
            WeLogger.d(TAG, "captured picker close transition: enter=$enter exit=$exit")
        }
    }

    /**
     * Gives a pushed sheet the picker's close animation by writing the pair into the extras
     * MMActivity.finish() already reads. Feeding WeChat's own mechanism covers every dismissal
     * route — back press, the tap-outside finish, any programmatic finish — without hooking
     * finish() on those sheets, neither of which declares one, so a hook would resolve to
     * MMActivity.finish and fire for every screen in WeChat.
     *
     * Caveat: the pair only becomes known once the picker has closed at least once this process,
     * and a pushed sheet can be dismissed before that ever happens. Until then this is a no-op and
     * the sheet keeps WeChat's default close animation. In practice that means at most one
     * mismatched dismissal per WeChat process, and only when the very first picker session opens a
     * preview or the search screen before being closed.
     */
    private fun applySlideOutTransition(activity: Activity) {
        val (enter, exit) = slideOutTransition ?: return
        val intent = activity.intent ?: return
        intent.putExtra(EXTRA_ENTER_ANIMATION, enter)
        intent.putExtra(EXTRA_EXIT_ANIMATION, exit)
    }

    override fun onDisable() {
        // Normally cleared by the matching hookAfterOnCreate; this only matters if the feature is
        // switched off while a capture window happens to be open.
        removeTransitionCapture()
    }

    /**
     * Records the picker's own enter animation, hooking android.app.Activity for the duration of
     * a single onCreate and no longer. MMFragmentActivity does not override
     * overridePendingTransition — its `putActivity*Animation` methods only cache defaults and
     * delegate — so the picker's call lands on the framework method, and that is the only place
     * to observe it.
     *
     * A permanent hook there would sit on every activity transition in the process, so this one
     * is installed from the picker's before-onCreate hook and torn down again in the matching
     * after-onCreate hook ([removeTransitionCapture]). It is also installed at most once ever:
     * with a value already captured there is nothing left to watch for.
     *
     * Registered with `hookBeforeDirectly` rather than the BaseFeature helper on purpose — the
     * handle is owned here, not by the feature's bulk-unhook list.
     *
     * Calls of (0, 0) are ignored because WeChat uses those to mean "no animation", and the last
     * real pair within the window wins. Should that ever pick up some other call made later in
     * the picker's onCreate, the result is a different WeChat-native animation on the preview,
     * not a broken one.
     */
    private fun installTransitionCapture(picker: Activity) {
        if (slideInTransition != null || transitionCaptureUnhook != null) return

        transitionCaptureUnhook = Activity::class.reflekt().firstMethod {
            name = "overridePendingTransition"
            parameters(int, int)
        }.hookBeforeDirectly {
            if (thisObject !== picker) return@hookBeforeDirectly
            val enter = args[0] as? Int ?: return@hookBeforeDirectly
            val exit = args[1] as? Int ?: return@hookBeforeDirectly
            if (enter == 0 && exit == 0) return@hookBeforeDirectly
            slideInTransition = enter to exit
            WeLogger.d(TAG, "captured picker transition: enter=$enter exit=$exit")
        }
    }

    private fun removeTransitionCapture() {
        transitionCaptureUnhook?.unhook()
        transitionCaptureUnhook = null
    }

    /**
     * Replays the picker's slide-in on a pushed sheet, so tapping a thumbnail or the search entry
     * pushes the sheet up instead of scaling it out of the screen center.
     *
     * Doing this from the sheet's own onCreate is what makes it stick: MMFragmentActivity applies
     * its default open animation caller-side, immediately after super.startActivityForResult, so
     * this later call replaces it. It is the same trick AlbumPreviewUI uses on itself. If nothing
     * was captured, WeChat's default animation is left alone.
     */
    @Suppress("DEPRECATION")
    private fun applySlideInTransition(activity: Activity) {
        val (enter, exit) = slideInTransition ?: return
        activity.overridePendingTransition(enter, exit)
    }

    /**
     * MMActivity.onStart → setMMOrientation → setRequestedOrientation, which WeChat routes
     * through its AndroidOSafety helper. That helper reflectively calls
     * Activity.convertFromTranslucent() on any activity whose theme is translucent, to satisfy the
     * pre-API-31 "only fullscreen opaque activities can request orientation" rule. Left alone the
     * ActivityRecord becomes occluding and the system stops ChattingUI behind us once the enter
     * transition settles, blanking the exposed area.
     *
     * Two ways out, picked per activity by whether the manifest already pins the orientation:
     *  - Pinned ([PUSHED_SHEETS]) → skip the runtime call, which is pure redundancy.
     *  - Not pinned (the picker) → let it run and convert straight back afterwards.
     *
     * setRequestedOrientation is declared on MMFragmentActivity, so this hooks one concrete
     * method instead of resolving to an inherited one. It fires for every WeChat activity, hence
     * the guards — it is a once-per-onStart call, so the cost is a string compare.
     */
    private fun hookOrientationRequests() {
        val setRequestedOrientation = MM_FRAGMENT_ACTIVITY.toClass().reflekt().firstMethod {
            name = "setRequestedOrientation"
            parameters(int)
        }

        setRequestedOrientation.hookBefore {
            val activity = thisObject as? Activity ?: return@hookBefore
            if (activity.javaClass.name !in PUSHED_SHEETS) return@hookBefore
            if (!isChatSheet(activity)) return@hookBefore

            // Both pushed sheets declare android:screenOrientation="portrait" in the manifest and
            // their getForceOrientation() asks for that very same portrait value, so this call
            // changes nothing and skipping it avoids the conversion outright. It is also required
            // for ImagePreviewUI specifically: its @style/ls theme is opaque, so we grant it
            // translucency ourselves, after which the call would throw IllegalStateException on
            // API 26–30.
            result = null
        }

        setRequestedOrientation.hookAfter {
            val activity = thisObject as? Activity ?: return@hookAfter
            if (activity.javaClass.name != ALBUM_PREVIEW_UI) return@hookAfter
            if (!isChatSheet(activity)) return@hookAfter

            // The picker cannot take the skip route: it has no android:screenOrientation in the
            // manifest, so this runtime call is the only thing pinning it to portrait. Its
            // @style/lc theme is translucent, so AndroidOSafety has just converted it to opaque —
            // undo that, on every start.
            makeWindowTranslucent(activity)
        }
    }

    /**
     * Every hook bails unless this is the chat "+" panel's picker or one of the two sheets it
     * pushes, so anything unrecognized falls through to stock WeChat behavior. The direct
     * GalleryEntryUI launches that skip the picker — the recent-photo bubble and the system
     * camera's post-capture preview — are chat sheets too (they carry the same
     * query_source_type); [hookTrampolineRedirectAfterFirstFrame] makes sure their redirecting
     * trampoline has drawn a frame before the sheet launches.
     */
    private fun isChatSheet(activity: Activity): Boolean {
        // Subclasses that do not override onCreate inherit the onCreate hooks: MediaTabAlbumUI and
        // EmojiAlbumPreviewUI (8.0.74+) extend AlbumPreviewUI, FinderPreviewUI extends
        // ImagePreviewUI. Matching the exact class keeps all of them out. SmartGalleryUI has none.
        if (activity.javaClass.name !in SHEET_ACTIVITIES) return false
        // The marker reaches all three: GalleryEntryUI passes its whole intent through to the
        // picker, and the picker forwards its own extras onto both the preview intent and — via
        // putExtras(getIntent()) in the search-menu handler — the SmartGalleryUI intent.
        //
        // This gate is load-bearing for the search screen in particular. WeChat's own guard on the
        // search entry checks only permissions and a device blacklist, with no route check at all,
        // so SmartGalleryUI is reachable from every picker route — Moments, favorites, webview
        // chooseImage, emoji, avatars. Only the chat ones set a query_source_type we accept.
        val sourceType = activity.intent?.getIntExtra("query_source_type", -1) ?: return false
        return sourceType in CHAT_QUERY_SOURCE_TYPES
    }

    /**
     * Holds off GalleryEntryUI's immediate redirect until the trampoline has drawn its first
     * frame.
     *
     * GalleryEntryUI is a blank translucent window with no content; its onResume redirects to a
     * sheet before it has produced a single frame. This happens on two direct chat routes: the
     * "你可能要发送的照片" bubble, and the system camera's post-capture preview
     * (`preview_image` + `isTakePhoto`, launched after the user confirms in the system camera).
     * The sheet hook then converts the target to translucent while that never-drawn window sits
     * below it, and Android's launch transition adds the below window as a participant it has to
     * wait for; with nothing ever triggering its draw, the whole launch is held for the
     * transition's multi-second collection timeout. The picker-pushed preview is unaffected
     * because AlbumPreviewUI has long been drawn by the time it opens ImagePreviewUI.
     *
     * Deferring the redirect by one frame makes every trampoline route structurally identical to
     * the picker route: the caller is already drawn, so the synchronous translucent conversion in
     * the sheet's onCreate completes immediately, the chat stays visible above the sheet through
     * the enter transition, and the normal close transition is restored.
     */
    private fun hookTrampolineRedirectAfterFirstFrame() {
        GALLERY_ENTRY_UI.toClass().reflekt().firstMethod {
            name = "startActivityForResult"
            parameters(Intent::class.java, int)
        }.hookBefore {
            val entry = thisObject as Activity
            val intent = args[0] as Intent
            val requestCode = args[1] as Int

            // Only the chat half-screen sheet routes matter: the target becomes translucent, so
            // the never-drawn trampoline below it stalls the launch transition. GalleryEntryUI's
            // other redirects (opaque screens) cover the trampoline and never hit the stall, so
            // they keep WeChat's stock launch.
            val targetClass = intent.component?.className ?: return@hookBefore
            if (targetClass !in SHEET_ACTIVITIES) return@hookBefore
            if (intent.getIntExtra("query_source_type", -1) !in CHAT_QUERY_SOURCE_TYPES) {
                return@hookBefore
            }

            val decor = entry.window.decorView
            // Already laid out (and therefore drawn at least once): launch straight away. This
            // also covers a reused GalleryEntryUI instance reaching this method again.
            if (decor.isLaidOut) return@hookBefore

            // Skip the immediate redirect; retry once the trampoline's first frame has been
            // submitted. The re-entered startActivityForResult then sees the laid-out decor and
            // passes through to the original method.
            result = null
            decor.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        decor.viewTreeObserver.removeOnPreDrawListener(this)
                        decor.post { entry.startActivityForResult(intent, requestCode) }
                        return true
                    }
                }
            )
        }
    }

    /**
     * MMActivity.onCreate forces `fixStatusbar = true` unless the activity already opted out,
     * which makes it wrap the content in a frame padded by the status bar height. A
     * bottom-anchored 60% window never reaches the status bar, so that padding is just dead
     * space above the ActionBar. Opt out before super.onCreate runs.
     */
    private fun suppressStatusBarPadding(activity: Activity) {
        runCatching {
            val reflect = activity.reflekt()
            reflect.firstMethodOrNull {
                name = "customfixStatusbar"
                parameters(bool)
                superclass()
            }?.invoke(true)
            reflect.firstMethodOrNull {
                name = "fixStatusbar"
                parameters(bool)
                superclass()
            }?.invoke(false)
        }.onFailure { WeLogger.w(TAG, "could not opt out of fixStatusbar", it) }
    }

    private fun applySheetWindow(activity: Activity) {
        val window = activity.window
        val sheetHeight = (displayHeightPx(activity) * heightPercent / 100).coerceAtLeast(1)

        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            gravity = Gravity.BOTTOM
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = sheetHeight
            dimAmount = DIM_AMOUNT
        }

        // Tapping the chat visible above closes the picker. The window is touch-modal (nothing
        // sets FLAG_NOT_TOUCH_MODAL), so it still receives the out-of-bounds taps this needs.
        activity.setFinishOnTouchOutside(true)

        applyRoundedTopCorners(activity)
        keepSheetChromeCorrected(activity)

        WeLogger.d(TAG, "album sheet: $heightPercent% -> ${sheetHeight}px")
    }

    /**
     * Rounds the top of the sheet.
     *
     * The clip goes on the DecorView itself, not on a child. Two child-level targets look
     * tempting and both fail: android.R.id.content sits *below* WeChat's ActionBar, which would
     * leave the ActionBar with square corners poking out of the card; and the DecorView's child
     * 0 is not the content root in ImagePreviewUI, which injects its own body view there with
     * `getDecorView().addView(bodyView, 0)` and leaves the real content at index 1, unclipped.
     *
     * Clipping the DecorView covers the window background and every child whatever WeChat
     * injects, which is also why nothing here needs to repaint a body background — the theme's
     * own windowBackground is inside the clip.
     */
    private fun applyRoundedTopCorners(activity: Activity) {
        val decor = activity.window.decorView
        val radius = CORNER_RADIUS_DP * activity.resources.displayMetrics.density

        decor.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Extend the rect past the bottom edge so only the top pair of corners falls
                // inside the view — the bottom pair rounds off-screen and reads as square.
                outline.setRoundRect(
                    0,
                    0,
                    view.width,
                    view.height + radius.toInt(),
                    radius
                )
            }
        }
        decor.clipToOutline = true
    }

    /**
     * Marks the activity translucent so its ActivityRecord stays non-occluding and the system
     * keeps ChattingUI drawn behind the sheet instead of stopping it and blanking the exposed
     * area.
     *
     * This both *restores* translucency for AlbumPreviewUI (whose theme has it, until WeChat
     * converts it away) and *grants* it to ImagePreviewUI (whose @style/ls theme never had it).
     * The hidden API does not care which — it flips the record either way.
     *
     * Mirrors what WeChat's own ActivityUtil does in the opposite direction: reflect out the
     * hidden Activity.convertToTranslucent and pass a null listener. The Samsung
     * SemTranslucentConversionListener has to be filtered out — on One UI both nested types
     * match the name and only the AOSP one is the parameter type.
     */
    private fun makeWindowTranslucent(activity: Activity) {
        runCatching {
            val listenerType = Activity::class.java.declaredClasses.firstOrNull {
                it.simpleName.contains("TranslucentConversionListener") &&
                    it.simpleName != "SemTranslucentConversionListener"
            } ?: error("TranslucentConversionListener not found")

            val method = Activity::class.java.declaredMethods.firstOrNull {
                it.name == "convertToTranslucent" &&
                    it.parameterTypes.firstOrNull() == listenerType
            } ?: error("convertToTranslucent not found")

            method.makeAccessible()
            // (listener) on some builds, (listener, ActivityOptions) on others.
            if (method.parameterCount == 1) {
                method.invoke(activity, null)
            } else {
                method.invoke(activity, null, null)
            }
        }.onFailure { WeLogger.w(TAG, "could not restore translucency", it) }
    }

    /**
     * Keeps WeChat's full-screen chrome corrected for the life of the sheet, off a single layout
     * listener. All of these are (re-)applied *after* onCreate returns, so a one-shot fix in the
     * onCreate hook would not survive:
     *
     *  - **Top padding.** MMActivity picks its EdgeToEdgeWrapperLayout branch independently of
     *    `fixStatusbar`, and that wrapper pads from window insets. A bottom-anchored sheet does
     *    not intersect the status bar so those insets should already be zero, but WeChat
     *    re-applies padding on later inset dispatches.
     *  - **FLAG_FULLSCREEN.** ImagePreviewUI's `fullScreenNoTitleBar(true)` does not set the flag
     *    inline — it posts a runnable that calls `setFlags(FLAG_FULLSCREEN)` 256 ms later, so
     *    clearing it during onCreate does nothing. Left set, the status bar hides across the whole
     *    screen and the chat above the sheet loses it too. (No-op for AlbumPreviewUI, which never
     *    sets the flag.)
     *  - **Status bar.** ImagePreviewUI paints its status bar dark (it is a full-screen photo
     *    viewer by default), but the sheet does not extend under the system bar, so that dark
     *    color would sit above the exposed chat. Reset the system bar to transparent so the chat's
     *    own status bar area shows through, and make the bar icons follow night mode so they stay
     *    visible against it. WeChat re-applies its color late (256 ms after onCreate), hence the
     *    per-layout correction.
     *
     * Each check is guarded on the current value, so it settles after one extra layout pass
     * rather than looping.
     */
    @Suppress("DEPRECATION")
    private fun keepSheetChromeCorrected(activity: Activity) {
        val wrappingFrame = runCatching {
            activity.reflekt()
                .firstFieldOrNull { name = "mWrappingFrame"; superclass() }
                ?.get() as? ViewGroup
        }.onFailure {
            WeLogger.w(TAG, "could not reach mWrappingFrame", it)
        }.getOrNull()

        val statusBarStripClass = runCatching {
            Class.forName("com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout")
        }.getOrNull()
        val statusBarStrip = statusBarStripClass?.let { cls ->
            runCatching { activity.window.decorView.findFirstViewByClass(cls) }.getOrNull()
        }
        val statusBarStripSetColor = statusBarStripClass?.getMethod(
            "setStatusBarColor",
            Int::class.javaPrimitiveType
        )

        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            if (wrappingFrame != null && wrappingFrame.paddingTop != 0) {
                wrappingFrame.setPadding(
                    wrappingFrame.paddingLeft,
                    0,
                    wrappingFrame.paddingRight,
                    wrappingFrame.paddingBottom
                )
            }

            val fullscreen = WindowManager.LayoutParams.FLAG_FULLSCREEN
            if (activity.window.attributes.flags and fullscreen != 0) {
                activity.window.clearFlags(fullscreen)
            }

            val window = activity.window
            if (window.statusBarColor != Color.TRANSPARENT) {
                // API 26-29 only honors the color when the window claims the system bar.
                if (window.attributes.flags and
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS == 0
                ) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                }
                window.statusBarColor = Color.TRANSPARENT
            }

            val lightStatusBar =
                activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                    Configuration.UI_MODE_NIGHT_YES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                val controller = window.insetsController
                if (controller != null) {
                    val hasLightBar = controller.systemBarsAppearance and appearance != 0
                    if (hasLightBar != lightStatusBar) {
                        controller.setSystemBarsAppearance(
                            if (lightStatusBar) appearance else 0,
                            appearance
                        )
                    }
                }
            } else {
                val flag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                val decor = window.decorView
                val hasFlag = decor.systemUiVisibility and flag != 0
                if (hasFlag != lightStatusBar) {
                    decor.systemUiVisibility = if (lightStatusBar) {
                        decor.systemUiVisibility or flag
                    } else {
                        decor.systemUiVisibility and flag.inv()
                    }
                }
            }

            // WeChat paints its own status bar strip inside the decor (DrawStatusBarFrameLayout);
            // the sheet does not sit under the system bar, so keep that strip transparent too.
            if (statusBarStrip != null && statusBarStripSetColor != null) {
                runCatching { statusBarStripSetColor.invoke(statusBarStrip, Color.TRANSPARENT) }
                    .onFailure { WeLogger.w(TAG, "could not clear status bar strip", it) }
            }
        }
    }

    private fun View.findFirstViewByClass(cls: Class<*>): View? {
        if (cls.isInstance(this)) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) {
            getChildAt(i).findFirstViewByClass(cls)?.let { return it }
        }
        return null
    }

    /** Full display height, so the percentage means what the slider says. */
    @Suppress("DEPRECATION")
    private fun displayHeightPx(activity: Activity): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds.height()
        } else {
            Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }.y
        }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var height by remember {
                mutableIntStateOf(heightPercent.coerceIn(MIN_HEIGHT_PERCENT, MAX_HEIGHT_PERCENT))
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_half_screen_album_picker_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.chat_half_screen_album_height_label),
                                    value = height,
                                    startInt = MIN_HEIGHT_PERCENT,
                                    endInt = MAX_HEIGHT_PERCENT,
                                    stepSize = 1,
                                    valueSuffix = "%",
                                    onValueChange = {
                                        height = it
                                        heightPercent = it
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }
}
