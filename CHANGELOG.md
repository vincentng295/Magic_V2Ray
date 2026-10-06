# Release Notes — v1.21

Recent updates to Xray-core introduced a strict security policy that automatically blocks unencrypted VLESS outbound connections pointing to external addresses. In response to community concerns regarding local setups and specific routing needs, this release introduces a dedicated loopback tunnel workaround. When plain VLESS outbounds are detected, the system rewrites their destination to dedicated local loopback addresses (`127.18.x.x`) via an internal `dokodemo-door` proxy tunnel, allowing raw TCP traffic to reach remote servers safely without breaking core compatibility. To protect the system, iptables rules hardens this loopback range to prevent local apps from fingerprinting or probing the underlying tunnel.

Alongside the internal routing rework, the UI now actively alerts users when selecting unencrypted VLESS nodes, ensuring transparency before establishing plain connections. Furthermore, default routing settings have been refined by standardizing domain strategy handling to `AsIs`, avoiding unnecessary DNS lookups when matching rules.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)