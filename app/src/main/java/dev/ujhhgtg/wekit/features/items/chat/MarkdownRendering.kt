package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Html
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import com.tencent.mm.ui.widget.MMNeat7extView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.collections.LruCache
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.strings.replaceEmojis
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.spans.LastLineSpacingSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Heading
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import java.lang.reflect.Field

object MarkdownRendering : ClickableFeature(), IResolveDex {

    override val technicalId = "Markdown 渲染"
    override val nameRes = R.string.feature_markdown_rendering_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_markdown_rendering_description

    private const val TAG = "MarkdownRendering"

    private const val KEY_RENDER_MODE = "render_mode"
    private const val KEY_COMPACT_HTML = "compact_html"
    private const val KEY_NO_TEXT_SIZING = "no_text_sizing"

    private const val WECHAT_TEXT_MESSAGE_TYPE = 1
    private const val WECHAT_MARKDOWN_MESSAGE_TYPE = 16777265

    private val nativeMarkdownItemTypeKey =
        "${WECHAT_MARKDOWN_MESSAGE_TYPE}_1_true_0".hashCode()

    private lateinit var markwon: Markwon

    private external fun convertMarkdownToHtmlNative(markdown: String): String?

    // Apply a small compensation to the max width to prevent unnecessary text wrapping
    private const val MAX_WIDTH_BUFFER = 40

    private var nativeRendererAvailable = false

    private val messageFields by lazy {
        MessageFields(
            content = findMessageField("field_content"),
            isSend = findMessageField("field_isSend"),
            talker = findMessageField("field_talker"),
            type = findMessageField("field_type")
        )
    }

    override fun onEnable() {
        installNativeRenderer()

        MMNeat7extView::class.reflekt()
            .firstMethod { name = "onDraw" }
            .hookBefore {
                if (activeRenderMode == RenderMode.NATIVE) return@hookBefore

                val neatTextView = thisObject as View
                if (!::markwon.isInitialized) {
                    markwon = buildMarkwon(neatTextView.context)
                }

                var origText = (neatTextView.reflekt()
                    .firstField {
                        type = CharSequence::class
                        superclass()
                    }.get()!! as CharSequence).toString()
                if (origText.isBlank()) return@hookBefore
                origText = origText.replaceEmojis()

                val msgInfo: Any

                val tag = neatTextView.tag
                val fMsgInfoWrapper = tag.reflekt()
                    .firstFieldOrNull {
                        type = classMsgInfoWrapper.clazz
                        superclass()
                    }

                if (fMsgInfoWrapper != null) {
                    val msgInfoWrapper = fMsgInfoWrapper.get()!!
                    msgInfo = MessageInfo(
                        msgInfoWrapper.reflekt()
                            .firstField {
                                superclass()
                            }
                            .get()!!.reflekt()
                            .firstField { type = WeMessageApi.classMsgInfo.clazz }
                            .get()!!)
                } else {
                    msgInfo = MessageInfo(
                        tag.reflekt()
                            .firstField {
                                type = WeMessageApi.classMsgInfo.clazz
                                superclass()
                            }.get()!!
                    )
                }

                if (!(msgInfo.type?.isText ?: false)) return@hookBefore
                val isSelfSender = msgInfo.isSelfSender

                val canvas = args[0] as Canvas
                val context = neatTextView.context

                val textColor = if (context.isDarkMode && !isSelfSender) {
                    "#CDCDCD".toColorInt()
                } else {
                    "#282828".toColorInt()
                }

                // Respecting bubble constraints
                val horizontalPadding = neatTextView.paddingLeft + neatTextView.paddingRight
                val maxWidth = neatTextView.width - horizontalPadding + MAX_WIDTH_BUFFER

                if (maxWidth <= 0) return@hookBefore

                // null => the text could not be rendered, leave the original drawing untouched.
                val staticLayout = obtainLayout(context, origText, maxWidth, textColor)
                    ?: return@hookBefore

                canvas.withTranslation(
                    neatTextView.paddingLeft.toFloat(),
                    neatTextView.paddingTop.toFloat()
                ) {
                    staticLayout.draw(this)
                }
                result = null
            }
    }

