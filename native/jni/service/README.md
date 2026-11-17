# magic_v2ray service (C port of `service.sh`)

One static-ish binary replaces `service.sh` and every external tool it called
(`ip`, `iptables`, `ip6tables`, `busybox`/`toybox`: `sed grep tr base64 stat mount umount
mkfifo date kill inotifyd getprop sleep`, and `curl`), and the control front-end `proxy_control.sh`
(now the `proxy_control` applet).
Only your own helper `bin/xhuskydg_helper` is still used (the `logservice` verbs).

## Layout
| file | replaces in service.sh |
|---|---|
| `src/ipt.c`, `ipt.h` | `iptables` / `ip6tables`: talks to the kernel via `IPT_SO_GET_INFO/GET_ENTRIES/SET_REPLACE`; edits a table in memory and swaps it in with **one** replace (atomic per table, retries on `EAGAIN`) |
| `src/netlink.c` | every `ip rule/route/addr/link`, `ip monitor`, `ip rule show \| grep` |
| `src/routing.c` | section 7: `apply_routing_rules`, `clear_routing_rules`, tun config, blackhole, hold/release |
| `src/iface.c`, `ula.c`, `iphunt.c` | active/upstream interface, fwmark rule, IPv6 ULA decoy, IP hunter |
| `src/exclude.c` | Exclude Apps + apps monitor (inotify instead of `inotifyd`); the monitor always runs and tracks the app uid that may reach the proxy inbound |
| `src/latency.c` | latency monitor (built-in SOCKS5 client instead of `curl`) |
| `src/traffic.c` | Traffic Monitor tab: per-source-IP counters of what enters `xraytun0` (see below) |
| `src/xray.c` | start/stop/reload, native `openxtun` (open tun, `TUNSETIFF`, `XRAY_TUN_FD`, exec) |
| `src/control.c`, `main.c` | FIFO command loop (queue + request/reply), boot sequencer |
| `proxy_control.cpp` | `proxy_control.sh`: client side of the control pipe |
| `src/settings.c`, `proc_track.c`, `log.c`, `util.c`, `worker.c` | settings.base64 reader, child pid tracking + reaping, logging, threads |

## Runtime directory
`/mnt/runtime/magicv2ray` (`MV_STUB_DIR`): a plain directory, **no tmpfs mount of its own** (`/mnt` already is one, so
it is gone at reboot). Every service start recreates it empty; `stop_svc` removes it. Layout: `run/` (control pipe, pid
files, `time_res`, `addr_info`, `override/`, ...) and `logs/` (log pipes). Builds before this one kept it on a private
tmpfs at `/dev/sysctl_stubs` (`MV_LEGACY_STUB_DIR`); that location is only looked at to stop / clean up an old service.

## Process tracking
No `/proc/<pid>` bind mounts any more. The service forks xray itself, so it remembers the pid (`proc_track.c`) and starts a
reaper thread that `waitpid()`s it: a child that dies - our SIGKILL, the OOM killer, a crash - never stays a zombie, and
`status` is exact (`running` / `crashed` = it exited while still tracked / `stopped`). A deliberate stop untracks first, so
it is never reported as a crash, and a dead pid is never signalled again (it may have been reused). The pidfile
(`run/xray.pid`) is only used to find an xray of a *previous* service, and only if `/proc/<pid>/exe` really is our binary.
Monitor threads need no tracking: the service owns them.

## Control pipe protocol
`run/control.pipe` takes one line per command:
* plain `<verb>` - fire and forget (boot sequencer, older callers, `service --stop` writes `stop_svc`).
* `REQ <reply-fifo> <verb>` - what `xhuskydg_helper proxy_control <verb>` sends, the same scheme as `logservice read`: the
  client creates `<pipe>.r<pid>`, the service answers `<exit code>\n` then lines `o <stdout>` / `e <stderr>` and closes it.

