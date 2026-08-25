#!/system/bin/sh

TARGET_PACKAGE='com.tencent.mm'
ALL_OVERLAY_PACKAGES='monet.com.tencent.mm monet.classicbubble.com.tencent.mm monet.bubblepro.com.tencent.mm monet.multiscenecorners.com.tencent.mm monet.solidtab.com.tencent.mm monet.blurtab.com.tencent.mm'
DATA_USER_DIR=${MONET_DATA_USER_DIR:-/data/user}

log_message() {
  printf '%s\n' "[WeKit Monet] $*" >&2
}

invalid_config() {
  log_message "invalid module config: $1"
  return 1
}

load_config() {
  [ -f "$CONFIG_FILE" ] || {
    invalid_config "missing config.conf"
    return 1
  }

  BUBBLE_STYLE=
  MULTI_SCENE_CORNERS_ENABLED=
  TAB_STYLE=
  USER_SCOPE=
  GENERATED_USER_ID=
  seen_bubble=0
  seen_corners=0
  seen_tab=0
  seen_scope=0
  seen_user=0

  while IFS= read -r config_line || [ -n "$config_line" ]; do
    case "$config_line" in
      bubble_style=*)
        [ "$seen_bubble" -eq 0 ] || {
          invalid_config "duplicate bubble_style"
          return 1
        }
        BUBBLE_STYLE=${config_line#bubble_style=}
        seen_bubble=1
        ;;
      multi_scene_corners_enabled=*)
        [ "$seen_corners" -eq 0 ] || {
          invalid_config "duplicate multi_scene_corners_enabled"
          return 1
        }
        MULTI_SCENE_CORNERS_ENABLED=${config_line#multi_scene_corners_enabled=}
        seen_corners=1
        ;;
      tab_style=*)
        [ "$seen_tab" -eq 0 ] || {
          invalid_config "duplicate tab_style"
          return 1
        }
        TAB_STYLE=${config_line#tab_style=}
        seen_tab=1
        ;;
      user_scope=*)
        [ "$seen_scope" -eq 0 ] || {
          invalid_config "duplicate user_scope"
          return 1
        }
        USER_SCOPE=${config_line#user_scope=}
        seen_scope=1
        ;;
      generated_user_id=*)
        [ "$seen_user" -eq 0 ] || {
          invalid_config "duplicate generated_user_id"
          return 1
        }
        GENERATED_USER_ID=${config_line#generated_user_id=}
        seen_user=1
        ;;
      *)
        invalid_config "unknown or malformed entry"
        return 1
        ;;
    esac
  done < "$CONFIG_FILE"

  [ "$seen_bubble" -eq 1 ] &&
    [ "$seen_corners" -eq 1 ] &&
    [ "$seen_tab" -eq 1 ] &&
    [ "$seen_scope" -eq 1 ] &&
    [ "$seen_user" -eq 1 ] || {
      invalid_config "missing required entry"
      return 1
    }
  case "$BUBBLE_STYLE" in MODERN|CLASSIC|PRO) ;; *) invalid_config "bubble_style"; return 1 ;; esac
  case "$MULTI_SCENE_CORNERS_ENABLED" in true|false) ;; *) invalid_config "multi_scene_corners_enabled"; return 1 ;; esac
  case "$TAB_STYLE" in SOLID|BLUR) ;; *) invalid_config "tab_style"; return 1 ;; esac
  case "$USER_SCOPE" in CURRENT|ALL) ;; *) invalid_config "user_scope"; return 1 ;; esac
  case "$GENERATED_USER_ID" in ''|*[!0-9]*) invalid_config "generated_user_id"; return 1 ;; esac
  case "$GENERATED_USER_ID" in ???????????*) invalid_config "generated_user_id"; return 1 ;; esac
  [ "$GENERATED_USER_ID" -le 2147483647 ] || {
    invalid_config "generated_user_id"
    return 1
  }
}

