package dev.ujhhgtg.wekit.utils

import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.fs.tryDelete
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile

object AudioUtils {
    fun anyToSilk(sourcePath: String, silkPath: String): Boolean {
        if (nativeAnyToSilk(sourcePath, silkPath)) return true

        val silkFile = silkPath.asPath.toAbsolutePath()
        var pcmFile: Path? = null
        return try {
            val temporaryPcm = createTempFile(silkFile.parent, "wekit-audio-", ".pcm")
            pcmFile = temporaryPcm
            val decoded = AndroidAudioDecoder.decodeToPcm16(sourcePath, temporaryPcm)
            val converted =
                pcmToSilk(
                    temporaryPcm.absolutePathString(),
                    silkPath,
                    decoded.sampleRate,
                    decoded.channelCount,
                )
            if (!converted) silkFile.tryDelete()
            converted
        } catch (error: Exception) {
            WeLogger.e("AudioUtils", "Android audio decoder fallback failed", error)
            silkFile.tryDelete()
            false
        } finally {
            pcmFile?.tryDelete()
        }
    }

    private external fun nativeAnyToSilk(sourcePath: String, silkPath: String): Boolean

    private external fun pcmToSilk(
        pcmPath: String,
        silkPath: String,
        sampleRate: Int,
        channelCount: Int,
    ): Boolean

    external fun silkToPcm(silkPath: String, pcmPath: String): Boolean

    external fun pcmToMp3(silkPath: String, pcmPath: String): Boolean

    external fun getDurationMs(path: String): Long
}