    private fun installNativeRenderer() {
        if (classItemFactory.isPlaceholder || methodMarkdownPreprocess.isPlaceholder) {
            WeLogger.w(TAG, "WeChat native Markdown renderer is unavailable in this version")
            return
        }

        val itemTypeMethod = classItemFactory.reflekt().firstMethodOrNull {
            parameters(WeMessageApi.classMsgInfo.clazz)
            returnType = int
            superclass()
        } ?: run {
            WeLogger.w(TAG, "failed to resolve WeChat message item selector")
            return
        }

        itemTypeMethod.hookBefore {
            if (selectedRenderMode != RenderMode.NATIVE) return@hookBefore

            val msg = args[0] ?: return@hookBefore
            if (!isNativeRendererCandidate(msg)) return@hookBefore
            result = nativeMarkdownItemTypeKey
        }

        methodMarkdownPreprocess.hookBefore {
            val msg = extractMsgInfo(args.getOrNull(1) ?: return@hookBefore) ?: return@hookBefore
            if (!isNativeRendererCandidate(msg)) return@hookBefore

            val fields = messageFields
            val originalContent = fields.content.get(msg) as String
            extra = originalContent
            fields.content.set(msg, toNativeMarkdownAppMsg(nativeMarkdownContent(msg)))
        }

        methodMarkdownPreprocess.hookAfter {
            val originalContent = extra as? String ?: return@hookAfter
            val msg = extractMsgInfo(args.getOrNull(1) ?: return@hookAfter) ?: return@hookAfter
            messageFields.content.set(msg, originalContent)
        }

        nativeRendererAvailable = true
    }

    private val selectedRenderMode: RenderMode
        get() = RenderMode.fromPreference(
            WePrefs.getStringOrDef(KEY_RENDER_MODE, RenderMode.HTML.preference)
        )

    private val activeRenderMode: RenderMode
        get() {
            val selectedMode = selectedRenderMode
            return if (selectedMode == RenderMode.NATIVE && !nativeRendererAvailable) RenderMode.HTML else selectedMode
        }

    private fun setRenderMode(mode: RenderMode) {
        WePrefs.putString(KEY_RENDER_MODE, mode.preference)
    }

    private fun isNativeRendererCandidate(msg: Any): Boolean {
        val fields = messageFields
        return fields.type.getInt(msg) == WECHAT_TEXT_MESSAGE_TYPE &&
                !(fields.content.get(msg) as String).isBlank()
    }

    private fun nativeMarkdownContent(msg: Any): String {
        val fields = messageFields
        val content = fields.content.get(msg) as String
        val talker = fields.talker.get(msg) as String
        if (!talker.endsWith("@chatroom") && !talker.endsWith("@im.chatroom")) return content

        val senderSeparator = content.indexOf(':')
        return if (senderSeparator in 0 until content.lastIndex &&
            !content.substring(0, senderSeparator).contains('<')
        ) {
            content.substring(senderSeparator + 1).trim()
        } else {
            content
        }
    }

    private fun extractMsgInfo(msgData: Any): Any? {
        val messageClass = WeMessageApi.classMsgInfo.clazz
        for (field in instanceFields(msgData.javaClass)) {
            val nested = field.get(msgData) ?: continue
            if (messageClass.isInstance(nested)) return nested
            for (nestedField in instanceFields(nested.javaClass)) {
                val msg = nestedField.get(nested)
                if (msg != null && messageClass.isInstance(msg)) return msg
            }
        }
        return null
    }

    private fun findMessageField(name: String): Field {
        return instanceFields(WeMessageApi.classMsgInfo.clazz).first { it.name == name }
    }

    private fun instanceFields(clazz: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = clazz
        while (current != null) {
            for (field in current.declaredFields) {
                field.isAccessible = true
                yield(field)
            }
            current = current.superclass
        }
    }

