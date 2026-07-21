package dev.ujhhgtg.wekit.features.items.chat.panel.voice

import dev.ujhhgtg.wekit.features.items.chat.panel.CloneVoice
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelPaths
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object CloneVoiceRepository {
    const val MAX_IMPORT_BYTES = 1024L * 1024L

    @Serializable
    private data class CloneVoiceStore(
        val tones: List<CloneVoice> = emptyList(),
        val selectedId: String = "",
    )

    private val metadataFile get() = PanelPaths.cloneVoiceDir / "voices.json"

    @Synchronized
    fun load(): List<CloneVoice> {
        val store = readStoreOrNull() ?: return emptyList()
        val valid = store.tones.distinctBy { it.id }.filter {
            runCatching { voicePath(it).isRegularFile() }.getOrDefault(false)
        }
        if (valid != store.tones) {
            writeStore(
                store.copy(
                    tones = valid,
                    selectedId = store.selectedId.takeIf { id -> valid.any { it.id == id } }
                        ?: valid.firstOrNull()?.id.orEmpty(),
                ),
            )
        }
        cleanupOrphans(valid)
        return valid
    }

    @Synchronized
    fun selectedId(): String {
        val store = readStoreOrNull() ?: return ""
        return store.selectedId.takeIf { id ->
            store.tones.any { it.id == id && runCatching { voicePath(it).isRegularFile() }.getOrDefault(false) }
        }.orEmpty()
    }

    fun selected(): CloneVoice? = load().firstOrNull { it.id == selectedId() }

    @Synchronized
    fun select(id: String?): Result<Unit> = runCatching {
        val store = requireStore()
        require(id == null || store.tones.any { it.id == id && voicePath(it).isRegularFile() }) { "音色不存在" }
        writeStore(store.copy(selectedId = id.orEmpty()))
    }

    @Synchronized
    fun import(name: String, displayName: String, input: InputStream, declaredSize: Long? = null): Result<CloneVoice> =
        runCatching {
            val safeName = name.trim()
            require(safeName.isNotBlank()) { "音色名称不能为空" }
            if (declaredSize != null && declaredSize > MAX_IMPORT_BYTES) {
                error("音色文件不能超过 1 MiB")
            }
            val suffix = displayName.substringAfterLast('.', "bin").lowercase().replace(Regex("[^a-z0-9]"), "")
                .ifBlank { "bin" }
            val id = UUID.randomUUID().toString().replace("-", "")
            val fileName = "$id.$suffix"
            val destination = PanelPaths.cloneVoiceDir / fileName
            destination.parent.createDirectories()
            try {
                input.use { source ->
                    Files.newOutputStream(destination).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_IMPORT_BYTES) error("音色文件不能超过 1 MiB")
                            output.write(buffer, 0, count)
                        }
                        require(total > 0) { "音色文件为空" }
                    }
                }
                require(isReadableVoice(destination)) { "音色文件不可读" }
                val clone = CloneVoice(id = id, name = safeName, fileName = fileName)
                val store = requireStore()
                writeStore(
                    store.copy(
                        tones = store.tones + clone,
                        selectedId = store.selectedId.ifBlank { id },
                    ),
                )
                clone
            } catch (error: Throwable) {
                destination.deleteIfExists()
                throw error
            }
        }

    @Synchronized
    fun importBytes(name: String, displayName: String, bytes: ByteArray): Result<CloneVoice> {
        require(bytes.size.toLong() <= MAX_IMPORT_BYTES) { "音色文件不能超过 1 MiB" }
        return import(name, displayName, bytes.inputStream(), bytes.size.toLong())
    }

    @Synchronized
    fun delete(id: String): Result<Unit> = runCatching {
        val store = requireStore()
        val target = store.tones.firstOrNull { it.id == id } ?: error("音色不存在")
        val targetPath = voicePath(target)
        val remaining = store.tones.filterNot { it.id == id }
        writeStore(
            store.copy(
                tones = remaining,
                selectedId = when {
                    store.selectedId != id -> store.selectedId
                    remaining.isNotEmpty() -> remaining.first().id
                    else -> ""
                },
            ),
        )
        targetPath.deleteIfExists()
    }

    fun voicePath(voice: CloneVoice): Path {
        val fileName = voice.fileName
        require(fileName.isNotBlank() && fileName.asPath.fileName.toString() == fileName) {
            "音色文件名无效"
        }
        val root = PanelPaths.cloneVoiceDir.toAbsolutePath().normalize()
        return root.resolve(fileName).normalize().also { path ->
            require(path.parent == root) { "音色文件路径无效" }
        }
    }

    fun synthesisInput(voice: CloneVoice): Result<Pair<ByteArray, String>> = runCatching {
        val source = voicePath(voice)
        require(source.isRegularFile()) { "选择的语音文件不存在或不可读" }
        if (source.extension.equals("silk", true) || hasSilkHeader(source)) {
            val stem = md5(source.absolutePathString())
            val pcm = PanelPaths.panelCacheDir / "$stem.pcm"
            val mp3 = PanelPaths.panelCacheDir / "$stem.mp3"
            try {
                require(AudioUtils.silkToPcm(source.absolutePathString(), pcm.absolutePathString())) { "Silk 转 PCM 失败" }
                require(AudioUtils.pcmToMp3(pcm.absolutePathString(), mp3.absolutePathString())) { "PCM 转 MP3 失败" }
                Files.readAllBytes(mp3) to voice.fileName.substringBeforeLast('.') + ".mp3"
            } finally {
                pcm.deleteIfExists()
                mp3.deleteIfExists()
            }
        } else {
            Files.readAllBytes(source) to voice.fileName
        }
    }

    private fun readStoreOrNull(): CloneVoiceStore? {
        if (metadataFile.notExists()) return CloneVoiceStore()
        return runCatching { DefaultJson.decodeFromString<CloneVoiceStore>(metadataFile.readText()) }
            .getOrNull()
    }

    private fun requireStore(): CloneVoiceStore = readStoreOrNull()
        ?: error("音色元数据损坏，请修复 voices.json 后重试")

    private fun writeStore(store: CloneVoiceStore) {
        metadataFile.parent.createDirectories()
        val temporary = metadataFile.resolveSibling("${metadataFile.name}.tmp")
        temporary.writeText(DefaultJson.encodeToString(store))
        runCatching {
            Files.move(
                temporary,
                metadataFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary, metadataFile, StandardCopyOption.REPLACE_EXISTING)
        }
        temporary.deleteIfExists()
    }

    private fun cleanupOrphans(voices: List<CloneVoice>) {
        val names = voices.mapTo(mutableSetOf()) { it.fileName }
        runCatching {
            Files.list(PanelPaths.cloneVoiceDir).use { stream ->
                stream.filter { it.isRegularFile() && it != metadataFile && it.name !in names }.forEach {
                    it.deleteIfExists()
                }
            }
        }
    }

    private fun isReadableVoice(path: Path): Boolean {
        if (!path.isRegularFile() || path.fileSize() <= 0) return false
        return AudioUtils.getDurationMs(path.absolutePathString()) > 0
    }

    private fun hasSilkHeader(path: Path): Boolean = runCatching {
        Files.newInputStream(path).use { input ->
            val header = ByteArray(8)
            input.read(header) == header.size && header.contentEquals(byteArrayOf(2, 35, 33, 83, 73, 76, 75, 95))
        }
    }.getOrDefault(false)

    private fun md5(value: String) = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
