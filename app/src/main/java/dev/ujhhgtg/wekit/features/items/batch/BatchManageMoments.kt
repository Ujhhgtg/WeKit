package dev.ujhhgtg.wekit.features.items.batch

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Adapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.children
import androidx.core.view.isVisible
import com.tencent.mm.plugin.sns.storage.SnsInfo
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.ui.MMActivity
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.data
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexConstructor
import dev.ujhhgtg.wekit.dex.dsl.dexField
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.i18n.localizedString
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

object BatchManageMoments : ClickableFeature(), IResolveDex {
    override val technicalId = "批量管理朋友圈"
    override val nameRes = R.string.feature_batch_manage_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS, FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_manage_moments_description
    override val noSwitchWidget = true

    // There is no switch, so the hooks below would never be installed without this.
    override val alwaysEnabled = true

    private const val EXTRA_BATCH_MANAGE = "wekit_batch_manage_moments"
    private const val TAG = "BatchManageMoments"

    private const val MENU_MULTI_SELECT = 0x7F01
    private const val MENU_SELECT_ALL = 0x7F02
    private const val MENU_DELETE = 0x7F03

    /** The album list inserts non-post rows (self header, star slot) with this local id. */
    private const val PLACEHOLDER_LOCAL_ID = -1

    /** NetSceneSnsObjectOp op type 1 = delete a post. */
    private const val OP_DELETE = 1

    /** SnsUserUINotifyEvent type 3 = remove a post from the album list. */
    private const val EVENT_REMOVE_POST = 3

    private val DELETE_INTERVAL = 1.5.seconds

    private val sessions = CopyOnWriteArrayList<Session>()
    private val pendingScenes = ConcurrentHashMap<Any, CompletableDeferred<Boolean>>()

    // These album IDs are stable across the supported normal and Play builds.
    private val idPhotoThumb by lazy { hostResource("haa", "id") }
    private val idContentThumb by lazy { hostResource("h9t", "id") }

    private val classSnsObjectOp by dexClass {
        searchPackages("com.tencent.mm.plugin.sns.model")
        usingEqStrings("MicroMsg.NetSceneSnsObjectOp", "/cgi-bin/micromsg-bin/mmsnsobjectop")
    }

    private val ctorDeleteScene by dexConstructor {
        declaredClass(classSnsObjectOp.data.name)
        paramTypes("long", "int")
    }

    private val methodAdapterGetView by dexMethod {
        returnType = "android.view.View"
        paramTypes("int", "android.view.View", "android.view.ViewGroup")
        usingEqStrings("getView", "com.tencent.mm.plugin.sns.ui.SnsSelfAdapter")
    }

    private val methodAdapterGetItem by dexMethod {
        declaredClass(methodAdapterGetView.data.declaredClassName)
        name = "getItem"
        paramTypes("int")
        returnType = "java.lang.Object"
    }

    // getCount() counts grouped display rows, not the posts behind getItem().
    private val fieldLoadedPosts by dexField {
        type = "java.util.List"
        readMethod { descriptor = methodAdapterGetItem.data.descriptor }
    }

    private val fieldRowCount by dexField {
        type = "int"
        readMethod {
            declaredClass(methodAdapterGetView.data.declaredClassName)
            name = "getCount"
            paramCount = 0
            returnType = "int"
        }
    }

    private val methodIsSearchMode by dexMethod {
        declaredClass(methodAdapterGetView.data.declaredClassName)
        paramCount = 0
        returnType = "boolean"
        usingEqStrings("isSearchMode", "com.tencent.mm.plugin.sns.ui.SnsSelfAdapter")
    }

    // The album groups up to 3 posts per row unless this says a row holds a single post.
    private val methodIsOnlyOne by dexMethod {
        returnType = "boolean"
        paramCount = 0
        usingEqStrings("isOnlyOne", "com.tencent.mm.plugin.sns.data.CalInfo")
    }

