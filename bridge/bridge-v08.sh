#!/system/bin/sh
set -u

STATUS_PATH=${1:-}
MODE=${2:-activate}
MODDIR=${0%/*}
INNER=$MODDIR/bridge-v07.sh
RUNTIME=$MODDIR/.bridge-v08-runtime.sh
RZ=/data/adb/modules/rezygisk
LIB_PATH=$RZ/lib64/libzygisk.so
LIB_BACKUP=$RZ/lib64/.libzygisk.so.rmg-original
LIB_STAGE=/dev/.rmg-rezygisk-libzygisk.so
LIB_STAGE_TMP=/dev/.rmg-rezygisk-libzygisk.so.tmp
STATUS=/data/local/tmp/rmg-rezygisk-bridge-status
PENDING=/data/local/tmp/rmg-rezygisk-soft-reboot-pending

emit() {
    printf '%s\n' "$1" >> "$STATUS" 2>/dev/null || true
    [ -z "$STATUS_PATH" ] || printf '%s\n' "$1" >> "$STATUS_PATH" 2>/dev/null || true
}

file_context() {
    toybox ls -Z "$1" 2>/dev/null | toybox sed -n 's/.*\(u:object_r:[^[:space:]]*\).*/\1/p' | toybox head -n 1
}

restore_library() {
    remove_stage=${1:-no}
    if [ -e "$LIB_BACKUP" ] || [ -L "$LIB_BACKUP" ]; then
        if [ -L "$LIB_PATH" ] || [ ! -e "$LIB_PATH" ]; then
            rm -f "$LIB_PATH" 2>/dev/null || return 1
            mv -f "$LIB_BACKUP" "$LIB_PATH" 2>/dev/null || return 1
            chown 0:0 "$LIB_PATH" 2>/dev/null || true
            chmod 0644 "$LIB_PATH" 2>/dev/null || true
        elif cmp -s "$LIB_PATH" "$LIB_BACKUP" 2>/dev/null; then
            rm -f "$LIB_BACKUP" 2>/dev/null || true
        else
            return 1
        fi
    elif [ -L "$LIB_PATH" ]; then
        target=$(readlink "$LIB_PATH" 2>/dev/null || true)
        [ -n "$target" ] && [ -e "$target" ] || return 1
    fi
    [ "$remove_stage" = yes ] && rm -f "$LIB_STAGE" "$LIB_STAGE_TMP" 2>/dev/null || true
    return 0
}

recover_interrupted() {
    if [ -e "$LIB_BACKUP" ] || [ -L "$LIB_BACKUP" ]; then
        restore_library no
        return $?
    fi
    [ -L "$LIB_PATH" ] || return 0
    target=$(readlink "$LIB_PATH" 2>/dev/null || true)
    [ -n "$target" ] && [ -e "$target" ]
}

stage_library() {
    recover_interrupted || return 1
    [ -f "$LIB_PATH" ] && [ ! -L "$LIB_PATH" ] && [ -r "$LIB_PATH" ] || return 1
    [ ! -e "$LIB_BACKUP" ] && [ ! -L "$LIB_BACKUP" ] || return 1

    ctx=$(file_context "$LIB_PATH" || true)
    rm -f "$LIB_STAGE_TMP" "$LIB_STAGE" 2>/dev/null || true
    cp -f "$LIB_PATH" "$LIB_STAGE_TMP" 2>/dev/null || return 1
    chown 0:0 "$LIB_STAGE_TMP" 2>/dev/null || true
    chmod 0644 "$LIB_STAGE_TMP" 2>/dev/null || true

    desired_ctx=${ctx:-u:object_r:system_file:s0}
    if ! chcon "$desired_ctx" "$LIB_STAGE_TMP" 2>/dev/null; then
        desired_ctx=u:object_r:system_file:s0
        chcon "$desired_ctx" "$LIB_STAGE_TMP" 2>/dev/null || return 1
    fi
    actual_ctx=$(file_context "$LIB_STAGE_TMP" || true)
    [ "$actual_ctx" = "$desired_ctx" ] || return 1

    mv -f "$LIB_STAGE_TMP" "$LIB_STAGE" 2>/dev/null || return 1
    cmp -s "$LIB_PATH" "$LIB_STAGE" 2>/dev/null || { rm -f "$LIB_STAGE"; return 1; }
    mv "$LIB_PATH" "$LIB_BACKUP" 2>/dev/null || { rm -f "$LIB_STAGE"; return 1; }
    if ! ln -s "$LIB_STAGE" "$LIB_PATH" 2>/dev/null; then
        mv -f "$LIB_BACKUP" "$LIB_PATH" 2>/dev/null || true
        rm -f "$LIB_STAGE"
        return 1
    fi

    resolved=$(readlink "$LIB_PATH" 2>/dev/null || true)
    [ "$resolved" = "$LIB_STAGE" ] && [ -r "$LIB_PATH" ] && cmp -s "$LIB_PATH" "$LIB_STAGE" 2>/dev/null || {
        restore_library yes || true
        return 1
    }

    emit DEFEX_TMPFS_BYPASS_ARMED=1
    emit "DEFEX_ORIGINAL_LIBRARY=$LIB_PATH"
    emit "DEFEX_STAGED_LIBRARY=$LIB_STAGE"
    emit "DEFEX_STAGED_CONTEXT=$(file_context "$LIB_STAGE" || echo unknown)"
    return 0
}

prepare_runtime() {
    [ -r "$INNER" ] || return 1
    toybox sed 's/BRIDGE_VERSION=0\.7\.0/BRIDGE_VERSION=0.8.0/g' "$INNER" > "$RUNTIME" 2>/dev/null || return 1
    chmod 0755 "$RUNTIME" 2>/dev/null || return 1
    grep -q 'BRIDGE_VERSION=0.8.0' "$RUNTIME" 2>/dev/null
}

run_inner() {
    prepare_runtime || return 125
    /system/bin/sh "$RUNTIME" "$STATUS_PATH" "$MODE"
}

case "$MODE" in
    activate)
        if ! stage_library; then
            emit DEFEX_TMPFS_BYPASS_FAILED=1
            restore_library yes || true
            exit 1
        fi
        run_inner
        rc=$?
        # A successful activation returns after scheduling the soft reboot. Keep the
        # symlink while the pending marker exists so ReZygisk can inject after restart.
        if [ "$rc" -ne 0 ] || [ ! -r "$PENDING" ]; then
            restore_library no || emit DEFEX_LIBRARY_RESTORE_FAILED=1
        fi
        exit "$rc"
        ;;
    verify)
        run_inner
        rc=$?
        restore_library no || emit DEFEX_LIBRARY_RESTORE_FAILED=1
        exit "$rc"
        ;;
    recover)
        recover_interrupted
        ;;
    *)
        emit "FAILURE=unknown bridge mode: $MODE"
        exit 1
        ;;
esac
