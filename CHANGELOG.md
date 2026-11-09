# Magic V2Ray v1.29 Release Notes

This release shifts core functionality from legacy shell scripts (`service.sh`) to a native, statically linked C/C++ helper binary (`xhuskydg_helper`). The backend daemon and its sub-tools (`openxtun`, `dnsjson`, and `logservice`) are now built directly from source using the Android NDK, drastically improving execution speed, stability, and control over system calls.

Network state management and firewall operations have been completely overhauled. Routing and iptables rules are now managed through a native xtables and netlink engine, allowing table updates to process atomically in memory with retry logic instead of executing slow, individual shell commands. Additionally, FakeDNS IP pool range has been updated to 198.19.0.0/16 to minimize conflicts.

Background processes have been optimized for better system resource usage. The application now includes a built-in background subscription auto-updater that periodically syncs node lists, improved network interface monitoring with event debouncing, and refined logging daemons that prevent thread leaks and unresponsive sockets.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)