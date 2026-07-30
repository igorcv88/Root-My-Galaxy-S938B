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
VERIFY_PID=/data/local/tmp/rmg-rezygisk-verify.pid

HEALTH_REASON=
HEALTH_DAEMON_PID=
HEALTH_INFO=

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

first_pid_of() {
    for process_name in "$@"; do
        process_pids=$(/system/bin/pidof "$process_name" 2>/dev/null || true)
        for process_pid in $process_pids; do
            case "$process_pid" in
                ''|*[!0-9]*) continue ;;
            esac
            /system/bin/printf '%s\n' "$process_pid"
            return 0
        done
    done
    return 1
}

parse_daemon_pid() {
    DAEMON_PID=
    while IFS= read -r info_line; do
        case "$info_line" in
            'Daemon process PID: '*)
                DAEMON_PID=${info_line#Daemon process PID: }
                break
                ;;
        esac
    done <<EOF_INFO
$1
EOF_INFO

    case "$DAEMON_PID" in
        ''|-1|*[!0-9]*) return 1 ;;
    esac
    /system/bin/printf '%s\n' "$DAEMON_PID"
}

process_is_rezygisk_daemon() {
    daemon_pid=$1
    [ -r "/proc/$daemon_pid/comm" ] || return 1
    daemon_comm=$(/system/bin/cat "/proc/$daemon_pid/comm" 2>/dev/null || true)
    case "$daemon_comm" in
        zygiskd64|zygiskd32|zygiskd) return 0 ;;
        *) return 1 ;;
    esac
}

probe_health() {
    HEALTH_REASON=
    HEALTH_DAEMON_PID=
    HEALTH_INFO=

    tracer=$(find_tracer 2>/dev/null || true)
    if [ -z "$tracer" ]; then
        HEALTH_REASON="ReZygisk tracer is missing"
        return 1
    fi
    if [ ! -S "$WORK_DIR/init_monitor" ]; then
        HEALTH_REASON="ReZygisk monitor socket is unavailable"
        return 1
    fi

    HEALTH_INFO=$(TMP_PATH="$WORK_DIR" "$tracer" info 2>/dev/null || true)
    case "$HEALTH_INFO" in
        *'Root implementation: KernelSU'*) ;;
        *)
            HEALTH_REASON="ReZygisk daemon did not report KernelSU"
            return 1
            ;;
    esac

    HEALTH_DAEMON_PID=$(parse_daemon_pid "$HEALTH_INFO" 2>/dev/null || true)
    if [ -z "$HEALTH_DAEMON_PID" ]; then
        HEALTH_REASON="ReZygisk daemon PID is unavailable"
        return 1
    fi
    if ! /system/bin/toybox kill -0 "$HEALTH_DAEMON_PID" 2>/dev/null; then
        HEALTH_REASON="ReZygisk daemon is not alive"
        return 1
    fi
    if ! process_is_rezygisk_daemon "$HEALTH_DAEMON_PID"; then
        HEALTH_REASON="ReZygisk daemon PID belongs to another process"
        return 1
    fi
    if ! first_pid_of zygote64 zygote >/dev/null 2>&1; then
        HEALTH_REASON="zygote is unavailable"
        return 1
    fi
    if ! first_pid_of system_server >/dev/null 2>&1; then
        HEALTH_REASON="system_server is unavailable"
        return 1
    fi
    if [ "$(/system/bin/getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
        HEALTH_REASON="Android boot is not complete"
        return 1
    fi

    return 0
}

