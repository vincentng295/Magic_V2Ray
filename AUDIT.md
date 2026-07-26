# Magic V2Ray — Current-State Audit Report

**Repo:** `vincentng295/Magic_V2Ray` @ `34f2a73` (v1.8 & Xray-core@v26.7.11, versionCode 135)
**Scope:** entire tree — `customize.sh`, `service.sh`, `proxy_control.sh`, `uninstall.sh`, `module.prop`, `META-INF/`, `webroot/*` (helper.js, main.js, vars.js, i18n.js, index.html, style.css), `bin/` payload layout.
**Status:** read-only audit. **No files were modified.**

---

## 0. Read this first — recovery net

Everything this module changes at runtime (`iptables`, `ip6tables`, `ip rule`, `/proc/sys/net/*`, the tmpfs at `/dev/sysctl_stubs`, the TUN device) is **volatile**. A reboot wipes all of it and lets `netd` rebuild the stock ruleset. So the only genuinely dangerous failure mode is a **bootloop**, not network loss.

| Situation | Recovery |
|---|---|
| Network dead, device boots | `su -c 'sh /data/adb/modules/magic_v2ray/proxy_control.sh stop'` — or just reboot |
| Network dead, control pipe wedged | Reboot. Rules do not survive it |
| Bootloop | Magisk/KernelSU/APatch **safe mode**: hold Volume Up during boot; all modules are disabled for that boot |
| Bootloop, safe mode unavailable | From recovery/adb: `touch /data/adb/modules/magic_v2ray/disable` or `rm -rf /data/adb/modules/magic_v2ray` |

Keep `adb` reachable before flashing any modified build.

---

## 1. Architecture as actually built

```
app socket
  └─ mangle OUTPUT → XRAY_MARK → MARK 1 (uid 1000, 1052, 9999+)
       └─ ip rule fwmark 1 → table 100 → default dev xraytun0
            └─ hev-socks5-tunnel  (user-space #1, lwIP TCP/IP stack)
                 └─ SOCKS5 over loopback 127.17.1.3:808
                      └─ xray  (user-space #2)
                           └─ physical iface (socket mark 255 → ip rule pref 1000 → iface table)
```

Tethered clients enter through `mangle PREROUTING → HOTSPOT_PREROUTING` and `ip rule` prefs 5000–6000 instead, converging on the same table 100.

Process model: `service.sh` forks two long-lived background subshells — a **command loop** reading a FIFO at `/dev/sysctl_stubs/run/control.pipe`, and a **boot sequencer**. `proxy_control.sh` is a thin FIFO writer. Liveness is tracked by bind-mounting `/proc/<pid>` into `/dev/sysctl_stubs/proc/<name>` and testing for `exe` — a genuinely neat trick that survives PID reuse.

### 1.1 README claims vs. code

| README claim | Reality |
|---|---|
| "uses native Linux kernel routing (`iptables` / `ip rule` / **`TPROXY`**)" | **TPROXY appears nowhere in the codebase.** Grep is empty. The datapath is TUN + tun2socks |
| "**Zero-Copy** Context Switching … packets go straight from App → Kernel → Xray" | Packets traverse **two** user-space processes plus a loopback TCP connection per flow. That is strictly *more* copying and context-switching than a single-process `VpnService` app, not less |
| "**No Single-Queue Bottleneck** … splits traffic at the Netfilter/Mangle layer" | `xraytun0` is a single-queue TUN (`IFF_MULTI_QUEUE` is not used). The mangle rules choose *whether* to tunnel, they do not parallelise anything |
| "Optimized Packet Handling … latency slashed by several ms" | The extra lwIP termination + loopback SOCKS handshake **adds** per-connection latency vs. a direct-to-core design |
| "Seamless Dynamic Reconnects" | **Genuinely implemented and event-based** (`ip monitor route`, service.sh:212) — good. But it is silently killed by finding **H2** |
| "Universal Root Support (Magisk/KernelSU/APatch)" | Accurate. No root-solution-specific API is used |
| "Immortal System-Wide Coverage … OS cannot kill it" | Accurate and the real differentiator |

The module's true advantages — LMK immunity, coverage of system UIDs that `VpnService` cannot reach, native tethering, no VPN key icon, no conflict with per-app VPNs — are real and undersold. The performance section is marketing that the code does not support. **Recommendation: rewrite that section rather than defend it** (see P1 for the change that would make the original claim true).

---

## 2. Findings

Severity: **Critical** = root compromise or guaranteed loss of connectivity · **High** = silent failure of a core feature, leak, or resource exhaustion · **Medium** = wrong behaviour, hardening gap · **Low** = quality/portability/size.

