package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetTabStyle
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

internal class MonetModulePackager(
    private val payloadDir: File,
    private val versionName: String,
    private val versionCode: Long,
    private val sdkInt: Int,
) {
    fun pack(
        signedOverlays: List<MonetBuiltOverlay>,
        options: MonetGenerationOptions,
        generatedUserId: Int,
        diagnosticsFile: File,
        outputZip: File,
    ) {
        validateMetadata()
        require(generatedUserId >= 0) { "Generated Android user ID must be non-negative" }
        require(diagnosticsFile.isFile) { "Monet resolution diagnostics are missing: $diagnosticsFile" }

        val overlaysById = signedOverlays.associateBy(MonetBuiltOverlay::overlayId)
        require(overlaysById.size == signedOverlays.size) { "Built Monet overlays contain duplicate IDs" }
        val expectedIds = expectedOverlayIds(options)
        require(overlaysById.keys == expectedIds) {
            "Built Monet overlay selection mismatch: expected ${expectedIds.sorted()}, " +
                "found ${overlaysById.keys.sorted()}"
        }
        signedOverlays.forEach { overlay ->
            val expected = requireNotNull(OVERLAY_IDENTITIES[overlay.overlayId]) {
                "Unknown built Monet overlay: ${overlay.overlayId}"
            }
            require(overlay.packageName == expected.packageName) {
                "Monet overlay ${overlay.overlayId} package identity drift"
            }
            require(overlay.fileName == expected.fileName) {
                "Monet overlay ${overlay.overlayId} file identity drift"
            }
            require(overlay.file.isFile) { "Signed Monet overlay is missing: ${overlay.file}" }
        }

        val entries = buildList {
            add(textEntry("module.prop", buildModuleProp()))
            add(textEntry("config.conf", buildConfig(options, generatedUserId)))
            add(payloadEntry("customize.sh", SCRIPT_MODE))
            add(payloadEntry("common.sh", SCRIPT_MODE))
            add(payloadEntry("service.sh", SCRIPT_MODE))
            add(payloadEntry("boot-completed.sh", SCRIPT_MODE))
            add(fileEntry("monet-resolution.json", diagnosticsFile))
            add(payloadEntry("META-INF/com/google/android/update-binary", SCRIPT_MODE, "update-binary"))
            add(payloadEntry("META-INF/com/google/android/updater-script", FILE_MODE, "updater-script"))
            signedOverlays.forEach { overlay ->
                add(fileEntry(installPath(overlay.fileName), overlay.file))
            }
        }
        writeZip(outputZip, entries)
    }

    private fun validateMetadata() {
        require(sdkInt >= 31) { "Android 12 or newer is required" }
        require(versionCode >= 0) { "WeChat version code must be non-negative" }
        require(versionName.isNotBlank() && versionName.none { it == '\n' || it == '\r' }) {
            "WeChat version name is invalid"
        }
    }

    private fun expectedOverlayIds(options: MonetGenerationOptions): Set<String> = buildSet {
        add(if (sdkInt >= 34) BASE_API_34_ID else BASE_API_31_ID)
        when (options.bubbleStyle) {
            MonetBubbleStyle.MODERN -> Unit
            MonetBubbleStyle.CLASSIC -> add(CLASSIC_BUBBLE_ID)
            MonetBubbleStyle.PRO -> add(PRO_BUBBLE_ID)
        }
        if (options.multiSceneCornersEnabled) add(CORNERS_ID)
        add(
            when (options.tabStyle) {
                MonetTabStyle.SOLID -> SOLID_TAB_ID
                MonetTabStyle.BLUR -> BLUR_TAB_ID
            },
        )
    }

    private fun installPath(fileName: String): String = if (sdkInt >= 34) {
        val stem = fileName.removeSuffix(APK_SUFFIX)
        require(stem != fileName && stem.isNotEmpty()) { "Invalid Monet overlay APK name: $fileName" }
        "system/priv-app/$stem/$fileName"
    } else {
        "system/product/overlay/$fileName"
    }

    private fun buildConfig(options: MonetGenerationOptions, generatedUserId: Int): String = buildString {
        appendLine("bubble_style=${options.bubbleStyle.name}")
        appendLine("multi_scene_corners_enabled=${options.multiSceneCornersEnabled}")
        appendLine("tab_style=${options.tabStyle.name}")
        appendLine("user_scope=${options.userScope.name}")
        appendLine("generated_user_id=$generatedUserId")
    }

    private fun payloadEntry(
        archiveName: String,
        mode: Int,
        payloadName: String = archiveName,
    ): ModuleEntry {
        val file = payloadDir.resolve(payloadName)
        require(file.isFile) { "Monet module payload is missing: $payloadName" }
        return ModuleEntry(archiveName, file.readBytes(), mode)
    }

    private fun textEntry(name: String, content: String, mode: Int = FILE_MODE) =
        ModuleEntry(name, content.toByteArray(Charsets.UTF_8), mode)

    private fun fileEntry(name: String, file: File, mode: Int = FILE_MODE) =
        ModuleEntry(name, file.readBytes(), mode)

    private fun buildModuleProp(): String = buildString {
        appendLine("id=wekit-monet-engine")
        appendLine("name=微信莫奈引擎 S4 (WeKit)")
        appendLine("version=$versionName ($versionCode)")
        appendLine("versionCode=$versionCode")
        appendLine("author=枯れ木, H_1e93d, HSSkyBoy; WeKit runtime adaptation: Ujhhgtg")
        appendLine(
            "description=为微信 $versionName 安装所选 S4 Monet 覆盖；由 WeKit 在生成时适配并在开机后恢复状态",
        )
    }

    private fun writeZip(outputZip: File, entries: List<ModuleEntry>) {
        require(entries.isNotEmpty() && entries.size <= UShort.MAX_VALUE.toInt()) {
            "Invalid Monet module ZIP entry count"
        }
        val ordered = entries.sortedBy(ModuleEntry::name)
        require(ordered.map(ModuleEntry::name).toSet().size == ordered.size) {
            "Monet module ZIP contains duplicate paths"
        }
        ordered.forEach { entry ->
            require(isSafeArchivePath(entry.name)) { "Unsafe Monet module ZIP path: ${entry.name}" }
        }

        val archive = ByteArrayOutputStream()
        val centralEntries = ArrayList<CentralEntry>(ordered.size)
        ordered.forEach { entry ->
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            require(nameBytes.size <= UShort.MAX_VALUE.toInt()) { "Monet module ZIP path is too long" }
            val offset = archive.size()
            val crc = CRC32().apply { update(entry.bytes) }.value.toInt()
            archive.writeIntLe(LOCAL_FILE_HEADER_SIGNATURE)
            archive.writeShortLe(ZIP_VERSION)
            archive.writeShortLe(UTF8_FLAG)
            archive.writeShortLe(STORED_METHOD)
            archive.writeShortLe(FIXED_DOS_TIME)
            archive.writeShortLe(FIXED_DOS_DATE)
            archive.writeIntLe(crc)
            archive.writeIntLe(entry.bytes.size)
            archive.writeIntLe(entry.bytes.size)
            archive.writeShortLe(nameBytes.size)
            archive.writeShortLe(0)
            archive.write(nameBytes)
            archive.write(entry.bytes)
            centralEntries += CentralEntry(entry, nameBytes, crc, offset)
        }

        val centralOffset = archive.size()
        centralEntries.forEach { central ->
            archive.writeIntLe(CENTRAL_FILE_HEADER_SIGNATURE)
            archive.writeShortLe(ZIP_VERSION_MADE_BY_UNIX)
            archive.writeShortLe(ZIP_VERSION)
            archive.writeShortLe(UTF8_FLAG)
            archive.writeShortLe(STORED_METHOD)
            archive.writeShortLe(FIXED_DOS_TIME)
            archive.writeShortLe(FIXED_DOS_DATE)
            archive.writeIntLe(central.crc)
            archive.writeIntLe(central.entry.bytes.size)
            archive.writeIntLe(central.entry.bytes.size)
            archive.writeShortLe(central.nameBytes.size)
            archive.writeShortLe(0)
            archive.writeShortLe(0)
            archive.writeShortLe(0)
            archive.writeShortLe(0)
            archive.writeIntLe(central.entry.mode shl 16)
            archive.writeIntLe(central.localHeaderOffset)
            archive.write(central.nameBytes)
        }
        val centralSize = archive.size() - centralOffset
        archive.writeIntLe(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        archive.writeShortLe(0)
        archive.writeShortLe(0)
        archive.writeShortLe(centralEntries.size)
        archive.writeShortLe(centralEntries.size)
        archive.writeIntLe(centralSize)
        archive.writeIntLe(centralOffset)
        archive.writeShortLe(0)

        outputZip.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) {
                "Could not create Monet module output directory: $parent"
            }
        }
        outputZip.outputStream().buffered().use { it.write(archive.toByteArray()) }
    }

    private fun isSafeArchivePath(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith('/') && '\\' !in name &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        writeShortLe(value)
        writeShortLe(value ushr 16)
    }

    private data class ModuleEntry(val name: String, val bytes: ByteArray, val mode: Int)
    private data class CentralEntry(
        val entry: ModuleEntry,
        val nameBytes: ByteArray,
        val crc: Int,
        val localHeaderOffset: Int,
    )
    private data class OverlayIdentity(val packageName: String, val fileName: String)

    private companion object {
        const val BASE_APK_NAME = "MonetWeChat.apk"
        const val APK_SUFFIX = ".apk"
        const val BASE_API_31_ID = "base-api31"
        const val BASE_API_34_ID = "base-api34"
        const val CLASSIC_BUBBLE_ID = "classic-bubble"
        const val PRO_BUBBLE_ID = "pro-bubble"
        const val CORNERS_ID = "multi-scene-corners"
        const val SOLID_TAB_ID = "solid-tab"
        const val BLUR_TAB_ID = "blur-tab"

        const val REGULAR_FILE_MODE = 0x8000
        const val FILE_MODE = REGULAR_FILE_MODE or 0x1a4
        const val SCRIPT_MODE = REGULAR_FILE_MODE or 0x1ed
        const val ZIP_VERSION = 20
        const val ZIP_VERSION_MADE_BY_UNIX = (3 shl 8) or ZIP_VERSION
        const val UTF8_FLAG = 0x0800
        const val STORED_METHOD = 0
        const val FIXED_DOS_TIME = 0
        const val FIXED_DOS_DATE = 0x21
        const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50
        const val CENTRAL_FILE_HEADER_SIGNATURE = 0x02014b50
        const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50

        val OVERLAY_IDENTITIES = mapOf(
            BASE_API_31_ID to OverlayIdentity("monet.com.tencent.mm", BASE_APK_NAME),
            BASE_API_34_ID to OverlayIdentity("monet.com.tencent.mm", BASE_APK_NAME),
            CLASSIC_BUBBLE_ID to OverlayIdentity(
                "monet.classicbubble.com.tencent.mm",
                "MonetWeChatClassicBubble.apk",
            ),
            PRO_BUBBLE_ID to OverlayIdentity(
                "monet.bubblepro.com.tencent.mm",
                "MonetWeChatBubblePro.apk",
            ),
            CORNERS_ID to OverlayIdentity(
                "monet.multiscenecorners.com.tencent.mm",
                "MonetWeChatMultiSceneCorners.apk",
            ),
            SOLID_TAB_ID to OverlayIdentity(
                "monet.solidtab.com.tencent.mm",
                "MonetWeChatSolidTab.apk",
            ),
            BLUR_TAB_ID to OverlayIdentity(
                "monet.blurtab.com.tencent.mm",
                "MonetWeChatBlurTab.apk",
            ),
        )
    }
}
