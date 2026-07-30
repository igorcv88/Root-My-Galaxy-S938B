#!/system/bin/sh
MODDIR=${0%/*}
exec /system/bin/sh "$MODDIR/bridge-v08.sh" "$@"