    private fun toNativeMarkdownAppMsg(markdown: String): String {
        val cdataSafeMarkdown = markdown.replace("]]>", "]]]]><![CDATA[>")
        return "<msg><appmsg appid=\"\" sdkver=\"0\"><title><![CDATA[$cdataSafeMarkdown]]></title>" +
                "<action>view</action><type>1</type><showtype>0</showtype><contentattr>0</contentattr>" +
                "</appmsg></msg>"
    }

    // onDraw fires constantly (invalidation, scrolling, animation, text selection), so parsing the
    // Markdown and laying it out from scratch on every call is heavy UI-thread work scaling with
    // the number of visible messages. The finished layout is cached instead and only rebuilt when
    // something that actually changes it changes.
    private data class LayoutKey(
        // Source text: keying on it means a fixed renderer (or edited message) yields a new entry
        // instead of pinning a stale rendering forever.
        val text: String,
        // The measured bubble width: a width change must re-lay-out.
        val maxWidth: Int,
        val mode: RenderMode,
        // A StaticLayout keeps the TextPaint it was built with, so incoming/outgoing bubbles and
        // light/dark mode each need their own entry.
        val textColor: Int,
        val compactHtml: Boolean,
        val noTextSizing: Boolean
    )

    // Bounded so it can never grow with the message history; only touched from onDraw, i.e. the
    // main thread, so no synchronization is needed.
    private const val LAYOUT_CACHE_SIZE = 64

    private val layoutCache = LruCache<LayoutKey, StaticLayout>(maxLimit = LAYOUT_CACHE_SIZE)

    /** Cached [StaticLayout] for [text], or null when the text could not be rendered at all. */
    private fun obtainLayout(
        context: Context,
        text: String,
        maxWidth: Int,
        textColor: Int
    ): StaticLayout? {
        val key = LayoutKey(
            text = text,
            maxWidth = maxWidth,
            mode = activeRenderMode,
            textColor = textColor,
            compactHtml = WePrefs.getBoolOrFalse(KEY_COMPACT_HTML),
            noTextSizing = WePrefs.getBoolOrFalse(KEY_NO_TEXT_SIZING)
        )
        layoutCache[key]?.let { return it }

        val spanned = if (key.mode == RenderMode.MARKWON) {
            markwon.render(markwon.parse(text))
        } else {
            val html = convertMarkdownToHtmlNative(text)
            if (html == null) {
                // Nothing is cached for a native-side failure, so a later call retries.
                WeLogger.e(
                    TAG,
                    "convertMarkdownToHtmlNative returned nullptr, falling back to original rendering"
                )
                return null
            }
            htmlToSpanned(html, key.compactHtml, key.noTextSizing)
        }

        val layout = buildStaticLayout(spanned, buildTextPaint(context, textColor), maxWidth)
        layoutCache[key] = layout
        return layout
    }

    private fun buildTextPaint(context: Context, textColor: Int): TextPaint = TextPaint().apply {
        color = textColor

        val spSize = 17f
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            spSize,
            context.resources.displayMetrics
        )

