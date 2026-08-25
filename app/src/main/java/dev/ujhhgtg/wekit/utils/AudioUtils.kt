package dev.ujhhgtg.wekit.utils

import java.io.File

object AudioUtils {

    fun anyToSilk(sourcePath: String, silkPath: String): Boolean {
        if (nativeAnyToSilk(sourcePath, silkPath)) return true

        val silkFile = File(silkPath).absoluteFile
        var pcmFile: File? = null
        return try {
            val temporaryPcm = File.createTempFile("wekit-audio-", ".pcm", silkFile.parentFile)
            pcmFile = temporaryPcm
            val decoded = AndroidAudioDecoder.decodeToPcm16(sourcePath, temporaryPcm)
            val converted = pcmToSilk(
                temporaryPcm.absolutePath,
                silkPath,
                decoded.sampleRate,
                decoded.channelCount,
            )
            if (!converted) silkFile.delete()
            converted
        } catch (error: Exception) {
            WeLogger.e("AudioUtils", "Android audio decoder fallback failed", error)
            silkFile.delete()
            false
        } finally {
            pcmFile?.delete()
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
