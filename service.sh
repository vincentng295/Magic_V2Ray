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
#   6.  Monitors (network interface, latency, apps)  <- event-driven, bounded
#   7.  Routing rules  <- UNCHANGED in this pass, pending review
#   8.  Command loop
#   9.  Boot sequencer
#
# NOTE: section 7 (apply_routing_rules / clear_routing_rules) is intentionally
# carried over verbatim (apart from the Exclude Apps hooks: the XRAY_EXCLUDE_APP
# jump in each XRAY_MARK chain and the sync_exclude_apps call). The firewall/DNS/fail-safe fixes for it are staged as
# separate reviewed changes so a routing regression can never be confused with
# a lifecycle regression.

MODDIR=${0%/*}
BINDIR="$MODDIR/bin"
DATADIR="/data/adb/magic_v2ray"
STUB_DIR=/dev/sysctl_stubs
CONFIG_NAME=config.v2.json

# ===========================================================================
# 1. Paths, constants, logging
# ===========================================================================

RUN_DIR="$STUB_DIR/run"
PROC_DIR="$STUB_DIR/proc"
LOG_DIR="$STUB_DIR/logs"

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
mkdir -p "$RUN_DIR" "$PROC_DIR" "$LOG_DIR"
chmod 700 "$RUN_DIR" "$PROC_DIR" "$LOG_DIR"

LOG_CTRL="$LOG_DIR/control.pipe"
XRAY_LOG="$LOG_DIR/input.pipe"
XRAY_LOG_FILE="$DATADIR/xray.log"
# Create the logservice input pipe first so the helper can start writing to it
"$MODDIR/bin/xhuskydg_helper" logservice create -c "$LOG_CTRL" -i "$XRAY_LOG" --daemonize

SERVICE_LOG="$DATADIR/service.log"
IP_HUNT_FILE="$DATADIR/ip_hunt.list"
# Comma-separated interface names whose outbound traffic skips Xray
# entirely. Plain file rather than a settings.base64 key — same pattern as
# IP_HUNT_FILE: existence means enabled, content is the interface list.
BYPASS_IFACE_FILE="$DATADIR/bypassIface.txt"
ENABLED_FLAG="$DATADIR/enabled"

# Runtime state files (tmpfs; cleared on every boot)
PIDFILE="$RUN_DIR/xray.pid"
TIME_RES_FILE="$RUN_DIR/time_res"
ADDR_INFO_FILE="$RUN_DIR/addr_info"
LATENCY_HB_FILE="$RUN_DIR/latency.hb"
PIPE_FILE="$RUN_DIR/control.pipe"
IFACE_EVENT_PIPE="$RUN_DIR/iface_events.pipe"
IFACE_MON_CHILD="$RUN_DIR/iface_monitor_child.pid"

# Exclude Apps (see "Exclude Apps" in section 6). EXCLUDE_LIST_FILE is written
# by the WebUI; the uid records and the event FIFO live in tmpfs.
APP_DATA_DIR="/data/data"
EXCLUDE_LIST_FILE="$DATADIR/excludelist.txt"
EXCLUDE_MAP_DIR="$RUN_DIR/exclude_uids"
APP_EVENT_PIPE="$RUN_DIR/app_events.pipe"
APPS_MON_CHILD="$RUN_DIR/apps_monitor_child.pid"

# IPv6 ULA decoy (see "IPv6 ULA decoy" in section 6). ULA_FLAG existing means
# the feature is active for this engine run (written by apply_routing_rules,
# removed by clear_routing_rules), so the interface monitor can test it with
# no forks. Its content is the prefix length to put on the address: 64 when
# hotspot clients may use the proxy (allowTether), 128 otherwise.
# ULA_IFACE_FILE remembers which interface currently carries the
# address; ULA_SUFFIX_FILE keeps the random xxxx:xxxx stable until reboot.
ULA_FLAG="$RUN_DIR/ula_enabled"
ULA_IFACE_FILE="$RUN_DIR/ula_iface"
ULA_SUFFIX_FILE="$RUN_DIR/ula_suffix"
# fc00:7872:6179::/64 - "xray" in hex (78 72 61 79). The address is this prefix
# plus a random 32-bit suffix, e.g. fc00:7872:6179::d6ba:7979.
ULA_PREFIX="fc00:7872:6179::"

# List of UIDs we want them to be routed into Xray-core
XRAY_UID_LIST="
0-2147483647
"

XRAY_UID_EXCLUDE_LIST="
1001
"

# Full RFC-reserved / non-globally-routable IPv4 & IPv6 ranges. This is the
# single source of truth for "LAN bypass" — every mangle chain and every
# policy-routing rule that needs to exclude private/special-use destinations
# loops over these two lists instead of hard-coding a handful of subnets.
#
# IPv4: dropped the extra /4 and /24 aliases for the Class-E range
# (255.0.0.0/4, 255.255.255.0/24) that the source list had alongside
# 240.0.0.0/4 — they're fully contained in it and would just be dead
# duplicate rules. 255.255.255.255/32 (limited broadcast) is kept since it
# is *not* covered by 240.0.0.0/4.
LAN_BYPASS_V4="
10.0.0.0/8
100.64.0.0/10
127.0.0.0/8
169.254.0.0/16
172.16.0.0/12
192.0.0.0/24
192.0.2.0/24
192.88.99.0/24
192.168.0.0/16
198.51.100.0/24
203.0.113.0/24
224.0.0.0/4
240.0.0.0/4
255.255.255.255/32
"

# NOTE: "includeLan" (Network tab, default off) does not edit these lists. It
# only swaps which list the *bypass* rules consume: apply_routing_rules()
# builds LAN_SKIP_V4/LAN_SKIP_V6 from them (everything when off, loopback
# only when on) and uses those for the mangle RETURNs and the pref-5025
# "to <cidr> lookup main" rules. The tether DNS DNAT and the pref-5030
# "from <cidr>" rules keep using the full lists on purpose.
#
# IPv6. ::ffff:0:0/96 (IPv4-mapped) is included for completeness but rarely
# appears on the wire — it mostly matters for local dual-stack sockets.
#
# FakeDNS pools must never be bypassed: the addresses Xray hands out for
# fake DNS answers have to enter the tun so Xray can map them back to a
# domain. IPv4 198.18.0.0/15 is simply not in LAN_BYPASS_V4. The IPv6 pool
# (fc00::/18, see FAKEDNS_POOL_V6 in helper.js) sits inside ULA fc00::/7, so
# that entry is written as fc00::/7 MINUS fc00::/18 — the 11 CIDRs from
# fc00:4000::/18 up to fd00::/8. If the pool in helper.js ever changes, this
# split has to change with it.
LAN_BYPASS_V6="
::1/128
::ffff:0:0/96
64:ff9b::/96
100::/64
2001::/32
2001:10::/28
2001:20::/28
2001:db8::/32
2002::/16
fc00:4000::/18
fc00:8000::/17
fc01::/16
fc02::/15
fc04::/14
fc08::/13
fc10::/12
fc20::/11
fc40::/10
fc80::/9
fd00::/8
fe80::/10
ff00::/8
"


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
APPS_MONITOR_PID=0

ip="/system/bin/ip"
iptables="/system/bin/iptables"
ip6tables="/system/bin/ip6tables"

RULE_PRIORITY=1000
FWMARK=255
TUN_NAME="xraytun0"
TUN_ADDR="127.17.1.3"
TUN_PORT="808"

# Loopback pool for plaintext-VLESS tunnels (helper.js: applyPlainVlessLoopback).
# Xray listens on 127.18.<block>.<n>:80 with a dokodemo-door inbound per
# plaintext VLESS server. Must match PLAIN_POOL_PREFIX / PLAIN_POOL_PORT there.
PLAIN_POOL_NET="127.18.0.0/16"
PLAIN_POOL_PORT="80"

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

# ---------------------------------------------------------------------------
# VPN-app bypass (fixes the xray <-> other-VpnService routing loop)
#
# Problem: when a third-party app opens its own VpnService, Android's
# default route flips to that app's own tun (e.g. "tun0") and our
# fwmark-based monitor would otherwise keep marking that app's own egress
# packets. Those packets get read back out of the *other* VpnService,
# re-enter xray, get marked again, and never leave -> infinite loop.
#
# Fix: Android itself must exclude the VPN app's own uid from its tun's
# policy routing (or the VPN app would loop on its own tun the same way),
# which shows up as a single-uid gap in `ip rule show ... lookup <tun>`
# between two uidrange blocks (see find_vpn_app_uids). We keep a small,
# standalone mangle chain (BYPASS_VPN_UID) permanently populated with that
# uid so the app's traffic exits XRAY_MARK untouched — independent of
# whether xray is currently running, and without ever having to rebuild the
# rest of the ruleset.
# ---------------------------------------------------------------------------

# Idempotent: safe to call at boot, on every apply_routing_rules, and from
# the interface monitor. -N fails harmlessly if the chain already exists.
ensure_bypass_vpn_chain() {
    $iptables  -t mangle -N BYPASS_VPN_UID 2>/dev/null
    $ip6tables -t mangle -N BYPASS_VPN_UID 2>/dev/null
    return 0
}

# Replaces the whole bypass set in one go. Call with no arguments to clear
# it (e.g. once the foreign VPN app is no longer the active route, so a
# later app that inherits the same uid never gets bypassed by mistake).
#
# Deliberately ACCEPT, not RETURN: BYPASS_VPN_UID is called from XRAY_MARK
# with a plain jump, and RETURN would only unwind back into XRAY_MARK
# (still hitting the uid-owner MARK rules below it) instead of leaving the
# mangle table entirely. ACCEPT terminates that table's traversal outright,
# regardless of nesting, which is what an actual bypass needs. This never
# touches nat/filter, so normal routing/forwarding for that uid is
# unaffected.
set_vpn_bypass_uids() {
    ensure_bypass_vpn_chain
    $iptables  -t mangle -F BYPASS_VPN_UID 2>/dev/null
    $ip6tables -t mangle -F BYPASS_VPN_UID 2>/dev/null
    local uid
    for uid in "$@"; do
        [ -z "$uid" ] && continue
        $iptables  -t mangle -A BYPASS_VPN_UID -m owner --uid-owner "$uid" -j ACCEPT
        $ip6tables -t mangle -A BYPASS_VPN_UID -m owner --uid-owner "$uid" -j ACCEPT
        log "vpn bypass: uid $uid added to BYPASS_VPN_UID"
    done
    return 0
}

# Interfaces we already know are physical/mobile radios, our own tun, or
# loopback. Anything else that becomes the default route (tun*, ppp*, ...)
# is treated as a foreign VpnService and is a candidate for uid bypass.
is_vpn_enabled() {
    local iface="$1" iface_index
    iface_index="$(read_table_index "$iface")"
    [ -z "$iface_index" ] && return 1
    return 0
}

# Finds the uid Android excluded from a foreign VpnService's own policy
# routing table, i.e. the VPN app's own uid (it has to be excluded there or
# the VPN app would loop on its own tun the same way xray would)
find_vpn_app_uids() {
    local pids pid uid fd_path fd_num fd_flags

    pids=$(fuser /dev/tun /dev/net/tun 2>/dev/null)
    [ -z "$pids" ] && return 1

    for pid in $pids; do
        uid=$(stat -c '%u' "/proc/$pid" 2>/dev/null)

        if [ -n "$uid" ] && [ "$uid" -ge 10000 ]; then
            for fd_path in /proc/"$pid"/fd/*; do
                if readlink "$fd_path" 2>/dev/null | grep -qE "/dev/net/tun|/dev/tun"; then
                    fd_num=$(basename "$fd_path")
                    fd_flags=$(grep -i "flags" "/proc/$pid/fdinfo/$fd_num" 2>/dev/null | awk '{print $2}')

                    case "$fd_flags" in
                        *2|*6)
                            echo "$uid"
                            break
                            ;;
                    esac
                fi
            done
        fi
    done | sort -u
}

# Called whenever the active interface changes. Keeps BYPASS_VPN_UID in
# sync regardless of whether xray is currently started — the chain is
# always ready, apply_routing_rules just has to reference it.
update_vpn_bypass() {
    local vpn_uids
    if is_vpn_enabled "tun0"; then
        log "foreign VPN interface detected"

        local attempt=0 max_attempts=5
        while [ "$attempt" -lt "$max_attempts" ]; do
            vpn_uids=$(find_vpn_app_uids)
            [ -n "$vpn_uids" ] && break
            attempt=$((attempt + 1))
            [ "$attempt" -lt "$max_attempts" ] && sleep 0.2
        done

        if [ -n "$vpn_uids" ]; then
            log "vpn bypass: uid(s) [$(echo "$vpn_uids" | tr '\n' ' ')] -> BYPASS_VPN_UID"
            set_vpn_bypass_uids $vpn_uids
        else
            log "vpn bypass: could not determine uid"
        fi
    else
        set_vpn_bypass_uids
    fi
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

# --- IPv6 ULA decoy ----------------------------------------------------------
#
# xraytun0 is not the default network, so Android judges IPv6 by the physical
# interface alone. When that interface only has IPv4, apps see "no IPv6" and
# never open IPv6 sockets, even though Xray could carry them. With the
# "enableIPv6ULA" setting on (and enableIPv6), a fc00:7872:6179::xxxx:xxxx address
# is added to the active interface so apps consider IPv6 usable. No route goes
# into the main table: marked packets are re-routed into $TUN_NAME by
# XRAY_MARK, and Xray's own egress (fwmark $FWMARK) must keep falling back to
# IPv4 instead of trying a v6 path that goes nowhere.
#
# Prefix length: /64 when allowTether is on, /128 when it is off. Android's
# tethering only advertises (router advertisement) a /64 taken from the
# upstream interface, so /64 is what lets hotspot clients pick up
# fc00:7872:6179::/64 as well; their traffic is then marked in
# HOTSPOT_PREROUTING and sent to the tun like the phone's own. With tethering
# off nothing should be advertised, so the address stays a /128.
#
# noprefixroute: no connected prefix route is created in the main table. That
# matters for /64: the prefix is on-link on the hotspot interface, not on the
# upstream one, so an on-link route here would send replies to clients the
# wrong way.
# nodad: the address is usable immediately instead of sitting in "tentative".
#
# Address alone is not enough: with no IPv6 route, `ip -6 route get <global>`
# falls through to Android's "32000: from all unreachable" rule and returns
# "unreachable", so apps still see IPv6 as unusable. We therefore also install
# a private policy-routing entry just before that catch-all:
#
#   pref 31998  fwmark $FWMARK unreachable   Xray's own egress keeps failing
#                                            fast and falls back to IPv4
#   pref 31999  lookup table 31999           default dev <iface> src <ULA>
#
# Marked app traffic never gets here (rule 1010 sends it into $TUN_NAME first).
# Only unmarked traffic (excluded apps, foreign VPN apps, bypassIface) does.

ULA_TABLE=31999
ULA_RULE_PREF=31999
ULA_BLOCK_PREF=31998

# Idempotent: table 31999 holds `default dev <iface> src <ULA>` for the current
# interface, and the two rules above exist exactly once.
ula_route_apply() {
    local iface="$1" addr="$2" rules
    if ! $ip -6 route replace default dev "$iface" src "$addr" metric 1024 \
        table $ULA_TABLE 2>/dev/null; then
        log "ULA: route add failed on $iface"
        return 1
    fi

    rules="$($ip -6 rule show 2>/dev/null)"
    echo "$rules" | grep -q "^$ULA_BLOCK_PREF:" \
        || $ip -6 rule add fwmark $FWMARK unreachable pref $ULA_BLOCK_PREF 2>/dev/null
    echo "$rules" | grep -q "^$ULA_RULE_PREF:" \
        || $ip -6 rule add lookup $ULA_TABLE pref $ULA_RULE_PREF 2>/dev/null
    return 0
}

ula_route_remove() {
    while $ip -6 rule del pref $ULA_RULE_PREF 2>/dev/null; do :; done
    while $ip -6 rule del pref $ULA_BLOCK_PREF 2>/dev/null; do :; done
    $ip -6 route flush table $ULA_TABLE 2>/dev/null
}

ula_suffix() {
    local u hi lo
    if [ -s "$ULA_SUFFIX_FILE" ]; then
        cat "$ULA_SUFFIX_FILE"
        return 0
    fi
    u=$(tr -d '-' < /proc/sys/kernel/random/uuid 2>/dev/null)
    [ "${#u}" -ge 8 ] || return 1
    # printf %x drops leading zeros so the text matches what `ip addr show`
    # prints back (needed by the grep in ula_apply). A zero first group would
    # be compressed away by `ip`, so nudge it to 1.
    hi=$(printf '%x' "0x$(echo "$u" | cut -c1-4)")
    lo=$(printf '%x' "0x$(echo "$u" | cut -c5-8)")
    [ "$hi" = 0 ] && hi=1
    echo "$hi:$lo" | tee "$ULA_SUFFIX_FILE"
}

# Idempotent: makes sure the active interface carries the ULA address and
# that no other interface still does. Cheap no-op when the feature is off.
ula_apply() {
    local iface="$1" suffix addr old len
    [ -f "$ULA_FLAG" ] || return 0
    [ -n "$iface" ] || return 0
    [ "$iface" = "$TUN_NAME" ] && return 0

    suffix=$(ula_suffix) || { log "ULA: could not generate address"; return 1; }
    addr="$ULA_PREFIX$suffix"

    # Prefix length lives in the flag file (see apply_routing_rules); `read`
    # is a builtin, so this stays fork-free. Anything unexpected -> /128.
    len=""
    read -r len < "$ULA_FLAG" 2>/dev/null
    case "$len" in 64|128) ;; *) len=128 ;; esac

    old=$(cat "$ULA_IFACE_FILE" 2>/dev/null)
    if [ -n "$old" ] && [ "$old" != "$iface" ]; then
        ula_addr_del "$addr" "$old"
        log "ULA: removed $addr from $old"
    fi

    if $ip -6 addr show dev "$iface" 2>/dev/null | grep -qF "inet6 $addr/$len"; then
        echo "$iface" > "$ULA_IFACE_FILE"
        ula_route_apply "$iface" "$addr"
        return 0
    fi

    # Not there with the wanted length. It may exist with the other one (the
    # allowTether setting changed), and an address cannot be re-added with a
    # different prefix length, so drop both first.
    ula_addr_del "$addr" "$iface"

    if $ip -6 addr replace "$addr/$len" dev "$iface" nodad noprefixroute 2>/dev/null \
        || $ip -6 addr replace "$addr/$len" dev "$iface" 2>/dev/null; then
        # Fallback form (no noprefixroute support) makes the kernel add the
        # on-link prefix route to the main table; remove it, see header above.
        [ "$len" = 64 ] && $ip -6 route del "$ULA_PREFIX/64" dev "$iface" 2>/dev/null
        echo "$iface" > "$ULA_IFACE_FILE"
        log "ULA: added $addr/$len to $iface"
        ula_route_apply "$iface" "$addr"
        return 0
    fi
    log "ULA: failed to add $addr/$len to $iface"
    return 1
}

# Deletes the ULA address from an interface whichever prefix length it has.
ula_addr_del() {
    $ip -6 addr del "$1/64" dev "$2" 2>/dev/null
    $ip -6 addr del "$1/128" dev "$2" 2>/dev/null
}

# Removes the address and drops the flag. Used when the feature is switched
# off and when the engine stops.
ula_remove() {
    local old suffix
    old=$(cat "$ULA_IFACE_FILE" 2>/dev/null)
    if [ -n "$old" ]; then
        suffix=$(cat "$ULA_SUFFIX_FILE" 2>/dev/null)
        [ -n "$suffix" ] && ula_addr_del "$ULA_PREFIX$suffix" "$old"
        log "ULA: removed from $old"
    fi
    ula_route_remove
    rm -f "$ULA_FLAG" "$ULA_IFACE_FILE"
}

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
        update_vpn_bypass
        ula_apply "$cur"
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

        update_vpn_bypass

        if [ "$new" = "$cur" ]; then
            # Same interface, but netd/IpClient may have flushed its
            # addresses during a reconnect: re-assert the ULA (no-op if
            # present or if the feature is off).
            ula_apply "$cur"
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
        ula_apply "$new"
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

# --- Exclude Apps ----------------------------------------------------------
#
# Apps listed in $EXCLUDE_LIST_FILE (one package name per line, written by the
# Exclude Apps tab) skip Xray when the "excludeApps" setting is true.
#
# Mechanism: a standalone mangle chain, XRAY_EXCLUDE_APP, holds one
# `-m owner --uid-owner <uid> -j ACCEPT` rule per excluded uid. XRAY_MARK
# jumps to it before anything is marked. ACCEPT rather than RETURN for the
# same reason as BYPASS_VPN_UID: the chain is entered with a plain jump, so
# RETURN would only unwind into XRAY_MARK and the MARK rules below would
# still catch the uid.
#
# Lifecycle:
#   * Full start (apply_routing_rules -> sync_exclude_apps): the chain is
#     flushed and rebuilt from the list. The UI forces a full restart on every
#     change, so the list file never changes under a running monitor.
#   * monitor_apps (started right after, only while the feature is on) keeps
#     the chain correct between restarts as apps come and go. It watches
#     /data/data with inotifyd: <pkg> appears on install, disappears on
#     uninstall.
#
# Removal cannot stat() a directory that is already gone, so every rule is
# recorded in $EXCLUDE_MAP_DIR/<pkg> (contents: the uid). The record also
# keeps shared-uid packages honest: a uid's rule is only deleted once the
# last excluded package using it is gone, and a uid Android later hands to a
# different app is never left excluded by mistake.
#
# Only user 0 (/data/data) is covered.

ensure_exclude_app_chain() {
    $iptables  -t mangle -N XRAY_EXCLUDE_APP 2>/dev/null
    $ip6tables -t mangle -N XRAY_EXCLUDE_APP 2>/dev/null
    return 0
}

# Empties the chain and the uid records. Chain and records are always reset
# together so they cannot disagree.
reset_exclude_app_chain() {
    ensure_exclude_app_chain
    $iptables  -t mangle -F XRAY_EXCLUDE_APP 2>/dev/null
    $ip6tables -t mangle -F XRAY_EXCLUDE_APP 2>/dev/null
    rm -rf "${EXCLUDE_MAP_DIR:?}" 2>/dev/null
    mkdir -p "$EXCLUDE_MAP_DIR"
    return 0
}

# The list name ends up in a path under /data/data, so reject anything that
# is not a plain package name (no slashes, no leading dot, no "..").
valid_pkg_name() {
    case "$1" in
        ''|.*|*..*|*[!A-Za-z0-9_.]*) return 1 ;;
    esac
    return 0
}

# Cleaned list, one entry per line (CRs, comments and blank lines removed).
read_exclude_list() {
    [ -f "$EXCLUDE_LIST_FILE" ] || return 0
    tr -d '\r' < "$EXCLUDE_LIST_FILE" \
        | sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
        | grep -v '^$'
}

exclude_list_has() {
    read_exclude_list | grep -qxF -- "$1"
}

# Owner uid of /data/data/<pkg>. installd creates the directory first and
# chowns it a moment later, so on install events retry until it is no longer
# root-owned.
get_app_uid() {
    local pkg="$1" tries="${2:-1}" uid n=0
    while :; do
        uid=$(stat -c '%u' "$APP_DATA_DIR/$pkg" 2>/dev/null)
        case "$uid" in *[!0-9]*) uid="" ;; esac
        if [ -n "$uid" ] && [ "$uid" -ge 1000 ]; then
            echo "$uid"
            return 0
        fi
        n=$((n + 1))
        [ "$n" -ge "$tries" ] && return 1
        sleep 0.2
    done
}

# True if another recorded package (not $1) already holds uid $2.
uid_held_by_other() {
    local pkg="$1" uid="$2" f
    for f in "$EXCLUDE_MAP_DIR"/*; do
        [ -f "$f" ] || continue
        [ "${f##*/}" = "$pkg" ] && continue
        [ "$(cat "$f" 2>/dev/null)" = "$uid" ] && return 0
    done
    return 1
}

