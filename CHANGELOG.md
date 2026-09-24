# Release Notes — v1.18.2

- Add per-upstream routing for DNS module
- Fix start in `action.sh`

# Release Notes — v1.18.1

Version 1.18.1 introduces critical networking and configuration adjustments designed to improve IPv6 routing stability and prevent local network detection issues. In this update, the FakeDNS IPv6 address pool has been migrated from the previous Local Unique Address space to the dedicated IETF benchmarking range at 2001:2::/48. This change prevents modern browsers, such as Chromium-based applications, from triggering unexpected local network permission prompts when establishing proxy connections.

Additionally, IPv6 FakeDNS pools are now enabled unconditionally by default rather than depending on explicit IPv6 configuration flags. To maintain clean traffic handling across mobile access points, incoming IPv6 DNS packet dropping on port 53 for hotspot interfaces has been disabled. The service routing scripts have also been streamlined by cleaning up redundant subnet bypass rules that are no longer required under the updated address scheme.

# Release Notes — v1.18

Release v1.18 introduces flexible DNS controls, enhanced multi-server configurations, and improved settings management for a smoother proxy and routing experience.

A major feature in this release is configurable DNS Hijacking. Users can now choose whether port 53 DNS traffic from local inbounds is handed over directly to Xray's internal DNS module or forwarded outward. Along with this change, DNS port 53 traffic is no longer unconditionally dropped over IPv6 firewall rules, preventing unnecessary packet loss and keeping IPv6 resolution smooth.

The Foreign DNS module has been upgraded to support multiple fallback servers simultaneously. Users can now input a comma-separated list of resolvers, allowing Xray to distribute and failover foreign domain queries across several DNS endpoints. Additionally, local DoH DNS entries for major Vietnamese ISPs (Viettel, Mobifone, and VNPT) have been included in the default fallback list.

To improve usability in the Web UI, advanced Xray DNS engine parameters can now be configured directly, such as caching controls, stale query behavior, and system hosts integration. A new "Reset to defaults" button has also been added to the Traffic Settings tab, allowing users to restore default traffic parameters easily without affecting custom network interfaces, routing rules, or hosts overrides.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)