## Security & lifecycle hardening (v1.9)

Fixes a **remotely-triggerable root compromise** plus a set of silent-failure
and battery bugs. Everything here is process / UI / install lifecycle — **the
live iptables / ip-rule routing datapath is unchanged**, so this branch cannot
cut off connectivity or bootloop on upgrade. Routing-layer and LAN-gateway work
is staged separately for review (`REVIEW-routing-and-gateway.md`) and is **not**
in this PR.

### Highlights

**Security**
- **Root command injection via subscription content (critical).** Config was
  written with a shell `echo '<json>'`; JSON does not escape `'`, so a crafted
  node/subscription field executed as root. All writes now go through a base64
  pipe that cannot carry shell metacharacters.
- Subscriptions fetched with **TLS verification ON** (was `curl -k`), redirects
  and protocols pinned, with an explicit per-subscription opt-out for self-signed
  hosts.
- `updateJson` moved to **HTTPS** (was MITM-able plain HTTP).
- **XSS-to-root** in the profile list closed (DOM APIs + text nodes instead of
  string-built handlers; `escapeAttr` removed).
- Node-test credentials no longer written to world-readable `/dev`.

**Reliability**
- Crash no longer leaves a stale mount that makes status read "crashed" forever,
  spawns duplicate Xray processes, and makes Stop a no-op.
- Interface monitor and latency monitor no longer share a PID — opening the
  Latency tab used to orphan the interface monitor and silently kill dynamic
  reconnect.
- IP Hunter can no longer restart telephony in an unbounded loop.
- Broken / URL-safe-base64 configs are rejected before they can black-hole the
  device (and before they get retried every boot).
- **`uninstall.sh` is now installed and actually cleans up** — it was never
  extracted, so removal previously left every server address, UUID, password and
  WireGuard key on disk.

**Battery**
- Latency probe is bounded by a UI heartbeat (was: one network request per
  second until reboot if the WebUI was closed with it on).
- Background polling/probing suspends on `visibilitychange` / `pagehide`.
- Route-event handling debounced (~35 events → 2 handler runs, measured).
- Orphaned `ip monitor` / Xray / node-test processes eliminated.

**Quality & size**
- Removed bundled `jq` (~3.9 MiB across both arches; replaced with `sed`).
- Fixed `read_table_index` subshell bug, the always-checked "DNS via Proxy"
  toggle, `proxy_control.sh` trace spraying + WebUI hangs.
- New `action.sh`, per-arch build script, and CI (shell-injection regression
  guard, `dash -n`, i18n completeness).
- All scripts pass strict POSIX parse; LF pinned.

### Behaviour changes ⚠️
- `updateJson` is now HTTPS — update any self-hosted manifest URL.
- Stopping the proxy no longer deletes `config.json`; the selected node persists
  across stop/start.

### Testing
Static analysis, strict POSIX (`dash -n`) parse, JS syntax checks, and logic
simulation of the new shell paths (settings parser, event debounce,
`read_table_index`, injection inertness). **Not yet run on a physical rooted
device** — on-device test steps are in the branch's review packet.

Full details in [`CHANGELOG.md`](CHANGELOG.md).
