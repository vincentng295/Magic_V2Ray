# Magic V2Ray v1.28.1 Release Notes

An urgent core security update alongside targeted routing and control fixes.

Xray-core has been updated to v26.10.31. This release disables MPTCP by default to mitigate a critical Linux kernel vulnerability in IPv4 MPTCP listening, significantly reducing potential attack exposure. Users are strongly urged to update promptly.

Network detection logic in `service.sh` now properly recognizes system default network rules that specify full-range UIDs (0-2147483647), preventing per-app rules from interfering with default interface resolution.

The proxy control flow in `proxy_control.sh` now validates active configuration files (`config.v2.json`) before issuing start commands, ensuring smoother startup behavior and accurate service state tracking.

Continuous integration workflows have been expanded with automated Python unit tests that execute service and proxy control routines against virtual fixtures prior to packaging release zips. Alpha builds now include explicit commit-hash version stamping in module metadata and release names.

# Magic V2Ray v1.28 Release Notes

Introduces a new VpnService bypass setting in the Network tab, which lets third-party VPN tunnels handle packets first while routing only the VPN app's own outgoing traffic through Xray. In addition, service logging has been upgraded to include microsecond-level timestamps and stream directly into the Xray log pipe for easier debugging. This update also includes refreshed GeoIP and GeoSite database files to ensure up-to-date domain and IP routing classification.  

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)