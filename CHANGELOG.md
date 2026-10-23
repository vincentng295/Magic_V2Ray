# Magic V2Ray v1.25.1 Release Notes

Update to Xray-core v26.10.21

Urgent security update: Due to the [private keys of eight intermediate certificates in India being cracked a few days ago due to their simplicity](https://github.com/XTLS/Xray-core/pull/7212#issuecomment-6310150214), the RootCAs dependency library in this version has removed three root certificates (v26.10.10 and v26.10.17 have these certificates built-in and have been retired), and the files are compiled using Go 1.27.3 to ensure that problematic certificates are filtered out even if the operating system CA library is not updated. Users should upgrade to this version as soon as possible and release the stable at the end of the month release (the operating system's CA library may update slowly, so override the previous latest)

# Magic V2Ray v1.25 Release Notes

Introduces critical improvements to DNS traffic routing, rule prioritization, and user interface stability.

This update reorganizes routing rules to place user-defined rules and client DNS routing ahead of standard DNS rules, ensuring that custom configurations are correctly prioritized. When DNS hijacking is active, client DNS rules are now prioritized at the top of the routing stack to prevent traffic from being intercepted prematurely. Additionally, the header layout has been refined to streamline the service status display and layout presentation.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)