exclude_uid_rule_add() {
    $iptables  -t mangle -A XRAY_EXCLUDE_APP -m owner --uid-owner "$1" -j ACCEPT
    $ip6tables -t mangle -A XRAY_EXCLUDE_APP -m owner --uid-owner "$1" -j ACCEPT
}

exclude_uid_rule_del() {
    $iptables  -t mangle -D XRAY_EXCLUDE_APP -m owner --uid-owner "$1" -j ACCEPT 2>/dev/null
    $ip6tables -t mangle -D XRAY_EXCLUDE_APP -m owner --uid-owner "$1" -j ACCEPT 2>/dev/null
}

# Adds $1's uid to the chain (idempotent). $2 = uid read attempts.
exclude_app_add() {
    local pkg="$1" tries="${2:-1}" uid old
    valid_pkg_name "$pkg" || return 1
    uid="$(get_app_uid "$pkg" "$tries")" || return 1

    old="$(cat "$EXCLUDE_MAP_DIR/$pkg" 2>/dev/null)"
    [ "$old" = "$uid" ] && return 0

    if [ -n "$old" ]; then
        # The package came back under a different uid without us seeing it go.
        rm -f "$EXCLUDE_MAP_DIR/$pkg"
        uid_held_by_other "$pkg" "$old" || exclude_uid_rule_del "$old"
    fi

    echo "$uid" > "$EXCLUDE_MAP_DIR/$pkg"
    # A shared uid already has its rule; adding it again would just duplicate.
    uid_held_by_other "$pkg" "$uid" || exclude_uid_rule_add "$uid"
    log "exclude apps: $pkg (uid $uid) excluded"
    return 0
}

