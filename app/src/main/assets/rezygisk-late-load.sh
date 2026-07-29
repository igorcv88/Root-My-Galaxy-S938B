#!/system/bin/sh

set -u

MODULE_DIR=/data/adb/modules/rezygisk
WORK_DIR=/data/adb/rezygisk
SELF=/data/local/tmp/rmg-rezygisk-late-load.sh
MONITOR_LOG=/data/local/tmp/rmg-rezygisk-monitor.log
ACTIVATION_LOG=/data/local/tmp/rmg-rezygisk-activation.log
RESULT_FILE=/data/local/tmp/rmg-rezygisk-result
PID_FILE=/data/local/tmp/rmg-rezygisk-monitor.pid
PRELOAD_LOG=/data/local/tmp/rmg-rezygisk-preload.log
PRELOAD_PID=/data/local/tmp/rmg-rezygisk-preload.pid

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

fail() {
    log "ERROR: $*" >&2
    echo "failure: $*" > "$RESULT_FILE"
    exit 1
}

find_tracer() {
    if [ -x "$MODULE_DIR/bin/zygisk-ptrace64" ]; then
        echo "$MODULE_DIR/bin/zygisk-ptrace64"
    elif [ -x "$MODULE_DIR/bin/zygisk-ptrace32" ]; then
        echo "$MODULE_DIR/bin/zygisk-ptrace32"
    else
        return 1
    fi
}

zygote_pid() {
    pidof zygote64 2>/dev/null | awk '{print $1}'
    if [ -z "$(pidof zygote64 2>/dev/null)" ]; then
        pidof zygote 2>/dev/null | awk '{print $1}'
    fi
}

stop_rezygisk_monitor() {
    tracer=$(find_tracer 2>/dev/null || true)
    if [ -n "$tracer" ] && [ -S "$WORK_DIR/init_monitor" ]; then
        "$tracer" ctl exit >/dev/null 2>&1 || true
        sleep 1
    fi

    for pid in $(pidof zygisk-ptrace64 zygisk-ptrace32 2>/dev/null); do
        [ -r "/proc/$pid/cmdline" ] || continue
        cmdline=$(tr '\000' ' ' < "/proc/$pid/cmdline")
        case "$cmdline" in
            *"$MODULE_DIR"*) kill "$pid" 2>/dev/null || true ;;
        esac
    done
}

