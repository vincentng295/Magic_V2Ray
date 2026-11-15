# Magic V2Ray

[Xem phiên bản Tiếng Việt](./README_vi.md)

![screenshot_1](images/screenshot_1.jpg) 

A powerful, modern internet proxy manager for Android devices (supporting both Rooted and Non-Rooted environments). It routes system and application traffic through high-performance proxy cores to secure your connection, bypass network restrictions, and share your connection with other devices.

---

## What is Magic V2Ray?

**Magic V2Ray** is an advanced network client for Android. Built as a standalone Android app, it packages management tools and proxy cores natively to create a seamless network connection across your device.

Starting with **v2.0**, Magic V2Ray introduces a **Non-Root Mode** powered by Android's `VpnService`, allowing non-rooted users to run the proxy engine smoothly. Rooted users continue to enjoy kernel-level daemon routing, persistent background processing, and low-level packet marking.

It features a clean Web UI to organize proxy configurations, manage subscriptions, and monitor your network with ease.

---

## Key Highlights & Operation Modes

### 1. Root Mode (Daemon Engine)
Designed for maximum stability and advanced network routing on rooted devices (Magisk, KernelSU, APatch):
- **Immortal Connection:** Operates as a root-level system daemon. The OS Low Memory Killer (LMK) will not kill the process under heavy memory pressure.
- **Deep Routing Coverage:** Intercepts traffic at the Netfilter layer, enabling transparent proxying for system UIDs that standard VPNs cannot reach.
- **No System VPN Slot Consumed:** Works without taking up the standard Android VPN slot—no permanent VPN key icon in the status bar, and no conflicts with per-app VPNs.
- **Native Hotspot / Tethering:** Intercepts traffic at `PREROUTING` to route tethered devices (Wi-Fi Hotspot, USB Tethering) through the proxy tunnel automatically.
- **Dynamic Reconnects:** Listens directly to kernel network events (`ip monitor`) for instant Wi-Fi ↔ Cellular handovers.

### 2. Non-Root Mode (VpnService)
Starting with **v2.0**, devices without root access can run Magic V2Ray seamlessly using Android's native `VpnService` engine:
- Safe, standard user-space proxy routing.
- No root or custom kernel modules required.

---

## Additional Features

- **Quick Settings Tile:** Toggle the proxy engine directly from your notification shade. Long-pressing the tile opens the app.
- **Live Module Updates:** Fully supports live updates for version 176 and newer—upgrade the service on the fly without rebooting your device.
- **Category Organization:** Group your proxy servers into custom folders/categories.
- **Smart Subscription Import:** Easily paste subscription URLs, raw configurations, or mixed text codes.
- **One-Click Auto-Reload:** Keep your subscriptions up-to-date with a single tap.
- **Hotspot Sharing without root:** in Non-Root mode, Shizuku can share the proxy with hotspot / USB clients (Android 13+).
- **Hotspot Sharing:** Share your proxied connection with laptops, consoles, or other phones via IPv4/IPv6 Wi-Fi Hotspot.
- **Provider Websites:** Add a VPN seller's website as a category, open it in a built-in mini browser, tap its Sync button to import nodes, and pin a home-screen shortcut to it. See [Deep Links](#deep-links-for-providers).

---

## Installation & Usage

Magic V2Ray is distributed as a standalone Android application (`.apk`), which also doubles as a flashable root module zip.

### Non-Root Users
1. Download and install `magic_v2ray-*-arm64-v8a.apk` (or `-x86_64.apk`).
2. Open the app, select **Non-Root (VpnService) Mode**, grant the VPN permission, and start the connection.

### Root Users (Magisk / KernelSU / APatch)
**Option A: Install via Root Manager (Module + App)**
1. Rename `magic_v2ray-*.apk` to `magic_v2ray-*.zip`.
2. Flash it in Magisk / KernelSU / APatch (Modules → Install from storage). It installs the background module and runs `pm install` for the app interface.
3. Reboot, or open the app directly.

**Option B: Standard APK Installation**
1. Install `magic_v2ray-*-arm64-v8a.apk` and open it.
2. Grant Root permissions. The app automatically checks and sets up the root daemon engine in `/data/adb/modules/magic_v2ray` (user settings are preserved in `/data/adb/magic_v2ray`).

