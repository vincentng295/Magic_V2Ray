# Magic V2Ray v2.1 Release Notes

Magic V2Ray 2.1 introduces a redesigned Dashboard interface that brings a cleaner, card-based layout to the app. The primary connection controls and import options are organized into structured dashboard cards, while subscription links, node URIs, and base64 lists now use a unified importer for easier setup. The top header provides quick access to actions through a dedicated menu, allowing you to easily toggle Professional Mode or adjust node categories while keeping the interface clutter-free. The app interface has also been updated with a new green color theme.

This update introduces automated system DNS resolution for outbound proxy domains, addressing routing and lookup issues before a proxy connection is established. When enabled, Magic V2Ray resolves outbound server domains using your device's physical network DNS and pins the resulting IP addresses directly into the local host rules. This mechanism runs prior to starting or restarting the proxy engine, and it is also integrated into node ping and HTTP tests to ensure consistent domain resolution without relying on uninitialized tunnel routes.

Root execution performance and system integration have been significantly improved. The app now uses libsu to maintain a bounded pool of persistent root shells instead of launching a new root process for every command, reducing root prompt overhead and system resource usage. Additionally, network interface handling during handoffs has been hardened to seamlessly pick up valid fallback networks when standard default routes are delayed, preventing data stalls during connection transitions.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)