#!/system/bin/sh

MODDIR=${0%/*}
SOURCE=${1:-late-load}
ARM_FILE=/data/local/tmp/rmg-rezygisk-arm
PID_FILE=/data/local/tmp/rmg-rezygisk-bridge.pid
LOG_FILE=/data/local/tmp/rmg-rezygisk-bridge.log
LOCK_DIR=/data/local/tmp/rmg-rezygisk-bridge.lock
DIAGNOSTIC=/data/local/tmp/rmg-rezygisk-bridge-status

# The app creates this file while it still has bootstrap uid 0 but remains in an app SELinux
# domain. Normalize DAC and label from KernelSU's domain before attempting to read it.
if [ -e "$ARM_FILE" ]; then
    /system/bin/chown 0:0 "$ARM_FILE" 2>/dev/null || true
    /system/bin/chmod 0644 "$ARM_FILE" 2>/dev/null || true
    /system/bin/restorecon -F "$ARM_FILE" 2>/dev/null || \
        /system/bin/chcon u:object_r:shell_data_file:s0 "$ARM_FILE" 2>/dev/null || true
fi

[ -r "$ARM_FILE" ] || exit 0

expected_boot=$(/system/bin/toybox sed -n '1p' "$ARM_FILE" 2>/dev/null)
status_path=$(/system/bin/toybox sed -n '2p' "$ARM_FILE" 2>/dev/null)
current_boot=$(/system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null)

[ -n "$expected_boot" ] || exit 0
[ "$expected_boot" = "$current_boot" ] || exit 0
case "$status_path" in
    /data/user/0/dev.busung.s25uroot/files/*|/data/data/dev.busung.s25uroot/files/*) ;;
    *) exit 0 ;;
esac

bridge_is_running() {
    [ -r "$PID_FILE" ] || return 1
    old_pid=$(/system/bin/cat "$PID_FILE" 2>/dev/null)
    [ -n "$old_pid" ] || return 1
    /system/bin/toybox kill -0 "$old_pid" 2>/dev/null || return 1
    [ -r "/proc/$old_pid/cmdline" ] || return 1
    cmdline=$(/system/bin/toybox tr '\000' ' ' < "/proc/$old_pid/cmdline" 2>/dev/null)
    case "$cmdline" in
        *"$MODDIR/bridge-v07.sh"*) return 0 ;;
        *) return 1 ;;
    esac
}

if bridge_is_running; then
    exit 0
fi

if ! /system/bin/mkdir "$LOCK_DIR" 2>/dev/null; then
    if bridge_is_running; then
        exit 0
    fi
    /system/bin/rm -rf "$LOCK_DIR" 2>/dev/null || exit 0
    /system/bin/mkdir "$LOCK_DIR" 2>/dev/null || exit 0
fi

cleanup_lock() {
    /system/bin/rmdir "$LOCK_DIR" 2>/dev/null || true
}
trap cleanup_lock EXIT HUP INT TERM

if bridge_is_running; then
    exit 0
fi

/system/bin/rm -f "$LOG_FILE" "$PID_FILE" "$DIAGNOSTIC"
/system/bin/printf '%s\n' "LAUNCH_SOURCE=$SOURCE" >> "$status_path" 2>/dev/null || true
/system/bin/toybox setsid /system/bin/sh "$MODDIR/bridge-v07.sh" "$status_path" >>"$LOG_FILE" 2>&1 </dev/null &
bridge_pid=$!
/system/bin/echo "$bridge_pid" > "$PID_FILE"
/system/bin/sleep 1
if ! /system/bin/toybox kill -0 "$bridge_pid" 2>/dev/null; then
    /system/bin/rm -f "$PID_FILE"
    exit 0
fi

# Keep the token until a worker has actually survived startup so service.sh can retry a failed
# late-load launch. Once the worker is alive, consume it exactly once for this boot.
/system/bin/rm -f "$ARM_FILE"
exit 0
