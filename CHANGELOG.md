# Magic V2Ray v3.3 Release Notes

Magic V2Ray v3.3 adds in-app update checks. You can check manually or let the app check about once a day, and a notification and a dashboard banner tell you when a new version is out. A popup shows the release notes with a button to download the APK that matches your device. If GitHub is blocked in your region, the check automatically falls back to mirror links, and it now follows HTTP redirects instead of failing with HTTP 301.

You can now manage your own geoip and geosite databases. Import `.dat` files or download them from a URL, rename or remove custom files, and add extra `.dat` files for rules. Your files are kept separate from the app's bundled copies, so app updates never overwrite them, and the bundled defaults can be restored at any time. This works in both root and non-root modes.

On rooted devices, Xray now runs as an unprivileged user with only the capabilities it needs, and its config is passed to it without being left on disk. The separate log daemon has been replaced by a log buffer inside the service. The tunnel processes (`openxtun` and `xray`) are also protected from Android's low-memory killer, so the proxy stays alive under memory pressure. Proxy ports are hidden from other apps while Xray itself stays excluded from the tunnel.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)