stop_stale_runtime() {
    tracer=$(find_tracer 2>/dev/null || true)
    daemon_pid=
    if [ -n "$tracer" ]; then
        info=$(TMP_PATH="$WORK_DIR" "$tracer" info 2>/dev/null || true)
        daemon_pid=$(parse_daemon_pid "$info" 2>/dev/null || true)
        if [ -S "$WORK_DIR/init_monitor" ]; then
            TMP_PATH="$WORK_DIR" "$tracer" ctl exit >/dev/null 2>&1 || true
            /system/bin/sleep 1
        fi
    fi

    if [ -n "$daemon_pid" ] && process_is_rezygisk_daemon "$daemon_pid"; then
        status "STALE_DAEMON_PID=$daemon_pid"
        /system/bin/toybox kill -TERM "$daemon_pid" 2>/dev/null || true
        wait_count=0
        while [ "$wait_count" -lt 3 ] && /system/bin/toybox kill -0 "$daemon_pid" 2>/dev/null; do
            /system/bin/sleep 1
            wait_count=$((wait_count + 1))
        done
        if /system/bin/toybox kill -0 "$daemon_pid" 2>/dev/null && process_is_rezygisk_daemon "$daemon_pid"; then
            /system/bin/toybox kill -KILL "$daemon_pid" 2>/dev/null || true
        fi
    fi

    /system/bin/rm -f "$MONITOR_PID"
    /system/bin/rm -rf "$WORK_DIR"
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

record_health_snapshot() {
    current_zygote=$(first_pid_of zygote64 zygote 2>/dev/null || true)
    current_system_server=$(first_pid_of system_server 2>/dev/null || true)
    [ -n "$current_zygote" ] && status "ZYGOTE_CURRENT_PID=$current_zygote"
    [ -n "$current_system_server" ] && status "SYSTEM_SERVER_CURRENT_PID=$current_system_server"
    [ -n "$HEALTH_DAEMON_PID" ] && status "REZYGISK_DAEMON_PID=$HEALTH_DAEMON_PID"

    if [ -s "$MODULE_DIR/module.prop" ]; then
        if /system/bin/grep -q 'Monitor: ✅' "$MODULE_DIR/module.prop"; then
            status "MODULE_PROP_MONITOR_OK=1"
        else
            status "MODULE_PROP_MONITOR_OK=0"
        fi
        if /system/bin/grep -q 'ReZygisk 64-bit: ✅' "$MODULE_DIR/module.prop"; then
            status "MODULE_PROP_REZYGISK64_OK=1"
        else
            status "MODULE_PROP_REZYGISK64_OK=0"
        fi
    fi
}

fail_pre() {
    reason=$1
    /system/bin/rm -f "$PENDING_FILE"
    /system/bin/echo "failure: $reason" > "$RESULT_FILE"
    status "FAILURE=$reason"
    log "Activation request failed: $reason"
    exit 1
}

verification_inconclusive() {
    reason=$1
    record_health_snapshot
    /system/bin/rm -f "$PENDING_FILE" "$VERIFY_PID"
    /system/bin/echo "inconclusive: $reason" > "$RESULT_FILE"
    status "INCONCLUSIVE=$reason"
    status "FAILURE=$reason"
    log "Verification inconclusive; preserving ReZygisk runtime: $reason"
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

    if probe_health; then
        status "ALREADY_ACTIVE=1"
        record_health_snapshot
        /system/bin/rm -f "$PENDING_FILE"
        /system/bin/echo success > "$RESULT_FILE"
        status "SUCCESS=1"
        log "ReZygisk was already healthy; no soft reboot requested"
        exit 0
    fi

    status "PREFLIGHT_HEALTH=$HEALTH_REASON"
    stop_stale_runtime

    ksud=$(find_ksud) || fail_pre "KernelSU userspace binary with soft-reboot support was not found"
    boot_id=$(/system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    [ -n "$boot_id" ] || fail_pre "unable to read current boot ID"
    old_zygote=$(first_pid_of zygote64 zygote 2>/dev/null || true)
    old_system_server=$(first_pid_of system_server 2>/dev/null || true)
    [ -n "$old_zygote" ] || fail_pre "unable to identify current zygote"
    [ -n "$old_system_server" ] || fail_pre "unable to identify current system_server"

    /system/bin/rm -f "$RESULT_FILE" "$SOFT_REBOOT_LOG"
    /system/bin/printf '%s\n%s\n%s\n%s\n' \
        "$boot_id" "$STATUS_PATH" "$old_zygote" "$old_system_server" > "$PENDING_FILE" || \
        fail_pre "unable to create soft-reboot verification marker"
    /system/bin/chown 0:0 "$PENDING_FILE" 2>/dev/null || true
    /system/bin/chmod 0600 "$PENDING_FILE" 2>/dev/null || true

    status "ZYGOTE_OLD_PID=$old_zygote"
    status "SYSTEM_SERVER_OLD_PID=$old_system_server"
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
    [ -n "$expected_boot" ] || verification_inconclusive "soft-reboot verification marker is incomplete"
    [ "$expected_boot" = "$current_boot" ] || verification_inconclusive "soft-reboot marker belongs to another boot"
    [ -n "$old_zygote" ] || verification_inconclusive "old zygote PID is unavailable"
    [ -n "$old_system_server" ] || verification_inconclusive "old system_server PID is unavailable"

    status "POST_SOFT_REBOOT_VERIFYING=1"
    count=0
    last_reason=
    while [ "$count" -lt 120 ]; do
        current_zygote=$(first_pid_of zygote64 zygote 2>/dev/null || true)
        current_system_server=$(first_pid_of system_server 2>/dev/null || true)

        if [ -z "$current_zygote" ]; then
            reason="waiting for zygote"
        elif [ -z "$current_system_server" ]; then
            reason="waiting for system_server"
        elif [ "$current_zygote" = "$old_zygote" ]; then
            reason="waiting for replacement zygote"
        elif [ "$current_system_server" = "$old_system_server" ]; then
            reason="waiting for replacement system_server"
        elif probe_health; then
            status "ZYGOTE_NEW_PID=$current_zygote"
            status "SYSTEM_SERVER_NEW_PID=$current_system_server"
            record_health_snapshot
            /system/bin/rm -f "$PENDING_FILE" "$VERIFY_PID"
            /system/bin/echo success > "$RESULT_FILE"
            status "SUCCESS=1"
            log "ReZygisk injection verified after KernelSU soft reboot"
            exit 0
        else
            reason=$HEALTH_REASON
        fi

        if [ "$reason" != "$last_reason" ]; then
            log "Verification waiting: $reason"
            last_reason=$reason
        fi
        /system/bin/sleep 1
        count=$((count + 1))
    done

    [ -n "$last_reason" ] || last_reason="ReZygisk health did not become conclusive"
    verification_inconclusive "$last_reason"
}

case "$MODE" in
    activate) activate ;;
    verify) verify ;;
    *) fail_pre "unknown bridge mode: $MODE" ;;
esac
