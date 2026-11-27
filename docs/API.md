# Magic V2Ray API

[English](./API.md) | [Tiếng Việt](./vi/API.md) | [Русский](./ru/API.md) | [简体中文](./zh-CN/API.md) | [繁體中文（台灣）](./zh-TW/API.md) | [繁體中文（香港）](./zh-HK/API.md)

[← README](README.md)

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

### Info and state events (API level 1)

Apps can ask what is running and be told when the proxy **disconnects** (or connects), however it happened: the app that asked, the tile, the notification button, a crash, the root service.

**1. Info** (version, supported API, current state)

```java
// A) ContentProvider: synchronous, no UI, works from any thread
Bundle b = getContentResolver().call(Uri.parse("content://com.huskydg.magicv2ray.api"), "getInfo", null, null);
String json = b.getString("json");        // full JSON below
int apiLevel = b.getInt("apiLevel");      // also: versionName, versionCode, mode, connected, known
// "getState" returns only the state. query(content://com.huskydg.magicv2ray.api/info | /state) gives one row.

// B) Ordered broadcast: the reply is the JSON (result data) + extras json / versionName / apiLevel
Intent i = new Intent("com.huskydg.magicv2ray.action.GET_INFO").setPackage("com.huskydg.magicv2ray");
sendOrderedBroadcast(i, null, new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent r) { String json = getResultData(); }   // null = not installed
}, null, Activity.RESULT_CANCELED, null, null);
```

Android 11+: add `<queries><package android:name="com.huskydg.magicv2ray"/></queries>` to your manifest.

```json
{ "app": "Magic V2Ray", "package": "com.huskydg.magicv2ray", "versionName": "v2.5-alpha", "versionCode": 192, "mode": "root",
  "api": { "level": 3,
           "actions": ["com.huskydg.magicv2ray.action.CONNECT", "...DISCONNECT", "...GET_INFO"],
           "schemes": ["magicv2ray://connect", "magicv2ray://disconnect"],
           "events": ["com.huskydg.magicv2ray.event.STATE_CHANGED"],
           "extras": ["uri", "name", "exclude", "overrides", "overridesUri", "assets"],
           "overrides": ["traffic", "network", "exclude", "routing", "hosts", "assets"],
           "provider": { "authority": "com.huskydg.magicv2ray.api", "methods": ["getInfo", "getState", "getNodes", "getActiveNode", "getNodeAccess"], "uris": ["..."] },
           "features": ["connect", "disconnect", "overrides", "exclude", "info", "state_events", "state_provider", "nodes", "node_access_request", "connect_freedom", "connect_chain", "connect_nodeid", "overrides_unlimited", "overrides_uri", "custom_assets"] },
  "state": { "connected": true, "known": true, "mode": "root",
             "session": { "active": true, "name": "My server", "caller": "com.example.app", "startedAt": 1731670000000 } } }
```

`api.level` grows when something is added: test for it (or for a name in `features`) instead of the app version. No server address and no settings are ever included.

**2. State events**: broadcast `com.huskydg.magicv2ray.event.STATE_CHANGED`

| Extra | Value |
|---|---|
| `state` / `connected` | `"connected"` / `"disconnected"` and `true` / `false` |
| `reason` | `started`, `session_started`, `session_ended` (the external session ended), `stopped`, `error`, `crashed` |
| `session`, `sessionName`, `caller` | an external session is involved / its name / the package that started it |
| `mode`, `time`, `seq` | `root` / `nonroot`, epoch ms, counter (per process of Magic V2Ray) |

```java
registerReceiver(new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
        if (!i.getBooleanExtra("connected", false)) onMagicV2RayDisconnected(i.getStringExtra("reason"));
    }
}, new IntentFilter("com.huskydg.magicv2ray.event.STATE_CHANGED"), Context.RECEIVER_EXPORTED);
```

