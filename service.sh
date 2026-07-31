#!/system/bin/sh
#
# Magic V2Ray — boot service and runtime control loop.
#
# Layout of this file:
#   1.  Paths, constants, logging
#   2.  Settings helpers
#   3.  Process tracking (/proc bind-mount liveness)
#   4.  Interface + policy-routing helpers
#   5.  Mobile IP hunter
#   6.  Monitors (network interface, latency)  <- event-driven, bounded
#   7.  Routing rules  <- UNCHANGED in this pass, pending review
#   8.  Command loop
#   9.  Boot sequencer
#
# NOTE: section 7 (apply_routing_rules / clear_routing_rules) is intentionally
# carried over verbatim. The firewall/DNS/fail-safe fixes for it are staged as
# separate reviewed changes so a routing regression can never be confused with
# a lifecycle regression.

MODDIR=${0%/*}
BINDIR="$MODDIR/bin"
DATADIR="/data/adb/magic_v2ray"
STUB_DIR=/dev/sysctl_stubs
CONFIG_FILE="$DATADIR/config.v2.json"

# ===========================================================================
# 1. Paths, constants, logging
# ===========================================================================

RUN_DIR="$STUB_DIR/run"
PROC_DIR="$STUB_DIR/proc"

# Prepare working dir. Unmount any leftover from a previous run first —
# rm -rf on a live mountpoint silently leaves the mount in place.
umount -l "$STUB_DIR" 2>/dev/null
rm -rf "$STUB_DIR" 2>/dev/null
mkdir -p "$STUB_DIR"
if ! mount -t tmpfs -o "mode=0755,context=u:object_r:proc_net:s0" proc "$STUB_DIR"; then
    # Some kernels reject that SELinux context. /dev is already tmpfs, so a
    # plain directory still works; we only lose the private mount namespace.
    echo "warning: tmpfs mount failed, falling back to a plain directory"
fi
mkdir -p "$RUN_DIR" "$PROC_DIR"
chmod 700 "$RUN_DIR"

XRAY_LOG="$DATADIR/xray.log"
SERVICE_LOG="$DATADIR/service.log"
IP_HUNT_FILE="$DATADIR/ip_hunt.list"
ENABLED_FLAG="$DATADIR/enabled"

# Runtime state files (tmpfs; cleared on every boot)
PIDFILE="$RUN_DIR/xray.pid"
TIME_RES_FILE="$RUN_DIR/time_res"
ADDR_INFO_FILE="$RUN_DIR/addr_info"
LATENCY_HB_FILE="$RUN_DIR/latency.hb"
PIPE_FILE="$RUN_DIR/control.pipe"
IFACE_EVENT_PIPE="$RUN_DIR/iface_events.pipe"
IFACE_MON_CHILD="$RUN_DIR/iface_monitor_child.pid"

# List of UIDs we want them to be routed into Xray-core
XRAY_UID_LIST="
1000
1051
1052
1053
9999-2147483647
"

rm -f "$XRAY_LOG" "$DATADIR/tun2socks.log"  # tun2socks.log: cleanup of leftover file from pre-tun-inbound installs

grep_prop() {
    local regex="s/^$1=//p"
    shift
    local files="$*"
    [ -z "$files" ] && files="$MODDIR/module.prop"
    # dos2unix is not present on every toybox build; tr is.
    cat $files 2>/dev/null | tr -d '\r' | sed -n "$regex" | head -n 1
}

DEBUG=0
if [ "$(grep_prop debug)" = "1" ]; then
    DEBUG=1
fi

# Cap the service log so a long-lived boot cannot fill /data. Rotation happens
# once at startup; the log only grows on real events after the debouncing work
# below, so a single generation is plenty.
if [ -f "$SERVICE_LOG" ]; then
    log_size=$(stat -c '%s' "$SERVICE_LOG" 2>/dev/null || echo 0)
    if [ "$log_size" -gt 1048576 ]; then
        mv -f "$SERVICE_LOG" "$SERVICE_LOG.1" 2>/dev/null
    fi
fi

exec >> "$SERVICE_LOG" 2>&1
[ "$DEBUG" = "1" ] && set -x

log() { echo "[$(date '+%H:%M:%S')] $*"; }

log "=== service.sh starting (debug=$DEBUG) ==="

mkfifo "$PIPE_FILE" 2>/dev/null
chmod 600 "$PIPE_FILE" 2>/dev/null

# Tracked child PIDs. These were previously a single shared MONITOR_PID, which
# meant starting the latency monitor overwrote — and permanently orphaned —
# the network-interface monitor, silently disabling reconnect handling.
XRAY_PID=0
IFACE_MONITOR_PID=0
LATENCY_MONITOR_PID=0

ip="/system/bin/ip"
iptables="/system/bin/iptables"
ip6tables="/system/bin/ip6tables"

RULE_PRIORITY=1000
FWMARK=255
TUN_NAME="xraytun0"
TUN_ADDR="127.17.1.3"
TUN_PORT="808"

# Latency probe cadence and how long the backend keeps probing after the last
# UI heartbeat. See monitor_network_latency().
LATENCY_INTERVAL=2
LATENCY_HB_TIMEOUT=15

# ===========================================================================
# 2. Settings helpers
# ===========================================================================

# Reads one field out of the base64-encoded settings blob written by the UI.
# jq was a 2 MB dependency for this single lookup; sed does it adequately for
# the flat scalar fields we need.
query_settings() {
    local key="$1"
    local settings_file="$DATADIR/settings.base64"
    [ ! -f "$settings_file" ] && return 1
    base64 -d "$settings_file" 2>/dev/null \
        | tr ',{}' '\n\n\n' \
        | sed -n "s/^[[:space:]]*\"$key\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\"]*\)\"\{0,1\}[[:space:]]*$/\1/p" \
        | head -n 1
}

# Boolean settings default to false when absent or unreadable (fail closed).
setting_is_true() {
    [ "$(query_settings "$1")" = "true" ]
}

# ===========================================================================
# 3. Process tracking
# ===========================================================================
#
# Liveness is tracked by bind-mounting /proc/<pid> to a stable name. When the
# process dies the bind mount becomes an empty directory, so testing for
# .../exe is a reliable "is it still alive" check that survives PID reuse.

mount_proc_with_name() {
    local pid="$1"
    local name="$2"
    [ -d "/proc/$pid" ] || return 1

    # Always clear a previous mount first. The old code skipped mounting when
    # the directory already existed, so after any crash the new process was
    # never tracked: status read "crashed" forever, Start spawned untracked
    # duplicates, and Stop killed nothing.
    umount -l "$PROC_DIR/$name" 2>/dev/null
    # ${var:?} aborts instead of silently expanding to "/name" or "/" if
    # either half of the path were ever empty/unset.
    rm -rf "${PROC_DIR:?}/${name:?}" 2>/dev/null

    mkdir -p "$PROC_DIR/$name"
    if mount --bind "/proc/$pid" "$PROC_DIR/$name"; then
        log "tracking pid $pid as $name"
        return 0
    fi
    log "warning: could not bind-mount /proc/$pid for $name"
    return 1
}

umount_proc_with_name() {
    local name="$1"
    umount -l "$PROC_DIR/$name" 2>/dev/null
    # ${var:?} aborts instead of silently expanding to "/name" or "/" if
    # either half of the path were ever empty/unset.
    rm -rf "${PROC_DIR:?}/${name:?}" 2>/dev/null
}

is_proc_running() {
    [ -e "$PROC_DIR/$1/exe" ]
}

# Kills a tracked process and drops its /proc bind mount.
#
# Deliberately does NOT kill by process group: this script runs without job
# control, so background children share the parent's PGID and `kill -9 -PID`
# would either be a no-op or, worse, signal an unrelated group. Long-lived
# grandchildren (the `ip monitor` under the interface monitor) are tracked by
# their own pid file instead.
kill_tracked() {
    local pid="$1" name="$2"
    [ -z "$pid" ] && return 0
    [ "$pid" -gt 0 ] 2>/dev/null || return 0
    kill -9 "$pid" 2>/dev/null
    [ -n "$name" ] && umount_proc_with_name "$name"
    return 0
}

# ===========================================================================
# 4. Interface + policy-routing helpers
# ===========================================================================

# Looks up an interface's routing table id in Android's rt_tables.
#
# Rewritten: the previous version piped into `while`, so the loop ran in a
# subshell and its return value never reached the caller; it also emitted one
# line per match, and a duplicate rt_tables entry (which happens after
# interface churn) produced "23 45", making the subsequent `ip rule add`
# fail silently and leaving the fwmark rule uninstalled.
read_table_index() {
    local iface="$1" index name
    [ -z "$iface" ] && return 1
    [ -r /data/misc/net/rt_tables ] || return 1

    while read -r index name; do
        if [ "$name" = "$iface" ] && [ -n "$index" ]; then
            echo "$index"
            return 0
        fi
    done < /data/misc/net/rt_tables

    return 1
}

get_active_interface() {
    local iface
    iface=$($ip route get 8.8.8.8 2>/dev/null | sed -n 's/.*[[:space:]]dev[[:space:]]\{1,\}\([^[:space:]]*\).*/\1/p' | head -n 1)
    [ -n "$iface" ] || return 1
    echo "$iface"
    return 0
}

