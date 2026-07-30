#!/system/bin/sh

set -u

STATUS_PATH=${1:-}
MODE=${2:-activate}
MODULE_DIR=/data/adb/modules/rezygisk
WORK_DIR=/data/adb/rezygisk
RESULT_FILE=/data/local/tmp/rmg-rezygisk-result
MONITOR_LOG=/data/local/tmp/rmg-rezygisk-monitor.log
MONITOR_PID=/data/local/tmp/rmg-rezygisk-monitor.pid
DIAGNOSTIC=/data/local/tmp/rmg-rezygisk-bridge-status
PENDING_FILE=/data/local/tmp/rmg-rezygisk-soft-reboot-pending
SOFT_REBOOT_LOG=/data/local/tmp/rmg-rezygisk-soft-reboot.log

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

find_ksud() {
    for candidate in /data/adb/ksu/bin/ksud /data/adb/ksud /data/local/tmp/ksud-s25u-kdp /data/local/tmp/.ksud-stage; do
        if [ -x "$candidate" ]; then
            /system/bin/echo "$candidate"
            return 0
        fi
    done
    return 1
}

stop_monitor() {
    tracer=$(find_tracer 2>/dev/null || true)
    if [ -n "$tracer" ] && [ -S "$WORK_DIR/init_monitor" ]; then
        TMP_PATH="$WORK_DIR" "$tracer" ctl exit >/dev/null 2>&1 || true
        /system/bin/sleep 1
    fi
    if [ -r "$MONITOR_PID" ]; then
        monitor_pid=$(/system/bin/cat "$MONITOR_PID" 2>/dev/null)
        [ -n "$monitor_pid" ] && /system/bin/toybox kill "$monitor_pid" 2>/dev/null || true
    fi
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
                fail_pre "another Zygisk provider is enabled: $id"
                ;;
        esac
    done
}

healthy() {
    tracer=$(find_tracer 2>/dev/null || true)
    [ -n "$tracer" ] || return 1
    [ -S "$WORK_DIR/init_monitor" ] || return 1
    [ -s "$MODULE_DIR/module.prop" ] || return 1
    /system/bin/grep -q 'Monitor: ✅' "$MODULE_DIR/module.prop" || return 1
    /system/bin/grep -q 'ReZygisk 64-bit: ✅' "$MODULE_DIR/module.prop" || return 1

    info=$(TMP_PATH="$WORK_DIR" "$tracer" info 2>/dev/null || true)
    /system/bin/printf '%s\n' "$info" | /system/bin/grep -q 'Root implementation: KernelSU' || return 1
    daemon_pid=$(/system/bin/printf '%s\n' "$info" | /system/bin/toybox sed -n 's/^Daemon process PID: //p' | /system/bin/toybox head -n 1)
    [ -n "$daemon_pid" ] || return 1
    [ "$daemon_pid" != "-1" ] || return 1
    /system/bin/toybox kill -0 "$daemon_pid" 2>/dev/null || return 1
    /system/bin/pidof system_server >/dev/null 2>&1 || return 1
    [ "$(/system/bin/getprop sys.boot_completed 2>/dev/null)" = "1" ] || return 1
    return 0
}

fail_pre() {
    reason=$1
    /system/bin/rm -f "$PENDING_FILE"
    /system/bin/echo "failure: $reason" > "$RESULT_FILE"
    status "FAILURE=$reason"
    log "Activation request failed: $reason"
    exit 1
}

rollback_verify() {
    reason=$1
    log "Rollback: $reason"
    /system/bin/touch "$MODULE_DIR/disable" 2>/dev/null || true
    stop_monitor
    /system/bin/rm -f "$WORK_DIR/state.json" "$PENDING_FILE"
    /system/bin/echo "rollback: $reason" > "$RESULT_FILE"
    status "ROLLBACK=$reason"
    status "FAILURE=$reason"
    exit 1
}

validate_environment() {
    [ "$(/system/bin/id -u)" = "0" ] || fail_pre "bridge did not run as uid 0"
    [ -d "$MODULE_DIR" ] || fail_pre "ReZygisk is not installed"
    [ ! -e "$MODULE_DIR/disable" ] || fail_pre "ReZygisk is disabled"
    [ ! -e "$MODULE_DIR/remove" ] || fail_pre "ReZygisk is pending removal"
    [ -x "$MODULE_DIR/bin/zygisk-ptrace64" ] || fail_pre "ReZygisk tracer is missing"
}

