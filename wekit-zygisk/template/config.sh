#!/system/bin/sh

# Persistent selection state intentionally lives outside the module directory.
# Module upgrades replace /data/adb/modules/wekit, while this file must retain
# the user's per-Android-user choices across upgrades.
STATE_DIR=/data/adb/wekit
TARGETS_FILE=$STATE_DIR/injection-targets.tsv
LOCK_DIR=$STATE_DIR/.injection-targets.lock
LOCK_RETRIES=10
TARGET_PACKAGE=com.tencent.mm

# Every temporary replacement file is published with rename(2). Set this before
# any redirection so it is private even during construction.
umask 077

ensure_state_dir() {
  umask 077
  mkdir -p "$STATE_DIR" || return 1
  chmod 700 "$STATE_DIR" || return 1
}

# Android module shells do not consistently ship flock(1). mkdir(2) is atomic,
# so use a private lock directory to serialize read-modify-publish operations.
# A killed WebUI command can leave a stale lock; the next command verifies the
# recorded PID and safely reclaims it.
release_targets_lock() {
  [ -r "$LOCK_DIR/pid" ] || return 0
  IFS= read -r lock_owner < "$LOCK_DIR/pid" || return 0
  [ "$lock_owner" = "$$" ] || return 0
  rm -f "$LOCK_DIR/pid"
  rmdir "$LOCK_DIR" 2>/dev/null || true
}

acquire_targets_lock() {
  ensure_state_dir || return 1
  lock_attempt=0
  while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    if [ -r "$LOCK_DIR/pid" ]; then
      IFS= read -r lock_owner < "$LOCK_DIR/pid" || lock_owner=
      case "$lock_owner" in
        ''|*[!0-9]*)
          if [ "$lock_attempt" -ge 2 ]; then
            rm -f "$LOCK_DIR/pid"
            rmdir "$LOCK_DIR" 2>/dev/null && continue
          fi
          ;;
        *)
          if ! kill -0 "$lock_owner" 2>/dev/null; then
            rm -f "$LOCK_DIR/pid"
            rmdir "$LOCK_DIR" 2>/dev/null || true
            continue
          fi
          ;;
      esac
    elif [ "$lock_attempt" -ge 2 ]; then
      # Do not race the owner between mkdir and pid creation; only reclaim an
      # empty lock after it has remained incomplete for multiple seconds.
      rmdir "$LOCK_DIR" 2>/dev/null && continue
    fi

    lock_attempt=$((lock_attempt + 1))
    if [ "$lock_attempt" -ge "$LOCK_RETRIES" ]; then
      echo "timed out waiting for WeKit target configuration lock" >&2
      return 1
    fi
    sleep 1
  done

  printf '%s\n' "$$" > "$LOCK_DIR/pid" || {
    rmdir "$LOCK_DIR" 2>/dev/null || true
    return 1
  }
}

run_with_targets_lock() {
  acquire_targets_lock || return 1
  "$@"
  status=$?
  release_targets_lock
  return "$status"
}

list_user_ids() {
  users=$(cmd user list 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')
  if [ -n "$users" ]; then
    printf '%s\n' "$users"
  else
    # AOSP devices always have user 0. Keep the UI usable on older builds whose
    # user-service command is unavailable to the root shell.
    printf '%s\n' 0
  fi
}

is_valid_user_id() {
  case "$1" in
    ''|*[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}

is_valid_package() {
  case "$1" in
    ''|.*|*.|*..*|*[!A-Za-z0-9._]*) return 1 ;;
    *.*) return 0 ;;
    *) return 1 ;;
  esac
}

is_installed_for_user() {
  user_id=$1
  package_name=$2
  cmd package list packages --user "$user_id" 2>/dev/null |
    sed -n 's/^package://p' |
    grep -F -x "$package_name" >/dev/null 2>&1
}

write_default_header() {
  printf '%s\n' '# WeKit Zygisk injection targets v1'
  printf '%s\n' '# userId<TAB>packageName<TAB>enabled'
}

write_scan_result_locked() {
  ensure_state_dir || return 1
  temp_file=$TARGETS_FILE.tmp.$$
  (
    umask 077
    write_default_header
    for user_id in $(list_user_ids); do
      if is_installed_for_user "$user_id" "$TARGET_PACKAGE"; then
        printf '%s\t%s\t0\n' "$user_id" "$TARGET_PACKAGE"
      fi
    done
  ) > "$temp_file" || {
    rm -f "$temp_file"
    return 1
  }
  chmod 600 "$temp_file" || {
    rm -f "$temp_file"
    return 1
  }
  mv -f "$temp_file" "$TARGETS_FILE"
}

