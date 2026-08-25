package dev.ujhhgtg.wekit.utils

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream

object AndroidAudioDecoder {
    data class DecodedPcm(
        val sampleRate: Int,
        val channelCount: Int,
    )

    fun decodeToPcm16(sourcePath: String, destination: File): DecodedPcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(sourcePath)
            val trackIndex = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(sourceFormat.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(sourceFormat, null, null, 0)
                codec.start()
                return decode(codec, extractor, destination)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decode(
        codec: MediaCodec,
        extractor: MediaExtractor,
        destination: File,
    ): DecodedPcm {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var decodedPcm: DecodedPcm? = null
        var wroteOutput = false

        FileOutputStream(destination).use { output ->
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        decodedPcm = readPcmFormat(
                            codec.outputFormat,
                            decodedPcm.takeIf { wroteOutput },
                        )
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        if (decodedPcm == null) {
                            decodedPcm = readPcmFormat(codec.outputFormat, null)
                        }
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)
                            output.write(bytes)
                            wroteOutput = true
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        return requireNotNull(decodedPcm) { "Audio decoder produced no output format" }
    }

    private fun readPcmFormat(format: MediaFormat, previous: DecodedPcm?): DecodedPcm {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        require(encoding == AudioFormat.ENCODING_PCM_16BIT) {
            "Unsupported decoder PCM encoding: $encoding"
        }
        val current = DecodedPcm(
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        )
        require(previous == null || previous == current) {
            "Audio output format changed from $previous to $current"
        }
        return current
    }

    private const val CODEC_TIMEOUT_US = 10_000L
}
