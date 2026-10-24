package dev.ujhhgtg.wekit.features.items.beautify.home

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.data.KvStore.prefOption
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HomeWallpaperSource(val key: String, @StringRes val titleRes: Int) {
    // https://www.pixiv.net/artworks/137041477
    BUILTIN_FIRST("builtin_1", R.string.beautify_home_background_first),

    // source not found; fetched from YumeBox
    BUILTIN_SECOND("builtin_2", R.string.beautify_home_background_second),
    CUSTOM("custom", R.string.beautify_home_background_custom),
    SYSTEM("system", R.string.beautify_home_background_system),
}

enum class HomeWallpaperStyle(val key: String, @StringRes val titleRes: Int) {
    FULL_BLEED("full_bleed", R.string.beautify_home_background_style_full_bleed),
    FRAMED("framed", R.string.beautify_home_background_style_framed),
}

object HomeWallpaper {
    private var savedSource by prefOption("beautify_home_background_source", "builtin_2")
    private var savedStyle by prefOption("beautify_home_background_style", "full_bleed")
    var source by mutableStateOf(HomeWallpaperSource.BUILTIN_SECOND)
        private set

    var revision by mutableIntStateOf(0)
        private set

    var style by mutableStateOf(HomeWallpaperStyle.FULL_BLEED)
        private set

    private val imageDirectory by lazy {
        (KnownPaths.moduleAssets / "beautify_home").createDirsSafe()
    }
    private val customImage
        get() = imageDirectory / "background.image"

    fun initialize() {
        source =
            HomeWallpaperSource.entries.firstOrNull { it.key == savedSource }
                ?: HomeWallpaperSource.BUILTIN_SECOND
        style =
            HomeWallpaperStyle.entries.firstOrNull { it.key == savedStyle }
                ?: HomeWallpaperStyle.FULL_BLEED
    }

    private fun select(selected: HomeWallpaperSource) {
        savedSource = selected.key
        source = selected
        revision++
    }

    private fun selectStyle(selected: HomeWallpaperStyle) {
        savedStyle = selected.key
        style = selected
    }

    fun refreshSystemWallpaper() {
        if (source == HomeWallpaperSource.SYSTEM) revision++
    }

    /** Called on the IO dispatcher; ImageDecoder also applies the image's EXIF orientation. */
    fun load(resources: Resources, selected: HomeWallpaperSource): ImageBitmap {
        val imageSource =
            when (selected) {
                HomeWallpaperSource.BUILTIN_FIRST -> {
                    ImageDecoder.createSource(resources, R.drawable.beautify_home_background_1)
                }

                HomeWallpaperSource.BUILTIN_SECOND -> {
                    ImageDecoder.createSource(resources, R.drawable.beautify_home_background_2)
                }

                HomeWallpaperSource.CUSTOM -> {
                    ImageDecoder.createSource(customImage.toFile())
                }

                HomeWallpaperSource.SYSTEM -> {
                    val file = KnownPaths.moduleCache / "beautify-home-system-wallpaper.image"
                    val descriptor =
                        WallpaperManager.getInstance(HostInfo.application)
                            .getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
                            ?: error("The system wallpaper has no readable image")
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    ImageDecoder.createSource(file.toFile())
                }
            }
        return decode(resources, imageSource).asImageBitmap()
    }

    private fun decode(resources: Resources, imageSource: ImageDecoder.Source): Bitmap {
        val metrics = resources.displayMetrics
        val maximumSize = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        return ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longestSide = maxOf(info.size.width, info.size.height)
            if (longestSide > maximumSize) {
                decoder.setTargetSize(
                    (info.size.width.toLong() * maximumSize / longestSide).toInt().coerceAtLeast(1),
                    (info.size.height.toLong() * maximumSize / longestSide)
                        .toInt()
                        .coerceAtLeast(1),
                )
            }
        }
    }

    private suspend fun importImage(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            val partial = imageDirectory / ".background.part"
            try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                decode(context.resources, ImageDecoder.createSource(partial.toFile())).recycle()
                partial.moveTo(customImage, overwrite = true)
            } finally {
                partial.deleteIfExists()
            }
        }
        select(HomeWallpaperSource.CUSTOM)
    }

    fun showPicker(activity: Activity) {
        showComposeDialog(activity) {
            val scope = rememberCoroutineScope()
            val localizedContext = LocalWeKitLocalizedContext.current
            var importing by remember { mutableStateOf(false) }
            val pickImage = {
                TransparentActivity.launch(activity) {
                    val launcher =
                        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri
                            ->
                            finish()
                            if (uri != null) {
                                scope.launch {
                                    importing = true
                                    try {
                                        importImage(activity, uri)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        WeLogger.e(
                                            "BeautifyHome",
                                            "Unable to import home background",
                                            error,
                                        )
                                        showToast(
                                            localizedContext.getString(
                                                R.string.beautify_home_background_import_failed,
                                            ),
                                        )
                                    } finally {
                                        importing = false
                                    }
                                }
                            }
                        }
                    launcher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            }
            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_home_background)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SegmentedColumn(
                            title = stringResource(R.string.beautify_home_background_images),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            HomeWallpaperSource.entries.forEach { option ->
                                item {
                                    if (option == HomeWallpaperSource.CUSTOM) {
                                        RadioButtonWidget(
                                            title = stringResource(option.titleRes),
                                            description =
                                                stringResource(
                                                    if (importing) {
                                                        R.string.beautify_home_background_importing
                                                    } else {
                                                        R.string
                                                            .beautify_home_background_custom_summary
                                                    },
                                                ),
                                            selected = source == option,
                                            enabled = !importing,
                                            trailingDivider = true,
                                            onClick = pickImage,
                                            onSelect = {
                                                if (customImage.toFile().isFile) {
                                                    select(option)
                                                } else {
                                                    pickImage()
                                                }
                                            },
                                        )
                                    } else {
                                        RadioButtonWidget(
                                            title = stringResource(option.titleRes),
                                            selected = source == option,
                                            enabled = !importing,
                                            onSelect = { select(option) },
                                        )
                                    }
                                }
                            }
                        }
                        SegmentedColumn(
                            title = stringResource(R.string.beautify_home_background_styles),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            HomeWallpaperStyle.entries.forEach { option ->
                                item {
                                    RadioButtonWidget(
                                        title = stringResource(option.titleRes),
                                        selected = style == option,
                                        onSelect = { selectStyle(option) },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
