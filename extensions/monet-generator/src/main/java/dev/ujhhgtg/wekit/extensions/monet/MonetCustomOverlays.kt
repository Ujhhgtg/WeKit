package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.MonetOverlayApkWriter.DrawableTarget
import dev.ujhhgtg.wekit.extensions.monet.MonetOverlayApkWriter.XmlAttribute
import dev.ujhhgtg.wekit.extensions.monet.MonetOverlayApkWriter.XmlNode
import dev.ujhhgtg.wekit.extensions.monet.MonetOverlayApkWriter.XmlValue
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle

internal object MonetCustomOverlays {
    data class Palette(
        val incomingLight: Int,
        val incomingNight: Int,
        val outgoingLight: Int,
        val outgoingNight: Int,
        val surfaceLight: Int,
        val surfaceNight: Int,
        val primaryLight: Int,
        val primaryNight: Int,
    )

    fun baseVisuals(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        addPair(resolved, "brand.circular.background", oval(palette.primaryLight), oval(palette.primaryNight))
        addPair(resolved, "launcher.splash.background", shape(palette.surfaceLight), shape(palette.surfaceNight))
        addPair(resolved, "chat.brand-action.background", shape(palette.primaryLight, 16f), shape(palette.primaryNight, 16f))
        addPair(resolved, "chat.red-envelope.incoming.normal", shape(palette.primaryLight, 16f), shape(palette.primaryNight, 16f))
        addPair(resolved, "chat.red-envelope.outgoing.normal", shape(palette.primaryLight, 16f), shape(palette.primaryNight, 16f))
        val paymentLight = selector(shape(0x10000000, 8f), shape(palette.primaryLight, 8f))
        val paymentNight = selector(shape(0x26000000, 8f), shape(palette.primaryNight, 8f))
        addPair(resolved, "payment.key.primary", paymentLight, paymentNight)
        addPair(resolved, "payment.key.secondary", paymentLight, paymentNight)
        addPair(resolved, "chat.input.transparent-layer", shape(0x00000000), shape(0x00000000))
        val headerLight = header(palette.surfaceLight, palette.primaryLight)
        val headerNight = header(palette.surfaceNight, palette.primaryNight)
        addPair(resolved, "main.surface.header.primary", headerLight, headerNight)
        addPair(resolved, "main.surface.header.secondary", headerLight, headerNight)
    }

    fun bubbles(
        resolved: Map<String, MonetResourceNode>,
        style: MonetBubbleStyle,
        palette: Palette,
    ): List<DrawableTarget> {
        if (style == MonetBubbleStyle.CLASSIC) return emptyList()
        val radius = if (style == MonetBubbleStyle.PRO) 20f else 16f
        val outlineLight = if (style == MonetBubbleStyle.PRO) 0x26000000 else null
        val outlineNight = if (style == MonetBubbleStyle.PRO) 0x36ffffff else null
        val incomingLight = bubble(palette.incomingLight, radius, outlineLight)
        val incomingNight = bubble(palette.incomingNight, radius, outlineNight)
        val outgoingLight = bubble(palette.outgoingLight, radius, outlineLight)
        val outgoingNight = bubble(palette.outgoingNight, radius, outlineNight)
        val maskLight = shape(0x00000000, radius)
        val maskNight = shape(0x26000000, radius)
        return buildList {
            INCOMING_BUBBLES.forEach { addPair(resolved, it, incomingLight, incomingNight) }
            OUTGOING_BUBBLES.forEach { addPair(resolved, it, outgoingLight, outgoingNight) }
            INCOMING_MASKS.forEach { addPair(resolved, it, maskLight, maskNight) }
            OUTGOING_MASKS.forEach { addPair(resolved, it, maskLight, maskNight) }
            addPair(resolved, "chat.voice-to-text.background", incomingLight, incomingNight)
        }.distinctBy(DrawableTarget::name)
    }

