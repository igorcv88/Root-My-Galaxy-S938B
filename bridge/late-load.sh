#!/system/bin/sh

MODDIR=${0%/*}
ARM_FILE=/data/local/tmp/rmg-rezygisk-arm
PID_FILE=/data/local/tmp/rmg-rezygisk-bridge.pid
LOG_FILE=/data/local/tmp/rmg-rezygisk-bridge.log

[ -r "$ARM_FILE" ] || exit 0

expected_boot=$(/system/bin/toybox sed -n '1p' "$ARM_FILE" 2>/dev/null)
status_path=$(/system/bin/toybox sed -n '2p' "$ARM_FILE" 2>/dev/null)
current_boot=$(/system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null)
/system/bin/rm -f "$ARM_FILE"

[ -n "$expected_boot" ] || exit 0
[ "$expected_boot" = "$current_boot" ] || exit 0
case "$status_path" in
    /data/user/0/dev.busung.s25uroot/files/*|/data/data/dev.busung.s25uroot/files/*) ;;
    *) exit 0 ;;
esac

if [ -r "$PID_FILE" ]; then
    old_pid=$(/system/bin/cat "$PID_FILE" 2>/dev/null)
    if [ -n "$old_pid" ] && /system/bin/toybox kill -0 "$old_pid" 2>/dev/null; then
        exit 0
    fi
fi

/system/bin/rm -f "$LOG_FILE" "$PID_FILE"
/system/bin/toybox setsid /system/bin/sh "$MODDIR/bridge.sh" "$status_path" >>"$LOG_FILE" 2>&1 </dev/null &
bridge_pid=$!
/system/bin/echo "$bridge_pid" > "$PID_FILE"
exit 0
