# Magic V2Ray v4.0 Release Notes

### Highlights

- **Hotspot sharing without Shizuku**: share your proxy over hotspot using Wireless debugging (ADB) pairing and a built-in `app_process` helper.
- **Multi-hop proxy chains**: build chains of 2-16 hops.
- **In-app updates**: download, verify and install new APKs directly from the app.
- **External API level 2**: permission-gated node access for other apps.

### Added

- **ADB-based hotspot sharing (no Shizuku required)**
  - Wireless debugging pairing and an `app_process` helper, with ADB status and pairing controls in the Web UI and notification-based pairing.
  - Optional persistent ADB TCP mode that reconnects without re-pairing after the first wireless ADB connection. It can be disabled in settings, and a warning is shown that the port is reachable on networks the phone joins.
  - Configurable ADB TCP port: random or custom, persisted, validated from 1024 to 65535, and used for reconnections. The current port is shown on the pairing screen.
  - Localized setup, status and TCP-mode guidance.
- **Multi-hop proxy chains**
  - Create and configure chains with 2-16 hops, including dynamic hop selection and Xray outbound routing.
  - Duplicate nodes are allowed in a chain.
  - Legacy two-hop chain URIs are still supported.
  - Updated chain display, log export and plaintext checks for hop lists.
- **In-app APK update installation**
  - Downloads and verifies update APKs securely, then installs them through `PackageInstaller`.
  - Handles install permissions and shows progress in the Web UI.
  - Update info is now fetched without cache.
- **External API level 2**
  - Permission-gated node access: anonymous or full access, granted by user consent.
  - New provider methods for listing nodes and getting the active node, plus access management.
  - `connect` now supports direct, chained and node-ID URIs.
  - Added an English API guide documenting level 2 node access and special connect URIs.
- **Happy Eyeballs (RFC 8305)** for proxy and direct `freedom` outbounds, with interleaved address families and bounded concurrent connection attempts.
- **More ALPN and TLS fingerprint presets**: common choices added, custom ALPN values are preserved when editing, and MASQUE ALPN defaults to `h3`.
- **WebView navigation**: Android Back now dismisses the active modal and revisits previous tabs before sending the app to the background.

### Changed

- **Native HTTP bridge**: WebUI requests, downloads and probes no longer use shell-based `curl`. SOCKS routing is preserved, request limits and redirect schemes are enforced, and insecure TLS stays opt-in per subscription.
- **Native subscription updater**: the shell-based hourly updater is replaced by a stoppable native worker that downloads, decodes, caches and publishes changed subscriptions for the WebUI. Legacy updater processes are cleaned up, and host tests cover decoding, hashing and update behavior.
- **Geo-IP lookup** now queries `api.ip.sb` and `ipwho.is` in parallel, normalizes the responses, prefers the first complete result and keeps a valid partial result as a fallback.
- **MASQUE URI handling** synced with v2rayNG.
- **Documentation**: translated READMEs and API guides moved into locale directories. All README translations now explain that KernelSU ZIP downloads are installers, not APKs, and link to the Releases page for APK downloads.

### Fixed

- **Hotspot Binder handoff reliability**: the helper Binder is delivered through a synchronous `ContentProvider` call with broadcast fallbacks, and `adb shell` reads are bounded so detached helpers can no longer leave startup waiting indefinitely.
- **ROM-specific provider call signatures**: the correct `IContentProvider.call` overload is selected (preferring `AttributionSource`, falling back to the one with the most parameters) to avoid the deprecated Android 12 variant that forwards an invalid authority. The reflected method is logged, and plausible authority argument positions are tried.
- **Hotspot broadcasts** are now sent through `IActivityManager` first, with `Context` as a fallback, because bare `app_process` callers may lack an application thread and broadcasts could fail or be dropped.
- **Wireless debugging connections**: the TLS connect port is discovered through system properties and mDNS before falling back to library discovery. Early shell stream closure is tolerated, while replies indicating an unexpected shell UID are still rejected.
- **ADB pairing flow**: refreshed pairing screen and notifications with localized strings and status feedback. mDNS service resolution is serialized for Android versions that allow only one resolve request at a time.
- **System DNS leaks for local hosts**: reserved local names are excluded from system DNS queries, and dial probes resolve using configured or known local addresses, falling back to loopback.

### Notes

- Hotspot sharing via ADB requires Wireless debugging (Android 11+).
- KernelSU ZIP downloads are installers, not APKs. Download the APK from the repository Releases page.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)
