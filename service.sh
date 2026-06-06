#!/system/bin/sh
MODDIR=${0%/*}
BINDIR="$MODDIR/bin"
DATADIR="/data/adb/magic_v2ray"
set -x
exec > "$DATADIR/service.log" 2>&1

PIDFILE="$MODDIR/run/xray.pid"
TUN2SOCKS_PIDFILE="$MODDIR/run/tun2socks.pid"

# Control pipe for receiving commands from the UI or other components
PIPE_FILE="$MODDIR/run/control.pipe"
STUB_DIR=/dev/sysctl_stubs

rm -rf "$STUB_DIR"
mkdir -p "$STUB_DIR"
mount -t tmpfs -o "mode=0755,context=u:object_r:proc_net:s0" proc "$STUB_DIR"

rm -rf "$MODDIR/run"
mkdir -p "$MODDIR/run"
mkfifo "$PIPE_FILE"
XRAY_PID=0
TUN2SOCKS_PID=0
MONITOR_PID=0

ip="/system/bin/ip"
iptables="/system/bin/iptables"
ip6tables="/system/bin/ip6tables"

RULE_PRIORITY=1000
FWMARK=255
LOCKED=0

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

lock_sysctl() {
    local value="$1"
    local target_path="$2"
    local filedir=$(dirname "$target_path")
    local filename=$(basename "$target_path")
    local stub_path="$STUB_DIR/$filedir"
    local stub_file="$stub_path/$filename"
    local current_val="$(cat "$target_path")" 

    mkdir -p "$stub_path"
    echo "$current_val" > "$stub_file"
    echo "$value" > "$target_path"

    chown $(stat -c '%u:%g' "$target_path") "$stub_file"
    chcon $(stat -Z -c '%C' "$target_path") "$stub_file" # Just in case

    mount -o bind "$stub_file" "$target_path"
}

lock_xraytun0() {
    [ $LOCKED = 1 ] && return
    if [ -e "/proc/sys/net/ipv4/conf/xraytun0/rp_filter" ]; then
        LOCKED=1
        lock_sysctl "0" "/proc/sys/net/ipv4/conf/xraytun0/rp_filter"
    fi
}


get_active_interface() {
    for iface in /sys/class/net/*; do
        iface=$(basename "$iface")

        case "$iface" in
            wlan*|eth*|bt-pan*|rmnet_data*|r_rmnet_data*|ccmni*)
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

monitor_net_interfaces() {
    local cur=$(get_active_interface)
    if [ ! -z "$cur" ]; then
        echo "Initial active interface: $cur"
        # apply iptables rules for the first time
        apply_mark_rule "$cur"
    else
        echo "No active interface detected at startup."
    fi
    $ip monitor route | while read -r line; do
        case "$line" in
            "default "*)
                cur=""
                set -- $line
                while [ $# -gt 0 ]; do
                    if [ "$1" = "dev" ]; then
                        cur="$2"
                        break
                    fi
                    shift
                done

                if [ ! -z "$cur" ]; then
                    case "$cur" in
                        wlan*|eth*|bt-pan*|rmnet_data*|r_rmnet_data*|ccmni*)
                            echo "Network interface switched directly to: $cur"
                            # Remove the old rule
                            # then add the new rule
                            apply_mark_rule "$cur"
                            ;;
                    esac
                fi
                ;;
        esac
    done
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
    if [ "$content" = "start_httpd" ]; then
        httpd -p 127.17.1.3:80 -h "$MODDIR/webroot"
    fi
    if [ "$content" = "stop_httpd" ]; then
        pkill -f "httpd -p 127.17.1.3:80"
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
            # Lock down xraytun
            lock_xraytun0

            # IPV4
            # STEP 1: Create tun device and assign IP address
            $ip addr add 198.18.0.1/15 dev xraytun0
            $ip link set dev xraytun0 up
            $ip route replace default dev xraytun0 table 100
            # STEP 2: Add routing rule to route marked packets through the tun device
            $ip rule add fwmark 1 table 100 priority 1010
            # STEP 3: Add iptables rules to mark packets from tun2socks and route them through the tun device
            $iptables -t mangle -N XRAY_MARK
            $iptables -t mangle -A XRAY_MARK -m mark --mark 255 -j RETURN
            $iptables -t mangle -A XRAY_MARK -m owner --uid-owner 1000 -j MARK --set-xmark 1
            $iptables -t mangle -A XRAY_MARK -m owner --uid-owner 1052 -j MARK --set-xmark 1
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
            # STEP 2: Add routing rule to route marked packets through the tun device
            $ip -6 rule add fwmark 1 table 100 priority 1010
            # STEP 3: Add ip6tables rules to mark packets from tun2socks and route them through the tun device
            $ip6tables -t mangle -N XRAY_MARK
            $ip6tables -t mangle -A XRAY_MARK -m mark --mark 255 -j RETURN
            $ip6tables -t mangle -A XRAY_MARK -m owner --uid-owner 1000 -j MARK --set-xmark 1
            $ip6tables -t mangle -A XRAY_MARK -m owner --uid-owner 1052 -j MARK --set-xmark 1
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
    if [ "$content" = "start_monitor" ]; then
        [ $MONITOR_PID -gt 0 ] && kill -9 "$MONITOR_PID" 2>/dev/null
        MONITOR_PID=0
        monitor_net_interfaces &
        MONITOR_PID=$!
        echo "monitor_net_interfaces is running with PID $MONITOR_PID"
    fi
    if [ "$content" = "stop_monitor" ]; then
        if [ $MONITOR_PID -gt 0 ]; then
            kill -9 "$MONITOR_PID" 2>/dev/null
            echo "killed monitor_net_interfaces is with PID $MONITOR_PID"
        fi
        MONITOR_PID=0
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

{
while [ ! -f /data/misc/net/rt_tables ]; do
    sleep 1
done
lock_sysctl "1" "/proc/sys/net/ipv4/ip_forward"
lock_sysctl "1" "/proc/sys/net/ipv6/conf/all/forwarding"
lock_sysctl "1" "/proc/sys/net/ipv6/conf/default/forwarding"

lock_sysctl "0" "/proc/sys/net/ipv4/conf/all/rp_filter"
lock_sysctl "0" "/proc/sys/net/ipv4/conf/default/rp_filter"

echo "start_monitor" > "$PIPE_FILE"
if [ -e "$DATADIR/config.json" ]; then
    echo "Restart previous xray on boot"
    echo "start" > "$PIPE_FILE"
    echo "wait" > "$PIPE_FILE"
fi

} &
