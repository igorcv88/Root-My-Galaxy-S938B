#!/system/bin/sh

set -u

STATUS_PATH=${1:-}
MODULE_DIR=/data/adb/modules/rezygisk
WORK_DIR=/data/adb/rezygisk
RESULT_FILE=/data/local/tmp/rmg-rezygisk-result
MONITOR_LOG=/data/local/tmp/rmg-rezygisk-monitor.log
MONITOR_PID=/data/local/tmp/rmg-rezygisk-monitor.pid
DIAGNOSTIC=/data/local/tmp/rmg-rezygisk-bridge-status
ZYGOTE_RESTARTED=0

status() {
    value=$1
    /system/bin/printf '%s\n' "$value" >> "$DIAGNOSTIC" 2>/dev/null || true
    if [ -n "$STATUS_PATH" ]; then
        /system/bin/printf '%s\n' "$value" >> "$STATUS_PATH" 2>/dev/null || true
    fi
}

log() {
    /system/bin/echo "[$(/system/bin/date '+%Y-%m-%d %H:%M:%S')] $*"
}

find_tracer() {
    if [ -x "$MODULE_DIR/bin/zygisk-ptrace64" ]; then
        /system/bin/echo "$MODULE_DIR/bin/zygisk-ptrace64"
    elif [ -x "$MODULE_DIR/bin/zygisk-ptrace32" ]; then
        /system/bin/echo "$MODULE_DIR/bin/zygisk-ptrace32"
    else
        return 1
    fi
}

zygote_pid() {
    /system/bin/pidof zygote64 2>/dev/null | /system/bin/toybox awk '{print $1}'
    if [ -z "$(/system/bin/pidof zygote64 2>/dev/null)" ]; then
        /system/bin/pidof zygote 2>/dev/null | /system/bin/toybox awk '{print $1}'
    fi
}

stop_monitor() {
    tracer=$(find_tracer 2>/dev/null || true)
    if [ -n "$tracer" ] && [ -S "$WORK_DIR/init_monitor" ]; then
        TMP_PATH="$WORK_DIR" "$tracer" ctl exit >/dev/null 2>&1 || true
        /system/bin/sleep 1
    fi
    if [ -r "$MONITOR_PID" ]; then
        monitor_pid=$(/system/bin/cat "$MONITOR_PID" 2>/dev/null)
        [ -n "$monitor_pid" ] && /system/bin/kill "$monitor_pid" 2>/dev/null || true
    fi
}

restart_zygote() {
    old=$(zygote_pid | /system/bin/toybox head -n 1)
    [ -n "$old" ] || return 1
    /system/bin/setprop ctl.restart zygote || return 1

    count=0
    while [ "$count" -lt 15 ]; do
        current=$(zygote_pid | /system/bin/toybox head -n 1)
        if [ -n "$current" ] && [ "$current" != "$old" ]; then
            ZYGOTE_RESTARTED=1
            return 0
        fi
        /system/bin/sleep 1
        count=$((count + 1))
    done
    return 1
}

rollback() {
    reason=$1
    log "Rollback: $reason"
    /system/bin/touch "$MODULE_DIR/disable" 2>/dev/null || true
    stop_monitor
    /system/bin/rm -f "$WORK_DIR/state.json"

    if [ "$ZYGOTE_RESTARTED" = "1" ]; then
        /system/bin/setprop ctl.restart zygote >/dev/null 2>&1 || true
    fi

    /system/bin/echo "rollback: $reason" > "$RESULT_FILE"
    status "ROLLBACK=$reason"
    status "FAILURE=$reason"
    exit 1
}

