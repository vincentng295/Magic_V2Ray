# Review packet — Groups 4–6 (routing) and Task 3 (LAN gateway)

**Status: NOT APPLIED.** Branch `hardening/groups-1-3` holds the landed work. Everything below is proposed and awaits your approval, per the audit's own risk gating.

Read §0 first — there is a requirements conflict I need you to settle.

---

## 0. Conflict I need you to decide

Task 3 requirement 1 says the gateway toggle must be **off by default**.
Task 1 rule 5 says every breaking change must **default to preserving current behavior**.

These contradict each other, because **LAN gateway sharing is currently always on**. `apply_routing_rules()` unconditionally installs the `ip rule` prefs 5000–6000 block, the three DNS DNAT rules, and the blanket `FORWARD -j ACCEPT` pair on every engine start. Anyone using hotspot sharing today gets it without asking for it.

| Option | Behaviour | Cost |
|---|---|---|
| **A — off by default** (Task 3 req 1, and what I've built) | Gateway rules only when toggled on | **Breaking** for existing hotspot users: sharing silently stops after update until they flip the switch |
| **B — on by default** | Preserves today's behaviour exactly | Keeps M2's open-router exposure on by default for the ~majority who never tether |
| **C — migrate** | Fresh installs off; existing installs (settings file present, no `gatewayEnabled` key) default on | No breakage, secure for new users; slightly more code, and "off by default" isn't strictly true |

**My recommendation: C.** It satisfies both instructions honestly. It's ~6 lines in `bindSettingsToFormView`. I've written the code for **A** since that's the literal requirement — say the word and I'll switch it to C (or B).

Either way this is a flagged breaking change in the changelog.

---

## Group 4 — Fail-safe datapath

### 4.1 (C3) Abort when the TUN never appeared

```diff
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
+    # Previously fell through here regardless. `ip addr add` and
+    # `ip route replace` would fail, but the MARK rules and
+    # `ip rule fwmark 1 table 100` still installed — steering every marked
+    # packet into an empty table 100. Silent, total blackhole.
+    if ! $ip link show "$TUN_NAME" >/dev/null 2>&1; then
+        log "FATAL: $TUN_NAME did not appear after $((max_retry / 2))s; not applying routing"
+        return 1
+    fi
```

and at the call site:

```diff
     mount_proc_with_name "$XRAY_PID" "xray"
-    apply_routing_rules
+    if ! apply_routing_rules; then
+        log "rolling back: killing xray and clearing partial rules"
+        kill -9 "$XRAY_PID" 2>/dev/null
+        XRAY_PID=0
+        umount_proc_with_name "xray"
+        rm -f "$PIDFILE"
+        clear_routing_rules 2>/dev/null
+        return 1
+    fi
```

### 4.2 (C4) Validate the config before it can blackhole you

```diff
 start_xray() {
     if [ ! -s "$DATADIR/config.json" ]; then
         log "refusing to start: config.json missing or empty"
         return 1
     fi
+    # Catches a config that parses as JSON but Xray rejects — which used to
+    # mean the engine exited on launch while the routing rules stayed
+    # applied, and the same broken config was retried on every boot.
+    if ! "$BINDIR/xray" run -test -c "$DATADIR/config.json" >"$XRAY_LOG.test" 2>&1; then
+        log "refusing to start: xray rejected the config"
+        head -n 20 "$XRAY_LOG.test" >> "$SERVICE_LOG"
+        return 1
+    fi
```

### 4.3 (C2) Supervision — **event-driven, zero polling**

This is the one I most want you to look at. Rather than a `while true; sleep N` watchdog (which would cost wakeups forever), each core process gets a supervisor subshell that blocks in `wait`. A blocked `wait` consumes **zero** CPU and zero wakeups until the child actually dies.

```sh
# Restart policy: up to CRASH_MAX restarts inside CRASH_WINDOW seconds.
# Beyond that we stop fighting it and apply the configured failure mode.
CRASH_MAX=5
CRASH_WINDOW=60

supervise_xray() {
    local crashes=0 window_start pid rc
    window_start=$(date +%s)

    while :; do
        "$BINDIR/xray" run -c "$DATADIR/config.json" </dev/null >"$XRAY_LOG" 2>&1 &
        pid=$!
        echo "$pid" > "$PIDFILE"
        mount_proc_with_name "$pid" "xray"
        log "xray started (pid $pid)"

        wait "$pid"; rc=$?          # <-- blocks with no wakeups
        [ -f "$SUPERVISOR_STOP" ] && { log "xray supervisor: stop requested"; return 0; }

        now=$(date +%s)
        [ $((now - window_start)) -gt "$CRASH_WINDOW" ] && { crashes=0; window_start=$now; }
        crashes=$((crashes + 1))
        log "xray exited rc=$rc (crash $crashes/$CRASH_MAX in window)"

        if [ "$crashes" -ge "$CRASH_MAX" ]; then
            log "xray crash-looping; applying failure mode: $(failure_mode)"
            apply_failure_mode
            return 1
        fi
        sleep $((crashes * 2))       # 2,4,6,8s
    done
}
```

`hev-socks5-tunnel` gets the same treatment — today it is started once at boot and, if it dies, the module is dead until reboot with nothing detecting it.

**Failure mode is a user setting, defaulting to today's behaviour.** I need your call on the default:

| `failureMode` | On crash-loop | Consequence |
|---|---|---|
| `blackhole` (**proposed default — matches today**) | Leave rules applied | No traffic leaks, but the device has no internet until the user intervenes |
| `direct` | `clear_routing_rules` | Device works again, but traffic egresses **unproxied** — a leak |

I default to `blackhole` because silently deanonymising someone whose proxy died is worse than a dead network, and it matches current behaviour. The UI will show the crash-looped state explicitly instead of a bare "crashed".

### 4.4 (M4) Restart without tearing down routing

Today `restart` = `stop` (clears every rule) + `sleep 1` + `start`. The WebUI triggers `restart` on node selection, node edit, settings save, and every routing-rule edit — so each is a ~1s window of **unproxied** egress.

New `restart_core` verb: signal the supervisor to cycle xray, leave the rules in place. Traffic queues against the TUN and resumes. No leak window.

```sh
restart_core)  send_cmd_sync "restart_core" ;;   # new
restart)       send_cmd_sync "stop" && sleep 1 && send_cmd_sync "start" ;;  # kept for full re-apply
```

The UI's `applyActiveConfig()` switches to `restart_core`.

---

## Group 5 — Firewall hardening

### 5.1 (M2) The open-router gap

```diff
 forward() {
     local action="$1"
-    $iptables "$action" FORWARD -i "$TUN_NAME" -j ACCEPT
+    # Return traffic only. The old blanket accept sat above Android's
+    # bw_FORWARD/oem_fwd chains and let the proxy server initiate
+    # connections *into* the user's LAN and hotspot clients.
+    $iptables "$action" FORWARD -i "$TUN_NAME" -m conntrack \
+        --ctstate RELATED,ESTABLISHED -j ACCEPT
     $iptables "$action" FORWARD -o "$TUN_NAME" -j ACCEPT
-    $ip6tables "$action" FORWARD -i "$TUN_NAME" -j ACCEPT
+    $ip6tables "$action" FORWARD -i "$TUN_NAME" -m conntrack \
+        --ctstate RELATED,ESTABLISHED -j ACCEPT
     $ip6tables "$action" FORWARD -o "$TUN_NAME" -j ACCEPT
 }
```

Also satisfies Task 3 requirement 4.

### 5.2 (M3) Save and restore sysctls

`enable_forward` and `loosen_rp_filter` currently change `ip_forward` and every interface's `rp_filter` and never put them back — the device keeps forwarding with loose reverse-path checking after Stop and after uninstall.

```sh
sysctl_save() {
    [ -f "$SYSCTL_BAK" ] && return 0        # don't overwrite a good snapshot
    : > "$SYSCTL_BAK"
    for p in /proc/sys/net/ipv4/ip_forward \
             /proc/sys/net/ipv4/conf/all/rp_filter \
             /proc/sys/net/ipv4/conf/default/rp_filter \
             /proc/sys/net/ipv6/conf/all/forwarding \
             /proc/sys/net/ipv6/conf/default/forwarding; do
        [ -r "$p" ] && echo "$p $(cat "$p")" >> "$SYSCTL_BAK"
    done
    for p in /proc/sys/net/ipv4/conf/*/rp_filter; do
        case "$p" in */all/*|*/default/*|*/lo/*) continue ;; esac
        echo "$p $(cat "$p")" >> "$SYSCTL_BAK"
    done
}

sysctl_restore() {
    [ -f "$SYSCTL_BAK" ] || return 0
    while read -r path value; do
        [ -w "$path" ] && echo "$value" > "$path" 2>/dev/null
    done < "$SYSCTL_BAK"
    rm -f "$SYSCTL_BAK"
}
```

`uninstall.sh` already reads this file (landed in group 2); this is the writer.

### 5.3 (M16, L11) Smaller gaps

```diff
     # Hide proxy port from non-system apps
     $iptables -I OUTPUT -p tcp --dport $TUN_PORT -d $TUN_ADDR -m owner --uid-owner 9999-2147483647 -j REJECT --reject-with tcp-reset
+    # The SOCKS inbound has udp:true, so TCP-only concealment was porous.
+    $iptables -I OUTPUT -p udp --dport $TUN_PORT -d $TUN_ADDR -m owner --uid-owner 9999-2147483647 -j REJECT
```

```diff
     $iptables -t mangle -A XRAY_MARK -m mark --mark $FWMARK -j RETURN
+    # Traffic to any address the device itself owns must never be tunnelled.
+    $iptables -t mangle -A XRAY_MARK -m addrtype --dst-type LOCAL -j RETURN
     $iptables -t mangle -A XRAY_MARK -d 127.0.0.0/8 -j RETURN
+    $iptables -t mangle -A XRAY_MARK -d 100.64.0.0/10 -j RETURN   # CGNAT
```

### 5.4 (M10) Masked fwmark — **I recommend deferring this**

`--set-xmark 1` overwrites the whole 32-bit mark, clobbering the netId Android keeps in the low 16 bits. The correct form is `--set-xmark 0x10000/0x10000` with `ip rule add fwmark 0x10000/0x10000`.

**But**: it works today precisely because our rule at pref 1010 intercepts before netd's rules at 10000+, and changing the mark scheme touches every rule in the datapath at once. The payoff is robustness against future ROM changes, not a bug anyone is hitting. I'd rather land 4.x and 5.1–5.3, confirm they're stable on your device, and do M10 as its own isolated change afterwards. **Flagging rather than skipping — tell me if you want it now.**

---

## Group 6 — DNS

### 6.1 (H6) System DNS leak — needs your device to confirm first

I will not guess the resolver UID; it varies by ROM and Android version. The fix ships **disabled**, with the switch documented.

**Run this on your device with the engine running, then tell me the output:**

```bash
su -c 'iptables -t mangle -Z XRAY_MARK'
```

Browse to a few new domains, wait ~30s, then:

```bash
su -c 'iptables -t mangle -L XRAY_MARK -v -n --line-numbers'
```

and, while a lookup is in flight:

```bash
su -c 'ss -tunp state all sport = :53'
```

**What confirms the leak:** the `ss` output shows the socket owned by a process whose UID is **not** 1000, 1052, or ≥9999 — typically `netd`, `dnsresolv` or `iptables-restore` running as uid 0. And the packet counters on the three `--uid-owner ... MARK` rules stay near zero for DNS traffic while your browsing clearly works.

**What refutes it:** the DNS socket belongs to uid 1000 or the app's own uid, in which case DNS is already being marked and H6 should be closed as not-applicable.

Proposed fix once confirmed — a new setting `captureSystemDns` (default **off** until you confirm):

```sh
# Marks resolver DNS so it enters the tunnel. Placed AFTER the
# `--mark $FWMARK RETURN` rule, so xray's own upstream resolution — which
# carries mark 255 — is never caught by it. Without that ordering this
# rule would deadlock the engine against itself.
if setting_is_true captureSystemDns; then
    $iptables -t mangle -A XRAY_MARK -p udp --dport 53 -m owner --uid-owner 0 -j MARK --set-xmark 1
    $iptables -t mangle -A XRAY_MARK -p tcp --dport 53 -m owner --uid-owner 0 -j MARK --set-xmark 1
fi
```

**Verification that the fix worked:** with it on, `iptables -t mangle -L XRAY_MARK -v -n` shows a nonzero packet count on those two rules, and a `tcpdump -i <wlan> port 53` (or the Latency tab's IP check) shows no plaintext DNS leaving on the physical interface.

### 6.2 (M11) Tethered DNS goes to the configured resolver

Today three rules hard-pin tethered clients to `1.1.1.1`, overriding both the client's choice and this module's own DNS settings, and they can't be cleanly removed if the target ever changes. Moving them into a named chain fixes both:

```sh
gateway_dns_apply() {
    local dns; dns="$(query_settings vpnDns)"
    case "$dns" in ''|*[!0-9.]*) dns="1.1.1.1" ;; esac   # v4 literal or fall back

    $iptables -t nat -N MV2R_DNS 2>/dev/null
    $iptables -t nat -F MV2R_DNS
    for net in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16; do
        $iptables -t nat -A MV2R_DNS ! -i "$TUN_NAME" -s "$net" -p udp --dport 53 -j DNAT --to "$dns"
        $iptables -t nat -A MV2R_DNS ! -i "$TUN_NAME" -s "$net" -p tcp --dport 53 -j DNAT --to "$dns"
    done
    $iptables -t nat -C PREROUTING -j MV2R_DNS 2>/dev/null || \
        $iptables -t nat -I PREROUTING 1 -j MV2R_DNS
}
```

Note `-s` (source is a LAN client) rather than the current `-d` (destination is in a private range) — the existing rules also hijack a tethered client pointed at a LAN DNS server such as a Pi-hole. Adding TCP/53 closes the other half of the leak. **DoH cannot be transparently captured; that will be documented, not silently "handled".**

### 6.3 (M17) Stop dropping IPv6 DNS when IPv6 is enabled

Currently both branches unconditionally `DROP` v6 tcp/udp 53, so an IPv6-only or NAT64 network cannot resolve at all.

```diff
     $ip6tables -t mangle -A XRAY_MARK -m mark --mark $FWMARK -j RETURN
-    $ip6tables -t mangle -A XRAY_MARK -p udp --dport 53 -j DROP
-    $ip6tables -t mangle -A XRAY_MARK -p tcp --dport 53 -j DROP
+    # Only forced onto IPv4 when IPv6 is off. With IPv6 on, v6 DNS is
+    # marked into the tunnel like any other v6 traffic.
```
(The `else` branch keeps dropping everything, so the IPv6-disabled behaviour is unchanged.)

---

## Task 3 — LAN gateway sharing

### Design, and what I took from `Kr328/vpn-gateway`

I read the reference. Three things it does that matter here, and one it gets wrong:

1. **Natless routing.** It never uses SNAT/MASQUERADE. It puts `ip rule from <LAN subnet> lookup <tun table>` in front and lets the tunnel's own table carry the packet. **Magic_V2Ray already does exactly this** — prefs 5000/5010/5020/5030/5040/5050/6000 are a verbatim copy of vpn-gateway's `ip_rules_for()`, down to the numbers. So Task 3 is completing and gating existing infrastructure, not building a parallel path. Good.

2. **`inotifyd` on `/data/misc/net`.** vpn-gateway watches rt_tables for writes to detect network changes. I'll use this *in addition to* `ip monitor`, because a hotspot coming up registers a new routing table without necessarily producing the route event we currently watch.

3. **The `ip_forward` bind-mount stub** (its lines 109–113) — this is the important trick. netd resets `net.ipv4.ip_forward` to 0 whenever tethering stops. vpn-gateway sets the real sysctl to 1, then bind-mounts a stub file containing "0" over `/proc/sys/net/ipv4/ip_forward`, chowned and chcon'd to match so netd's writes land in the stub and succeed while the kernel value stays 1. Without this, gateway sharing silently dies the first time the user toggles hotspot off and on. (Magic_V2Ray's runtime dir is literally named `/dev/sysctl_stubs` — the author clearly intended this and never implemented it.)

4. **What it gets wrong**, and I will not copy: `FORWARD -i $tun -j ACCEPT` (the M2 open-router gap) and the same `read_table_index` subshell bug already fixed in group 3.

### Rules the feature owns

Everything goes in named chains and a contiguous pref block so teardown is total:

| Layer | Object |
|---|---|
| `filter FORWARD` | `MV2R_GATEWAY` — `-o tun -s <lan> -j ACCEPT`, `-i tun -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT` |
| `nat PREROUTING` | `MV2R_DNS` — §6.2 |
| `mangle PREROUTING` | `HOTSPOT_PREROUTING` (existing, moved under the toggle) |
| `mangle FORWARD` | `MV2R_MSS` — MSS clamp, moved out of the built-in chain |
| `ip rule` | prefs 5000–5060 (existing block, moved under the toggle) |
| sysctl | `ip_forward=1` + bind-mount stub |

`gateway_stop()` removes all of the above and unmounts the stub; `uninstall.sh` (already landed) removes them too.

### Dynamic interface detection — event-driven

Extends the existing debounced monitor rather than adding a poller:

```sh
# ip monitor already runs for route events; add link events to the same
# stream, and inotifyd on rt_tables for the tethering case that emits
# neither. All three feed one debounced handler.
$ip monitor route link > "$IFACE_EVENT_PIPE" 2>/dev/null &
inotifyd - /data/misc/net:w > "$RT_EVENT_PIPE" 2>/dev/null &
```

On each debounced wake: recompute the tether interface set; if it changed and the gateway is enabled, re-apply. Interfaces are matched by name (`ap*`, `wlan1`, `swlan*`, `rndis*`, `usb*`, `ncm*`) **and** cross-checked against `ip -4 addr show` for a private-range address, so a renamed AP interface still works.

### UI — real state, not optimism

Task 3 requirement 5 asks for verified status. `gateway_status` checks the *actual* kernel state, not the toggle:

```sh
gateway_status() {
    local applied=0
    $iptables -t filter -C FORWARD -j MV2R_GATEWAY 2>/dev/null && applied=$((applied+1))
    $iptables -t nat    -C PREROUTING -j MV2R_DNS  2>/dev/null && applied=$((applied+1))
    $ip rule show | grep -q "^5030:" && applied=$((applied+1))
    [ "$applied" -eq 3 ] && echo "applied" || echo "not-applied:$applied/3"
    # Live clients, cheap: the kernel's neighbour table
    for i in $(tether_interfaces); do
        echo "iface $i $(ip -4 neigh show dev "$i" 2>/dev/null | grep -c REACHABLE)"
    done
}
```

That gives a genuine **applied ✓ / partial ⚠ / not applied ✗** badge plus a per-interface reachable-client count, with no extra daemon.

### IPv6 decision (requirement 6)

**Gateway sharing is IPv4-only, explicitly.** Documented, not accidental:

- When the gateway is **off**: unchanged from today.
- When **on** and module IPv6 is **off**: the existing `HOTSPOT_FORWARD` REJECT chain already blocks forwarded v6 — kept. LAN clients get no v6 rather than a leaking v6 path around the tunnel.
- When **on** and module IPv6 is **on** (§6.3): device traffic uses v6 normally, but forwarded v6 is still REJECTed with `icmp6-no-route` so clients fail fast and fall back to v4 instead of hanging.

Reason: v6 LAN sharing needs prefix delegation or NDP proxying, which is a much larger feature. Silently forwarding v6 outside the tunnel would be exactly the leak requirement 6 warns about. A future `gatewayIPv6` setting can lift this.

---

## Manual test plan

### Before anything

```bash
su -c 'iptables-save -t mangle; iptables-save -t nat; iptables-save -t filter; ip rule show; ip -6 rule show; ip route show table 100' > /sdcard/mv2r_before.txt
```

Recovery is unchanged from audit §0: nothing here survives a reboot; hold Volume Up at boot for module safe mode if anything goes wrong.

### Group 4

1. **C3** — `su -c 'ip link del xraytun0'`, then Start. Expect: refusal in `service.log`, engine not started, **network still works** (previously: blackhole).
2. **C4** — put `{"bad":1}` in `config.json`, Start. Expect: refusal, no rules applied.
3. **C2** — with the engine up, `su -c 'pkill -9 xray'`. Expect: restarts within ~2s, `service.log` shows `crash 1/5`. Then `while true; do pkill -9 xray; sleep 1; done` for ~30s → expect crash-loop detection and the configured failure mode.
4. **M4** — start the engine, open the Latency tab to get a live ping, then save a settings change. Expect: ping blips but does **not** show the unproxied path; `iptables -t mangle -L XRAY_MARK` stays populated throughout.

### Group 5

5. **M2** — with a client on the hotspot, from the proxy side try to reach the client's LAN IP. Expect: blocked. Confirm the rule reads `ctstate RELATED,ESTABLISHED`:
   `su -c 'iptables -L FORWARD -v -n | grep xraytun0'`
6. **M3** — `su -c 'cat /proc/sys/net/ipv4/ip_forward /proc/sys/net/ipv4/conf/all/rp_filter'` before start, after start, after stop. Expect: the after-stop values match the before values.

### Task 3

7. Toggle the gateway **on** with hotspot active. Confirm it is genuinely applied:

```bash
su -c 'iptables -t filter -S MV2R_GATEWAY; iptables -t nat -S MV2R_DNS; ip rule show | sed -n "/^50[0-9][0-9]:/p"'
```

Expect all three non-empty, and the UI badge to read **applied ✓**.

8. From a connected client: `curl https://icanhazip.com` returns the **proxy's** egress IP, not the carrier's.
9. Toggle hotspot off and on again. Expect: rules re-applied automatically (this is the `ip_forward` stub test — without it, forwarding dies here).
10. Toggle the gateway **off**. Expect every one of those objects gone:

```bash
su -c 'iptables -t filter -S | grep -c MV2R; iptables -t nat -S | grep -c MV2R; ip rule show | grep -c "^50[0-9][0-9]:"'
```

Expect `0`, `0`, `0`. Then diff the full state against your baseline:

```bash
su -c 'iptables-save -t mangle; iptables-save -t nat; iptables-save -t filter; ip rule show' > /sdcard/mv2r_after.txt
diff /sdcard/mv2r_before.txt /sdcard/mv2r_after.txt
```

Expect no differences.

---

## Root-solution parity

Nothing proposed here is root-solution specific. Two notes:

- `inotifyd` is a busybox/toybox applet. Present on Magisk and KernelSU busybox; **APatch** uses KernelSU's busybox so it is present there too. The code falls back to `ip monitor` alone if `inotifyd` is missing, which costs only the hotspot-toggle detection case.
- Hotspot state is detected purely from kernel interface state (`ip link` / `ip addr`), not from `dumpsys` or any manager API, so there is no per-root-solution branch. This is deliberately more portable than querying `TetheringManager`.
