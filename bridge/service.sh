#!/system/bin/sh

MODDIR=${0%/*}
/system/bin/sh "$MODDIR/late-load.sh" service
exit $?
