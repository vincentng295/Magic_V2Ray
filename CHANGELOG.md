# Release Notes — Version v1.12.2

- Integrated Carrier DNS Support: Hardcoded default DNS servers for Viettel, VinaPhone, and Mobifone into the Legacy DNS list for better out-of-the-box local connectivity.
- Optimized Domain Resolution: Switched outbound domain resolution to use Xray's native local DNS, ensuring faster lookup times and more reliable routing.
- Upgrade Xray-core to v26.9.9: Note, Starting with version v26.9.9, Xray-core officially implements a mechanism to block unencrypted VLESS connections (lacking TLS or VLESS's native encryption) over the public internet in order to prevent data exposure and network scanning risks.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)