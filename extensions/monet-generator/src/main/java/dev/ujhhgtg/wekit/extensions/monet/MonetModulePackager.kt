package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class MonetModulePackager(
    private val request: MonetGenerationRequest,
) {
    fun pack(signedOverlayApk: File, outputZip: File) {
        val apkInstallPath = if (request.sdkInt >= 34) {
            "system/priv-app/$OVERLAY_APK_NAME"
        } else {
            "system/product/overlay/$OVERLAY_APK_NAME"
        }

        outputZip.parentFile?.mkdirs()
        ZipOutputStream(outputZip.outputStream().buffered()).use { zos ->
            zos.putTextEntry("module.prop", buildModuleProp())
            zos.putRawEntry("customize.sh", payload("customize.sh"))
            zos.putRawEntry(
                "META-INF/com/google/android/update-binary",
                payload("update-binary"),
            )
            zos.putRawEntry(
                "META-INF/com/google/android/updater-script",
                payload("updater-script"),
            )
            zos.putFileEntry(apkInstallPath, signedOverlayApk)
        }
    }

    private fun payload(name: String) = request.payloadDir.resolve(name).readBytes()

    private fun buildModuleProp(): String = buildString {
        appendLine("id=wekit-monet-engine")
        appendLine("name=微信莫奈引擎 (WeKit)")
        appendLine("version=${request.versionName} (${request.versionCode})")
        appendLine("versionCode=${request.versionCode}")
        appendLine("author=Ujhhgtg")
        append("description=为微信 ${request.versionName} 启用动态壁纸取色, 由 WeKit 在运行时生成")
    }

    private fun ZipOutputStream.putTextEntry(name: String, content: String) {
        putRawEntry(name, content.toByteArray(Charsets.UTF_8))
    }

    private fun ZipOutputStream.putFileEntry(name: String, file: File) {
        putRawEntry(name, file.readBytes())
    }

    private fun ZipOutputStream.putRawEntry(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private companion object {
        const val OVERLAY_APK_NAME = "MonetWeChat.apk"
    }
}
