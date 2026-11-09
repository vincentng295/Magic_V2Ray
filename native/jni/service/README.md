# magic_v2ray service (C port of `service.sh`)

One static-ish binary replaces `service.sh` and every external tool it called
(`ip`, `iptables`, `ip6tables`, `busybox`/`toybox`: `sed grep tr base64 stat mount umount
mkfifo date kill inotifyd getprop sleep`, and `curl`).
Only your own helper `bin/xhuskydg_helper` is still used (the `logservice` verbs).

## Layout
| file | replaces in service.sh |
|---|---|
| `src/ipt.c`, `ipt.h` | `iptables` / `ip6tables`: talks to the kernel via `IPT_SO_GET_INFO/GET_ENTRIES/SET_REPLACE`; edits a table in memory and swaps it in with **one** replace (atomic per table, retries on `EAGAIN`) |
| `src/netlink.c` | every `ip rule/route/addr/link`, `ip monitor`, `ip rule show \| grep` |
| `src/routing.c` | section 7: `apply_routing_rules`, `clear_routing_rules`, tun config, blackhole, hold/release |
| `src/iface.c`, `ula.c`, `iphunt.c` | active/upstream interface, fwmark rule, IPv6 ULA decoy, IP hunter |
| `src/exclude.c` | Exclude Apps (inotify instead of `inotifyd`) |
| `src/latency.c` | latency monitor (built-in SOCKS5 client instead of `curl`) |
| `src/xray.c` | start/stop/reload, native `openxtun` (open tun, `TUNSETIFF`, `XRAY_TUN_FD`, exec) |
| `src/control.c`, `main.c` | FIFO command loop, boot sequencer |
| `src/settings.c`, `proc_track.c`, `log.c`, `util.c`, `worker.c` | settings.base64 reader, `/proc` bind-mount liveness, logging, threads |

The runtime contract with the WebUI is unchanged: same paths under `/dev/sysctl_stubs`
(`run/control.pipe`, `proc/<name>/exe`, `time_res`, `addr_info`, ...), same commands, same log format.

## Stopping and live upgrade
`xhuskydg_helper service --stop` writes `stop_svc` into `run/control.pipe` and waits for the service to exit.
On `stop_svc` the service stops its monitors, kills xray, removes the iptables chains / ip rules / ULA / fwmark
rule / traffic hold, stops the log daemon and the subscription loop, detaches every mount under
`/dev/sysctl_stubs` (and the legacy `ip_forward` stub), then exits. The "xray was running" flag is kept,
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