### CRITICAL

---

**C1 — Root command injection from subscription content** · `webroot/main.js:142, 1630, 1842, 2109, 2307`

```js
execShell(`echo '${xrayConfig}' > '${CONFIG_JSON}'`, () => { ... });
```

`xrayConfig` is `JSON.stringify()` output built from attacker-controlled subscription fields (`address`, `id`, `path`, `host`, `sni`, `serviceName`, `password`, …). **JSON does not escape single quotes**, so any `'` in those fields terminates the shell quoting. Verified locally:

```
vless://uuid@evil.com:443?type=ws&path=%2F%27%3Bid%3E%2Fdata%2Flocal%2Ftmp%2Fpwn%3B%27

→ echo '  "path": "/';id>/data/local/tmp/pwn;'"' > /data/adb/magic_v2ray/config.json
```

`ksu.exec` runs as **root**. Importing a hostile subscription and pressing Start is arbitrary root code execution. Compounded by **H8** (`curl -k`), a network attacker who can MITM the subscription URL gets root without the user ever visiting a malicious link.

Note the codebase *already* has the correct pattern three lines away — `saveProfiles()` (main.js:74) does `printf '%s' '<base64>' | ...`. Only the config path was missed.

> **Fix direction:** route every shell-bound string through one helper, e.g. `writeFileB64(path, content)` → `printf '%s' '<b64>' | base64 -d > 'path'`. Base64 output is `[A-Za-z0-9+/=]` only and cannot escape quoting. Audit all five call sites plus `escapedUrl` handling.

---

**C2 — No supervision: any core process death = total network blackhole** · `service.sh:548, 667`

`xray` and `hev-socks5-tunnel` are each started with a bare `&`. Nothing restarts them, nothing notices. If either dies while `apply_routing_rules` is in effect:

- default route for all marked traffic is `dev xraytun0`,
- nothing is servicing the tunnel,
- **every app on the device loses internet** until the user notices, opens the WebUI, and toggles the engine.

`hev-socks5-tunnel` is started exactly once per boot (service.sh:667) and is never restarted under any circumstance — if it dies, the module is dead until reboot, and there is no code path that even detects it.

> **Fix direction:** a supervisor loop in the command-loop subshell that re-spawns on death with exponential backoff and a crash cap; on exceeding the cap, `clear_routing_rules` (fail-open to direct) and record the state for the UI. Decide deliberately whether the safe end state is fail-open (usable device) or fail-closed (no leak) and make it a user setting.

---

**C3 — `apply_routing_rules` applies routing even when the TUN never appeared** · `service.sh:277-306`

```sh
while [ $retry -lt $max_retry ]; do
    if $ip link show "$TUN_NAME" >/dev/null 2>&1; then break; fi
    sleep 0.5; retry=$((retry + 1))
done
# ← falls through after 5 s with no check on $retry
$ip addr add 198.18.0.1/15 dev $TUN_NAME
```

After the 5-second budget expires the function continues unconditionally. `ip addr add` / `ip route replace` fail, but the mangle MARK rules and `ip rule fwmark 1 table 100` install successfully. Result: marked packets are steered into an empty or stale table 100 → dropped or routed to a down interface. **Blackhole with no error surfaced.**

> **Fix direction:** `if [ $retry -ge $max_retry ]; then log; return 1; fi`, and have `do_job start` abort the start (and kill the just-spawned xray) when `apply_routing_rules` fails.

---

**C4 — An unparseable URI produces `{"error":…}` as config.json, which is then started** · `webroot/helper.js:727, 731` → `main.js:141-149`

`convert_uri_to_xray_json()` returns `JSON.stringify({error: "..."})` on failure. Only `copyNodeFullConfig()` (main.js:766) checks for `.error`. Every path that actually *starts* the engine writes the error object to `config.json` verbatim and calls `proxy_control.sh restart`. Xray exits immediately on the invalid config → **C2** → blackhole. Also survives reboot: `service.sh:671` sees `config.json` exists and re-starts the doomed engine every boot.

> **Fix direction:** validate before write — reject if `parsed.error`, and have `proxy_control.sh start` verify with `xray run -test -c config.json` before touching any routing rule.

---

### HIGH

---

**H1 — Stale `/proc` bind-mount desyncs status, orphans xray, breaks Stop** · `service.sh:499-507, 543-568`

```sh
mount_proc_with_name() {
    if [ -d "/proc/$PID" ] && [ ! -e "$STUB_DIR/proc/$NAME" ]; then   # ← guard
```

