# Release Notes — Version 1.11.3

**Fixed:** Switching the active node, editing a node/proxy chain, or editing routing rules while the proxy was running previously triggered a full engine restart that also tore down and rebuilt all iptables/routing rules — causing a brief connectivity gap and unnecessary overhead on every switch.

Now, these config-only changes simply reload the Xray process with the new config while leaving the existing routing/firewall rules untouched, since they don't depend on node selection. This results in faster, smoother node switching with no interruption to traffic routing rules.

Advanced network settings that do affect routing behavior (Network Mode, Allow Tether, IPv6) still perform a full restart as before, ensuring rules stay correctly in sync when those options change.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)