package dev.ujhhgtg.wekit.extensions.monet

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object MonetModulePackager {
    fun pack(overlay: File, output: File) {
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name).apply {
                    time = 315532800000L
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            add("module.prop", "id=wekit_monet\nname=WeKit Monet\nversion=2\nversionCode=2\nauthor=Ujhhgtg\ndescription=Runtime generated WeChat Monet overlay\n".toByteArray())
            add("customize.sh", "#!/system/bin/sh\nui_print 'WeKit Monet installed'\n".toByteArray())
            add("system/product/overlay/MonetWeChat.apk", overlay.readBytes())
        }
    }
}