    // Rebuilds the row layout from the post list and pushes it into the adapter.
    private val methodRefreshRows by dexMethod {
        paramCount = 0
        usingEqStrings("refreshInMemery", "com.tencent.mm.plugin.sns.ui.SnsSelfAdapterHelper")
    }

    // Picking a year/month switches the same adapter to a separate in-memory helper.
    private val methodRebuildSearchRows by dexMethod {
        paramTypes("boolean", "java.util.List")
        usingEqStrings("initFixType", "com.tencent.mm.plugin.sns.ui.SnsSelfAdapterSearchHelper")
    }

    private val methodPublishEvent by dexMethod {
        declaredClass("com.tencent.mm.sdk.event.IEvent")
        returnType = "boolean"
        paramCount = 0
    }

    private val ctorChattingItemContainer by dexConstructor {
        paramTypes("android.view.LayoutInflater", "int")
        usingEqStrings("MicroMsg.ChattingItemContainer")
    }

    private val methodAddDeleteItem by dexMethod {
        paramTypes("long")
        usingEqStrings("addDelItem", "com.tencent.mm.plugin.sns.model.SnsAsyncQueueMgr")
    }

    private val methodGetAsyncQueue by dexMethod {
        returnType(methodAddDeleteItem.data.declaredClassName)
        paramCount = 0
        modifiers = 8
        usingEqStrings("getSnsAsyncQueueMgr", "com.tencent.mm.plugin.sns.model.SnsCore")
    }

    private val methodDeleteLocalPost by dexMethod {
        paramTypes("long")
        returnType = "boolean"
        usingEqStrings("delete", "com.tencent.mm.plugin.sns.storage.SnsInfoStorage")
    }

    private val methodGetPostStorage by dexMethod {
        returnType(methodDeleteLocalPost.data.declaredClassName)
        paramCount = 0
        modifiers = 8
        usingEqStrings("getSnsInfoStorage", "com.tencent.mm.plugin.sns.model.SnsCore")
    }

    private val methodDeleteLocalComments by dexMethod {
        paramTypes("long")
        usingEqStrings("deleteBySnsId", "com.tencent.mm.plugin.sns.storage.SnsCommentStorage")
    }

    private val methodGetCommentStorage by dexMethod {
        returnType(methodDeleteLocalComments.data.declaredClassName)
        paramCount = 0
        modifiers = 8
        usingEqStrings("getSnsCommentStg", "com.tencent.mm.plugin.sns.model.SnsCore")
    }

    private val methodRemoveCollapseId by dexMethod {
        paramTypes("long")
        modifiers = 8
        usingEqStrings("removeCollapseSnsId", "com.tencent.mm.plugin.sns.storage.SnsCollapseInfo")
    }