remove_mark_rule() {
    while $ip    rule del fwmark $FWMARK priority $RULE_PRIORITY 2>/dev/null; do :; done
    while $ip -6 rule del fwmark $FWMARK priority $RULE_PRIORITY 2>/dev/null; do :; done
}

apply_mark_rule() {
    local iface="$1" iface_index
    [ -z "$iface" ] && return 1

    # Never point the mark rule at our own TUN — that would loop xray's
    # egress back into the tunnel.
    [ "$iface" = "$TUN_NAME" ] && { log "ignoring $TUN_NAME as active interface"; return 1; }

    if ! iface_index="$(read_table_index "$iface")"; then
        log "no routing table for interface $iface; mark rule not applied"
        return 1
    fi

    remove_mark_rule
    $ip    rule add fwmark $FWMARK table "$iface_index" priority $RULE_PRIORITY 2>/dev/null
    $ip -6 rule add fwmark $FWMARK table "$iface_index" priority $RULE_PRIORITY 2>/dev/null
    log "applied fwmark $FWMARK -> table $iface_index ($iface)"
    return 0
}

# ===========================================================================
# 5. Mobile IP hunter
# ===========================================================================

network_reset() {
    log "resetting the mobile data stack"
    # Airplane mode would kill every radio at once, breaking Wi-Fi and
    # Bluetooth too. Restarting just the telephony process targets the mobile
    # data stack cleanly. Matched on uid 1001 + exact cmdline so we do not
    # catch an unrelated process whose name merely contains com.android.phone.
    for pid_dir in /proc/[0-9]*; do
        [ -d "$pid_dir" ] || continue
        [ "$(stat -c '%u' "$pid_dir" 2>/dev/null)" = "1001" ] || continue
        local cmdline
        cmdline=$(tr '\0' ' ' < "$pid_dir/cmdline" 2>/dev/null)
        case "$cmdline" in
            com.android.phone|com.android.phone\ *)
                log "killing com.android.phone (pid ${pid_dir##*/})"
                kill -9 "${pid_dir##*/}" 2>/dev/null
                ;;
        esac
    done
}

