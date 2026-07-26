# Magic V2Ray

[Xem phiên bản Tiếng Việt](./README_vi.md)

![screenshot_1](images/screenshot_1.jpg) 

A powerful and easy-to-use internet proxy manager for rooted Android devices. It helps you route all device traffic through a proxy server to secure your connection, bypass restrictions, and share your high-speed connection with other devices.

---

## What is Magic V2Ray?

**Magic V2Ray** is an advanced network tool designed for rooted Android phones. By combining top-tier proxy cores, it creates a seamless system-wide connection that covers all your apps. 

It comes with a clean Web UI where you can easily organize your proxy configurations, save your subscription links, and manage your network with a few clicks.

---

## Why Use Magic V2Ray for rooted Android devices?

If you are used to standard V2Ray apps (like v2rayNG, Matsuri, Nekobox), here is why Magic V2Ray is a game-changer:

- **Immortal System-Wide Coverage:** Standard apps run in user-space via Android's native `VpnService` API and get easily killed by Android's aggressive memory management (Low Memory Killer) when RAM is tight, dropping your connection or leaking your real IP. Magic V2Ray operates with Root privileges, running as a system daemon that the OS does not kill under memory pressure.
- **Reaches traffic `VpnService` cannot:** A `VpnService` app only sees traffic from UIDs the framework routes to it. Magic V2Ray marks packets at the Netfilter layer, so it can also cover system UIDs, and it does not consume the single system-wide VPN slot — no conflict with a per-app VPN, and no persistent VPN key icon in the status bar.
- **Native Hotspot / Tethering Support:** `VpnService` cannot carry tethered clients, because Android forwards their traffic outside the VPN's namespace. Magic V2Ray intercepts it at `PREROUTING` and policy-routes it into the same tunnel, so devices sharing your hotspot get the proxied connection too. See *LAN Gateway Sharing* below.
- **Seamless Dynamic Reconnects:** Detects Wi-Fi ↔ 4G/5G handovers from kernel routing events (`ip monitor`) rather than polling, and re-applies the routing marks without waiting for a timeout.
- **Universal Root Support:** Works across Magisk, KernelSU, and APatch.

### A note on performance

Earlier versions of this README claimed "zero-copy" operation, `TPROXY` routing, and lower latency than `VpnService` apps. **Those claims were not accurate and have been removed.**

What actually happens today is:

```
app → kernel → xraytun0 (TUN) → hev-socks5-tunnel → SOCKS5 over loopback → Xray → internet
```

That is *two* user-space hops, not zero — a `VpnService` app has one. `TPROXY` is not used anywhere in the codebase. Expect throughput and latency broadly **comparable** to a well-implemented `VpnService` client, not dramatically better.

The real reasons to use this module are the ones listed above: it survives low-memory kills, it covers UIDs a `VpnService` cannot, it shares to tethered devices, and it leaves the VPN slot free.

A genuine `TPROXY` mode — which would remove the `hev-socks5-tunnel` hop and make the original claim true — is tracked as future work. It requires `xt_TPROXY` and `IP_TRANSPARENT` kernel support, which not every Android kernel ships, so it would land as an opt-in mode with automatic fallback.
- **Built-in Smart Hotspot Sharing:** Standard VPN apps fail to share proxy connections via Wi-Fi Hotspot because Android routes tethered traffic through a separate network namespace that bypasses the `VpnService`. Magic V2Ray natively intercepts hotspot traffic at the Netfilter layer (`PREROUTING`). It injects custom `iptables`/`ip6tables` and `ip rule` structures to seamlessly force connected clients into the `xraytun0` core, allowing you to share your bypassed 4G/5G connection without any extra tethering apps.
- **Universal Root Support:** Works flawlessly out-of-the-box across Magisk, KernelSU, and APatch, fitting perfectly into modern Android root environments.

---

## You dont have root?

> [!IMPORTANT]
> This is a system **module** (for Magisk / KernelSU / APatch), **NOT** a standalone application!

If your device is not rooted, or if you are looking for a regular GUI application with a visual interface for Android, Windows, macOS, or iOS, please find the official and community-supported clients here:
👉 **[Xray-core GUI Clients](https://github.com/xtls/xray-core#gui-clients)**

---

## Key Features

- **Category Organizing:** Group your proxy servers into custom folders or categories.
- **Smart Link Import:** Easily paste subscription URLs, raw configuration strings, or mixed text codes.
- **One-Click Auto-Reload:** Saves your subscription links so you can update an entire category with a single tap.
- **No Battery Drain:** Native background processing ensures your battery lasts much longer compared to running heavy standalone VPN apps.
- **Native Hotspot Tethering:** Share your secured and bypassed proxy connection to laptops, consoles, or other phones via Wi-Fi Hotspot with full IPv4 and IPv6 routing support.


**Note:** Magisk users need to install [KsuWebUIStandalone](https://github.com/5ec1cff/KsuWebUIStandalone/releases/tag/v1.0) to open WebUI of the module

---

## Acknowledgments & Credits

This project uses pre-built binaries from the following open-source projects:
* **[Xray-core](https://github.com/XTLS/Xray-core):** The underlying engine that handles next-generation proxy protocols.
* **[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel):** A high-performance utility used to wrap proxy channels into a virtual network interface.
* **[curl-android](https://github.com/vvb2060/curl-android):** curl tool and libcurl static library prefab for android

## License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. 

By using this project, you agree to the terms and conditions set forth in the license. For more details, please refer to the [LICENSE](LICENSE) file in this repository.

Essentially, you are free to use, modify, and distribute this software, provided that you include the same license and source code availability in your own project.