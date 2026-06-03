#!/system/bin/sh
MODDIR=${0%/*}
BINDIR="$MODDIR/bin"
DATADIR="/data/adb/magic_v2ray"

PIDFILE="$MODDIR/run/xray.pid"
TUN2SOCKS_PIDFILE="$MODDIR/run/tun2socks.pid"

# Control pipe for receiving commands from the UI or other components
PIPE_FILE="$MODDIR/run/control.pipe"

rm -rf "$MODDIR/run"
mkdir -p "$MODDIR/run"
mkfifo "$PIPE_FILE"
XRAY_PID=0
TUN2SOCKS_PID=0

do_job() {
    local content="$1"
    if [ "$content" = "start" ]; then
        STAT_XRAY_EXE=$(stat -L -c "%D:%i" "/proc/$XRAY_PID/exe")
        STAT_XRAY_BIN=$(stat -L -c "%D:%i" "$MODDIR/bin/xray")
        if [ $XRAY_PID -gt 0 ] && [ "$STAT_XRAY_EXE" = "$STAT_XRAY_BIN" ]; then
            echo "Xray is already running with PID $XRAY_PID"
        else
            # Start Xray core
            "$BINDIR/xray" run -c "$DATADIR/config.json" </dev/null &>"$DATADIR/xray.log" &
            XRAY_PID=$!
            echo "$XRAY_PID" > "$PIDFILE"
        fi

        STAT_TUN2SOCKS_EXE=$(stat -L -c "%D:%i" "/proc/$TUN2SOCKS_PID/exe")
        STAT_TUN2SOCKS_BIN=$(stat -L -c "%D:%i" "$MODDIR/bin/tun2socks")
        if [ $TUN2SOCKS_PID -gt 0 ] && [ "$STAT_TUN2SOCKS_EXE" = "$STAT_TUN2SOCKS_BIN" ]; then
            echo "tun2socks is already running with PID $TUN2SOCKS_PID"
        else
            # Start tun2socks
            "$BINDIR/tun2socks" -device tun://xraytun0 -proxy socks5://127.0.0.1:10808 -fwmark 255 </dev/null &>"$DATADIR/tun2socks.log" &
            TUN2SOCKS_PID=$!
            echo "$TUN2SOCKS_PID" > "$TUN2SOCKS_PIDFILE"
        fi
    fi
}

{
while true; do
    if read -r line < "$PIPE_FILE"; then
        if [ -n "$line" ]; then
            do_job "$line" &
        fi
    fi
done
} &

# ===

RULE_PRIORITY=1000
FWMARK=255
ip=/system/bin/ip

get_active_interface() {
    for iface in /sys/class/net/*; do
        iface=$(basename "$iface")

        case "$iface" in
            wlan0|eth0|bt-pan|rmnet_data*|r_rmnet_data*|ccmni*)
                if $ip route show table "$iface" 2>/dev/null | grep -q '^default '; then
                    echo "$iface"
                    return 0
                fi
                ;;
        esac
    done
}

remove_mark_rule() {
    $ip rule del fwmark $FWMARK priority $RULE_PRIORITY 2>/dev/null
}

apply_mark_rule() {
    local iface="$1"

    [ -z "$iface" ] && return 1

    remove_mark_rule

    $ip rule add \
        fwmark $FWMARK \
        table "$iface" \
        priority $RULE_PRIORITY

    echo "Applied: fwmark $FWMARK -> table $iface"
}

{
last=""

while [ ! -f /data/misc/net/rt_tables ]; do
    sleep 1
done

cur=$(get_active_interface)
last="$cur"
if [ ! -z "$cur" ]; then
    echo "Initial active interface: $cur"
    # apply iptables rules for the first time
    apply_mark_rule "$cur"
else
    echo "No active interface detected at startup."
fi

inotifyd - /data/misc/net::w | while read -r _; do
    until [ ! -z "$(get_active_interface)" ]; do
        sleep 1
    done
    cur=$(get_active_interface)

    if [ "$cur" != "$last" ]; then
        echo "Network changed: $last -> $cur"
        last="$cur"

        # Remove the old rule
        # then add the new rule
        apply_mark_rule "$cur"
    fi
done
} &
