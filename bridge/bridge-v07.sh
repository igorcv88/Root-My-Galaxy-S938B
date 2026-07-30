#!/system/bin/sh
set -u

STATUS_PATH=${1:-}
MODE=${2:-activate}
MODDIR=${0%/*}
RZ=/data/adb/modules/rezygisk
WORK=/data/adb/rezygisk
POSTFS=$RZ/post-fs-data.sh
BACKUP=$MODDIR/.rezygisk-post-fs-data.backup
PATCHED=$MODDIR/.rezygisk-post-fs-data.patched
RESULT=/data/local/tmp/rmg-rezygisk-result
STATUS=/data/local/tmp/rmg-rezygisk-bridge-status
PENDING=/data/local/tmp/rmg-rezygisk-soft-reboot-pending
SOFTLOG=/data/local/tmp/rmg-rezygisk-soft-reboot.log
VERIFY_PID=/data/local/tmp/rmg-rezygisk-verify.pid
MONLOG=/data/local/tmp/rmg-rezygisk-monitor.log
RZLOG=/data/local/tmp/rmg-rezygisk-logcat.log
RZLOG_PID=/data/local/tmp/rmg-rezygisk-logcat.pid
RUNLOG=/data/local/tmp/rmg-rezygisk-post-fs.log
ACTIVE=/data/local/tmp/rmg-rezygisk-post-fs-active
LOCK=/data/local/tmp/rmg-rezygisk-post-fs.lock
KSU_LOG=/data/local/tmp/rmg-rezygisk-ksu-logcat.log
KSU_OLD=/data/local/tmp/rmg-rezygisk-ksu-logcat.old.log

emit() {
    printf '%s\n' "$1" >> "$STATUS" 2>/dev/null || true
    [ -z "$STATUS_PATH" ] || printf '%s\n' "$1" >> "$STATUS_PATH" 2>/dev/null || true
}
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
valid_pid() { case "${1:-}" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
cmdline() { [ -r "/proc/$1/cmdline" ] && toybox tr '\000' ' ' < "/proc/$1/cmdline" 2>/dev/null; }
comm() { [ -r "/proc/$1/comm" ] && cat "/proc/$1/comm" 2>/dev/null; }
ppid() { toybox sed -n 's/^PPid:[[:space:]]*//p' "/proc/$1/status" 2>/dev/null | toybox head -n 1; }
tracerpid() { toybox sed -n 's/^TracerPid:[[:space:]]*//p' "/proc/$1/status" 2>/dev/null | toybox head -n 1; }

append_pid() {
    list=$1; pid=$2
    for old in $list; do [ "$old" = "$pid" ] && { printf '%s\n' "$list"; return; }; done
    [ -z "$list" ] && printf '%s\n' "$pid" || printf '%s %s\n' "$list" "$pid"
}

classified_pids() {
    kind=$1; shift; out=
    for name in "$@"; do
        for pid in $(pidof "$name" 2>/dev/null || true); do
            valid_pid "$pid" || continue
            c=$(comm "$pid" || true); a=$(cmdline "$pid" || true)
            case "$kind:$c:$a" in
                monitor:zygisk-ptrace*:*) case "$a" in *zygisk-ptrace*monitor*) ;; *) continue;; esac ;;
                trace:zygisk-ptrace*:*) case "$a" in *zygisk-ptrace*trace*) ;; *) continue;; esac ;;
                daemon:zygiskd*:*) ;;
                *) continue ;;
            esac
            out=$(append_pid "$out" "$pid")
        done
    done
    printf '%s\n' "$out"
}
count() { n=0; for pid in $1; do valid_pid "$pid" && n=$((n+1)); done; echo "$n"; }
first_pid() { for pid in $(pidof "$@" 2>/dev/null || true); do valid_pid "$pid" && { echo "$pid"; return 0; }; done; return 1; }

