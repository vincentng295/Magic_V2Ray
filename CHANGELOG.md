# Magic V2Ray v1.27 Release Notes

Magic V2Ray version 1.27 updates the core engine to Xray-core v26.10.29 and introduces configurable upstream network interface selection. You can now pin Xray's egress traffic directly to a specific network interface in Network Settings, while automatically falling back to auto-detection whenever the chosen interface becomes unavailable.

Policy groups are now integrated directly into profile categories as standard nodes. This allow policy groups to be edited, moved, renamed, and organized alongside other regular proxy nodes while retaining their dynamic node filtering capabilities. Additionally, nodes can now be moved between categories directly within the node editor modal.

Network routing and IPv6 connectivity have been refined with updated IPv6 ULA address prefixes for active network interfaces and VirtualAP IPv6 Routed mode compatibility.

Update Xray-core to v26.10.29

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)