`status` and `latency_heartbeat` are answered by the reader thread at once; every other verb goes through a queue to one
executor thread, so long jobs (`start`, `restart`) run in order and `status` never waits behind them.
Verbs: `start stop restart reload status reapply start_monitor stop_monitor start_monitor_latency stop_monitor_latency
latency_heartbeat reset_mobile_network traffic_poll traffic_readonly traffic_reset traffic_stop`. Contract with the WebUI: `status` prints one word (`running` 0 / `stopped` 1 /
`crashed` 2); with no service it prints `stopped`. Diagnostics go to `$DATADIR/proxy_control.log`, never to stdout.

## Stopping and live upgrade
`xhuskydg_helper service --stop` writes `stop_svc` into `run/control.pipe` and waits for the service to exit. It does so
for the current runtime dir **and** the legacy `/dev/sysctl_stubs`, so it stops whichever version of the service is
running. On `stop_svc` the service stops its monitors, kills xray, removes the iptables chains / ip rules / ULA / fwmark
rule / traffic hold, stops the log daemon and the subscription loop, deletes the runtime directory (and detaches / removes
what an old version left at the legacy path, unless an old service is still alive there), then exits. The "xray was running" flag is kept,
so a freshly started service resumes xray by itself. The app uses this for updates from versionCode 176 on
(no reboot); older installs get the new module staged in `/data/adb/modules_update/magic_v2ray`, plus
`touch /data/adb/modules/magic_v2ray/update`, and need a reboot.

## Build
```
ANDROID_NDK_HOME=/path/to/ndk ./build_android.sh arm64-v8a   # -> build/arm64-v8a/magic_v2ray_service
```
Install it as `<module>/bin/magic_v2ray_service` and use `dist/service.sh` as the module's
`service.sh` (Magisk/KernelSU only execute a file with that name; it just `exec`s the binary).
Run `magic_v2ray_service -f` to stay in the foreground.

## Tests
```
gcc -Wno-override-init -fsanitize=address,undefined -o test_ipt tests/test_ipt.c src/ipt.c -lpthread && ./test_ipt
gcc -Wno-override-init -o live_ipt tests/live_ipt.c src/ipt.c -lpthread && unshare -n ./live_ipt   # real kernel, root
```

## Requirements / caveats
* Kernel must expose the **legacy** xtables sockopt interface (`ip_tables`, `ip6_tables`), which
  Android's netd uses. nft-only kernels are not supported.
* `MV_XTABLES_LOCK` (default `/system/etc/xtables.lock`) is flock'ed around table edits to
  serialise with netd; check the path on your ROM (skipped if the file is absent).
* Latency probe is now plain HTTP to `gstatic.com/generate_204` through the SOCKS5 inbound
  (no TLS stack), so the number excludes the TLS handshake `curl` used to include.
* Timestamps/`addr_info`: `addr_info` is rendered like `ip addr show`, but check the WebUI's parser.
* 32-bit userland on a 64-bit kernel relies on the kernel's xtables compat layer; prefer arm64 builds.

## Non-root mode (VpnService)
Without root the app drives `MagicVpnService` instead of this daemon: `VpnService.establish()` gives a tun fd,
`xhuskydg_helper vpnrun <socket> xray run -c ...` receives it over `SCM_RIGHTS` and execs xray with `XRAY_TUN_FD`
(same contract as the root engine). The app itself is excluded from the VPN, so xray's sockets need no fwmark
(`sockopt.mark` is stripped from the runtime config). Exclude Apps maps to `addDisallowedApplication`, Bypass LAN to
`excludeRoute` (Android 13+). Interface / tethering / apply-on / other-VPN options need iptables and are greyed out.