snapshot() {
    tag=$1
    MONITORS=$(classified_pids monitor zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
    TRACES=$(classified_pids trace zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
    DAEMONS=$(classified_pids daemon zygiskd64 zygiskd32 zygiskd)
    MON_COUNT=$(count "$MONITORS"); TRACE_COUNT=$(count "$TRACES"); DAE_COUNT=$(count "$DAEMONS")
    INIT_TRACER=$(tracerpid 1 || true); [ -n "$INIT_TRACER" ] || INIT_TRACER=0
    pairs=
    for daemon in $DAEMONS; do pair="$daemon<-$(ppid "$daemon" || echo unknown)"; [ -z "$pairs" ] && pairs=$pair || pairs="$pairs $pair"; done
    emit "${tag}_MONITOR_PIDS=${MONITORS:-none}"
    emit "${tag}_TRACE_PIDS=${TRACES:-none}"
    emit "${tag}_DAEMON_PIDS=${DAEMONS:-none}"
    emit "${tag}_MONITOR_COUNT=$MON_COUNT"
    emit "${tag}_TRACE_COUNT=$TRACE_COUNT"
    emit "${tag}_DAEMON_COUNT=$DAE_COUNT"
    emit "${tag}_INIT_TRACER_PID=$INIT_TRACER"
    emit "${tag}_MONITOR_DAEMON_PAIRS=${pairs:-none}"
}

find_ksud() {
    for p in /data/adb/ksu/bin/ksud /data/adb/ksud /data/local/tmp/ksud-s25u-kdp /data/local/tmp/.ksud-stage; do
        [ -x "$p" ] && { echo "$p"; return 0; }
    done
    return 1
}

stop_capture() {
    [ -r "$RZLOG_PID" ] || return 0
    pid=$(cat "$RZLOG_PID" 2>/dev/null || true)
    valid_pid "$pid" && toybox kill -TERM "$pid" 2>/dev/null || true
    rm -f "$RZLOG_PID"
}
start_capture() {
    stop_capture
    rm -f "$RZLOG"
    toybox setsid logcat -b all -v threadtime \
        -s zygisk-core64:V zygisk-injector64:V zygiskd64:V \
           zygisk-core32:V zygisk-injector32:V zygiskd32:V \
           zygisk-sh:V linker:V libc:V '*:S' >"$RZLOG" 2>&1 </dev/null &
    pid=$!; echo "$pid" > "$RZLOG_PID"; sleep 1
    toybox kill -0 "$pid" 2>/dev/null
}
copy_ksu_logs() {
    [ -r /data/adb/ksu/log/logcat.log ] && cp -f /data/adb/ksu/log/logcat.log "$KSU_LOG" 2>/dev/null || true
    [ -r /data/adb/ksu/log/logcat.old.log ] && cp -f /data/adb/ksu/log/logcat.old.log "$KSU_OLD" 2>/dev/null || true
}
restore_postfs() {
    [ -r "$BACKUP" ] || { rm -f "$PATCHED" "$ACTIVE"; rm -rf "$LOCK"; return 0; }
    cp -f "$BACKUP" "$POSTFS" 2>/dev/null || return 1
    chown 0:0 "$POSTFS" 2>/dev/null || true; chmod 0755 "$POSTFS" 2>/dev/null || true; restorecon -F "$POSTFS" 2>/dev/null || true
    rm -f "$BACKUP" "$PATCHED" "$ACTIVE"; rm -rf "$LOCK"
}
finish_diag() { stop_capture || true; copy_ksu_logs || true; restore_postfs || emit 'POST_FS_RESTORE_FAILED=1'; }

instrument_postfs() {
    restore_postfs || return 1
    [ -r "$POSTFS" ] || return 1
    cp -f "$POSTFS" "$BACKUP" || return 1
    chown 0:0 "$BACKUP" 2>/dev/null || true; chmod 0600 "$BACKUP" 2>/dev/null || true
    tmp=$MODDIR/.rezygisk-post-fs-data.tmp
    rm -f "$tmp"
    {
        IFS= read -r first || true
        printf '%s\n' "$first"
        cat <<GUARD
# Root My Galaxy v0.7 boot-scoped idempotence guard.
RMG_BOOT=\$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
RMG_ACTIVE="$ACTIVE"
RMG_LOCK="$LOCK"
RMG_LOG="$RUNLOG"
RMG_MONLOG="$MONLOG"
rmg_note() { echo "[\$(date '+%Y-%m-%d %H:%M:%S')] \$*" >> "\$RMG_LOG" 2>/dev/null || true; }
rmg_existing_monitor() {
  for p in \$(pidof zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace 2>/dev/null); do
    [ -r "/proc/\$p/cmdline" ] || continue
    a=\$(toybox tr '\\000' ' ' < "/proc/\$p/cmdline" 2>/dev/null)
    case "\$a" in *zygisk-ptrace*monitor*) return 0;; esac
  done
  return 1
}
if [ -r "\$RMG_ACTIVE" ] && [ "\$(cat "\$RMG_ACTIVE" 2>/dev/null)" = "\$RMG_BOOT" ]; then
  rmg_note "duplicate post-fs-data invocation skipped: active marker"
  exit 0
fi
if rmg_existing_monitor; then
  rmg_note "duplicate post-fs-data invocation skipped: monitor already running"
  echo "\$RMG_BOOT" > "\$RMG_ACTIVE" 2>/dev/null || true
  exit 0
fi
if ! mkdir "\$RMG_LOCK" 2>/dev/null; then
  rmg_note "concurrent post-fs-data invocation skipped: lock busy"
  exit 0
fi
trap 'rmdir "\$RMG_LOCK" 2>/dev/null || true' EXIT HUP INT TERM
echo "\$RMG_BOOT" > "\$RMG_ACTIVE"
rmg_note "primary post-fs-data invocation accepted"
GUARD
        while IFS= read -r line || [ -n "$line" ]; do
            case "$line" in
                *'./bin/zygisk-ptrace64 monitor &'*)
                    indent=${line%%./bin/zygisk-ptrace64*}
                    printf '%s\n' "${indent}./bin/zygisk-ptrace64 monitor >>\"$MONLOG\" 2>&1 &"
                    ;;
                *'./bin/zygisk-ptrace32 monitor &'*)
                    indent=${line%%./bin/zygisk-ptrace32*}
                    printf '%s\n' "${indent}./bin/zygisk-ptrace32 monitor >>\"$MONLOG\" 2>&1 &"
                    ;;
                *) printf '%s\n' "$line" ;;
            esac
        done
    } < "$BACKUP" > "$tmp" || { rm -f "$tmp"; restore_postfs; return 1; }
    grep -q 'Root My Galaxy v0.7 boot-scoped' "$tmp" || { rm -f "$tmp"; restore_postfs; return 1; }
    grep -q "$MONLOG" "$tmp" || { rm -f "$tmp"; restore_postfs; return 1; }
    cp -f "$tmp" "$POSTFS" || { rm -f "$tmp"; restore_postfs; return 1; }
    rm -f "$tmp"; chown 0:0 "$POSTFS" 2>/dev/null || true; chmod 0755 "$POSTFS"; restorecon -F "$POSTFS" 2>/dev/null || true
    touch "$PATCHED"; rm -f "$MONLOG" "$RUNLOG" "$ACTIVE"; rm -rf "$LOCK"
    emit 'POST_FS_IDEMPOTENCE_GUARD_ARMED=1'; emit "MONITOR_LOG_PATH=$MONLOG"; emit "LOGCAT_LOG_PATH=$RZLOG"
}

