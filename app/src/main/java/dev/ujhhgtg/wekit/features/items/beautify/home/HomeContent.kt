package dev.ujhhgtg.wekit.features.items.beautify.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Search
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.TAB_CONTACTS
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.TAB_DISCOVER
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.TAB_ME
import dev.ujhhgtg.wekit.features.items.beautify.navigation_bar.navTab
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.utils.rememberDeviceCornerRadius
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val FrameHorizontalInset = 12.dp

@Composable
fun HomeContent(
    content: View,
    active: Boolean,
    statusBarTopPx: Int,
    navigationTabs: List<Int>,
    onNavigate: (Int) -> Unit,
    onSearch: () -> Unit,
    onBackgroundLongPress: () -> Unit,
    menuItems: () -> List<HomeMenuItem>,
) {
    var time by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                time = LocalTime.now()
                delay(60_050L - System.currentTimeMillis() % 60_000L)
            }
        }
    }
    val resources = LocalResources.current
    val localizedContext = LocalWeKitLocalizedContext.current
    val wallpaperSource = HomeWallpaper.source
    val wallpaperRevision = HomeWallpaper.revision
    val background by
        produceState<ImageBitmap?>(null, resources, wallpaperSource, wallpaperRevision) {
            try {
                value =
                    withContext(Dispatchers.IO) { HomeWallpaper.load(resources, wallpaperSource) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                WeLogger.e(
                    "BeautifyHome",
                    "Unable to load home background: $wallpaperSource",
                    error,
                )
                showToast(localizedContext.getString(R.string.beautify_home_background_load_failed))
                value =
                    withContext(Dispatchers.IO) {
                        HomeWallpaper.load(resources, HomeWallpaperSource.BUILTIN_SECOND)
                    }
            }
        }
    val wallpaperStyle = HomeWallpaper.style
    val colors = MaterialTheme.colorScheme
    val darkSurface = colors.surface.luminance() < 0.5f
    val surface = lerp(colors.surface.copy(alpha = 1f), colors.primary, 0.025f)
    val foreground =
        if (colors.onSurface.luminance() > 0.5f) Color(0xFFD7E2F0) else Color(0xFF29445A)
    val deviceCorner = rememberDeviceCornerRadius()
    val panelCorner = if (deviceCorner < 16.dp) 24.dp else deviceCorner
    BoxWithConstraints(Modifier.fillMaxSize().background(surface)) {
        // YumeBox's visible rail is one quarter of the screen minus the panel's 28dp overlap.
        val railWidth = (maxWidth * 0.25f - 28.dp).coerceAtLeast(0.dp)
        // Extend the sidebar under the panel's cutouts, as in YumeBox's MoeHomeLayout.
        val decorationWidth = maxOf(maxWidth * 0.25f, railWidth + panelCorner)
        Box(Modifier.width(decorationWidth).fillMaxHeight().clipToBounds()) {
            background?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(
                                this@BoxWithConstraints.maxWidth,
                                this@BoxWithConstraints.maxHeight,
                            )
                            .blur(52.dp),
                )
            }
            Box(
                Modifier.matchParentSize()
                    .background(
                        if (darkSurface) {
                            Color.Black.copy(alpha = 0.24f)
                        } else {
                            colors.surface.copy(alpha = 0.13f)
                        },
                    )
                    .background(Color.Black.copy(alpha = 0.1f))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                if (darkSurface) {
                                    Color.Black.copy(alpha = 0.36f)
                                } else {
                                    colors.surface.copy(alpha = 0.23f)
                                },
                                colors.surface.copy(alpha = if (darkSurface) 0.18f else 0.16f),
                            ),
                        ),
                    ),
            )
        }
        Row(Modifier.fillMaxSize()) {
            HomeRail(
                time,
                active,
                navigationTabs,
                onNavigate,
                Modifier.width(railWidth).fillMaxHeight().padding(vertical = 24.dp),
            )
            BoxWithConstraints(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = panelCorner, bottomStart = panelCorner))
                    .background(surface),
            ) {
                val density = LocalDensity.current
                val systemPadding = WindowInsets.systemBars.asPaddingValues()
                val contentHeight =
                    (maxHeight -
                            systemPadding.calculateTopPadding() -
                            systemPadding.calculateBottomPadding() -
                            16.dp)
                        .coerceAtLeast(0.dp)
                val titleHeight = with(density) { 54.sp.toDp() }.coerceAtLeast(48.dp)
                val headerHeight = with(density) { 30.sp.toDp() } + titleHeight + 6.dp + 14.dp
                val desiredTop =
                    min(contentHeight.value * 0.44f, max(contentHeight.value - 440f, 140f)).dp
                val maximumTop = (contentHeight - headerHeight - 160.dp).coerceAtLeast(0.dp)
                val topSpacer = desiredTop.coerceIn(0.dp, maximumTop)
                val frameTop =
                    maxOf(
                        systemPadding.calculateTopPadding(),
                        with(density) { statusBarTopPx.toDp() },
                    )
                // The frame remains a background layer. The existing header and list
                // overlap its lower fade instead of being laid out below the picture.
                val frameHeight = (maxHeight - frameTop).coerceAtLeast(0.dp) * 0.63f
                HomeBackground(
                    background,
                    wallpaperStyle,
                    surface,
                    frameTop,
                    frameHeight,
                    active,
                    onBackgroundLongPress,
                )
                val contentHorizontalInset =
                    if (wallpaperStyle == HomeWallpaperStyle.FRAMED) {
                        FrameHorizontalInset
                    } else {
                        24.dp
                    }
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 16.dp)) {
                    Spacer(Modifier.height(topSpacer))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                        HomeHeader(time, foreground, active, onSearch, menuItems)
                    }
                    AndroidView(
                        factory = { content },
                        modifier =
                            Modifier.fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = contentHorizontalInset)
                                .clip(RoundedCornerShape(20.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeBackground(
    background: ImageBitmap?,
    style: HomeWallpaperStyle,
    surface: Color,
    statusBarTop: Dp,
    frameHeight: Dp,
    active: Boolean,
    onLongPress: () -> Unit,
) {
    val framed = style == HomeWallpaperStyle.FRAMED
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val haptic = LocalHapticFeedback.current
    // YumeBox's MoeHero: 12dp side insets, a 28dp frame, and a fade inside the image.
    val imageModifier =
        if (framed) {
            Modifier.fillMaxWidth()
                .padding(
                    start = FrameHorizontalInset,
                    end = FrameHorizontalInset,
                    top = statusBarTop,
                )
                .height(frameHeight)
                .graphicsLayer {
                    shape = RoundedCornerShape(28.dp)
                    clip = true
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
        } else {
            Modifier.fillMaxWidth().fillMaxHeight(0.6f)
        }
    Box(
        imageModifier.pointerInput(active) {
            if (active) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnLongPress()
                    },
                )
            }
        },
    ) {
        background?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (framed) {
            Box(
                Modifier.matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            0.78f to surface.copy(alpha = 0.88f),
                            0.90f to surface,
                            1f to surface,
                        ),
                    ),
            )
        }
    }
    if (!framed) {
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to surface.copy(alpha = 0f),
                        0.3f to surface.copy(alpha = 0f),
                        0.43f to surface.copy(alpha = 0.86f),
                        0.56f to surface,
                        1f to surface,
                    ),
                ),
        )
    }
}

