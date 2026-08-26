package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.MonetOverlayApkWriter.DrawableTarget
import dev.ujhhgtg.wekit.extensions.monet.MonetOverlayApkWriter.XmlAttribute
import dev.ujhhgtg.wekit.extensions.monet.MonetOverlayApkWriter.XmlNode
import dev.ujhhgtg.wekit.extensions.monet.MonetOverlayApkWriter.XmlValue

internal object MonetCustomOverlays {
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

    fun baseVisuals(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
        splashIconId: Int,
    ): List<DrawableTarget> = buildList {
        addPair(resolved, "brand.circular.background", oval(palette.accent1_300), oval(palette.accent1_300))
        addPair(
            resolved,
            "launcher.splash.background",
            splash(XmlValue.Color(0xfff4fbf5.toInt()), splashIconId),
            splash(XmlValue.Reference(palette.surfaceDark), splashIconId),
        )
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

    fun modernBubbles(
        resolved: Map<String, MonetResourceNode>,
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

    fun proBubbles(
        resolved: Map<String, MonetResourceNode>,
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

    fun classicBubbles(
        resolved: Map<String, MonetResourceNode>,
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

    fun corners(
        resolved: Map<String, MonetResourceNode>,
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

    fun themedIcon(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
    ): List<DrawableTarget> {
        val targetName = requireNotNull(resolved["launcher.themed.icon"]).key.name
        val adaptive = XmlNode(
            "adaptive-icon",
            children = listOf(
                XmlNode("background", listOf(android("drawable", ATTR_DRAWABLE, XmlValue.NamedReference("drawable", "wekit_icon_bg")))),
                XmlNode("foreground", listOf(android("drawable", ATTR_DRAWABLE, XmlValue.NamedReference("drawable", "wekit_icon_fg")))),
                XmlNode("monochrome", listOf(android("drawable", ATTR_DRAWABLE, XmlValue.NamedReference("drawable", "wekit_icon_mono")))),
            ),
        )
        return listOf(
            DrawableTarget("wekit_icon_bg", solid(0xfff4fbf5.toInt()), solid(palette.surfaceDark)),
            DrawableTarget("wekit_icon_fg", foregroundIcon()),
            DrawableTarget("wekit_icon_mono", monochromeIcon()),
            DrawableTarget(targetName, adaptive, type = "mipmap", lightQualifiers = "-anydpi-v26"),
        )
    }

    private fun MutableList<DrawableTarget>.addPair(
        resolved: Map<String, MonetResourceNode>,
        role: String,
        light: XmlNode,
        night: XmlNode,
    ) {
        add(DrawableTarget(requireNotNull(resolved[role]) { role }.key.name, light, night))
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

    private fun splash(color: XmlValue, iconId: Int): XmlNode = XmlNode(
        "layer-list",
        listOf(android("opacity", ATTR_OPACITY, XmlValue.Integer(-1))),
        listOf(
            XmlNode("item", children = listOf(solid(color))),
            XmlNode("item", listOf(
                android("gravity", ATTR_GRAVITY, XmlValue.Integer(17)),
                android("drawable", ATTR_DRAWABLE, XmlValue.Reference(iconId)),
            )),
        ),
    )

    private fun header(surface: Int, accent: Int): XmlNode = XmlNode(
        "layer-list",
        children = listOf(
            XmlNode("item", children = listOf(solid(surface))),
            XmlNode("item", listOf(android("top", ATTR_TOP, XmlValue.Dimension(120f))), listOf(solid(accent))),
        ),
    )

    private fun foregroundIcon(): XmlNode = vector(listOf(
        path(0xff5bb974.toInt(), "M87,145a54,45 0 0 1 108,0a54,45 0 0 1 -108,0Z"),
        path(0xff5bb974.toInt(), "M106,179a4.627,4.627,0,0,1,1,4c-0.641,2.143-3,9-3,9s-0.942,4.954,4,2,14-9,14-9l-9-8Z"),
        path(0xff00ac47.toInt(), SECOND_BUBBLE_PATH),
        path(0xff00ac47.toInt(), "M221.3,206.689a4,4,0,0,0-.864,3.459c.554,1.853,2.594,7.781,2.594,7.781s.814,4.283-3.459,1.729-12.1-7.781-12.1-7.781l7.781-6.917Z"),
        XmlNode("group", children = listOf(
            XmlNode("clip-path", listOf(android("pathData", ATTR_PATH_DATA, XmlValue.String("M87,145a54,45 0 0 1 108,0a54,45 0 0 1 -108,0Z")))),
            path(0xff00832d.toInt(), SECOND_BUBBLE_PATH),
        )),
    ))

    private fun monochromeIcon(): XmlNode = vector(listOf(
        path(0x0106000c, "M191,138c-27.062,0-49,18.132-49,40.5a34.025,34.025,0,0,0,1.991,11.428C143,189.973,142,190,141,190c-29.823,0-54-20.147-54-45s24.177-45,54-45c27.01,0,49.388,16.526,53.369,38.106C193.255,138.043,192.134,138,191,138Z"),
        path(0x0106000c, "M106,179a4.627,4.627,0,0,1,1,4c-0.641,2.143-3,9-3,9s-0.942,4.954,4,2,14-9,14-9l-9-8Z"),
        path(0x0106000c, SECOND_BUBBLE_PATH),
        path(0x0106000c, "M221.3,206.689a4,4,0,0,0-.864,3.459c.554,1.853,2.594,7.781,2.594,7.781s.814,4.283-3.459,1.729-12.1-7.781-12.1-7.781l7.781-6.917Z"),
    ))

    private fun vector(children: List<XmlNode>): XmlNode = XmlNode(
        "vector",
        listOf(
            android("height", ATTR_HEIGHT, XmlValue.Dimension(324f)),
            android("width", ATTR_WIDTH, XmlValue.Dimension(324f)),
            android("viewportWidth", ATTR_VIEWPORT_WIDTH, XmlValue.Float(324f)),
            android("viewportHeight", ATTR_VIEWPORT_HEIGHT, XmlValue.Float(324f)),
        ),
        children,
    )

    private fun path(color: Int, data: String): XmlNode = XmlNode(
        "path",
        listOf(
            android("fillColor", ATTR_FILL_COLOR, colorValue(color)),
            android("pathData", ATTR_PATH_DATA, XmlValue.String(data)),
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

    private val PRO_INCOMING = listOf(
        "chat.bubble.incoming.normal", "chat.bubble.incoming.link",
        "chat.bubble.incoming.pro", "chat.bubble.incoming.pro.handled",
    )
    private val PRO_OUTGOING = listOf(
        "chat.bubble.outgoing.normal", "chat.bubble.outgoing.link",
        "chat.bubble.outgoing.pro", "chat.bubble.outgoing.pro.handled",
    )

    private const val SECOND_BUBBLE_PATH = "M 191.5 141 C 179.437 141 167.856 144.954 159.327 151.983 C 150.797 159.013 146 168.558 146 178.5 C 146 188.442 150.797 197.987 159.327 205.017 C 167.856 212.046 179.437 216 191.5 216 C 203.563 216 215.144 212.046 223.673 205.017 C 232.203 197.987 237 188.442 237 178.5 C 237 168.558 232.203 159.013 223.673 151.983 C 215.144 144.954 203.563 141 191.5 141 Z"

    private const val ATTR_STATE_FOCUSED = 0x0101009c
    private const val ATTR_STATE_ENABLED = 0x0101009e
    private const val ATTR_STATE_SELECTED = 0x010100a1
    private const val ATTR_STATE_PRESSED = 0x010100a7
    private const val ATTR_GRAVITY = 0x010100af
    private const val ATTR_WIDTH = 0x01010159
    private const val ATTR_HEIGHT = 0x01010155
    private const val ATTR_SHAPE = 0x0101019a
    private const val ATTR_COLOR = 0x010101a5
    private const val ATTR_RADIUS = 0x010101a8
    private const val ATTR_TOP_LEFT_RADIUS = 0x010101a9
    private const val ATTR_TOP_RIGHT_RADIUS = 0x010101aa
    private const val ATTR_BOTTOM_LEFT_RADIUS = 0x010101ab
    private const val ATTR_BOTTOM_RIGHT_RADIUS = 0x010101ac
    private const val ATTR_LEFT = 0x010101ad
    private const val ATTR_TOP = 0x010101ae
    private const val ATTR_RIGHT = 0x010101af
    private const val ATTR_BOTTOM = 0x010101b0
    private const val ATTR_DRAWABLE = 0x01010199
    private const val ATTR_OPACITY = 0x0101031e
    private const val ATTR_VIEWPORT_WIDTH = 0x01010402
    private const val ATTR_VIEWPORT_HEIGHT = 0x01010403
    private const val ATTR_FILL_COLOR = 0x01010404
    private const val ATTR_PATH_DATA = 0x01010405
}
