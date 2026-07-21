package dev.ujhhgtg.wekit.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

object TelegramStickerConverter {
    fun tgsToGif(input: Path, output: Path): Result<Unit> = runCatching {
        output.parent?.createDirectories()
        tgsToGifNative(input.toString(), output.toString())?.let(::error)
        require(output.isRegularFile() && output.fileSize() > 0L) { "TGS 转换未生成 GIF" }
    }

    suspend fun webmToGif(input: Path, output: Path): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            output.parent?.createDirectories()
            webmToGifNative(input.toString(), output.toString())?.let(::error)
            currentCoroutineContext().ensureActive()
            require(output.isRegularFile() && output.fileSize() > 0L) { "视频表情转换未生成 GIF" }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private external fun tgsToGifNative(inputPath: String, outputPath: String): String?

    private external fun webmToGifNative(inputPath: String, outputPath: String): String?
}