    override fun onEnable() {
        SnsUserUI::class.firstMethod { name = "initView" }
            .hookAfter {
                val activity = thisObject as MMActivity
                if (!activity.intent.getBooleanExtra(EXTRA_BATCH_MANAGE, false)) return@hookAfter
                val session = Session(activity)
                sessions += session
                activity.addIconOptionMenu(
                    MENU_MULTI_SELECT,
                    localizedString(R.string.batch_manage_moments_multi_select),
                    hostRaw("icons_filled_multiple_choice"),
                ) {
                    session.enter()
                    true
                }
            }

        SnsUserUI::class.firstMethod { name = "onBackPressed" }
            .hookBefore {
                val session =
                    sessions.firstOrNull { it.activity === thisObject } ?: return@hookBefore
                if (!session.multiSelect) return@hookBefore
                session.exit()
                result = null
            }

        SnsUserUI::class.firstMethod { name = "onDestroy" }
            .hookBefore {
                sessions.removeAll {
                    if (it.activity !== thisObject) return@removeAll false
                    it.destroy()
                    true
                }
            }

        methodIsOnlyOne.hookAfter {
            // CalInfo belongs to a specific album helper; other album activities stay unchanged.
            if (sessions.any { it.multiSelect && it.ownsCalInfo(thisObject!!) }) result = true
        }

        methodAdapterGetView.hook {
            before {
                val convertView = args[1] as View?
                if (convertView is SelectableRow) {
                    extra = convertView
                    args[1] = convertView.detach()
                } else if (sessions.any { it.multiSelect && it.adapter === thisObject }) {
                    extra = convertView
                }
            }
            after {
                if (throwable != null) return@after
                val session = sessions.firstOrNull { it.adapter === thisObject } ?: return@after
                if (session.multiSelect) {
                    // Both helpers hold one post per row during multi-select.
                    val post = session.adapter.getItem(args[0] as Int) as SnsInfo
                    result =
                        (extra as? SelectableRow ?: SelectableRow(session)).also {
                            it.attach(result as View, post)
                        }
                }
                val replaced = extra as View?
                if (replaced != null && result !== replaced) {
                    // ListView scraps a convertView when getView returns a different
                    // root. During mode changes that would leave both roots owning
                    // the same host content. Discard only the replaced root.
                    val params = replaced.layoutParams
                    replaced.layoutParams =
                        AbsListView.LayoutParams(
                            params.width,
                            params.height,
                            Adapter.IGNORE_ITEM_VIEW_TYPE,
                        )
                }
            }
        }

        WeNetSceneApi.methodAddNetSceneToQueue.method.declaringClass
            .reflekt()
            .firstMethod {
                name = "onSceneEnd"
                parameterCount = 4
            }
            .hookAfter {
                // Queue completion also covers requests that failed before reaching onGYNetEnd.
                completeScene(args[3]!!, args[0] as Int, args[1] as Int)
            }

        classSnsObjectOp.clazz
            .reflekt()
            .firstMethod {
                name = "callbackErrorIfNeed"
                parameterCount = 3
                superclass()
            }
            .hookAfter {
                // Reset/clearRunningQueue reports its failures through the scene base class.
                completeScene(thisObject!!, args[0] as Int, args[1] as Int)
            }
    }

    private fun completeScene(scene: Any, errType: Int, errCode: Int) {
        val pending = pendingScenes[scene] ?: return
        if (errType != 0 || errCode != 0) {
            WeLogger.w(TAG, "Moments deletion failed: type=$errType code=$errCode")
        }
        pending.complete(errType == 0 && errCode == 0)
    }

    override fun onClick(context: ComponentActivity) {
        WeApi.openMoments(context, WeApi.selfWxId) { putExtra(EXTRA_BATCH_MANAGE, true) }
    }

    @SuppressLint("DiscouragedApi")
    private fun hostResource(name: String, type: String): Int =
        HostInfo.application.resources.getIdentifier(name, type, PackageNames.WECHAT).also {
            check(it != 0) { "missing host $type/$name" }
        }

    private fun hostRaw(name: String) = hostResource(name, "raw")

    private val SnsInfo.isDeletable
        get() =
            localid != PLACEHOLDER_LOCAL_ID &&
                isValid &&
                !isAd &&
                !isDieItem &&
                !isWaitPost &&
                WeMomentsApi.getTimelineProto(this)?.contentObj?.mediaList?.none { it.type == 8 } ==
                    true

    private enum class MomentKind(@StringRes val labelRes: Int) {
        TEXT(R.string.batch_manage_moments_kind_text),
        IMAGE(R.string.batch_manage_moments_kind_image),
        LIVE_PHOTO(R.string.batch_manage_moments_kind_live_photo),
        VIDEO(R.string.batch_manage_moments_kind_video),
        LINK(R.string.batch_manage_moments_kind_link),
        MUSIC(R.string.batch_manage_moments_kind_music),
        OTHER(R.string.batch_manage_moments_kind_other),
    }

    // SnsInfo.field_type mirrors the post's ContentObj.type.
    private val SnsInfo.kind
        get() =
            when {
                field_type == 54 ||
                    WeMomentsApi.getTimelineProto(this)?.contentObj?.mediaList?.any {
                        it.livePhotoVideo != null
                    } == true -> MomentKind.LIVE_PHOTO
                else ->
                    when (field_type) {
                        2 -> MomentKind.TEXT
                        1 -> MomentKind.IMAGE
                        5,
                        15,
                        18,
                        19,
                        28,
                        36 -> MomentKind.VIDEO
                        3 -> MomentKind.LINK
                        4,
                        42,
                        47 -> MomentKind.MUSIC
                        else -> MomentKind.OTHER
                    }
            }

