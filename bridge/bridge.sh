#!/system/bin/sh
MODDIR=${0%/*}
exec /system/bin/sh "$MODDIR/bridge-v07.sh" "$@"