*Note: The module's **Action** button (Magisk 27+ / KernelSU / APatch) launches the app directly, installing `app.apk` automatically if it is not present.*

---

## Deep Links for Providers

Magic V2Ray registers the schemes `magicv2ray://` and `v2rayng://`. VPN sellers can put these links on their website or in their bot messages so customers can set up with one tap. Links opened from outside the app ask the user to confirm before anything is added; only the Sync link inside a provider's own mini browser imports directly.

### Add the provider itself: `magicv2ray://add-provider`

```
magicv2ray://add-provider?url=<website>&remark=<display name>
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `url` | yes | The provider website. `https://` is added when missing; only `https` sites are accepted. |
| `remark` | no | Category name (`name` also works). Defaults to the site's hostname. If the name is taken, `(2)`, `(3)`... is appended. |

Example (URL-encode the values):

```
magicv2ray://add-provider?url=vless4gtiktok.duckdns.org&remark=Free%20Server%20VLESS
```

After the user confirms, the app creates a category, fetches the site's `<title>` and icon, and opens the site in the mini browser. `add-privider` is accepted as an alias of `add-provider`.

### Sync nodes from the provider website

Inside the mini browser, a link like the following imports the subscription into **that provider's category** (nodes are replaced, the URL is kept for Reload / auto-update) and returns to the app:

```
v2rayng://install-config?url=<URL-encoded subscription URL>
```

`v2rayng://install-sub?...` and `magicv2ray://install-config?...` / `magicv2ray://install-sub?...` work the same. Notes for website authors:

- Encode the subscription URL with `encodeURIComponent` (it must be `http(s)://`). An unencoded `&` cuts the URL.
- Put the link behind a normal button or `<a href>`; JavaScript `location.href = ...` also works.
- Links to other domains (Telegram login, payment pages...) and `tg:`, `mailto:`, `tel:` open outside the mini browser. Other schemes are blocked.

### Add a subscription without a provider category

Outside the mini browser (any browser or app), the same install links add a plain subscription after the user confirms:

```
v2rayng://install-config?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
magicv2ray://install-sub?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
```

### Example snippet for a provider website

```html
<a href="magicv2ray://add-provider?url=example.com&remark=My%20VPN">Add to Magic V2Ray</a>

<script>
  const sub = 'https://example.com/sub/USER_TOKEN';
  document.getElementById('sync').href =
    'v2rayng://install-config?url=' + encodeURIComponent(sub);
</script>
<a id="sync">Sync</a>
```

---

## Connect from other apps and websites

Any app or website can ask Magic V2Ray to route traffic through a server for a while. The server is **temporary**: your own nodes are not touched, and ending the connection brings your previously selected node back (running again if it was running before).

| How | Format |
|---|---|
| Link | `magicv2ray://connect?uri=<urlencoded uri>&name=<name>&exclude=<package,package>` |
| Link | `magicv2ray://disconnect` |
| Native app | `startActivity` with the action `com.huskydg.magicv2ray.action.CONNECT` / `.DISCONNECT` and the extras `uri`, `name`, `exclude` |
| Native app | `sendBroadcast` with the same action and extras (a connect shows a "tap to review" notification, because Android 10+ blocks opening the confirmation from the background) |

- **Local SOCKS5**: send `uri=socks://127.0.0.1:4311`. No new mechanism is needed, SOCKS nodes are already supported.
- **Display name** (status chip, notification, tile: `RUNNING - <name>`): the `name` parameter, then the calling app's name, then the remark of the uri, then its host. It is only a label, nothing is trusted because of it.
- **Loop prevention**: an app that serves the SOCKS5 port and reaches the internet through this device must be kept out of the VPN, otherwise its traffic loops back into it. Pass `exclude=<package>`; a verified native caller that serves a local address is excluded automatically. Non-root mode uses the VPN's disallowed-apps list, root mode adds the uid to the `XRAY_EXCLUDE_APP` iptables chain. Both apply only while the connection lasts, whatever the Exclude Apps switch says.
- **Ending it**: `magicv2ray://disconnect`, the notification button, the Quick Settings tile, the Stop / Disconnect buttons in the app. A reboot also ends it.
- **Security**: any website can fire a `connect` link, so every connect is confirmed, showing the full uri, the caller and the excluded apps. The caller is only "verified" when Android supplies it (never the spoofable `EXTRA_REFERRER`) and it is not a browser. A plaintext VLESS / Trojan node keeps its existing warning. The **Access permissions** tab lists the apps set to "always trust"; that option is only offered for explicit intents from verified native apps (never links or browsers) and is tied to the app's signing certificate. Even a trusted app is confirmed for plaintext nodes and for exclusions other than itself.

