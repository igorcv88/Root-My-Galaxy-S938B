#!/system/bin/sh

MODDIR=${0%/*}
LIB_PATH=/data/adb/modules/rezygisk/lib64/libzygisk.so
LIB_BACKUP=/data/adb/modules/rezygisk/lib64/.libzygisk.so.rmg-original
if [ -L "$LIB_PATH" ] && [ ! -e "$LIB_PATH" ] && [ -e "$LIB_BACKUP" ]; then
    /system/bin/rm -f "$LIB_PATH" 2>/dev/null || true
    /system/bin/mv -f "$LIB_BACKUP" "$LIB_PATH" 2>/dev/null || true
    /system/bin/chown 0:0 "$LIB_PATH" 2>/dev/null || true
    /system/bin/chmod 0644 "$LIB_PATH" 2>/dev/null || true
fi
PENDING_FILE=/data/local/tmp/rmg-rezygisk-soft-reboot-pending
VERIFY_PID=/data/local/tmp/rmg-rezygisk-verify.pid
VERIFY_LOG=/data/local/tmp/rmg-rezygisk-verify.log

verifier_is_running() {
    [ -r "$VERIFY_PID" ] || return 1
    old_pid=$(/system/bin/cat "$VERIFY_PID" 2>/dev/null)
    [ -n "$old_pid" ] || return 1
    /system/bin/toybox kill -0 "$old_pid" 2>/dev/null || return 1
    [ -r "/proc/$old_pid/cmdline" ] || return 1
    cmdline=$(/system/bin/toybox tr '\000' ' ' < "/proc/$old_pid/cmdline" 2>/dev/null)
    case "$cmdline" in
        *"$MODDIR/bridge-v08.sh"*" verify"*) return 0 ;;
        *) return 1 ;;
    esac
}

if [ -r "$PENDING_FILE" ]; then
    expected_boot=$(/system/bin/toybox sed -n '1p' "$PENDING_FILE" 2>/dev/null)
    status_path=$(/system/bin/toybox sed -n '2p' "$PENDING_FILE" 2>/dev/null)
    current_boot=$(/system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null)

    if [ -n "$expected_boot" ] && [ "$expected_boot" = "$current_boot" ]; then
        case "$status_path" in
            /data/user/0/dev.busung.s25uroot/files/*|/data/data/dev.busung.s25uroot/files/*) ;;
            *) status_path= ;;
        esac

        if ! verifier_is_running; then
            /system/bin/rm -f "$VERIFY_PID" "$VERIFY_LOG"
            /system/bin/toybox setsid /system/bin/sh "$MODDIR/bridge-v08.sh" "$status_path" verify >>"$VERIFY_LOG" 2>&1 </dev/null &
            verify_pid=$!
            /system/bin/echo "$verify_pid" > "$VERIFY_PID"
        fi
        exit 0
    fi
fi

# Fallback for KernelSU builds that reach service without having consumed the arm token in
# late-load. The launcher remains idempotent and will not create a second activation worker.
/system/bin/sh "$MODDIR/late-load.sh" service
exit $?
