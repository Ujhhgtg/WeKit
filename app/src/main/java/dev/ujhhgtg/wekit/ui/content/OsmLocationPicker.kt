package dev.ujhhgtg.wekit.ui.content

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.ui.utils.LocationOnRedIcon
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.BitmapPool
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.RectL
import org.osmdroid.views.MapView
import org.osmdroid.views.MapViewRepository
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import kotlin.math.roundToInt


/**
 * A tile source that mirrors OSM tiles from within mainland China.
 * Uses [osm.open.cn](https://osm.open.cn/) — a community CDN accessible behind the GFW.
 * No API key required.
 */
val CHINA_OSM_TILE_SOURCE: ITileSource = XYTileSource(
    "ChinaOSM", 0, 19, 256, ".png",
    arrayOf("https://osm.open.cn/"),
    "© OpenStreetMap contributors"
)

/**
 * Creates a tile source backed by [天地图](http://lbs.tianditu.gov.cn/),
 * China's official national geographic information service.
 * Requires a free API key (register at the link above).
 *
 * Uses the vector base map in Web Mercator projection (EPSG:3857, `vec_w` layer).
 */
fun tiandituTileSource(apiKey: String): ITileSource = object : OnlineTileSourceBase(
    "Tianditu", 0, 19, 256, "",
    arrayOf(
        "https://t0.tianditu.gov.cn/vec_w/wmts?" +
            "SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
            "&LAYER=vec&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&tk=$apiKey"
    ),
    "© 天地图"
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl +
            "&TILECOL=" + MapTileIndex.getX(pMapTileIndex) +
            "&TILEROW=" + MapTileIndex.getY(pMapTileIndex) +
            "&TILEMATRIX=" + MapTileIndex.getZoom(pMapTileIndex)
}

/**
 * A dialog wrapping an osmdroid [MapView] (OpenStreetMap, FOSS).
 * The user taps anywhere on the map to drop a pin; tapping Confirm fires
 * [onLocationSelected] with the chosen [GeoPoint].
 *
 * @param initialLocation    Camera target on open (defaults to Shanghai).
 * @param initialZoom        Initial zoom level (0–19, defaults to 12).
 * @param tileSource         Tile source. Default is [CHINA_OSM_TILE_SOURCE],
 *                           an OSM mirror hosted in mainland China (no API key).
 *                           Pass [tiandituTileSource] with a Tianditu API key for
 *                           an official China-government-backed tile source.
 * @param onLocationSelected Called with the confirmed [GeoPoint]; dismiss here.
 * @param onDismiss          Called when the user cancels.
 */
@Composable
fun OsmLocationPicker(
    initialLocation: GeoPoint = GeoPoint(31.224361, 121.469170), // Shanghai
    initialZoom: Double = 5.0,
    tileSource: ITileSource = TileSourceFactory.MAPNIK,
    onLocationSelected: (GeoPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    // osmdroid requires a user-agent to be set before first MapView creation
    SideEffect {
        Configuration.getInstance().userAgentValue = PackageNames.MODULE
    }

    var pickedPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var showManualDialog by remember { mutableStateOf(false) }

    // Stable references for the map and custom marker
    val markerRef = remember { mutableStateOf<CustomOsmMarker?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(fraction = 0.9f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ────────────────────────────────────────────────
            OsmPickerHeader(
                pickedPoint = pickedPoint,
                onDismiss = onDismiss,
                onConfirm = { pickedPoint?.let(onLocationSelected) },
            )

            HorizontalDivider()

            // ── Map ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            mapViewRef = this
                            setTileSource(tileSource)
                            setMultiTouchControls(true)
                            controller.setZoom(initialZoom)
                            controller.setCenter(initialLocation)
                            minZoomLevel = 3.0
                            maxZoomLevel = 19.0

                            val eventsOverlay = MapEventsOverlay(
                                object : MapEventsReceiver {
                                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                        pickedPoint = p

                                        val existing = markerRef.value
                                        if (existing != null) {
                                            existing.position = p
                                        } else {
                                            val m = CustomOsmMarker(this@apply).apply {
                                                position = p
                                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                            }
                                            overlays.add(m)
                                            markerRef.value = m
                                        }
                                        invalidate()
                                        return true
                                    }

                                    override fun longPressHelper(p: GeoPoint) = false
                                }
                            )
                            overlays.add(0, eventsOverlay)
                        }
                    },
                    update = { mapView ->
                        if (mapView.tileProvider.tileSource != tileSource) {
                            mapView.setTileSource(tileSource)
                        }
                    },
                )

                // Hint overlay – hidden once a point is chosen
                if (pickedPoint == null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    ) {
                        Text(
                            text = "点击地图或下方坐标以选择虚拟位置",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // ── Coordinate readout (Always visible) ────────────────────
            HorizontalDivider()
            OsmCoordinateReadout(
                point = pickedPoint,
                onClick = { showManualDialog = true }
            )
        }
    }

    // ── Manual Input Dialog ───────────────────────────────────────────
    if (showManualDialog) {
        ManualCoordinateDialog(
            initialPoint = pickedPoint,
            onDismiss = { showManualDialog = false },
            onConfirm = { lat, lng ->
                showManualDialog = false
                val newPoint = GeoPoint(lat, lng)
                pickedPoint = newPoint

                // Update Map View and Marker programmatically
                mapViewRef?.let { mv ->
                    val existing = markerRef.value
                    if (existing != null) {
                        existing.position = newPoint
                    } else {
                        val m = CustomOsmMarker(mv).apply {
                            position = newPoint
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        mv.overlays.add(m)
                        markerRef.value = m
                    }
                    mv.controller.animateTo(newPoint)
                    mv.invalidate()
                }
            }
        )
    }
}