is_mobile_data_iface() {
    case "$1" in
        rmnet*|ccmni*|pdp*|wwan*) return 0 ;;
        *) return 1 ;;
    esac
}

# Hunter state. Previously every mismatch fired another radio restart with no
# limit, so an unreachable prefix list produced an unbounded reconnect loop:
# constant telephony crashes, no mobile data, and severe battery drain.
IP_HUNT_ATTEMPTS=0
IP_HUNT_MAX_ATTEMPTS=10
IP_HUNT_NEXT_ALLOWED=0

ip_hunt_reset() {
    IP_HUNT_ATTEMPTS=0
    IP_HUNT_NEXT_ALLOWED=0
}

check_ip_hunter() {
    local iface="$1"
    is_mobile_data_iface "$iface" || return 0
    [ -f "$IP_HUNT_FILE" ] || return 0

    local prefixes current_ip target now
    prefixes="$(tr -d '\r' < "$IP_HUNT_FILE")"
    [ -z "$prefixes" ] && return 0

    current_ip="$($ip addr show "$iface" 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -n 1)"
    [ -z "$current_ip" ] && return 0

    local IFS=";"
    for target in $prefixes; do
        [ -z "$target" ] && continue
        case "$target" in *.) ;; *) target="$target." ;; esac
        case "$current_ip." in
            "$target"*)
                log "IP hunter: matched $current_ip"
                ip_hunt_reset
                return 0
                ;;
        esac
    done
    unset IFS

    now=$(date +%s)
    if [ "$IP_HUNT_ATTEMPTS" -ge "$IP_HUNT_MAX_ATTEMPTS" ]; then
        log "IP hunter: giving up after $IP_HUNT_ATTEMPTS attempts (got $current_ip); disabling"
        rm -f "$IP_HUNT_FILE"
        ip_hunt_reset
        return 1
    fi
    if [ "$now" -lt "$IP_HUNT_NEXT_ALLOWED" ]; then
        log "IP hunter: backing off, next attempt in $((IP_HUNT_NEXT_ALLOWED - now))s"
        return 1
    fi

    IP_HUNT_ATTEMPTS=$((IP_HUNT_ATTEMPTS + 1))
    # 3, 6, 12, 24, 48 ... capped at 300s
    local backoff=$((3 * (1 << (IP_HUNT_ATTEMPTS - 1))))
    [ "$backoff" -gt 300 ] && backoff=300
    IP_HUNT_NEXT_ALLOWED=$((now + backoff))

    log "IP hunter: $current_ip does not match (attempt $IP_HUNT_ATTEMPTS/$IP_HUNT_MAX_ATTEMPTS), retry in ${backoff}s"
    network_reset &
    return 1
}

# ===========================================================================
# 6. Monitors
# ===========================================================================

# --- Network interface monitor ---------------------------------------------
#
# Event-driven via `ip monitor route`. Two changes from the original:
#
#  * The event stream is read through a FIFO rather than a pipeline, so the
#    loop body runs in this shell and the `ip monitor` child's PID is known
#    and killable. Previously `kill` hit only the wrapping subshell and left
#    an orphaned `ip monitor` behind on every restart.
#
#  * Events are debounced. A single Wi-Fi/mobile handover emits dozens of
#    route messages, and each one used to fork `ip route get` + grep + awk.
#    We now drain the burst and act once it goes quiet.
monitor_net_interfaces() {
    local cur new line drained

    rm -f "$IFACE_EVENT_PIPE"
    mkfifo "$IFACE_EVENT_PIPE" 2>/dev/null || return 1

    $ip monitor route > "$IFACE_EVENT_PIPE" 2>/dev/null &
    echo $! > "$IFACE_MON_CHILD"

    cur=$(get_active_interface) || cur=""
    if [ -n "$cur" ]; then
        log "initial active interface: $cur"
        apply_mark_rule "$cur" || cur=""
    else
        log "no active interface at startup"
    fi

    while read -r line; do
        # Coalesce the burst: keep reading until the stream is quiet for 1s,
        # or until we have absorbed a reasonable number of messages.
        drained=0
        # shellcheck disable=SC3045
        # `read -t` is not in dash, which is what this repo's CI checks
        # against for stricter portability signal — but this script's actual
        # shebang target is /system/bin/sh, i.e. Android's mksh, which does
        # support -t. Losing the debounce entirely (see the block comment at
        # the top of monitor_net_interfaces) is worse than this dash gap.
        while [ "$drained" -lt 200 ] && read -r -t 1 line; do
            drained=$((drained + 1))
        done

        new=$(get_active_interface) || new=""

        if [ "$new" = "$cur" ]; then
            continue
        fi

        if [ -z "$new" ]; then
            log "network interface disconnected"
            cur=""
            rm -f "$ADDR_INFO_FILE"
            continue
        fi

        log "network interface changed: ${cur:-none} -> $new"
        if apply_mark_rule "$new"; then
            cur="$new"
            ip_hunt_reset
        fi
        $ip addr show "$new" > "$ADDR_INFO_FILE" 2>/dev/null
        check_ip_hunter "$new"
    done < "$IFACE_EVENT_PIPE"

    log "interface monitor exiting"
}

stop_iface_monitor() {
    if [ -f "$IFACE_MON_CHILD" ]; then
        local child
        child=$(cat "$IFACE_MON_CHILD" 2>/dev/null)
        [ -n "$child" ] && kill -9 "$child" 2>/dev/null
        rm -f "$IFACE_MON_CHILD"
    fi
    kill_tracked "$IFACE_MONITOR_PID" "monitor_net_interfaces"
    IFACE_MONITOR_PID=0
    rm -f "$IFACE_EVENT_PIPE"
}

