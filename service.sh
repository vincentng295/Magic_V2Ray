#!/system/bin/sh
MODDIR=${0%/*}
BINDIR="$MODDIR/bin"
DATADIR="/data/adb/magic_v2ray"
STUB_DIR=/dev/sysctl_stubs

# Prepare working dir
rm -rf "$STUB_DIR"
mkdir -p "$STUB_DIR"
mount -t tmpfs -o "mode=0755,context=u:object_r:proc_net:s0" proc "$STUB_DIR"

grep_prop() {
  local REGEX="s/^$1=//p"
  shift
  local FILES=$@
  [ -z "$FILES" ] && FILES="$MODDIR/module.prop"
  cat $FILES 2>/dev/null | dos2unix | sed -n "$REGEX" | head -n 1
}

rm -rf "$DATADIR/xray.log"
XRAY_LOG="$DATADIR/xray.log"
if [ "$(grep_prop debug)" = "1" ]; then
    set -x
fi
exec > "$DATADIR/service.log" 2>&1

PIDFILE="$STUB_DIR/run/xray.pid"

# Control pipe for receiving commands from the UI or other components
PIPE_FILE="$STUB_DIR/run/control.pipe"

rm -rf "$STUB_DIR/run"
mkdir -p "$STUB_DIR/run"
mkfifo "$PIPE_FILE"
XRAY_PID=0
MONITOR_PID=0

ip="/system/bin/ip"
iptables="/system/bin/iptables"
ip6tables="/system/bin/ip6tables"

RULE_PRIORITY=1000
FWMARK=255
PROXY_MARK=1
TPROXY_PORT="807"
SOCKS_ADDR="127.17.1.3"
SOCKS_PORT="808"

get_status() {
    if [ -f "$PIDFILE" ]; then
        PID=$(cat "$PIDFILE")
        STAT_XRAY_EXE=$(stat -L -c "%D:%i" "/proc/$PID/exe")
        STAT_XRAY_BIN=$(stat -L -c "%D:%i" "$MODDIR/bin/xray")

        if kill -0 "$PID" && [ "$STAT_XRAY_EXE" = "$STAT_XRAY_BIN" ]; then
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

read_table_index() {
    local iface=$1
    local error=1

    cat /data/misc/net/rt_tables | while read -r index name; do
        if [[ "$name" = "$iface" ]]; then
            echo $index
            error=0
        fi
    done

    return $error
}

get_active_interface() {
    # Ask the kernel the route to 8.8.8.8
    local iface=$($ip route get 8.8.8.8 2>/dev/null | grep -oE 'dev [^ ]+' | awk '{print $2}')
    if [ ! -z "$iface" ]; then
        echo "$iface"
        return 0
    fi
    
    return 1
}

remove_mark_rule() {
    $ip rule del fwmark $FWMARK priority $RULE_PRIORITY
    $ip -6 rule del fwmark $FWMARK priority $RULE_PRIORITY
}

apply_mark_rule() {
    local iface="$1"
    [ -z "$iface" ] && return 1

    remove_mark_rule

    local iface_index="$(read_table_index "$iface")"

    [ -z "$iface_index" ] && return 1

    $ip rule add fwmark $FWMARK table "$iface_index" priority $RULE_PRIORITY
    $ip -6 rule add fwmark $FWMARK table "$iface_index" priority $RULE_PRIORITY
    echo "Applied Bypass Loop: fwmark $FWMARK -> table $iface_index ($iface)"
}

monitor_net_interfaces() {
    local cur=$(get_active_interface)
    local new=""
    if [ ! -z "$cur" ]; then
        echo "Initial active interface: $cur"
        # apply iptables rules for the first time
        apply_mark_rule "$cur"
    else
        echo "No active interface detected at startup."
    fi
    $ip monitor route | while read -r line; do
        case "$line" in
            "default "*|"Deleted default "*|"throw default "*)
                new=$(get_active_interface)
                [ -z "$new" ] && continue
                [ "$new" == "$cur" ] && continue
                cur="$new"
                echo "Network interface switched directly to: $cur"
                apply_mark_rule "$cur"
                ;;
        esac
    done
}

apply_routing_rules() {
    # --- TPROXY IPV4 ---
    $ip rule add fwmark $PROXY_MARK table 100 priority 1010 2>/dev/null || true
    $ip route add local default dev lo table 100 2>/dev/null || true

    # Add Custom Chains in mangle
    $iptables -t mangle -N XRAY_PRE 2>/dev/null || true
    $iptables -t mangle -N XRAY_OUT 2>/dev/null || true
    $iptables -t mangle -I PREROUTING 1 -j XRAY_PRE
    $iptables -t mangle -I OUTPUT 1 -j XRAY_OUT

    # 1. XRAY_PRE (PREROUTING)
    # Bypass internal IP in chain OUTPUT
    $iptables -t mangle -A XRAY_PRE -d 0.0.0.0/8 -j RETURN
    $iptables -t mangle -A XRAY_PRE -d 10.0.0.0/8 -j RETURN
    $iptables -t mangle -A XRAY_PRE -d 127.0.0.0/8 -j RETURN
    $iptables -t mangle -A XRAY_PRE -d 169.254.0.0/16 -j RETURN
    $iptables -t mangle -A XRAY_PRE -d 172.16.0.0/12 -j RETURN
    $iptables -t mangle -A XRAY_PRE -d 192.168.0.0/16 -j RETURN
    $iptables -t mangle -A XRAY_PRE -d 224.0.0.0/4 -j RETURN
    $iptables -t mangle -A XRAY_PRE -d 240.0.0.0/4 -j RETURN

    # Apply TPROXY for marked packages with Proxy Mark
    $iptables -t mangle -A XRAY_PRE -p tcp -m mark --mark $PROXY_MARK -j TPROXY --on-port $TPROXY_PORT --on-ip 0.0.0.0 --tproxy-mark $PROXY_MARK
    $iptables -t mangle -A XRAY_PRE -p udp -m mark --mark $PROXY_MARK -j TPROXY --on-port $TPROXY_PORT --on-ip 0.0.0.0 --tproxy-mark $PROXY_MARK

    # Hotspot/Tethering
    for prefix in wlan+ ap+ rndis+; do
        $iptables -t mangle -A XRAY_PRE -i $prefix -p tcp -j TPROXY --on-port $TPROXY_PORT --on-ip 0.0.0.0 --tproxy-mark $PROXY_MARK
        $iptables -t mangle -A XRAY_PRE -i $prefix -p udp -j TPROXY --on-port $TPROXY_PORT --on-ip 0.0.0.0 --tproxy-mark $PROXY_MARK
    done

    # 2. XRAY_OUT (OUTPUT)
    # Exclude packets emitted by the Xray core to avoid a cyclic loop
    $iptables -t mangle -A XRAY_OUT -m mark --mark $FWMARK -j RETURN

    # Bypass internal IP in chain OUTPUT
    $iptables -t mangle -A XRAY_OUT -d 0.0.0.0/8 -j RETURN
    $iptables -t mangle -A XRAY_OUT -d 10.0.0.0/8 -j RETURN
    $iptables -t mangle -A XRAY_OUT -d 127.0.0.0/8 -j RETURN
    $iptables -t mangle -A XRAY_OUT -d 169.254.0.0/16 -j RETURN
    $iptables -t mangle -A XRAY_OUT -d 172.16.0.0/12 -j RETURN
    $iptables -t mangle -A XRAY_OUT -d 192.168.0.0/16 -j RETURN
    $iptables -t mangle -A XRAY_OUT -d 224.0.0.0/4 -j RETURN
    $iptables -t mangle -A XRAY_OUT -d 240.0.0.0/4 -j RETURN

    # Mark UID that need to forward package to Proxy
    $iptables -t mangle -A XRAY_OUT -m owner --uid-owner 1000 -j MARK --set-xmark $PROXY_MARK
    $iptables -t mangle -A XRAY_OUT -m owner --uid-owner 1052 -j MARK --set-xmark $PROXY_MARK
    $iptables -t mangle -A XRAY_OUT -m owner --uid-owner 9999-2147483647 -j MARK --set-xmark $PROXY_MARK

    # Hide proxy from apps
    $iptables -I OUTPUT -p tcp --dport $SOCKS_PORT -d $SOCKS_ADDR -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset

    # --- TPROXY IPV6 ---
    $ip -6 rule add fwmark $PROXY_MARK table 100 priority 1010 2>/dev/null || true
    $ip -6 route add local default dev lo table 100 2>/dev/null || true

    $ip6tables -t mangle -N XRAY_PRE 2>/dev/null || true
    $ip6tables -t mangle -N XRAY_OUT 2>/dev/null || true
    $ip6tables -t mangle -I PREROUTING 1 -j XRAY_PRE
    $ip6tables -t mangle -I OUTPUT 1 -j XRAY_OUT

    # IPv6 Bypass LAN
    $ip6tables -t mangle -A XRAY_PRE -d ::1/128 -j RETURN
    $ip6tables -t mangle -A XRAY_PRE -d fe80::/10 -j RETURN
    $ip6tables -t mangle -A XRAY_PRE -d fc00::/7 -j RETURN

    # IPv6 TPROXY PREROUTING
    $ip6tables -t mangle -A XRAY_PRE -p tcp -m mark --mark $PROXY_MARK -j TPROXY --on-port $TPROXY_PORT --on-ip :: --tproxy-mark $PROXY_MARK
    $ip6tables -t mangle -A XRAY_PRE -p udp -m mark --mark $PROXY_MARK -j TPROXY --on-port $TPROXY_PORT --on-ip :: --tproxy-mark $PROXY_MARK

    for prefix in wlan+ ap+ rndis+; do
        $ip6tables -t mangle -A XRAY_PRE -i $prefix -p tcp -j TPROXY --on-port $TPROXY_PORT --on-ip :: --tproxy-mark $PROXY_MARK
        $ip6tables -t mangle -A XRAY_PRE -i $prefix -p udp -j TPROXY --on-port $TPROXY_PORT --on-ip :: --tproxy-mark $PROXY_MARK
    done

    # IPv6 OUTPUT
    $ip6tables -t mangle -A XRAY_OUT -m mark --mark $FWMARK -j RETURN
    $ip6tables -t mangle -A XRAY_OUT -d ::1/128 -j RETURN
    $ip6tables -t mangle -A XRAY_OUT -d fe80::/10 -j RETURN
    $ip6tables -t mangle -A XRAY_OUT -d fc00::/7 -j RETURN

    $ip6tables -t mangle -A XRAY_OUT -m owner --uid-owner 1000 -j MARK --set-xmark $PROXY_MARK
    $ip6tables -t mangle -A XRAY_OUT -m owner --uid-owner 1052 -j MARK --set-xmark $PROXY_MARK
    $ip6tables -t mangle -A XRAY_OUT -m owner --uid-owner 9999-2147483647 -j MARK --set-xmark $PROXY_MARK
    echo "TPROXY Rules Applied Successfully."
}

clear_routing_rules() {
    # IPv4 Mangle & Routing
    $iptables -t mangle -D PREROUTING -j XRAY_PRE 2>/dev/null
    $iptables -t mangle -D OUTPUT -j XRAY_OUT 2>/dev/null
    $iptables -t mangle -F XRAY_PRE 2>/dev/null
    $iptables -t mangle -X XRAY_PRE 2>/dev/null
    $iptables -t mangle -F XRAY_OUT 2>/dev/null
    $iptables -t mangle -X XRAY_OUT 2>/dev/null
    $iptables -D OUTPUT -p tcp --dport $SOCKS_PORT -d $SOCKS_ADDR -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset
    $ip rule del fwmark $PROXY_MARK table 100 priority 1010 2>/dev/null
    $ip route flush table 100 2>/dev/null

    # IPv6 Mangle & Routing
    $ip6tables -t mangle -D PREROUTING -j XRAY_PRE 2>/dev/null
    $ip6tables -t mangle -D OUTPUT -j XRAY_OUT 2>/dev/null
    $ip6tables -t mangle -F XRAY_PRE 2>/dev/null
    $ip6tables -t mangle -X XRAY_PRE 2>/dev/null
    $ip6tables -t mangle -F XRAY_OUT 2>/dev/null
    $ip6tables -t mangle -X XRAY_OUT 2>/dev/null
    $ip -6 rule del fwmark $PROXY_MARK table 100 priority 1010 2>/dev/null
    $ip -6 route flush table 100 2>/dev/null
    
    echo "TPROXY Rules Cleared."
}

mount_proc_with_name() {
    local PID="$1"
    local NAME="$2"
    if [ -d "/proc/$PID" ] && [ ! -e "$STUB_DIR/proc/$NAME" ]; then
        mkdir -p "$STUB_DIR/proc/$NAME"
        mount --bind "/proc/$PID" "$STUB_DIR/proc/$NAME"
        echo "Mounted /proc/$PID to $STUB_DIR/proc/$NAME"
    fi
}

umount_proc_with_name() {
    local NAME="$1"
    umount -l "$STUB_DIR/proc/$NAME" 2>/dev/null || true
    rm -rf "$STUB_DIR/proc/$NAME"
    echo "Unmounted $STUB_DIR/proc/$NAME"
}

is_proc_running() {
    local NAME="$1"
    if [ -e "$STUB_DIR/proc/$NAME/exe" ]; then
        return 0
    else
        # When the process is dead, stat $STUB_DIR/proc/$NAME will fail
        # as well as anything inside it, so we can safely assume that the process is not running.
        return 1
    fi
}

do_job() {
    local content="$1"
    if [ "$content" = "wait" ]; then
        : # Do nothing
        return 0
    fi
    if [ "$content" = "apply_cur_iface" ]; then
        local cur_iface=$(get_active_interface)
        if [ ! -z "$cur_iface" ]; then
            echo "Applying routing rules for current active interface: $cur_iface"
            apply_mark_rule "$cur_iface"
        else
            echo "No active interface detected to apply routing rules."
        fi
        return 0
    fi
    if [ "$content" = "start" ]; then
        if is_proc_running "xray"; then
            echo "Xray is already running with PID $XRAY_PID"
        else
            # Start Xray core
            "$BINDIR/xray" run -c "$DATADIR/config.json" </dev/null &>"$XRAY_LOG" &
            XRAY_PID=$!
            echo "$XRAY_PID" > "$PIDFILE"
            echo "Xray is running with PID $XRAY_PID"

            mount_proc_with_name "$XRAY_PID" "xray"
            apply_routing_rules
        fi
        return 0
    fi
    if [ "$content" = "stop" ]; then
        clear_routing_rules 2>/dev/null

        if is_proc_running "xray"; then
            kill -9 "$XRAY_PID"
            XRAY_PID=0
        fi
        umount_proc_with_name "xray"
        rm -f "$PIDFILE"
        return 0
    fi
    if [ "$content" = "start_monitor" ]; then
        [ $MONITOR_PID -gt 0 ] && is_proc_running "monitor_net_interfaces" && kill -9 "$MONITOR_PID"
        MONITOR_PID=0
        monitor_net_interfaces &
        MONITOR_PID=$!
        mount_proc_with_name "$MONITOR_PID" "monitor_net_interfaces"
        echo "monitor_net_interfaces is running with PID $MONITOR_PID"
        return 0
    fi
    if [ "$content" = "stop_monitor" ]; then
        if [ $MONITOR_PID -gt 0 ] && is_proc_running "monitor_net_interfaces"; then
            kill -9 "$MONITOR_PID"
            echo "killed monitor_net_interfaces is with PID $MONITOR_PID"
        fi
        umount_proc_with_name "monitor_net_interfaces"
        MONITOR_PID=0
        return 0
    fi
    return 1
}

{
while true; do
    if read -r line < "$PIPE_FILE"; then
        if [ -n "$line" ]; then
            if ! do_job "$line"; then
                echo "Unknown command: $line"
            fi
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

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done
sleep 5

# This is for WireGuard to create a virtual tunnel wg0
if [ ! -e /dev/net/tun ]; then
    mkdir -p /dev/net
    mknod /dev/net/tun c 10 200
    chmod 666 /dev/net/tun
fi

if [ -e "$DATADIR/config.json" ]; then
    echo "Restart previous xray on boot"
    echo "start" > "$PIPE_FILE"
    echo "wait" > "$PIPE_FILE"
fi

} &
