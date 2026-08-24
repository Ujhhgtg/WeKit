package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.graphics.BitmapFactory
import android.media.ExifInterface
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface HomeSidePanelImageImportResult {
    data class Success(
        val assetId: String,
        val file: Path,
        val widthPx: Int,
        val heightPx: Int,
    ) : HomeSidePanelImageImportResult
    data object TooLarge : HomeSidePanelImageImportResult
    data object TooManyPixels : HomeSidePanelImageImportResult
    data object UnsupportedAspectRatio : HomeSidePanelImageImportResult
    data object InvalidImage : HomeSidePanelImageImportResult
    data class Failure(val error: Throwable) : HomeSidePanelImageImportResult
}

internal class HomeSidePanelImageAssetStore(
    private val root: Path = KnownPaths.moduleAssets / "home_side_panel" / "images",
) {
    internal data class PreparedCommit(
        val sessionId: String,
        val promotedAssetIds: Set<String>,
        val orphanedAssetIds: Set<String>,
    )

    init {
        root.createDirectories()
    }

    fun resolve(sessionId: String?, assetId: String): Path? {
        val validatedAssetId = validateId(assetId, "asset")
        if (sessionId != null) {
            val draft = draftAsset(validateId(sessionId, "session"), validatedAssetId)
            if (draft.exists()) return draft
        }
        return formalAsset(validatedAssetId).takeIf(Path::exists)
    }

    fun prepareCommit(
        sessionId: String,
        oldAssetIds: Set<String>,
        newAssetIds: Set<String>,
    ): Result<PreparedCommit> = runCatching {
        val validatedSessionId = validateId(sessionId, "session")
        val oldIds = oldAssetIds.mapTo(linkedSetOf()) { validateId(it, "asset") }
        val newIds = newAssetIds.mapTo(linkedSetOf()) { validateId(it, "asset") }
        val promoted = linkedSetOf<String>()
        try {
            for (assetId in newIds) {
                val formal = formalAsset(assetId)
                if (formal.exists()) continue
                val draft = draftAsset(validatedSessionId, assetId)
                if (!draft.exists()) {
                    check(assetId in oldIds) { "Image asset '$assetId' is missing" }
                    continue
                }
                val partial = formalPartial(assetId)
                partial.deleteIfExists()
                draft.copyTo(partial)
                moveAtomically(partial, formal)
                promoted += assetId
            }
        } catch (error: Throwable) {
            promoted.forEach { formalAsset(it).deleteIfExists() }
            newIds.forEach { formalPartial(it).deleteIfExists() }
            throw error
        }
        PreparedCommit(
            sessionId = validatedSessionId,
            promotedAssetIds = promoted,
            orphanedAssetIds = oldIds - newIds,
        )
    }

    fun rollbackCommit(prepared: PreparedCommit) {
        prepared.promotedAssetIds.forEach { formalAsset(it).deleteIfExists() }
    }

    fun finalizeCommit(prepared: PreparedCommit) {
        prepared.orphanedAssetIds.forEach { formalAsset(it).deleteIfExists() }
        discardSession(prepared.sessionId)
    }

    fun discardSession(sessionId: String) {
        draftSession(validateId(sessionId, "session")).toFile().deleteRecursively()
    }

    fun deleteDraftAsset(sessionId: String, assetId: String) {
        draftAsset(validateId(sessionId, "session"), validateId(assetId, "asset")).deleteIfExists()
    }

    fun cleanupStaleDrafts() {
        draftRoot.toFile().deleteRecursively()
        root.toFile().listFiles { file ->
            file.isFile && file.name.startsWith('.') && file.name.endsWith(".part")
        }?.forEach { it.delete() }
    }

    suspend fun importDraft(
        sessionId: String,
        openInput: () -> InputStream,
    ): HomeSidePanelImageImportResult = withContext(Dispatchers.IO) {
        val validatedSessionId = runCatching { validateId(sessionId, "session") }.getOrElse {
            return@withContext HomeSidePanelImageImportResult.Failure(it)
        }
        val assetId = UUID.randomUUID().toString()
        val session = draftSession(validatedSessionId).createDirectories()
        val partial = session / ".$assetId.part"
        val destination = draftAsset(validatedSessionId, assetId)
        try {
            var total = 0L
            openInput().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) {
                            return@withContext HomeSidePanelImageImportResult.TooLarge
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(partial.toString(), bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext HomeSidePanelImageImportResult.InvalidImage
            }
            val dimensions = orientedDimensions(partial, bounds.outWidth, bounds.outHeight)
            if (dimensions.widthPx.toLong() * dimensions.heightPx.toLong() > MAX_IMAGE_PIXELS) {
                return@withContext HomeSidePanelImageImportResult.TooManyPixels
            }
            if (!isHomeSidePanelImageAspectRatioSupported(dimensions.widthPx, dimensions.heightPx)) {
                return@withContext HomeSidePanelImageImportResult.UnsupportedAspectRatio
            }
            currentCoroutineContext().ensureActive()
            moveAtomically(partial, destination)
            HomeSidePanelImageImportResult.Success(
                assetId = assetId,
                file = destination,
                widthPx = dimensions.widthPx,
                heightPx = dimensions.heightPx,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            HomeSidePanelImageImportResult.Failure(error)
        } finally {
            partial.deleteIfExists()
        }
    }

    private fun formalAsset(assetId: String): Path = root / assetId

    private fun orientedDimensions(path: Path, widthPx: Int, heightPx: Int): ImportedDimensions {
        val orientation = runCatching {
            ExifInterface(path.toString()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return if (
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            ImportedDimensions(heightPx, widthPx)
        } else {
            ImportedDimensions(widthPx, heightPx)
        }
    }

    private fun moveAtomically(source: Path, destination: Path) {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun formalPartial(assetId: String): Path = root / ".$assetId.part"

    private fun draftSession(sessionId: String): Path = draftRoot / sessionId

    private fun draftAsset(sessionId: String, assetId: String): Path = draftSession(sessionId) / assetId

    private fun validateId(id: String, subject: String): String {
        val parsed = runCatching { UUID.fromString(id) }.getOrElse {
            throw IllegalArgumentException("Invalid $subject ID: $id", it)
        }
        require(parsed.toString() == id) { "Invalid $subject ID: $id" }
        return id
    }

    private val draftRoot: Path
        get() = root / ".draft"

    private companion object {
        const val MAX_IMAGE_BYTES = 50L * 1024L * 1024L
        const val MAX_IMAGE_PIXELS = 50_000_000L
    }

    private data class ImportedDimensions(
        val widthPx: Int,
        val heightPx: Int,
    )
}