When xray crashes, the bind-mounted directory `$STUB_DIR/proc/xray` **remains** (empty, which is exactly how `is_proc_running` detects death). On the next Start, the `! -e` guard sees that leftover directory and **skips the mount**. From then on:

- `is_proc_running "xray"` is permanently false → UI shows `crashed` while xray is running,
- Start spawns *another* xray each time it is pressed,
- Stop takes the `is_proc_running` false branch and **never kills anything** — it just clears the routing rules and unmounts, leaving an untracked root xray process running with the user's credentials in memory.

> **Fix direction:** unconditionally `umount -l` + `rm -rf` the mountpoint before mounting; make Stop kill by the PID file as a fallback path, and reconcile PID-file state on boot.

---

**H2 — `MONITOR_PID` is shared by two different monitors; the interface monitor is lost** · `service.sh:52, 569-605`

One variable tracks both `monitor_net_interfaces` and `monitor_network_latency`. Sequence on any device where the user opens the Latency tab once:

1. boot → `start_monitor` → `MONITOR_PID` = interface monitor.
2. user enables latency → `start_monitor_latency` overwrites `MONITOR_PID` with the latency PID.
3. **The interface monitor's PID is now unrecoverable.** It cannot be stopped or restarted, and `stop_monitor` would kill the wrong process.

Additionally `$ip monitor route | while read` puts the loop in a pipeline subshell — `kill -9 $MONITOR_PID` kills the function's shell but leaves the `ip monitor` child alive until it takes a SIGPIPE. Each cycle leaks a process.

Net effect: the README's headline **"Seamless Dynamic Reconnects" feature silently stops working** after the user visits the Latency tab, and Wi-Fi↔mobile switches stop re-applying the `fwmark 255` rule.

> **Fix direction:** separate `IFACE_MONITOR_PID` / `LATENCY_MONITOR_PID`; restructure to `while read; do … done < <(...)` equivalent or track the child PID explicitly; kill the process group (`kill -9 -$PID` with `set -m`).

---

**H3 — IP Hunter can loop forever killing the telephony process** · `service.sh:176-200, 202-227`

```sh
echo "IP $CURRENT_IP does not match the expected list, continue..."
network_reset &
```

`check_ip_hunter` is called from the route-event monitor. If the carrier never hands out an address matching the user's prefix list, every reconnect fires another `kill -9 com.android.phone`, which forces another reconnect, which fires again. No attempt counter, no backoff, no give-up. On a mismatched prefix list this is an **unbounded radio-restart loop** — severe battery drain, no mobile data, and repeated telephony crashes.

> **Fix direction:** attempt counter with exponential backoff (e.g. 3 s → 60 s), hard cap (~10 attempts), then disable the hunter and report the state to the UI.

---

**H4 — `uninstall.sh` is never installed, so it never runs** · `customize.sh:26-32`

`SKIPUNZIP=1` means *nothing* is extracted except what `customize.sh` extracts explicitly. The list covers `webroot/*`, `proxy_control.sh`, `service.sh`, `tunnel.yml` (which **does not exist in the repo** — the unzip prints `caution: filename not matched` on every install), the `.dat` files and `module.prop`. **`uninstall.sh` is not in the list.** It never lands in `$MODPATH`, so Magisk/KernelSU/APatch never execute it on removal.

Consequence: `/data/adb/magic_v2ray` — containing `profiles.base64`, `settings.base64` and `config.json` with **every server address, UUID, password and WireGuard private key the user ever imported** — persists indefinitely after the module is uninstalled.

Separately, the file's one line is insufficient even if it did run: it does not stop processes, clear routing, unmount `/dev/sysctl_stubs`, or restore `ip_forward` / `rp_filter`.

> **Fix direction:** add `uninstall.sh` to the extraction list; expand it into a full teardown that is safe to run twice and safe to run when nothing is applied.

---

**H5 — Latency monitor never stops when the WebUI is closed** · `service.sh:229-239`, `webroot/main.js:2928-2939, 1968-1973`

The backend loop runs `curl` **once per second, forever**, gated only on the existence of `$TIME_RES_FILE`. The UI clears `_latencyPollTimer` when you switch tabs (main.js:1972) but **never sends `stop_monitor_latency`**. Closing the WebUI (or the manager app) with the toggle on leaves a root process spawning a TLS connection every second indefinitely.

This directly contradicts the README's "No Battery Drain" claim, and is the single largest avoidable power cost in the module.

> **Fix direction:** heartbeat file touched by the UI, with the backend exiting after ~15 s of no heartbeat; increase the default probe interval to 2–5 s; back off to 30 s when the screen is off (`dumpsys deviceidle` or `/sys/power/state` is unreliable — prefer the heartbeat).

---

