# Magic V2Ray

[Xem phiên bản Tiếng Việt](./README_vi.md)

<img width="4096" height="4096" alt="image_0" src="https://github.com/user-attachments/assets/41c830ac-3c5c-43a4-8329-96713cb8a3c2" />

A powerful and easy-to-use internet proxy manager for rooted Android devices. It helps you route all device traffic through a proxy server to secure your connection, bypass restrictions, and share your high-speed connection with other devices.

---

## What is Magic V2Ray?

**Magic V2Ray** is an advanced network tool designed for rooted Android phones. By combining top-tier proxy cores, it creates a seamless system-wide connection that covers all your apps. 

It comes with a clean Web UI where you can easily organize your proxy configurations, save your subscription links, and manage your network with a few clicks.

---

## Why Use Magic V2Ray for rooted Android devices?

If you are used to standard V2Ray apps (like v2rayNG, Matsuri, Nekobox), here is why Magic V2Ray is a game-changer:

- **Immortal System-Wide Coverage:** Standard apps run in user-space via Android's native `VPNService` API and get easily killed by Android's aggressive memory management (Low Memory Killer) when RAM is tight, dropping your connection or leaking your real IP. Magic V2Ray operates with Root/Kernel privileges, running silently and invincibly as a system daemon that the OS cannot kill.
- **The Power of Core Routing:** Standard VPN apps force all traffic through a virtual network interface (`tun0`), creating a software bottleneck that increases ping and context-switching overhead. Magic V2Ray uses native Linux kernel routing (`iptables` / `ip rule` / `TPROXY`), intercepting network packets right at the core level. This leads to blazing-fast throughput and desktop-grade lower latency.
- **Performance & Battery Optimization:**
  + **Zero-Copy Context Switching:** Standard apps rely on Android’s `VpnService` API, forcing network packets to travel from the Application $\rightarrow$ Linux Kernel $\rightarrow$ copied up to the Java User-space (VPN App) $\rightarrow$ processed and thrown back down to the Kernel. This continuous context switching between Kernel and User-space consumes massive CPU cycles. Magic V2Ray uses Root privileges to interact directly with the Linux network stack (`iptables` / `ip rule`). Packets go straight from the App $\rightarrow$ routed by the Kernel natively into Xray $\rightarrow$ out to the Internet. The entire routing process happens at the native binary level, completely bypassing Android's heavy Java overhead.
  + **No Single-Queue Bottleneck:** Android's `VpnService` manages all application traffic through a single, system-allocated queue. Under heavy load (e.g., streaming 4K video while downloading), this Java-managed queue quickly bottlenecks. Magic V2Ray splits traffic efficiently at the Netfilter/Mangle layer based on specific application profiles, unleashing packets the millisecond they are generated and unlocking maximum bandwidth.
  + **Optimized Packet Handling:** Without a virtual VPN wrapper, connection latency (Ping) and initial TCP Handshake times are slashed by several milliseconds. Packets travel directly without being fragmented or bloated by the Android framework's VPN management headers.
- **Seamless Dynamic Reconnects:** Instantly detects when you switch between Wi-Fi and 4G/5G, hot-reloading the firewall routing rules directly in the kernel without the typical 5-to-10 second connection freeze found in standard user-space VPN apps.
- **Universal Root Support:** Works flawlessly out-of-the-box across Magisk, KernelSU, and APatch, fitting perfectly into modern Android root environments.

---

## Key Features

- **Category Organizing:** Group your proxy servers into custom folders or categories.
- **Smart Link Import:** Easily paste subscription URLs, raw configuration strings, or mixed text codes.
- **One-Click Auto-Reload:** Saves your subscription links so you can update an entire category with a single tap.
- **No Battery Drain:** Native background processing ensures your battery lasts much longer compared to running heavy standalone VPN apps.

---

## Acknowledgments & Credits

This project uses pre-built binaries from the following open-source projects:
* **[Xray-core](https://github.com/XTLS/Xray-core):** The underlying engine that handles next-generation proxy protocols.
* **[tun2socks](https://github.com/xjasonlyu/tun2socks):** A high-performance utility used to wrap proxy channels into a virtual network interface.