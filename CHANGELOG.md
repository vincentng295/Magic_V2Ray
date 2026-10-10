# Release Notes — v1.23

Magic V2Ray version 1.23 introduces a comprehensive set of improvements centered around network safety, restart reliability, and a redesigned DNS management experience.

The update brings a new fail-closed mechanism for network restarts. Previously, tearing down and rebuilding routing rules could occasionally cause short windows of unrouted traffic leaks. Version 1.23 solves this by introducing a dedicated hard restart flow paired with routing safeguards. A blackhole route in table 100 ensures that marked traffic is safely dropped if the TUN interface ever disappears, while temporary firewall hold rules block non-Xray traffic during service reloads. Additionally, the cleanup path automatically flushes table 100 and clears stale gates to prevent leaks from any previously interrupted restarts.

DNS configuration has also been completely overhauled to give users a much cleaner interface and finer control. The old legacy DNS and VPN DNS settings have been replaced by a modern, dedicated DNS servers modal editor. This interface provides individual, manageable lists for direct DNS resolvers, domains using direct DNS, and proxy DNS servers, complete with bulk editing and quick item controls.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)