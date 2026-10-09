# Release Notes — v1.22

Magic V2Ray version 1.22 focuses on granular network controls and DNS improvements, giving users precise control over local traffic while ensuring reliable resolution for critical local destinations.

This update replaces the previous, all-or-nothing LAN bypass option with fine-grained LAN bypass controls. Users can now individually toggle specific IPv4 and IPv6 subnet ranges—such as RFC 1918 private networks, Carrier-grade NAT, or link-local subnets—to choose exactly which destination ranges skip Xray and connect directly, and which are routed through the proxy tunnel. To prevent routing loops and avoid breaking internal proxies, loopback destinations remain strictly bypassed and cannot be unticked.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)