check_provider_conflicts() {
    for prop in /data/adb/modules/*/module.prop; do
        [ -f "$prop" ] || continue
        dir=${prop%/*}
        [ -e "$dir/disable" ] && continue
        [ -e "$dir/remove" ] && continue
        id=$(sed -n 's/^id=//p' "$prop" | head -n 1)
        case "$id" in
            rezygisk|'') ;;
            zygisksu|zygisknext|zygisk_next|brezygisk)
                fail "another Zygisk provider is enabled: $id"
                ;;
        esac
    done
}

prepare_monitor() {
    [ "$(id -u)" = "0" ] || fail "root shell was not acquired"
    [ -d "$MODULE_DIR" ] || fail "ReZygisk is not installed"
    [ ! -e "$MODULE_DIR/disable" ] || fail "ReZygisk is disabled"
    [ ! -e "$MODULE_DIR/remove" ] || fail "ReZygisk is pending removal"

    check_provider_conflicts
    tracer=$(find_tracer) || fail "ReZygisk tracer binary is missing"

    if [ -f "$MODULE_DIR/module.prop.bak" ]; then
        cp "$MODULE_DIR/module.prop.bak" "$MODULE_DIR/module.prop"
    fi

    mkdir -p /data/adb/ksu/bin
    if [ -x /data/adb/ksud ]; then
        [ -e /data/adb/ksu/bin/ksud ] || ln -s /data/adb/ksud /data/adb/ksu/bin/ksud
    elif [ -x /data/local/tmp/ksud-s25u-kdp ]; then
        [ -e /data/adb/ksu/bin/ksud ] || ln -s /data/local/tmp/ksud-s25u-kdp /data/adb/ksu/bin/ksud
    fi

    stop_rezygisk_monitor
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR" || fail "unable to create ReZygisk work directory"
    chown 0:0 "$WORK_DIR"
    chmod 0555 "$WORK_DIR"
    chcon u:object_r:system_file:s0 "$WORK_DIR" || fail "unable to label ReZygisk work directory"

    rm -f "$RESULT_FILE" "$PID_FILE" "$MONITOR_LOG" "$ACTIVATION_LOG"
    if command -v setsid >/dev/null 2>&1; then
        setsid "$tracer" monitor >>"$MONITOR_LOG" 2>&1 </dev/null &
    else
        nohup "$tracer" monitor >>"$MONITOR_LOG" 2>&1 </dev/null &
    fi
    monitor_pid=$!
    echo "$monitor_pid" > "$PID_FILE"

    ready=0
    count=0
    while [ "$count" -lt 8 ]; do
        kill -0 "$monitor_pid" 2>/dev/null || break
        if [ -S "$WORK_DIR/init_monitor" ]; then
            ready=1
            break
        fi
        sleep 1
        count=$((count + 1))
    done

    if [ "$ready" != "1" ]; then
        stop_rezygisk_monitor
        fail "ReZygisk monitor did not attach to init"
    fi

    sleep 2
    kill -0 "$monitor_pid" 2>/dev/null || fail "ReZygisk monitor exited during preflight"
    log "ReZygisk monitor is tracing init (pid=$monitor_pid)"
}

state_is_healthy() {
    state=$WORK_DIR/state.json
    prop=$MODULE_DIR/module.prop
    [ -s "$state" ] || return 1
    [ -s "$prop" ] || return 1
    grep -q '"root": "KernelSU"' "$state" || return 1
    grep -q '"state": "0"' "$state" || return 1
    grep -q '"state": 1' "$state" || return 1
    grep -q 'Monitor: ✅' "$prop" || return 1
    grep -q 'ReZygisk 64-bit: ✅' "$prop" || return 1
    pidof system_server >/dev/null 2>&1 || return 1
    return 0
}

restart_zygote() {
    old=$(zygote_pid | head -n 1)
    [ -n "$old" ] || return 1
    kill -KILL "$old" 2>/dev/null || return 1
    return 0
}

rollback() {
    reason=$1
    log "Rollback: $reason"
    touch "$MODULE_DIR/disable"
    stop_rezygisk_monitor
    rm -f "$WORK_DIR/state.json"
    restart_zygote || true

    count=0
    while [ "$count" -lt 12 ]; do
        if pidof system_server >/dev/null 2>&1; then
            echo "rollback: $reason" > "$RESULT_FILE"
            log "Rollback completed; ReZygisk disabled"
            return 0
        fi
        sleep 1
        count=$((count + 1))
    done

    echo "rollback-incomplete: $reason" > "$RESULT_FILE"
    return 1
}

activation_worker() {
    sleep 8
    old_pid=$(zygote_pid | head -n 1)
    [ -n "$old_pid" ] || { rollback "zygote was not running before activation"; exit 1; }
    echo "pending" > "$RESULT_FILE"
    log "Restarting zygote for ReZygisk injection"
    restart_zygote || { rollback "unable to restart zygote"; exit 1; }

    last_pid=$old_pid
    generations=0
    count=0
    while [ "$count" -lt 20 ]; do
        current_pid=$(zygote_pid | head -n 1)
        if [ -n "$current_pid" ] && [ "$current_pid" != "$last_pid" ]; then
            generations=$((generations + 1))
            last_pid=$current_pid
            log "Observed zygote generation $generations (pid=$current_pid)"
        fi

        if state_is_healthy; then
            echo "success" > "$RESULT_FILE"
            log "ReZygisk injection verified"
            exit 0
        fi

        if [ "$generations" -ge 2 ]; then
            rollback "zygote restarted repeatedly before verification"
            exit $?
        fi

        monitor_pid=$(cat "$PID_FILE" 2>/dev/null || true)
        if [ -n "$monitor_pid" ] && ! kill -0 "$monitor_pid" 2>/dev/null; then
            rollback "ReZygisk monitor exited during activation"
            exit $?
        fi

        sleep 1
        count=$((count + 1))
    done

    rollback "ReZygisk did not verify within 20 seconds"
}

kernel_su_ready() {
    grep -q '^kernelsu ' /proc/modules 2>/dev/null || [ -e /dev/ksu ]
}

preload_worker() {
    [ "$(id -u)" = "0" ] || fail "preloaded worker lost bootstrap root"
    log "Bootstrap-root worker is waiting for KernelSU"

    count=0
    while [ "$count" -lt 45 ]; do
        if kernel_su_ready; then
            log "KernelSU detected by preloaded worker"
            sleep 2
            schedule_activation
            exit $?
        fi
        sleep 1
        count=$((count + 1))
    done

    fail "KernelSU did not become active within 45 seconds"
}

schedule_activation() {
    prepare_monitor
    if command -v setsid >/dev/null 2>&1; then
        setsid "$SELF" worker >>"$ACTIVATION_LOG" 2>&1 </dev/null &
    else
        nohup "$SELF" worker >>"$ACTIVATION_LOG" 2>&1 </dev/null &
    fi
    log "Activation worker scheduled"
    echo "RMG_REZYGISK_SCHEDULED=1"
}

case "${1:-}" in
    preload) preload_worker ;;
    schedule) schedule_activation ;;
    worker) activation_worker ;;
    *) echo "Usage: $0 <preload|schedule|worker>" >&2; exit 2 ;;
esac
