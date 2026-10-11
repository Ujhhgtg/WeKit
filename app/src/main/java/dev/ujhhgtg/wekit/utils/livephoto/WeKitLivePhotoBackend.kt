package dev.ujhhgtg.wekit.utils.livephoto

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The standard JPEG/SDR AVC or HEVC implementation of the six LivePhotoCore methods. */
@SuppressLint("StaticFieldLeak")
object WeKitLivePhotoBackend {
    private const val TAG = "WeKitLivePhotoBackend"
    private const val INVALID_INPUT = 1
    private const val INVALID_OUTPUT = 2
    private const val NOT_INITIALIZED = 3
    private const val READ_WRITE_FAILED = 4
    private const val UNSUPPORTED_MEDIA = 5
    private const val INVALID_MEDIA = 6
    private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024
    private const val OPLUS_GALLERY_USER_COMMENT = "{\"oplustag\":8388608}"
    // Native ColorUtils::ColorTransfer values without public MediaFormat constants:
    // frameworks/av/media/module/foundation/include/media/stagefright/foundation/ColorUtils.h.
    private const val COLOR_TRANSFER_SRGB = 2
    private const val COLOR_TRANSFER_GAMMA22 = 4
    private const val COLOR_TRANSFER_GAMMA28 = 5

    private lateinit var context: Context
    // The host scans the whole gallery in successive batches. Retain several thousand
    // media identities so later batches do not immediately evict the first screen's results.
    private val identificationCache = LruCache<MediaIdentity, Boolean>(4096)
    // File opens and Android media checks wait on independent system services. Keep
    // at most four inspections active; return the complete batch before the host uses it.
    private val identificationWorkers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "WeKit-LivePhoto")
    }

    private data class MediaIdentity(val id: Long, val size: Long, val modifiedSeconds: Long)
    private data class ImageRequest(val id: Long, val identity: MediaIdentity?, val path: String?)
    private data class VideoInfo(val width: Int, val height: Int, val durationMs: Long, val rotation: Int)
    private data class CoverInfo(val width: Int, val height: Int)
    private class MediaFailure(val code: Int, message: String) : Exception(message)
    private class IdentificationTimings {
        var queryNs = 0L
        var identifyNs = 0L
        var openNs = 0L
        var headerNs = 0L
        var containerNs = 0L
        var videoNs = 0L
        var coverNs = 0L
        var candidates = 0
        var directOpens = 0
        fun add(other: IdentificationTimings) {
            identifyNs += other.identifyNs
            openNs += other.openNs
            headerNs += other.headerNs
            containerNs += other.containerNs
            videoNs += other.videoNs
            coverNs += other.coverNs
            candidates += other.candidates
            directOpens += other.directOpens
        }
        override fun toString() = "queryMs=${queryNs / 1_000_000} identifyMs=${identifyNs / 1_000_000} " +
            "openMs=${openNs / 1_000_000} headerMs=${headerNs / 1_000_000} " +
            "containerMs=${containerNs / 1_000_000} videoMs=${videoNs / 1_000_000} " +
            "coverMs=${coverNs / 1_000_000} candidates=$candidates directOpens=$directOpens"
    }

    fun initCore(context: Context): Int {
        this.context = context.applicationContext
        return 0
    }

    fun isSupport(): Boolean = ::context.isInitialized

    fun getCoreMetaData(): String = JSONObject()
        .put("coreVersion", 1)
        .put("sysVersion", "WeKit-JPEG-SDR-2")
        .put("wechatKey", "303e1e50a7e5a84dc261223a0dd6a083")
        .toString()

    @Suppress("DEPRECATION") // DATA is allowed for direct reads of existing MediaStore files.
    fun isLivePhoto(mediaIds: List<Long>): HashMap<Long, Boolean> {
        val startedAt = SystemClock.elapsedRealtime()
        var cacheHits = 0
        var inspected = 0
        val result = HashMap<Long, Boolean>(mediaIds.size)
        mediaIds.forEach { result[it] = false }
        if (!isSupport()) return result
        val resolver = context.contentResolver
        val timings = IdentificationTimings()
        val pending = mutableListOf<ImageRequest>()
        // Bound SQL arguments while retaining the gallery's batch contract.
        for (ids in mediaIds.filter { it > 0 }.distinct().chunked(128)) {
            try {
                val selection = "${MediaStore.Images.Media._ID} IN (${ids.joinToString(",") { "?" }})"
                val queryStartedAt = SystemClock.elapsedRealtimeNanos()
                val queried = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DATA),
                    selection,
                    ids.map(Long::toString).toTypedArray(),
                    null,
                )
                timings.queryNs += SystemClock.elapsedRealtimeNanos() - queryStartedAt
                queried?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val identity = if (!cursor.isNull(1) && !cursor.isNull(2)) {
                            MediaIdentity(id, cursor.getLong(1), cursor.getLong(2)).takeIf {
                                it.size > 0 && it.modifiedSeconds > 0
                            }
                        } else {
                            null
                        }
                        val cached = identity?.let(identificationCache::get)
                        if (cached != null) {
                            cacheHits++
                            result[id] = cached
                        } else {
                            inspected++
                            pending += ImageRequest(id, identity, cursor.getString(3))
                        }
                    }
                }
            } catch (e: Exception) {
                // Failed queries are retried on the next gallery request, not cached as static photos.
                WeLogger.w(TAG, "Cannot query a live-photo media batch", e)
            }
        }
        val workers = minOf(4, pending.size)
        val tasks = (0 until workers).map { worker ->
            identificationWorkers.submit<Pair<HashMap<Long, Boolean>, IdentificationTimings>> {
                val detected = HashMap<Long, Boolean>()
                val workerTimings = IdentificationTimings()
                // Adjacent recent photos often contain the live candidates. Interleave
                // work so one worker does not receive every expensive video check.
                for (index in worker until pending.size step workers) {
                    val request = pending[index]
                    detected[request.id] = identify(resolver, request.id, request.identity, workerTimings, request.path)
                }
                detected to workerTimings
            }
        }
        tasks.forEach { task ->
            val (detected, workerTimings) = task.get()
            result.putAll(detected)
            timings.add(workerTimings)
        }
        WeLogger.d(TAG, "Identify batch: requested=${mediaIds.size} cached=$cacheHits inspected=$inspected elapsedMs=${SystemClock.elapsedRealtime() - startedAt} workers=$workers $timings")
        return result
    }

    private fun identify(resolver: ContentResolver, id: Long, identity: MediaIdentity?, timings: IdentificationTimings, path: String?): Boolean {
        val identifyStartedAt = SystemClock.elapsedRealtimeNanos()
        try {
            val openStartedAt = SystemClock.elapsedRealtimeNanos()
            val live = openImage(resolver, id, path, timings).use { input ->
                timings.openNs += SystemClock.elapsedRealtimeNanos() - openStartedAt
                var stageStartedAt = SystemClock.elapsedRealtimeNanos()
                val candidate = MotionPhotoContainer.hasMotionPhotoMetadata(input.channel)
                timings.headerNs += SystemClock.elapsedRealtimeNanos() - stageStartedAt
                if (!candidate) return@use false
                timings.candidates++
                stageStartedAt = SystemClock.elapsedRealtimeNanos()
                val info = MotionPhotoContainer.inspect(input.channel)
                timings.containerNs += SystemClock.elapsedRealtimeNanos() - stageStartedAt
                if (info == null) return@use false
                stageStartedAt = SystemClock.elapsedRealtimeNanos()
                val video = readVideo(input.fd, info.videoOffset, info.videoLength)
                timings.videoNs += SystemClock.elapsedRealtimeNanos() - stageStartedAt
                resolveCoverTimestamp(info.coverTimestampMs, video.durationMs)
                stageStartedAt = SystemClock.elapsedRealtimeNanos()
                validateCover(readCover(input.fd), video)
                timings.coverNs += SystemClock.elapsedRealtimeNanos() - stageStartedAt
                true
            }
            if (identity != null) identificationCache.put(identity, live)
            return live
        } catch (e: MediaFailure) {
            // Format/geometry failures are deterministic for this media identity.
            if (identity != null) identificationCache.put(identity, false)
            WeLogger.d(TAG, "Media $id is unsupported (${e.code}): ${e.message}")
        } catch (e: Exception) {
            // IO, permission and Android parser failures may be transient; never cache them.
            WeLogger.w(TAG, "Cannot inspect live-photo media $id", e)
        } finally {
            timings.identifyNs += SystemClock.elapsedRealtimeNanos() - identifyStartedAt
        }
        return false
    }

    fun getVideoMetaData(mediaId: Long, videoPath: String): String {
        if (!isSupport()) return errorMetadata(NOT_INITIALIZED, "Backend is not initialized")
        if (mediaId <= 0) return errorMetadata(INVALID_INPUT, "Invalid MediaStore ID")
        if (videoPath.isBlank()) return errorMetadata(INVALID_OUTPUT, "Empty video output path")
        var temporary: File? = null
        try {
            val output = File(videoPath).absoluteFile
            val extracted = temporaryFor(output, ".mp4")
            temporary = extracted
            val source = openImage(context.contentResolver, mediaId).use { input ->
                if (output.exists()) {
                    val sourceStat = Os.fstat(input.fd)
                    val outputStat = Os.stat(output.path)
                    if (sourceStat.st_dev == outputStat.st_dev && sourceStat.st_ino == outputStat.st_ino) {
                        fail(INVALID_OUTPUT, "Video output must not replace the source photo")
                    }
                }
                val info = MotionPhotoContainer.inspect(input.channel)
                    ?: fail(UNSUPPORTED_MEDIA, "Not a supported JPEG Motion Photo")
                val cover = readCover(input.fd)
                MotionPhotoContainer.extractVideo(input.channel, info, extracted)
                info to cover
            }
            val video = FileInputStream(extracted).use { input -> readVideo(input.fd, 0, input.channel.size()) }
            validateCover(source.second, video)
            val timestamp = resolveCoverTimestamp(source.first.coverTimestampMs, video.durationMs)
            val size = extracted.length()
            // The host consumes videoSize/videoDuration as Int, so do not overflow its DTO.
            if (size > Int.MAX_VALUE || video.durationMs > Int.MAX_VALUE) {
                fail(UNSUPPORTED_MEDIA, "Video exceeds the host metadata range")
            }
            Os.rename(extracted.path, output.path)
            return JSONObject()
                .put("errorCode", 0)
                .put("videoPath", videoPath)
                .put("videoSize", size)
                .put("videoWidth", video.width)
                .put("videoHeight", video.height)
                .put("videoDuration", video.durationMs)
                .put("coverTimeStampMs", timestamp)
                .toString()
        } catch (e: Exception) {
            WeLogger.w(TAG, "Cannot extract live-photo media $mediaId", e)
            return errorMetadata(errorCode(e), e.message ?: e.javaClass.simpleName)
        } finally {
            temporary?.delete()
        }
    }

    @JvmOverloads
    fun exportLivePhoto(json: String, oplusCompatibility: Boolean = false, vivoCompatibility: Int = 0): Int {
        if (!isSupport()) return NOT_INITIALIZED
        var temporary: File? = null
        var privateTemporary: File? = null
        var videoTemporary: File? = null
        try {
            val data = JSONObject(json)
            val cover = File(requiredPath(data, "coverPath")).absoluteFile
            val video = File(requiredPath(data, "videoPath")).absoluteFile
            val output = File(requiredPath(data, "exportPath")).absoluteFile
            val requestedTimestamp = if (data.has("coverTimeStampMs")) data.getLong("coverTimeStampMs") else -1L
            if (vivoCompatibility !in 0..3) fail(INVALID_INPUT, "Unknown Vivo export format")
            WeLogger.d(TAG, "Export request: cover=$cover video=$video output=$output coverTimeStampMs=$requestedTimestamp")
            if (output.canonicalFile == cover.canonicalFile || output.canonicalFile == video.canonicalFile) {
                fail(INVALID_OUTPUT, "Output must not replace an input file")
            }
            if (!cover.isFile || !video.isFile) fail(INVALID_INPUT, "Missing cover or video file")
            val videoInfo = FileInputStream(video).use { input -> readVideo(input.fd, 0, input.channel.size()) }
            val coverInfo = FileInputStream(cover).use { readCover(it.fd) }
            WeLogger.d(TAG, "Export media: cover=$coverInfo video=$videoInfo")
            validateCover(coverInfo, videoInfo)
            // FIXME: A received Huawei sample supplies microseconds in the host's millisecond
            //        field, and the save contract carries no reliable sender/vendor identity. Apply
            //        this user-approved compatibility only during export, only when milliseconds
            //        are out of range but converting microseconds fits. Strict contract handling
            //        would remove this conversion and reject the original out-of-range value.
            val exportTimestamp = if (requestedTimestamp > videoInfo.durationMs &&
                requestedTimestamp / 1000 <= videoInfo.durationMs
            ) {
                val milliseconds = requestedTimestamp / 1000
                WeLogger.w(TAG, "Export cover timestamp $requestedTimestamp interpreted as microseconds: ${milliseconds}ms")
                milliseconds
            } else {
                requestedTimestamp
            }
            val timestamp = resolveCoverTimestamp(exportTimestamp, videoInfo.durationMs)
            val generated = temporaryFor(output, ".jpg")
            temporary = generated
            // OPlus's marker describes an embedded video, so it applies to single-file exports.
            val addOplusTag = vivoCompatibility != 1 && oplusCompatibility &&
                ExifInterface(cover).getAttribute(ExifInterface.TAG_USER_COMMENT).isNullOrEmpty()
            var exportCover = cover
            if (addOplusTag) {
                // FIXME: OPlus Gallery requires UserComment's 0x800000 motion bit even when
                //        its native reader accepts the standard container. This opt-in adds only
                //        that bit on any device, without replacing an existing comment or copying
                //        camera/HDR flags. Remove this branch to restore standard-only export.
                //        Normalize before editing EXIF so cached-cover checksums are not invalidated.
                //        EXIF save changes JPEG length: rebuild XMP/MPF afterwards. Stage privately
                //        because ExifInterface's own temporary files need not have a media suffix.
                val privateDirectory = File(context.filesDir, "wekit/livephoto")
                if (!privateDirectory.isDirectory && !privateDirectory.mkdirs()) {
                    fail(INVALID_OUTPUT, "Cannot create private live-photo staging directory")
                }
                val normalized = File.createTempFile("wekit-livephoto-", ".jpg", privateDirectory)
                privateTemporary = normalized
                if (vivoCompatibility == 0) {
                    MotionPhotoContainer.normalizeCoverForExport(cover, video, normalized)
                } else {
                    MotionPhotoContainer.normalizeCover(cover, normalized)
                }
                ExifInterface(normalized).apply {
                    setAttribute(ExifInterface.TAG_USER_COMMENT, OPLUS_GALLERY_USER_COMMENT)
                    saveAttributes()
                }
                exportCover = normalized
            }
            val companion = if (vivoCompatibility == 1) {
                File(output.parentFile, output.nameWithoutExtension + ".mp4").also {
                    if (it.canonicalFile == cover.canonicalFile || it.canonicalFile == video.canonicalFile ||
                        it.canonicalFile == output.canonicalFile
                    ) fail(INVALID_OUTPUT, "Companion output must not replace a source or the photo output")
                }
            } else null
            val generatedInfo = when (vivoCompatibility) {
                0 -> MotionPhotoContainer.write(exportCover, video, generated, timestamp)
                1 -> {
                    val generatedVideo = temporaryFor(companion!!, ".mp4")
                    videoTemporary = generatedVideo
                    val pair = VivoMotionPhotoContainer.writeV1(exportCover, video, generated, generatedVideo, timestamp)
                    if (pair.coverTimestampMs != timestamp || pair.videoLength != generatedVideo.length()) {
                        fail(INVALID_MEDIA, "Generated Vivo pair metadata does not match its inputs")
                    }
                    FileInputStream(generatedVideo).use { input ->
                        if (readVideo(input.fd, 0, input.channel.size()) != videoInfo) {
                            fail(INVALID_MEDIA, "Generated Vivo pair changed video metadata")
                        }
                    }
                    null
                }
                2 -> VivoMotionPhotoContainer.writeV2(exportCover, video, generated, timestamp).photoInfo!!
                3 -> VivoMotionPhotoContainer.writeStandard(exportCover, video, generated, timestamp).photoInfo!!
                else -> error("Validated Vivo format is out of range")
            }
            FileInputStream(generated).use { input ->
                if (readCover(input.fd) != coverInfo) fail(INVALID_MEDIA, "Generated photo changed cover geometry")
                if (vivoCompatibility != 1) {
                    // The writer validated this staged file before publication; reuse its result.
                    val info = generatedInfo!!
                    if (info.coverTimestampMs != timestamp) fail(INVALID_MEDIA, "Generated cover time does not match its input")
                    if (vivoCompatibility == 0 && info.videoLength != video.length()) {
                        fail(INVALID_MEDIA, "Generated video length does not match its input")
                    }
                    val writtenVideo = readVideo(input.fd, info.videoOffset, info.videoLength)
                    if (writtenVideo != videoInfo) fail(INVALID_MEDIA, "Generated Motion Photo changed video metadata")
                }
            }
            if (addOplusTag && ExifInterface(generated).getAttribute(ExifInterface.TAG_USER_COMMENT) != OPLUS_GALLERY_USER_COMMENT) {
                fail(INVALID_MEDIA, "Generated Motion Photo lost the OPlus compatibility tag")
            }
            if (companion != null) {
                MotionPhotoFilePublisher.publishPair(generated, videoTemporary!!, output, companion)
                // FIXME: Vivo V1 pairing is indexed from both files' shared private ID.
                //        Scan both files, matching the native protocol; scanning only the JPEG
                //        cannot populate the companion row. Single-file formats need no pair scan.
                MediaScannerConnection.scanFile(context, arrayOf(output.path, companion.path), null, null)
            } else {
                Os.rename(generated.path, output.path)
            }
            WeLogger.d(TAG, "Exported live photo: output=$output coverTimeStampMs=$timestamp vivoFormat=$vivoCompatibility oplusTagAdded=$addOplusTag")
            return 0
        } catch (e: Exception) {
            WeLogger.w(TAG, "Cannot export a live photo", e)
            return errorCode(e)
        } finally {
            temporary?.delete()
            privateTemporary?.delete()
            videoTemporary?.delete()
        }
    }

    private fun openImage(
        resolver: ContentResolver, id: Long, path: String? = null, timings: IdentificationTimings? = null,
    ): FileInputStream {
        // Only use a path obtained by the current Images query; FUSE still enforces
        // access and EXIF redaction. Older target SDKs have different URI/path redaction.
        if (context.applicationInfo.targetSdkVersion >= 30 && path != null && File(path).isAbsolute) {
            try {
                val stream = FileInputStream(path)
                timings!!.directOpens++
                return stream
            } catch (_: FileNotFoundException) {
                // An indexed path can be missing or inaccessible. Preserve the provider
                // route; only opening failures fall back, never a format/validation failure.
            }
        }
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("No media descriptor for $id")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    private fun readVideo(fd: FileDescriptor, offset: Long, length: Long): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(fd, offset, length)
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: fail(INVALID_MEDIA, "Track has no MIME type")
                if (mime.startsWith("video/")) {
                    if (videoTrack != -1) fail(UNSUPPORTED_MEDIA, "Multiple video tracks are unsupported")
                    videoTrack = i
                }
            }
            if (videoTrack == -1) fail(INVALID_MEDIA, "Motion Photo has no video track")
            val format = extractor.getTrackFormat(videoTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else null
            val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            } else {
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            }
            val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
            } else {
                null
            }
            val details = "mime=$mime, profile=${profile ?: "unspecified"}, transfer=$transfer, standard=${standard ?: "unspecified"}"
            if (mime != MediaFormat.MIMETYPE_VIDEO_AVC && mime != MediaFormat.MIMETYPE_VIDEO_HEVC) {
                fail(UNSUPPORTED_MEDIA, "Only SDR AVC or HEVC video is supported ($details)")
            }
            val sdrTransfer = transfer == MediaFormat.COLOR_TRANSFER_LINEAR || transfer == COLOR_TRANSFER_SRGB ||
                transfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO || transfer == COLOR_TRANSFER_GAMMA22 ||
                transfer == COLOR_TRANSFER_GAMMA28
            @SuppressLint("InlinedApi")
            val hdrHevcProfile = mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
            if (!sdrTransfer || hdrHevcProfile ||
                format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) ||
                standard == MediaFormat.COLOR_STANDARD_BT2020
            ) {
                fail(UNSUPPORTED_MEDIA, "Unsupported transfer, HDR or BT.2020 video ($details)")
            }
            if (!extractor.psshInfo.isNullOrEmpty()) fail(UNSUPPORTED_MEDIA, "Protected video is unsupported ($details)")
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                Math.floorMod(format.getInteger(MediaFormat.KEY_ROTATION), 360)
            } else 0
            if (width <= 0 || height <= 0 || durationMs <= 0) fail(INVALID_MEDIA, "Invalid video dimensions or duration")
            if (rotation !in arrayOf(0, 90, 180, 270)) fail(UNSUPPORTED_MEDIA, "Unsupported video rotation")
            extractor.selectTrack(videoTrack)
            val sampleSize = extractor.sampleSize
            if (sampleSize <= 0) fail(INVALID_MEDIA, "Video track has no sample data")
            if (sampleSize > MAX_SAMPLE_BYTES) fail(UNSUPPORTED_MEDIA, "Video sample exceeds the read limit")
            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                fail(UNSUPPORTED_MEDIA, "Encrypted video sample is unsupported ($details)")
            }
            if (extractor.readSampleData(ByteBuffer.allocate(sampleSize.toInt()), 0) != sampleSize.toInt()) {
                fail(INVALID_MEDIA, "Truncated video sample")
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(fd, offset, length)
                if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) != "video/mp4") {
                    fail(UNSUPPORTED_MEDIA, "Video must use an MP4 container")
                }
                val reportedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val reportedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                val reportedDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val reportedRotation = Math.floorMod(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0, 360,
                )
                if (reportedWidth != width || reportedHeight != height || reportedDuration == null ||
                    reportedDuration <= 0 || abs(reportedDuration - durationMs) > 1000 || reportedRotation != rotation
                ) {
                    fail(INVALID_MEDIA, "Video metadata readers disagree")
                }
            } finally {
                retriever.release()
            }
            return VideoInfo(width, height, durationMs, rotation)
        } finally {
            extractor.release()
        }
    }

    private fun readCover(fd: FileDescriptor): CoverInfo {
        Os.lseek(fd, 0, OsConstants.SEEK_SET)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd, null, options)
        if (options.outMimeType != "image/jpeg" || options.outWidth <= 0 || options.outHeight <= 0) {
            fail(UNSUPPORTED_MEDIA, "Cover must be a valid JPEG image")
        }
        Os.lseek(fd, 0, OsConstants.SEEK_SET)
        val orientation = ExifInterface(fd).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val swap = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        return if (swap) CoverInfo(options.outHeight, options.outWidth) else CoverInfo(options.outWidth, options.outHeight)
    }

    private fun validateCover(cover: CoverInfo, video: VideoInfo) {
        val swap = video.rotation == 90 || video.rotation == 270
        val width = if (swap) video.height else video.width
        val height = if (swap) video.width else video.height
        val coverRatio = max(cover.width, cover.height).toDouble() / min(cover.width, cover.height)
        val videoRatio = max(width, height).toDouble() / min(width, height)
        if (abs(coverRatio - videoRatio) > 0.1 || (cover.width > cover.height) != width > height) {
            fail(UNSUPPORTED_MEDIA, "Cover/video geometry requires unsupported crop or orientation normalization")
        }
    }

    private fun resolveCoverTimestamp(timestamp: Long, durationMs: Long): Long {
        if (timestamp == -1L) return durationMs / 2
        if (timestamp !in 0..durationMs) {
            fail(INVALID_MEDIA, "Cover timestamp ${timestamp}ms is outside video duration ${durationMs}ms")
        }
        return timestamp
    }

    private fun temporaryFor(output: File, suffix: String): File {
        val parent = output.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs()) fail(INVALID_OUTPUT, "Cannot create the output directory")
        return File.createTempFile("wekit-livephoto-", suffix, parent)
    }

    private fun requiredPath(data: JSONObject, key: String): String = data.getString(key).also {
        if (it.isBlank()) fail(INVALID_INPUT, "Empty $key")
    }

    private fun errorCode(e: Exception): Int = when (e) {
        is MediaFailure -> e.code
        is JSONException -> INVALID_INPUT
        else -> READ_WRITE_FAILED
    }

    private fun errorMetadata(code: Int, message: String): String = JSONObject()
        .put("errorCode", code)
        .put("errorMessage", message)
        .put("videoPath", "")
        .put("videoSize", 0)
        .put("videoWidth", 0)
        .put("videoHeight", 0)
        .put("videoDuration", 0)
        .put("coverTimeStampMs", -1)
        .toString()

    private fun fail(code: Int, message: String): Nothing = throw MediaFailure(code, message)
}