# --- Latency monitor -------------------------------------------------------
#
# Bounded by a heartbeat the UI refreshes while its Latency tab is visible.
# The old loop ran `curl` once a second for as long as its state file existed,
# which meant closing the WebUI (rather than toggling the switch off) left a
# root process making a TLS connection every second until reboot.
#
# Wakeups: was 86400 probes/day if left on. Now 43200/day while actively
# watched, and zero within LATENCY_HB_TIMEOUT seconds of the UI going away.
monitor_network_latency() {
    local url="https://gstatic.com/generate_204"
    local time_res now last_hb age

    : > "$TIME_RES_FILE"
    date +%s > "$LATENCY_HB_FILE"

    while [ -f "$TIME_RES_FILE" ]; do
        last_hb=$(cat "$LATENCY_HB_FILE" 2>/dev/null || echo 0)
        now=$(date +%s)
        age=$((now - last_hb))
        if [ "$age" -gt "$LATENCY_HB_TIMEOUT" ]; then
            log "latency monitor: no UI heartbeat for ${age}s, stopping"
            break
        fi

        time_res=$("$BINDIR/curl" --socks5-hostname "${TUN_ADDR}:${TUN_PORT}" \
            -s -w "%{time_starttransfer}" --max-time 3 -o /dev/null "$url" 2>/dev/null)
        printf '%s' "$time_res" > "$TIME_RES_FILE"
        sleep "$LATENCY_INTERVAL"
    done

    rm -f "$TIME_RES_FILE" "$LATENCY_HB_FILE"
    log "latency monitor exiting"
}

stop_latency_monitor() {
    kill_tracked "$LATENCY_MONITOR_PID" "monitor_network_latency"
    LATENCY_MONITOR_PID=0
    rm -f "$TIME_RES_FILE" "$LATENCY_HB_FILE"
}

# ===========================================================================
# 7. Routing rules
# ===========================================================================
#
# ---------------------------------------------------------------------------
# NOT MODIFIED IN THIS PASS.
#
# The known issues in this section — no abort when the TUN is missing, the
# blanket FORWARD accept, unrestored sysctls, the hardcoded tethering DNS
# target, and the unconditional IPv6 DNS drop — are staged as separate,
# individually reviewable changes. Keeping them byte-identical here means any
# regression from the lifecycle rework above cannot be mistaken for a routing
# regression.
# ---------------------------------------------------------------------------

enable_forward() {
    echo "1" > "/proc/sys/net/ipv4/ip_forward"
    if [ "$1" = "true" ]; then
        echo "1" > "/proc/sys/net/ipv6/conf/all/forwarding"
        echo "1" > "/proc/sys/net/ipv6/conf/default/forwarding"
    else
        echo "0" > "/proc/sys/net/ipv6/conf/all/forwarding"
        echo "0" > "/proc/sys/net/ipv6/conf/default/forwarding"
    fi
}

lock_xraytun0() {
    if [ -e "/proc/sys/net/ipv4/conf/$TUN_NAME/rp_filter" ]; then
        echo "0" > "/proc/sys/net/ipv4/conf/$TUN_NAME/rp_filter"
    fi
}

# ---------------------------------------------------------------------------
# forward(): mirrors box-for-magisk's forward() helper. action is "-I" to add
# or "-D" to remove. Kept as one function so apply/clear never drift apart.
# ---------------------------------------------------------------------------
forward() {
    local action="$1"
    $iptables "$action" FORWARD -i "$TUN_NAME" -j ACCEPT
    $iptables "$action" FORWARD -o "$TUN_NAME" -j ACCEPT
    $ip6tables "$action" FORWARD -i "$TUN_NAME" -j ACCEPT
    $ip6tables "$action" FORWARD -o "$TUN_NAME" -j ACCEPT
} >/dev/null 2>&1

# ---------------------------------------------------------------------------
# loosen_rp_filter(): fixes wifi hotspot breaking on some Samsung One UI
# devices once xraytun0 becomes the default route.
#
# The kernel's effective reverse-path check is max(conf.all.rp_filter,
# conf.<iface>.rp_filter) - our boot-time older lock_sysctl only locks "all" and
# "default" to 0, but Samsung's netd sets the softAP interface's OWN
# rp_filter value independently (often after the interface is already up),
# so that per-interface value wins and hotspot client packets get dropped
# for asymmetric routing. Loosen (not disable - 2, "loose mode") every
# interface that already exists, and loosen "default" too so an AP
# interface brought up later still inherits it.
# ---------------------------------------------------------------------------
loosen_rp_filter() {
    sysctl -w net.ipv4.conf.all.rp_filter=2
    sysctl -w net.ipv4.conf.default.rp_filter=2
    for conf in /proc/sys/net/ipv4/conf/*/rp_filter; do
        case "$conf" in
            */lo/rp_filter|*/"$TUN_NAME"/rp_filter) continue ;;
        esac
        echo 2 > "$conf"
    done
}

