package dev.ujhhgtg.wekit.extensions.monet

import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetUserScope
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object MonetModulePackager {
    data class Overlay(val file: File, val packageName: String, val installInitially: Boolean = true)

    fun pack(
        overlays: List<Overlay>,
        options: MonetGenerationOptions,
        versionName: String,
        versionCode: Long,
        sdkInt: Int,
        output: File,
    ) {
        require(overlays.isNotEmpty())
        require(versionName.isNotBlank() && '\n' !in versionName && '\r' !in versionName)
        require(versionCode >= 0)
        require(sdkInt >= 31)
        output.parentFile?.mkdirs()
        val selected = overlays.filter(Overlay::installInitially)
        val base = selected.filterNot { it.packageName == SOLID_TAB_PACKAGE || it.packageName == BLUR_TAB_PACKAGE }
        val selectedTab = selected.single { it.packageName == SOLID_TAB_PACKAGE || it.packageName == BLUR_TAB_PACKAGE }
        val packages = selected.joinToString(" ", transform = Overlay::packageName)
        val basePackages = base.joinToString(" ", transform = Overlay::packageName)
        val initialFiles = selected.joinToString(" ") { it.file.name }
        val tabStyle = if (selectedTab.packageName == BLUR_TAB_PACKAGE) "blur" else "solid"
        val scope = if (options.userScope == MonetUserScope.ALL) "all" else "current"
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun add(name: String, text: String) = add(zip, name, text.toByteArray())
            add(
                "module.prop",
                "id=wekit-monet-engine\nname=微信莫奈引擎 (WeKit)\n" +
                    "version=$versionName ($versionCode)\nversionCode=$versionCode\nauthor=Ujhhgtg\n" +
                    "description=为微信 $versionName 启用动态壁纸取色, 由 WeKit 在运行时生成\n",
            )
            add("customize.sh", CUSTOMIZE_SCRIPT)
            add("META-INF/com/google/android/update-binary", UPDATE_BINARY)
            add("META-INF/com/google/android/updater-script", "#MAGISK\n")
            add(
                "config.conf",
                "USER_SCOPE=$scope\nCURRENT_USER=${options.currentUserId}\n" +
                    "BASE_OVERLAY_PACKAGES='$basePackages'\nOVERLAY_PACKAGES='$packages'\n" +
                    "INITIAL_OVERLAY_FILES='$initialFiles'\nTAB_STYLE=$tabStyle\n",
            )
            add("common.sh", COMMON_SCRIPT)
            add("action.sh", ACTION_SCRIPT)
            add("service.sh", $$"#!/system/bin/sh\nMODDIR=${0%/*}\nsh \"$MODDIR/boot-completed.sh\"\n")
            add("boot-completed.sh", BOOT_SCRIPT)
            overlays.forEach { overlay ->
                add(zip, "files/${overlay.file.name}", overlay.file.readBytes())
            }
        }
    }

    private fun add(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private const val COMMON_SCRIPT = $$"""#!/system/bin/sh
restore_overlays() {
  config="$MODDIR/config.conf"
  [ -f "$config" ] || return 1
  . "$config"
  if [ "$USER_SCOPE" = all ]; then
    users="$(cmd user list 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')"
  else
    users="$CURRENT_USER"
  fi
  result=0
  for user in $users; do
    for package in $OVERLAY_PACKAGES; do
      pm path "$package" >/dev/null 2>&1 || continue
      cmd overlay enable --user "$user" "$package" >/dev/null 2>&1 || result=1
    done
    am force-stop --user "$user" com.tencent.mm >/dev/null 2>&1 || result=1
  done
  return $result
}

set_config_value() {
  key="$1"
  value="$2"
  if grep -q "^$key=" "$MODDIR/config.conf"; then
    sed -i "s|^$key=.*|$key='$value'|" "$MODDIR/config.conf"
  else
    echo "$key='$value'" >> "$MODDIR/config.conf"
  fi
}

select_tab_overlay() {
  style="$1"
  case "$style" in
    solid)
      name=MonetWeChatSolidTab
      package=monet.solidtab.com.tencent.mm
      ;;
    blur)
      name=MonetWeChatBlurTab
      package=monet.blurtab.com.tencent.mm
      ;;
    *) return 1 ;;
  esac
  source="$MODDIR/files/$name.apk"
  [ -f "$source" ] || return 1
  if [ "$(getprop ro.build.version.sdk)" -ge 34 ]; then
    rm -rf "$MODDIR/system/priv-app/MonetWeChatSolidTab" "$MODDIR/system/priv-app/MonetWeChatBlurTab"
    target="$MODDIR/system/priv-app/$name"
    mkdir -p "$target" || return 1
    cp -f "$source" "$target/$name.apk" || return 1
  else
    target="$MODDIR/system/product/overlay"
    mkdir -p "$target" || return 1
    rm -f "$target/MonetWeChatSolidTab.apk" "$target/MonetWeChatBlurTab.apk"
    cp -f "$source" "$target/$name.apk" || return 1
  fi
  chmod 0644 "$target/$name.apk" || return 1
  set_config_value TAB_STYLE "$style"
  set_config_value OVERLAY_PACKAGES "$BASE_OVERLAY_PACKAGES $package"
}
"""

    private const val ACTION_SCRIPT = $$"""#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/config.conf"
. "$MODDIR/common.sh"
if [ "$TAB_STYLE" = blur ]; then
  next=solid
  label=纯色
else
  next=blur
  label=模糊
fi
if select_tab_overlay "$next"; then
  echo "- 已切换为${label}底栏。"
  echo "- 请重启系统以应用新的静态 Overlay。"
else
  echo "! 底栏切换失败。"
  exit 1
fi
"""

    private const val CUSTOMIZE_SCRIPT = $$"""# shellcheck disable=SC2034
SKIPUNZIP=0

# Ask compatible root managers to activate this module update immediately.
export MODULE_HOT_INSTALL_REQUEST=true

ui_print " "
ui_print '             _       __     __ __ _ __'
ui_print '            | |     / /__  / //_/(_) /_'
ui_print '            | | /| / / _ \/ ,<  / / __/'
ui_print '            | |/ |/ /  __/ /| |/ / /_'
ui_print '            |__/|__/\___/_/ |_/_/\__/'
ui_print " "
ui_print "       [WeKit] WeChat, now with superpowers"
ui_print " "
ui_print "已安装生成时选定的莫奈覆盖。"
ui_print " "
ui_print "温馨提示:"
ui_print "- 若正在使用 KernelSU 或 APatch 及其衍生版, 请禁用「微信」的「App Profile」中的「卸载模块」选项。"
ui_print "- 无须禁用「默认卸载模块」。"
ui_print "- 若仍不生效, 请尝试给予「微信」Root 权限。"

install_static_overlay() {
  apk="$1"
  name="${apk##*/}"
  name="${name%.apk}"
  if [ "$(getprop ro.build.version.sdk)" -ge 34 ]; then
    target="$MODPATH/system/priv-app/$name"
    mkdir -p "$target" || return 1
    cp -f "$apk" "$target/$name.apk" || return 1
  else
    target="$MODPATH/system/product/overlay"
    mkdir -p "$target" || return 1
    cp -f "$apk" "$target/$name.apk" || return 1
  fi
}

 . "$MODPATH/config.conf"
for name in $INITIAL_OVERLAY_FILES; do
  apk="$MODPATH/files/$name"
  [ -f "$apk" ] || abort "! 缺少 Overlay APK。"
  install_static_overlay "$apk" || abort "! 安装 ${apk##*/} 失败。"
done

set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/config.conf" 0 0 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/common.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm_recursive "$MODPATH/files" 0 0 0755 0644
[ -d "$MODPATH/system" ] && set_perm_recursive "$MODPATH/system" 0 0 0755 0644
"""

    private const val UPDATE_BINARY = $$"""#!/sbin/sh

#################
# Initialization
#################

umask 022

ui_print() { echo "$1"; }

require_new_magisk() {
  ui_print "********************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "********************************"
  exit 1
}

#########################
# Load util_functions.sh
#########################

OUTFD=$2
ZIPFILE=$3

mount /data 2>/dev/null

[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ $MAGISK_VER_CODE -lt 20400 ] && require_new_magisk

install_module
exit 0
"""

    private const val BOOT_SCRIPT = $$"""#!/system/bin/sh
MODDIR=${0%/*}
LOCK=/dev/.wekit-monet-overlay-restore
mkdir "$LOCK" 2>/dev/null || exit 0
trap 'rmdir "$LOCK"' EXIT
. "$MODDIR/common.sh"
until [ "$(getprop sys.boot_completed)" = 1 ]; do
  sleep 2
done
restore_overlays
"""

    private const val SOLID_TAB_PACKAGE = "monet.solidtab.com.tencent.mm"
    private const val BLUR_TAB_PACKAGE = "monet.blurtab.com.tencent.mm"
}
