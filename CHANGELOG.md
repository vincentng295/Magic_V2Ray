# Magic V2Ray v3.2 Release Notes

Magic V2Ray v3.2 introduces a dedicated Traffic Monitor tab to track network usage in real time. The new view features a live tunnel speed chart alongside total upload and download counters that work in both root and VpnService modes. For rooted devices, a separate Devices view breaks down hotspot traffic by MAC address while filtering out multicast noise, and allows you to block specific clients, enforce per-device or global data limits, and reset individual counters.

You can now enable live tunnel speeds and session totals directly inside the active proxy notification from App Settings. Additionally, proxy nodes now display exit country flags, with automatic lookups after importing subscriptions or nodes as well as manual refresh options for individual nodes and categories.

This release also routes the built-in provider mini browser through the core proxy's SOCKS inbound when active, ensuring provider websites remain accessible even when the main app is excluded from the tunnel. Finally, URI parsing and serialization have been updated to use v2rayNG's `fm` parameter for final masks while keeping backward compatibility with older `finalmask` links.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)
