# shellcheck disable=SC2034
SKIPUNZIP=1

# Ask root managers that implement the hot-install protocol to activate this
# update immediately. Managers that do not recognize it ignore the request.
export MODULE_HOT_INSTALL_REQUEST=true

DEBUG=@DEBUG@
SONAME=@SONAME@
SUPPORTED_ABIS="@SUPPORTED_ABIS@"

if [ "$BOOTMODE" ] && [ "$KSU" ]; then
  ui_print "- Installing from KernelSU app"
  ui_print "- KernelSU version: $KSU_KERNEL_VER_CODE (kernel) + $KSU_VER_CODE (ksud)"
elif [ "$BOOTMODE" ] && [ "$MAGISK_VER_CODE" ]; then
  ui_print "- Installing from Magisk app"
else
  ui_print "*********************************************************"
  ui_print "! Install from recovery is not supported"
  ui_print "! Please install from KernelSU or Magisk app"
  abort    "*********************************************************"
fi

VERSION=$(grep_prop version "${TMPDIR}/module.prop")
ui_print "- Installing $SONAME $VERSION"

# check architecture
support=false
for abi in $SUPPORTED_ABIS
do
  if [ "$ARCH" == "$abi" ]; then
    support=true
  fi
done
if [ "$support" == "false" ]; then
  abort "! Unsupported platform: $ARCH"
else
  ui_print "- Device platform: $ARCH"
fi

ui_print "- Extracting verify.sh"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2
if [ ! -f "$TMPDIR/verify.sh" ]; then
  ui_print "*********************************************************"
  ui_print "! Unable to extract verify.sh!"
  ui_print "! This zip may be corrupted, please try downloading again"
  abort    "*********************************************************"
fi
. "$TMPDIR/verify.sh"
extract "$ZIPFILE" 'customize.sh'  "$TMPDIR/.vunzip"
extract "$ZIPFILE" 'verify.sh'     "$TMPDIR/.vunzip"
extract "$ZIPFILE" 'sepolicy.rule' "$TMPDIR"

ui_print "- Extracting module files"
extract "$ZIPFILE" 'module.prop'     "$MODPATH"
extract "$ZIPFILE" 'post-fs-data.sh' "$MODPATH"
extract "$ZIPFILE" 'service.sh'      "$MODPATH"
extract "$ZIPFILE" 'config.sh'       "$MODPATH"
extract "$ZIPFILE" 'action.sh'       "$MODPATH"
extract "$ZIPFILE" 'uninstall.sh'    "$MODPATH"
extract "$ZIPFILE" 'webroot/index.html'       "$MODPATH"
extract "$ZIPFILE" 'webroot/css/app.css'      "$MODPATH"
extract "$ZIPFILE" 'webroot/js/bridge.js'     "$MODPATH"
extract "$ZIPFILE" 'webroot/js/app.js'        "$MODPATH"
extract "$ZIPFILE" 'webroot/js/kernelsu.js'   "$MODPATH"
mv "$TMPDIR/sepolicy.rule" "$MODPATH"

mkdir "$MODPATH/zygisk"

ui_print "- Extracting arm64 libraries"
extract "$ZIPFILE" "lib/arm64-v8a/lib$SONAME.so" "$MODPATH/zygisk" true
mv "$MODPATH/zygisk/lib$SONAME.so" "$MODPATH/zygisk/arm64-v8a.so"

# Extract each APK, then derive the DEX payload required by the
# InMemoryDexClassLoader bootstrap. Keeping DEX only inside the APK avoids
# storing the same bytes twice in the module ZIP.
extract_payload_dex() {
  payload_apk=$1
  installed_payload_dir=$2

  # A hot update may replace an APK with fewer DEX files. Remove all derived
  # files first so a stale classesN.dex cannot remain loadable.
  rm -f "$installed_payload_dir"/classes*.dex "$installed_payload_dir/dex.list"
  unzip -o "$payload_apk" 'classes*.dex' -d "$installed_payload_dir" >&2 ||
    abort "! Unable to extract DEX payload from $payload_apk"

  dex_max=0
  for dex_path in "$installed_payload_dir"/classes*.dex
  do
    [ -f "$dex_path" ] || continue
    dex_name=${dex_path##*/}
    case "$dex_name" in
      classes.dex)
        dex_number=1
        ;;
      classes[0-9]*.dex)
        dex_number=${dex_name#classes}
        dex_number=${dex_number%.dex}
        case "$dex_number" in
          ''|*[!0-9]*|0*|1) abort "! Invalid DEX payload entry: $dex_name" ;;
        esac
        ;;
      *)
        abort "! Invalid DEX payload entry: $dex_name"
        ;;
    esac
    if [ "$dex_number" -gt "$dex_max" ]; then
      dex_max=$dex_number
    fi
  done

  [ "$dex_max" -ge 1 ] || abort "! APK does not contain classes.dex: $payload_apk"
  : > "$installed_payload_dir/dex.list" ||
    abort "! Unable to create DEX list for $payload_apk"
  dex_number=1
  while [ "$dex_number" -le "$dex_max" ]
  do
    if [ "$dex_number" -eq 1 ]; then
      dex_name=classes.dex
    else
      dex_name="classes$dex_number.dex"
    fi
    [ -f "$installed_payload_dir/$dex_name" ] ||
      abort "! APK has a non-contiguous classes*.dex sequence: $payload_apk"
    printf '%s\n' "$dex_name" >> "$installed_payload_dir/dex.list" ||
      abort "! Unable to write DEX list for $payload_apk"
    dex_number=$((dex_number + 1))
  done
}

ui_print "- Extracting WeKit payload"
mkdir -p "$MODPATH/payload"
extract "$ZIPFILE" "payload/wekit.apk" "$MODPATH"
extract_payload_dex "$MODPATH/payload/wekit.apk" "$MODPATH/payload"
ui_print "  WeKit payload installed to $MODPATH/payload"

ui_print "- Setting permissions"
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm_recursive "$MODPATH/payload" 0 0 0755 0644
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/config.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

# KernelSU assigns the WebUI directory's mode and SELinux context itself.
# Do not include $MODPATH/webroot in a recursive set_perm call.

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
