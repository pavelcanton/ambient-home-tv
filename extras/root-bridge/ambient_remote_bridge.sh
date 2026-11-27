#!/system/bin/sh
# ambient_remote_bridge.sh — optional privileged FIFO bridge daemon for Ambient Home.
# Launched by a native init service (ambient_remote_bridge.rc) or root boot script.
# Blocks on Ambient Home's private 0600 FIFO (0.0% idle CPU) and executes ONLY
# strictly allowlisted, shell-expansion-free commands. Never uses eval or /data/local/tmp.

export LC_ALL=C

if [ "${REMOTE_BRIDGE_TEST_MODE:-0}" = "1" ]; then
  APP_DIR="${REMOTE_BRIDGE_APP_DIR:-/data/data/com.will.ambienthome}"
  FIFO="${REMOTE_BRIDGE_FIFO:-$APP_DIR/files/remote.fifo}"
  LOG="${REMOTE_BRIDGE_LOG:-/data/adb/ambient-remote-bridge.log}"
else
  APP_DIR="/data/data/com.will.ambienthome"
  FIFO="/data/data/com.will.ambienthome/files/remote.fifo"
  LOG="/data/adb/ambient-remote-bridge.log"
fi

CR=$(printf '\r')

now_ts() {
  date '+%F %T' 2>/dev/null
}

rotate_bridge_log() {
  [ ! -L "$LOG" ] || return 1
  [ -f "$LOG" ] || return 0
  _rb_lines=$(wc -l < "$LOG" 2>/dev/null)
  case "$_rb_lines" in
    ''|*[!0-9]*) _rb_lines=0 ;;
  esac
  if [ "$_rb_lines" -gt 200 ]; then
    _rb_tmp="$LOG.$$.tmp"
    rm -f "$_rb_tmp" 2>/dev/null
    if [ -e "$_rb_tmp" ] || [ -L "$_rb_tmp" ]; then
      return 1
    fi
    if (umask 077; tail -n 100 "$LOG" > "$_rb_tmp") 2>/dev/null && \
       chmod 600 "$_rb_tmp" 2>/dev/null && \
       [ ! -L "$LOG" ] && \
       mv "$_rb_tmp" "$LOG" 2>/dev/null; then
      return 0
    fi
    rm -f "$_rb_tmp" 2>/dev/null
    return 1
  fi
  return 0
}

log_bridge() {
  [ ! -L "$LOG" ] || return 1
  _lb_dir=$(dirname "$LOG")
  if [ ! -d "$_lb_dir" ]; then
    mkdir -p "$_lb_dir" 2>/dev/null && chmod 700 "$_lb_dir" 2>/dev/null
  fi
  (umask 077; printf '%s %s\n' "$(now_ts)" "$1" >> "$LOG") 2>/dev/null || return 1
  chmod 600 "$LOG" 2>/dev/null
  rotate_bridge_log
}

run_input() {
  if [ "${REMOTE_BRIDGE_TEST_MODE:-0}" = "1" ] && [ -n "${REMOTE_BRIDGE_INPUT_CMD:-}" ]; then
    "$REMOTE_BRIDGE_INPUT_CMD" "$@"
  else
    input "$@"
  fi
}

run_am() {
  if [ "${REMOTE_BRIDGE_TEST_MODE:-0}" = "1" ] && [ -n "${REMOTE_BRIDGE_AM_CMD:-}" ]; then
    "$REMOTE_BRIDGE_AM_CMD" "$@"
  else
    am "$@"
  fi
}

run_cmd_display() {
  if [ "${REMOTE_BRIDGE_TEST_MODE:-0}" = "1" ] && [ -n "${REMOTE_BRIDGE_CMD_DISPLAY:-}" ]; then
    "$REMOTE_BRIDGE_CMD_DISPLAY" "$@"
  else
    cmd display "$@"
  fi
}