apply_routing_rules() {
    local retry=0
    local max_retry=10
    while [ $retry -lt $max_retry ]; do
        if $ip link show "$TUN_NAME" >/dev/null 2>&1; then
            break
        fi
        sleep 0.5
        retry=$((retry + 1))
    done
    ipv6_enabled="$(setting_is_true enableIPv6 && echo true || echo false)"
    echo "IPv6 enabled: $ipv6_enabled"

    network_mode="$(query_settings .networkMode)"
    [ -z "$network_mode" ] && network_mode=0
    echo "Network mode: $network_mode"

    allow_tether="$(query_settings .allowTether)"
    [ "$allow_tether" = "false" ] && allow_tether=false || allow_tether=true
    echo "Allow tether from proxy: $allow_tether"

    # Enable IP forward feature
    enable_forward "$ipv6_enabled"

    # Lock down xraytun0 interface
    lock_xraytun0

    # Loosen rp_filter on the hotspot/AP interface (see loosen_rp_filter above)
    loosen_rp_filter

    # =========================================================================
    # IPv4 CONFIGURATION
    # =========================================================================

    # Step 1: Set TUN device UP (xray's "gateway" config assigns the address
    # itself now — see helper.js's tun-in inbound)
    $ip link set dev $TUN_NAME up
    $ip route replace default dev $TUN_NAME table 100

    # Step 2: Routing Rule for marked packets
    $ip rule add fwmark 1 table 100 priority 1010

    # Step 3: Create Mangle chain for local output traffic
    $iptables -t mangle -N XRAY_MARK
    $iptables -t mangle -A XRAY_MARK -m mark --mark $FWMARK -j RETURN
    $iptables -t mangle -A XRAY_MARK -d 127.0.0.0/8 -j RETURN
    $iptables -t mangle -A XRAY_MARK -d 10.0.0.0/8 -j RETURN
    $iptables -t mangle -A XRAY_MARK -d 172.16.0.0/12 -j RETURN
    $iptables -t mangle -A XRAY_MARK -d 192.168.0.0/16 -j RETURN
    $iptables -t mangle -A XRAY_MARK -d 169.254.0.0/16 -j RETURN       # Link-local
    $iptables -t mangle -A XRAY_MARK -d 224.0.0.0/4 -j RETURN         # Multicast
    $iptables -t mangle -A XRAY_MARK -d 240.0.0.0/4 -j RETURN         # Class E (Reserved)

    # networkMode bypass (see query_settings .networkMode):
    #   0 = default, everything goes through XRAY_MARK
    #   1 = WiFi/Ethernet Only -> bypass mobile data interfaces
    #   2 = Mobile data Only   -> bypass everything except mobile data interfaces
    if [ "$network_mode" = "1" ]; then
        $iptables -t mangle -A XRAY_MARK -o rmnet+ -j RETURN
        $iptables -t mangle -A XRAY_MARK -o ccmni+ -j RETURN
        $iptables -t mangle -A XRAY_MARK -o pdp+ -j RETURN
        $iptables -t mangle -A XRAY_MARK -o wwan+ -j RETURN

        for uid in $XRAY_UID_LIST; do
            $iptables -t mangle -A XRAY_MARK -m owner --uid-owner "$uid" -j MARK --set-xmark 1
        done
    elif [ "$network_mode" = "2" ]; then
        # Only mark traffic that is BOTH on a mobile interface AND in the
        # allowed uid range; anything else falls through to the final
        # RETURN below and bypasses the proxy untouched.
        for mobile_if in rmnet+ ccmni+ pdp+ wwan+; do
            for uid in $XRAY_UID_LIST; do 
                $iptables -t mangle -A XRAY_MARK -o "$mobile_if" -m owner --uid-owner "$uid" -j MARK --set-xmark 1
            done
        done
        $iptables -t mangle -A XRAY_MARK -j RETURN
    else
        for uid in $XRAY_UID_LIST; do 
            $iptables -t mangle -A XRAY_MARK -m owner --uid-owner "$uid" -j MARK --set-xmark 1
        done
    fi
    $iptables -t mangle -A OUTPUT -j XRAY_MARK

    # Step 4: IPv4 + IPv6 Hotspot / Tethering Support
    # Filter FORWARD rules (both address families, see forward() above)
    forward -I

    # PREROUTING Mangle rules for incoming hotspot traffic
    $iptables -t mangle -N HOTSPOT_PREROUTING
    $iptables -t mangle -A HOTSPOT_PREROUTING -d 10.0.0.0/8 -j RETURN
    $iptables -t mangle -A HOTSPOT_PREROUTING -d 172.16.0.0/12 -j RETURN
    $iptables -t mangle -A HOTSPOT_PREROUTING -d 192.168.0.0/16 -j RETURN
    $iptables -t mangle -A HOTSPOT_PREROUTING -d 127.0.0.0/8 -j RETURN

    # allowTether (see query_settings .allowTether, default true):
    #   true  = tethered/hotspot clients are marked and routed through the proxy
    #   false = tethered/hotspot clients bypass the proxy entirely and use the
    #           device's normal/direct route (chain still exists but only RETURNs)
    if [ "$allow_tether" = true ]; then
        # Force DNS redirection for tethered clients to Cloudflare DNS
        $iptables -t nat -I PREROUTING ! -i $TUN_NAME -d 10.0.0.0/8 -p udp --dport 53 -j DNAT --to 1.1.1.1
        $iptables -t nat -I PREROUTING ! -i $TUN_NAME -d 172.16.0.0/12 -p udp --dport 53 -j DNAT --to 1.1.1.1
        $iptables -t nat -I PREROUTING ! -i $TUN_NAME -d 192.168.0.0/16 -p udp --dport 53 -j DNAT --to 1.1.1.1

        $iptables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -p tcp -j MARK --set-xmark 1
        $iptables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -p udp -j MARK --set-xmark 1
    fi
    $iptables -t mangle -I PREROUTING 1 -j HOTSPOT_PREROUTING

    # IP Routing rules for tethering private subnets
    $ip rule add iif lo goto 6000 pref 5000
    $ip rule add iif $TUN_NAME lookup main suppress_prefixlength 0 pref 5010
    $ip rule add iif $TUN_NAME goto 6000 pref 5020
    # Bypass LAN
    $ip rule add to 10.0.0.0/8 lookup main pref 5025
    $ip rule add to 172.16.0.0/12 lookup main pref 5026
    $ip rule add to 192.168.0.0/16 lookup main pref 5027
    if [ "$allow_tether" = true ]; then
        # Redirect Hotspot to table 100 (proxy)
        $ip rule add from 10.0.0.0/8 lookup 100 pref 5030
        $ip rule add from 172.16.0.0/12 lookup 100 pref 5040
        $ip rule add from 192.168.0.0/16 lookup 100 pref 5050
    fi
    $ip rule add nop pref 6000

    # Clamp TCP MSS to prevent fragmentation issues over VPN/TUN
    $iptables -t mangle -I FORWARD -o $TUN_NAME -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350

    # Hide proxy port from non-system apps
    $iptables -I OUTPUT -p tcp --dport $TUN_PORT -d $TUN_ADDR -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset

    # ICMP now needs no special handling — xray's tun-in inbound
    # proxies ICMP Echo request/reply natively (see LIMITATIONS in
    # README-proxy-tun-in.md: only Echo is supported, and replies are
    # generated locally by the TUN stack rather than validating real remote
    # reachability, but that's a strict improvement over the old
    # redirect-to-loopback workaround this replaces).
    # $iptables -t nat -N XRAY_FAKE_ICMP
    # $iptables -t nat -A XRAY_FAKE_ICMP -d 127.0.0.0/8 -j RETURN
    # $iptables -t nat -A XRAY_FAKE_ICMP -d 10.0.0.0/8 -j RETURN
    # $iptables -t nat -A XRAY_FAKE_ICMP -d 172.16.0.0/12 -j RETURN
    # $iptables -t nat -A XRAY_FAKE_ICMP -d 192.168.0.0/16 -j RETURN
    # $iptables -t nat -A XRAY_FAKE_ICMP -p icmp -j DNAT --to-destination 127.0.0.1
    # $iptables -t nat -I OUTPUT -p icmp -j XRAY_FAKE_ICMP
    # $iptables -t nat -I PREROUTING ! -i $TUN_NAME -p icmp -j XRAY_FAKE_ICMP


    # =========================================================================
    # IPv6 CONFIGURATION
    # =========================================================================

    if [ "$ipv6_enabled" = true ]; then
        # Step 1: Default route (address itself comes from xray's "gateway"
        # config now, same as the IPv4 side above)
        $ip -6 route replace default dev $TUN_NAME table 100

        # Step 2: Routing Rule for marked IPv6 packets
        $ip -6 rule add fwmark 1 table 100 priority 1010

        # Step 3: Create Mangle chain for local IPv6 output traffic
        $ip6tables -t mangle -N XRAY_MARK
        $ip6tables -t mangle -A XRAY_MARK -m mark --mark $FWMARK -j RETURN
        $ip6tables -t mangle -A XRAY_MARK -p udp --dport 53 -j DROP
        $ip6tables -t mangle -A XRAY_MARK -p tcp --dport 53 -j DROP
        $ip6tables -t mangle -A XRAY_MARK -d ::1/128 -j RETURN
        $ip6tables -t mangle -A XRAY_MARK -d fe80::/10 -j RETURN
        $ip6tables -t mangle -A XRAY_MARK -d fc00::/7 -j RETURN
        $ip6tables -t mangle -A XRAY_MARK -d ff00::/8 -j RETURN

        # networkMode bypass (mirrors IPv4 XRAY_MARK, see above)
        if [ "$network_mode" = "1" ]; then
            $ip6tables -t mangle -A XRAY_MARK -o rmnet+ -j RETURN
            $ip6tables -t mangle -A XRAY_MARK -o ccmni+ -j RETURN
            $ip6tables -t mangle -A XRAY_MARK -o pdp+ -j RETURN
            $ip6tables -t mangle -A XRAY_MARK -o wwan+ -j RETURN

            for uid in $XRAY_UID_LIST; do
                $ip6tables -t mangle -A XRAY_MARK -m owner --uid-owner "$uid" -j MARK --set-xmark 1
            done
        elif [ "$network_mode" = "2" ]; then
            for mobile_if in rmnet+ ccmni+ pdp+ wwan+; do
                for uid in $XRAY_UID_LIST; do
                    $ip6tables -t mangle -A XRAY_MARK -o "$mobile_if" -m owner --uid-owner "$uid" -j MARK --set-xmark 1
                done
            done
            $ip6tables -t mangle -A XRAY_MARK -j RETURN
        else
            for uid in $XRAY_UID_LIST; do
                $ip6tables -t mangle -A XRAY_MARK -m owner --uid-owner "$uid" -j MARK --set-xmark 1
            done
        fi
        $ip6tables -t mangle -A OUTPUT -j XRAY_MARK

        # Step 4: IPv6 Hotspot / Tethering Support

        # PREROUTING Mangle rules for incoming IPv6 hotspot traffic
        $ip6tables -t mangle -N HOTSPOT_PREROUTING
        $ip6tables -t mangle -A HOTSPOT_PREROUTING -p udp --dport 53 -j DROP
        $ip6tables -t mangle -A HOTSPOT_PREROUTING -p tcp --dport 53 -j DROP
        $ip6tables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -d ::1/128 -j RETURN
        $ip6tables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -d fe80::/10 -j RETURN
        $ip6tables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -d fc00::/7 -j RETURN
        # allowTether (see query_settings .allowTether, default true): only mark
        # tethered/hotspot IPv6 traffic for the proxy when tethering is allowed
        # to use it; otherwise let it RETURN and use the normal direct route.
        if [ "$allow_tether" = true ]; then
            $ip6tables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -j MARK --set-xmark 1
        fi
        $ip6tables -t mangle -I PREROUTING 1 -j HOTSPOT_PREROUTING

        # Step 5: Hotspot Forwarding (Clamp IPv6 TCP MSS)
        $ip6tables -t mangle -N HOTSPOT_FORWARD
        $ip6tables -t mangle -A HOTSPOT_FORWARD -o $TUN_NAME -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1330
        $ip6tables -t mangle -I FORWARD 1 -j HOTSPOT_FORWARD
    else
        # Step 1: Disable IPv6 at system level and block via routing rule
        # (If in mode 1 or 2, we let non-managed interfaces handle IPv6 normally)
        if [ "$network_mode" != "1" ] && [ "$network_mode" != "2" ]; then
            $ip -6 rule add unreachable priority 1010
        fi

        # Step 2: Create Mangle chain for local IPv6 output traffic (Drop all IPv6)
        $ip6tables -t mangle -N XRAY_MARK
        # Allow core proxy socket bypass if fwmark is already present
        $ip6tables -t mangle -A XRAY_MARK -m mark --mark $FWMARK -j RETURN
        if [ "$network_mode" = "1" ]; then
            $ip6tables -t mangle -A XRAY_MARK -o rmnet+ -j RETURN
            $ip6tables -t mangle -A XRAY_MARK -o ccmni+ -j RETURN
            $ip6tables -t mangle -A XRAY_MARK -o pdp+ -j RETURN
            $ip6tables -t mangle -A XRAY_MARK -o wwan+ -j RETURN
            # Drop all outgoing IPv6 traffic from local applications
            $ip6tables -t mangle -A XRAY_MARK -j DROP
        elif [ "$network_mode" = "2" ]; then
            $ip6tables -t mangle -A XRAY_MARK -o rmnet+ -j DROP
            $ip6tables -t mangle -A XRAY_MARK -o ccmni+ -j DROP
            $ip6tables -t mangle -A XRAY_MARK -o pdp+ -j DROP
            $ip6tables -t mangle -A XRAY_MARK -o wwan+ -j DROP
            $ip6tables -t mangle -A XRAY_MARK -j RETURN
        else
            # Drop all outgoing IPv6 traffic from local applications
            $ip6tables -t mangle -A XRAY_MARK -j DROP
        fi
        $ip6tables -t mangle -A OUTPUT -j XRAY_MARK

        # Step 3: Create Mangle chain for IPv6 Hotspot/Tethering traffic (Drop all IPv6)
        $ip6tables -t mangle -N HOTSPOT_PREROUTING
        # Drop all incoming IPv6 traffic from connected hotspot clients
        $ip6tables -t mangle -A HOTSPOT_PREROUTING -j DROP
        $ip6tables -t mangle -I PREROUTING 1 -j HOTSPOT_PREROUTING

        # Step 4: Reject all forwarded IPv6 traffic for Hotspot clients
        $ip6tables -t filter -N HOTSPOT_FORWARD
        $ip6tables -t filter -A HOTSPOT_FORWARD -j REJECT --reject-with icmp6-no-route
        $ip6tables -t filter -I FORWARD 1 -j HOTSPOT_FORWARD
    fi
}

