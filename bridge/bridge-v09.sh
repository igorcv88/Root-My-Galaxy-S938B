#!/system/bin/sh

set -u

STATUS_PATH=${1:-}
MODDIR=${0%/*}
RZ=/data/adb/modules/rezygisk
WORK=/data/adb/rezygisk
TRACER=$RZ/bin/zygisk-ptrace64
LIB_PATH=$RZ/lib64/libzygisk.so
LIB_BACKUP=$RZ/lib64/.libzygisk.so.rmg-original
LIB_STAGE=/dev/.rmg-rezygisk-libzygisk.so
LIB_STAGE_TMP=/dev/.rmg-rezygisk-libzygisk.so.tmp
RESULT=/data/local/tmp/rmg-rezygisk-result
STATUS=/data/local/tmp/rmg-rezygisk-bridge-status
MONLOG=/data/local/tmp/rmg-rezygisk-monitor.log
RZLOG=/data/local/tmp/rmg-rezygisk-logcat.log
RZLOG_PID=/data/local/tmp/rmg-rezygisk-logcat.pid
RUNLOG=/data/local/tmp/rmg-rezygisk-targeted-restart.log

emit() {
    printf '%s\n' "$1" >> "$STATUS" 2>/dev/null || true
    [ -z "$STATUS_PATH" ] || printf '%s\n' "$1" >> "$STATUS_PATH" 2>/dev/null || true
}

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$RUNLOG"
}

valid_pid() {
    case "${1:-}" in
        ''|*[!0-9]*) return 1 ;;
        *) return 0 ;;
    esac
}

cmdline() {
    [ -r "/proc/$1/cmdline" ] || return 1
    toybox tr '\000' ' ' < "/proc/$1/cmdline" 2>/dev/null
}

comm() {
    [ -r "/proc/$1/comm" ] || return 1
    cat "/proc/$1/comm" 2>/dev/null
}

ppid() {
    toybox sed -n 's/^PPid:[[:space:]]*//p' "/proc/$1/status" 2>/dev/null | toybox head -n 1
}

tracerpid() {
    toybox sed -n 's/^TracerPid:[[:space:]]*//p' "/proc/$1/status" 2>/dev/null | toybox head -n 1
}

first_pid() {
    for pid in $(pidof "$@" 2>/dev/null || true); do
        valid_pid "$pid" && { echo "$pid"; return 0; }
    done
    return 1
}

classified_pids() {
    kind=$1
    shift
    out=
    for name in "$@"; do
        for pid in $(pidof "$name" 2>/dev/null || true); do
            valid_pid "$pid" || continue
            c=$(comm "$pid" 2>/dev/null || true)
            a=$(cmdline "$pid" 2>/dev/null || true)
            case "$kind:$c:$a" in
                monitor:zygisk-ptrace*:*) case "$a" in *zygisk-ptrace*monitor*) ;; *) continue ;; esac ;;
                trace:zygisk-ptrace*:*) case "$a" in *zygisk-ptrace*trace*) ;; *) continue ;; esac ;;
                daemon:zygiskd*:*) ;;
                *) continue ;;
            esac
            case " $out " in *" $pid "*) ;; *) out="${out:+$out }$pid" ;; esac
        done
    done
    printf '%s\n' "$out"
}

count_pids() {
    n=0
    for pid in $1; do valid_pid "$pid" && n=$((n + 1)); done
    echo "$n"
}