### Temporary settings (`overrides`)

Besides a node, `connect` can carry an `overrides` parameter that **temporarily changes** Traffic settings, Network settings, Excluded apps, Routing and Custom hosts while connected. `uri` is optional when `overrides` is present (the node you selected is then used).

- Link: `magicv2ray://connect?uri=...&overrides=<base64url of the JSON>`; intent / broadcast: the `overrides` extra is a JSON string.
- Only whitelisted keys of the right type and range are accepted, everything else is dropped (see `ExternalSession.sanitizeOverrides`). Not overridable: `exposeSocks*`, professional mode, language / theme. Routing rules only accept `outboundTag` `proxy`, `direct` or `block`.

```json
{
  "traffic": { "mux": true, "mtu": 1400, "directDns": "quic+local://223.5.5.5" },
  "network": { "networkMode": 1, "allowTether": false, "bypassIfaces": ["tailscale0"], "bypassVpnService": true },
  "exclude": { "apps": ["com.example.app"], "mode": "add", "enabled": true },
  "routing": { "domainStrategy": "IPIfNonMatch", "mode": "prepend",
               "rules": [ { "domain": "geosite:google", "outboundTag": "proxy", "remarks": "temp" } ] },
  "hosts":   { "mode": "merge", "entries": { "example.com": "1.2.3.4", "b.com": ["::1", "10.0.0.1"] } }
}
```

**Privacy: temporary settings are NEVER saved.**

| Mode | Session lives in | Never written to |
|---|---|---|
| Root | `/dev/sysctl_stubs/run/override/` (tmpfs, gone at reboot) | `/data/adb/magic_v2ray` |
| Non-root | `cache/override/` | `files/data/` (not even `config.v2.json`) |

- The engine reads `config.v2.json`, `settings.base64`, `excludelist.txt` and `bypassIface.txt` from the session directory **instead of** your file of the same name; your files are only read, never modified. Disconnecting deletes the session directory and restarts the engine on your own setup.
- The pending request is kept in memory only; the session stores metadata (name, caller, exclusion list) but neither the server nor any overridden value.
- A session **never resumes after a reboot** (root: `/dev` is wiped and the service clears the directory at start; non-root: the VpnService is `START_NOT_STICKY` and a stale session is dropped when the app opens). An app that wants it again must send `connect` after each boot. A temporary session also never changes the `enabled` flag that decides what resumes at boot.
- A request with `overrides` is **always confirmed**, even for an "always trust" app, and the dialog lists what changes.

## Building from Source

```sh
./build.sh          # Outputs APKs (arm64-v8a, x86_64, universal) -> dist/
                    # (Each APK can also be renamed to .zip to serve as a module installer)

```

**Requirements:**

* JDK 17+
* Gradle 8.7+ (8.9 recommended)
* Android SDK (`ANDROID_HOME`, target platform `android-35`)
* Android NDK (for native helpers)

The Web UI assets are located at `app/src/main/assets/webroot`. The app exposes a `window.ksu`-compatible bridge (`exec`, `listPackages`, `getPackagesInfo`).

---

## Acknowledgments & Credits

* **[Xray-core](https://github.com/XTLS/Xray-core):** The underlying engine handling next-generation proxy protocols.
* **[curl-android](https://github.com/vvb2060/curl-android):** Pre-built `curl` command-line tool and `libcurl` static library prefab.

---

## License

By using this project, you agree to the terms and conditions set forth in the license. For more details, please refer to the [LICENSE](LICENSE) file in this repository.

Essentially, you are free to use, modify, and distribute this software, provided that you include the same license and source code availability in your own project. Moreover, using its source code or replicating its features within closed-source software may constitute a violation of the license terms.