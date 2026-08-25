#!/system/bin/sh

MODDIR=${0%/*}
exec sh "$MODDIR/boot-completed.sh"