# Drops $1's uid from the chain, using the record since the directory is gone.
exclude_app_remove() {
    local pkg="$1" uid
    valid_pkg_name "$pkg" || return 1
    [ -f "$EXCLUDE_MAP_DIR/$pkg" ] || return 0
    uid="$(cat "$EXCLUDE_MAP_DIR/$pkg" 2>/dev/null)"
    rm -f "$EXCLUDE_MAP_DIR/$pkg"
    if [ -n "$uid" ] && ! uid_held_by_other "$pkg" "$uid"; then
        exclude_uid_rule_del "$uid"
    fi
    log "exclude apps: $pkg (uid ${uid:-?}) no longer excluded"
    return 0
}

# Brings the chain in line with what is installed right now.
reconcile_exclude_apps() {
    local list pkg
    list="$(read_exclude_list)"
    [ -z "$list" ] && return 0
    while read -r pkg; do
        valid_pkg_name "$pkg" || continue
        if [ -d "$APP_DATA_DIR/$pkg" ]; then
            exclude_app_add "$pkg"
        else
            exclude_app_remove "$pkg"
        fi
    done <<EOF
$list
EOF
    return 0
}

# Full reset + rebuild. Called from apply_routing_rules() BEFORE XRAY_MARK is
# attached to OUTPUT, so excluded apps are never briefly proxied.
sync_exclude_apps() {
    reset_exclude_app_chain
    if ! setting_is_true excludeApps; then
        log "exclude apps: disabled"
        return 0
    fi
    reconcile_exclude_apps
    log "exclude apps: $(ls -1 "$EXCLUDE_MAP_DIR" 2>/dev/null | wc -l) app(s) in XRAY_EXCLUDE_APP"
    return 0
}

