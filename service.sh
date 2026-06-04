#!/system/bin/sh
MODDIR=${0%/*}
BINDIR="$MODDIR/bin"
DATADIR="/data/adb/magic_v2ray"
set -x >"$DATADIR/service.log" 2>&1

PIDFILE="$MODDIR/run/xray.pid"
TUN2SOCKS_PIDFILE="$MODDIR/run/tun2socks.pid"

# Control pipe for receiving commands from the UI or other components
PIPE_FILE="$MODDIR/run/control.pipe"

rm -rf "$MODDIR/run"
mkdir -p "$MODDIR/run"
mkfifo "$PIPE_FILE"
XRAY_PID=0
TUN2SOCKS_PID=0

ip="/system/bin/ip"
iptables="/system/bin/iptables"
ip6tables="/system/bin/ip6tables"

RULE_PRIORITY=1000
FWMARK=255

get_status() {
    if [ -f "$PIDFILE" ]; then
        PID=$(cat "$PIDFILE")
        STAT_XRAY_EXE=$(stat -L -c "%D:%i" "/proc/$PID/exe")
        STAT_XRAY_BIN=$(stat -L -c "%D:%i" "$MODDIR/bin/xray")

        if kill -0 "$PID" 2>/dev/null && [ "$STAT_XRAY_EXE" = "$STAT_XRAY_BIN" ]; then
            return 0
        fi
    fi
    return 1
}

clear_routing_rules() {
    # IPv4
    $iptables -t mangle -D OUTPUT -j XRAY_MARK 2>/dev/null
    $iptables -t mangle -F XRAY_MARK 2>/dev/null
    $iptables -t mangle -X XRAY_MARK 2>/dev/null
    $ip rule del fwmark 1 table 100 priority 1010 2>/dev/null
    # IPv4 hotspot
    $ip rule del pref 5030 2>/dev/null
    $ip rule del pref 5040 2>/dev/null
    $ip rule del pref 5050 2>/dev/null
    $iptables -D FORWARD -o xraytun0 -j ACCEPT 2>/dev/null
    $iptables -D FORWARD -i xraytun0 -j ACCEPT 2>/dev/null
    $iptables -t mangle -D FORWARD -o xraytun0 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350 2>/dev/null
    # IPv6
    $ip6tables -t mangle -D OUTPUT -j XRAY_MARK 2>/dev/null
    $ip6tables -t mangle -F XRAY_MARK 2>/dev/null
    $ip6tables -t mangle -X XRAY_MARK 2>/dev/null
    $ip -6 rule del fwmark 1 table 100 priority 1010 2>/dev/null
    # IPv6 hotspot
    $ip6tables -D FORWARD -j REJECT --reject-with icmp6-no-route 2>/dev/null

    # Down the tun device
    $ip link set dev xraytun0 down 2>/dev/null
}

do_job() {
    local content="$1"
    if [ "$content" = "wait" ]; then
        : # Do nothing
    fi
    if [ "$content" = "start" ]; then
        if [ ! -e /dev/net/tun ]; then
            mkdir -p /dev/net
            mknod /dev/net/tun c 10 200
            chmod 666 /dev/net/tun
        fi
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
            local retry=0
            local max_retry=10
            while [ $retry -lt $max_retry ]; do
                if $ip link show "xraytun0" >/dev/null 2>&1; then
                    break
                fi
                sleep 0.5
                retry=$((retry + 1))
            done

            # Capture all traffic to tun device and redirect to xray core

            # IPV4
            # STEP 1: Create tun device and assign IP address
            $ip addr add 198.18.0.1/15 dev xraytun0
            $ip link set dev xraytun0 up
            $ip route replace default dev xraytun0 table 100
            # STEP 2: Enable IP Forwarding and disable rp_filter
            echo 1 > /proc/sys/net/ipv4/ip_forward
            echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter
            echo 0 > /proc/sys/net/ipv4/conf/xraytun0/rp_filter
            # STEP 3: Add routing rule to route marked packets through the tun device
            $ip rule add fwmark 1 table 100 priority 1010
            # STEP 4: Add iptables rules to mark packets from tun2socks and route them through the tun device
            $iptables -t mangle -N XRAY_MARK
            $iptables -t mangle -A XRAY_MARK -m mark --mark 255 -j RETURN
            $iptables -t mangle -A XRAY_MARK -m owner --uid-owner 9999-2147483647 -j MARK --set-xmark 1
            $iptables -t mangle -A OUTPUT -j XRAY_MARK 
            # IPv4 Hotspot support
            # STEP 1: Allow forward traffic between hotspot interfaces and xraytun0
            $iptables -I FORWARD -o xraytun0 -j ACCEPT
            $iptables -I FORWARD -i xraytun0 -j ACCEPT
            # STEP 2: Force hotspot private IP ranges to lookup table 100
            $ip rule add from 10.0.0.0/8 lookup 100 pref 5030
            $ip rule add from 172.16.0.0/12 lookup 100 pref 5040
            $ip rule add from 192.168.0.0/16 lookup 100 pref 5050
            # STEP 3: Adjust TCPMSS to prevent TLS packet fragmentation overhead
            $iptables -t mangle -I FORWARD -o xraytun0 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350

            # IPV6
            # STEP 1: Create tun device and assign IP address
            $ip -6 addr add fdfe:dcba:9876::1/64 dev xraytun0
            $ip -6 route replace default dev xraytun0 table 100
            # STEP 2: Enable IP Forwarding
            echo 1 > /proc/sys/net/ipv6/conf/all/forwarding
            # STEP 3: Add routing rule to route marked packets through the tun device
            $ip -6 rule add fwmark 1 table 100 priority 1010
            # STEP 4: Add ip6tables rules to mark packets from tun2socks and route them through the tun device
            $ip6tables -t mangle -N XRAY_MARK
            $ip6tables -t mangle -A XRAY_MARK -m mark --mark 255 -j RETURN
            $ip6tables -t mangle -A XRAY_MARK -m owner --uid-owner 9999-2147483647 -j MARK --set-xmark 1
            $ip6tables -t mangle -A OUTPUT -j XRAY_MARK
            # IPv6 Hotspot support
            # NOTE: IPv6 is strictly rejected for connected hotspot clients due to two reasons:
            # 1. Android's network daemon (netd) constantly flushes and rewrites the native 
            #    FORWARD chains when tethering states toggle, leaking raw IPv6 data to clients.
            # 2. Most upstream proxy endpoints (or gRPC outbounds) lack native mobile IPv6 
            #    support, leading to fatal "read/write on closed pipe" UDP errors in Xray core.
            # By rejecting IPv6 at the gate, clients are safely forced to fallback 100% to IPv4.
            $ip6tables -I FORWARD -j REJECT --reject-with icmp6-no-route
        fi
    fi
    if [ "$content" = "stop" ]; then
        clear_routing_rules

        if [ $XRAY_PID -gt 0 ]; then
            STAT_XRAY_EXE=$(stat -L -c "%D:%i" "/proc/$XRAY_PID/exe")
            STAT_XRAY_BIN=$(stat -L -c "%D:%i" "$MODDIR/bin/xray")
            if [ "$STAT_XRAY_EXE" = "$STAT_XRAY_BIN" ]; then
                kill -9 "$XRAY_PID" 2>/dev/null
            fi
            rm -f "$PIDFILE"
            XRAY_PID=0
        fi

        if [ $TUN2SOCKS_PID -gt 0 ]; then
            STAT_TUN2SOCKS_EXE=$(stat -L -c "%D:%i" "/proc/$TUN2SOCKS_PID/exe")
            STAT_TUN2SOCKS_BIN=$(stat -L -c "%D:%i" "$MODDIR/bin/tun2socks")
            if [ "$STAT_TUN2SOCKS_EXE" = "$STAT_TUN2SOCKS_BIN" ]; then
                kill -9 "$TUN2SOCKS_PID" 2>/dev/null
            fi
            rm -f "$TUN2SOCKS_PIDFILE"
            TUN2SOCKS_PID=0
        fi
    fi
}

{
while true; do
    if read -r line < "$PIPE_FILE"; then
        if [ -n "$line" ]; then
            do_job "$line"
        fi
    fi
done
} &

# ===

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
    $ip -6 rule del fwmark $FWMARK priority $RULE_PRIORITY 2>/dev/null
}

apply_mark_rule() {
    local iface="$1"

    [ -z "$iface" ] && return 1

    remove_mark_rule

    $ip rule add fwmark $FWMARK table "$iface" priority $RULE_PRIORITY
    $ip -6 rule add fwmark 255 table "$iface" priority $RULE_PRIORITY
    echo "Applied: fwmark $FWMARK -> table $iface"
}

{
on_boot_triggered=0
last=""

start_on_boot() {
    [ $on_boot_triggered = 1 ] && return
    on_boot_triggered=1
    if [ -e "$DATADIR/config.json" ]; then
        echo "start" > "$PIPE_FILE"
        echo "wait" > "$PIPE_FILE"
    fi
}

while [ ! -f /data/misc/net/rt_tables ]; do
    sleep 1
done

cur=$(get_active_interface)
last="$cur"
if [ ! -z "$cur" ]; then
    echo "Initial active interface: $cur"
    # apply iptables rules for the first time
    start_on_boot
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
        # Need to restart xray
        if get_status; then
            echo "stop" > "$PIPE_FILE"
            echo "wait" > "$PIPE_FILE"
            echo "start" > "$PIPE_FILE"
            echo "wait" > "$PIPE_FILE"
        fi
        start_on_boot

        # Remove the old rule
        # then add the new rule
        apply_mark_rule "$cur"
    fi
done
} &
