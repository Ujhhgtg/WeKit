package dev.ujhhgtg.wekit.utils.monet

import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.ColorTarget
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.ColorValue
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.DrawableTarget
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.XmlAttribute
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.XmlNode
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.XmlValue

/**
 * Turns resolved bindings plus the current theme state into the runtime resource set.
 *
 * Colors are carried as ints: a value whose high byte is `0x01` is a framework color resource ID
 * (system Monet, resolved by the platform), anything else is a literal ARGB (seed palette colors
 * are always opaque, so the two never collide).
 */
object MonetThemeRenderer {
    data class Palette(
        val surfaceLight: Int,
        val surfaceDark: Int,
        val surfaceContainerLight: Int,
        val surfaceContainerDark: Int,
        val surfaceContainerHighLight: Int,
        val surfaceContainerHighDark: Int,
        val primaryLight: Int,
        val primaryDark: Int,
        val primaryContainerLight: Int,
        val primaryContainerDark: Int,
        val accent1_300: Int,
        val accent1_400: Int,
        val accent1_500: Int,
        val accent1_700: Int,
        val accent2_100: Int,
        val neutral2_700: Int,
    )

    data class Options(
        val bubbleStyle: MonetBubbleStyle,
        val multiSceneCorners: Boolean,
        /** Also recolor WeChat's error/destructive reds ([ERROR_ROLES]); off keeps them red. */
        val errorColors: Boolean,
    )

    data class Output(val colors: List<ColorTarget>, val drawables: List<DrawableTarget>)

    /**
     * @param color framework color name (e.g. `system_primary_light`) → encoded color, or `null`
     * when the source cannot provide it (older platforms lack the M3 surface-container names).
     */
    fun palette(color: (String) -> Int?): Palette {
        fun pick(vararg names: String): Int = names.firstNotNullOfOrNull(color)
            ?: error("Monet color unavailable: ${names.joinToString()}")
        return Palette(
            surfaceLight = pick("system_surface_light"),
            surfaceDark = pick("system_surface_dark"),
            surfaceContainerLight = pick(*SURFACE_CONTAINER_LIGHT),
            surfaceContainerDark = pick(*SURFACE_CONTAINER_DARK),
            surfaceContainerHighLight = pick(
                "system_surface_container_high_light", "system_surface_container_light", "system_surface_light",
            ),
            surfaceContainerHighDark = pick(
                "system_surface_container_high_dark", "system_surface_container_dark", "system_surface_dark",
            ),
            primaryLight = pick("system_primary_light", "system_accent1_500"),
            primaryDark = pick("system_primary_dark", "system_accent1_200"),
            primaryContainerLight = pick("system_primary_container_light", "system_accent1_100"),
            primaryContainerDark = pick("system_primary_container_dark", "system_accent1_800"),
            accent1_300 = pick("system_accent1_300"),
            accent1_400 = pick("system_accent1_400"),
            accent1_500 = pick("system_accent1_500"),
            accent1_700 = pick("system_accent1_700"),
            accent2_100 = pick("system_accent2_100"),
            neutral2_700 = pick("system_neutral2_700", "system_surface_dark"),
        )
    }

    fun render(
        bindings: MonetBindings,
        palette: Palette,
        options: Options,
        color: (String) -> Int?,
    ): Output {
        val resolved = bindings.roles
        // The bottom tab bar is left to WeChat (and WeKit's navigation bar features); error reds
        // stay WeChat's unless the user opts in.
        val skipped = if (options.errorColors) UNRENDERED_ROLES else UNRENDERED_ROLES + ERROR_ROLES
        val colors = MONET_RULES.filter { it.type == "color" && it.id !in skipped }.mapNotNull { rule ->
            val binding = resolved[rule.id] ?: return@mapNotNull null
            val (light, night) = themeColor(rule.id, color)
            if (light == null && night == null) null else ColorTarget(binding, light, night)
        }
        // Later groups replace earlier ones for the same resource, like the old overlay priorities.
        val drawables = linkedMapOf<Int, DrawableTarget>()
        fun put(targets: List<DrawableTarget>) = targets.forEach { drawables[it.binding.id] = it }
        put(baseVisuals(resolved, palette))
        put(modernBubbles(resolved, palette))
        when (options.bubbleStyle) {
            MonetBubbleStyle.MODERN -> Unit
            MonetBubbleStyle.CLASSIC -> put(classicBubbles(resolved, palette))
            MonetBubbleStyle.PRO -> put(proBubbles(resolved, palette))
        }
        if (options.multiSceneCorners) put(corners(resolved, palette))
        // Brand-green tints as one-item color state lists: a framework color reference cannot carry
        // alpha as a plain value, a color XML item can.
        val tints = bindings.tints.map { tint ->
            DrawableTarget(
                tint.binding,
                tintColor(palette.primaryLight, tint.lightAlpha),
                tint.nightAlpha?.let { tintColor(palette.primaryDark, it) },
            )
        }
        return Output(colors, drawables.values.toList() + tints)
    }