        isAntiAlias = true
        typeface = Typeface.DEFAULT
    }

    private fun htmlToSpanned(
        htmlString: String,
        compactHtml: Boolean,
        noTextSizing: Boolean
    ): Spanned {
        var spanned = Html.fromHtml(
            htmlString,
            if (compactHtml) Html.FROM_HTML_MODE_COMPACT else Html.FROM_HTML_MODE_LEGACY
        )

        if (noTextSizing) {
            spanned = SpannableStringBuilder(spanned)

            val relativeSpans = spanned.getSpans(
                0, spanned.length,
                RelativeSizeSpan::class.java
            )
            for (span in relativeSpans) {
                spanned.removeSpan(span)
            }

            val absoluteSpans = spanned.getSpans(
                0, spanned.length,
                AbsoluteSizeSpan::class.java
            )
            for (span in absoluteSpans) {
                spanned.removeSpan(span)
            }
        }

        return spanned
    }

    private fun buildStaticLayout(spanned: Spanned, textPaint: TextPaint, maxWidth: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(spanned, 0, spanned.length, textPaint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                }
            }
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    private fun buildMarkwon(context: Context): Markwon {
        return Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    if (WePrefs.getBoolOrFalse(KEY_NO_TEXT_SIZING)) {
                        builder.setFactory(Heading::class.java) { _, _ ->
                            StyleSpan(Typeface.BOLD)
                        }
                    }
                    builder.setFactory(Paragraph::class.java) { _, _ ->
                        LastLineSpacingSpan(0)
                    }
                    builder.setFactory(BulletList::class.java) { _, _ ->
                        LastLineSpacingSpan(0)
                    }
                    builder.setFactory(OrderedList::class.java) { _, _ ->
                        LastLineSpacingSpan(0)
                    }
                    builder.setFactory(BlockQuote::class.java) { _, _ ->
                        LastLineSpacingSpan(0)
                    }
                }
            })
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create {})
            .usePlugin(TaskListPlugin.create(context))
            .build()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_markdown_rendering_name)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        var renderMode by remember { mutableStateOf(selectedRenderMode) }

                        SegmentedColumn(
                            title = stringResource(R.string.chat_markdown_render_engine),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item(key = "native") {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_markdown_native),
                                    description = stringResource(R.string.chat_markdown_native_description),
                                    selected = renderMode == RenderMode.NATIVE,
                                    enabled = nativeRendererAvailable,
                                    onClick = {
                                        renderMode = RenderMode.NATIVE
                                        setRenderMode(renderMode)
                                    },
                                )
                            }
                            item(key = "html") {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_markdown_html),
                                    description = stringResource(R.string.chat_markdown_html_description),
                                    selected = renderMode == RenderMode.HTML,
                                    onClick = {
                                        renderMode = RenderMode.HTML
                                        setRenderMode(renderMode)
                                    },
                                )
                            }
                            item(key = "markwon") {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_markdown_markwon),
                                    description = stringResource(R.string.chat_markdown_markwon_description),
                                    selected = renderMode == RenderMode.MARKWON,
                                    onClick = {
                                        renderMode = RenderMode.MARKWON
                                        setRenderMode(renderMode)
                                    },
                                )
                            }
                        }

                        var noTextSizing by
                        remember { mutableStateOf(WePrefs.getBoolOrFalse(KEY_NO_TEXT_SIZING)) }
                        SegmentedColumn(
                            title = stringResource(R.string.chat_markdown_general_settings),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item(key = "no_text_sizing") {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_markdown_disable_text_sizing),
                                    description = stringResource(R.string.chat_markdown_disable_text_sizing_description),
                                    checked = noTextSizing,
                                    onCheckedChange = {
                                        noTextSizing = it
                                        WePrefs.putBool(KEY_NO_TEXT_SIZING, it)
                                    },
                                )
                            }
                        }

                        var compactHtml by
                        remember { mutableStateOf(WePrefs.getBoolOrFalse(KEY_COMPACT_HTML)) }
                        SegmentedColumn(
                            title = stringResource(R.string.chat_markdown_html_settings),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item(key = "compact_html") {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_markdown_compact_html),
                                    description = stringResource(R.string.chat_markdown_compact_html_description),
                                    checked = compactHtml,
                                    onCheckedChange = {
                                        compactHtml = it
                                        WePrefs.putBool(KEY_COMPACT_HTML, it)
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }

    private enum class RenderMode(val preference: String) {
        NATIVE("native"),
        HTML("html"),
        MARKWON("markwon");

        companion object {
            fun fromPreference(value: String): RenderMode =
                entries.firstOrNull { it.preference == value } ?: HTML
        }
    }

    private data class MessageFields(
        val content: Field,
        val isSend: Field,
        val talker: Field,
        val type: Field
    )

    private val classItemFactory by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.ItemFactoryNew", "initChattingItemConfig")
        }
    }

    private val methodMarkdownPreprocess by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingItemMarkdownMvvm",
                "[STREAM_TRACE] preDealData: msgId=%d, msgSvrId=%d, contentLen=%d, isStreaming=%b"
            )
        }
    }

    private val classMsgInfoWrapper by dexClass {
        matcher {
            usingEqStrings("other", "null cannot be cast to non-null type com.tencent.mm.storage.MsgInfo")
        }
    }
}
