package dev.ujhhgtg.wekit.utils

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object TelegramStickerConverter {
    fun tgsToGif(input: Path, output: Path, frameRate: Int): Result<Unit> = runCatching {
        output.parent?.createDirectories()
        tgsToGifNative(input.toString(), output.toString(), frameRate)?.let(::error)
        require(output.isRegularFile() && output.fileSize() > 0L) { "TGS 转换未生成 GIF" }
    }

    suspend fun webmToGif(
        input: Path,
        output: Path,
        removeRoundedCanvasMask: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                output.parent?.createDirectories()
                webmToGifNative(
                        input.toString(),
                        output.toString(),
                        removeRoundedCanvasMask,
                    )
                    ?.let(::error)
                currentCoroutineContext().ensureActive()
                require(output.isRegularFile() && output.fileSize() > 0L) { "视频表情转换未生成 GIF" }
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

    private external fun tgsToGifNative(
        inputPath: String,
        outputPath: String,
        frameRate: Int,
    ): String?

    private external fun webmToGifNative(
        inputPath: String,
        outputPath: String,
        removeRoundedCanvasMask: Boolean,
    ): String?
}