signal_list() {
    kind=$1; sig=$2; list=$3
    for pid in $list; do
        case "$kind" in monitor) now=$(classified_pids monitor zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace);; daemon) now=$(classified_pids daemon zygiskd64 zygiskd32 zygiskd);; esac
        for live in $now; do [ "$live" = "$pid" ] && toybox kill "-$sig" "$pid" 2>/dev/null || true; done
    done
}
wait_gone() { kind=$1; tries=0; while [ $tries -lt 4 ]; do [ -z "$(classified_pids "$kind" zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace zygiskd64 zygiskd32 zygiskd)" ] && return 0; sleep 1; tries=$((tries+1)); done; return 1; }

clean_runtime() {
    snapshot PRE
    PRE_MONITORS=$MONITORS; PRE_DAEMONS=$DAEMONS
    [ "$TRACE_COUNT" -eq 0 ] || { PREPARE_REASON="active ReZygisk trace process detected; refusing to kill an injector"; return 1; }
    tracer=$RZ/bin/zygisk-ptrace64
    [ -x "$tracer" ] && [ -S "$WORK/init_monitor" ] && TMP_PATH="$WORK" "$tracer" ctl exit >>"$MONLOG" 2>&1 || true
    sleep 1
    mons=$(classified_pids monitor zygisk-ptrace64 zygisk-ptrace32 zygisk-ptrace)
    [ -z "$mons" ] || { signal_list monitor TERM "$mons"; wait_gone monitor || { signal_list monitor KILL "$mons"; wait_gone monitor || true; }; }
    daes=$(classified_pids daemon zygiskd64 zygiskd32 zygiskd)
    [ -z "$daes" ] || { signal_list daemon TERM "$daes"; wait_gone daemon || { signal_list daemon KILL "$daes"; wait_gone daemon || true; }; }
    snapshot CLEANUP
    [ "$MON_COUNT" -eq 0 ] && [ "$DAE_COUNT" -eq 0 ] && [ "$TRACE_COUNT" -eq 0 ] || { PREPARE_REASON="ReZygisk runtime did not reach a zero-process baseline"; return 1; }
}

