package dev.ujhhgtg.wekit.features.items.beautify

import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Movie
import com.composables.icons.materialsymbols.outlined.Settings
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.GlobalImageLoader
import dev.ujhhgtg.wekit.ui.utils.InjectedUiTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URL

@Feature(name = "主屏幕媒体侧栏", categories = ["美化"], description = "在微信主界面左缘打开图片、GIF和9:16短视频侧栏")
object MainScreenMediaDrawer : SwitchFeature() {
    private var imageUris by prefOption("main_media_image_uris", emptySet<String>())
    private var videoUris by prefOption("main_media_video_uris", emptySet<String>())
    private var apiUrls by prefOption("main_media_api_urls", DEFAULT_API)
    private var intervalSeconds by prefOption("main_media_interval_seconds", 6)
    private var muted by prefOption("main_media_muted", true)
    private var apiFirst by prefOption("main_media_api_first", false)

    private const val DEFAULT_API = "https://api.yujn.cn/api/zzxjj.php?type=json"

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject!!.reflekt().firstField { type = "com.tencent.mm.ui.MMFragmentActivity" }.get() as Activity
            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
            val host = activity.rootView
            if (host.findViewWithTag<ComposeView>(TAG) != null) return@hookAfter
            host.addView(ComposeView(activity).apply {
                tag = TAG
                setLifecycleOwner(lifecycleOwner)
                setContent { InjectedUiTheme { MediaDrawerOverlay(activity) } }
            }, ViewGroup.LayoutParams(-1, -1))
        }
    }

    @Composable
    private fun MediaDrawerOverlay(context: Activity) {
        var open by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures { _, drag -> if (drag > 28f) open = true }
        }) {
            Box(Modifier.fillMaxHeight().width(18.dp).align(Alignment.CenterStart).clickable { open = true })
            AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)).clickable { open = false })
            }
            AnimatedVisibility(
                visible = open,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Surface(
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(.78f).clickable(enabled = false) {},
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    if (settings) MediaSettings(context) { settings = false } else MediaContent(context) { settings = true }
                }
            }
        }
    }

    @Composable
    private fun MediaContent(context: Activity, onSettings: () -> Unit) {
        val scope = rememberCoroutineScope()
        val images = imageUris.toList()
        var imageIndex by remember { mutableIntStateOf(0) }
        var videoUrl by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        val loadVideo = {
            if (!loading) scope.launch(Dispatchers.IO) {
                loading = true
                val local = videoUris.toList()
                val selected = if (apiFirst || local.isEmpty()) fetchApiUrl() else local.randomOrNull()
                videoUrl = selected
                loading = false
            }
        }
        LaunchedEffect(Unit) { if (images.isEmpty()) loadVideo() }
        LaunchedEffect(imageIndex, images) {
            if (images.isNotEmpty()) {
                kotlinx.coroutines.delay(intervalSeconds.coerceIn(2, 60) * 1000L)
                imageIndex = (imageIndex + 1) % images.size
            }
        }
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("媒体", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(MaterialSymbols.Outlined.Settings, "设置") }
            }
            if (images.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(Uri.parse(images[imageIndex])).build(),
                    imageLoader = GlobalImageLoader,
                    contentDescription = "图片幻灯片",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black),
                )
            } else {
                Text("暂无图片或GIF", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("短视频", style = MaterialTheme.typography.titleSmall)
            if (videoUrl != null) {
                AndroidView(
                    factory = { VideoView(it) },
                    update = { view ->
                        if (view.tag != videoUrl) {
                            view.tag = videoUrl
                            view.setVideoURI(Uri.parse(videoUrl))
                            view.setOnPreparedListener { player -> player.isLooping = true; player.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f); view.start() }
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color.Black).clickable { loadVideo() },
                )
            } else Text(if (loading) "正在加载视频..." else "暂无视频地址", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = loadVideo, modifier = Modifier.fillMaxWidth()) { Icon(MaterialSymbols.Outlined.Movie, null); Spacer(Modifier.width(6.dp)); Text("下一个视频") }
        }
    }

    @Composable
    private fun MediaSettings(context: Activity, onClose: () -> Unit) {
        var apiText by remember { mutableStateOf(apiUrls) }
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("媒体设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(MaterialSymbols.Outlined.Close, "关闭设置") }
            }
            Button(onClick = { pickImages(context) }, modifier = Modifier.fillMaxWidth()) { Icon(MaterialSymbols.Outlined.Movie, null); Spacer(Modifier.width(6.dp)); Text("选择图片或GIF") }
            Button(onClick = { pickVideos(context) }, modifier = Modifier.fillMaxWidth()) { Icon(MaterialSymbols.Outlined.Movie, null); Spacer(Modifier.width(6.dp)); Text("选择本地MP4") }
            Text("图片间隔: ${intervalSeconds}s")
            Slider(value = intervalSeconds.toFloat(), onValueChange = { intervalSeconds = it.toInt().coerceAtLeast(2) }, valueRange = 2f..60f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = muted, onCheckedChange = { muted = it })
                Text("视频静音")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = apiFirst, onCheckedChange = { apiFirst = it })
                Text("优先使用API视频")
            }
            OutlinedTextField(apiText, { apiText = it; apiUrls = it }, label = { Text("视频API地址，每行一条") }, modifier = Modifier.fillMaxWidth())
            Text("已选图片 ${imageUris.size} 个，已选视频 ${videoUris.size} 个", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    private fun pickImages(context: Activity) = pick(context, arrayOf("image/*")) { uris -> imageUris = imageUris + uris }
    private fun pickVideos(context: Activity) = pick(context, arrayOf("video/mp4", "video/*")) { uris -> videoUris = videoUris + uris }

    private fun pick(context: Activity, types: Array<String>, onResult: (Set<String>) -> Unit) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                onResult(uris.map(Uri::toString).toSet())
                finish()
            }
            launcher.launch(types)
        }
    }

    private fun fetchApiUrl(): String? = apiUrls.lineSequence().map(String::trim).filter(String::isNotEmpty).firstNotNullOfOrNull { endpoint ->
        runCatching {
            val body = URL(endpoint).openConnection().apply { connectTimeout = 5000; readTimeout = 8000 }.getInputStream().bufferedReader().use { it.readText() }
            if (body.startsWith("http")) body.trim('"') else JSONObject(body).optString("data").takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    private const val TAG = "wekit_main_media_drawer"
}