- Receivers registered at run time get it as a normal broadcast. The app that owns the session also gets an explicit copy, so a run-time receiver may see the same event twice: drop duplicates by `seq` + `time`. The app that owns the external session also gets it **explicitly**, so a receiver declared in its manifest works (Android 14+ for broadcast-started sessions only when the caller is known, otherwise use a run-time receiver or the observer below).
- Observer instead of a receiver: `registerContentObserver(Uri.parse("content://com.huskydg.magicv2ray.api/state"), false, observer)`, then read `getState`.
- A disconnect is announced after 2 s if the engine is still down, so a restart or reload does not look like a disconnect. Ending a session is announced at once.
- Root mode: the service tells the app through `am broadcast ... ENGINE_CHANGED`, which makes it re-read the real status. The same broadcast from anyone else can do no more than that.
- If the Magic V2Ray process is killed in non-root mode the VPN dies with it and nothing can be sent: poll `getState` when it matters.

### Nodes and special connect uris (API level 2, apps only)

`api.level` is `2` when the features `nodes`, `node_access_request`, `connect_freedom`, `connect_chain` and `connect_nodeid` are present.

**1. Permission.** An app can only read nodes after the user allows it. The caller is the package Android reports for the call itself, so a website, a link or a broadcast can never read nodes and nothing can pretend to be another app. The user chooses **what** the app sees and **for how long**:

| Choice | Meaning |
|---|---|
| Anonymous info (default) | name, protocol and an id; the node is `nodeid://<id>` instead of `vless://...`. No server, port or credentials. |
| Full node info | the real node links (`vless://...`), servers and credentials included. |
| Allow once | valid for 10 minutes, memory only. |
| Always allow | kept until revoked in the **Access permissions** tab; tied to the app's signing certificate. |
| Cancel | nothing is granted. |

```java
// 1) ask. status: ok | permission_required | unknown_caller | unavailable
Uri api = Uri.parse("content://com.huskydg.magicv2ray.api");
Bundle b = getContentResolver().call(api, "getNodes", null, null);
if ("permission_required".equals(b.getString("status"))) {
    // 2) the permission dialog: MUST be startActivityForResult (that is how the caller is verified)
    Intent i = new Intent("com.huskydg.magicv2ray.action.REQUEST_NODE_ACCESS").setPackage("com.huskydg.magicv2ray");
    i.putExtra("level", "full");          // optional: what the app would like; the dialog still starts on "anonymous"
    startActivityForResult(i, 42);        // result: granted, level ("anonymous" | "full"), persistent
}
// 3) read (after RESULT_OK, or any time later)
String json = getContentResolver().call(api, "getNodes", null, null).getString("json");
```

| Method | Returns |
|---|---|
| `getNodeAccess` | `granted`, `level`, `persistent`; never prompts |
| `getNodes` | `json` = `{level, activeId, nodes:[{id, name, protocol, uri, (full only: category, address, port)}]}`, plus `count`, `activeId` |
| `getActiveNode` | `json` = `{connected, known, source, session?, node}`, plus `connected`, `source` |