clear_routing_rules() {
    # =========================================================================
    # CLEAR IPv4 RULES
    # =========================================================================

    # Delete local mangle output rules
    $iptables -t mangle -D OUTPUT -j XRAY_MARK
    $iptables -t mangle -F XRAY_MARK
    $iptables -t mangle -X XRAY_MARK
    $ip rule del fwmark 1 table 100 priority 1010
    $iptables -D OUTPUT -p tcp --dport $TUN_PORT -d $TUN_ADDR -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset

    # Delete hotspot rules & ip rules
    $ip rule del pref 5000
    $ip rule del pref 5010
    $ip rule del pref 5020
    $ip rule del pref 5025
    $ip rule del pref 5026
    $ip rule del pref 5027
    $ip rule del pref 5030
    $ip rule del pref 5040
    $ip rule del pref 5050
    $ip rule del pref 6000

    # Remove FORWARD ACCEPT rules for both address families (see forward() above)
    forward -D

    $iptables -D PREROUTING -t nat ! -i $TUN_NAME -d 10.0.0.0/8 -p udp --dport 53 -j DNAT --to 1.1.1.1
    $iptables -D PREROUTING -t nat ! -i $TUN_NAME -d 172.16.0.0/12 -p udp --dport 53 -j DNAT --to 1.1.1.1
    $iptables -D PREROUTING -t nat ! -i $TUN_NAME -d 192.168.0.0/16 -p udp --dport 53 -j DNAT --to 1.1.1.1

    $iptables -t mangle -D PREROUTING -j HOTSPOT_PREROUTING
    $iptables -t mangle -F HOTSPOT_PREROUTING
    $iptables -t mangle -X HOTSPOT_PREROUTING

    $iptables -t mangle -D FORWARD -o $TUN_NAME -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350


    # =========================================================================
    # CLEAR IPv6 RULES
    # =========================================================================

    # Delete local mangle output rules
    $ip6tables -t mangle -D OUTPUT -j XRAY_MARK
    $ip6tables -t mangle -F XRAY_MARK
    $ip6tables -t mangle -X XRAY_MARK
    $ip -6 rule del priority 1010

    # Delete hotspot rules

    $ip6tables -t mangle -D PREROUTING -j HOTSPOT_PREROUTING
    $ip6tables -t mangle -F HOTSPOT_PREROUTING
    $ip6tables -t mangle -X HOTSPOT_PREROUTING

    $ip6tables -t mangle -D FORWARD -j HOTSPOT_FORWARD
    $ip6tables -t mangle -F HOTSPOT_FORWARD
    $ip6tables -t mangle -X HOTSPOT_FORWARD

    $ip6tables -t filter -D FORWARD -j HOTSPOT_FORWARD
    $ip6tables -t filter -F HOTSPOT_FORWARD
    $ip6tables -t filter -X HOTSPOT_FORWARD

    # Down the TUN device
    $ip link set dev $TUN_NAME down
}