activate() {
    validate_environment
    status "BRIDGE_DETECTED=1"
    check_conflicts

    ksud=$(find_ksud) || fail_pre "KernelSU userspace binary with soft-reboot support was not found"
    boot_id=$(/system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    old_zygote=$(/system/bin/pidof zygote64 2>/dev/null | /system/bin/toybox awk '{print $1}')
    [ -n "$old_zygote" ] || old_zygote=$(/system/bin/pidof zygote 2>/dev/null | /system/bin/toybox awk '{print $1}')
    old_system_server=$(/system/bin/pidof system_server 2>/dev/null | /system/bin/toybox awk '{print $1}')

    /system/bin/rm -f "$RESULT_FILE" "$SOFT_REBOOT_LOG"
    /system/bin/printf '%s\n%s\n%s\n%s\n' \
        "$boot_id" "$STATUS_PATH" "$old_zygote" "$old_system_server" > "$PENDING_FILE" || \
        fail_pre "unable to create soft-reboot verification marker"
    /system/bin/chown 0:0 "$PENDING_FILE" 2>/dev/null || true
    /system/bin/chmod 0600 "$PENDING_FILE" 2>/dev/null || true

    [ -n "$old_zygote" ] && status "ZYGOTE_OLD_PID=$old_zygote"
    [ -n "$old_system_server" ] && status "SYSTEM_SERVER_OLD_PID=$old_system_server"
    status "MONITOR_STARTING=1"
    status "SOFT_REBOOT_SCHEDULED=1"
    status "KSU_SOFT_REBOOT_REQUESTED=1"
    /system/bin/echo pending > "$RESULT_FILE"
    log "Requesting KernelSU emulated soft reboot through $ksud"

    "$ksud" soft-reboot >>"$SOFT_REBOOT_LOG" 2>&1
    rc=$?
    [ "$rc" -eq 0 ] || fail_pre "ksud soft-reboot exited with status $rc"
    exit 0
}

verify() {
    [ -r "$PENDING_FILE" ] || exit 0
    expected_boot=$(/system/bin/toybox sed -n '1p' "$PENDING_FILE" 2>/dev/null)
    old_zygote=$(/system/bin/toybox sed -n '3p' "$PENDING_FILE" 2>/dev/null)
    old_system_server=$(/system/bin/toybox sed -n '4p' "$PENDING_FILE" 2>/dev/null)
    current_boot=$(/system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    [ -n "$expected_boot" ] || rollback_verify "soft-reboot verification marker is incomplete"
    [ "$expected_boot" = "$current_boot" ] || rollback_verify "soft-reboot marker belongs to another boot"

    status "POST_SOFT_REBOOT_VERIFYING=1"
    count=0
    while [ "$count" -lt 90 ]; do
        current_zygote=$(/system/bin/pidof zygote64 2>/dev/null | /system/bin/toybox awk '{print $1}')
        [ -n "$current_zygote" ] || current_zygote=$(/system/bin/pidof zygote 2>/dev/null | /system/bin/toybox awk '{print $1}')
        current_system_server=$(/system/bin/pidof system_server 2>/dev/null | /system/bin/toybox awk '{print $1}')

        generation_changed=1
        if [ -n "$old_zygote" ] && [ "$current_zygote" = "$old_zygote" ]; then
            generation_changed=0
        fi
        if [ -n "$old_system_server" ] && [ "$current_system_server" = "$old_system_server" ]; then
            generation_changed=0
        fi

        if [ "$generation_changed" = "1" ] && healthy; then
            [ -n "$current_zygote" ] && status "ZYGOTE_NEW_PID=$current_zygote"
            [ -n "$current_system_server" ] && status "SYSTEM_SERVER_NEW_PID=$current_system_server"
            /system/bin/rm -f "$PENDING_FILE"
            /system/bin/echo success > "$RESULT_FILE"
            status "SUCCESS=1"
            log "ReZygisk injection verified after KernelSU soft reboot"
            exit 0
        fi

        /system/bin/sleep 1
        count=$((count + 1))
    done

    rollback_verify "ReZygisk did not verify after KernelSU soft reboot"
}

case "$MODE" in
    activate) activate ;;
    verify) verify ;;
    *) fail_pre "unknown bridge mode: $MODE" ;;
esac
