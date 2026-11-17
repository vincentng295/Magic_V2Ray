#!/system/bin/sh
#
# Magic V2Ray — module removal teardown.
#
# Runs when the user uninstalls the module (Magisk / KernelSU / APatch all
# execute this from the module directory before deleting it).
#
# Design rules for this file:
#   * Fully self-contained. It must work even if service.sh is already gone
#     and even if the proxy was never started this boot.
#   * Idempotent and non-fatal. Every removal is "delete if present"; nothing
#     here may abort the uninstall, so all errors are swallowed deliberately.
#   * A superset of the runtime teardown, including the LAN gateway rules.
#
# KEEP IN SYNC with service.sh's clear_routing_rules()/gateway_stop(). These
# lists are deliberately duplicated so uninstall works standalone; the shared
# rule definitions are being factored into bin/lib_rules.sh separately.

MODDIR=${0%/*}
DATADIR="/data/adb/magic_v2ray"
STUB_DIR="/mnt/runtime/magicv2ray"          # runtime state: a plain directory, no mount
LEGACY_STUB_DIR="/dev/sysctl_stubs"        # older builds kept it on a private tmpfs there
TUN_NAME="xraytun0"
TUN_ADDR="127.17.1.3"
TUN_PORT="808"
FWMARK=255

ip="/system/bin/ip"
iptables="/system/bin/iptables"
ip6tables="/system/bin/ip6tables"

log() { echo "[magic_v2ray uninstall] $*"; }

# --- 1. Stop our processes -------------------------------------------------
# Let the native service tear itself down first (xray, monitors, ip rules, iptables, mounts).
[ -x "$MODDIR/bin/xhuskydg_helper" ] && timeout 40 "$MODDIR/bin/xhuskydg_helper" service --stop 2>/dev/null
# Matched by full binary path so we never touch an unrelated xray/curl.
for proc in xray hev-socks5-tunnel curl; do
    pkill -9 -f "$MODDIR/bin/$proc" 2>/dev/null
    pkill -9 -f "/data/adb/modules/magic_v2ray/bin/$proc" 2>/dev/null
done
pkill -9 -f "ip monitor route" 2>/dev/null
log "stopped module processes"

# --- 2. iptables / ip6tables ----------------------------------------------

# Unlink, flush and delete a chain, tolerating any of it not existing.
drop_chain() {
    local bin="$1" table="$2" parent="$3" chain="$4"
    while $bin -t "$table" -D "$parent" -j "$chain" 2>/dev/null; do :; done
    $bin -t "$table" -F "$chain" 2>/dev/null
    $bin -t "$table" -X "$chain" 2>/dev/null
}

# Repeat deletion so duplicate rules from earlier double-applies are cleared.
drop_rule() {
    local bin="$1"; shift
    while $bin "$@" 2>/dev/null; do :; done
}

for chain in XRAY_MARK HOTSPOT_PREROUTING MV2R_GATEWAY MV2R_DNS; do
    drop_chain "$iptables"  mangle OUTPUT     "$chain"
    drop_chain "$iptables"  mangle PREROUTING "$chain"
    drop_chain "$iptables"  nat    PREROUTING "$chain"
    drop_chain "$iptables"  filter FORWARD    "$chain"
    drop_chain "$ip6tables" mangle OUTPUT     "$chain"
    drop_chain "$ip6tables" mangle PREROUTING "$chain"
    drop_chain "$ip6tables" filter FORWARD    "$chain"
done
drop_chain "$iptables"  mangle FORWARD MV2R_MSS
drop_chain "$ip6tables" mangle FORWARD HOTSPOT_FORWARD
drop_chain "$ip6tables" filter FORWARD HOTSPOT_FORWARD

# Traffic Monitor tab counters: per-client upload (mangle PREROUTING) and download (mangle POSTROUTING) at the hotspot.
for bin in "$iptables" "$ip6tables"; do
    drop_rule "$bin" -t mangle -D PREROUTING -j MV2R_HS_UP
    $bin -t mangle -F MV2R_HS_UP 2>/dev/null
    $bin -t mangle -X MV2R_HS_UP 2>/dev/null
    drop_rule "$bin" -t mangle -D POSTROUTING -j MV2R_HS_DN
    $bin -t mangle -F MV2R_HS_DN 2>/dev/null
    $bin -t mangle -X MV2R_HS_DN 2>/dev/null
    # older builds: hooked on xraytun0
    drop_rule "$bin" -t mangle -D POSTROUTING -o "$TUN_NAME" -j MV2R_TRAFFIC
    $bin -t mangle -F MV2R_TRAFFIC 2>/dev/null
    $bin -t mangle -X MV2R_TRAFFIC 2>/dev/null
    # download counters (traffic.c)
    drop_rule "$bin" -t mangle -D FORWARD -i "$TUN_NAME" -j MV2R_TRAFFIC_RX
    drop_rule "$bin" -t mangle -D INPUT -i "$TUN_NAME" -j MV2R_TRAFFIC_RX
    $bin -t mangle -F MV2R_TRAFFIC_RX 2>/dev/null
    $bin -t mangle -X MV2R_TRAFFIC_RX 2>/dev/null
    # device block / limit chain (traffic.c)
    drop_rule "$bin" -t mangle -D FORWARD -j MV2R_TRAFFIC_CTL
    $bin -t mangle -F MV2R_TRAFFIC_CTL 2>/dev/null
    $bin -t mangle -X MV2R_TRAFFIC_CTL 2>/dev/null
done

# Standalone rules that were inserted directly into built-in chains.
drop_rule "$iptables" -t mangle -D FORWARD -o "$TUN_NAME" -p tcp \
    --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350
drop_rule "$iptables" -D OUTPUT -p tcp --dport "$TUN_PORT" -d "$TUN_ADDR" \
    -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset
drop_rule "$iptables" -D OUTPUT -p udp --dport "$TUN_PORT" -d "$TUN_ADDR" \
    -m owner --uid-owner 9999-2147483647 -j REJECT

for fam in "$iptables" "$ip6tables"; do
    drop_rule "$fam" -D FORWARD -i "$TUN_NAME" -j ACCEPT
    drop_rule "$fam" -D FORWARD -o "$TUN_NAME" -j ACCEPT
    drop_rule "$fam" -D FORWARD -i "$TUN_NAME" -m conntrack \
        --ctstate RELATED,ESTABLISHED -j ACCEPT
done
drop_rule "$ip6tables" -D FORWARD -j REJECT --reject-with icmp6-no-route

# Legacy hardcoded tethering DNS redirects (pre-configurable-DNS builds).
for net in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16; do
    drop_rule "$iptables" -t nat -D PREROUTING ! -i "$TUN_NAME" -d "$net" \
        -p udp --dport 53 -j DNAT --to 1.1.1.1
done
# Any DNAT this build inserted with a user-configured resolver is caught by
# the MV2R_DNS chain removal above.
log "removed netfilter rules"

# --- 3. Policy routing rules ----------------------------------------------
# Every priority this module has ever used, deleted until none remain so
# duplicates from repeated applies cannot survive.
for pref in 1000 1010 5000 5010 5020 5025 5026 5027 5030 5040 5050 5060 6000; do
    while $ip    rule del pref "$pref" 2>/dev/null; do :; done
    while $ip -6 rule del pref "$pref" 2>/dev/null; do :; done
done
while $ip rule del fwmark "$FWMARK" 2>/dev/null; do :; done
while $ip -6 rule del fwmark "$FWMARK" 2>/dev/null; do :; done

$ip    route flush table 100 2>/dev/null
$ip -6 route flush table 100 2>/dev/null
log "removed policy routing rules"

# --- 4. TUN device ---------------------------------------------------------
$ip link set dev "$TUN_NAME" down 2>/dev/null
$ip link del dev "$TUN_NAME" 2>/dev/null

# --- 5. sysctl restore -----------------------------------------------------
# The gateway feature bind-mounts a stub over ip_forward so netd cannot reset
# it; that mount must come off before the real value is meaningful again.
umount /proc/sys/net/ipv4/ip_forward 2>/dev/null

if [ -f "$STUB_DIR/run/sysctl.bak" ]; then
    # Lines are "<proc path> <original value>".
    while read -r path value; do
        [ -n "$path" ] && [ -w "$path" ] && echo "$value" > "$path" 2>/dev/null
    done < "$STUB_DIR/run/sysctl.bak"
    log "restored saved sysctl values"
else
    # No snapshot (module never started this boot, or an older build).
    # Leave ip_forward alone rather than guessing — netd owns it, and a
    # reboot restores the stock value anyway.
    log "no sysctl snapshot; leaving kernel values untouched"
fi

# --- 6. Runtime state ------------------------------------------------------
rm -rf "$STUB_DIR" 2>/dev/null
# leftovers of an older build (it bind-mounted /proc/<pid> entries under proc/ and mounted a tmpfs on the dir)
umount -l "$LEGACY_STUB_DIR/proc/xray" 2>/dev/null
umount -l "$LEGACY_STUB_DIR/proc/monitor_net_interfaces" 2>/dev/null
umount -l "$LEGACY_STUB_DIR/proc/monitor_network_latency" 2>/dev/null
umount -l "$LEGACY_STUB_DIR" 2>/dev/null
rm -rf "$LEGACY_STUB_DIR" 2>/dev/null

# --- 7. User data ----------------------------------------------------------
# profiles / settings / config.json hold every server address, UUID, password
# and WireGuard private key the user imported. This is the whole reason the
# missing uninstall.sh mattered.
rm -rf "$DATADIR"
log "removed $DATADIR"

log "teardown complete"
exit 0