// ── Internal composables ──────────────────────────────────────────────────────

@Composable
private fun OsmPickerHeader(
    pickedPoint: GeoPoint?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(MaterialSymbols.Outlined.Close, contentDescription = "Cancel")
        }
        Text(
            text = "地图选点",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onConfirm,
            enabled = pickedPoint != null,
        ) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("确定")
        }
    }
}

@Composable
private fun OsmCoordinateReadout(
    point: GeoPoint?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        OsmCoordinateChip(
            label = "纬度",
            value = point?.let { "%.6f".format(it.latitude) } ?: "N/A",
            onClick = onClick
        )
        OsmCoordinateChip(
            label = "经度",
            value = point?.let { "%.6f".format(it.longitude) } ?: "N/A",
            onClick = onClick
        )
    }
}

@Composable
private fun OsmCoordinateChip(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ManualCoordinateDialog(
    initialPoint: GeoPoint?,
    onConfirm: (Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var latText by remember { mutableStateOf(initialPoint?.latitude?.toString() ?: "") }
    var lngText by remember { mutableStateOf(initialPoint?.longitude?.toString() ?: "") }

    var latError by remember { mutableStateOf(false) }
    var lngError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动输入坐标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = {
                        latText = it
                        val lat = it.toDoubleOrNull()
                        latError = lat == null || lat !in -90.0..90.0
                    },
                    label = { Text("纬度 (-90 ~ 90)") },
                    isError = latError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lngText,
                    onValueChange = {
                        lngText = it
                        val lng = it.toDoubleOrNull()
                        lngError = lng == null || lng !in -180.0..180.0
                    },
                    label = { Text("经度 (-180 ~ 180)") },
                    isError = lngError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lat = latText.toDoubleOrNull()
                    val lng = lngText.toDoubleOrNull()

                    val isLatValid = lat != null && lat in -90.0..90.0
                    val isLngValid = lng != null && lng in -180.0..180.0

                    latError = !isLatValid
                    lngError = !isLngValid

                    if (isLatValid && isLngValid) {
                        onConfirm(lat, lng)
                    }
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ── CustomMarker Implementation ────────────────────────────────────────────────
private class CustomOsmMarker(mapView: MapView) : Overlay() {
    private var mIcon: Drawable? = null
    private var mPosition: GeoPoint?
    private var mBearing: Float = 0.0f
    private var mAnchorU: Float
    private var mAnchorV: Float
    private var mIWAnchorU: Float
    private var mIWAnchorV: Float
    var alpha: Float = 1.0f
    private var mDraggable: Boolean
    private var mIsDragged: Boolean
    var isFlat: Boolean
    private var mOnMarkerClickListener: OnMarkerClickListener?
    private var mOnMarkerDragListener: OnMarkerDragListener?

    var image: Drawable? = null
    private var mPanToView: Boolean
    private var mDragOffsetY: Float

    private var mPositionPixels: Point
    private var mResources: Resources?

    private var mMapViewRepository: MapViewRepository?

    var isDisplayed: Boolean = false
        private set
    private val mRect = Rect()
    private val mOrientedMarkerRect = Rect()

    init {
        mMapViewRepository = mapView.repository
        mResources = mapView.context.resources
        mPosition = GeoPoint(0.0, 0.0)
        mAnchorU = ANCHOR_CENTER
        mAnchorV = ANCHOR_CENTER
        mIWAnchorU = ANCHOR_CENTER
        mIWAnchorV = ANCHOR_TOP
        mDraggable = false
        mIsDragged = false
        mPositionPixels = Point()
        mPanToView = true
        mDragOffsetY = 0.0f
        isFlat = false
        mOnMarkerClickListener = null
        mOnMarkerDragListener = null
        setDefaultIcon()
    }

    fun setDefaultIcon() {
        mIcon = LocationOnRedIcon
        setAnchor(ANCHOR_CENTER, ANCHOR_BOTTOM)
    }

    var position: GeoPoint?
        get() = mPosition
        set(position) {
            mPosition = position!!.clone()
            mBounds = BoundingBox(
                position.latitude,
                position.longitude,
                position.latitude,
                position.longitude
            )
        }

    fun setAnchor(anchorU: Float, anchorV: Float) {
        mAnchorU = anchorU
        mAnchorV = anchorV
    }

    override fun draw(canvas: Canvas, pj: Projection) {
        if (mIcon == null) return
        if (!isEnabled) return

        pj.toPixels(mPosition, mPositionPixels)

        val rotationOnScreen = if (isFlat) -mBearing else -pj.orientation - mBearing
        drawAt(canvas, mPositionPixels.x, mPositionPixels.y, rotationOnScreen)
    }

    override fun onDetach(mapView: MapView?) {
        BitmapPool.getInstance().asyncRecycle(mIcon)
        mIcon = null
        BitmapPool.getInstance().asyncRecycle(image)
        mOnMarkerClickListener = null
        mOnMarkerDragListener = null
        mResources = null

        mMapViewRepository = null
        super.onDetach(mapView)
    }

    fun hitTest(event: MotionEvent): Boolean {
        return mIcon != null && isDisplayed && mOrientedMarkerRect.contains(
            event.x.toInt(), event.y.toInt()
        )
    }

    override fun onSingleTapConfirmed(event: MotionEvent, mapView: MapView): Boolean {
        val touched = hitTest(event)
        return if (touched) {
            if (mOnMarkerClickListener == null) {
                onMarkerClickDefault(this, mapView)
            } else {
                mOnMarkerClickListener!!.onMarkerClick(this, mapView)
            }
        } else false
    }

    fun moveToEventPosition(event: MotionEvent, mapView: MapView) {
        val offsetY = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_MM,
            mDragOffsetY,
            mapView.context.resources.displayMetrics
        )
        val pj = mapView.getProjection()
        position =
            pj.fromPixels(event.x.toInt(), (event.y - offsetY).toInt()) as GeoPoint?
        mapView.invalidate()
    }

    override fun onLongPress(event: MotionEvent, mapView: MapView): Boolean {
        val touched = hitTest(event)
        if (touched) {
            if (mDraggable) {
                mIsDragged = true
                if (mOnMarkerDragListener != null) mOnMarkerDragListener!!.onMarkerDragStart(this)
                moveToEventPosition(event, mapView)
            }
        }
        return touched
    }

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        if (mDraggable && mIsDragged) {
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    mIsDragged = false
                    if (mOnMarkerDragListener != null) mOnMarkerDragListener!!.onMarkerDragEnd(this)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    moveToEventPosition(event, mapView)
                    if (mOnMarkerDragListener != null) mOnMarkerDragListener!!.onMarkerDrag(this)
                    return true
                }

                else -> return false
            }
        } else return false
    }

    interface OnMarkerClickListener {
        fun onMarkerClick(marker: CustomOsmMarker?, mapView: MapView?): Boolean
    }

    interface OnMarkerDragListener {
        fun onMarkerDrag(marker: CustomOsmMarker?)
        fun onMarkerDragEnd(marker: CustomOsmMarker?)
        fun onMarkerDragStart(marker: CustomOsmMarker?)
    }

    private fun onMarkerClickDefault(marker: CustomOsmMarker, mapView: MapView): Boolean {
        if (marker.mPanToView) mapView.controller.animateTo(marker.position)
        return true
    }

    private fun drawAt(pCanvas: Canvas, pX: Int, pY: Int, pOrientation: Float) {
        val markerWidth = mIcon!!.intrinsicWidth
        val markerHeight = mIcon!!.intrinsicHeight
        val offsetX = pX - (markerWidth * mAnchorU).roundToInt()
        val offsetY = pY - (markerHeight * mAnchorV).roundToInt()
        mRect.set(offsetX, offsetY, offsetX + markerWidth, offsetY + markerHeight)
        RectL.getBounds(mRect, pX, pY, pOrientation.toDouble(), mOrientedMarkerRect)
        isDisplayed = Rect.intersects(mOrientedMarkerRect, pCanvas.getClipBounds())
        if (!isDisplayed) {
            return
        }
        if (alpha == 0f) {
            return
        }
        if (pOrientation != 0f) {
            pCanvas.save()
            pCanvas.rotate(pOrientation, pX.toFloat(), pY.toFloat())
        }
        mIcon!!.alpha = (alpha * 255).toInt()
        mIcon!!.bounds = mRect
        mIcon!!.draw(pCanvas)
        if (pOrientation != 0f) {
            pCanvas.restore()
        }
    }

    companion object {
        const val ANCHOR_CENTER: Float = 0.5f
        const val ANCHOR_TOP: Float = 0.0f
        const val ANCHOR_BOTTOM: Float = 1.0f
    }
}
