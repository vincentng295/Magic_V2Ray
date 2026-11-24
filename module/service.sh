#!/system/bin/sh
# Magisk/KernelSU only run a file named service.sh. All engine logic lives in the
# native binary; this hands over to it (exec is a shell builtin: no other tool needed).
MODDIR=${0%/*}
DATADIR=/data/adb/magic_v2ray

# An external-app session (magicv2ray://connect) ends at reboot: its proxy (often an app's local SOCKS5
# server) is gone, so put the previously selected node's config back instead of booting into a dead one.
if [ -f "$DATADIR/config.base.json" ]; then
    cp -f "$DATADIR/config.base.json" "$DATADIR/config.v2.json" 2>/dev/null && chmod 600 "$DATADIR/config.v2.json"
    rm -f "$DATADIR/config.base.json" "$DATADIR/excludelist.session.txt"
fi

exec "$MODDIR/bin/xhuskydg_helper" service -m "$MODDIR"