# Event-driven like monitor_net_interfaces: inotifyd writes to a FIFO and its
# pid is recorded, so stop_apps_monitor can kill it (a plain pipeline would
# orphan it). Events look like "n<TAB>/data/data<TAB>com.foo" (n = subfile
# created, d = subfile deleted); the package is taken from the last field.
monitor_apps() {
    local line events path name

    rm -f "$APP_EVENT_PIPE"
    mkfifo "$APP_EVENT_PIPE" 2>/dev/null || return 1

    inotifyd - "$APP_DATA_DIR:nd" > "$APP_EVENT_PIPE" 2>/dev/null &
    echo $! > "$APPS_MON_CHILD"

    # Opening the read end lets the backgrounded inotifyd get past its own
    # open of the FIFO. Keep it on fd 3 so the catch-up below can run first.
    exec 3< "$APP_EVENT_PIPE"

    # Anything installed/removed between sync_exclude_apps() and the watch
    # becoming active; events arriving meanwhile just queue in the FIFO.
    sleep 0.3
    reconcile_exclude_apps
    log "apps monitor: watching $APP_DATA_DIR"

    while read -r line <&3; do
        read -r events path name <<EOF
$line
EOF
        [ -z "$name" ] && name="${path##*/}"
        name="${name##*/}"
        valid_pkg_name "$name" || continue

        case "$events" in
            *n*)
                if exclude_list_has "$name"; then
                    exclude_app_add "$name" 15 \
                        || log "apps monitor: could not read uid of $name"
                fi
                ;;
            *d*)
                exclude_app_remove "$name"
                ;;
        esac
    done

    exec 3<&-
    log "apps monitor exiting"
}

