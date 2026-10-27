# Magic V2Ray v1.26 Release Notes

Magic V2Ray version 1.26 brings significant improvements to network monitoring, active interface detection, and protocol support.

This update improves active network detection by parsing netd default-network routing rules directly, cleanly mapping numeric table IDs through the system rt_tables. In addition, the redundant VPN-app UID bypass chain and its associated monitoring logic have been completely removed to streamline routing performance. Furthermore, full MASQUE proxy support has been introduced, covering URI parsing, serialization, and configurable settings for TLS, HTTP, DNS, and Cloudflare WARP.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)