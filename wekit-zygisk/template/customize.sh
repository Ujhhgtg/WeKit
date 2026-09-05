# shellcheck disable=SC2034
SKIPUNZIP=1

# Ask root managers that implement the hot-install protocol to activate this
# update immediately. Managers that do not recognize it ignore the request.
export MODULE_HOT_INSTALL_REQUEST=true

if [ "$BOOTMODE" = true ] && [ -n "$KSU" ]; then
  ui_print "- Installing from KernelSU app"
elif [ "$BOOTMODE" = true ] && [ -n "$APATCH" ]; then
  ui_print "- Installing from APatch app"
elif [ "$BOOTMODE" = true ] && [ -n "$MAGISK_VER_CODE" ]; then
  ui_print "- Installing from Magisk app"
else
  abort "! Install from a root manager app; recovery is not supported"
fi

[ "$ARCH" = arm64 ] || abort "! Unsupported platform: $ARCH"
ui_print "- Checking WeKit dual-format APK"
unzip -t "$ZIPFILE" >/dev/null 2>&1 || abort "! Corrupt WeKit APK"
unzip -l "$ZIPFILE" > "$TMPDIR/wekit-apk-entries" || abort "! Cannot list WeKit APK"

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
    "$TMPDIR/wekit-apk-entries" || abort "! Missing or duplicate APK entry: $entry"
done
rm -f "$TMPDIR/wekit-apk-entries"

ui_print "- Extracting module files"
for entry in module.prop uninstall.sh sepolicy.rule config.sh action.sh \
  webroot/index.html webroot/css/app.css webroot/js/bridge.js \
  webroot/js/app.js webroot/js/kernelsu.js
do
  unzip -o "$ZIPFILE" "$entry" -d "$MODPATH" >&2 || abort "! Cannot extract $entry"
done
mkdir -p "$MODPATH/zygisk" || abort "! Cannot create Zygisk directory"
unzip -p "$ZIPFILE" lib/arm64-v8a/libwekit_zygisk.so > "$MODPATH/zygisk/arm64-v8a.so" ||
  abort "! Cannot extract Zygisk library"

ui_print "- Storing the original WeKit APK"
cp "$ZIPFILE" "$MODPATH/module.apk.tmp" || abort "! Cannot copy WeKit APK"
mv -f "$MODPATH/module.apk.tmp" "$MODPATH/module.apk" || abort "! Cannot publish WeKit APK"
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
  ui_print "- Migrating from old module ID"

  if [ -f "$OLD_TARGETS_FILE" ]; then
    if [ -e "$NEW_TARGETS_FILE" ]; then
      ui_print "- Keeping existing injection targets"
    else
      migration_file=$NEW_STATE_DIR/.injection-targets.migrate.$$
      umask 077
      mkdir -p "$NEW_STATE_DIR" ||
        abort "! Unable to create state directory: $NEW_STATE_DIR"
      chmod 700 "$NEW_STATE_DIR" ||
        abort "! Unable to set permissions on: $NEW_STATE_DIR"
      cp "$OLD_TARGETS_FILE" "$migration_file" || {
        rm -f "$migration_file"
        abort "! Unable to copy injection targets"
      }
      chmod 600 "$migration_file" || {
        rm -f "$migration_file"
        abort "! Unable to set permissions on migrated injection targets"
      }
      mv -f "$migration_file" "$NEW_TARGETS_FILE" || {
        rm -f "$migration_file"
        abort "! Unable to publish migrated injection targets"
      }
      ui_print "- Migrated injection targets"
    fi
  else
    ui_print "- No injection targets to migrate"
  fi

  if [ -d "$OLD_MODULE_DIR" ]; then
    touch "$OLD_MODULE_DIR/disable" ||
      abort "! Unable to disable old module"
    ui_print "- Old module disabled"
  fi
  ui_print "*********************************************************"
fi
