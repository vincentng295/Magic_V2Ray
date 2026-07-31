#!/system/bin/sh
#
# Magic V2Ray — manager "Action" button.
#
# Toggles the proxy engine without opening the WebUI. Supported by KernelSU,
# APatch and Magisk 27+; on older Magisk the button is simply not shown, so
# this file is inert there rather than broken.
#
# Output goes straight to the manager's action dialog, so keep it short.

MODDIR=${0%/*}
DATADIR="/data/adb/magic_v2ray"
CONFIG_FILE="$DATADIR/config.v2.json"

STATUS="$(sh "$MODDIR/proxy_control.sh" status 2>/dev/null)"

case "$STATUS" in
    running)
        echo "Engine is running — stopping it."
        sh "$MODDIR/proxy_control.sh" stop
        ;;
    crashed)
        echo "Engine crashed. Clearing state and restarting."
        sh "$MODDIR/proxy_control.sh" stop
        sleep 1
        if [ -s "$CONFIG_FILE" ]; then
            sh "$MODDIR/proxy_control.sh" start
        else
            echo "No configuration saved. Open the WebUI and pick a node first."
            exit 1
        fi
        ;;
    *)
        if [ ! -s "$CONFIG_FILE" ]; then
            echo "No configuration saved."
            echo "Open the WebUI, import a node and select it first."
            exit 1
        fi
        echo "Engine is stopped — starting it."
        sh "$MODDIR/proxy_control.sh" start
        ;;
esac

sleep 1
echo "Now: $(sh "$MODDIR/proxy_control.sh" status 2>/dev/null)"
exit 0