@Composable
private fun HomeHeader(
    time: LocalTime,
    color: Color,
    active: Boolean,
    onSearch: () -> Unit,
    menuItems: () -> List<HomeMenuItem>,
) {
    val greeting =
        when (time.hour) {
            in 5..11 -> R.string.beautify_home_good_morning
            in 12..17 -> R.string.beautify_home_good_afternoon
            else -> R.string.beautify_home_good_evening
        }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.beautify_home_brand),
            modifier = Modifier.weight(1f),
            color = color,
            fontSize = 42.sp,
            lineHeight = 54.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        HomeMenu(active, onSearch, menuItems, color)
    }
    Spacer(Modifier.height(6.dp))
    Text(stringResource(greeting), color = color, fontSize = 22.sp, lineHeight = 30.sp)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun HomeRail(
    time: LocalTime,
    active: Boolean,
    navigationTabs: List<Int>,
    onNavigate: (Int) -> Unit,
    modifier: Modifier,
) {
    // Geometry and opacity follow YumeBox's MoeSidebarRail/MoeUi.Sidebar:
    // https://github.com/YumeYucca/YumeBox/blob/769335d2d286d5167c0547a192cdbc84d404cdaf/app/src/screen/moe/MoeHomeSupport.kt
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(70.dp))
        Column(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RailTimeValue(time.hour.toString().padStart(2, '0'))
            RailDivider()
            RailTimeValue(time.minute.toString().padStart(2, '0'))
        }
        HomeBattery(active, Modifier.padding(top = 18.dp))
        Spacer(Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            navigationTabs.forEachIndexed { index, id ->
                if (index > 0) RailDivider()
                val tab = navTab(id)
                val icon =
                    when (id) {
                        TAB_CONTACTS -> HomeIcons.Contacts
                        TAB_DISCOVER -> HomeIcons.Discover
                        TAB_ME -> HomeIcons.Profile
                        else -> tab.outlined
                    }
                RailDestination(icon, tab.labelRes) { onNavigate(id) }
            }
        }
        Spacer(Modifier.height(38.dp))
    }
}

