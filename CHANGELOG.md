# Magic V2Ray v2.3 Release Notes

This release transitions the core engine to a custom Xray-core source submodule, allowing direct connections to plaintext VLESS and Trojan nodes without needing the previous loopback tunnel workaround. To keep connections secure, the app now displays explicit security warnings before connecting to unencrypted VLESS or Trojan nodes, enables Hijack DNS by default, and warns you whenever DNS settings leave queries vulnerable to leaking outside the proxy.

You can now personalize the interface with custom app wallpapers in App settings. When a background image is active, the app can automatically extract its dominant color to generate a matching theme, apply an adjustable readability overlay, and adapt card transparency while keeping dropdown menus opaque. Light theme Start and Stop buttons have also been updated with higher-contrast styling, and the Exclude apps tab now loads installed applications in smoother asynchronous batches with a loading indicator.

For troubleshooting and bug reports, a new Export app logs option in App settings packages application logs, Xray logs, active settings, sanitized configurations with credentials redacted, device specifications, and network routing details into a single ZIP file. On Android 10 and newer, this archive saves directly to Download/MagicV2Ray, while older Android versions open the system file picker.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)