run_sm() {
  if [ "${REMOTE_BRIDGE_TEST_MODE:-0}" = "1" ] && [ -n "${REMOTE_BRIDGE_SM_CMD:-}" ]; then
    "$REMOTE_BRIDGE_SM_CMD" "$@"
  else
    sm "$@"
  fi
}

run_chown() {
  if [ "${REMOTE_BRIDGE_TEST_MODE:-0}" = "1" ]; then
    if [ -n "${REMOTE_BRIDGE_CHOWN_CMD:-}" ]; then
      "$REMOTE_BRIDGE_CHOWN_CMD" "$@"
    elif [ "$(id -u)" -eq 0 ]; then
      chown "$@"
    else
      return 0
    fi
  else
    chown "$@"
  fi
}

run_chcon() {
  if [ "${REMOTE_BRIDGE_TEST_MODE:-0}" = "1" ]; then
    if [ -n "${REMOTE_BRIDGE_CHCON_CMD:-}" ]; then
      "$REMOTE_BRIDGE_CHCON_CMD" "$@"
    else
      return 0
    fi
  else
    if command -v chcon >/dev/null 2>&1; then
      chcon "$@" >/dev/null 2>&1
    fi
  fi
}

get_selinux_context() {
  _sc_path="$1"
  _sc_val=$(stat -c '%C' "$_sc_path" 2>/dev/null)
  case "$_sc_val" in
    u:object_r:*)
      printf '%s\n' "$_sc_val"
      return 0
      ;;
  esac
  _sc_val=$(ls -Zd "$_sc_path" 2>/dev/null | awk '{print $1}')
  case "$_sc_val" in
    u:object_r:*)
      printf '%s\n' "$_sc_val"
      return 0
      ;;
  esac
  return 1
}

ensure_fifo() {
  if [ ! -d "$APP_DIR" ] || [ -L "$APP_DIR" ]; then
    return 1
  fi

  _ef_files=$(dirname "$FIFO")
  if [ -L "$_ef_files" ]; then
    log_bridge "ERR refusing symlink files dir"
    return 1
  fi

  _ef_owner=$(stat -c '%u:%g' "$APP_DIR" 2>/dev/null)
  case "$_ef_owner" in
    [0-9]*:[0-9]*) ;;
    *)
      log_bridge "ERR cannot resolve app uid:gid"
      return 1
      ;;
  esac
  if [ "${REMOTE_BRIDGE_TEST_MODE:-0}" != "1" ] && [ "$_ef_owner" = "0:0" ]; then
    log_bridge "ERR refusing root-owned app dir"
    return 1
  fi

  _ef_ctx=""
  if [ -d "$_ef_files" ]; then
    _ef_ctx=$(get_selinux_context "$_ef_files")
  fi
  if [ -z "$_ef_ctx" ]; then
    _ef_ctx=$(get_selinux_context "$APP_DIR")
  fi

  if [ ! -d "$_ef_files" ]; then
    (umask 077; mkdir -p -m 700 "$_ef_files") 2>/dev/null || return 1
    [ -d "$_ef_files" ] && [ ! -L "$_ef_files" ] || return 1
    run_chown -h "$_ef_owner" "$_ef_files" || return 1
    _ef_dir_mode=$(stat -c '%a' "$_ef_files" 2>/dev/null)
    if [ "$_ef_dir_mode" != "700" ]; then
      [ -d "$_ef_files" ] && [ ! -L "$_ef_files" ] || return 1
      chmod 700 "$_ef_files" 2>/dev/null || return 1
    fi
    [ -n "$_ef_ctx" ] && run_chcon -h "$_ef_ctx" "$_ef_files"
  fi

  if [ -L "$FIFO" ]; then
    log_bridge "WARN removing symlink at fifo path"
    rm -f "$FIFO" 2>/dev/null || return 1
  elif [ -e "$FIFO" ] && [ ! -p "$FIFO" ]; then
    log_bridge "WARN removing non-fifo at fifo path"
    rm -f "$FIFO" 2>/dev/null || return 1
  fi

  if [ ! -p "$FIFO" ]; then
    (umask 077; mkfifo -m 0600 "$FIFO") 2>/dev/null || return 1
  fi

  [ -p "$FIFO" ] && [ ! -L "$FIFO" ] || return 1
  run_chown -h "$_ef_owner" "$FIFO" || return 1
  _ef_mode=$(stat -c '%a' "$FIFO" 2>/dev/null)
  if [ "$_ef_mode" != "600" ]; then
    [ -p "$FIFO" ] && [ ! -L "$FIFO" ] || return 1
    chmod 0600 "$FIFO" 2>/dev/null || return 1
  fi
  [ -n "$_ef_ctx" ] && run_chcon -h "$_ef_ctx" "$FIFO"
  [ -p "$FIFO" ] && [ ! -L "$FIFO" ]
}

