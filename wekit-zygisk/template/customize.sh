# shellcheck disable=SC2034
SKIPUNZIP=1

# Request hot installation only when the active native loader can be reused.
# Keep native updates staged until reboot, including repeated installs this boot.
export MODULE_HOT_INSTALL_REQUEST=false

if [ "$BOOTMODE" = true ] && [ -n "$KSU" ]; then
  ui_print "- 正在通过 KernelSU 应用安装 / Installing from KernelSU app"
elif [ "$BOOTMODE" = true ] && [ -n "$APATCH" ]; then
  ui_print "- 正在通过 APatch 应用安装 / Installing from APatch app"
elif [ "$BOOTMODE" = true ] && [ -n "$MAGISK_VER_CODE" ]; then
  ui_print "- 正在通过 Magisk 应用安装 / Installing from Magisk app"
else
  abort "! 请通过 Root 管理器应用安装，不支持 Recovery / Install from a root manager app; recovery is not supported"
fi

[ "$ARCH" = arm64 ] || abort "! 不支持的平台 / Unsupported platform: $ARCH"
ui_print "- 正在检查 WeKit 二合一 APK / Checking WeKit dual-format APK"
unzip -t "$ZIPFILE" >/dev/null 2>&1 || abort "! WeKit APK 已损坏 / Corrupt WeKit APK"
unzip -l "$ZIPFILE" > "$TMPDIR/wekit-apk-entries" || abort "! 无法列出 WeKit APK 内容 / Cannot list WeKit APK"

# Use a fixed list so Android resources and DEX are never unpacked here.
for entry in module.prop customize.sh uninstall.sh sepolicy.rule config.sh action.sh \
  webroot/index.html webroot/css/app.css webroot/js/bridge.js \
  webroot/js/app.js webroot/js/kernelsu.js \
  META-INF/com/google/android/update-binary META-INF/com/google/android/updater-script \
  AndroidManifest.xml classes.dex resources.arsc \
  lib/arm64-v8a/libwekit_native.so lib/arm64-v8a/libwekit_zygisk.so
do
  # BusyBox unzip may return success when a requested name matches no entry.
  # All required names are fixed and contain no whitespace.
  awk -v entry="$entry" '$NF == entry { count++ } END { exit count != 1 }' \
    "$TMPDIR/wekit-apk-entries" || abort "! APK 条目缺失或重复 / Missing or duplicate APK entry: $entry"
done
rm -f "$TMPDIR/wekit-apk-entries"

ui_print "- 正在解压模块文件 / Extracting module files"
for entry in module.prop uninstall.sh sepolicy.rule config.sh action.sh \
  webroot/index.html webroot/css/app.css webroot/js/bridge.js \
  webroot/js/app.js webroot/js/kernelsu.js
do
  unzip -o "$ZIPFILE" "$entry" -d "$MODPATH" >/dev/null || abort "! 无法解压 $entry / Cannot extract $entry"
done
mkdir -p "$MODPATH/zygisk" || abort "! 无法创建 Zygisk 目录 / Cannot create Zygisk directory"
unzip -p "$ZIPFILE" lib/arm64-v8a/libwekit_zygisk.so > "$TMPDIR/wekit-zygisk.so" ||
  abort "! 无法解压 Zygisk 原生库 / Cannot extract Zygisk library"

# Compare bytes with the active module, never a previous modules_update candidate.
# Do this before publishing the new library in case MODPATH is the active path.
ACTIVE_MODULE_DIR=/data/adb/modules/wekit_zygisk
if [ ! -f "$ACTIVE_MODULE_DIR/zygisk/arm64-v8a.so" ]; then
  NATIVE_UPDATE=first_install
elif [ -e "$ACTIVE_MODULE_DIR/disable" ] || [ -e "$ACTIVE_MODULE_DIR/remove" ]; then
  NATIVE_UPDATE=inactive
elif cmp -s "$TMPDIR/wekit-zygisk.so" "$ACTIVE_MODULE_DIR/zygisk/arm64-v8a.so"; then
  NATIVE_UPDATE=unchanged
else
  case $? in
    1) NATIVE_UPDATE=changed ;;
    *) NATIVE_UPDATE=unknown ;;
  esac
fi
mv -f "$TMPDIR/wekit-zygisk.so" "$MODPATH/zygisk/arm64-v8a.so" ||
  abort "! 无法写入 Zygisk 原生库 / Cannot publish Zygisk library"

