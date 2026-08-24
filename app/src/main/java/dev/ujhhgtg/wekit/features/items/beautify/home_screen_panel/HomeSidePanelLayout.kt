package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonClassDiscriminator
import java.util.UUID

internal const val HOME_SIDE_PANEL_LAYOUT_VERSION = 1
internal const val HOME_SIDE_PANEL_IMAGE_MIN_HEIGHT_DP = 80
internal const val HOME_SIDE_PANEL_IMAGE_MAX_HEIGHT_DP = 800
internal const val HOME_SIDE_PANEL_IMAGE_HEIGHT_STEP_DP = 8
internal const val HOME_SIDE_PANEL_IMAGE_MAX_ASPECT_RATIO = 100

internal fun interface HomeSidePanelIdGenerator {
    fun nextId(): String
}

internal object UuidHomeSidePanelIdGenerator : HomeSidePanelIdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}

@Serializable
internal data class HomeSidePanelLayout(
    val version: Int = HOME_SIDE_PANEL_LAYOUT_VERSION,
    val cards: List<HomeSidePanelCardConfig>,
)

@Serializable
internal enum class HomeSidePanelActionKind {
    ADD_FRIEND,
    SCAN,
    MOMENTS,
    WALLET,
    CHANNELS,
    WECHAT_SETTINGS,
    FAVORITES,
    WEKIT_SETTINGS,
    RESTART_WECHAT,
    FORCE_STOP_WECHAT,
    MARK_ALL_READ,
}

@Serializable
internal enum class HomeSidePanelCardType {
    DATE_TIME,
    WEATHER,
    WALLET,
    HITOKOTO,
    IMAGE,
    HORIZONTAL_ACTIONS,
    VERTICAL_ACTIONS,
}

