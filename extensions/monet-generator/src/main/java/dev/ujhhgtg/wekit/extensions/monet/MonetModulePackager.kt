package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetUserScope
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object MonetModulePackager {
    data class Overlay(val file: File, val packageName: String)

    fun pack(overlays: List<Overlay>, options: MonetGenerationOptions, output: File) {
        require(overlays.isNotEmpty())
        output.parentFile?.mkdirs()
        val packages = overlays.joinToString(" ", transform = Overlay::packageName)
        val scope = if (options.userScope == MonetUserScope.ALL) "all" else "current"
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun add(name: String, text: String) = add(zip, name, text.toByteArray())
            add(
                "module.prop",
                "id=wekit_monet\nname=WeKit Monet\nversion=2\nversionCode=2\nauthor=Ujhhgtg\n" +
                    "description=Runtime generated WeChat Monet overlays\n",
            )
            add(
                "customize.sh",
                "#!/system/bin/sh\nset_perm_recursive \"${'$'}MODPATH\" 0 0 0755 0644\n" +
                    "set_perm \"${'$'}MODPATH/service.sh\" 0 0 0755\n" +
                    "set_perm \"${'$'}MODPATH/boot-completed.sh\" 0 0 0755\n",
            )
            add(
                "config.conf",
                "USER_SCOPE=$scope\nCURRENT_USER=${options.currentUserId}\nOVERLAY_PACKAGES='$packages'\n",
            )
            add("common.sh", COMMON_SCRIPT)
            add("service.sh", "#!/system/bin/sh\nMODDIR=${'$'}{0%/*}\nsh \"${'$'}MODDIR/boot-completed.sh\"\n")
            add("boot-completed.sh", BOOT_SCRIPT)
            overlays.forEach { overlay ->
                add(zip, "system/product/overlay/${overlay.file.name}", overlay.file.readBytes())
            }
        }
    }

    private fun add(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private const val COMMON_SCRIPT = """#!/system/bin/sh
restore_overlays() {
  config="${'$'}MODDIR/config.conf"
  [ -f "${'$'}config" ] || return 1
  . "${'$'}config"
  if [ "${'$'}USER_SCOPE" = all ]; then
    users="${'$'}(cmd user list 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')"
  else
    users="${'$'}CURRENT_USER"
  fi
  result=0
  for user in ${'$'}users; do
    for package in ${'$'}OVERLAY_PACKAGES; do
      cmd overlay enable --user "${'$'}user" "${'$'}package" >/dev/null 2>&1 || result=1
      cmd overlay set-priority --user "${'$'}user" "${'$'}package" highest >/dev/null 2>&1 || result=1
    done
    am force-stop --user "${'$'}user" com.tencent.mm >/dev/null 2>&1 || result=1
  done
  return ${'$'}result
}
"""

    private const val BOOT_SCRIPT = """#!/system/bin/sh
MODDIR=${'$'}{0%/*}
LOCK=/dev/.wekit-monet-overlay-restore
mkdir "${'$'}LOCK" 2>/dev/null || exit 0
trap 'rmdir "${'$'}LOCK"' EXIT
. "${'$'}MODDIR/common.sh"
restore_overlays
"""
}
