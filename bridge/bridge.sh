#!/system/bin/sh

set -u

STATUS_PATH=${1:-}
MODE=${2:-activate}
BRIDGE_DIR=${0%/*}
MODULE_DIR=/data/adb/modules/rezygisk
WORK_DIR=/data/adb/rezygisk
REZYGISK_POST_FS=$MODULE_DIR/post-fs-data.sh
RESULT_FILE=/data/local/tmp/rmg-rezygisk-result
MONITOR_LOG=/data/local/tmp/rmg-rezygisk-monitor.log
DIAGNOSTIC=/data/local/tmp/rmg-rezygisk-bridge-status
PENDING_FILE=/data/local/tmp/rmg-rezygisk-soft-reboot-pending
SOFT_REBOOT_LOG=/data/local/tmp/rmg-rezygisk-soft-reboot.log
VERIFY_PID=/data/local/tmp/rmg-rezygisk-verify.pid
POST_FS_BACKUP=$BRIDGE_DIR/.rezygisk-post-fs-data.backup
POST_FS_PATCHED=$BRIDGE_DIR/.rezygisk-post-fs-data.patched

HEALTH_REASON=
HEALTH_MONITOR_PID=
HEALTH_DAEMON_PID=
HEALTH_ZYGOTE_PID=
HEALTH_SYSTEM_SERVER_PID=
RUNTIME_MONITOR_PIDS=
RUNTIME_DAEMON_PIDS=
RUNTIME_MONITOR_COUNT=0
RUNTIME_DAEMON_COUNT=0
STATE_ROOT_KERNELSU=0
STATE_ZYGOTE64_OK=0
MODULE_PROP_MONITOR_OK=0
MODULE_PROP_REZYGISK64_OK=0

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

valid_pid() {
    case "${1:-}" in
        ''|*[!0-9]*) return 1 ;;
        *) return 0 ;;
    esac
}

append_unique_pid() {
    list=$1
    candidate=$2
    for existing in $list; do
        [ "$existing" = "$candidate" ] && {
            /system/bin/printf '%s\n' "$list"
            return 0
        }
    done
    if [ -n "$list" ]; then
        /system/bin/printf '%s %s\n' "$list" "$candidate"
    else
        /system/bin/printf '%s\n' "$candidate"
    fi
}

pids_of() {
    result=
    for process_name in "$@"; do
        process_pids=$(/system/bin/pidof "$process_name" 2>/dev/null || true)
        for process_pid in $process_pids; do
            valid_pid "$process_pid" || continue
            result=$(append_unique_pid "$result" "$process_pid")
        done
    done
    /system/bin/printf '%s\n' "$result"
}

count_pids() {
    count=0
    for process_pid in $1; do
        valid_pid "$process_pid" || continue
        count=$((count + 1))
    done
    /system/bin/printf '%s\n' "$count"
}

first_pid_of() {
    process_pids=$(pids_of "$@")
    for process_pid in $process_pids; do
        /system/bin/printf '%s\n' "$process_pid"
        return 0
    done
    return 1
}

process_comm() {
    process_pid=$1
    [ -r "/proc/$process_pid/comm" ] || return 1
    /system/bin/cat "/proc/$process_pid/comm" 2>/dev/null
}

process_cmdline() {
    process_pid=$1
    [ -r "/proc/$process_pid/cmdline" ] || return 1
    /system/bin/toybox tr '\000' ' ' < "/proc/$process_pid/cmdline" 2>/dev/null
}

process_parent_pid() {
    process_pid=$1
    [ -r "/proc/$process_pid/status" ] || return 1
    while IFS= read -r status_line; do
        case "$status_line" in
            PPid:*)
                parent=${status_line#PPid:}
                parent=$(/system/bin/printf '%s' "$parent" | /system/bin/toybox tr -cd '0-9')
                valid_pid "$parent" || return 1
                /system/bin/printf '%s\n' "$parent"
                return 0
                ;;
        esac
    done < "/proc/$process_pid/status"
    return 1
}

process_is_rezygisk_monitor() {
    process_pid=$1
    comm=$(process_comm "$process_pid" 2>/dev/null || true)
    case "$comm" in
        zygisk-ptrace64|zygisk-ptrace32|zygisk-ptrace) ;;
        *) return 1 ;;
    esac
    cmdline=$(process_cmdline "$process_pid" 2>/dev/null || true)
    case "$cmdline" in
        *zygisk-ptrace*monitor*) return 0 ;;
        *) return 1 ;;
    esac
}

process_is_rezygisk_daemon() {
    process_pid=$1
    comm=$(process_comm "$process_pid" 2>/dev/null || true)
    case "$comm" in
        zygiskd64|zygiskd32|zygiskd) return 0 ;;
        *) return 1 ;;
    esac
}

list_contains_pid() {
    list=$1
    wanted=$2
    for process_pid in $list; do
        [ "$process_pid" = "$wanted" ] && return 0
    done
    return 1
}

list_has_survivor() {
    old_list=$1
    new_list=$2
    for process_pid in $old_list; do
        list_contains_pid "$new_list" "$process_pid" && return 0
    done
    return 1
}

list_has_new_pid() {
    old_list=$1
    new_list=$2
    for process_pid in $new_list; do
        list_contains_pid "$old_list" "$process_pid" || return 0
    done
    return 1
}

runtime_pairs() {
    pairs=
    for daemon_pid in $RUNTIME_DAEMON_PIDS; do
        parent=$(process_parent_pid "$daemon_pid" 2>/dev/null || true)
        [ -n "$parent" ] || parent=unknown
        if [ -n "$pairs" ]; then
            pairs="$pairs $daemon_pid<-$parent"
        else
            pairs="$daemon_pid<-$parent"
        fi
    done
    /system/bin/printf '%s\n' "$pairs"
}

snapshot_runtime() {
    prefix=$1
    should_record=${2:-1}
    RUNTIME_MONITOR_PIDS=$(pids_of zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
    RUNTIME_DAEMON_PIDS=$(pids_of zygiskd64 zygiskd32 zygiskd)
    RUNTIME_MONITOR_COUNT=$(count_pids "$RUNTIME_MONITOR_PIDS")
    RUNTIME_DAEMON_COUNT=$(count_pids "$RUNTIME_DAEMON_PIDS")
    [ "$should_record" = "1" ] || return 0

    pairs=$(runtime_pairs)
    status "${prefix}_MONITOR_PIDS=${RUNTIME_MONITOR_PIDS:-none}"
    status "${prefix}_DAEMON_PIDS=${RUNTIME_DAEMON_PIDS:-none}"
    status "${prefix}_MONITOR_COUNT=$RUNTIME_MONITOR_COUNT"
    status "${prefix}_DAEMON_COUNT=$RUNTIME_DAEMON_COUNT"
    status "${prefix}_MONITOR_DAEMON_PAIRS=${pairs:-none}"
}

read_state_snapshot() {
    STATE_ROOT_KERNELSU=0
    STATE_ZYGOTE64_OK=0

    [ -r "$WORK_DIR/state.json" ] || return 0
    if /system/bin/grep -q '"root"[[:space:]]*:[[:space:]]*"KernelSU"' "$WORK_DIR/state.json" 2>/dev/null; then
        STATE_ROOT_KERNELSU=1
    fi

    in_zygote=0
    while IFS= read -r state_line; do
        if [ "$in_zygote" -eq 0 ]; then
            case "$state_line" in
                *'"zygote"'*'{') in_zygote=1 ;;
            esac
            continue
        fi

        compact_line=$(/system/bin/printf '%s' "$state_line" | /system/bin/toybox tr -d ' \t\r')
        case "$compact_line" in
            *'"64":1'*)
                STATE_ZYGOTE64_OK=1
                break
                ;;
            *'}'*) break ;;
        esac
    done < "$WORK_DIR/state.json"
}

read_module_prop_snapshot() {
    MODULE_PROP_MONITOR_OK=0
    MODULE_PROP_REZYGISK64_OK=0
    [ -r "$MODULE_DIR/module.prop" ] || return 0

    /system/bin/grep -q 'Monitor: ✅' "$MODULE_DIR/module.prop" 2>/dev/null && MODULE_PROP_MONITOR_OK=1
    /system/bin/grep -q 'ReZygisk 64-bit: ✅' "$MODULE_DIR/module.prop" 2>/dev/null && MODULE_PROP_REZYGISK64_OK=1
}

record_health_snapshot() {
    HEALTH_ZYGOTE_PID=$(first_pid_of zygote64 zygote 2>/dev/null || true)
    HEALTH_SYSTEM_SERVER_PID=$(first_pid_of system_server 2>/dev/null || true)
    read_state_snapshot
    read_module_prop_snapshot

    status "ZYGOTE_CURRENT_PID=${HEALTH_ZYGOTE_PID:-none}"
    status "SYSTEM_SERVER_CURRENT_PID=${HEALTH_SYSTEM_SERVER_PID:-none}"
    status "STATE_ROOT_KERNELSU=$STATE_ROOT_KERNELSU"
    status "STATE_ZYGOTE64_OK=$STATE_ZYGOTE64_OK"
    status "MODULE_PROP_MONITOR_OK=$MODULE_PROP_MONITOR_OK"
    status "MODULE_PROP_REZYGISK64_OK=$MODULE_PROP_REZYGISK64_OK"
    status "MONITOR_LOG=$MONITOR_LOG"
}

probe_health() {
    HEALTH_REASON=
    HEALTH_MONITOR_PID=
    HEALTH_DAEMON_PID=

    snapshot_runtime HEALTH 0
    if [ "$RUNTIME_MONITOR_COUNT" -gt 1 ] || [ "$RUNTIME_DAEMON_COUNT" -gt 1 ]; then
        HEALTH_REASON="duplicate ReZygisk monitor or daemon stack"
        return 1
    fi
    if [ "$RUNTIME_MONITOR_COUNT" -ne 1 ]; then
        HEALTH_REASON="ReZygisk monitor is not running"
        return 1
    fi
    if [ "$RUNTIME_DAEMON_COUNT" -ne 1 ]; then
        HEALTH_REASON="ReZygisk 64-bit daemon is not running"
        return 1
    fi

    HEALTH_MONITOR_PID=$RUNTIME_MONITOR_PIDS
    HEALTH_DAEMON_PID=$RUNTIME_DAEMON_PIDS
    process_is_rezygisk_monitor "$HEALTH_MONITOR_PID" || {
        HEALTH_REASON="monitor PID does not belong to ReZygisk monitor mode"
        return 1
    }
    process_is_rezygisk_daemon "$HEALTH_DAEMON_PID" || {
        HEALTH_REASON="daemon PID does not belong to ReZygisk"
        return 1
    }

    daemon_parent=$(process_parent_pid "$HEALTH_DAEMON_PID" 2>/dev/null || true)
    if [ "$daemon_parent" != "$HEALTH_MONITOR_PID" ]; then
        HEALTH_REASON="ReZygisk daemon is not owned by the active monitor"
        return 1
    fi
    if [ ! -S "$WORK_DIR/init_monitor" ]; then
        HEALTH_REASON="ReZygisk monitor socket is unavailable"
        return 1
    fi
    if [ ! -S "$WORK_DIR/cp64.sock" ]; then
        HEALTH_REASON="ReZygisk 64-bit daemon socket is unavailable"
        return 1
    fi

    record_health_snapshot
    if [ "$STATE_ROOT_KERNELSU" -ne 1 ]; then
        HEALTH_REASON="ReZygisk state did not report KernelSU"
        return 1
    fi
    if [ "$MODULE_PROP_MONITOR_OK" -ne 1 ]; then
        HEALTH_REASON="ReZygisk module status did not confirm the active monitor"
        return 1
    fi
    if [ "$STATE_ZYGOTE64_OK" -ne 1 ] || [ "$MODULE_PROP_REZYGISK64_OK" -ne 1 ]; then
        HEALTH_REASON="ReZygisk daemon is active but zygote64 injection is not confirmed"
        return 1
    fi
    if [ -z "$HEALTH_ZYGOTE_PID" ]; then
        HEALTH_REASON="zygote is unavailable"
        return 1
    fi
    if [ -z "$HEALTH_SYSTEM_SERVER_PID" ]; then
        HEALTH_REASON="system_server is unavailable"
        return 1
    fi
    if [ "$(/system/bin/getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
        HEALTH_REASON="Android boot is not complete"
        return 1
    fi

    return 0
}

restore_rezygisk_post_fs() {
    [ -r "$POST_FS_BACKUP" ] || {
        /system/bin/rm -f "$POST_FS_PATCHED" 2>/dev/null || true
        return 0
    }

    if /system/bin/cp -f "$POST_FS_BACKUP" "$REZYGISK_POST_FS" 2>/dev/null; then
        /system/bin/chown 0:0 "$REZYGISK_POST_FS" 2>/dev/null || true
        /system/bin/chmod 0755 "$REZYGISK_POST_FS" 2>/dev/null || true
        /system/bin/restorecon -F "$REZYGISK_POST_FS" 2>/dev/null || true
        /system/bin/rm -f "$POST_FS_BACKUP" "$POST_FS_PATCHED" 2>/dev/null || true
        return 0
    fi

    return 1
}

instrument_rezygisk_post_fs() {
    restore_rezygisk_post_fs || return 1
    [ -r "$REZYGISK_POST_FS" ] || return 1

    /system/bin/cp -f "$REZYGISK_POST_FS" "$POST_FS_BACKUP" 2>/dev/null || return 1
    /system/bin/chown 0:0 "$POST_FS_BACKUP" 2>/dev/null || true
    /system/bin/chmod 0600 "$POST_FS_BACKUP" 2>/dev/null || true

    temp_file=$BRIDGE_DIR/.rezygisk-post-fs-data.tmp
    /system/bin/rm -f "$temp_file"
    patched_count=0

    while IFS= read -r script_line || [ -n "$script_line" ]; do
        case "$script_line" in
            *'./bin/zygisk-ptrace64 monitor &'*)
                indent=${script_line%%./bin/zygisk-ptrace64*}
                /system/bin/printf '%s\n' "${indent}./bin/zygisk-ptrace64 monitor >>\"$MONITOR_LOG\" 2>&1 &" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/cp -f \"$POST_FS_BACKUP\" \"$REZYGISK_POST_FS\" 2>/dev/null || true" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/chown 0:0 \"$REZYGISK_POST_FS\" 2>/dev/null || true" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/chmod 0755 \"$REZYGISK_POST_FS\" 2>/dev/null || true" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/restorecon -F \"$REZYGISK_POST_FS\" 2>/dev/null || true" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/rm -f \"$POST_FS_BACKUP\" \"$POST_FS_PATCHED\" 2>/dev/null || true" >> "$temp_file"
                patched_count=$((patched_count + 1))
                ;;
            *'./bin/zygisk-ptrace32 monitor &'*)
                indent=${script_line%%./bin/zygisk-ptrace32*}
                /system/bin/printf '%s\n' "${indent}./bin/zygisk-ptrace32 monitor >>\"$MONITOR_LOG\" 2>&1 &" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/cp -f \"$POST_FS_BACKUP\" \"$REZYGISK_POST_FS\" 2>/dev/null || true" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/chown 0:0 \"$REZYGISK_POST_FS\" 2>/dev/null || true" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/chmod 0755 \"$REZYGISK_POST_FS\" 2>/dev/null || true" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/restorecon -F \"$REZYGISK_POST_FS\" 2>/dev/null || true" >> "$temp_file"
                /system/bin/printf '%s\n' "${indent}/system/bin/rm -f \"$POST_FS_BACKUP\" \"$POST_FS_PATCHED\" 2>/dev/null || true" >> "$temp_file"
                patched_count=$((patched_count + 1))
                ;;
            *) /system/bin/printf '%s\n' "$script_line" >> "$temp_file" ;;
        esac
    done < "$POST_FS_BACKUP"

    if [ "$patched_count" -lt 1 ]; then
        /system/bin/rm -f "$temp_file"
        restore_rezygisk_post_fs || true
        return 1
    fi

    /system/bin/cp -f "$temp_file" "$REZYGISK_POST_FS" 2>/dev/null || {
        /system/bin/rm -f "$temp_file"
        restore_rezygisk_post_fs || true
        return 1
    }
    /system/bin/rm -f "$temp_file"
    /system/bin/chown 0:0 "$REZYGISK_POST_FS" 2>/dev/null || true
    /system/bin/chmod 0755 "$REZYGISK_POST_FS" 2>/dev/null || true
    /system/bin/restorecon -F "$REZYGISK_POST_FS" 2>/dev/null || true
    /system/bin/touch "$POST_FS_PATCHED" 2>/dev/null || true
    /system/bin/rm -f "$MONITOR_LOG"
    status "MONITOR_LOG_CAPTURE_ARMED=1"
    status "MONITOR_LOG_PATH=$MONITOR_LOG"
    return 0
}

wait_for_pid_list_exit() {
    process_type=$1
    process_list=$2
    wait_count=0
    while [ "$wait_count" -lt 4 ]; do
        alive=0
        for process_pid in $process_list; do
            if /system/bin/toybox kill -0 "$process_pid" 2>/dev/null; then
                if [ "$process_type" = monitor ]; then
                    process_is_rezygisk_monitor "$process_pid" && alive=1
                else
                    process_is_rezygisk_daemon "$process_pid" && alive=1
                fi
            fi
        done
        [ "$alive" -eq 0 ] && return 0
        /system/bin/sleep 1
        wait_count=$((wait_count + 1))
    done
    return 1
}

signal_runtime_list() {
    process_type=$1
    signal_name=$2
    process_list=$3
    for process_pid in $process_list; do
        if [ "$process_type" = monitor ]; then
            process_is_rezygisk_monitor "$process_pid" || continue
        else
            process_is_rezygisk_daemon "$process_pid" || continue
        fi
        /system/bin/toybox kill "-$signal_name" "$process_pid" 2>/dev/null || true
    done
}

prepare_single_runtime() {
    snapshot_runtime PRE
    pre_monitor_pids=$RUNTIME_MONITOR_PIDS
    pre_daemon_pids=$RUNTIME_DAEMON_PIDS

    tracer=$(find_tracer 2>/dev/null || true)
    if [ -n "$tracer" ] && [ -S "$WORK_DIR/init_monitor" ]; then
        TMP_PATH="$WORK_DIR" "$tracer" ctl exit >>"$MONITOR_LOG" 2>&1 || true
        /system/bin/sleep 1
    fi

    remaining_monitors=$(pids_of zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
    if [ -n "$remaining_monitors" ]; then
        signal_runtime_list monitor TERM "$remaining_monitors"
        wait_for_pid_list_exit monitor "$remaining_monitors" || {
            signal_runtime_list monitor KILL "$remaining_monitors"
            wait_for_pid_list_exit monitor "$remaining_monitors" || true
        }
    fi

    remaining_daemons=$(pids_of zygiskd64 zygiskd32 zygiskd)
    if [ -n "$remaining_daemons" ]; then
        signal_runtime_list daemon TERM "$remaining_daemons"
        wait_for_pid_list_exit daemon "$remaining_daemons" || {
            signal_runtime_list daemon KILL "$remaining_daemons"
            wait_for_pid_list_exit daemon "$remaining_daemons" || true
        }
    fi

    snapshot_runtime CLEANUP
    if [ "$RUNTIME_MONITOR_COUNT" -ne 0 ] || [ "$RUNTIME_DAEMON_COUNT" -ne 0 ]; then
        return 1
    fi

    PRE_MONITOR_PIDS=$pre_monitor_pids
    PRE_DAEMON_PIDS=$pre_daemon_pids
    return 0
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

fail_pre() {
    reason=$1
    restore_rezygisk_post_fs || true
    /system/bin/rm -f "$PENDING_FILE"
    /system/bin/echo "failure: $reason" > "$RESULT_FILE"
    status "FAILURE=$reason"
    log "Activation request failed: $reason"
    exit 1
}

verification_inconclusive() {
    reason=$1
    restore_rezygisk_post_fs || true
    snapshot_runtime POST
    record_health_snapshot
    /system/bin/rm -f "$PENDING_FILE" "$VERIFY_PID"
    /system/bin/echo "inconclusive: $reason" > "$RESULT_FILE"
    status "INCONCLUSIVE=$reason"
    status "FAILURE=$reason"
    log "Verification inconclusive; preserving ReZygisk runtime: $reason"
    exit 1
}

verification_not_working() {
    reason=$1
    restore_rezygisk_post_fs || true
    snapshot_runtime POST
    record_health_snapshot
    /system/bin/rm -f "$PENDING_FILE" "$VERIFY_PID"
    /system/bin/echo "not_working: $reason" > "$RESULT_FILE"
    status "NOT_WORKING=1"
    status "FAILURE=$reason"
    log "ReZygisk is not working after soft reboot: $reason"
    exit 1
}

validate_environment() {
    [ "$(/system/bin/id -u)" = "0" ] || fail_pre "bridge did not run as uid 0"
    [ -d "$MODULE_DIR" ] || fail_pre "ReZygisk is not installed"
    [ ! -e "$MODULE_DIR/disable" ] || fail_pre "ReZygisk is disabled"
    [ ! -e "$MODULE_DIR/remove" ] || fail_pre "ReZygisk is pending removal"
    [ -x "$MODULE_DIR/bin/zygisk-ptrace64" ] || fail_pre "ReZygisk tracer is missing"
    [ -r "$REZYGISK_POST_FS" ] || fail_pre "ReZygisk post-fs-data.sh is missing"
}

record_topology_transition() {
    old_monitor_pids=$1
    old_daemon_pids=$2
    new_monitor_pids=$3
    new_daemon_pids=$4

    if list_has_survivor "$old_monitor_pids" "$new_monitor_pids"; then
        status "OLD_MONITOR_SURVIVED=1"
    else
        status "OLD_MONITOR_SURVIVED=0"
    fi
    if list_has_survivor "$old_daemon_pids" "$new_daemon_pids"; then
        status "OLD_DAEMON_SURVIVED=1"
    else
        status "OLD_DAEMON_SURVIVED=0"
    fi
    if list_has_new_pid "$old_monitor_pids" "$new_monitor_pids"; then
        status "NEW_MONITOR_CREATED=1"
    else
        status "NEW_MONITOR_CREATED=0"
    fi
    if list_has_new_pid "$old_daemon_pids" "$new_daemon_pids"; then
        status "NEW_DAEMON_CREATED=1"
    else
        status "NEW_DAEMON_CREATED=0"
    fi

    new_monitor_count=$(count_pids "$new_monitor_pids")
    new_daemon_count=$(count_pids "$new_daemon_pids")
    if [ "$new_monitor_count" -gt 1 ] || [ "$new_daemon_count" -gt 1 ]; then
        status "DUPLICATE_STACK=1"
    else
        status "DUPLICATE_STACK=0"
    fi
}

activate() {
    validate_environment
    status "BRIDGE_DETECTED=1"
    status "BRIDGE_VERSION=0.6.0"
    status "INFO_PREFLIGHT_SKIPPED=1"
    check_conflicts

    if probe_health; then
        status "PREFLIGHT_HEALTH=verified_but_restarting_clean_runtime"
    else
        status "PREFLIGHT_HEALTH=$HEALTH_REASON"
    fi
    prepare_single_runtime || fail_pre "unable to stop every existing ReZygisk monitor and daemon"
    instrument_rezygisk_post_fs || fail_pre "unable to instrument ReZygisk monitor startup"

    ksud=$(find_ksud) || fail_pre "KernelSU userspace binary with soft-reboot support was not found"
    boot_id=$(/system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    [ -n "$boot_id" ] || fail_pre "unable to read current boot ID"
    old_zygote=$(first_pid_of zygote64 zygote 2>/dev/null || true)
    old_system_server=$(first_pid_of system_server 2>/dev/null || true)
    [ -n "$old_zygote" ] || fail_pre "unable to identify current zygote"
    [ -n "$old_system_server" ] || fail_pre "unable to identify current system_server"

    /system/bin/rm -f "$RESULT_FILE" "$SOFT_REBOOT_LOG"
    /system/bin/printf '%s\n%s\n%s\n%s\n%s\n%s\n' \
        "$boot_id" "$STATUS_PATH" "$old_zygote" "$old_system_server" \
        "${PRE_MONITOR_PIDS:-}" "${PRE_DAEMON_PIDS:-}" > "$PENDING_FILE" || \
        fail_pre "unable to create soft-reboot verification marker"
    /system/bin/chown 0:0 "$PENDING_FILE" 2>/dev/null || true
    /system/bin/chmod 0600 "$PENDING_FILE" 2>/dev/null || true

    status "ZYGOTE_OLD_PID=$old_zygote"
    status "SYSTEM_SERVER_OLD_PID=$old_system_server"
    status "SOFT_REBOOT_SCHEDULED=1"
    status "KSU_SOFT_REBOOT_REQUESTED=1"
    /system/bin/echo pending > "$RESULT_FILE"
    log "Requesting KernelSU emulated soft reboot through $ksud"

    "$ksud" soft-reboot >>"$SOFT_REBOOT_LOG" 2>&1
    rc=$?
    if [ "$rc" -ne 0 ]; then
        restore_rezygisk_post_fs || true
        fail_pre "ksud soft-reboot exited with status $rc"
    fi
    exit 0
}

verify() {
    [ -r "$PENDING_FILE" ] || exit 0
    expected_boot=$(/system/bin/toybox sed -n '1p' "$PENDING_FILE" 2>/dev/null)
    old_zygote=$(/system/bin/toybox sed -n '3p' "$PENDING_FILE" 2>/dev/null)
    old_system_server=$(/system/bin/toybox sed -n '4p' "$PENDING_FILE" 2>/dev/null)
    old_monitor_pids=$(/system/bin/toybox sed -n '5p' "$PENDING_FILE" 2>/dev/null)
    old_daemon_pids=$(/system/bin/toybox sed -n '6p' "$PENDING_FILE" 2>/dev/null)
    current_boot=$(/system/bin/cat /proc/sys/kernel/random/boot_id 2>/dev/null)

    restore_rezygisk_post_fs || status "POST_FS_RESTORE_FAILED=1"
    [ -n "$expected_boot" ] || verification_inconclusive "soft-reboot verification marker is incomplete"
    [ "$expected_boot" = "$current_boot" ] || verification_inconclusive "soft-reboot marker belongs to another boot"
    [ -n "$old_zygote" ] || verification_inconclusive "old zygote PID is unavailable"
    [ -n "$old_system_server" ] || verification_inconclusive "old system_server PID is unavailable"

    status "POST_SOFT_REBOOT_VERIFYING=1"
    snapshot_runtime POST_INITIAL
    record_topology_transition "$old_monitor_pids" "$old_daemon_pids" "$RUNTIME_MONITOR_PIDS" "$RUNTIME_DAEMON_PIDS"

    count=0
    duplicate_count=0
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
            status "REZYGISK_MONITOR_PID=$HEALTH_MONITOR_PID"
            status "REZYGISK_DAEMON_PID=$HEALTH_DAEMON_PID"
            record_topology_transition "$old_monitor_pids" "$old_daemon_pids" "$RUNTIME_MONITOR_PIDS" "$RUNTIME_DAEMON_PIDS"
            /system/bin/rm -f "$PENDING_FILE" "$VERIFY_PID"
            /system/bin/echo success > "$RESULT_FILE"
            status "SUCCESS=1"
            log "ReZygisk zygote64 injection verified after KernelSU soft reboot"
            exit 0
        else
            reason=$HEALTH_REASON
            if [ "$reason" = "duplicate ReZygisk monitor or daemon stack" ]; then
                duplicate_count=$((duplicate_count + 1))
            else
                duplicate_count=0
            fi
        fi

        if [ "$reason" != "$last_reason" ]; then
            log "Verification waiting: $reason"
            last_reason=$reason
        fi
        if [ "$duplicate_count" -ge 10 ]; then
            verification_not_working "duplicate ReZygisk monitor or daemon stack persisted after soft reboot"
        fi
        /system/bin/sleep 1
        count=$((count + 1))
    done

    snapshot_runtime POST_TIMEOUT
    record_topology_transition "$old_monitor_pids" "$old_daemon_pids" "$RUNTIME_MONITOR_PIDS" "$RUNTIME_DAEMON_PIDS"
    record_health_snapshot

    if [ "$RUNTIME_MONITOR_COUNT" -gt 1 ] || [ "$RUNTIME_DAEMON_COUNT" -gt 1 ]; then
        verification_not_working "duplicate ReZygisk monitor or daemon stack"
    fi
    if [ "$RUNTIME_DAEMON_COUNT" -eq 1 ] && { [ "$STATE_ZYGOTE64_OK" -ne 1 ] || [ "$MODULE_PROP_REZYGISK64_OK" -ne 1 ]; }; then
        verification_not_working "ReZygisk daemon is active but zygote64 injection remained zero"
    fi
    if [ -n "$current_zygote" ] && [ -n "$current_system_server" ] && \
       [ "$current_zygote" != "$old_zygote" ] && [ "$current_system_server" != "$old_system_server" ] && \
       [ "$(/system/bin/getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
        [ -n "$last_reason" ] || last_reason="ReZygisk did not become functional"
        verification_not_working "$last_reason"
    fi

    [ -n "$last_reason" ] || last_reason="ReZygisk health did not become conclusive"
    verification_inconclusive "$last_reason"
}

case "$MODE" in
    activate) activate ;;
    verify) verify ;;
    *) fail_pre "unknown bridge mode: $MODE" ;;
esac