stop_apps_monitor() {
    if [ -f "$APPS_MON_CHILD" ]; then
        local child
        child=$(cat "$APPS_MON_CHILD" 2>/dev/null)
        [ -n "$child" ] && kill -9 "$child" 2>/dev/null
        rm -f "$APPS_MON_CHILD"
    fi
    kill_tracked "$APPS_MONITOR_PID" "monitor_apps"
    APPS_MONITOR_PID=0
    rm -f "$APP_EVENT_PIPE"
}

# Only runs while Exclude apps is on. Changing that setting restarts the
# engine, which comes back through here, so no separate re-check is needed.
start_apps_monitor() {
    stop_apps_monitor
    if ! setting_is_true excludeApps; then
        log "apps monitor: Exclude apps is off, not started"
        return 0
    fi
    monitor_apps &
    APPS_MONITOR_PID=$!
    mount_proc_with_name "$APPS_MONITOR_PID" "monitor_apps"
    log "apps monitor running with pid $APPS_MONITOR_PID"
    return 0
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
    $ip addr show "$(get_active_interface)" > "$ADDR_INFO_FILE" 2>/dev/null

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

# ---------------------------------------------------------------------------
# configure_tun_iface(): (re)assigns address/mtu/up/default-route on
# $TUN_NAME. Split out of apply_routing_rules() so it can also be called from
# restart_xray() — with openxtun, xraytun0 is a brand-new netdevice instance
# every time xray (re)starts (non-persistent TUN: dies with the process that
# holds its fd), so its address/UP-state/table-100 default route are gone
# too and must be redone even on a plain reload, not just a full start/stop.
# Deliberately touches only the interface itself, never iptables/ip-rule, so
# restart_xray's "leave routing rules untouched" contract still holds.
# ---------------------------------------------------------------------------
configure_tun_iface() {
    local retry=0
    local max_retry=20
    while [ $retry -lt $max_retry ]; do
        if $ip link show "$TUN_NAME" >/dev/null 2>&1; then
            break
        fi
        sleep 0.5
        retry=$((retry + 1))
    done
    if [ $retry -eq $max_retry ]; then
        log "error: $TUN_NAME did not appear after ${max_retry} retries, abort"
        return 1
    fi

    # Recomputed here (not just inherited from apply_routing_rules' global)
    # because restart_xray() calls this directly without going through
    # apply_routing_rules first.
    ipv6_enabled="$(setting_is_true enableIPv6 && echo true || echo false)"

    lock_xraytun0
    # openxtun only does TUNSETIFF; it never sets an MTU, so this has to
    # happen here (previously hev-socks5-tunnel's tunnel.yml set mtu: 8500).
    $ip link set dev $TUN_NAME mtu 8500
    $ip addr add 198.18.0.1/15 dev $TUN_NAME 2>/dev/null
    $ip link set dev $TUN_NAME up
    $ip route replace default dev $TUN_NAME table 100

    if [ "$ipv6_enabled" = true ]; then
        $ip -6 addr add fdfe:dcba:9876::1/64 dev $TUN_NAME 2>/dev/null
        $ip -6 route replace default dev $TUN_NAME table 100
    fi
    return 0
}

apply_routing_rules() {
    ipv6_enabled="$(setting_is_true enableIPv6 && echo true || echo false)"
    echo "IPv6 enabled: $ipv6_enabled"

    network_mode="$(query_settings networkMode)"
    [ -z "$network_mode" ] && network_mode=0
    echo "Network mode: $network_mode"

    allow_tether="$(setting_is_true allowTether && echo true || echo false)"
    echo "Allow tether from proxy: $allow_tether"

    # includeLan (default false, fail closed): when true, LAN / private /
    # special-use destinations are sent into Xray like any other traffic
    # instead of being skipped. Only loopback stays excluded — 127.0.0.0/8
    # and ::1/128 — because xray's own inbounds (socks-test-in on 127.17.1.3,
    # local DNS, ...) live there and must never be looped through the tun.
    # What Xray then does with LAN traffic is decided by its own routing
    # rules (the stock config sends geoip:private / geosite:private direct).
    include_lan="$(setting_is_true includeLan && echo true || echo false)"
    echo "Include LAN traffic: $include_lan"
    if [ "$include_lan" = true ]; then
        LAN_SKIP_V4="127.0.0.0/8"
        LAN_SKIP_V6="::1/128"
    else
        LAN_SKIP_V4="$LAN_BYPASS_V4"
        LAN_SKIP_V6="$LAN_BYPASS_V6"
    fi

    # bypassIface: comma-separated list of interface names whose outbound
    # traffic skips XRAY_MARK entirely (e.g. tailscale0, wt0) — useful for
    # VPN/mesh interfaces that must never be re-tunneled through the proxy.
    # Stored in BYPASS_IFACE_FILE rather than settings.base64 (see var
    # comment); existence of the file means the feature is enabled.
    bypass_iface_list=""
    if [ -f "$BYPASS_IFACE_FILE" ]; then
        raw_bypass_iface="$(tr -d '\r' < "$BYPASS_IFACE_FILE")"
        [ -z "$raw_bypass_iface" ] && raw_bypass_iface="tailscale0,wt0"
        bypass_iface_list="$(echo "$raw_bypass_iface" | tr ',' ' ')"
    fi
    echo "Bypass interfaces: ${bypass_iface_list:-<none>}"

    # IPv6 ULA decoy: only meaningful when IPv6 is routed through Xray at
    # all. Raising ULA_FLAG here (a full restart always passes through this
    # function when the setting changes) is what arms the interface monitor;
    # the current interface is handled right away instead of waiting for the
    # next route event.
    if [ "$ipv6_enabled" = true ] && setting_is_true enableIPv6ULA; then
        # The flag's content is the prefix length for ula_apply: /64 lets
        # Android advertise the prefix to hotspot clients, so only use it when
        # tethered clients are allowed through the proxy; otherwise /128.
        local ula_iface ula_len
        if [ "$allow_tether" = true ]; then ula_len=64; else ula_len=128; fi
        echo "IPv6 ULA decoy: on (/$ula_len)"
        echo "$ula_len" > "$ULA_FLAG"
        ula_iface="$(get_active_interface)" && ula_apply "$ula_iface"
    else
        ula_remove
    fi

    # Enable IP forward feature
    enable_forward "$ipv6_enabled"

    # Assign address/mtu/up/default-route on $TUN_NAME (both IPv4 and, if
    # enabled, IPv6 — see configure_tun_iface() above). Also re-run from
    # restart_xray() since openxtun recreates this interface from scratch
    # on every (re)start.
    if ! configure_tun_iface; then
        return 1
    fi

    # BYPASS_VPN_UID must exist before XRAY_MARK can jump to it. This is
    # normally already created at boot (and kept alive across xray
    # stop/start by clear_routing_rules never touching it) — this call is
    # just a cheap self-heal in case that boot-time call was ever missed.
    ensure_bypass_vpn_chain

    # Rebuild XRAY_EXCLUDE_APP from the Exclude Apps list. Done now, before
    # XRAY_MARK is attached to OUTPUT below, so excluded apps are never
    # proxied even for a moment. monitor_apps keeps it current afterwards.
    sync_exclude_apps

    # Loosen rp_filter on the hotspot/AP interface (see loosen_rp_filter above)
    loosen_rp_filter

    # =========================================================================
    # IPv4 CONFIGURATION
    # =========================================================================

    # Step 2: Routing Rule for marked packets
    $ip rule add fwmark 1 table 100 priority 1010

    # Step 3: Create Mangle chain for local output traffic
    $iptables -t mangle -N XRAY_MARK
    # Foreign VpnService apps (see BYPASS_VPN_UID above) never get marked,
    # so xray never re-swallows their already-tunneled traffic and loops.
    $iptables -t mangle -A XRAY_MARK -j BYPASS_VPN_UID
    # Excluded apps (Exclude Apps tab) are never marked either.
    $iptables -t mangle -A XRAY_MARK -j XRAY_EXCLUDE_APP
    $iptables -t mangle -A XRAY_MARK -m mark --mark $FWMARK -j RETURN
    # bypassIface: let traffic bound for these interfaces skip the proxy
    # entirely, ahead of the networkMode logic below.
    for bypass_if in $bypass_iface_list; do
        $iptables -t mangle -A XRAY_MARK -o "$bypass_if" -j RETURN
    done
    for cidr in $LAN_SKIP_V4; do
        $iptables -t mangle -A XRAY_MARK -d "$cidr" -j RETURN
    done

    # Exclude UIDs from the proxy entirely, regardless of networkMode below.
    for uid in $XRAY_UID_EXCLUDE_LIST; do
        $iptables -t mangle -A XRAY_MARK -m owner --uid-owner "$uid" -j RETURN
    done

    # networkMode bypass (see query_settings networkMode):
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
    for cidr in $LAN_SKIP_V4; do
        $iptables -t mangle -A HOTSPOT_PREROUTING -d "$cidr" -j RETURN
    done

    # allowTether (see query_settings allowTether, default true):
    #   true  = tethered/hotspot clients are marked and routed through the proxy
    #   false = tethered/hotspot clients bypass the proxy entirely and use the
    #           device's normal/direct route (chain still exists but only RETURNs)
    if [ "$allow_tether" = true ]; then
        # Force DNS redirection for tethered clients to Cloudflare DNS
        for cidr in $LAN_BYPASS_V4; do
            $iptables -t nat -I PREROUTING ! -i $TUN_NAME -d "$cidr" -p udp --dport 53 -j DNAT --to 1.1.1.1
        done

        $iptables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -p tcp -j MARK --set-xmark 1
        $iptables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -p udp -j MARK --set-xmark 1
        # ICMP from tethered/hotspot clients: mark it too so it's routed into
        # table 100 (xraytun0) the same way tcp/udp already are, instead of
        # being left unmarked (and previously DNAT'd to loopback below).
        $iptables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -p icmp -j MARK --set-xmark 1
    fi
    $iptables -t mangle -I PREROUTING 1 -j HOTSPOT_PREROUTING

    # IP Routing rules for tethering private subnets
    $ip rule add iif lo goto 6000 pref 5000
    $ip rule add iif $TUN_NAME lookup main suppress_prefixlength 0 pref 5010
    $ip rule add iif $TUN_NAME goto 6000 pref 5020
    # Bypass LAN (IPv4). All rules share one pref each so clear_routing_rules
    # can wipe the whole set with a loop-delete, the same idiom remove_mark_rule
    # already uses for the fwmark rule.
    for cidr in $LAN_SKIP_V4; do
        $ip rule add to "$cidr" lookup main pref 5025
    done
    if [ "$allow_tether" = true ]; then
        # Redirect Hotspot to table 100 (proxy)
        for cidr in $LAN_BYPASS_V4; do
            $ip rule add from "$cidr" lookup 100 pref 5030
        done
    fi
    $ip rule add nop pref 6000

    # Clamp TCP MSS to prevent fragmentation issues over VPN/TUN
    $iptables -t mangle -I FORWARD -o $TUN_NAME -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350

    # Hide proxy port from non-system apps
    $iptables -I OUTPUT -p tcp --dport $TUN_PORT -d $TUN_ADDR -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset

    # Same for the plaintext-VLESS tunnel pool: an app that connects to
    # 127.18.x.x:80 would reach the raw VLESS server (fingerprint it, or
    # detect that a proxy is running). Xray runs as root (uid 0), so its own
    # vless-out -> tunnel hop is unaffected. Always installed: it is a no-op
    # when no tunnel listens, and keeps the rule set independent of the node.
    $iptables -I OUTPUT -p tcp --dport $PLAIN_POOL_PORT -d $PLAIN_POOL_NET -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset



    # =========================================================================
    # IPv6 CONFIGURATION
    # =========================================================================

    if [ "$ipv6_enabled" = true ]; then
        # IPv6 address + default route on $TUN_NAME already done by
        # configure_tun_iface() above.

        # Step 2: Routing Rule for marked IPv6 packets
        $ip -6 rule add fwmark 1 table 100 priority 1010

        # Step 3: Create Mangle chain for local IPv6 output traffic
        $ip6tables -t mangle -N XRAY_MARK
        # See the IPv4 XRAY_MARK chain above for why this jump exists.
        $ip6tables -t mangle -A XRAY_MARK -j BYPASS_VPN_UID
        $ip6tables -t mangle -A XRAY_MARK -j XRAY_EXCLUDE_APP
        $ip6tables -t mangle -A XRAY_MARK -m mark --mark $FWMARK -j RETURN
        # bypassIface: mirrors the IPv4 XRAY_MARK rule above.
        for bypass_if in $bypass_iface_list; do
            $ip6tables -t mangle -A XRAY_MARK -o "$bypass_if" -j RETURN
        done
        # $ip6tables -t mangle -A XRAY_MARK -p udp --dport 53 -j DROP
        # $ip6tables -t mangle -A XRAY_MARK -p tcp --dport 53 -j DROP
        for cidr in $LAN_SKIP_V6; do
            $ip6tables -t mangle -A XRAY_MARK -d "$cidr" -j RETURN
        done

        # Exclude UIDs from the proxy entirely, regardless of networkMode below.
        for uid in $XRAY_UID_EXCLUDE_LIST; do
            $ip6tables -t mangle -A XRAY_MARK -m owner --uid-owner "$uid" -j RETURN
        done

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
        # $ip6tables -t mangle -A HOTSPOT_PREROUTING -p udp --dport 53 -j DROP
        # $ip6tables -t mangle -A HOTSPOT_PREROUTING -p tcp --dport 53 -j DROP
        for cidr in $LAN_SKIP_V6; do
            $ip6tables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -d "$cidr" -j RETURN
        done
        # allowTether (see query_settings allowTether, default true): only mark
        # tethered/hotspot IPv6 traffic for the proxy when tethering is allowed
        # to use it; otherwise let it RETURN and use the normal direct route.
        if [ "$allow_tether" = true ]; then
            $ip6tables -t mangle -A HOTSPOT_PREROUTING ! -i $TUN_NAME -j MARK --set-xmark 1
        fi
        $ip6tables -t mangle -I PREROUTING 1 -j HOTSPOT_PREROUTING

        # Bypass LAN (IPv6) via policy routing — mirrors the IPv4 rules above.
        # The previous version had no equivalent here at all, so IPv6 LAN
        # traffic depended entirely on the mangle RETURNs, with no fwmark-level
        # policy-routing bypass; this closes that gap.
        for cidr in $LAN_SKIP_V6; do
            $ip -6 rule add to "$cidr" lookup main pref 5025
        done
        if [ "$allow_tether" = true ]; then
            for cidr in $LAN_BYPASS_V6; do
                $ip -6 rule add from "$cidr" lookup 100 pref 5030
            done
        fi

        # Step 5: Hotspot Forwarding (Clamp IPv6 TCP MSS)
        $ip6tables -t mangle -N HOTSPOT_FORWARD
        $ip6tables -t mangle -A HOTSPOT_FORWARD -o $TUN_NAME -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1330
        $ip6tables -t mangle -I FORWARD 1 -j HOTSPOT_FORWARD
    else
        # Step 1: Disable IPv6 at system level and block via routing rule
        # (If in mode 1 or 2, we let non-managed interfaces handle IPv6 normally)
        if [ "$network_mode" != "1" ] && [ "$network_mode" != "2" ]; then
            $ip -6 rule add fwmark 1 unreachable priority 1010
        fi

        # Step 2: Create Mangle chain for local IPv6 output traffic (Drop all IPv6)
        $ip6tables -t mangle -N XRAY_MARK
        # See the IPv4 XRAY_MARK chain above for why this jump exists.
        $ip6tables -t mangle -A XRAY_MARK -j BYPASS_VPN_UID
        $ip6tables -t mangle -A XRAY_MARK -j XRAY_EXCLUDE_APP
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
    $iptables -D OUTPUT -p tcp --dport $PLAIN_POOL_PORT -d $PLAIN_POOL_NET -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset


    # Delete hotspot rules & ip rules
    $ip rule del pref 5000
    $ip rule del pref 5010
    $ip rule del pref 5020
    # pref 5025/5030 now hold one rule per LAN_BYPASS_V4 entry (see
    # apply_routing_rules), so a single "del" only removes one of them —
    # loop-delete the same way remove_mark_rule() does for the fwmark rule.
    while $ip rule del pref 5025 2>/dev/null; do :; done
    while $ip rule del pref 5030 2>/dev/null; do :; done
    $ip rule del pref 6000

    # Remove FORWARD ACCEPT rules for both address families (see forward() above)
    forward -D

    for cidr in $LAN_BYPASS_V4; do
        $iptables -D PREROUTING -t nat ! -i $TUN_NAME -d "$cidr" -p udp --dport 53 -j DNAT --to 1.1.1.1
    done

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
    # LAN bypass rules added in apply_routing_rules (only present when IPv6
    # was enabled that run, but harmless no-ops otherwise since these fail
    # silently when there's nothing to delete).
    while $ip -6 rule del pref 5025 2>/dev/null; do :; done
    while $ip -6 rule del pref 5030 2>/dev/null; do :; done

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

    # Drop the IPv6 ULA decoy address along with the rest of the rules.
    ula_remove

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
    if [ ! -s "$DATADIR/$CONFIG_NAME" ]; then
        log "refusing to start: $CONFIG_NAME missing or empty"
        return 1
    fi

    # openxtun opens /dev/net/tun, ioctl(TUNSETIFF) into $TUN_NAME, sets
    # XRAY_TUN_FD, then execvp's into xray (same pid — exec doesn't change
    # it). xray's tun-in inbound reads that fd directly; the interface is
    # non-persistent so it lives and dies with this process.
    "$MODDIR/bin/xhuskydg_helper" logservice flush -c "$LOG_CTRL"
    "$BINDIR/xhuskydg_helper" openxtun "$TUN_NAME" "$BINDIR/xray" run -c "$DATADIR/$CONFIG_NAME" \
        </dev/null >"$XRAY_LOG" 2>&1 &
    XRAY_PID=$!
    echo "$XRAY_PID" > "$PIDFILE"
    log "xray (via openxtun, tun=$TUN_NAME) started with pid $XRAY_PID"

    mount_proc_with_name "$XRAY_PID" "xray"
    apply_routing_rules
    start_apps_monitor
    touch "$ENABLED_FLAG"
    return 0
}

stop_xray() {
    stop_apps_monitor
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
    # Save output from the last run to the log file for later retrieval by the UI.
    "$MODDIR/bin/xhuskydg_helper" logservice read -c "$LOG_CTRL" -o "$XRAY_LOG_FILE"
    "$MODDIR/bin/xhuskydg_helper" logservice flush -c "$LOG_CTRL"
    return 0
}

# Swaps the running xray process for a fresh one reading the newly written
# $CONFIG_NAME, WITHOUT touching iptables/policy routing. Used for "config
# changed while running" (new node selected, node edited, Xray-level routing
# rules edited) — none of those touch anything apply_routing_rules reads
# (advSettings.networkMode / .allowTether / .enableIPv6 and the uid lists),
# so tearing the rules down and rebuilding them on every node switch was
# pure overhead and a brief window with no marking/forwarding rules at all.
# Advanced-settings saves that actually change those fields still go through
# the full stop+start path so the rules get rebuilt.
restart_xray() {
    if [ ! -s "$DATADIR/$CONFIG_NAME" ]; then
        log "refusing to reload: $CONFIG_NAME missing or empty"
        return 1
    fi

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
    fi
    umount_proc_with_name "xray"
    "$MODDIR/bin/xhuskydg_helper" logservice read -c "$LOG_CTRL" -o "$XRAY_LOG_FILE"
    "$MODDIR/bin/xhuskydg_helper" logservice flush -c "$LOG_CTRL"
    XRAY_PID=0

    # Must go through openxtun here too: a bare xray relaunch has no
    # XRAY_TUN_FD, so tun-in would come up with no fd at all. And because
    # $TUN_NAME is non-persistent, openxtun creates a brand-new netdevice
    # instance every time — the old one's address/UP-state/table-100 default
    # route died with the previous process, so configure_tun_iface() below
    # is required even though iptables/ip-rule state is intentionally left
    # untouched.
    "$BINDIR/xhuskydg_helper" openxtun "$TUN_NAME" "$BINDIR/xray" run -c "$DATADIR/$CONFIG_NAME" \
        </dev/null >"$XRAY_LOG" 2>&1 &
    XRAY_PID=$!
    echo "$XRAY_PID" > "$PIDFILE"
    log "xray reloaded with pid $XRAY_PID (tun=$TUN_NAME re-created, iptables/ip-rule left untouched)"

    mount_proc_with_name "$XRAY_PID" "xray"
    if ! configure_tun_iface; then
        return 1
    fi
    touch "$ENABLED_FLAG"
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
        reload_config)
            restart_xray
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
        start_monitor_apps)
            start_apps_monitor
            return 0
            ;;
        stop_monitor_apps)
            stop_apps_monitor
            log "apps monitor stopped"
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

# Create the VPN-app bypass chain now, before the interface monitor starts,
# so it's already there to receive uid updates the moment any foreign
# VpnService becomes the active route — independent of whether xray itself
# is ever started this boot.
ensure_bypass_vpn_chain
ensure_exclude_app_chain

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

# $TUN_NAME is no longer a standalone boot-time service (that was
# hev-socks5-tunnel). It's now created by openxtun as part of start_xray /
# restart_xray, tied to xray's own process lifetime — see configure_tun_iface().

# Resume the previous session only if the user actually left the engine
# running. This used to key off the mere existence of $CONFIG_NAME, so a
# config that had been explicitly stopped still came back at boot.
if [ -s "$DATADIR/$CONFIG_NAME" ] && [ -f "$ENABLED_FLAG" ]; then
    log "restoring previous session"
    echo "start" > "$PIPE_FILE"
    echo "wait"  > "$PIPE_FILE"
fi

log "boot sequence complete"
} &