    /** Parses `theme.color.<light>--<night>.slot-NN` role IDs into light/night values. */
    private fun themeColor(roleId: String, color: (String) -> Int?): Pair<ColorValue?, ColorValue?> {
        val semantic = roleId.removePrefix("theme.color.").substringBefore(".slot-")
        val parts = semantic.split("--", limit = 2)
        fun resolve(token: String): ColorValue? {
            if (token == "unknown") return null
            if (token.length == 8 && token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return ColorValue.Literal(token.toUInt(16).toInt())
            }
            require(token.startsWith("system-")) { "unsupported Monet color token: $token" }
            val names = when (val normalized = token.replace('-', '_')) {
                "system_surface_container_light" -> SURFACE_CONTAINER_LIGHT
                "system_surface_container_dark" -> SURFACE_CONTAINER_DARK
                else -> arrayOf(normalized)
            }
            val value = names.firstNotNullOfOrNull(color) ?: error("Monet color unavailable: ${names.joinToString()}")
            return resourceColor(value)
        }
        return resolve(parts.first()) to resolve(parts.getOrElse(1) { parts.first() })
    }

    private fun resourceColor(value: Int): ColorValue =
        if (value ushr 24 == 0x01) ColorValue.Reference(value) else ColorValue.Literal(value)

    private fun baseVisuals(
        resolved: Map<String, MonetBinding>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        addPair(resolved, "brand.circular.background", oval(palette.accent1_300), oval(palette.accent1_300))
        addPair(
            resolved,
            "chat.brand-action.background",
            simpleSelector(rounded(palette.primaryLight, 8f), rounded(palette.primaryLight, 8f)),
            simpleSelector(rounded(palette.primaryDark, 8f), rounded(palette.primaryDark, 8f)),
        )
        addPair(
            resolved,
            "chat.red-envelope.incoming.normal",
            messageSelector(rounded(palette.accent1_700, 16f), rounded(palette.primaryLight, 16f)),
            messageSelector(rounded(palette.accent1_300, 16f), rounded(palette.primaryDark, 16f)),
        )
        addPair(
            resolved,
            "chat.red-envelope.outgoing.normal",
            messageSelector(rounded(palette.accent1_700, 16f), rounded(palette.primaryLight, 16f)),
            messageSelector(rounded(palette.accent1_300, 16f), rounded(palette.primaryDark, 16f)),
        )
        val paymentLight = paymentSelector(palette.accent1_400, palette.primaryLight)
        val paymentNight = paymentSelector(palette.accent1_400, palette.primaryDark)
        addPair(resolved, "payment.key.primary", paymentLight, paymentNight)
        addPair(resolved, "payment.key.secondary", paymentLight, paymentNight)
        addPair(resolved, "chat.input.transparent-layer", solid(0x00ffffff), solid(0x00000000))
        addPair(
            resolved,
            "main.surface.header.primary",
            header(palette.surfaceLight, palette.surfaceContainerLight),
            header(palette.surfaceDark, palette.surfaceContainerDark),
        )
        addPair(
            resolved,
            "main.surface.header.secondary",
            header(palette.surfaceLight, palette.surfaceContainerLight),
            header(palette.surfaceDark, palette.surfaceContainerDark),
        )
    }

