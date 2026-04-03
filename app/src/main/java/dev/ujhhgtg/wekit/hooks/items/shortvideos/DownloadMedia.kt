package dev.ujhhgtg.wekit.hooks.items.shortvideos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.hooks.api.ui.WeShortVideosShareMenuApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.KnownPaths
import dev.ujhhgtg.wekit.utils.ModuleRes
import dev.ujhhgtg.wekit.utils.showToast
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.copyToClipboard
import dev.ujhhgtg.wekit.utils.formatBytesSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.net.URL
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

@HookItem(
    path = "视频号/下载媒体",
    desc = "向视频分享菜单中添加「复制链接」与「下载」菜单项"
)
object DownloadMedia : SwitchHookItem(),
    WeShortVideosShareMenuApi.IMenuItemsProvider {

    private val TAG = nameOf(DownloadMedia)

    override fun onEnable() {
        WeShortVideosShareMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeShortVideosShareMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeShortVideosShareMenuApi.MenuItem> {
        return listOf(
            WeShortVideosShareMenuApi.MenuItem(
                777004,
                "复制链接",
                { ModuleRes.getDrawable(R.drawable.link_24px)!! }
            )
            { _, mediaType, mediaList ->
                if (mediaType == 2) {
                    val imageUrls = mediaList.map { json ->
                        json.getString("url") + json.getString("url_token")
                    }

                    copyToClipboard(imageUrls.joinToString("\n"))
                    showToast("已复制")
                    return@MenuItem
                }

                if (mediaType == 4) {
                    val json = mediaList[0]

                    val clipItems = mutableListOf<Pair<String, String>>()

                    val duration = json.getInt("videoDuration")
                    val size = json.getInt("fileSize")
                    val displayDuration = "%02d:%02d:%02d".format(
                        Locale.CHINA,
                        duration / 3600, (duration % 3600) / 60, duration % 60
                    )
                    val displaySize = formatBytesSize(size.toLong())
                    clipItems += "时长" to displayDuration
                    clipItems += "大小" to displaySize

                    val cdnInfo = json.optJSONObject("media_cdn_info")
                    if (cdnInfo == null || !cdnInfo.has("pcdn_url")) {
                        val url = json.getString("url")
                        val urlToken = json.getString("url_token")
                        val decodeKey = json.getString("decodeKey")
                        clipItems += "密链" to (url + urlToken)
                        clipItems += "密钥" to decodeKey
                    } else {
                        clipItems += "链接" to json.getString("pcdn_url")
                    }

                    copyToClipboard(clipItems.joinToString("\n") { pair -> "${pair.first}: ${pair.second}" })
                    showToast("已复制")

                    return@MenuItem
                }

                showToast("未知的媒体类型, 无法复制链接")
            },
            WeShortVideosShareMenuApi.MenuItem(
                777007,
                "下载",
                { ModuleRes.getDrawable(R.drawable.download_24px)!! }
            )
            { _, mediaType, mediaList ->
                if (mediaType == 2) {
                    val imageUrls = mediaList.map { json ->
                        json.getString("url") + json.getString("url_token")
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        downloadImages(imageUrls)
                    }

                    return@MenuItem
                }

                if (mediaType == 4) {
                    val json = mediaList[0]

                    val cdnInfo = json.optJSONObject("media_cdn_info")

                    if (cdnInfo == null || !cdnInfo.has("pcdn_url")) {
                        val url = json.getString("url")
                        val urlToken = json.getString("url_token")
                        val decodeKey = json.getString("decodeKey")

                        CoroutineScope(Dispatchers.IO).launch {
                            downloadAndDecryptVideo(decodeKey, url, urlToken)
                        }
                    } else {
                        val pcdnUrl = cdnInfo.getString("pcdn_url")

                        CoroutineScope(Dispatchers.IO).launch {
                            downloadPcdnVideo(pcdnUrl)
                        }
                    }

                    return@MenuItem
                }

                showToast("未知的媒体类型, 无法下载")
            }
        )
    }

    private suspend fun downloadImages(imageUrls: List<String>) = withContext(Dispatchers.IO) {
        imageUrls.forEachIndexed { index, fullUrl ->
            val fileName = "image_${System.currentTimeMillis()}.png"

            withContext(Dispatchers.Main) {
                showToast("开始下载第 ${index + 1} 张图片")
            }

            runCatching {
                downloadFile(fullUrl, KnownPaths.downloads / fileName)
            }.onFailure {
                WeLogger.e(TAG, "failed to download ${index + 1}th image", it)
                withContext(Dispatchers.Main) {
                    showToast("第 ${index + 1} 张图片下载成功")
                }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    showToast("已将图片下载到 /sdcard/Download/WeKit/$fileName")
                }
            }
        }
    }

    private suspend fun downloadAndDecryptVideo(
        decodeKey: String,
        url: String,
        urlToken: String
    ) = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            showToast("开始下载并解密视频")
        }

        val baseDir = KnownPaths.downloads
        val fileName = "video_${System.currentTimeMillis()}.mp4"
        val tempFilePath = baseDir / "${fileName}.tmp"
        val finalFilePath = baseDir / fileName

        val fullUrl = url + urlToken

        runCatching {
            downloadFile(fullUrl, tempFilePath)

            withContext(Dispatchers.Main) {
                showToast("开始解密视频")
            }
            val key = BigInteger(decodeKey)
            decryptFile(tempFilePath, finalFilePath, key)

            tempFilePath.deleteIfExists()
        }.onFailure {
            WeLogger.e(TAG, "failed to download video", it)
            withContext(Dispatchers.Main) {
                showToast("视频下载失败")
            }
        }.onSuccess {
            withContext(Dispatchers.Main) {
                showToast("已将视频下载到 /sdcard/Download/WeKit/$fileName")
            }
        }
    }

    private suspend fun downloadPcdnVideo(
        url: String
    ) = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            showToast("开始下载视频")
        }

        val fileName = "video_${System.currentTimeMillis()}.mp4"
        runCatching {
            downloadFile(url, KnownPaths.downloads / fileName)
        }.onFailure {
            WeLogger.e(TAG, "failed to download video", it)
            withContext(Dispatchers.Main) {
                showToast("视频下载失败")
            }
        }.onSuccess {
            withContext(Dispatchers.Main) {
                showToast("已将视频下载到 /sdcard/Download/WeKit/$fileName")
            }
        }
    }

    private suspend fun downloadFile(url: String, targetPath: Path) = withContext(Dispatchers.IO) {
        URL(url).openStream().use { input ->
            targetPath.outputStream().use { input.copyTo(it) }
        }
    }

    private fun decryptFile(originalPath: Path, newPath: Path, bigIntDecodeKey: BigInteger) {
        originalPath.inputStream().use { fileInputStream ->
            newPath.outputStream().use { fileOutputStream ->
                val buffer = ByteArray(32 * 1024 * 1024) // 32 MB buffer as per decompiled code

                while (true) {
                    val bytesRead = fileInputStream.read(buffer)
                    if (bytesRead == -1) {
                        break
                    }

                    // Decrypt the buffer in-place
                    decryptBuffer(buffer, bigIntDecodeKey)

                    // Write only the bytes that were read
                    fileOutputStream.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    /**
     * Core decryption logic.
     */
    private fun decryptBuffer(buffer: ByteArray, key: BigInteger) {
        // Constraint from decompiled code: if buffer is smaller than 128KB, do nothing.
        if (buffer.isEmpty() || buffer.size < 128 * 1024) {
            return
        }

        val cryptoState = CryptoState(key)
        val limit = 128 * 1024 // 128 KB limit per chunk

        // Process the first 128KB of the buffer in 8-byte blocks
        for (i in 0 until limit step 8) {
            // Get current state index
            val f = cryptoState.f
            val keyBlock = cryptoState.c[f]

            // Update state index
            if (f == 0) {
                cryptoState.updateState() // Corresponds to cyh.h()
                cryptoState.f = 255
            } else {
                cryptoState.f = f - 1
            }

            // Convert BigInteger key block to 8 bytes (Big Endian)
            val keyBytes = ByteArray(8)
            for (j in 0 until 8) {
                // Shift right by j*8 bits, mask 0xFF, take byte value
                val shifted = keyBlock.shiftRight(j * 8)
                val masked = shifted.and(BigInteger.valueOf(255))
                // Place LSB at index 7, MSB at index 0
                keyBytes[7 - j] = masked.toByteArray().lastOrNull() ?: 0
            }

            // XOR the buffer segment with the key bytes
            for (j in 0 until 8) {
                val bufferIndex = i + j
                if (bufferIndex >= limit) {
                    return
                }
                buffer[bufferIndex] = (buffer[bufferIndex].toInt() xor keyBytes[j].toInt()).toByte()
            }
        }
    }

    /**
     * Handles the cryptographic state and key scheduling.
     */
    private class CryptoState(bigInteger: BigInteger) {
        // Mask: 0xffffffffffffffff
        private val mask = BigInteger("ffffffffffffffff", 16)

        // State arrays
        // Initialize b
        val b: Array<BigInteger> = Array(8) { BigInteger("9e3779b97f4a7c13", 16) } // Size 8

        // Initialize c, d, e with ZERO
        val c: Array<BigInteger> = Array(256) { BigInteger.ZERO } // Size 256
        val d: Array<BigInteger> = Array(256) { BigInteger.ZERO } // Size 256
        val e: Array<BigInteger> = Array(256) { BigInteger.ZERO } // Size 256

        // Index counter
        var f: Int = 255

        init {

            // Setup logic
            c[0] = bigInteger

            // First mixing loop (4 iterations)
            repeat(4) {
                mix(b)
            }

            // Fill d array based on c
            // Range 0 to 256 step 8
            var i6 = 0
            while (i6 < 256) {
                for (j in 0 until 8) {
                    b[j] = b[j].add(c[i6 + j]).and(mask)
                }
                mix(b)
                for (j in 0 until 8) {
                    d[i6 + j] = b[j]
                }
                i6 += 8
            }

            // Second mixing loop to fill d/e/c further
            var i11 = 0
            while (i11 < 256) {
                for (j in 0 until 8) {
                    b[j] = b[j].add(d[i11 + j]).and(mask)
                }
                mix(b)
                for (j in 0 until 8) {
                    d[i11 + j] = b[j]
                }
                i11 += 8
            }

            updateState()
        }

        /**
         * Mixes the state array 'b'.
         */
        private fun mix(state: Array<BigInteger>) {
            // 0 -= 4
            state[0] = state[0].subtract(state[4]).and(mask)

            // 5 ^= (7 >>> 9)
            state[5] = state[5].xor(state[7].shiftRight(9)).and(mask)
            // 7 += 0
            state[7] = state[7].add(state[0]).and(mask)

            // 1 -= 5
            state[1] = state[1].subtract(state[5]).and(mask)
            // 6 ^= (0 <<< 9)
            state[6] = state[6].xor(state[0].shiftLeft(9)).and(mask)
            // 0 += 1
            state[0] = state[0].add(state[1]).and(mask)

            // 2 -= 6
            state[2] = state[2].subtract(state[6]).and(mask)
            // 7 ^= (1 >>> 23)
            state[7] = state[7].xor(state[1].shiftRight(23)).and(mask)
            // 1 += 2
            state[1] = state[1].add(state[2]).and(mask)

            // 3 -= 7
            state[3] = state[3].subtract(state[7]).and(mask)
            // 0 ^= (2 <<< 15)
            state[0] = state[0].xor(state[2].shiftLeft(15)).and(mask)
            // 2 += 3
            state[2] = state[2].add(state[3]).and(mask)

            // 4 -= 0
            state[4] = state[4].subtract(state[0]).and(mask)
            // 1 ^= (3 >>> 14)
            state[1] = state[1].xor(state[3].shiftRight(14)).and(mask)
            // 3 += 4
            state[3] = state[3].add(state[4]).and(mask)

            // 5 -= 1
            state[5] = state[5].subtract(state[1]).and(mask)
            // 2 ^= (4 <<< 20)
            state[2] = state[2].xor(state[4].shiftLeft(20)).and(mask)
            // 4 += 5
            state[4] = state[4].add(state[5]).and(mask)

            // 6 -= 2
            state[6] = state[6].subtract(state[2]).and(mask)
            // 3 ^= (5 >>> 17)
            state[3] = state[3].xor(state[5].shiftRight(17)).and(mask)
            // 5 += 6
            state[5] = state[5].add(state[6]).and(mask)

            // 7 -= 3
            state[7] = state[7].subtract(state[3]).and(mask)
            // 4 ^= (6 <<< 14)
            state[4] = state[4].xor(state[6].shiftLeft(14)).and(mask)
            // 6 += 7
            state[6] = state[6].add(state[7]).and(mask)
        }

        /**
         * Updates the internal keystream state.
         */
        fun updateState() {
            // e[2]++
            e[2] = e[2].add(BigInteger.ONE).and(mask)
            // e[1] += e[2]
            e[1] = e[1].add(e[2]).and(mask)

            for (i in 0 until 256) {
                val i2 = i % 4
                when (i2) {
                    0 -> {
                        // e[0] = ~(e[0] ^ (e[0] <<< 21))
                        e[0] = e[0].xor(e[0].shiftLeft(21)).not().and(mask)
                    }

                    1 -> {
                        // e[0] ^= (e[0] >>> 5)
                        e[0] = e[0].xor(e[0].shiftRight(5))
                    }

                    2 -> {
                        // e[0] ^= (e[0] <<< 12)
                        e[0] = e[0].xor(e[0].shiftLeft(12))
                    }

                    3 -> {
                        // e[0] ^= (e[0] >>> 33)
                        e[0] = e[0].xor(e[0].shiftRight(33))
                    }
                }

                // e[0] += d[(i + 128) % 256]
                e[0] = e[0].add(d[(i + 128) % 256]).and(mask)

                // Complex update for d[i]
                val di = d[i]
                val shiftRight3 = di.shiftRight(3)
                val index1 = shiftRight3.mod(BigInteger.valueOf(256)).toInt()

                val sum1 = d[index1].add(e[0])
                val s = sum1.add(e[1]).and(mask)

                d[i] = s

                val shiftRight4 = s.shiftRight(11)
                val index2 = shiftRight4.mod(BigInteger.valueOf(256)).toInt()

                e[1] = d[index2].add(di).and(mask)
                c[i] = e[1]
            }
        }
    }
}
