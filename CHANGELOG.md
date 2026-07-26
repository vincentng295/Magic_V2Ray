# Changelog

## v1.9 — Security & lifecycle hardening

This release fixes a **remotely-triggerable root compromise** and a set of
silent-failure and battery bugs. Everything in it is process/UI/install
lifecycle — **no change to the live iptables/ip-rule routing datapath**, so it
carries no risk of cutting off connectivity or bootlooping on upgrade. The
routing-layer and LAN-gateway work is staged separately for review (see
*Not in this release*).

> Upgrading is strongly recommended. If you ever imported a subscription from
> a link you do not fully control, treat **C1** below as a reason to update now.

### 🔴 Security

- **Root command injection via subscription content (critical).** The Xray
  config was written to disk with a shell `echo '<json>'`; because JSON does
  not escape the single quote, a crafted field in any imported node or
  subscription (address, path, host, id, password, …) could break out of the
  quoting and execute as **root**. All config/state writes now pass through a
  base64 pipe that cannot contain shell metacharacters.
- **Subscriptions are now fetched with TLS verification ON.** The previous
  `curl -k` let anyone on the network substitute subscription contents — which,
  combined with the injection above, was unauthenticated remote root. Redirects
  and protocols are pinned. Hosts with a self-signed certificate can opt back
  out per subscription via a new, clearly-labelled **"Skip TLS certificate
  check"** switch.
- **Update manifest moved to HTTPS.** `updateJson` was plain `http://`,
  allowing a man-in-the-middle to point the module updater at a hostile build.
- **XSS-to-root in the profile list closed.** Category names and node fields
  are no longer interpolated into HTML event handlers; the list is built with
  DOM APIs and text nodes, so a name containing `"` can no longer inject script
  into a page that holds a root exec bridge.
- **Node-test credentials no longer written to world-readable `/dev`.** Probe
  configs (which contain server credentials) now live in the module's private
  tmpfs at `0600`, and each probe cleans up its temp file and its spawned
  process even if interrupted.

### 🟠 Reliability

- **Crash no longer leaves the module wedged.** A stale process-tracking mount
  used to make status read "crashed" forever after any Xray crash — Start would
  then spawn untracked duplicate processes and Stop would kill nothing. The
  mount is now always reconciled, and Stop has a PID-file fallback.
- **Dynamic reconnect no longer silently dies.** The network-interface monitor
  and the latency monitor shared one PID variable; opening the Latency tab
  overwrote and orphaned the interface monitor, disabling Wi-Fi↔mobile
  re-routing. They are now tracked separately, and the `ip monitor` child is no
  longer leaked on restart.
- **IP Hunter can no longer loop forever.** An unmatchable prefix list used to
  restart the telephony process without limit. It now backs off exponentially
  and gives up after a capped number of attempts.
- **Broken configs are rejected before they can black-hole the device.** An
  unparseable URI, or a URL-safe-base64 vmess link, used to generate an error
  document that was written as the config and started anyway — and retried on
  every boot. Such configs are now caught and refused.
- **`uninstall.sh` now actually runs, and actually cleans up.** It was never
  extracted by the installer, so removing the module left every saved server
  address, UUID, password and WireGuard private key on disk indefinitely. It is
  now installed and performs a full teardown (processes, netfilter chains,
  policy rules, TUN, saved sysctls, data directory).

### 🔋 Battery

- **The latency probe can no longer run forever.** It previously issued a
  network request every second until reboot if the WebUI was closed with the
  monitor on. It is now bounded by a heartbeat from the UI and stops within
  ~15s of the UI going away; the interval was also relaxed from 1s to 2s.
- **Background work stops when the UI is backgrounded or closed** — polling and
  probing are suspended on `visibilitychange`/`pagehide` instead of continuing
  to drive root shells with nobody watching.
- **Network-change handling is debounced.** A single Wi-Fi↔mobile handover
  emits dozens of kernel route events; each one previously forked several
  processes. A burst is now coalesced into a single re-apply (measured: ~35
  events → 2 handler runs).
- **Leaked processes eliminated** — orphaned `ip monitor`, orphaned Xray after
  a crash, and orphaned node-test instances all now have a hard lifetime.

### 🧹 Quality & size

- **Removed the bundled `jq` binary** (~3.9 MiB across both architectures); the
  single settings lookup it served is now done with `sed`.
- Fixed `read_table_index` (ran in a subshell, so its result never reached the
  caller and a duplicate routing-table entry silently broke the mark rule).
- Fixed the "Resolve DNS via Proxy" toggle, which could never render unchecked.
- `proxy_control.sh` no longer sprays shell-trace output on every UI action;
  a dead control loop no longer hangs the WebUI (writes are liveness-checked and
  time-bounded).
- Stopping the engine no longer deletes your selected node; boot-time resume now
  keys off a separate marker, so the manager's action button can stop/start
  without reopening the WebUI.
- New `action.sh` (toggle the engine from the manager button), per-architecture
  build script, and CI that fails on shell-injection regressions, bashisms, and
  incomplete translations.
- All shell scripts pass a strict POSIX (`dash -n`) parse; line endings pinned
  to LF so shebangs don't break on checkout.

### ⚠️ Behaviour change

- **`updateJson` switched to HTTPS.** If you self-host the update manifest over
  plain HTTP, update the URL or the updater will no longer reach it.
- **Stopping the proxy no longer clears `config.json`.** Your selected node
  persists across stop/start. (Previously, Stop discarded it.)

### Not in this release (staged for review)

The following require changes to the live routing rules, so per the project's
own risk policy they are proposed and test-planned but **not yet applied** —
they ship once reviewed and validated on-device:

- Fail-safe datapath: abort-on-missing-TUN, config validation before start,
  process supervision, and restart-without-dropping-rules.
- Firewall hardening: scoping the tunnel-side `FORWARD` accept to established
  traffic (closes an open-router exposure), and saving/restoring `ip_forward`
  and `rp_filter`.
- DNS correctness: optional system-DNS capture (pending on-device UID
  confirmation), configurable tethered-client DNS, and IPv6 DNS through the
  tunnel when IPv6 is enabled.
- **New feature — toggleable LAN gateway sharing**: route tethered/hotspot
  clients through the same tunnel, off by default, event-driven, with a real
  applied/not-applied status.

See `REVIEW-routing-and-gateway.md` for the full design and test plan.