@Serializable
internal data class HomeSidePanelActionConfig(
    val id: String,
    val kind: HomeSidePanelActionKind,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("cardType")
internal sealed class HomeSidePanelCardConfig {
    abstract val id: String
    abstract val type: HomeSidePanelCardType
}

@Serializable
@SerialName("date_time")
internal data class DateTimeCardConfig(
    override val id: String,
    val showLunarCalendar: Boolean = false,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.DATE_TIME
}

@Serializable
@SerialName("weather")
internal data class WeatherCardConfig(
    override val id: String,
    val city: WeatherCity,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.WEATHER
}

@Serializable
@SerialName("wallet")
internal data class WalletCardConfig(
    override val id: String,
    val hideBalanceByDefault: Boolean = false,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.WALLET
}

@Serializable
@SerialName("hitokoto")
internal data class HitokotoCardConfig(
    override val id: String,
    val settings: HitokotoSettings = HitokotoSettings(),
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.HITOKOTO
}

@Serializable
internal enum class HomeSidePanelImageScaleMode {
    CROP,
    FIT,
    FILL_BOUNDS,
    AUTO_RATIO,
}

@Serializable
@SerialName("image")
internal data class ImageCardConfig(
    override val id: String,
    val imageAssetId: String? = null,
    val imageWidthPx: Int? = null,
    val imageHeightPx: Int? = null,
    val heightDp: Int = 240,
    val scaleMode: HomeSidePanelImageScaleMode = HomeSidePanelImageScaleMode.CROP,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.IMAGE
}

@Serializable
@SerialName("horizontal_actions")
internal data class HorizontalActionsCardConfig(
    override val id: String,
    val actions: List<HomeSidePanelActionConfig>,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.HORIZONTAL_ACTIONS
}

@Serializable
@SerialName("vertical_actions")
internal data class VerticalActionsCardConfig(
    override val id: String,
    val actions: List<HomeSidePanelActionConfig>,
) : HomeSidePanelCardConfig() {
    @Transient
    override val type: HomeSidePanelCardType = HomeSidePanelCardType.VERTICAL_ACTIONS
}

internal class InvalidHomeSidePanelLayoutException(message: String) : IllegalArgumentException(message)

internal fun validateHomeSidePanelLayout(layout: HomeSidePanelLayout) {
    if (layout.version != HOME_SIDE_PANEL_LAYOUT_VERSION) {
        throw InvalidHomeSidePanelLayoutException("Unsupported layout version: ${layout.version}")
    }
    val cardIds = layout.cards.map(HomeSidePanelCardConfig::id)
    if (cardIds.any(String::isBlank)) {
        throw InvalidHomeSidePanelLayoutException("Card IDs must not be blank")
    }
    if (cardIds.size != cardIds.toSet().size) {
        throw InvalidHomeSidePanelLayoutException("Card IDs must be unique")
    }
    layout.cards.forEach { card ->
        when (card) {
            is HitokotoCardConfig -> validateHitokotoSettings(
                minLength = card.settings.minLength,
                maxLength = card.settings.maxLength,
                categories = card.settings.categories,
            )?.let { throw InvalidHomeSidePanelLayoutException("Invalid hitokoto settings: $it") }

            is ImageCardConfig -> {
                card.imageAssetId?.let { assetId ->
                    val parsed = runCatching { UUID.fromString(assetId) }.getOrElse {
                        throw InvalidHomeSidePanelLayoutException("Invalid image asset ID: $assetId")
                    }
                    if (parsed.toString() != assetId) {
                        throw InvalidHomeSidePanelLayoutException("Invalid image asset ID: $assetId")
                    }
                }
                if (
                    card.heightDp !in HOME_SIDE_PANEL_IMAGE_MIN_HEIGHT_DP..HOME_SIDE_PANEL_IMAGE_MAX_HEIGHT_DP ||
                    card.heightDp % HOME_SIDE_PANEL_IMAGE_HEIGHT_STEP_DP != 0
                ) {
                    throw InvalidHomeSidePanelLayoutException("Invalid image card height: ${card.heightDp}")
                }
                val width = card.imageWidthPx
                val height = card.imageHeightPx
                if (card.imageAssetId == null && (width != null || height != null)) {
                    throw InvalidHomeSidePanelLayoutException("An empty image card cannot have dimensions")
                }
                if ((width == null) != (height == null)) {
                    throw InvalidHomeSidePanelLayoutException("Image dimensions must both be present or absent")
                }
                if (width != null && height != null) {
                    if (
                        width <= 0 ||
                        height <= 0 ||
                        width.toLong() * height.toLong() > 50_000_000L ||
                        !isHomeSidePanelImageAspectRatioSupported(width, height)
                    ) {
                        throw InvalidHomeSidePanelLayoutException("Invalid image dimensions: ${width}x$height")
                    }
                }
            }

            is HorizontalActionsCardConfig -> validateActionIds(card.actions)
            is VerticalActionsCardConfig -> validateActionIds(card.actions)
            else -> Unit
        }
    }
}

internal fun isHomeSidePanelImageAspectRatioSupported(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    val longer = maxOf(width, height).toLong()
    val shorter = minOf(width, height).toLong()
    return longer <= shorter * HOME_SIDE_PANEL_IMAGE_MAX_ASPECT_RATIO
}

internal fun HomeSidePanelLayout.imageAssetIds(): Set<String> = cards
    .filterIsInstance<ImageCardConfig>()
    .mapNotNullTo(linkedSetOf(), ImageCardConfig::imageAssetId)

private fun validateActionIds(actions: List<HomeSidePanelActionConfig>) {
    val actionIds = actions.map(HomeSidePanelActionConfig::id)
    if (actionIds.any(String::isBlank)) {
        throw InvalidHomeSidePanelLayoutException("Action IDs must not be blank")
    }
    if (actionIds.size != actionIds.toSet().size) {
        throw InvalidHomeSidePanelLayoutException("Action IDs must be unique within a card")
    }
}

internal object HomeSidePanelLayoutCodec {

    fun encode(layout: HomeSidePanelLayout): String {
        validateHomeSidePanelLayout(layout)
        return DefaultJson.encodeToString(layout)
    }

    fun decode(raw: String): HomeSidePanelLayout =
        DefaultJson.decodeFromString<HomeSidePanelLayout>(raw).also(::validateHomeSidePanelLayout)

    fun load(
        raw: String,
        legacy: LegacyHomeSidePanelSnapshot,
        idGenerator: HomeSidePanelIdGenerator,
    ): HomeSidePanelLayoutLoad = try {
        HomeSidePanelLayoutLoad.Stored(decode(raw))
    } catch (error: Exception) {
        HomeSidePanelLayoutLoad.Fallback(
            layout = defaultHomeSidePanelLayout(legacy, idGenerator),
            invalidRaw = raw,
            reason = error.message ?: error::class.simpleName.orEmpty(),
        )
    }
}

internal data class LegacyHomeSidePanelSnapshot(
    val weatherCity: WeatherCity,
    val hideWalletBalance: Boolean,
    val hitokotoSettings: HitokotoSettings,
) {
    companion object {
        fun defaults() = LegacyHomeSidePanelSnapshot(
            DEFAULT_WEATHER_CITY,
            false,
            HitokotoSettings(),
        )
    }
}

internal sealed interface HomeSidePanelLayoutLoad {
    val layout: HomeSidePanelLayout

    data class Stored(override val layout: HomeSidePanelLayout) : HomeSidePanelLayoutLoad
    data class Migrated(override val layout: HomeSidePanelLayout) : HomeSidePanelLayoutLoad
    data class Fallback(
        override val layout: HomeSidePanelLayout,
        val invalidRaw: String,
        val reason: String,
    ) : HomeSidePanelLayoutLoad
}

internal fun defaultHomeSidePanelLayout(
    legacy: LegacyHomeSidePanelSnapshot,
    idGenerator: HomeSidePanelIdGenerator = UuidHomeSidePanelIdGenerator,
): HomeSidePanelLayout = HomeSidePanelLayout(
    cards = listOf(
        DateTimeCardConfig(idGenerator.nextId()),
        WeatherCardConfig(idGenerator.nextId(), legacy.weatherCity),
        WalletCardConfig(idGenerator.nextId(), legacy.hideWalletBalance),
        VerticalActionsCardConfig(
            idGenerator.nextId(),
            listOf(
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.ADD_FRIEND),
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.MOMENTS),
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.CHANNELS),
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.MARK_ALL_READ),
                HomeSidePanelActionConfig(idGenerator.nextId(), HomeSidePanelActionKind.WEKIT_SETTINGS),
            ),
        ),
        HitokotoCardConfig(idGenerator.nextId(), legacy.hitokotoSettings),
    ),
)