read_state() {
    ROOT_OK=0; ZYGOTE_OK=0; PROP_MON=0; PROP_RZ=0
    [ -r "$WORK/state.json" ] && grep -q '"root"[[:space:]]*:[[:space:]]*"KernelSU"' "$WORK/state.json" && ROOT_OK=1
    [ -r "$WORK/state.json" ] && grep -A8 '"zygote"' "$WORK/state.json" | grep -q '"64"[[:space:]]*:[[:space:]]*1' && ZYGOTE_OK=1
    [ -r "$RZ/module.prop" ] && grep -q 'Monitor: ✅' "$RZ/module.prop" && PROP_MON=1
    [ -r "$RZ/module.prop" ] && grep -q 'ReZygisk 64-bit: ✅' "$RZ/module.prop" && PROP_RZ=1
}
health() {
    snapshot HEALTH
    HEALTH_REASON=
    [ "$MON_COUNT" -le 1 ] && [ "$DAE_COUNT" -le 1 ] || { HEALTH_REASON='duplicate ReZygisk monitor or daemon stack'; return 1; }
    [ "$MON_COUNT" -eq 1 ] || { HEALTH_REASON='ReZygisk monitor is not running'; return 1; }
    [ "$DAE_COUNT" -eq 1 ] || { HEALTH_REASON='ReZygisk 64-bit daemon is not running'; return 1; }
    mon=$MONITORS; daemon=$DAEMONS
    [ "$(ppid "$daemon" || true)" = "$mon" ] || { HEALTH_REASON='daemon is not owned by active monitor'; return 1; }
    [ "$INIT_TRACER" = "$mon" ] || { HEALTH_REASON='active monitor is not tracing init'; return 1; }
    [ -S "$WORK/init_monitor" ] || { HEALTH_REASON='monitor socket is unavailable'; return 1; }
    [ -S "$WORK/cp64.sock" ] || { HEALTH_REASON='64-bit daemon socket is unavailable'; return 1; }
    read_state
    [ "$ROOT_OK" -eq 1 ] || { HEALTH_REASON='state did not report KernelSU'; return 1; }
    [ "$ZYGOTE_OK" -eq 1 ] && [ "$PROP_MON" -eq 1 ] && [ "$PROP_RZ" -eq 1 ] || { HEALTH_REASON='daemon active but zygote64 injection is not confirmed'; return 1; }
    [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] || { HEALTH_REASON='Android boot is not complete'; return 1; }
    HEALTH_MON=$mon; HEALTH_DAEMON=$daemon
}

fail_pre() { reason=$1; stop_capture || true; copy_ksu_logs || true; restore_postfs || true; rm -f "$PENDING"; echo "failure: $reason" > "$RESULT"; emit "FAILURE=$reason"; log "Activation request failed: $reason"; exit 1; }
terminal() {
    state=$1; reason=$2
    snapshot POST; read_state
    emit "STATE_ROOT_KERNELSU=$ROOT_OK"; emit "STATE_ZYGOTE64_OK=$ZYGOTE_OK"; emit "MODULE_PROP_MONITOR_OK=$PROP_MON"; emit "MODULE_PROP_REZYGISK64_OK=$PROP_RZ"
    finish_diag; rm -f "$PENDING" "$VERIFY_PID"
    case "$state" in success) echo success > "$RESULT"; emit SUCCESS=1;; not_working) echo "not_working: $reason" > "$RESULT"; emit NOT_WORKING=1; emit "FAILURE=$reason";; *) echo "inconclusive: $reason" > "$RESULT"; emit "INCONCLUSIVE=$reason"; emit "FAILURE=$reason";; esac
    log "ReZygisk verification result: $state: $reason"; [ "$state" = success ] && exit 0 || exit 1
}