    private suspend fun deleteOnServer(target: Target): Boolean {
        val confirmed = CompletableDeferred<Boolean>()
        val scene = ctorDeleteScene.newInstance(target.snsId, OP_DELETE)
        pendingScenes[scene] = confirmed
        try {
            // Match SnsCommentDetailUI: enqueue, send, then remove local post/comments/collapse.
            methodAddDeleteItem.method.invoke(methodGetAsyncQueue.method.invoke(null), target.snsId)
            WeNetSceneApi.sendNetScene(scene)
            try {
                methodDeleteLocalPost.method.invoke(
                    methodGetPostStorage.method.invoke(null),
                    target.snsId,
                )
                target.removedLocally = true
                methodDeleteLocalComments.method.invoke(
                    methodGetCommentStorage.method.invoke(null),
                    target.snsId,
                )
                methodRemoveCollapseId.method.invoke(null, target.snsId)
            } catch (error: Exception) {
                // The request is already in flight. Local cleanup must not skip its result
                // or let the next deletion start before this scene finishes.
                WeLogger.e(TAG, "failed to clean up local Moments post ${target.snsId}", error)
            }
            // WeChat owns the network timeout. Do not start the next post while this
            // scene is still in flight, or turn a late server success into a failure.
            return confirmed.await()
        } finally {
            pendingScenes.remove(scene)
        }
    }

    // Same notification WeChat sends after deleting a post from its detail page.
    private fun publishRemoved(snsIdText: String) {
        val event = "com.tencent.mm.autogen.events.SnsUserUINotifyEvent".toClass().createInstance()
        // The event declares a single payload field holding (int type, String snsId).
        val payload = event.reflekt().firstField().get()!!
        payload.reflekt().firstField { type = Int::class }.set(EVENT_REMOVE_POST)
        payload.reflekt().firstField { type = String::class }.set(snsIdText)
        methodPublishEvent.method.invoke(event)
    }

    private class Session(val activity: MMActivity) {
        @Volatile
        var multiSelect = false
            private set

        val selected = linkedSetOf<Long>()

        // The chat selector's obfuscated resource name differs on Play builds. Let the
        // native container select it, then give each row its own drawable instance.
        val checkboxBackground: Drawable.ConstantState by lazy {
            val container =
                ctorChattingItemContainer.newInstance(
                    LayoutInflater.from(activity),
                    android.R.layout.simple_list_item_1,
                ) as ViewGroup
            container.children.filterIsInstance<CheckBox>().single().background.constantState!!
        }

        private var backCallback: OnBackInvokedCallback? = null
        private var deleteDialog: Dialog? = null

        val adapter =
            activity
                .reflekt()
                .firstField { type = methodAdapterGetView.method.declaringClass }
                .get() as BaseAdapter

        private val helper =
            adapter.reflekt().firstField { type = methodRefreshRows.method.declaringClass }.get()!!

        private val searchHelper =
            adapter
                .reflekt()
                .firstField { type = methodRebuildSearchRows.method.declaringClass }
                .get()!!

        // An inactive helper's CalInfo is null until its first load.
        fun ownsCalInfo(info: Any): Boolean =
            helper.reflekt().firstField { type = methodIsOnlyOne.method.declaringClass }.get() ===
                info ||
                searchHelper
                    .reflekt()
                    .firstField { type = methodIsOnlyOne.method.declaringClass }
                    .get() === info

        private val isSearchMode: Boolean
            get() = methodIsSearchMode.method.invoke(adapter) as Boolean

        @Suppress("UNCHECKED_CAST")
        private fun searchPosts() =
            searchHelper.reflekt().firstField { type = List::class }.get() as MutableList<SnsInfo>

