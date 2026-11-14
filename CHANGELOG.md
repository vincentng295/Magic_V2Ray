# Magic V2Ray v2.4 Release Notes

You can now expose a SOCKS5 proxy port directly to your local network or hotspot via a new option in Traffic Settings. This feature supports custom port configuration, optional username and password authentication, and one-tap copyable connection details for IPv4, IPv6, and the local gateway domain.

To improve connection stability, WebSocket outbound connections now send keepalive heartbeats every 30 seconds by default. An alternative Magic gateway domain (`hakumaworld.dpdns.org`) has also been added to the default DNS overrides for easier local access.

For troubleshooting and maintenance, exported bug-report archives in root mode now capture Xray logs directly from the service ring buffer, while the underlying flashable module files have been reorganized under a dedicated directory structure for cleaner packaging.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)