# ===========================================================================
# 8. Command loop
# ===========================================================================

start_xray() {
    if is_proc_running "xray"; then
        log "xray already running (pid $XRAY_PID)"
        return 0
    fi
    if [ ! -s "$CONFIG_FILE" ]; then
        log "refusing to start: config.json missing or empty"
        return 1
    fi

    "$BINDIR/xray" run -c "$CONFIG_FILE" </dev/null >"$XRAY_LOG" 2>&1 &
    XRAY_PID=$!
    echo "$XRAY_PID" > "$PIDFILE"
    log "xray started with pid $XRAY_PID"

    mount_proc_with_name "$XRAY_PID" "xray"
    apply_routing_rules
    touch "$ENABLED_FLAG"
    return 0
}

stop_xray() {
    clear_routing_rules 2>/dev/null

    # Kill by tracked PID, then fall back to the pid file. The fallback covers
    # the case where an earlier build lost track of the process and left it
    # running with the routing rules applied.
    if [ "$XRAY_PID" -gt 0 ] 2>/dev/null; then
        kill -9 "$XRAY_PID" 2>/dev/null
    fi
    if [ -f "$PIDFILE" ]; then
        local stale
        stale=$(cat "$PIDFILE" 2>/dev/null)
        if [ -n "$stale" ] && [ "$stale" != "$XRAY_PID" ]; then
            log "killing untracked xray pid $stale from pidfile"
            kill -9 "$stale" 2>/dev/null
        fi
        rm -f "$PIDFILE"
    fi
    XRAY_PID=0
    umount_proc_with_name "xray"
    rm -f "$ENABLED_FLAG"
    log "xray stopped"
    return 0
}

