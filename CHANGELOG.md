# Release Notes — Version v1.12

### Core & Performance Improvements

* **Transitioned to XrayTUN & OpenXTun Integration**: Replaced `hev-socks5-tunnel` with `openxtun` as the real device-wide traffic path (`tun-in`). It creates and hands the `xraytun0` interface file descriptor directly to Xray, improving overall connection lifecycle and memory footprint.
* **Full LAN/Intranet Bypass**: Updated policy routing and IPTables rules for both IPv4 and IPv6 to cover complete RFC-reserved and non-globally-routable subnets. IPv6 local bypass parity with IPv4 is now fully enforced.

### Network & Routing Fixes

* **Automated VPN-App Loop Prevention**: Introduced dedicated mangle chain detection (`BYPASS_VPN_UID`) to automatically identify and exclude third-party `VpnService` applications from traffic marking, resolving infinite routing loops when concurrent VPN applications are present.
* **Bypass Custom Network Interfaces**: Added support for bypassing specific network interfaces (e.g., `tailscale0`, `wt0`). Outbound traffic heading toward specified interfaces skips Xray processing completely.
* **Resolved Tailscale & Dual-VPN Conflicts**: Fixed severe network dropouts, infinite traffic loops, and gateway hijacking caused by route conflicts between Magic V2Ray, Tailscale (Magisk module / Android app), and other local VPN services. 

### Web Dashboard & UI Updates

* **Bypass Interface Management Panel**: Added settings in the Web UI to configure custom bypass interfaces. State updates now properly persist upon clicking "Save & Apply Configurations."

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)