#!/system/bin/sh
# Magisk/KernelSU only run a file named service.sh. All logic lives in the
# native binary; this just hands over (exec is a shell builtin: no other tool needed).
MODDIR=${0%/*}
exec "$MODDIR/bin/xhuskydg_helper" service -m "$MODDIR"