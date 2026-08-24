package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Aspect_ratio
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Drag_handle
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.ui.content.GlobalImageLoader
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveOptionDropdown
import java.nio.file.Path
import kotlin.math.roundToInt

@Composable
internal fun HomeSidePanelImageCard(
    card: ImageCardConfig,
    imageFile: Path?,
    editMode: Boolean,
    importing: Boolean,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    onSelectImage: (String) -> Unit = {},
    onScaleModeChange: (String, HomeSidePanelImageScaleMode) -> Unit = { _, _ -> },
    onHeightChange: (String, Int) -> Unit = { _, _ -> },
    onImageDimensionsChange: (String, Int, Int) -> Unit = { _, _, _ -> },
    onDeleteCard: (String) -> Unit = {},
) {
    val shape = RoundedCornerShape(24.dp)
    var imageFailed by remember(card.imageAssetId, imageFile) {
        mutableStateOf(card.imageAssetId != null && imageFile == null)
    }
    var visualHeightDp by remember(card.id) { mutableFloatStateOf(card.heightDp.toFloat()) }
    var resizing by remember(card.id) { mutableStateOf(false) }
    var decodedDimensions by remember(card.imageAssetId) {
        mutableStateOf(card.persistedImageDimensions)
    }
    var decodedAspectRatioUnsupported by remember(card.imageAssetId) { mutableStateOf(false) }
    LaunchedEffect(card.heightDp) {
        if (!resizing) visualHeightDp = card.heightDp.toFloat()
    }
    LaunchedEffect(decodedDimensions, editMode) {
        val dimensions = decodedDimensions ?: return@LaunchedEffect
        if (
            editMode &&
            (card.imageWidthPx != dimensions.widthPx || card.imageHeightPx != dimensions.heightPx)
        ) {
            onImageDimensionsChange(card.id, dimensions.widthPx, dimensions.heightPx)
        }
    }
    val bodyEnabled = editMode && !importing
    val selectionLabel = stringResource(
        if (card.imageAssetId == null) {
            R.string.home_side_panel_image_select
        } else {
            R.string.home_side_panel_image_replace
        },
    )
    val bodyModifier = if (bodyEnabled) {
        Modifier.clickable(
            onClickLabel = selectionLabel,
            onClick = { onSelectImage(card.id) },
        )
    } else {
        Modifier
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val dimensions = decodedDimensions ?: card.persistedImageDimensions
        val autoHeightPx = dimensions?.let {
            constraints.maxWidth.toLong() * it.heightPx.toLong() / it.widthPx.toLong()
        }
        val autoRatioUnsupported = card.scaleMode == HomeSidePanelImageScaleMode.AUTO_RATIO &&
            (decodedAspectRatioUnsupported ||
                (autoHeightPx != null && autoHeightPx > HOME_SIDE_PANEL_IMAGE_MAX_MEASURED_HEIGHT_PX))
        val effectiveHeightDp = if (
            card.scaleMode == HomeSidePanelImageScaleMode.AUTO_RATIO &&
            dimensions != null &&
            !autoRatioUnsupported
        ) {
            maxWidth.value * dimensions.heightPx.toFloat() / dimensions.widthPx.toFloat()
        } else {
            visualHeightDp
        }
        HomeSidePanelCardFrame(
            cardId = card.id,
            modifier = Modifier.fillMaxWidth(),
            cardModifier = Modifier
                .fillMaxWidth()
                .height(effectiveHeightDp.dp)
                .then(bodyModifier),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            editMode = editMode,
            onEdit = null,
            onDelete = null,
            badgeContent = if (editMode) {
                {
                    HomeSidePanelImageBadge(
                        card = card,
                        enabled = !importing,
                        onScaleModeChange = onScaleModeChange,
                        onDeleteCard = onDeleteCard,
                    )
                }
            } else {
                null
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                if (
                    card.imageAssetId != null &&
                    imageFile != null &&
                    !imageFailed &&
                    !autoRatioUnsupported
                ) {
                    AsyncImage(
                        model = imageFile.toFile(),
                        imageLoader = GlobalImageLoader,
                        contentDescription = stringResource(R.string.home_side_panel_card_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = card.scaleMode.contentScale,
                        onState = { state ->
                            imageFailed = state is AsyncImagePainter.State.Error
                            if (state is AsyncImagePainter.State.Success) {
                                val width = state.result.image.width
                                val height = state.result.image.height
                                if (width > 0 && height > 0) {
                                    if (isHomeSidePanelImageAspectRatioSupported(width, height)) {
                                        decodedAspectRatioUnsupported = false
                                        decodedDimensions = HomeSidePanelImageDimensions(width, height)
                                    } else {
                                        decodedAspectRatioUnsupported = true
                                    }
                                }
                            }
                        },
                    )
                } else {
                    HomeSidePanelImagePlaceholder(
                        message = when {
                            autoRatioUnsupported -> R.string.home_side_panel_image_aspect_ratio_unsupported
                            imageFailed && editMode -> R.string.home_side_panel_image_load_failed_replace
                            imageFailed -> R.string.home_side_panel_image_load_failed
                            editMode || preview -> R.string.home_side_panel_image_select
                            else -> R.string.home_side_panel_image_enter_edit
                        },
                    )
                }

                if (editMode && card.scaleMode != HomeSidePanelImageScaleMode.AUTO_RATIO) {
                    HomeSidePanelImageResizeHandle(
                        enabled = !importing,
                        configuredHeightDp = card.heightDp,
                        onResizeStateChange = { active -> resizing = active },
                        onVisualHeightChange = { visualHeightDp = it },
                        onHeightCommit = { onHeightChange(card.id, it) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 5.dp),
                    )
                }

                if (importing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSidePanelImagePlaceholder(message: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            MaterialSymbols.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun HomeSidePanelImageBadge(
    card: ImageCardConfig,
    enabled: Boolean,
    onScaleModeChange: (String, HomeSidePanelImageScaleMode) -> Unit,
    onDeleteCard: (String) -> Unit,
) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    val options = listOf(
        DropdownOption(HomeSidePanelImageScaleMode.CROP, stringResource(R.string.home_side_panel_image_scale_crop)),
        DropdownOption(HomeSidePanelImageScaleMode.FIT, stringResource(R.string.home_side_panel_image_scale_fit)),
        DropdownOption(
            HomeSidePanelImageScaleMode.FILL_BOUNDS,
            stringResource(R.string.home_side_panel_image_scale_stretch),
        ),
        DropdownOption(
            HomeSidePanelImageScaleMode.AUTO_RATIO,
            stringResource(R.string.home_side_panel_image_scale_auto_ratio),
        ),
    )
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 2.dp,
        ) {
            Row {
                Box {
                    HomeSidePanelBadgeButton(
                        onClick = { expanded = true }.takeIf { enabled },
                        contentDescription = stringResource(R.string.home_side_panel_image_scale_mode),
                        icon = MaterialSymbols.Outlined.Aspect_ratio,
                    )
                    ExpressiveOptionDropdown(
                        expanded = expanded,
                        value = card.scaleMode,
                        options = options,
                        onDismissRequest = { expanded = false },
                        onValueChange = {
                            onScaleModeChange(card.id, it)
                            expanded = false
                        },
                    )
                }
                HomeSidePanelBadgeButton(
                    onClick = { onDeleteCard(card.id) }.takeIf { enabled },
                    contentDescription = stringResource(R.string.home_side_panel_delete_card),
                    icon = MaterialSymbols.Outlined.Close,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HomeSidePanelImageResizeHandle(
    enabled: Boolean,
    configuredHeightDp: Int,
    onResizeStateChange: (Boolean) -> Unit,
    onVisualHeightChange: (Float) -> Unit,
    onHeightCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val description = stringResource(R.string.home_side_panel_image_resize)
    Surface(
        modifier = modifier
            .size(width = 52.dp, height = 28.dp)
            .semantics { contentDescription = description }
            .pointerInput(enabled, configuredHeightDp) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    var rawHeight = configuredHeightDp.toFloat()
                    var completed = false
                    onResizeStateChange(true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            when (
                                homeSidePanelPointerLifecycleDecision(
                                    previousPressed = change.previousPressed,
                                    pressed = change.pressed,
                                    consumedAtInitialPass = change.isConsumed,
                                )
                            ) {
                                HomeSidePanelPointerLifecycleDecision.Continue -> {
                                    val deltaDp = with(density) { change.positionChange().y.toDp().value }
                                    if (deltaDp != 0f) {
                                        rawHeight = (rawHeight + deltaDp).coerceIn(
                                            HOME_SIDE_PANEL_IMAGE_MIN_HEIGHT_DP.toFloat(),
                                            HOME_SIDE_PANEL_IMAGE_MAX_HEIGHT_DP.toFloat(),
                                        )
                                        onVisualHeightChange(rawHeight)
                                    }
                                    change.consume()
                                }

                                HomeSidePanelPointerLifecycleDecision.Finish -> {
                                    completed = true
                                    change.consume()
                                    break
                                }

                                HomeSidePanelPointerLifecycleDecision.Cancel -> break
                            }
                        }
                        if (completed) {
                            onHeightCommit(
                                ((rawHeight / HOME_SIDE_PANEL_IMAGE_HEIGHT_STEP_DP).roundToInt() *
                                    HOME_SIDE_PANEL_IMAGE_HEIGHT_STEP_DP).coerceIn(
                                    HOME_SIDE_PANEL_IMAGE_MIN_HEIGHT_DP,
                                    HOME_SIDE_PANEL_IMAGE_MAX_HEIGHT_DP,
                                ),
                            )
                        }
                    } finally {
                        if (!completed) onVisualHeightChange(configuredHeightDp.toFloat())
                        onResizeStateChange(false)
                    }
                }
            },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                MaterialSymbols.Outlined.Drag_handle,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val HomeSidePanelImageScaleMode.contentScale: ContentScale
    get() = when (this) {
        HomeSidePanelImageScaleMode.CROP -> ContentScale.Crop
        HomeSidePanelImageScaleMode.FIT -> ContentScale.Fit
        HomeSidePanelImageScaleMode.FILL_BOUNDS -> ContentScale.FillBounds
        HomeSidePanelImageScaleMode.AUTO_RATIO -> ContentScale.Fit
    }

private data class HomeSidePanelImageDimensions(
    val widthPx: Int,
    val heightPx: Int,
)

private val ImageCardConfig.persistedImageDimensions: HomeSidePanelImageDimensions?
    get() {
        val width = imageWidthPx ?: return null
        val height = imageHeightPx ?: return null
        return HomeSidePanelImageDimensions(width, height)
    }

private const val HOME_SIDE_PANEL_IMAGE_MAX_MEASURED_HEIGHT_PX = 262_000L
