#!/system/bin/sh

MODDIR=${0%/*}
REZYGISK_POST_FS=/data/adb/modules/rezygisk/post-fs-data.sh
POST_FS_BACKUP=$MODDIR/.rezygisk-post-fs-data.backup

if [ -r "$POST_FS_BACKUP" ] && [ -d /data/adb/modules/rezygisk ]; then
    cp -f "$POST_FS_BACKUP" "$REZYGISK_POST_FS" 2>/dev/null || true
    chown 0:0 "$REZYGISK_POST_FS" 2>/dev/null || true
    chmod 0755 "$REZYGISK_POST_FS" 2>/dev/null || true
    restorecon -F "$REZYGISK_POST_FS" 2>/dev/null || true
fi

rm -f "$POST_FS_BACKUP" \
      "$MODDIR/.rezygisk-post-fs-data.patched" \
      /data/local/tmp/rmg-rezygisk-arm \
      /data/local/tmp/rmg-rezygisk-bridge.pid \
      /data/local/tmp/rmg-rezygisk-bridge.log \
      /data/local/tmp/rmg-rezygisk-bridge-status \
      /data/local/tmp/rmg-rezygisk-result \
      /data/local/tmp/rmg-rezygisk-monitor.log \
      /data/local/tmp/rmg-rezygisk-monitor.pid \
      /data/local/tmp/rmg-rezygisk-soft-reboot-pending \
      /data/local/tmp/rmg-rezygisk-soft-reboot.log \
      /data/local/tmp/rmg-rezygisk-verify.pid \
      /data/local/tmp/rmg-rezygisk-verify.log
rm -rf /data/local/tmp/rmg-rezygisk-bridge.lock
