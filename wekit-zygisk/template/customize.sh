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
  if [ "$(which magisk)" ]; then
    ui_print "*********************************************************"
    ui_print "! Multiple root implementation is NOT supported!"
    ui_print "! Please uninstall Magisk before installing Zygisk Next"
    abort    "*********************************************************"
  fi
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
extract "$ZIPFILE" 'uninstall.sh'    "$MODPATH"
extract "$ZIPFILE" 'webroot/index.html'       "$MODPATH"
extract "$ZIPFILE" 'webroot/css/app.css'      "$MODPATH"
extract "$ZIPFILE" 'webroot/js/bridge.js'     "$MODPATH"
extract "$ZIPFILE" 'webroot/js/app.js'        "$MODPATH"
mv "$TMPDIR/sepolicy.rule" "$MODPATH"

HAS32BIT=false
if [ -n "$(getprop ro.product.cpu.abilist32)" ] || [ -n "$(getprop ro.system.product.cpu.abilist32)" ]; then
  HAS32BIT=true
fi

mkdir "$MODPATH/zygisk"

if [ "$ARCH" = "arm64" ]; then
  if [ "$HAS32BIT" = true ]; then
    extract "$ZIPFILE" "lib/armeabi-v7a/lib$SONAME.so" "$MODPATH/zygisk" true
    mv "$MODPATH/zygisk/lib$SONAME.so" "$MODPATH/zygisk/armeabi-v7a.so"
  fi

  ui_print "- Extracting arm64 libraries"
  extract "$ZIPFILE" "lib/arm64-v8a/lib$SONAME.so" "$MODPATH/zygisk" true
  mv "$MODPATH/zygisk/lib$SONAME.so" "$MODPATH/zygisk/arm64-v8a.so"
elif [ "$ARCH" = "arm" ]; then
  ui_print "- Extracting arm libraries"
  extract "$ZIPFILE" "lib/armeabi-v7a/lib$SONAME.so" "$MODPATH/zygisk" true
  mv "$MODPATH/zygisk/lib$SONAME.so" "$MODPATH/zygisk/armeabi-v7a.so"
else
  abort "! Unsupported platform: $ARCH"
fi

# Extract the APK and every DEX entry required by the FunBox-style
# InMemoryDexClassLoader bootstrap. Both ABI payloads are retained on arm64
# devices because a 32-bit WeChat process can still be specialized there.
ui_print "- Extracting WeKit payload"
mkdir -p "$MODPATH/payload"
for abi in arm64-v8a armeabi-v7a
do
  payload_dir="payload/$abi"
  extract "$ZIPFILE" "$payload_dir/wekit.apk" "$MODPATH"
  extract "$ZIPFILE" "$payload_dir/dex.list" "$MODPATH"
  while IFS= read -r dex_name || [ -n "$dex_name" ]
  do
    case "$dex_name" in
      classes.dex) ;;
      classes[0-9]*.dex)
        dex_index=${dex_name#classes}
        dex_index=${dex_index%.dex}
        case "$dex_index" in
          ''|*[!0-9]*) abort "! Invalid DEX payload entry: $dex_name" ;;
        esac
        ;;
      *) abort "! Invalid DEX payload entry: $dex_name" ;;
    esac
    extract "$ZIPFILE" "$payload_dir/$dex_name" "$MODPATH"
  done < "$MODPATH/$payload_dir/dex.list"
  ui_print "  WeKit payload installed to $MODPATH/$payload_dir"
done

ui_print "- Setting permissions"
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm_recursive "$MODPATH/payload" 0 0 0755 0644
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/config.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

# KernelSU assigns the WebUI directory's mode and SELinux context itself.
# Do not include $MODPATH/webroot in a recursive set_perm call.