do_job() {
    local content="$1"
    case "$content" in
        wait)
            return 0
            ;;
        apply_cur_iface)
            local cur_iface
            if cur_iface=$(get_active_interface); then
                log "re-applying mark rule for $cur_iface"
                apply_mark_rule "$cur_iface"
            else
                log "no active interface to apply"
            fi
            return 0
            ;;
        start)
            start_xray
            return 0
            ;;
        stop)
            stop_xray
            return 0
            ;;
        start_monitor)
            stop_iface_monitor
            monitor_net_interfaces &
            IFACE_MONITOR_PID=$!
            mount_proc_with_name "$IFACE_MONITOR_PID" "monitor_net_interfaces"
            log "interface monitor running with pid $IFACE_MONITOR_PID"
            return 0
            ;;
        stop_monitor)
            stop_iface_monitor
            log "interface monitor stopped"
            return 0
            ;;
        start_monitor_latency)
            stop_latency_monitor
            monitor_network_latency &
            LATENCY_MONITOR_PID=$!
            mount_proc_with_name "$LATENCY_MONITOR_PID" "monitor_network_latency"
            log "latency monitor running with pid $LATENCY_MONITOR_PID"
            return 0
            ;;
        stop_monitor_latency)
            stop_latency_monitor
            log "latency monitor stopped"
            return 0
            ;;
        latency_heartbeat)
            # Refreshed by the UI while its Latency tab is open; the probe
            # loop exits on its own once these stop arriving.
            [ -f "$LATENCY_HB_FILE" ] && date +%s > "$LATENCY_HB_FILE"
            return 0
            ;;
        reset_mobile_network)
            local cur_iface
            if cur_iface=$(get_active_interface); then
                ip_hunt_reset
                check_ip_hunter "$cur_iface"
            fi
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

# Control loop. Each iteration re-opens the FIFO, which blocks in the kernel
# until a writer appears — no polling, no wakeups while idle.
{
while true; do
    if read -r line < "$PIPE_FILE"; then
        if [ -n "$line" ]; then
            if ! do_job "$line"; then
                log "unknown command: $line"
            fi
        fi
    fi
done
} &
CONTROL_LOOP_PID=$!
echo "$CONTROL_LOOP_PID" > "$RUN_DIR/control_loop.pid"

# ===========================================================================
# 9. Boot sequencer
# ===========================================================================

{
# Wait for netd to publish the routing table map.
boot_wait=0
while [ ! -f /data/misc/net/rt_tables ] && [ "$boot_wait" -lt 300 ]; do
    sleep 1
    boot_wait=$((boot_wait + 1))
done
if [ ! -f /data/misc/net/rt_tables ]; then
    log "rt_tables never appeared after ${boot_wait}s; continuing anyway"
fi

echo "start_monitor" > "$PIPE_FILE"

boot_wait=0
until [ "$(getprop sys.boot_completed)" = "1" ] || [ "$boot_wait" -ge 600 ]; do
    sleep 1
    boot_wait=$((boot_wait + 1))
done
sleep 5

if [ ! -e /dev/net/tun ]; then
    mkdir -p /dev/net
    mknod /dev/net/tun c 10 200
    # 0600: only root needs this. It used to be 0666, which is wider than
    # anything on the device requires.
    chmod 600 /dev/net/tun
fi

# xraytun0 is no longer created by a separate hev-socks5-tunnel process: it is
# now the "tun-in" inbound inside xray's own config.json, brought up as part
# of start_xray below (apply_routing_rules waits for the interface to appear
# the same way it used to wait for hev-socks5-tunnel).

# Resume the previous session only if the user actually left the engine
# running. This used to key off the mere existence of config.json, so a
# config that had been explicitly stopped still came back at boot.
if [ -s "$CONFIG_FILE" ] && [ -f "$ENABLED_FLAG" ]; then
    log "restoring previous session"
    echo "start" > "$PIPE_FILE"
    echo "wait"  > "$PIPE_FILE"
fi

log "boot sequence complete"
} &