handle_afr_arg() {
  _ha_arg="$1"
  case "$_ha_arg" in
    0|auto)
      run_cmd_display clear-user-preferred-display-mode >/dev/null 2>&1
      return $?
      ;;
    24|25|30|50|60)
      run_cmd_display set-user-preferred-display-mode 1920 1080 "$_ha_arg" >/dev/null 2>&1
      return $?
      ;;
    *" "*" "*)
      _ha_w=${_ha_arg%%" "*}
      _ha_rest=${_ha_arg#*" "}
      _ha_h=${_ha_rest%%" "*}
      _ha_fps=${_ha_rest#*" "}
      case "$_ha_fps" in
        *" "*) return 1 ;;
      esac
      case "$_ha_w" in
        ''|0*|*[!0-9]*) return 1 ;;
      esac
      case "$_ha_h" in
        ''|0*|*[!0-9]*) return 1 ;;
      esac
      [ "${#_ha_w}" -le 4 ] && [ "${#_ha_h}" -le 4 ] || return 1
      [ "$_ha_w" -ge 640 ] && [ "$_ha_w" -le 4096 ] || return 1
      [ "$_ha_h" -ge 480 ] && [ "$_ha_h" -le 2160 ] || return 1
      case "$_ha_fps" in
        *.*.*) return 1 ;;
        *.*)
          _ha_fi=${_ha_fps%%.*}
          _ha_ff=${_ha_fps#*.}
          case "$_ha_ff" in
            ''|*[!0-9]*) return 1 ;;
          esac
          [ "${#_ha_ff}" -le 6 ] || return 1
          ;;
        *)
          _ha_fi="$_ha_fps"
          ;;
      esac
      case "$_ha_fi" in
        ''|0*|*[!0-9]*) return 1 ;;
      esac
      [ "${#_ha_fi}" -le 3 ] || return 1
      [ "$_ha_fi" -ge 23 ] && [ "$_ha_fi" -le 120 ] || return 1
      run_cmd_display set-user-preferred-display-mode "$_ha_w" "$_ha_h" "$_ha_fps" >/dev/null 2>&1
      return $?
      ;;
    *)
      return 1
      ;;
  esac
}