ensure_initialized_locked() {
  if [ ! -f "$TARGETS_FILE" ]; then
    write_scan_result_locked
  fi
}

list_targets_locked() {
  ensure_initialized_locked || return 1
  awk -F '\t' 'NF == 3 && $1 ~ /^[0-9]+$/ && $2 != "" && ($3 == "0" || $3 == "1") { print $1 "\t" $2 "\t" $3 }' "$TARGETS_FILE"
}

list_targets() {
  run_with_targets_lock list_targets_locked
}

list_apps() {
  for user_id in $(list_user_ids); do
    cmd package list packages --user "$user_id" 2>/dev/null |
      sed -n 's/^package://p' |
      while IFS= read -r package_name; do
        [ -n "$package_name" ] && printf '%s\t%s\n' "$user_id" "$package_name"
      done
  done
}

add_target_locked() {
  user_id=$1
  package_name=$2
  ensure_initialized_locked || return 1
  is_installed_for_user "$user_id" "$package_name" || return 3

  if awk -F '\t' -v user="$user_id" -v package="$package_name" \
    '$1 == user && $2 == package { found = 1 } END { exit found ? 0 : 1 }' "$TARGETS_FILE"; then
    return 0
  fi

  temp_file=$TARGETS_FILE.tmp.$$
  (
    cat "$TARGETS_FILE"
    printf '%s\t%s\t0\n' "$user_id" "$package_name"
  ) > "$temp_file" || return 1
  chmod 600 "$temp_file" && mv -f "$temp_file" "$TARGETS_FILE"
}

add_target() {
  user_id=$1
  package_name=$2
  is_valid_user_id "$user_id" && is_valid_package "$package_name" || return 2
  if [ "$package_name" != "$TARGET_PACKAGE" ]; then
    echo "WeKit Zygisk currently supports only $TARGET_PACKAGE" >&2
    return 4
  fi
  run_with_targets_lock add_target_locked "$user_id" "$package_name"
}

set_enabled_locked() {
  user_id=$1
  package_name=$2
  enabled=$3
  ensure_initialized_locked || return 1

  temp_file=$TARGETS_FILE.tmp.$$
  awk -F '\t' -v OFS='\t' -v user="$user_id" -v package="$package_name" -v enabled="$enabled" '
    $1 == user && $2 == package { print $1, $2, enabled; found = 1; next }
    { print }
    END { exit found ? 0 : 4 }
  ' "$TARGETS_FILE" > "$temp_file"
  status=$?
  if [ "$status" -ne 0 ]; then
    rm -f "$temp_file"
    return "$status"
  fi
  chmod 600 "$temp_file" && mv -f "$temp_file" "$TARGETS_FILE"
}

set_enabled() {
  user_id=$1
  package_name=$2
  enabled=$3
  is_valid_user_id "$user_id" && is_valid_package "$package_name" || return 2
  if [ "$package_name" != "$TARGET_PACKAGE" ]; then
    echo "WeKit Zygisk currently supports only $TARGET_PACKAGE" >&2
    return 4
  fi
  [ "$enabled" = 0 ] || [ "$enabled" = 1 ] || return 2
  run_with_targets_lock set_enabled_locked "$user_id" "$package_name" "$enabled"
}

delete_target_locked() {
  user_id=$1
  package_name=$2
  ensure_initialized_locked || return 1

  temp_file=$TARGETS_FILE.tmp.$$
  awk -F '\t' -v user="$user_id" -v package="$package_name" '
    $1 == user && $2 == package { found = 1; next }
    { print }
    END { exit found ? 0 : 4 }
  ' "$TARGETS_FILE" > "$temp_file"
  status=$?
  if [ "$status" -ne 0 ]; then
    rm -f "$temp_file"
    return "$status"
  fi
  chmod 600 "$temp_file" && mv -f "$temp_file" "$TARGETS_FILE"
}

delete_target() {
  user_id=$1
  package_name=$2
  is_valid_user_id "$user_id" && is_valid_package "$package_name" || return 2
  # Keep legacy non-WeChat rows removable after upgrading from an older module
  # version, even though they are no longer eligible to be enabled.
  run_with_targets_lock delete_target_locked "$user_id" "$package_name"
}

reset_targets_locked() {
  write_scan_result_locked
}

reset_targets() {
  run_with_targets_lock reset_targets_locked
}

case "$1" in
  list) list_targets ;;
  apps) list_apps ;;
  add) add_target "$2" "$3" ;;
  set) set_enabled "$2" "$3" "$4" ;;
  delete) delete_target "$2" "$3" ;;
  reset) reset_targets ;;
  *)
    echo "Usage: $0 {list|apps|add|set|delete|reset}" >&2
    exit 64
    ;;
esac