list_live_user_ids() {
  {
    pm list users 2>/dev/null |
      sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p'
    if [ -d "$DATA_USER_DIR" ]; then
      for user_dir in "$DATA_USER_DIR"/*; do
        [ -d "$user_dir" ] || continue
        user_id=${user_dir##*/}
        case "$user_id" in ''|*[!0-9]*) continue ;; esac
        printf '%s\n' "$user_id"
      done
    fi
  } | sed -n '/^[0-9][0-9]*$/p' | LC_ALL=C sort -n -u
}

user_exists() {
  expected_user_id=$1
  for live_user_id in $(list_live_user_ids); do
    [ "$live_user_id" = "$expected_user_id" ] && return 0
  done
  return 1
}

package_available() {
  package_name=$1
  if pm path "$package_name" >/dev/null 2>&1; then
    return 0
  fi
  log_message "package is unavailable: $package_name"
  return 1
}

set_overlay_state() {
  user_id=$1
  state=$2
  package_name=$3
  package_available "$package_name" || return 0
  if cmd overlay "$state" --user "$user_id" "$package_name" >/dev/null 2>&1; then
    return 0
  fi
  log_message "user $user_id failed to $state $package_name"
  return 1
}

install_existing_for_user() {
  user_id=$1
  package_name=$2
  package_available "$package_name" || return 0
  if pm install-existing --user "$user_id" "$package_name" >/dev/null 2>&1; then
    return 0
  fi
  log_message "user $user_id failed to install existing package $package_name"
  return 1
}

selected_overlay_packages() {
  printf '%s\n' 'monet.com.tencent.mm'
  case "$BUBBLE_STYLE" in
    CLASSIC) printf '%s\n' 'monet.classicbubble.com.tencent.mm' ;;
    PRO) printf '%s\n' 'monet.bubblepro.com.tencent.mm' ;;
  esac
  [ "$MULTI_SCENE_CORNERS_ENABLED" = true ] &&
    printf '%s\n' 'monet.multiscenecorners.com.tencent.mm'
  if [ "$TAB_STYLE" = SOLID ]; then
    printf '%s\n' 'monet.solidtab.com.tencent.mm'
  else
    printf '%s\n' 'monet.blurtab.com.tencent.mm'
  fi
}

restore_user_state() {
  user_id=$1
  user_failed=0

  if [ "$USER_SCOPE" = ALL ]; then
    for package_name in $ALL_OVERLAY_PACKAGES; do
      install_existing_for_user "$user_id" "$package_name" || user_failed=1
    done
  fi
  for package_name in $ALL_OVERLAY_PACKAGES; do
    set_overlay_state "$user_id" disable "$package_name" || user_failed=1
  done
  for package_name in $(selected_overlay_packages); do
    set_overlay_state "$user_id" enable "$package_name" || user_failed=1
  done
  if ! am force-stop --user "$user_id" "$TARGET_PACKAGE" >/dev/null 2>&1; then
    log_message "user $user_id failed to stop $TARGET_PACKAGE"
    user_failed=1
  fi
  [ "$user_failed" -eq 0 ] || {
    log_message "user $user_id restoration failure"
    return 1
  }
}

restore_configured_state() {
  if [ "$USER_SCOPE" = CURRENT ]; then
    if ! user_exists "$GENERATED_USER_ID"; then
      log_message "configured CURRENT user $GENERATED_USER_ID does not exist"
      return 1
    fi
    restore_user_state "$GENERATED_USER_ID"
    return $?
  fi

  target_users=$(list_live_user_ids)
  [ -n "$target_users" ] || {
    log_message "no live Android users found"
    return 1
  }
  failure_count=0
  for user_id in $target_users; do
    if ! restore_user_state "$user_id"; then
      failure_count=$((failure_count + 1))
    fi
  done
  [ "$failure_count" -eq 0 ] || {
    log_message "$failure_count user restoration failure(s)"
    return 1
  }
}