dispatch_command() {
  _dc_line="$1"
  case "$_dc_line" in
    *"$CR") _dc_line=${_dc_line%"$CR"} ;;
  esac

  [ -n "$_dc_line" ] || return 0
  if [ "${#_dc_line}" -gt 128 ]; then
    log_bridge "REJECT oversized line len=${#_dc_line}"
    return 1
  fi

  case "$_dc_line" in
    *" "*)
      _dc_cmd=${_dc_line%%" "*}
      _dc_arg=${_dc_line#*" "}
      ;;
    *)
      _dc_cmd="$_dc_line"
      _dc_arg=""
      ;;
  esac

  case "$_dc_cmd" in
    KEY)
      case "$_dc_arg" in
        ''|0*|*[!0-9]*)
          log_bridge "REJECT KEY invalid arg"
          return 1
          ;;
      esac
      if [ "${#_dc_arg}" -gt 3 ] || [ "$_dc_arg" -lt 1 ] || [ "$_dc_arg" -gt 300 ]; then
        log_bridge "REJECT KEY out of range"
        return 1
      fi
      run_input keyevent "$_dc_arg" >/dev/null 2>&1
      return $?
      ;;

    PASTE)
      if [ -n "$_dc_arg" ]; then
        log_bridge "REJECT PASTE unexpected arg"
        return 1
      fi
      run_input keyevent 279 >/dev/null 2>&1
      return $?
      ;;

    SLEEP)
      if [ -n "$_dc_arg" ]; then
        log_bridge "REJECT SLEEP unexpected arg"
        return 1
      fi
      run_input keyevent 223 >/dev/null 2>&1
      return $?
      ;;

    WAKE)
      if [ -n "$_dc_arg" ]; then
        log_bridge "REJECT WAKE unexpected arg"
        return 1
      fi
      run_input keyevent 224 >/dev/null 2>&1
      return $?
      ;;

    FORCESTOP)
      case "$_dc_arg" in
        ''|.*|*.|*..*|*[!a-zA-Z0-9._]*)
          log_bridge "REJECT FORCESTOP invalid pkg"
          return 1
          ;;
        *.*) ;;
        *)
          log_bridge "REJECT FORCESTOP missing dot"
          return 1
          ;;
      esac
      case "$_dc_arg" in
        android|system|com.android.systemui|com.will.ambienthome|com.android.bluetooth|com.android.shell|com.android.networkstack|com.android.networkstack.tethering|com.android.providers.settings|com.android.wifi.resources|com.android.keychain|com.android.bootfix|com.android.inputmethod.latin|com.android.tv.settings|com.android.permissioncontroller|com.android.packageinstaller)
          log_bridge "REJECT FORCESTOP protected pkg=$_dc_arg"
          return 1
          ;;
      esac
      run_am force-stop "$_dc_arg" >/dev/null 2>&1
      return $?
      ;;

    AFR)
      if ! handle_afr_arg "$_dc_arg"; then
        log_bridge "REJECT AFR invalid arg"
        return 1
      fi
      return 0
      ;;

    EJECT)
      case "$_dc_arg" in
        ''|-*|.*|,*|*..*|*[!a-zA-Z0-9:._,-]*)
          log_bridge "REJECT EJECT invalid vol"
          return 1
          ;;
      esac
      if [ "${#_dc_arg}" -gt 64 ]; then
        log_bridge "REJECT EJECT oversized vol"
        return 1
      fi
      run_sm unmount "$_dc_arg" >/dev/null 2>&1
      return $?
      ;;

    *)
      log_bridge "REJECT unknown command"
      return 1
      ;;
  esac
}

main_loop() {
  trap 'exec 3<&- 3>&- 2>/dev/null; exit 0' INT TERM HUP
  log_bridge "=== start ambient_remote_bridge pid=$$ ==="

  while : ; do
    if ! ensure_fifo; then
      sleep 5
      continue
    fi

    if [ ! -p "$FIFO" ] || [ -L "$FIFO" ]; then
      sleep 2
      continue
    fi

    # Keep FIFO open read-write on FD 3 so non-blocking writers (O_WRONLY|O_NONBLOCK)
    # never race against reader re-open and idle CPU stays strictly 0.0%.
    if ! exec 3<>"$FIFO"; then
      log_bridge "ERR failed to open fifo"
      sleep 3
      continue
    fi

    log_bridge "READY fifo=$FIFO"
    while IFS= read -r _line <&3; do
      dispatch_command "$_line"
    done

    exec 3<&- 3>&- 2>/dev/null
    sleep 1
  done
}

if [ "${REMOTE_BRIDGE_TEST_MODE:-0}" != "1" ]; then
  main_loop
fi