        fun enter() {
            multiSelect = true
            if (Build.VERSION.SDK_INT >= 33) {
                val callback = OnBackInvokedCallback { exit() }
                backCallback = callback
                activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    callback,
                )
            }
            activity.showOptionMenu(false)
            activity.addIconOptionMenu(
                MENU_SELECT_ALL,
                localizedString(R.string.batch_manage_moments_select_all),
                hostRaw("icons_filled_allselect"),
            ) {
                selectAllLoaded()
                true
            }
            activity.addIconOptionMenu(
                MENU_DELETE,
                localizedString(R.string.action_delete),
                hostRaw("icons_filled_delete"),
            ) {
                confirmDelete()
                true
            }
            activity.setBackBtn(
                {
                    exit()
                    true
                },
                hostRaw("actionbar_icon_dark_close"),
            )
            rebuildRows()
        }

        fun exit() {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback!!)
                backCallback = null
            }
            multiSelect = false
            selected.clear()
            activity.removeOptionMenu(MENU_SELECT_ALL)
            activity.removeOptionMenu(MENU_DELETE)
            activity.showOptionMenu(true)
            activity.setBackBtn(
                {
                    @Suppress("DEPRECATION") activity.onBackPressed()
                    true
                },
                hostRaw("actionbar_icon_dark_back"),
            )
            rebuildRows()
        }

        private fun rebuildRows() {
            if (isSearchMode) {
                val posts = searchPosts()
                if (posts.isEmpty()) {
                    // Native setSnsList returns early for an empty search result, leaving
                    // stale display rows behind. Clear the adapter's list/count as well.
                    @Suppress("UNCHECKED_CAST") (loadedPosts() as MutableList<SnsInfo>).clear()
                    fieldRowCount.field.setInt(adapter, 0)
                    adapter.notifyDataSetChanged()
                } else {
                    methodRebuildSearchRows.method.invoke(searchHelper, false, posts)
                }
            } else {
                methodRefreshRows.method.invoke(helper)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun loadedPosts() = fieldLoadedPosts.field.get(adapter) as List<SnsInfo>

        fun destroy() {
            if (multiSelect && Build.VERSION.SDK_INT >= 33) {
                activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback!!)
            }
            deleteDialog?.dismiss()
        }

        private fun selectAllLoaded() {
            loadedPosts().filter { it.isDeletable }.forEach { selected += it.field_snsId }
            adapter.notifyDataSetChanged()
        }

        private fun confirmDelete() {
            val targets =
                loadedPosts()
                    .filter { it.isDeletable && it.field_snsId in selected }
                    .map { Target(it.field_snsId, it.snsId, it.kind) }
            if (targets.isEmpty()) {
                showToast(localizedString(R.string.batch_manage_moments_nothing_selected))
                return
            }
            val job = DeleteJob(targets)
            showComposeDialog(activity, directlyDismissable = false) {
                deleteDialog = dialog
                DeleteDialog(
                    job = job,
                    onStart = { exit() },
                    onClose = {
                        onDismiss()
                        deleteDialog = null
                        val removed = targets.filter {
                            it.removedLocally || it.outcome == Outcome.SUCCESS
                        }
                        removed.forEach { publishRemoved(it.snsIdText) }
                        if (removed.isNotEmpty()) {
                            // The native type=3 listener only updates the normal helper.
                            val removedIds = removed.mapTo(hashSetOf()) { it.snsId }
                            searchPosts().removeAll { it.field_snsId in removedIds }
                            if (isSearchMode) rebuildRows()
                        }
                    },
                )
            }
        }
    }

    @SuppressLint("ViewConstructor")
    private class SelectableRow(private val session: Session) : LinearLayout(session.activity) {
        private var snsId = 0L
        private lateinit var hostContent: View
        private var thumbnail: View? = null
        private lateinit var thumbnailParent: ViewGroup
        private var thumbnailIndex = 0
        private lateinit var thumbnailParams: ViewGroup.LayoutParams

        private val title =
            TextView(context).apply {
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        private val date =
            TextView(context).apply {
                textSize = 12f
                setTextColor(currentTextColor and 0x00FFFFFF or 0x99000000.toInt())
            }

        private val checkBox =
            CheckBox(context).apply {
                buttonDrawable = null
                background =
                    session.checkboxBackground.newDrawable(resources, context.theme).mutate()
                isClickable = false
                isFocusable = false
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(context), 8.dpToPx(context), 16.dpToPx(context), 8.dpToPx(context))
            minimumHeight = 64.dpToPx(context)
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(title)
                    addView(date)
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            val size = 24.dpToPx(context)
            addView(
                checkBox,
                LayoutParams(size, size).apply {
                    marginStart = 12.dpToPx(context)
                },
            )
            setOnClickListener {
                if (!session.selected.remove(snsId)) session.selected += snsId
                checkBox.isChecked = snsId in session.selected
            }
        }

        // Taps anywhere on the row toggle the selection instead of opening the post.
        override fun onInterceptTouchEvent(ev: MotionEvent) = true

        fun attach(content: View, post: SnsInfo) {
            hostContent = content
            snsId = post.field_snsId
            val deletable = post.isDeletable
            visibility = if (deletable) VISIBLE else GONE
            layoutParams =
                AbsListView.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    if (deletable) LayoutParams.WRAP_CONTENT else 1,
                )
            if (!deletable) return
            val proto = WeMomentsApi.getTimelineProto(post)!!
            title.text =
                proto.contentDesc?.takeIf { it.isNotBlank() }
                    ?: proto.contentObj?.title?.takeIf { it.isNotBlank() }
                    ?: localizedString(post.kind.labelRes)
            date.text =
                DateFormat.getDateFormat(context).format(Date(post.field_createTime * 1000L))
            checkBox.isChecked = snsId in session.selected
            // Reparent the host image itself so its asynchronous loader keeps updating it.
            // Restore its exact parent/params before giving the convertView back to WeChat.
            thumbnail =
                (content.findViewById<View>(idPhotoThumb)
                        ?: content.findViewById<View>(idContentThumb))
                    ?.takeIf { it.isVisible }
            thumbnail?.let {
                thumbnailParent = it.parent as ViewGroup
                thumbnailIndex = thumbnailParent.indexOfChild(it)
                thumbnailParams = it.layoutParams
                thumbnailParent.removeView(it)
                val size = 48.dpToPx(context)
                addView(it, 0, LayoutParams(size, size).apply { marginEnd = 12.dpToPx(context) })
            }
        }

        /** Gives the content back to the adapter, which rebinds it as its own convert view. */
        fun detach(): View {
            thumbnail?.let {
                removeView(it)
                thumbnailParent.addView(it, thumbnailIndex, thumbnailParams)
            }
            thumbnail = null
            return hostContent
        }
    }

    private enum class Outcome {
        PENDING,
        SUCCESS,
        FAILED,
    }

    private enum class Phase {
        CONFIRM,
        RUNNING,
        SUMMARY,
    }

    private class Target(val snsId: Long, val snsIdText: String, val kind: MomentKind) {
        var outcome by mutableStateOf(Outcome.PENDING)
        var removedLocally = false
    }

    private class DeleteJob(val targets: List<Target>) {
        var phase by mutableStateOf(Phase.CONFIRM)
        var cancelRequested by mutableStateOf(false)

        private val cancelSignal = CompletableDeferred<Unit>()

        fun cancel() {
            cancelRequested = true
            cancelSignal.complete(Unit)
        }

        suspend fun run() {
            for ((index, target) in targets.withIndex()) {
                if (cancelRequested) break
                // A post being deleted always finishes; cancel only stops before the next one.
                target.outcome =
                    try {
                        if (deleteOnServer(target)) Outcome.SUCCESS else Outcome.FAILED
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        WeLogger.e(TAG, "failed to delete Moments post ${target.snsId}", error)
                        Outcome.FAILED
                    }
                if (index < targets.lastIndex) {
                    // Space out requests, but wake up immediately on cancel.
                    withTimeoutOrNull(DELETE_INTERVAL) { cancelSignal.await() }
                }
            }
        }
    }

    @Composable
    private fun DeleteDialog(job: DeleteJob, onStart: () -> Unit, onClose: () -> Unit) {
        LaunchedEffect(job.phase) {
            if (job.phase == Phase.RUNNING) {
                job.run()
                job.phase = Phase.SUMMARY
            }
        }

        val total = job.targets.size
        when (job.phase) {
            Phase.CONFIRM ->
                AlertDialogContent(
                    title = { Text(stringResource(R.string.batch_manage_moments_confirm_title)) },
                    text = {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                pluralStringResource(
                                    R.plurals.batch_manage_moments_confirm_message,
                                    total,
                                    total,
                                ),
                            )
                            KindStats(job.targets) { it.size.toString() }
                        }
                    },
                    dismissButton = {
                        TextButton(onClose) { Text(stringResource(R.string.dialog_cancel)) }
                    },
                    confirmButton = {
                        TextButton(
                            {
                                onStart()
                                job.phase = Phase.RUNNING
                            },
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                    },
                )

            Phase.RUNNING -> {
                val processed = job.targets.count { it.outcome != Outcome.PENDING }
                AlertDialogContent(
                    title = {
                        Text(stringResource(R.string.batch_manage_moments_progress_title))
                    },
                    text = {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = { processed / total.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(
                                    R.string.batch_manage_moments_progress,
                                    processed,
                                    total,
                                ),
                            )
                            StatRow(
                                stringResource(R.string.batch_manage_moments_summary_success),
                                job.targets.count { it.outcome == Outcome.SUCCESS }.toString(),
                            )
                            StatRow(
                                stringResource(R.string.batch_manage_moments_summary_failed),
                                job.targets.count { it.outcome == Outcome.FAILED }.toString(),
                            )
                            if (job.cancelRequested) {
                                Text(stringResource(R.string.batch_manage_moments_canceling))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(job::cancel, enabled = !job.cancelRequested) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                    },
                )
            }

            Phase.SUMMARY -> {
                val succeeded = job.targets.count { it.outcome == Outcome.SUCCESS }
                val failed = job.targets.count { it.outcome == Outcome.FAILED }
                val skipped = total - succeeded - failed
                AlertDialogContent(
                    title = {
                        Text(
                            stringResource(
                                if (job.cancelRequested) {
                                    R.string.batch_manage_moments_summary_title_canceled
                                } else {
                                    R.string.batch_manage_moments_summary_title
                                },
                            ),
                        )
                    },
                    text = {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column {
                                StatRow(
                                    stringResource(R.string.batch_manage_moments_summary_total),
                                    total.toString(),
                                )
                                StatRow(
                                    stringResource(R.string.batch_manage_moments_summary_success),
                                    succeeded.toString(),
                                )
                                StatRow(
                                    stringResource(R.string.batch_manage_moments_summary_failed),
                                    failed.toString(),
                                )
                                if (skipped > 0) {
                                    StatRow(
                                        stringResource(
                                            R.string.batch_manage_moments_summary_skipped,
                                        ),
                                        skipped.toString(),
                                    )
                                }
                            }
                            Text(stringResource(R.string.batch_manage_moments_summary_by_kind))
                            KindStats(job.targets) { ofKind ->
                                "${ofKind.count { it.outcome == Outcome.SUCCESS }} / ${ofKind.size}"
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClose) { Text(stringResource(R.string.dialog_close)) }
                    },
                )
            }
        }
    }

    /** One row per post type that occurs in [targets]. */
    @Composable
    private fun KindStats(targets: List<Target>, value: (List<Target>) -> String) {
        Column {
            MomentKind.entries.forEach { kind ->
                val ofKind = targets.filter { it.kind == kind }
                if (ofKind.isNotEmpty()) StatRow(stringResource(kind.labelRes), value(ofKind))
            }
        }
    }

    @Composable
    private fun StatRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(1f))
            Text(value)
        }
    }
}