`source` is `session` (an app set the node for a while), `selected` (the user's own pick) or `direct` (the built-in no-proxy node). An anonymous reader never sees the uri of a node an app set that is not one of the user's own: it comes back as `<scheme>://hidden`. A chain is always shown as `chain://localhost/?hopList=...`.

**2. Connect uris.** `uri` of a connect request (link, intent or broadcast) may also be:

| uri | Meaning |
|---|---|
| `freedom://localhost` | the built-in "no proxy" node: xray as a pure router, every routing rule still applies |
| `chain://localhost/?hopList=<urlencode(JSON array of node uris)>` | a proxy chain of 2 to 16 hops: `["vless://...", "vmess://...", "socks://...", ...]`. The first item is dialed from the device, every next hop is tunneled through the previous one, the last is the exit. |
| `nodeid://<id>` | one of the user's own nodes, alone or as a hop of a chain. Only for a verified app that was allowed to read nodes (either level), so an anonymous app can still use a node it never sees. |

The usual confirmation applies: it shows the chain's protocols (or "direct"), and the first hop decides whether the app has to be kept out of the VPN (`freedom://` has no server to loop back to).

### Using it from Termux (or any shell)

A shell cannot register a receiver or use the provider without root, so it uses `am`. These commands follow from how the API works; the info and poll commands depend on Termux's `am` printing the broadcast result like the system `am`.

```sh
# Info: `am broadcast` is an ordered broadcast and prints the reply as data="<json>"
am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray
# With root, the provider can be called directly:
su -c 'content call --uri content://com.huskydg.magicv2ray.api --method getInfo'

# Connect / disconnect (the user still confirms a connect)
am start -a com.huskydg.magicv2ray.action.CONNECT -p com.huskydg.magicv2ray \
  --es uri 'socks://127.0.0.1:5353' --es name 'Termux' --es exclude com.termux
am start -a com.huskydg.magicv2ray.action.DISCONNECT -p com.huskydg.magicv2ray
```

A shell cannot receive `STATE_CHANGED`, so poll the info instead. This script checks the API level, then reports every change until Magic V2Ray disconnects:

```sh
#!/data/data/com.termux/files/usr/bin/sh
info() { am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray 2>/dev/null \
         | sed -n 's/.*data="\(.*\)".*/\1/p'; }
J=$(info)
[ -n "$J" ] || { echo "Magic V2Ray is not installed or too old (no API level 1)"; exit 1; }
echo "$J" | jq -r '"Magic V2Ray \(.versionName), API \(.api.level), \(.mode)"'
last=
while sleep 2; do
  c=$(info | jq -r '.state.connected')
  [ "$c" = "$last" ] || { echo "connected=$c"; last=$c; }
  [ "$c" = "false" ] && break
done
echo "Magic V2Ray disconnected"
```

Notes: `am start` from the background needs the "Display over other apps" permission for Termux on Android 10+. Whether Magic V2Ray can identify Termux as the caller (and so offer "Always trust") depends on what Android reports for `am`; an unknown caller is always confirmed. Install `jq` with `pkg install jq`.

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
| Root | `/mnt/runtime/magicv2ray/run/override/` (tmpfs, gone at reboot) | `/data/adb/magic_v2ray` |
| Non-root | `cache/override/` | `files/data/` (not even `config.v2.json`) |

- The engine reads `config.v2.json`, `settings.base64`, `excludelist.txt` and `bypassIface.txt` from the session directory **instead of** your file of the same name; your files are only read, never modified. Disconnecting deletes the session directory and restarts the engine on your own setup.
- The pending request is kept in memory only; the session stores metadata (name, caller, exclusion list) but neither the server nor any overridden value.
- A session **never resumes after a reboot** (root: `/mnt/runtime` is wiped and the service clears the directory at start; non-root: the VpnService is `START_NOT_STICKY` and a stale session is dropped when the app opens). An app that wants it again must send `connect` after each boot. A temporary session also never changes the `enabled` flag that decides what resumes at boot.
- A request with `overrides` is confirmed (the dialog lists what changes). An app set to "always trust" is **not asked again while it sends the same `overrides` it was last allowed with**. If they differ (new or changed), the dialog comes back saying the app wants to apply NEW settings. **Always allow** remembers the new set; **Allow once** does not. Only a SHA-256 fingerprint of the overrides is stored, never their values. Links and browsers can never be trusted.

---

### Unlimited rules and hosts, custom `.dat` files (API level 3, apps only)

`api.level` is `3` when the features `overrides_unlimited`, `overrides_uri` and `custom_assets` are present. Everything above keeps working unchanged.

**1. No limit on rules and hosts.** `routing.rules` and `hosts.entries` accept any number of entries, each host any number of addresses, and a rule's `domain` / `ip` may be a comma separated string **or a JSON array of strings** (a long list is simply written as an array). What remains is a ceiling on bytes, because an intent cannot carry more than about 1 MB and a phone has limited memory:

| Where the overrides travel | Ceiling |
|---|---|
| `overrides` extra / deep link | 256 KB of JSON (same as before, but counts no longer matter) |
| `overridesUri` (file) | 64 MB of JSON |

The ceilings are reported in `getInfo` under `api.limits`. Only entries that are not valid are dropped (a rule with an `outboundTag` other than `proxy` / `direct` / `block`, a malformed domain or address), never because the list is long.

**2. `overridesUri`: the overrides in a file.** A `content://` URI of a file that holds the same JSON as `overrides`. When it is present it **replaces** the `overrides` extra. Links and browsers cannot use it.

**3. `assets`: custom `.dat` databases.** `geoip.dat`, `geosite.dat` and any other file reachable through `ext:<file>.dat:<tag>` can be supplied for the length of the connection. The extra is a JSON array `[{"name": "geoip.dat", "uri": "content://..."}, ...]`.

- `name` is a bare file name ending in `.dat` (letters, digits, `.`, `_`, `-`). Files you do **not** send keep working: the engine sees your `assets` first and the user's own databases for every other name, so sending only `geosite.dat` leaves `geoip.dat` as it was.
- Each file is checked when the request arrives (it must look like a geo database, not an HTML page or a zip). At most 32 files, 256 MB each and 512 MB together; in root mode they live in RAM for the length of the session.
- The files are replaced, not merged: your `geosite.dat` is used **instead of** the user's, for every `geosite:` rule, including the user's own routing rules.

```
{ "uri": "freedom://localhost", "assets": [ {"name": "geosite.dat", "uri": "content://..."} ] }
```

**Granting the files.** Only `content://` URIs are accepted, and each one must also be in the intent's `ClipData` with `FLAG_GRANT_READ_URI_PERMISSION`; a URI that was not granted this way is refused. Use `startActivity` or `sendBroadcast` with the action `CONNECT`; a shell (Termux, `adb`) cannot grant URIs, so it cannot send files, and a deep link cannot either.

```java
// Files you own, shared through your FileProvider (or any ContentProvider that honours URI grants)
Uri ov    = FileProvider.getUriForFile(this, getPackageName() + ".files", overridesJsonFile);
Uri geoip = FileProvider.getUriForFile(this, getPackageName() + ".files", geoipDatFile);
Uri site  = FileProvider.getUriForFile(this, getPackageName() + ".files", geositeDatFile);

JSONArray assets = new JSONArray()
    .put(new JSONObject().put("name", "geoip.dat").put("uri", geoip.toString()))
    .put(new JSONObject().put("name", "geosite.dat").put("uri", site.toString()));

Intent i = new Intent("com.huskydg.magicv2ray.action.CONNECT").setPackage("com.huskydg.magicv2ray");
i.putExtra("uri", "freedom://localhost");                 // optional, as before
i.putExtra("overridesUri", ov.toString());                // the same JSON as `overrides`, from a file
i.putExtra("assets", assets.toString());
ClipData clip = ClipData.newRawUri("mv", ov);              // EVERY uri used above must be in the ClipData
clip.addItem(new ClipData.Item(geoip));
clip.addItem(new ClipData.Item(site));
i.setClipData(clip);
i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
startActivity(i);                                         // or sendBroadcast(i): the request then waits for a tap
```

**Privacy and consent.**

- Custom databases change what every `geoip:` / `geosite:` / `ext:` rule matches, so the confirmation dialog says so in plain words and lists each file with its size and the start of its SHA-256. Nothing is applied without that answer, except for an app set to "always trust" that sends the very same overrides and files again (the fingerprint of the files is part of the check; a changed file asks again).
- A request waits at most 10 minutes. While it waits the files sit in the app's private cache, checked and hashed; **deny, an error or a timeout deletes them at once**.
- After the user agrees they are moved into the session directory (root: tmpfs under `/mnt/runtime`, gone at reboot; non-root: the cache directory) and are **never** written to the data directory or to the user's `location_assets`. Disconnecting, a reboot or a crash deletes them and the engine goes back to the user's own databases.
- If a file cannot be read or fails the check, nothing is connected and the user sees why.

**Test for it:** `getInfo` -> `api.level >= 3`, or `"custom_assets"` in `api.features`.
