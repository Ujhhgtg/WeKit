#!/system/bin/sh

MODDIR=${0%/*}
while [ "$(getprop sys.boot_completed 2>/dev/null)" != 1 ]; do
  sleep 2
done

BOOT_STATE_DIR=${MONET_BOOT_STATE_DIR:-/dev}
case "$BOOT_STATE_DIR" in
  /*) ;;
  *)
    printf '%s\n' '[WeKit Monet] invalid boot state directory' >&2
    exit 1
    ;;
esac
[ -d "$BOOT_STATE_DIR" ] || {
  printf '%s\n' '[WeKit Monet] boot state directory is unavailable' >&2
  exit 1
}
BOOT_LOCK_DIR="$BOOT_STATE_DIR/.wekit-monet-engine-boot.lock"
BOOT_DONE_FILE="$BOOT_STATE_DIR/.wekit-monet-engine-boot.done"
BOOT_LOCK_OWNER=0

release_boot_lock() {
  if [ "$BOOT_LOCK_OWNER" -eq 1 ]; then
    rmdir "$BOOT_LOCK_DIR" 2>/dev/null
    BOOT_LOCK_OWNER=0
  fi
}

acquire_boot_lock() {
  [ ! -f "$BOOT_DONE_FILE" ] || return 1
  lock_attempt=0
  while ! mkdir "$BOOT_LOCK_DIR" 2>/dev/null; do
    [ ! -f "$BOOT_DONE_FILE" ] || return 1
    lock_attempt=$((lock_attempt + 1))
    if [ "$lock_attempt" -ge 60 ]; then
      printf '%s\n' '[WeKit Monet] timed out waiting for boot restoration lock' >&2
      return 2
    fi
    sleep 1
  done
  BOOT_LOCK_OWNER=1
  if [ -f "$BOOT_DONE_FILE" ]; then
    release_boot_lock
    return 1
  fi
}

acquire_boot_lock
lock_status=$?
case "$lock_status" in
  0) ;;
  1) exit 0 ;;
  *) exit "$lock_status" ;;
esac
# A failed owner releases the lock without publishing the marker, allowing another
# root-manager callback to retry. A successful owner publishes once for this boot.
trap 'release_boot_lock' 0
trap 'exit 1' 1 2 15

. "$MODDIR/common.sh" || exit 1
CONFIG_FILE="$MODDIR/config.conf"
load_config || exit 1
restore_configured_state
restore_status=$?
[ "$restore_status" -eq 0 ] || exit "$restore_status"
: > "$BOOT_DONE_FILE" || {
  log_message "failed to publish boot restoration completion marker"
  exit 1
}
