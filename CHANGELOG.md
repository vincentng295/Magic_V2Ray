# Magic V2Ray v2.2 Release Notes

Magic V2Ray v2.2 introduces proxy sharing without root via Shizuku. In Non-Root mode on Android 13 and newer, users can now route traffic from Wi-Fi hotspot and USB-tethering clients directly through the active proxy using a fail-closed test-network bridge that automatically stops sharing if the proxy or upstream disconnects.

A new App Settings tab allows full appearance customization. You can switch between Light, Dark, and System themes, or personalize the interface with dynamic system colors or a custom color picker on Android 13+. System bars and native dialogs automatically adapt to match your chosen theme.

Custom Hosts management has been expanded with tools to import entries from files or the clipboard, export your list via Android's document picker, and clear all entries at once. A new notice clarifies when DNS hijacking is required, while chunked file writing, entry validation, and memoized parsing ensure large hosts lists load and save reliably without hitting shell argument limits.

This release also adds support for importing subscriptions via `v2rayng://` and `magicv2ray://` deep links with a confirmation prompt, resolves DNS server hostnames alongside outbound domains using the system DNS, and includes full Russian, Simplified Chinese, and Traditional Chinese translations for the native app.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)