@Composable
private fun RailTimeValue(value: String) {
    Text(
        value,
        color = Color.White.copy(alpha = 0.96f),
        style = TextStyle(fontSize = 43.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        softWrap = false,
    )
}

@Composable
private fun RailDivider() {
    Box(Modifier.width(22.dp).height(2.dp).background(Color.White.copy(alpha = 0.62f)))
}

@Composable
private fun RailDestination(icon: ImageVector, title: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            stringResource(title),
            modifier = Modifier.size(30.dp),
            tint = Color.White.copy(alpha = 0.88f),
        )
    }
}

@Composable
private fun HomeBattery(active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var percentage by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context, active) {
        fun update(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            percentage =
                if (level < 0 || scale <= 0) {
                    null
                } else {
                    (level * 100f / scale).toInt().coerceIn(0, 100)
                }
        }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = update(intent)
            }
        if (active) {
            update(
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                ),
            )
        }
        onDispose { if (active) context.unregisterReceiver(receiver) }
    }
    val capsuleShape = RoundedCornerShape(999.dp)
    val fillFraction = ((percentage ?: 66) / 100f).coerceIn(0.12f, 1f)
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(36.dp)
                    .height(16.dp)
                    .clip(capsuleShape)
                    .background(Color.White.copy(alpha = 0.36f))
                    .padding(2.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier.fillMaxWidth(fillFraction)
                        .fillMaxHeight()
                        .clip(capsuleShape)
                        .background(Color.White.copy(alpha = 0.86f)),
                )
            }
            Box(
                Modifier.width(3.dp)
                    .height(16.dp * 0.46f)
                    .clip(capsuleShape)
                    .background(Color.White.copy(alpha = 0.54f)),
            )
        }
        Text(
            percentage?.let { "$it%" } ?: "--%",
            color = Color.White.copy(alpha = 0.82f),
            style = TextStyle(fontSize = 12.sp),
            softWrap = false,
        )
    }
}

@Composable
private fun HomeMenu(
    active: Boolean,
    onSearch: () -> Unit,
    menuItems: () -> List<HomeMenuItem>,
    color: Color,
) {
    var expanded by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<HomeMenuItem>()) }
    LaunchedEffect(active) { if (!active) expanded = false }
    Box {
        IconButton(
            onClick = {
                items = menuItems()
                expanded = true
            },
            enabled = active,
        ) {
            Icon(
                MaterialSymbols.Outlined.More_vert,
                stringResource(R.string.beautify_home_menu),
                tint = color,
            )
        }
        DropdownMenu(expanded && active, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.beautify_home_search)) },
                leadingIcon = { Icon(MaterialSymbols.Outlined.Search, null) },
                onClick = {
                    expanded = false
                    onSearch()
                },
            )
            for (item in items) {
                DropdownMenuItem(
                    text = { Text(item.title) },
                    onClick = {
                        expanded = false
                        item.click()
                    },
                )
            }
        }
    }
}