**H6 — System DNS bypasses the tunnel (DNS leak)** · `service.sh:321-323`

```sh
-m owner --uid-owner 1000   -j MARK --set-xmark 1
-m owner --uid-owner 1052   -j MARK --set-xmark 1
-m owner --uid-owner 9999-2147483647 -j MARK --set-xmark 1
```

Only `AID_SYSTEM`, `AID_DNS_TETHER` and the app UID range are marked. On modern Android, app DNS queries are not emitted by the app — they are proxied by `netd`/`resolv`, which runs as **uid 0 (root)**. Root is deliberately unmarked (so xray's own egress doesn't loop), so those queries leave over the physical interface in cleartext to the carrier's resolver.

Practical effect: connections still tunnel (apps get real IPs and connect through the proxy), but **the ISP sees every domain resolved**, and DNS-poisoning censorship is not bypassed at all. The UI's "Resolve DNS via Proxy" / "Local DNS" settings only affect queries that reach Xray's SOCKS inbound, which system DNS never does — so those toggles are largely inert for system-wide DNS.

Tethered clients are handled (DNAT to 1.1.1.1 at service.sh:331-333); the device's own DNS is not.

⚠️ **Needs on-device confirmation before fixing** — the resolver UID varies by ROM/Android version. Verification commands are in §4.

