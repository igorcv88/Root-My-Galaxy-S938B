#!/system/bin/sh
MODDIR=${0%/*}
/system/bin/sh "$MODDIR/bridge-v08.sh" "" recover >/dev/null 2>&1 || true
exec /system/bin/sh "$MODDIR/bridge-v09.sh" "$@"