check_conflicts() {
    for prop in /data/adb/modules/*/module.prop; do
        [ -f "$prop" ] || continue
        dir=${prop%/*}
        [ -e "$dir/disable" ] && continue
        [ -e "$dir/remove" ] && continue
        id=$(/system/bin/toybox sed -n 's/^id=//p' "$prop" | /system/bin/toybox head -n 1)
        case "$id" in
            rezygisk|rmg_rezygisk_bridge|'') ;;
            zygisksu|zygisknext|zygisk_next|brezygisk)
                rollback "another Zygisk provider is enabled: $id"
                ;;
        esac
    done
}

healthy() {
    state=$WORK_DIR/state.json
    prop=$MODULE_DIR/module.prop
    [ -s "$state" ] || return 1
    [ -s "$prop" ] || return 1
    /system/bin/grep -q '"root": "KernelSU"' "$state" || return 1
    /system/bin/grep -q '"state": "0"' "$state" || return 1
    /system/bin/grep -q '"state": 1' "$state" || return 1
    /system/bin/grep -q 'Monitor: ✅' "$prop" || return 1
    /system/bin/grep -q 'ReZygisk 64-bit: ✅' "$prop" || return 1
    /system/bin/pidof system_server >/dev/null 2>&1 || return 1
    return 0
}

[ "$(/system/bin/id -u)" = "0" ] || { status "FAILURE=bridge did not run as uid 0"; exit 1; }
[ -d "$MODULE_DIR" ] || { status "FAILURE=ReZygisk is not installed"; exit 1; }
[ ! -e "$MODULE_DIR/disable" ] || { status "FAILURE=ReZygisk is disabled"; exit 1; }
[ ! -e "$MODULE_DIR/remove" ] || { status "FAILURE=ReZygisk is pending removal"; exit 1; }
[ -x "$MODULE_DIR/bin/zygisk-ptrace64" ] || { status "FAILURE=ReZygisk tracer is missing"; exit 1; }

status "BRIDGE_DETECTED=1"
check_conflicts

[ -f "$MODULE_DIR/module.prop.bak" ] && /system/bin/cp "$MODULE_DIR/module.prop.bak" "$MODULE_DIR/module.prop"
/system/bin/rm -rf "$WORK_DIR"
/system/bin/mkdir -p "$WORK_DIR" || rollback "unable to create ReZygisk work directory"
/system/bin/chown 0:0 "$WORK_DIR"
/system/bin/chmod 0555 "$WORK_DIR"
/system/bin/chcon u:object_r:system_file:s0 "$WORK_DIR" || rollback "unable to label ReZygisk work directory"
/system/bin/rm -f "$RESULT_FILE" "$MONITOR_LOG" "$MONITOR_PID" "$DIAGNOSTIC"
status "BRIDGE_DETECTED=1"
status "MONITOR_STARTING=1"

tracer=$(find_tracer) || rollback "ReZygisk tracer is missing"
TMP_PATH="$WORK_DIR" /system/bin/toybox setsid "$tracer" monitor >>"$MONITOR_LOG" 2>&1 </dev/null &
monitor_pid=$!
/system/bin/echo "$monitor_pid" > "$MONITOR_PID"

count=0
while [ "$count" -lt 10 ]; do
    /system/bin/toybox kill -0 "$monitor_pid" 2>/dev/null || break
    [ -S "$WORK_DIR/init_monitor" ] && break
    /system/bin/sleep 1
    count=$((count + 1))
done
[ -S "$WORK_DIR/init_monitor" ] || rollback "ReZygisk monitor did not attach to init"

/system/bin/sleep 2
/system/bin/toybox kill -0 "$monitor_pid" 2>/dev/null || rollback "ReZygisk monitor exited during preflight"
status "SOFT_REBOOT_SCHEDULED=1"
/system/bin/echo pending > "$RESULT_FILE"
/system/bin/sleep 4
restart_zygote || rollback "init did not restart zygote"

count=0
while [ "$count" -lt 25 ]; do
    if healthy; then
        /system/bin/echo success > "$RESULT_FILE"
        status "SUCCESS=1"
        log "ReZygisk injection verified"
        exit 0
    fi
    /system/bin/toybox kill -0 "$monitor_pid" 2>/dev/null || rollback "ReZygisk monitor exited during activation"
    /system/bin/sleep 1
    count=$((count + 1))
done

rollback "ReZygisk did not verify within 25 seconds"