    fun classicBubbles(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
    ): List<DrawableTarget> {
        val incomingLight = classicBubble(palette.incomingLight, incoming = true)
        val incomingNight = classicBubble(palette.incomingNight, incoming = true)
        val outgoingLight = classicBubble(palette.outgoingLight, incoming = false)
        val outgoingNight = classicBubble(palette.outgoingNight, incoming = false)
        val incomingMask = classicMask(incoming = true)
        val outgoingMask = classicMask(incoming = false)
        return buildList {
            INCOMING_BUBBLES.forEach { addPair(resolved, it, incomingLight, incomingNight) }
            OUTGOING_BUBBLES.forEach { addPair(resolved, it, outgoingLight, outgoingNight) }
            INCOMING_MASKS.forEach { addPair(resolved, it, incomingMask, incomingMask) }
            OUTGOING_MASKS.forEach { addPair(resolved, it, outgoingMask, outgoingMask) }
            addPair(resolved, "chat.voice-to-text.background", incomingLight, incomingNight)
        }.distinctBy(DrawableTarget::name)
    }

    fun corners(
        resolved: Map<String, MonetResourceNode>,
        palette: Palette,
    ): List<DrawableTarget> = buildList {
        addPair(
            resolved,
            "chat.input.background",
            shape(palette.surfaceLight, 12f),
            shape(palette.surfaceNight, 12f),
        )
        addPair(resolved, "chat.quote.background", shape(0x10000000, 10f), shape(0x10ffffff, 10f))
        val normalLight = shape(palette.surfaceLight, 10f)
        val normalNight = shape(palette.surfaceNight, 10f)
        addPair(
            resolved,
            "payment.key.pressed",
            selector(shape(0x10000000, 10f), normalLight),
            selector(shape(0x26000000, 10f), normalNight),
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
            DrawableTarget("wekit_icon_bg", shape(palette.primaryLight), shape(palette.primaryNight)),
            DrawableTarget("wekit_icon_fg", iconVector(palette.incomingLight), iconVector(palette.incomingNight)),
            DrawableTarget("wekit_icon_mono", iconVector(0x0106000c)),
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

    private fun shape(color: Int, radius: Float? = null): XmlNode = XmlNode(
        "shape",
        children = buildList {
            add(XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color)))))
            radius?.let { add(XmlNode("corners", listOf(android("radius", ATTR_RADIUS, XmlValue.Dimension(it))))) }
        },
    )

    private fun oval(color: Int): XmlNode = XmlNode(
        "shape",
        listOf(android("shape", ATTR_SHAPE, XmlValue.Integer(1))),
        listOf(XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color))))),
    )

    private fun bubble(color: Int, radius: Float, outline: Int?): XmlNode = XmlNode(
        "shape",
        children = buildList {
            add(XmlNode("solid", listOf(android("color", ATTR_COLOR, colorValue(color)))))
            outline?.let {
                add(XmlNode("stroke", listOf(
                    android("width", ATTR_WIDTH, XmlValue.Dimension(1f)),
                    android("color", ATTR_COLOR, XmlValue.Color(it)),
                )))
            }
            add(XmlNode("corners", listOf(android("radius", ATTR_RADIUS, XmlValue.Dimension(radius)))))
            add(XmlNode("padding", listOf(
                android("left", ATTR_LEFT, XmlValue.Dimension(12f)),
                android("top", ATTR_TOP, XmlValue.Dimension(8f)),
                android("right", ATTR_RIGHT, XmlValue.Dimension(12f)),
                android("bottom", ATTR_BOTTOM, XmlValue.Dimension(8f)),
            )))
        },
    )

    private fun classicBubble(color: Int, incoming: Boolean): XmlNode = XmlNode(
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
            XmlNode("padding", listOf(
                android("left", ATTR_LEFT, XmlValue.Dimension(12f)),
                android("top", ATTR_TOP, XmlValue.Dimension(8f)),
                android("right", ATTR_RIGHT, XmlValue.Dimension(12f)),
                android("bottom", ATTR_BOTTOM, XmlValue.Dimension(8f)),
            )),
        ),
    )

    private fun classicMask(incoming: Boolean): XmlNode = classicBubble(0x1a000000, incoming)

    private fun selector(pressed: XmlNode, normal: XmlNode): XmlNode = XmlNode(
        "selector",
        children = listOf(
            XmlNode("item", listOf(android("state_pressed", ATTR_STATE_PRESSED, XmlValue.Boolean(true))), listOf(pressed)),
            XmlNode("item", children = listOf(normal)),
        ),
    )

    private fun header(surface: Int, accent: Int): XmlNode = XmlNode(
        "layer-list",
        children = listOf(
            XmlNode("item", children = listOf(shape(surface))),
            XmlNode("item", listOf(android("top", ATTR_TOP, XmlValue.Dimension(120f))), listOf(shape(accent))),
        ),
    )

    private fun iconVector(color: Int): XmlNode = XmlNode(
        "vector",
        listOf(
            android("height", ATTR_HEIGHT, XmlValue.Dimension(324f)),
            android("width", ATTR_WIDTH, XmlValue.Dimension(324f)),
            android("viewportWidth", ATTR_VIEWPORT_WIDTH, XmlValue.Float(324f)),
            android("viewportHeight", ATTR_VIEWPORT_HEIGHT, XmlValue.Float(324f)),
        ),
        ICON_PATHS.map { path ->
            XmlNode(
                "path",
                listOf(
                    android("fillColor", ATTR_FILL_COLOR, colorValue(color)),
                    android("pathData", ATTR_PATH_DATA, XmlValue.String(path)),
                ),
            )
        },
    )

    private fun colorValue(value: Int): XmlValue = if (value ushr 24 == 0x01) {
        XmlValue.Reference(value)
    } else {
        XmlValue.Color(value)
    }

    private fun android(name: String, id: Int, value: XmlValue) = XmlAttribute(name, id, value)

    private val INCOMING_BUBBLES = listOf(
        "chat.bubble.incoming.normal", "chat.bubble.incoming.link",
        "chat.bubble.incoming.pro", "chat.bubble.incoming.pro.handled",
        "chat.red-envelope.incoming.alias", "chat.transfer.incoming.received", "chat.transfer.incoming.expired",
    )
    private val OUTGOING_BUBBLES = listOf(
        "chat.bubble.outgoing.normal", "chat.bubble.outgoing.link",
        "chat.bubble.outgoing.pro", "chat.bubble.outgoing.pro.handled",
        "chat.red-envelope.outgoing.alias", "chat.transfer.outgoing.received", "chat.transfer.outgoing.expired",
    )
    private val INCOMING_MASKS = listOf("chat.bubble.incoming.link.mask")
    private val OUTGOING_MASKS = listOf("chat.bubble.outgoing.link.mask")

    private const val ATTR_STATE_PRESSED = 0x010100a7
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
    private const val ATTR_VIEWPORT_WIDTH = 0x01010402
    private const val ATTR_VIEWPORT_HEIGHT = 0x01010403
    private const val ATTR_FILL_COLOR = 0x01010404
    private const val ATTR_PATH_DATA = 0x01010405

    private val ICON_PATHS = listOf(
        "M87,145a54,45 0 0 1 108,0a54,45 0 0 1 -108,0Z",
        "M106,179a4.627,4.627,0,0,1,1,4c-0.641,2.143-3,9-3,9s-0.942,4.954,4,2,14-9,14-9l-9-8Z",
        "M191.5,141C166.37,141 146,157.79 146,178.5S166.37,216 191.5,216 237,199.21 237,178.5 216.63,141 191.5,141Z",
        "M221.3,206.689a4,4,0,0,0-.864,3.459c.554,1.853,2.594,7.781,2.594,7.781s.814,4.283-3.459,1.729-12.1-7.781-12.1-7.781l7.781-6.917Z",
    )
}