With *Enable IPv6* off, IPv6 is still routed into the tunnel and dropped by a leading `::/0 -> block` rule (like the root engine's ip6tables drop), so it cannot leak out of the physical interface.

## Traffic Monitor (`traffic.c`)
Counts every hotspot / LAN client **at the phone's own door**, independent of `xraytun0` and of whether the proxy runs.

* Upload: `mangle PREROUTING -j MV2R_HS_UP` (position 1, the first hook a client packet meets), one counter-only rule
  `-i <ap-if> -s <ip>` per client.
* Download: `mangle POSTROUTING -j MV2R_HS_DN` (position 1), one rule `-o <ap-if> -d <ip>` per client. It sits in
  POSTROUTING because reverse NAT (nat PREROUTING) runs after mangle PREROUTING; only there does the packet carry the
  client's own address again.
* Clients are learned on every poll from the neighbour table (`nl_neigh_list`); `<ap-if>` is the interface of the
  neighbour entry. Entries on `lo`, `xraytun0` or the upstream interface are never clients. v4 and v6, capped at 200
  rules per family. There is no catch-all rule any more (it would count all inbound traffic of the phone).
* `traffic_poll` creates chains/hooks/rules when missing, collapses duplicates, reads the counters and rewrites
  `run/traffic` atomically (`T tx rx`, `O 0|1`, `R <src> <pkts> <bytes>`, `D <dst> <pkts> <bytes>`, `H <if> <rx> <tx>`,
  `M`, `E`). `H` is the interface's own sysfs byte counter: the client rules can never add up to more than it.
  `traffic_readonly` only reads (nothing in the table is touched).
* **On / off**: `traffic_stop` writes `data/traffic_off` and removes every rule of ours; `traffic_start` deletes the
  file (the next poll installs the rules). The switch survives restarts: service start calls `traffic_autostart()`,
  service shutdown calls `traffic_remove()`. While off, `traffic_poll` only reports `T` / `O 0`.
* Block / limit (below) is a separate feature and keeps working whether counting is on or off.
* Older builds hooked `-o xraytun0` (`MV2R_TRAFFIC`) and `-i xraytun0` (`MV2R_TRAFFIC_RX`); each poll removes those
  hooks and chains (`drop_legacy`), and `uninstall.sh` has an iptables fallback for both generations.
* `traffic_reset` zeroes both chains.
* **Counters survive table commits.** The kernel zeroes every counter on `IPT_SO_SET_REPLACE` and returns the old ones;
  `ipt.c` maps them back onto the surviving entries with `IPT_SO_SET_ADD_COUNTERS` (`ipt_image.ctr_map`, like
  libiptc), so adding a client - or any other change by this service - does not reset the other rules' counters.
  Tests: `native/tests/test_counters.c`, `test_traffic.c`; real kernel: `live_hotspot.c` (needs root, see its header).
* Limitation: if the phone's tethering offload forwards packets in hardware, they never reach netfilter and cannot be
  counted by any iptables rule. The `H` line (interface counters) shows whether that happens.

### Device control (block / limit by MAC)
The Devices list can block a device, give it a data limit, or reset its counters (`xhuskydg_helper proxy_control`
verbs `traffic_block|unblock <mac>`, `traffic_limit <mac> <bytes>` (0 = none), `traffic_limit_all <bytes>`,
`traffic_reset_mac <mac>`). The `mac` match is not valid in POSTROUTING, so a second chain `MV2R_TRAFFIC_CTL` hangs
off `mangle FORWARD` (position 1, any interface):

* blocked: `-m mac --mac-source X -j DROP`
* limited: `-m mac --mac-source X -m quota --quota N -j RETURN`, then `-m mac --mac-source X -j DROP`
* `limit_all` gives every device on the neighbour table its own quota of that size (devices that appear later are
  picked up on the next `traffic_poll`); a per-device limit overrides it.

Details worth knowing: the quota counts what the device **sends** (the direction the list shows), and IPv4 / IPv6 have a
quota each. Settings are saved in `DATADIR/traffic_ctl` and re-applied at service start; the chain is removed on
`stop_svc` (and by `uninstall.sh`), and a quota starts full again after a service restart. `traffic_reset_mac` zeroes the
counters of every address seen for that MAC and rebuilds its quota. Block / limit survive "Stop counting".
New engine pieces in `ipt.c`: `mac` and `quota` matches, `ipt_zero_src`, `ipt_delete_mac`, `ipt_chain_macs`.