snapshot() {
    tag=$1
    MONITORS=$(classified_pids monitor zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
    TRACES=$(classified_pids trace zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
    DAEMONS=$(classified_pids daemon zygiskd64 zygiskd32 zygiskd)
    MON_COUNT=$(count_pids "$MONITORS")
    TRACE_COUNT=$(count_pids "$TRACES")
    DAE_COUNT=$(count_pids "$DAEMONS")
    INIT_TRACER=$(tracerpid 1 2>/dev/null || true)
    [ -n "$INIT_TRACER" ] || INIT_TRACER=0
    emit "${tag}_MONITOR_PIDS=${MONITORS:-none}"
    emit "${tag}_TRACE_PIDS=${TRACES:-none}"
    emit "${tag}_DAEMON_PIDS=${DAEMONS:-none}"
    emit "${tag}_MONITOR_COUNT=$MON_COUNT"
    emit "${tag}_TRACE_COUNT=$TRACE_COUNT"
    emit "${tag}_DAEMON_COUNT=$DAE_COUNT"
    emit "${tag}_INIT_TRACER_PID=$INIT_TRACER"
}

file_context() {
    toybox ls -Z "$1" 2>/dev/null | toybox sed -n 's/.*\(u:object_r:[^[:space:]]*\).*/\1/p' | toybox head -n 1
}

restore_library() {
    remove_stage=${1:-no}
    if [ -e "$LIB_BACKUP" ] || [ -L "$LIB_BACKUP" ]; then
        rm -f "$LIB_PATH" 2>/dev/null || true
        mv -f "$LIB_BACKUP" "$LIB_PATH" 2>/dev/null || return 1
        chown 0:0 "$LIB_PATH" 2>/dev/null || true
        chmod 0644 "$LIB_PATH" 2>/dev/null || true
        restorecon -F "$LIB_PATH" 2>/dev/null || true
    elif [ -L "$LIB_PATH" ]; then
        return 1
    fi
    [ "$remove_stage" = yes ] && rm -f "$LIB_STAGE" "$LIB_STAGE_TMP" 2>/dev/null || true
    return 0
}

recover_interrupted() {
    if [ -e "$LIB_BACKUP" ] || [ -L "$LIB_BACKUP" ]; then
        restore_library no
        return $?
    fi
    if [ -L "$LIB_PATH" ]; then
        target=$(readlink "$LIB_PATH" 2>/dev/null || true)
        [ -n "$target" ] && [ -e "$target" ] || return 1
    fi
    return 0
}

stage_library() {
    recover_interrupted || return 1
    [ -f "$LIB_PATH" ] && [ ! -L "$LIB_PATH" ] && [ -r "$LIB_PATH" ] || return 1
    rm -f "$LIB_STAGE_TMP" "$LIB_STAGE" 2>/dev/null || true
    cp -f "$LIB_PATH" "$LIB_STAGE_TMP" 2>/dev/null || return 1
    chown 0:0 "$LIB_STAGE_TMP" 2>/dev/null || true
    chmod 0644 "$LIB_STAGE_TMP" 2>/dev/null || true
    ctx=$(file_context "$LIB_PATH" 2>/dev/null || true)
    desired_ctx=${ctx:-u:object_r:system_file:s0}
    chcon "$desired_ctx" "$LIB_STAGE_TMP" 2>/dev/null || {
        desired_ctx=u:object_r:system_file:s0
        chcon "$desired_ctx" "$LIB_STAGE_TMP" 2>/dev/null || return 1
    }
    mv -f "$LIB_STAGE_TMP" "$LIB_STAGE" 2>/dev/null || return 1
    cmp -s "$LIB_PATH" "$LIB_STAGE" 2>/dev/null || return 1
    mv "$LIB_PATH" "$LIB_BACKUP" 2>/dev/null || return 1
    ln -s "$LIB_STAGE" "$LIB_PATH" 2>/dev/null || {
        mv -f "$LIB_BACKUP" "$LIB_PATH" 2>/dev/null || true
        return 1
    }
    [ "$(readlink "$LIB_PATH" 2>/dev/null || true)" = "$LIB_STAGE" ] || return 1
    emit DEFEX_TMPFS_BYPASS_ARMED=1
    emit "DEFEX_STAGED_LIBRARY=$LIB_STAGE"
    emit "DEFEX_STAGED_CONTEXT=$(file_context "$LIB_STAGE" 2>/dev/null || echo unknown)"
}

stop_capture() {
    [ -r "$RZLOG_PID" ] || return 0
    pid=$(cat "$RZLOG_PID" 2>/dev/null || true)
    valid_pid "$pid" && toybox kill -TERM "$pid" 2>/dev/null || true
    rm -f "$RZLOG_PID"
}

start_capture() {
    stop_capture || true
    rm -f "$RZLOG"
    /system/bin/toybox setsid /system/bin/logcat -b all -v threadtime \
        -s zygisk-ptrace64:V zygisk-core64:V zygisk-injector64:V zygiskd64:V \
           zygisk-ptrace32:V zygisk-core32:V zygisk-injector32:V zygiskd32:V \
           zygisk-sh:V linker:V libc:V '*:S' >"$RZLOG" 2>&1 </dev/null &
    pid=$!
    echo "$pid" > "$RZLOG_PID"
    sleep 1
    if toybox kill -0 "$pid" 2>/dev/null; then
        emit LOGCAT_CAPTURE_STARTED=1
    else
        rm -f "$RZLOG_PID"
        emit LOGCAT_CAPTURE_UNAVAILABLE=1
    fi
}

stop_runtime() {
    monitors=$(classified_pids monitor zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
    traces=$(classified_pids trace zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
    [ -z "$traces" ] || return 1
    if [ -n "$monitors" ] && [ -S "$WORK/init_monitor" ]; then
        TMP_PATH="$WORK" "$TRACER" ctl exit >>"$MONLOG" 2>&1 || true
        sleep 1
    fi
    for pid in $monitors; do toybox kill -TERM "$pid" 2>/dev/null || true; done
    sleep 1
    daemons=$(classified_pids daemon zygiskd64 zygiskd32 zygiskd)
    for pid in $daemons; do toybox kill -TERM "$pid" 2>/dev/null || true; done
    sleep 1
    snapshot CLEANUP
    [ "$MON_COUNT" -eq 0 ] && [ "$TRACE_COUNT" -eq 0 ] && [ "$DAE_COUNT" -eq 0 ]
}

prepare_workspace() {
    rm -rf "$WORK" 2>/dev/null || return 1
    mkdir -p "$WORK" 2>/dev/null || return 1
    chmod 0555 "$WORK" 2>/dev/null || true
    chcon u:object_r:system_file:s0 "$WORK" 2>/dev/null || true
    [ -r /data/adb/post-fs-data.d/rezygisk.sh ] && /system/bin/sh /data/adb/post-fs-data.d/rezygisk.sh >/dev/null 2>&1 || true
}

launch_monitor() {
    rm -f "$MONLOG"
    cd "$RZ" || return 1
    TMP_PATH="$WORK" /system/bin/toybox setsid "$TRACER" monitor >>"$MONLOG" 2>&1 </dev/null &
    launch_pid=$!
    emit "MONITOR_LAUNCH_PID=$launch_pid"
    n=0
    while [ "$n" -lt 10 ]; do
        monitors=$(classified_pids monitor zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
        for pid in $monitors; do
            if [ "$(tracerpid 1 2>/dev/null || true)" = "$pid" ] && [ -S "$WORK/init_monitor" ]; then
                MONITOR_PID=$pid
                emit "REZYGISK_MONITOR_PID=$pid"
                return 0
            fi
        done
        sleep 1
        n=$((n + 1))
    done
    return 1
}

read_injection_state() {
    ZYGOTE_OK=0
    PROP_OK=0
    if [ -r "$WORK/state.json" ]; then
        in_zygote=0
        while IFS= read -r line; do
            if [ "$in_zygote" -eq 0 ]; then
                case "$line" in *'"zygote"'*'{') in_zygote=1 ;; esac
                continue
            fi
            compact=$(printf '%s' "$line" | toybox tr -d ' \t\r')
            case "$compact" in
                *'"64":1'*) ZYGOTE_OK=1; break ;;
                *'}'*) break ;;
            esac
        done < "$WORK/state.json"
    fi
    grep -q 'ReZygisk 64-bit: ✅' "$RZ/module.prop" 2>/dev/null && PROP_OK=1
}

failure_seen() {
    grep -qiE 'failed to inject|remote CSOLoader mapping failed|Failed to open remote file|Immutable root violation|restart too much times' "$MONLOG" "$RZLOG" 2>/dev/null
}

pause_monitor_safely() {
    if [ -S "$WORK/init_monitor" ]; then
        TMP_PATH="$WORK" "$TRACER" ctl stop >>"$MONLOG" 2>&1 || true
    fi
    emit MONITOR_PAUSE_REQUESTED=1
}

wait_android_stable() {
    n=0
    while [ "$n" -lt 40 ]; do
        z=$(first_pid zygote64 2>/dev/null || first_pid zygote 2>/dev/null || true)
        s=$(first_pid system_server 2>/dev/null || true)
        if [ -n "$z" ] && [ -n "$s" ] && [ "$(getprop init.svc.zygote 2>/dev/null)" = running ]; then
            return 0
        fi
        sleep 1
        n=$((n + 1))
    done
    return 1
}

finish_failure() {
    reason=$1
    pause_monitor_safely || true
    wait_android_stable || true
    stop_capture || true
    restore_library yes || emit DEFEX_LIBRARY_RESTORE_FAILED=1
    echo "not_working: $reason" > "$RESULT"
    emit NOT_WORKING=1
    emit "FAILURE=$reason"
    log "ReZygisk targeted activation failed: $reason"
    exit 1
}

finish_success() {
    stop_capture || true
    restore_library no || emit DEFEX_LIBRARY_RESTORE_FAILED=1
    echo success > "$RESULT"
    emit SUCCESS=1
    log 'ReZygisk zygote64 injection verified after targeted zygote restart'
    exit 0
}

validate() {
    [ "$(id -u)" = 0 ] || return 1
    [ -x "$TRACER" ] || return 1
    [ -r "$LIB_PATH" ] || return 1
    [ ! -e "$RZ/disable" ] || return 1
    [ ! -e "$RZ/remove" ] || return 1
    return 0
}

activate() {
    rm -f "$STATUS" "$RESULT" "$RUNLOG"
    emit BRIDGE_DETECTED=1
    emit BRIDGE_VERSION=0.9.0
    emit ACTIVATION_MODE=targeted_zygote_restart
    emit GLOBAL_SOFT_REBOOT_DISABLED=1

    validate || { echo 'failure: ReZygisk environment is unavailable' > "$RESULT"; emit 'FAILURE=ReZygisk environment is unavailable'; exit 1; }
    recover_interrupted || { echo 'failure: unable to recover staged library state' > "$RESULT"; emit 'FAILURE=unable to recover staged library state'; exit 1; }
    snapshot PRE
    stop_runtime || { echo 'failure: unable to reach a clean ReZygisk runtime' > "$RESULT"; emit 'FAILURE=unable to reach a clean ReZygisk runtime'; exit 1; }
    stage_library || { echo 'failure: unable to stage DEFEX-safe libzygisk.so' > "$RESULT"; emit 'FAILURE=unable to stage DEFEX-safe libzygisk.so'; exit 1; }
    start_capture || true
    prepare_workspace || finish_failure 'unable to prepare ReZygisk workspace'
    launch_monitor || finish_failure 'unable to start ReZygisk monitor on init'

    oldz=$(first_pid zygote64 2>/dev/null || first_pid zygote 2>/dev/null || true)
    olds=$(first_pid system_server 2>/dev/null || true)
    [ -n "$oldz" ] && [ -n "$olds" ] || finish_failure 'unable to identify current zygote or system_server'
    emit "ZYGOTE_OLD_PID=$oldz"
    emit "SYSTEM_SERVER_OLD_PID=$olds"

    log 'Requesting targeted init restart of zygote only'
    /system/bin/setprop ctl.restart zygote

    changed=0
    n=0
    lastz=$oldz
    restart_count=0
    while [ "$n" -lt 60 ]; do
        z=$(first_pid zygote64 2>/dev/null || first_pid zygote 2>/dev/null || true)
        s=$(first_pid system_server 2>/dev/null || true)
        if [ -n "$z" ] && [ "$z" != "$lastz" ]; then
            restart_count=$((restart_count + 1))
            lastz=$z
            emit "ZYGOTE_RESTART_${restart_count}_PID=$z"
        fi
        [ -n "$z" ] && [ "$z" != "$oldz" ] && changed=1

        read_injection_state
        snapshot VERIFY
        if [ "$changed" -eq 1 ] && [ -n "$s" ] && [ "$s" != "$olds" ] && \
           [ "$ZYGOTE_OK" -eq 1 ] && [ "$PROP_OK" -eq 1 ] && \
           [ "$MON_COUNT" -eq 1 ] && [ "$DAE_COUNT" -eq 1 ] && \
           [ "$INIT_TRACER" = "$MONITOR_PID" ] && [ -S "$WORK/cp64.sock" ]; then
            emit "ZYGOTE_NEW_PID=$z"
            emit "SYSTEM_SERVER_NEW_PID=$s"
            emit "ZYGOTE_RESTART_COUNT=$restart_count"
            finish_success
        fi

        if failure_seen || [ "$restart_count" -ge 2 ]; then
            pause_monitor_safely || true
            finish_failure 'injector failed; monitor paused before a zygote restart loop could continue'
        fi

        if [ "$n" -eq 10 ] && [ "$changed" -eq 0 ]; then
            finish_failure 'targeted zygote restart was not accepted by init'
        fi

        sleep 1
        n=$((n + 1))
    done

    finish_failure 'zygote64 injection did not verify within 60 seconds'
}

case "${2:-activate}" in
    activate) activate ;;
    recover) recover_interrupted ;;
    *) echo "failure: unknown mode" > "$RESULT"; emit 'FAILURE=unknown mode'; exit 1 ;;
esac