validate() {
    [ "$(id -u)" = 0 ] || fail_pre 'bridge did not run as uid 0'
    [ -d "$RZ" ] && [ ! -e "$RZ/disable" ] && [ ! -e "$RZ/remove" ] || fail_pre 'ReZygisk is unavailable or disabled'
    [ -x "$RZ/bin/zygisk-ptrace64" ] && [ -r "$POSTFS" ] || fail_pre 'ReZygisk tracer or post-fs-data.sh is missing'
    for prop in /data/adb/modules/*/module.prop; do
        [ -f "$prop" ] || continue; d=${prop%/*}; [ -e "$d/disable" ] && continue
        id=$(toybox sed -n 's/^id=//p' "$prop" | toybox head -n1)
        case "$id" in zygisksu|zygisknext|zygisk_next|brezygisk) fail_pre "another Zygisk provider is enabled: $id";; esac
    done
}

activate() {
    validate; rm -f "$STATUS"; emit BRIDGE_DETECTED=1; emit BRIDGE_VERSION=0.7.0; emit INFO_PREFLIGHT_SKIPPED=1
    clean_runtime || fail_pre "${PREPARE_REASON:-unable to clean ReZygisk runtime}"
    instrument_postfs || fail_pre 'unable to install boot-scoped ReZygisk post-fs guard'
    ksud=$(find_ksud) || fail_pre 'KernelSU userspace binary with soft-reboot support was not found'
    boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null); oldz=$(first_pid zygote64 2>/dev/null || first_pid zygote 2>/dev/null || true); olds=$(first_pid system_server 2>/dev/null || true)
    [ -n "$boot" ] && [ -n "$oldz" ] && [ -n "$olds" ] || fail_pre 'unable to identify boot, zygote, or system_server'
    rm -f "$RESULT" "$SOFTLOG" "$RZLOG" "$RUNLOG" "$KSU_LOG" "$KSU_OLD"
    printf '%s\n%s\n%s\n%s\n%s\n%s\n' "$boot" "$STATUS_PATH" "$oldz" "$olds" "${PRE_MONITORS:-}" "${PRE_DAEMONS:-}" > "$PENDING" || fail_pre 'unable to create verification marker'
    chown 0:0 "$PENDING" 2>/dev/null || true; chmod 0600 "$PENDING" 2>/dev/null || true
    start_capture || fail_pre 'unable to start ReZygisk logcat capture'
    emit "ZYGOTE_OLD_PID=$oldz"; emit "SYSTEM_SERVER_OLD_PID=$olds"; emit SOFT_REBOOT_SCHEDULED=1; emit KSU_SOFT_REBOOT_REQUESTED=1
    echo pending > "$RESULT"; log "Requesting KernelSU emulated soft reboot through $ksud"
    "$ksud" soft-reboot >>"$SOFTLOG" 2>&1 || fail_pre "ksud soft-reboot exited with status $?"
}

verify() {
    [ -r "$PENDING" ] || exit 0
    boot=$(toybox sed -n 1p "$PENDING"); oldz=$(toybox sed -n 3p "$PENDING"); olds=$(toybox sed -n 4p "$PENDING"); now=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    [ -n "$boot" ] && [ "$boot" = "$now" ] || terminal inconclusive 'verification marker belongs to another boot'
    emit POST_FS_GUARD_RETAINED_DURING_VERIFY=1; emit POST_SOFT_REBOOT_VERIFYING=1
    n=0; dup=0; last=''
    while [ $n -lt 120 ]; do
        z=$(first_pid zygote64 2>/dev/null || first_pid zygote 2>/dev/null || true); s=$(first_pid system_server 2>/dev/null || true)
        if [ -z "$z" ]; then reason='waiting for zygote'
        elif [ -z "$s" ]; then reason='waiting for system_server'
        elif [ "$z" = "$oldz" ]; then reason='waiting for replacement zygote'
        elif [ "$s" = "$olds" ]; then reason='waiting for replacement system_server'
        elif health; then emit "ZYGOTE_NEW_PID=$z"; emit "SYSTEM_SERVER_NEW_PID=$s"; emit "REZYGISK_MONITOR_PID=$HEALTH_MON"; emit "REZYGISK_DAEMON_PID=$HEALTH_DAEMON"; terminal success 'zygote64 injection verified'
        else reason=$HEALTH_REASON; fi
        [ "$reason" = 'duplicate ReZygisk monitor or daemon stack' ] && dup=$((dup+1)) || dup=0
        [ "$reason" = "$last" ] || { log "Verification waiting: $reason"; last=$reason; }
        [ $dup -lt 10 ] || terminal not_working 'duplicate ReZygisk monitor or daemon stack persisted after soft reboot'
        sleep 1; n=$((n+1))
    done
    snapshot POST_TIMEOUT; read_state
    [ "$MON_COUNT" -le 1 ] && [ "$DAE_COUNT" -le 1 ] || terminal not_working 'duplicate ReZygisk monitor or daemon stack'
    [ "$DAE_COUNT" -eq 1 ] && [ "$ZYGOTE_OK" -ne 1 ] && terminal not_working 'ReZygisk daemon is active but zygote64 injection remained zero'
    terminal inconclusive "${last:-ReZygisk health did not become conclusive}"
}

case "$MODE" in activate) activate;; verify) verify;; *) fail_pre "unknown bridge mode: $MODE";; esac