> **Fix direction:** after confirming the UID, add an explicit mangle rule marking udp/tcp dport 53 from the resolver UID (relying on the existing `--mark 255 RETURN` at service.sh:313 to keep xray's own egress out), or REDIRECT port 53 to a local `dokodemo-door` DNS inbound. Must be verified not to loop xray's own upstream resolution.

---

**H7 — Update manifest served over plain HTTP** · `module.prop:7`

```
updateJson=http://vincentng295.github.io/magic-v2ray-site/update.json
```

The root manager fetches this to discover new versions and the download URL. Over HTTP, any on-path attacker rewrites `zipUrl` to point at a hostile module — which installs with full root. GitHub Pages serves HTTPS on the same host; this is a one-character fix.

> **Fix direction:** `https://`. Consider also publishing a checksum in the JSON and documenting it.

---

**H8 — Subscription fetched with TLS verification disabled** · `webroot/main.js:225`

```js
execShell(`${MODDIR}/bin/curl ${extraArgs} -sLk --max-time 15 '${escapedUrl}'`, ...)
```

`-k` disables certificate validation, and `-L` follows redirects with that setting intact. Any on-path attacker can substitute subscription content. On its own that is a node-substitution attack; **combined with C1 it is unauthenticated remote root.**

> **Fix direction:** drop `-k`. If self-signed subscription hosts must be supported, make it a per-subscription opt-in flag shown clearly in the UI, and cap redirects with `--max-redirs 3 --proto '=https'`.

---

### MEDIUM

---

**M1 — `read_table_index` is broken three ways** · `service.sh:96-108`

```sh
cat /data/misc/net/rt_tables | while read -r index name; do
    if [[ "$name" = "$iface" ]]; then
        echo $index
        error=0          # ← subshell; never reaches the caller
    fi
done
return $error            # ← always 1
```

(a) The pipeline puts the loop in a subshell, so `error=0` is discarded and the function always returns 1 — the caller happens to ignore it, so this is silent dead logic. (b) No `break`: duplicate `rt_tables` entries (they do occur after interface churn) emit two indices, so `ip rule add … table "23 45"` fails and the fwmark rule is silently not installed. (c) `[[ ]]` is a bashism — fine under Magisk/KernelSU busybox `ash`, undefined under a strict POSIX `sh`.

> **Fix direction:** `while read -r index name < /data/misc/net/rt_tables` style redirect (no pipe), `break` on first match, `[ ]` instead of `[[ ]]`, and actually honour the return value at the call site (service.sh:133-135).

---

**M2 — Blanket `FORWARD … -j ACCEPT` turns the device into an open router from the tunnel side** · `service.sh:245-251, 328`

```sh
$iptables "$action" FORWARD -i "$TUN_NAME" -j ACCEPT
$iptables "$action" FORWARD -o "$TUN_NAME" -j ACCEPT
```

Inserted with `-I` at the **top** of `filter FORWARD`, above Android's `bw_FORWARD` / `oem_fwd` / tethering chains. `-i xraytun0 -j ACCEPT` accepts **unsolicited** traffic arriving from the tunnel toward the LAN and hotspot clients, bypassing Android's firewall entirely — and `enable_forward` (service.sh:79) has already set `ip_forward=1`. A hostile or compromised proxy server can reach the user's LAN.

> **Fix direction:** replace the ingress rule with `-i xraytun0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT`; keep the egress rule as-is. Gate the whole tethering block behind an opt-in "Hotspot sharing" setting (**breaking change for hotspot users — must default to current behaviour** and be flagged in release notes).

---

**M3 — `ip_forward` and `rp_filter` are changed globally and never restored** · `service.sh:79-88, 266-275`

`enable_forward` sets `net.ipv4.ip_forward=1`; `loosen_rp_filter` sets `rp_filter=2` on `all`, `default`, and every existing interface. Neither is reverted by `clear_routing_rules`, by Stop, or by uninstall. The device keeps forwarding and keeps loose reverse-path checking until reboot, whether or not the proxy is running. (`loosen_rp_filter`'s comment explaining the Samsung One UI `max(all, iface)` behaviour is excellent — the problem is only that it is one-way.)

> **Fix direction:** snapshot the original values into `$STUB_DIR/run/sysctl.bak` on apply, restore on clear/uninstall.

---

**M4 — Rules are torn down for ~1 s on every settings change** · `proxy_control.sh:75`

```sh
restart) stop_proxy; sleep 1; start_proxy ;;
```

`stop_proxy` runs `clear_routing_rules`, so during that window all traffic egresses **directly, unproxied**. The WebUI triggers `restart` on: node selection, node edit, settings save, routing-rule add/edit/delete/toggle, and routing-preset import. For a user changing several routing rules, that is repeated deanonymisation windows.

> **Fix direction:** restart xray *without* clearing routing rules (the rules are independent of the xray process); traffic queues against the TUN and resumes when xray is back. Optionally add a temporary `DROP` fallback for the gap.

---

**M5 — Unproxied window on every boot** · `service.sh:630-675`

The engine only starts after `sys.boot_completed=1` **plus** a hard `sleep 5`. Everything transmitted before that — connectivity checks, push reconnects, sync adapters, OEM telemetry — goes direct. There is no boot-time kill switch.

> **Fix direction:** offer an opt-in "block traffic until the engine is up" mode that installs a DROP/REJECT ruleset in `post-fs-data.sh` and swaps it for the real rules on start. Must fail open on a start error or it becomes a soft brick.

---

**M6 — XSS in the profile list via category name → root** · `webroot/main.js:629-637, 670-671, 1951-1953`

```js
function escapeAttr(str) { return String(str).replace(/'/g, "\\'"); }
...
onclick="reloadCategory('${escapeAttr(category)}'); closeAllMenus();"
```

`escapeAttr` handles only `'`, but the value is interpolated into a **double-quoted** HTML attribute. A category name containing `"` closes the attribute and injects markup/JS into a page that has `ksu.exec` (root) available. Category names come from the subscription hostname or from free-text user input in the Edit Subscription modal, so it is mostly self-inflicted — but the escalation from XSS to root makes it worth fixing properly.

> **Fix direction:** stop building handlers as HTML strings. Use `dataset` attributes + `addEventListener`, exactly as `renderRoutingRules()` (main.js:2148-2199) already does correctly.

---

**M7 — Node test writes full credentials into `/dev`** · `webroot/main.js:2463, 2494-2503, 2537-2546`

```js
const tmpFile = `/dev/tmp_config_${node.id}.json`;
```

The HTTP/IP check writes a complete Xray config — server address, UUID/password, keys — to `/dev` at default umask (0644) with tmpfs/`device` SELinux context, then removes it. `/dev` is world-traversable. Also, if `ksu.exec` is interrupted or the UI is closed mid-check, both the file **and** the spawned `xray` process leak (up to 10 concurrent at `CONCURRENCY_LIMIT = 10`).

> **Fix direction:** write to `$STUB_DIR/run/` with `umask 077`, `trap` cleanup in the generated script, and a `timeout` wrapper around the whole probe.

---

**M8 — `echo` mangles backslashes in the config JSON** · `webroot/main.js:142, 1630, 1842, 2109, 2307`

`ksu.exec` dispatches through the system shell (`mksh` on Android), whose `echo` builtin interprets backslash escapes by default. Any config string containing `\` or an escaped `"` — a WebSocket path with a quote, a custom `xhttpExtra` blob, a routing rule with a regex — is silently corrupted before Xray parses it. The base64 fix for **C1** resolves this at the same time.

---

**M9 — Two divergent base64 decoders give inconsistent vmess results** · `webroot/helper.js:2-11 vs 204-210`

`decodeBase64()` normalises URL-safe alphabet and pads. The private `b64decode()` inside `convert_uri_to_xray_json()` does neither. `parseProxyUri()` (main.js) uses the tolerant one, `convert_uri_to_xray_json()` uses the strict one. A vmess URI with URL-safe base64 therefore **lists correctly in the UI but yields `{"error": …}` when started** → **C4** → blackhole. Confusing to diagnose because the node "looks fine".

> **Fix direction:** delete `b64decode`, use `decodeBase64` everywhere.

---

**M10 — `--set-xmark 1` clobbers Android's entire fwmark** · `service.sh:321-323, 341-342, 387-389, 401`

Android encodes the netId in the low 16 bits of the fwmark and uses it throughout `netd`'s rule set. `--set-xmark 1` (no mask) overwrites the whole 32-bit mark. It works here because `ip rule fwmark 1 table 100 priority 1010` intercepts before netd's rules at 10000+, but it is fragile against ROM changes and makes per-app VPN / multi-network interaction unpredictable.

> **Fix direction:** use a dedicated bit — `-j MARK --set-xmark 0x10000/0x10000` with `ip rule add fwmark 0x10000/0x10000` — preserving netId. **Touches core routing; needs careful staged testing.**

---

**M11 — Tethered DNS is hard-pinned to 1.1.1.1, ignoring user settings** · `service.sh:331-333`

Three `nat PREROUTING … --dport 53 -j DNAT --to 1.1.1.1` rules force every tethered client's DNS to Cloudflare, overriding both the client's own choice (e.g. a LAN Pi-hole) and this module's own "VPN DNS / Foreign DNS / Domestic DNS" settings. TCP/53, DoT (853) and DoH are not intercepted at all, so a client configured for DoH bypasses the interception entirely and a client using TCP/53 leaks.

> **Fix direction:** DNAT to the configured DNS instead of a literal; add the TCP/53 counterpart; document that DoH cannot be transparently captured.

---

**M12 — "Resolve DNS via Proxy" can never render as unchecked** · `webroot/main.js:2040`

```js
document.getElementById('set-dnsviaproxy').checked = advSettings.dnsViaProxy || true;
```

`x || true` is unconditionally `true`. The user can uncheck and save (the value *is* persisted at main.js:2073), but on every reload the box reappears checked — so the setting looks broken and the user cannot tell what state they are in.

> **Fix direction:** `advSettings.dnsViaProxy !== false`.

---

**M13 — `set -x` redirect is a no-op; xtrace pollutes every UI call** · `proxy_control.sh:9`

```sh
set -x >"$DATADIR/proxy_control.log" 2>&1
```

The redirection applies to the `set` builtin itself (which prints nothing) and is then discarded. `set -x` stays enabled for the whole script, sending trace to **stderr**, which `ksu.exec` captures on every single UI action. Note the naive fix (`exec > log`) would **break the UI**, because `get_status` echoes `running`/`stopped` on stdout and the WebUI parses it.

> **Fix direction:** `exec 2>>"$DATADIR/proxy_control.log"` and enable `set -x` only when `module.prop`'s `debug=1`.

---

**M14 — A blocking FIFO write can hang the WebUI forever** · `proxy_control.sh:38-39, 46-47, 53-70`

`echo "start" > "$PIPE_FILE"` blocks until a reader opens the FIFO. If the command loop has died (or `/dev/sysctl_stubs` was never mounted — service.sh:10 has no error handling), the write blocks indefinitely. `ksu.exec` has no timeout, so the WebUI sits on the loading overlay forever with no way out but killing the manager app.

The `echo "wait" > "$PIPE_FILE"` idiom used as a completion barrier is clever, but doubles the exposure.

> **Fix direction:** wrap writes in `timeout 10` (toybox provides it) and report a clear error; add a liveness check on the command loop before writing.

---

**M15 — The MTU setting doesn't do what the UI says** · `webroot/index.html:160-165`, `service.sh:653`, `helper.js:607`

The "Network Interfaces → MTU (1350)" field is applied **only** to WireGuard outbounds. The tunnel MTU is hardcoded `8500` in the generated `tunnel.yml`, and the MSS clamps are hardcoded 1350/1330. A user lowering MTU to fix path-MTU problems changes nothing for non-WireGuard nodes.

> **Fix direction:** either wire the setting to `tunnel.yml` + the TCPMSS clamps, or relabel it "WireGuard MTU".

---

**M16 — SOCKS port is hidden from apps over TCP only** · `service.sh:363`

The `REJECT` rule covers `-p tcp --dport 808`. The inbound has `"udp": true` (helper.js:828), so any app can still use the proxy's UDP associate path. Minor, but it defeats the stated intent of the rule.

---

**M17 — IPv6 DNS is dropped even when IPv6 is enabled** · `service.sh:381-382, 396-397`

`XRAY_MARK` and `HOTSPOT_PREROUTING` unconditionally `DROP` tcp/udp dport 53 over IPv6, in **both** branches. Deliberate (commit `e269070 "Drop IPv6 DNS"`) but it silently breaks resolution on IPv6-only / NAT64 networks, where there is no IPv4 resolver to fall back to.

> **Fix direction:** keep the drop only when IPv6 is disabled; when enabled, route v6 DNS through the tunnel like v4.

---

**M18 — Install-time hygiene** · `customize.sh:29, 38-42`

- `unzip … "tunnel.yml"` extracts a file **that does not exist in the repo** — every install prints an unzip warning. Dead line.
- `if [ ! -d "$DATADIR" ]; then rm -rf "$DATADIR"; mkdir -p "$DATADIR"; fi` — works, but the inverted-looking condition invites future breakage.
- The data directory is created at default umask (0755) with no explicit mode, and config files land 0644. `/data/adb` is 0700 root-owned so this is not currently exploitable — but files holding private keys should be 0600 under a 0700 directory as defence in depth.
- No integrity/arch sanity check after extraction (e.g. that `bin/xray` is present and executable).

---

### LOW

| # | Finding | Location |
|---|---|---|
| L1 | Dead code: `get_status()`, `$PIDFILE` (written, only read by the dead function), `TUN2SOCKS_PID`, the entire `stop_monitor` command (never sent by anything) | `service.sh:66-77, 50, 578-586` |
| L2 | `xrayConfig = _resolveXrayConfig(...)` assigns to an **implicit global** and the value is never used | `main.js:588` |
| L3 | `switchTab()` relies on the deprecated global `event` — breaks under strict mode | `main.js:1960` |
| L4 | Portability: `uninstall.sh` has no shebang; `==` inside `[ ]` (service.sh:81, 213, 370); `ipv6_enabled` not declared `local` (service.sh:287); several unquoted expansions | multiple |
| L5 | `network_reset` matches `com.android.phone` as a **substring** of cmdline | `service.sh:156` |
| L6 | `jq` (1.8 MB arm64 / 2.3 MB x86_64) is shipped to answer exactly one query, `.enableIPv6` | `service.sh:24` |
| L7 | The release zip carries **both** architectures (~123 MB) and installs one. Per-arch zips would cut the download ~60% | `bin/` |
| L8 | No `action.sh` — the manager's one-tap action button is unavailable on all three root solutions | — |
| L9 | `loadState` is defined then monkey-patched later in the same file; works only because of statement ordering | `main.js:1985-2000` |
| L10 | Test inbounds collide beyond 250 nodes (`127.17.1.${4 + index % 250}`) | `main.js:2461` |
| L11 | `XRAY_MARK` has no `-m addrtype --dst-type LOCAL -j RETURN` and no CGNAT `100.64.0.0/10` bypass | `service.sh:312-320` |
| L12 | `chmod 666 /dev/net/tun` is broader than needed | `service.sh:645` |
| L13 | No shellcheck config, no CI, no lint on the JS | repo root |

---

### PERFORMANCE

**P1 — The real optimisation: eliminate the tun2socks hop (TPROXY mode)**

The datapath's dominant cost is not iptables rule count — it is that every flow is terminated by lwIP inside `hev-socks5-tunnel`, re-originated as a loopback TCP connection, and handed to Xray over SOCKS5. That is one extra user-space process, one extra full TCP stack, and one extra socket pair per connection.

A `TPROXY` design — `mangle PREROUTING/OUTPUT → TPROXY --on-port <n>` into an Xray `dokodemo-door` inbound with `followRedirect: true` — deletes `hev-socks5-tunnel`, the TUN device, and the loopback hop entirely. It also makes the README's existing claims true. Trade-offs: needs `xt_TPROXY` and `IP_TRANSPARENT` in the kernel (present on most but **not all** Android kernels), and UDP TPROXY support is patchier. This must ship as an **opt-in mode with automatic fallback** to the current TUN path, never as a silent default.

**P2 — Route-event storm causes needless process spawning** · `service.sh:212-226`

Each line from `ip monitor route` triggers `get_active_interface()`, which forks `ip` + `grep` + `awk`. A mobile handover or a busy Wi-Fi network emits dozens of route events per second — dozens of process spawns for a state that changed once. The event-based design is right (this is already better than polling); it just needs a debounce (coalesce events over ~500 ms) and cheaper parsing (shell `case` instead of `grep|awk`).

**P3 — iptables rule count is not the bottleneck**

For completeness, since the brief asks: `XRAY_MARK` is 12 rules, ordered correctly with the cheap `--mark 255 RETURN` first. `ipset` would save a handful of comparisons on the private-range RETURNs, but requires `ip_set` kernel modules that many Android kernels **do not build**, plus a new ~200 KB binary. **Not worth it** — recommend against. There is no double-NAT (only the three DNS DNAT rules exist, and they are `PREROUTING`-only). The one real cleanup is collapsing the three per-subnet DNAT rules and the three LAN-bypass `ip rule`s.

---

## 3. Proposed change plan

Ordered by risk-adjusted value. Groups 1–3 touch no routing logic and are safe to land immediately. Groups 4+ touch the datapath and, per your instruction, I will stop for confirmation before each.

| Group | Contents | Risk | Routing? |
|---|---|---|---|
| **1 — Security hotfix** | C1 (base64 write helper, all 5 sites), M8, H7 (`https`), H8 (drop `-k`), M6 (event listeners), M7 (`/dev` → stub dir + 0600), M9 (single base64 decoder) | Low | No |
| **2 — Install / uninstall correctness** | H4 (extract `uninstall.sh` + full teardown), M18 (drop dead `tunnel.yml` line, 0700/0600 perms, post-extract sanity check), L4 (shebangs) | Low | No |
| **3 — Process lifecycle & UI truthfulness** | H1 (stale mount), H2 (split monitor PIDs), H5 (latency heartbeat + auto-stop), H3 (IP hunter backoff cap), M12, M13, M14 (`timeout` on FIFO writes), L1–L3, L9 | Medium | No |
| **4 — Fail-safe datapath** ⚠️ | C3 (abort when TUN absent), C4 (`xray -test` before start), C2 (supervisor + backoff + fail-open policy), M4 (restart without tearing down rules) | **High** | **Yes** |
| **5 — Firewall hardening** ⚠️ | M2 (conntrack-scoped FORWARD), M3 (sysctl save/restore), M16, L11, M1 (`read_table_index`) | **High** | **Yes** |
| **6 — DNS correctness** ⚠️ | H6 (system DNS leak — *after* on-device UID confirmation), M11, M17 | **High** | **Yes** |
| **7 — Efficiency & size** | P2 (debounce), L6 (drop `jq`, ~4 MB), L7 (per-arch zips), M15 (MTU wiring) | Medium | Partly |
| **8 — Quality & tooling** | shellcheck-clean pass, function/variable naming, comments on the mangle & `ip rule` blocks, `action.sh`, shellcheck CI, README rewrite (§1.1) + changelog | Low | No |
| **9 — Optional TPROXY mode** ⚠️ | P1, behind a setting with automatic fallback | **Very high** | **Yes** |

**Recommendation: start with Groups 1 → 2 → 3.** They remove a root RCE and all the silent-failure bugs, need no routing changes, and are individually revertible. Group 4 is the highest-value routing work and I would do it next, one change at a time.

**Breaking-change candidates** (I will not make these silently): M2's opt-in hotspot gate, M11's DNS retarget, M17's IPv6 DNS behaviour, M4's restart semantics, and P1. Each will be flagged explicitly in the changelog if you approve it.

**No new binaries are proposed.** Group 7 *removes* `jq` (~4 MB across both arches). `curl` stays — SOCKS5 + TLS from shell has no cheaper substitute. `ipset` is explicitly rejected (P3).

---

## 4. Verification commands (run on your device, before any changes)

Baseline capture — save this output so post-change diffs are meaningful:

```bash
su -c 'iptables-save -t mangle; iptables-save -t nat; iptables-save -t filter; ip rule show; ip -6 rule show; ip route show table 100' > /sdcard/mv2r_before.txt
```

Confirm **H6** (which UID emits DNS on your ROM):

```bash
su -c 'iptables -t mangle -Z XRAY_MARK; sleep 30; iptables -t mangle -L XRAY_MARK -v -n --line-numbers'
```

then, while resolving a fresh domain:

```bash
su -c 'ss -tunp state all sport = :53'
```

Confirm **H2** (interface monitor still alive after visiting the Latency tab):

```bash
su -c 'ls -la /dev/sysctl_stubs/proc/; ps -A | grep -E "ip monitor|xray|hev-socks5"'
```

Confirm **H1** (stale mount after a crash):

```bash
su -c 'pkill -9 xray; sleep 2; ls /dev/sysctl_stubs/proc/xray/; sh /data/adb/modules/magic_v2ray/proxy_control.sh status'
```

Confirm **H4**:

```bash
su -c 'ls -la /data/adb/modules/magic_v2ray/uninstall.sh'   # expect: No such file
```

---

*Audit performed against commit `34f2a73`. No files in the repository were modified.*