ui_print "- 正在保存原始 WeKit APK / Storing the original WeKit APK"
cp "$ZIPFILE" "$MODPATH/module.apk.tmp" || abort "! 无法复制 WeKit APK / Cannot copy WeKit APK"
mv -f "$MODPATH/module.apk.tmp" "$MODPATH/module.apk" || abort "! 无法写入 WeKit APK / Cannot publish WeKit APK"
# Only remove obsolete files in the installation candidate, never in the active module.
rm -rf "$MODPATH/payload"
rm -f "$MODPATH/post-fs-data.sh" "$MODPATH/service.sh" "$MODPATH/verify.sh"

set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm "$MODPATH/module.apk" 0 0 0644
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/sepolicy.rule" 0 0 0644
set_perm "$MODPATH/config.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
# KernelSU owns the WebUI permissions and SELinux context.

OLD_MODULE_DIR=/data/adb/modules/wekit
OLD_TARGETS_FILE=/data/adb/wekit/injection-targets.tsv
NEW_STATE_DIR=/data/adb/wekit_zygisk
NEW_TARGETS_FILE=$NEW_STATE_DIR/injection-targets.tsv

if [ -f "$OLD_TARGETS_FILE" ] || [ -d "$OLD_MODULE_DIR" ]; then
  ui_print "*********************************************************"
  ui_print "- 正在从旧模块 ID 迁移 / Migrating from old module ID"

  if [ -f "$OLD_TARGETS_FILE" ]; then
    if [ -e "$NEW_TARGETS_FILE" ]; then
      ui_print "- 保留现有注入目标 / Keeping existing injection targets"
    else
      migration_file=$NEW_STATE_DIR/.injection-targets.migrate.$$
      umask 077
      mkdir -p "$NEW_STATE_DIR" ||
        abort "! 无法创建状态目录 / Unable to create state directory: $NEW_STATE_DIR"
      chmod 700 "$NEW_STATE_DIR" ||
        abort "! 无法设置目录权限 / Unable to set permissions on: $NEW_STATE_DIR"
      cp "$OLD_TARGETS_FILE" "$migration_file" || {
        rm -f "$migration_file"
        abort "! 无法复制注入目标 / Unable to copy injection targets"
      }
      chmod 600 "$migration_file" || {
        rm -f "$migration_file"
        abort "! 无法设置已迁移注入目标的权限 / Unable to set permissions on migrated injection targets"
      }
      mv -f "$migration_file" "$NEW_TARGETS_FILE" || {
        rm -f "$migration_file"
        abort "! 无法写入已迁移的注入目标 / Unable to publish migrated injection targets"
      }
      ui_print "- 已迁移注入目标 / Migrated injection targets"
    fi
  else
    ui_print "- 没有需要迁移的注入目标 / No injection targets to migrate"
  fi

  if [ -d "$OLD_MODULE_DIR" ]; then
    touch "$OLD_MODULE_DIR/disable" ||
      abort "! 无法停用旧模块 / Unable to disable old module"
    ui_print "- 已停用旧模块 / Old module disabled"
  fi
  ui_print "*********************************************************"
fi

ui_print "*********************************************************"
case "$NATIVE_UPDATE" in
  unchanged)
    export MODULE_HOT_INSTALL_REQUEST=true
    ui_print "- Zygisk 原生库未变，本次 APK 更新无须重启设备。 / Zygisk native library unchanged; no device reboot is needed for the APK update."
    ui_print "- 更新激活后，请完全结束微信的所有进程并重新启动微信。 / After activation, fully stop all WeChat processes and restart WeChat."
    ui_print "- 若 Root 管理器仍将更新保留为待重启状态，请按其要求重启设备。 / If your root manager keeps this update pending, reboot as it requires."
    ;;
  changed)
    ui_print "! Zygisk 原生库已更新，请重启设备以加载新版本。 / Zygisk native library updated; reboot your device to load it."
    ;;
  first_install)
    ui_print "! 首次安装：请重启设备以加载 Zygisk 原生库。 / First installation: reboot your device to load the Zygisk native library."
    ;;
  inactive)
    ui_print "! 已安装的模块已停用或待移除，请启用模块并重启设备。 / The installed module is disabled or pending removal; enable it and reboot your device."
    ;;
  unknown)
    ui_print "! 无法比较 Zygisk 原生库，请重启设备以加载更新。 / Cannot compare Zygisk native libraries; reboot your device to load the update."
    ;;
esac
ui_print "*********************************************************"