    private fun modernBubbles(
        resolved: Map<String, MonetBinding>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        addPair(
            resolved,
            "chat.bubble.incoming.normal",
            rounded(palette.surfaceLight, 16f, ALL_BUBBLE_PADDING),
            rounded(palette.surfaceDark, 16f, ALL_BUBBLE_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.normal",
            rounded(palette.accent2_100, 16f, ALL_BUBBLE_PADDING),
            rounded(palette.neutral2_700, 16f, ALL_BUBBLE_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.incoming.link",
            rounded(palette.surfaceLight, 16f, INCOMING_LINK_PADDING),
            rounded(palette.surfaceDark, 16f, INCOMING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.link",
            rounded(palette.surfaceLight, 16f, OUTGOING_LINK_PADDING),
            rounded(palette.surfaceDark, 16f, OUTGOING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.incoming.link.mask",
            linkMask(0x1a000000, INCOMING_LINK_PADDING),
            linkMask(0x10ffffff, INCOMING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.link.mask",
            linkMask(0x1a000000, OUTGOING_LINK_PADDING),
            linkMask(0x10ffffff, OUTGOING_LINK_PADDING),
        )
        val redLight = messageSelector(rounded(palette.accent1_700, 16f), rounded(palette.primaryLight, 16f))
        val redNight = messageSelector(rounded(palette.accent1_300, 16f), rounded(palette.primaryDark, 16f))
        addPair(resolved, "chat.red-envelope.incoming.alias", redLight, redNight)
        addPair(resolved, "chat.red-envelope.outgoing.alias", redLight, redNight)
        val received = messageSelector(rounded(palette.accent1_400, 16f), rounded(palette.accent1_300, 16f))
        addPair(resolved, "chat.transfer.incoming.received", received, received)
        addPair(resolved, "chat.transfer.outgoing.received", received, received)
        val expired = messageSelector(rounded(palette.accent1_500, 16f), rounded(palette.accent1_400, 16f))
        addPair(resolved, "chat.transfer.incoming.expired", expired, expired)
        addPair(resolved, "chat.transfer.outgoing.expired", expired, expired)
        val voiceLight = voiceSelector(rounded(palette.surfaceLight, 16f))
        val voiceNight = voiceSelector(rounded(palette.surfaceDark, 16f))
        addPair(resolved, "chat.voice-to-text.background", voiceLight, voiceNight)
    }

    private fun proBubbles(
        resolved: Map<String, MonetBinding>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        val incomingLight = proBubble(palette.surfaceContainerHighLight, 0x26000000)
        val incomingNight = proBubble(palette.surfaceContainerHighDark, 0x36ffffff)
        val outgoingLight = proBubble(palette.primaryContainerLight, 0x26000000)
        val outgoingNight = proBubble(palette.primaryContainerDark, 0x36ffffff)
        PRO_INCOMING.forEach { addPair(resolved, it, incomingLight, incomingNight) }
        PRO_OUTGOING.forEach { addPair(resolved, it, outgoingLight, outgoingNight) }
        addPair(resolved, "chat.bubble.incoming.link.mask", rounded(0x00000000, 20f), rounded(0x26000000, 20f))
        addPair(resolved, "chat.bubble.outgoing.link.mask", rounded(0x00000000, 20f), rounded(0x26000000, 20f))
    }

    private fun classicBubbles(
        resolved: Map<String, MonetBinding>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        addPair(
            resolved,
            "chat.bubble.incoming.normal",
            classicShape(palette.surfaceLight, true, ALL_BUBBLE_PADDING),
            classicShape(palette.surfaceDark, true, ALL_BUBBLE_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.normal",
            classicShape(palette.accent2_100, false, ALL_BUBBLE_PADDING),
            classicShape(palette.neutral2_700, false, ALL_BUBBLE_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.incoming.link",
            classicShape(palette.surfaceLight, true, INCOMING_LINK_PADDING),
            classicShape(palette.surfaceDark, true, INCOMING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.link",
            classicShape(palette.surfaceLight, false, OUTGOING_LINK_PADDING),
            classicShape(palette.surfaceDark, false, OUTGOING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.incoming.link.mask",
            classicLinkMask(true, INCOMING_LINK_PADDING),
            classicLinkMask(true, INCOMING_LINK_PADDING),
        )
        addPair(
            resolved,
            "chat.bubble.outgoing.link.mask",
            classicLinkMask(false, OUTGOING_LINK_PADDING),
            classicLinkMask(false, OUTGOING_LINK_PADDING),
        )
        val incomingPrimaryLight = classicShape(palette.primaryLight, true, INCOMING_LINK_PADDING)
        val incomingPrimaryNight = classicShape(palette.primaryDark, true, INCOMING_LINK_PADDING)
        val outgoingPrimaryLight = classicShape(palette.primaryLight, false, OUTGOING_LINK_PADDING)
        val outgoingPrimaryNight = classicShape(palette.primaryDark, false, OUTGOING_LINK_PADDING)
        addPair(
            resolved,
            "chat.red-envelope.incoming.normal",
            messageSelector(incomingPrimaryLight, incomingPrimaryLight),
            messageSelector(incomingPrimaryNight, incomingPrimaryNight),
        )
        addPair(
            resolved,
            "chat.red-envelope.outgoing.normal",
            messageSelector(outgoingPrimaryLight, outgoingPrimaryLight),
            messageSelector(outgoingPrimaryNight, outgoingPrimaryNight),
        )
        val incomingMaskLight = classicShape(palette.accent1_700, true, INCOMING_LINK_PADDING)
        val incomingMaskNight = classicShape(palette.accent1_300, true, INCOMING_LINK_PADDING)
        val outgoingMaskLight = classicShape(palette.accent1_700, false, OUTGOING_LINK_PADDING)
        val outgoingMaskNight = classicShape(palette.accent1_300, false, OUTGOING_LINK_PADDING)
        listOf("chat.transfer.incoming.received", "chat.transfer.incoming.expired").forEach {
            addPair(resolved, it, messageSelector(incomingMaskLight, incomingMaskLight), messageSelector(incomingMaskNight, incomingMaskNight))
        }
        listOf("chat.transfer.outgoing.received", "chat.transfer.outgoing.expired").forEach {
            addPair(resolved, it, messageSelector(outgoingMaskLight, outgoingMaskLight), messageSelector(outgoingMaskNight, outgoingMaskNight))
        }
    }

    private fun corners(
        resolved: Map<String, MonetBinding>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        addPair(resolved, "chat.input.background", rounded(palette.surfaceLight, 12f), rounded(palette.surfaceDark, 12f))
        addPair(resolved, "chat.quote.background", rounded(0x10000000, 10f), rounded(0x10ffffff, 10f))
        addPair(
            resolved,
            "payment.key.pressed",
            simpleSelector(rounded(0x10000000, 10f), rounded(palette.surfaceLight, 10f)),
            simpleSelector(rounded(palette.surfaceContainerDark, 10f), rounded(palette.surfaceDark, 10f)),
        )
    }

    /** Roles the resolver could not pin down on this WeChat build are skipped, not fatal. */
    private fun MutableList<DrawableTarget>.addPair(
        resolved: Map<String, MonetBinding>,
        role: String,
        light: XmlNode,
        night: XmlNode,
    ) {
        add(DrawableTarget(resolved[role] ?: return, light, night))
    }

    private fun solid(color: Int): XmlNode = solid(colorValue(color))
    private fun solid(color: XmlValue): XmlNode = XmlNode(
        "shape",
        listOf(android("shape", ATTR_SHAPE, XmlValue.Integer(0))),
        listOf(XmlNode("solid", listOf(android("color", ATTR_COLOR, color)))),
    )

    private fun rounded(color: Int, radius: Float, padding: Padding? = null): XmlNode = XmlNode(
        "shape",
        listOf(android("shape", ATTR_SHAPE, XmlValue.Integer(0))),
        buildList {
            add(XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color)))))
            add(XmlNode("corners", listOf(android("radius", ATTR_RADIUS, XmlValue.Dimension(radius)))))
            padding?.let { add(it.node()) }
        },
    )

    private fun tintColor(color: Int, alpha: Int): XmlNode = XmlNode(
        "selector",
        children = listOf(XmlNode("item", listOf(
            android("color", ATTR_COLOR, colorValue(color)),
            android("alpha", ATTR_ALPHA, XmlValue.Float(alpha / 255f)),
        ))),
    )

    private fun oval(color: Int): XmlNode = XmlNode(
        "shape",
        listOf(android("shape", ATTR_SHAPE, XmlValue.Integer(1))),
        listOf(XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color))))),
    )

    private fun proBubble(color: Int, outline: Int): XmlNode = XmlNode(
        "shape",
        children = listOf(
            XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color)))),
            XmlNode("stroke", listOf(
                android("width", ATTR_WIDTH, XmlValue.Dimension(1f)),
                android("color", ATTR_COLOR, XmlValue.Color(outline)),
            )),
            XmlNode("corners", listOf(android("radius", ATTR_RADIUS, XmlValue.Dimension(20f)))),
            ALL_BUBBLE_PADDING.node(),
        ),
    )

    private fun classicShape(color: Int, incoming: Boolean, padding: Padding): XmlNode = XmlNode(
        "shape",
        listOf(android("shape", ATTR_SHAPE, XmlValue.Integer(0))),
        listOf(
            XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color)))),
            XmlNode("corners", listOf(
                android("topLeftRadius", ATTR_TOP_LEFT_RADIUS, XmlValue.Dimension(if (incoming) 6f else 16f)),
                android("topRightRadius", ATTR_TOP_RIGHT_RADIUS, XmlValue.Dimension(if (incoming) 16f else 6f)),
                android("bottomLeftRadius", ATTR_BOTTOM_LEFT_RADIUS, XmlValue.Dimension(16f)),
                android("bottomRightRadius", ATTR_BOTTOM_RIGHT_RADIUS, XmlValue.Dimension(16f)),
            )),
            padding.node(),
        ),
    )

    private fun classicLinkMask(incoming: Boolean, padding: Padding): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_pressed", ATTR_STATE_PRESSED, classicShape(0x1a000000, incoming, padding)),
            stateItem("state_selected", ATTR_STATE_SELECTED, classicShape(0x1a000000, incoming, padding)),
            XmlNode("item", children = listOf(solid(0x00000000))),
        ),
    )

    private fun linkMask(color: Int, padding: Padding): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_pressed", ATTR_STATE_PRESSED, rounded(color, 16f, padding)),
            stateItem("state_selected", ATTR_STATE_SELECTED, rounded(color, 16f, padding)),
            XmlNode("item", children = listOf(solid(0x00000000))),
        ),
    )

    private fun messageSelector(pressed: XmlNode, normal: XmlNode): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_focused", ATTR_STATE_FOCUSED, pressed),
            stateItem("state_pressed", ATTR_STATE_PRESSED, pressed),
            stateItem("state_selected", ATTR_STATE_SELECTED, pressed),
            XmlNode("item", children = listOf(normal)),
        ),
    )

    private fun voiceSelector(drawable: XmlNode): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_focused", ATTR_STATE_FOCUSED, drawable),
            stateItem("state_pressed", ATTR_STATE_PRESSED, drawable),
            stateItem("state_selected", ATTR_STATE_SELECTED, drawable),
            XmlNode("item", children = listOf(drawable)),
        ),
    )

    private fun simpleSelector(pressed: XmlNode, normal: XmlNode): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_pressed", ATTR_STATE_PRESSED, pressed),
            XmlNode("item", children = listOf(normal)),
        ),
    )

    private fun paymentSelector(pressedColor: Int, normalColor: Int): XmlNode = XmlNode(
        "selector",
        children = listOf(
            stateItem("state_pressed", ATTR_STATE_PRESSED, rounded(pressedColor, 8f)),
            stateItem("state_enabled", ATTR_STATE_ENABLED, rounded(pressedColor, 8f), false),
            XmlNode("item", children = listOf(rounded(normalColor, 8f))),
        ),
    )

    private fun stateItem(name: String, id: Int, child: XmlNode, value: Boolean = true): XmlNode =
        XmlNode("item", listOf(android(name, id, XmlValue.Boolean(value))), listOf(child))

    private fun header(surface: Int, accent: Int): XmlNode = XmlNode(
        "layer-list",
        children = listOf(
            XmlNode("item", children = listOf(solid(surface))),
            XmlNode("item", listOf(android("top", ATTR_TOP, XmlValue.Dimension(120f))), listOf(solid(accent))),
        ),
    )

    private fun Padding.node(): XmlNode = XmlNode("padding", listOf(
        android("left", ATTR_LEFT, XmlValue.Dimension(left)),
        android("top", ATTR_TOP, XmlValue.Dimension(top)),
        android("right", ATTR_RIGHT, XmlValue.Dimension(right)),
        android("bottom", ATTR_BOTTOM, XmlValue.Dimension(bottom)),
    ))

    private fun colorValue(value: Int): XmlValue = if (value ushr 24 == 0x01) {
        XmlValue.Reference(value)
    } else {
        XmlValue.Color(value)
    }

    private fun android(name: String, id: Int, value: XmlValue) = XmlAttribute(name, id, value)

    private data class Padding(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private val ALL_BUBBLE_PADDING = Padding(12f, 8f, 12f, 8f)
    private val INCOMING_LINK_PADDING = Padding(0f, 5f, 5f, 5f)
    private val OUTGOING_LINK_PADDING = Padding(5f, 5f, 0f, 5f)

    private val UNRENDERED_ROLES = setOf("main.tab.background")

    /**
     * WeChat's reds that mean error/destructive (verified against 8.0.76 usage). The other red
     * roles (`Red_90` Finder likes, `ph` / `n1` / `dl` Finder and mini-program accents) are brand
     * accents and are always recolored.
     */
    val ERROR_ROLES = setOf(
        // `ac` / `Red_100` (#FA5151): unread badges and dots, failed-message icons, 删除 / 移除 actions,
        // error banners.
        "theme.color.system-primary-light--system-primary-dark.slot-18",
        "theme.color.system-primary-light--system-primary-dark.slot-19",
        // `ch` (#FF3E3E): the destructive dialog button.
        "theme.color.system-primary-light--system-primary-dark.slot-27",
    )
    private val SURFACE_CONTAINER_LIGHT = arrayOf("system_surface_container_light", "system_neutral2_50", "system_surface_light")
    private val SURFACE_CONTAINER_DARK = arrayOf("system_surface_container_dark", "system_neutral2_800", "system_surface_dark")

    private val PRO_INCOMING = listOf(
        "chat.bubble.incoming.normal", "chat.bubble.incoming.link",
        "chat.bubble.incoming.pro", "chat.bubble.incoming.pro.handled",
    )
    private val PRO_OUTGOING = listOf(
        "chat.bubble.outgoing.normal", "chat.bubble.outgoing.link",
        "chat.bubble.outgoing.pro", "chat.bubble.outgoing.pro.handled",
    )

    private const val ATTR_STATE_FOCUSED = 0x0101009c
    private const val ATTR_STATE_ENABLED = 0x0101009e
    private const val ATTR_STATE_SELECTED = 0x010100a1
    private const val ATTR_STATE_PRESSED = 0x010100a7
    private const val ATTR_WIDTH = 0x01010159
    private const val ATTR_SHAPE = 0x0101019a
    private const val ATTR_COLOR = 0x010101a5
    private const val ATTR_ALPHA = 0x0101031f
    private const val ATTR_RADIUS = 0x010101a8
    private const val ATTR_TOP_LEFT_RADIUS = 0x010101a9
    private const val ATTR_TOP_RIGHT_RADIUS = 0x010101aa
    private const val ATTR_BOTTOM_LEFT_RADIUS = 0x010101ab
    private const val ATTR_BOTTOM_RIGHT_RADIUS = 0x010101ac
    private const val ATTR_LEFT = 0x010101ad
    private const val ATTR_TOP = 0x010101ae
    private const val ATTR_RIGHT = 0x010101af
    private const val ATTR_BOTTOM = 0x010101b0
}
