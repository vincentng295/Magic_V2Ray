# Magic V2Ray v2.0.2 Release Notes

Ask users to disable battery optimization

# Magic V2Ray v2.0.1 Release Notes

Introduces system notifications and direct proxy controls to keep users informed and in control of their connection. A persistent notification now displays the active proxy status along with the currently selected node name, allowing users to stop the proxy engine with a single tap directly from the notification bar.

For rooted devices, the release adds a dedicated OOM guard background component that periodically maintains process priority under memory pressure, keeping the status notification and Quick Settings tile reliably accessible. Additionally, IPv6 routing in `VpnService` mode has been refined to assign IPv6 tunnel addresses only when IPv6 is enabled, ensuring clean connection fallbacks when IPv6 is turned off.

Update Xray-core to v26.11.9

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)