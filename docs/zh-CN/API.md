# Magic V2Ray API

[English](../API.md) | [Tiếng Việt](../vi/API.md) | [Русский](../ru/API.md) | [简体中文](./API.md) | [繁體中文（台灣）](../zh-TW/API.md) | [繁體中文（香港）](../zh-HK/API.md)

[← README](README.md)

---

## 服务商深度链接

Magic V2Ray 注册了 `magicv2ray://` 和 `v2rayng://` 两种 scheme。VPN 商家可以把这些链接放在网站或机器人消息中，让客户一键完成设置。从应用外部打开的链接在添加任何内容之前都会请用户确认；只有服务商自己的迷你浏览器内的“同步”链接才会直接导入。

### 添加服务商本身：`magicv2ray://add-provider`

```
magicv2ray://add-provider?url=<网站>&remark=<显示名称>
```

| 参数 | 是否必填 | 说明 |
|------|----------|------|
| `url` | 是 | 服务商网站。缺少时会自动补全 `https://`；仅接受 `https` 网站。 |
| `remark` | 否 | 分类名称（也可使用 `name`）。默认为站点主机名。如果名称已存在，会追加 `(2)`、`(3)`…… |

示例（请对值进行 URL 编码）：

```
magicv2ray://add-provider?url=vless4gtiktok.duckdns.org&remark=Free%20Server%20VLESS
```

用户确认后，应用会创建一个分类，获取站点的 `<title>` 和图标，并在迷你浏览器中打开该站点。`add-privider` 可作为 `add-provider` 的别名使用。

### 从服务商网站同步节点

在迷你浏览器内，如下链接会将订阅导入到**该服务商的分类**（节点会被替换，URL 会保留以便刷新 / 自动更新），并返回应用：

```
v2rayng://install-config?url=<经 URL 编码的订阅链接>
```

`v2rayng://install-sub?...` 以及 `magicv2ray://install-config?...` / `magicv2ray://install-sub?...` 的作用相同。给网站作者的说明：

- 请用 `encodeURIComponent` 对订阅链接进行编码（必须是 `http(s)://`）。未编码的 `&` 会截断 URL。
- 把链接放在普通按钮或 `<a href>` 后面即可；用 JavaScript 的 `location.href = ...` 也可以。
- 指向其他域名的链接（Telegram 登录、支付页面等）以及 `tg:`、`mailto:`、`tel:` 会在迷你浏览器之外打开。其他 scheme 会被拦截。

### 不经服务商分类添加订阅

在迷你浏览器之外（任意浏览器或应用），同样的 install 链接在用户确认后会添加一个普通订阅：

```
v2rayng://install-config?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
magicv2ray://install-sub?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
```

### 服务商网站示例代码

```html
<a href="magicv2ray://add-provider?url=example.com&remark=My%20VPN">添加到 Magic V2Ray</a>

<script>
  const sub = 'https://example.com/sub/USER_TOKEN';
  document.getElementById('sync').href =
    'v2rayng://install-config?url=' + encodeURIComponent(sub);
</script>
<a id="sync">同步</a>
```

---

## 从其他应用和网站连接

任何应用或网站都可以请求 Magic V2Ray 在一段时间内让流量通过某个服务器。该服务器是**临时**的：你自己的节点不会被改动，结束连接后会恢复你之前选择的节点（如果之前在运行，则会重新运行）。

| 方式 | 格式 |
|---|---|
| 链接 | `magicv2ray://connect?uri=<urlencoded uri>&name=<名称>&exclude=<package,package>` |
| 链接 | `magicv2ray://disconnect` |
| 原生应用 | 使用 `startActivity`，action 为 `com.huskydg.magicv2ray.action.CONNECT` / `.DISCONNECT`，extras 为 `uri`、`name`、`exclude` |
| 原生应用 | 使用 `sendBroadcast`，action 和 extras 相同（connect 会显示一条“点按以查看”的通知，因为 Android 10+ 禁止从后台打开确认窗口） |

- **本地 SOCKS5**：发送 `uri=socks://127.0.0.1:4311`。无需任何新机制，SOCKS 节点本来就受支持。
- **显示名称**（状态标签、通知、磁贴：`RUNNING - <名称>`）：依次取 `name` 参数、调用方应用名称、uri 的 remark、其主机名。它只是一个标签，不会因此信任任何对象。
- **防止回环**：提供 SOCKS5 端口并通过本设备访问互联网的应用必须被排除在 VPN 之外，否则它的流量会回流到自身。请传入 `exclude=<package>`；已验证的、提供本地地址服务的原生调用方会被自动排除。非 Root 模式使用 VPN 的 disallowed-apps 列表，Root 模式则将 uid 加入 iptables 的 `XRAY_EXCLUDE_APP` 链。两者都只在连接持续期间生效，不受“排除应用”开关影响。
- **结束连接**：`magicv2ray://disconnect`、通知中的按钮、快速设置磁贴、应用内的“停止 / 断开连接”按钮。重启设备也会结束连接。
- **安全**：任何网站都可以触发 `connect` 链接，因此每次连接都需确认，并显示完整的 uri、调用方和被排除的应用。只有当调用方信息由 Android 提供（绝不使用可伪造的 `EXTRA_REFERRER`）且不是浏览器时，才算“已验证”。明文 VLESS / Trojan 节点仍保留原有警告。**访问权限**标签页列出了设为“始终信任”的应用；该选项仅对来自已验证原生应用的显式 intent 提供（链接和浏览器永远不行），并绑定到应用的签名证书。即使是受信任的应用，遇到明文节点以及排除自身以外的应用时仍需确认。

### 信息与状态事件（API level 1）

应用可以查询当前运行的内容，并在代理**断开**（或连接）时收到通知，无论是由什么原因引起：发起请求的应用、磁贴、通知按钮、崩溃，还是 Root 服务。

**1. Info**（版本、支持的 API、当前状态）

```java
// A) ContentProvider：同步，无界面，可在任意线程调用
Bundle b = getContentResolver().call(Uri.parse("content://com.huskydg.magicv2ray.api"), "getInfo", null, null);
String json = b.getString("json");        // 完整 JSON，见下文
int apiLevel = b.getInt("apiLevel");      // 另有：versionName、versionCode、mode、connected、known
// "getState" 仅返回状态。query(content://com.huskydg.magicv2ray.api/info | /state) 返回一行数据。

// B) Ordered broadcast：回复为 JSON（result data）+ extras json / versionName / apiLevel
Intent i = new Intent("com.huskydg.magicv2ray.action.GET_INFO").setPackage("com.huskydg.magicv2ray");
sendOrderedBroadcast(i, null, new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent r) { String json = getResultData(); }   // null = 未安装
}, null, Activity.RESULT_CANCELED, null, null);
```

Android 11+：请在调用方应用的 manifest 中添加 `<queries><package android:name="com.huskydg.magicv2ray"/></queries>`。

```json
{ "app": "Magic V2Ray", "package": "com.huskydg.magicv2ray", "versionName": "v2.5-alpha", "versionCode": 192, "mode": "root",
  "api": { "level": 3,
           "actions": ["com.huskydg.magicv2ray.action.CONNECT", "...DISCONNECT", "...GET_INFO"],
           "schemes": ["magicv2ray://connect", "magicv2ray://disconnect"],
           "events": ["com.huskydg.magicv2ray.event.STATE_CHANGED"],
           "extras": ["uri", "name", "exclude", "overrides", "overridesUri", "assets"],
           "overrides": ["traffic", "network", "exclude", "routing", "hosts", "assets"],
           "provider": { "authority": "com.huskydg.magicv2ray.api", "methods": ["getInfo", "getState", "getNodes", "getActiveNode", "getNodeAccess"], "uris": ["..."] },
           "features": ["connect", "disconnect", "overrides", "exclude", "info", "state_events", "state_provider", "nodes", "node_access_request", "connect_freedom", "connect_chain", "connect_nodeid", "overrides_unlimited", "overrides_uri", "custom_assets", "connect_policygroup"] },
  "state": { "connected": true, "known": true, "mode": "root",
             "session": { "active": true, "name": "My server", "caller": "com.example.app", "startedAt": 1731670000000 } } }
```

| 字段 | 含义 |
|---|---|
| `versionName`、`versionCode` | 当前安装的 Magic V2Ray 版本 |
| `mode` | `root` 或 `nonroot` |
| `api.level` | 每当 API 新增了其他应用需要检测的内容时递增 |
| `api.actions`、`schemes`、`extras`、`overrides` | 支持的 action、深度链接、extras 以及 `overrides` 分组 |
| `api.events`、`api.provider` | 发出的事件以及 provider 的访问方式 |
| `api.features` | 功能列表，可用于逐项检测 |
| `state.connected` | 代理是否正在运行 |
| `state.known` | Root 模式下尚无法读取状态时（未授予 Root、服务未运行）为 `false`；此时 `connected` 只是默认的 `false` |
| `state.session` | 当前的外部连接会话（名称、调用方、开始时间）；不含服务器地址和设置 |

请检测 `api.level` 或 `features` 中的名称，而不是比较应用版本号。返回的数据绝不包含服务器地址或设置。

**2. 状态事件**：广播 `com.huskydg.magicv2ray.event.STATE_CHANGED`

| Extra | 值 |
|---|---|
| `state` / `connected` | `"connected"` / `"disconnected"` 以及 `true` / `false` |
| `reason` | `started`、`session_started`、`session_ended`（外部会话已结束）、`stopped`（正常停止）、`error`（引擎出错、VPN 权限丢失）、`crashed` |
| `session`、`sessionName`、`caller` | 是否涉及外部会话 / 会话名称 / 发起会话的应用包名 |
| `mode` | `root` 或 `nonroot` |
| `time`、`seq` | 时间（epoch ms）和序号，在每个 Magic V2Ray 进程内递增 |

```java
registerReceiver(new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
        if (!i.getBooleanExtra("connected", false)) {
            onMagicV2RayDisconnected(i.getStringExtra("reason"));
        }
    }
}, new IntentFilter("com.huskydg.magicv2ray.event.STATE_CHANGED"), Context.RECEIVER_EXPORTED);
```

- **谁能收到**：任何应用在运行时注册的 receiver（`registerReceiver`）都会将其作为普通广播收到；拥有该会话的应用可能会收到同一事件两次，请按 `seq` + `time` 去重。发起外部会话的应用还会被**显式**投递，因此在 manifest 中声明的 receiver 也能收到。对于在 Android 14 以下通过 `sendBroadcast` 发起的会话，Magic V2Ray 无法得知调用方是哪个应用，请使用运行时注册的 receiver 或下面的 observer。
- **用 observer 代替 receiver**：`registerContentObserver(Uri.parse("content://com.huskydg.magicv2ray.api/state"), false, observer)`，被回调时再读取 `getState`。
- **通知时机**：只有在 2 秒后引擎仍处于关闭状态时才会通知断开，因此重启或重新加载（切换节点、更改设置）不会被视为断开。外部会话结束（`session_ended`）会立即通知。如果启动失败且此前并未在运行，则不产生事件。
- **Root 模式**：Root 服务通过 `am broadcast ... ENGINE_CHANGED` 通知应用，应用重新读取真实状态后再发出事件。该广播的内容不被信任，因此其他应用伪造发送也只会让应用重新检查状态。这样同样能捕获崩溃以及来自 shell 的停止命令。
- **限制**：在非 Root 模式下，如果 Magic V2Ray 进程被系统杀掉，VPN 也会随之终止，无法发送任何事件。若此事很重要，请定期调用 `getState`。
- **隐私**：事件和 info 只包含状态，不含服务器地址、节点 uri 或设置。

### 节点与特殊 connect uri（API level 2，仅限应用）

当存在 features `nodes`、`node_access_request`、`connect_freedom`、`connect_chain` 和 `connect_nodeid` 时，`api.level` 为 `2`。

**1. 权限。** 应用必须经用户允许后才能读取节点。调用者是 Android 为该调用本身报告的包名，因此网站、链接或广播永远无法读取节点，也没有任何东西能冒充其他应用。用户决定应用能看到**什么**以及**多长时间**：

| 选项 | 含义 |
|---|---|
| 匿名信息（默认） | 名称、协议和 id；节点显示为 `nodeid://<id>` 而不是 `vless://...`。没有服务器、端口和凭据。 |
| 完整节点信息 | 真实的节点链接（`vless://...`），包含服务器和凭据。 |
| 允许一次 | 有效期 10 分钟，仅保存在内存中。 |
| 始终允许 | 保留到在“访问权限”标签页中撤销；与应用的签名证书绑定。 |
| 取消 | 不授予任何权限。 |

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

| 方法 | 返回 |
|---|---|
| `getNodeAccess` | `granted`、`level`、`persistent`；从不弹出对话框 |
| `getNodes` | `json` = `{level, activeId, nodes:[{id, name, protocol, uri, (仅完整级别：category, address, port)}]}`，另有 `count`、`activeId` |
| `getActiveNode` | `json` = `{connected, known, source, session?, node}`，另有 `connected`、`source` |

`source` 为 `session`（应用临时设置的节点）、`selected`（用户自己选的节点）或 `direct`（内置的无代理节点）。匿名读取者永远看不到由应用设置、且不属于用户自己节点的 uri：它会返回为 `<scheme>://hidden`。链始终显示为 `chain://localhost/?hopList=...`。

**2. Connect uri。** connect 请求（链接、intent 或广播）的 `uri` 还可以是：

| uri | 含义 |
|---|---|
| `freedom://localhost` | 内置的“无代理”节点：xray 仅作为路由器，所有路由规则仍然生效 |
| `chain://localhost/?hopList=<urlencode(JSON array of node uris)>` | 2 到 16 跳的代理链：`["vless://...", "vmess://...", "socks://...", ...]`。第一项由设备直接拨出，之后每一跳都通过前一跳的隧道，最后一项是出口。 |
| `nodeid://<id>` | 用户自己的某个节点，可单独使用，也可作为链中的一跳。仅限已验证且获准读取节点（任一级别）的应用，因此匿名级别的应用也能使用它从未见过的节点。 |

照常需要确认：对话框会显示链的协议（或“直连”），由第一跳决定是否要让该应用绕过 VPN（`freedom://` 没有可供流量回环的服务器）。

### 在 Termux（或任意 shell）中使用

shell 无法注册 receiver，在没有 Root 时也无法使用 provider，因此需要使用 `am`。下列命令是根据 API 的工作方式推导出来的；其中 info 命令和轮询循环取决于 Termux 的 `am` 是否像系统 `am` 一样打印广播结果。

```sh
# Info：`am broadcast` 是 ordered broadcast，会以 data="<json>" 的形式输出回复
am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray
# 有 Root 时，可以直接调用 provider：
su -c 'content call --uri content://com.huskydg.magicv2ray.api --method getInfo'

# Connect / disconnect（connect 仍需用户确认）
am start -a com.huskydg.magicv2ray.action.CONNECT -p com.huskydg.magicv2ray \
  --es uri 'socks://127.0.0.1:5353' --es name 'Termux' --es exclude com.termux
am start -a com.huskydg.magicv2ray.action.DISCONNECT -p com.huskydg.magicv2ray
```

shell 收不到 `STATE_CHANGED`，所以请轮询 info。下面的脚本会检查 API level，然后在每次状态变化时报告，直到 Magic V2Ray 断开：

```sh
#!/data/data/com.termux/files/usr/bin/sh
info() { am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray 2>/dev/null \
         | sed -n 's/.*data="\(.*\)".*/\1/p'; }
J=$(info)
[ -n "$J" ] || { echo "Magic V2Ray 未安装或版本过旧（没有 API level 1）"; exit 1; }
echo "$J" | jq -r '"Magic V2Ray \(.versionName), API \(.api.level), \(.mode)"'
last=
while sleep 2; do
  c=$(info | jq -r '.state.connected')
  [ "$c" = "$last" ] || { echo "connected=$c"; last=$c; }
  [ "$c" = "false" ] && break
done
echo "Magic V2Ray 已断开"
```

注意：从 Android 10 起，后台执行 `am start` 需要为 Termux 授予“显示在其他应用上层”权限。Magic V2Ray 能否将 Termux 识别为调用方（从而提供“始终信任”），取决于 Android 为 `am` 报告的信息；未知调用方始终需要确认。请使用 `pkg install jq` 安装 `jq`。

### 临时设置（`overrides`）

除节点外，`connect` 还可以携带 `overrides` 参数，在连接期间**临时更改**流量设置、网络设置、排除应用、路由和自定义 hosts。存在 `overrides` 时，`uri` 为可选（此时使用你当前选中的节点）。

- 链接：`magicv2ray://connect?uri=...&overrides=<JSON 的 base64url>`；intent / broadcast：`overrides` extra 为 JSON 字符串。
- 只接受白名单内、类型和取值范围正确的键，其余一律丢弃（参见 `ExternalSession.sanitizeOverrides`）。不可覆盖：`exposeSocks*`、专业模式、语言 / 主题。路由规则的 `outboundTag` 仅接受 `proxy`、`direct` 或 `block`。

```json
{
  "traffic": { "mux": true, "mtu": 1400, "directDns": "quic+local://223.5.5.5" },
  "network": { "networkMode": 1, "allowTether": false, "bypassIfaces": ["tailscale0"], "bypassVpnService": true },
  "exclude": { "apps": ["com.example.app"], "mode": "add", "enabled": true },
  "routing": { "domainStrategy": "IPIfNonMatch", "mode": "prepend",
               "rules": [ { "domain": "geosite:google", "outboundTag": "proxy", "remarks": "temp" },
                          { "process": ["com.example.app"], "outboundTag": "direct", "remarks": "app direct" } ] },
  "hosts":   { "mode": "merge", "entries": { "example.com": "1.2.3.4", "b.com": ["::1", "10.0.0.1"] } }
}
```

**隐私：临时设置绝不会被保存。**

| 模式 | 会话存放位置 | 绝不写入 |
|---|---|---|
| Root | `/mnt/runtime/magicv2ray/run/override/`（tmpfs，重启后消失） | `/data/adb/magic_v2ray` |
| 非 Root | `cache/override/` | `files/data/`（连 `config.v2.json` 也不会） |

- 引擎会从会话目录读取 `config.v2.json`、`settings.base64`、`excludelist.txt` 和 `bypassIface.txt`，**取代**你的同名文件；你的文件只会被读取，不会被修改。断开连接即删除会话目录，并以你自己的配置重启引擎。
- 待确认的请求只保存在内存中；会话仅保存元数据（名称、调用方、排除列表），既不保存服务器，也不保存任何被覆盖的值。
- 会话**重启后不会恢复**（Root：`/mnt/runtime` 会被清空，且服务在启动时清理该目录；非 Root：VpnService 为 `START_NOT_STICKY`，应用打开时会丢弃过期会话）。需要再次使用的应用必须在每次开机后重新发送 `connect`。临时会话也不会改变决定开机时恢复什么的 `enabled` 标志。
- 带有 `overrides` 的请求需要确认（对话框会列出将更改的内容）。选择了**始终允许**的应用，在更改设置时**不会再次询问**：新增或已更改的 `overrides` 会直接应用。链接和浏览器永远不能被信任。每个应用的请求和所做的决定都会记录在它的活动日志中（访问权限标签页，应用卡片上的日志按钮）。

---

### 不限数量的规则与 hosts、自定义 `.dat` 文件（API level 3，仅限应用）

当存在功能 `overrides_unlimited`、`overrides_uri` 和 `custom_assets` 时，`api.level` 为 `3`。以上内容保持不变。

**1. 规则与 hosts 不限数量。** `routing.rules` 和 `hosts.entries` 可包含任意数量的条目，每个 host 可有任意数量的地址，规则的 `domain` / `ip` 可以是逗号分隔的字符串，**也可以是字符串的 JSON 数组**（长列表直接写成数组即可）。只剩字节数上限，因为 intent 最多只能携带约 1 MB，而手机内存有限：

| `overrides` 的传递方式 | 上限 |
|---|---|
| `overrides` extra / 深度链接 | 256 KB JSON（与之前相同，但条目数量不再重要） |
| `overridesUri`（文件） | 64 MB JSON |

这些上限会在 `getInfo` 的 `api.limits` 中报告。只有无效的条目才会被丢弃（`outboundTag` 不是 `proxy` / `direct` / `block` 的规则、格式错误的域名或地址），绝不会因为列表太长而丢弃。

**2. `overridesUri`：把 overrides 放进文件。** 指向文件的 `content://` URI，文件内容与 `overrides` 的 JSON 相同。提供时它会**取代** `overrides` extra。链接和浏览器无法使用。

**3. `assets`：自定义 `.dat` 数据库。** `geoip.dat`、`geosite.dat` 以及通过 `ext:<文件>.dat:<标签>` 引用的任何文件，都可以在连接期间提供。该 extra 是 JSON 数组 `[{"name": "geoip.dat", "uri": "content://..."}, ...]`。

- `name` 是以 `.dat` 结尾的纯文件名（字母、数字、`.`、`_`、`-`）。您**没有**发送的文件照常工作：引擎优先使用您的 `assets`，其余名称使用用户自己的数据库，所以只发送 `geosite.dat` 时 `geoip.dat` 保持不变。
- 每个文件在请求到达时都会检查（必须像 geo 数据库，而不是 HTML 页面或 zip）。最多 32 个文件，每个 256 MB，合计 512 MB；root 模式下它们在会话期间驻留于内存。
- 文件是替换而非合并：您的 `geosite.dat` 会**取代**用户的，用于所有 `geosite:` 规则，包括用户自己的路由规则。

```
{ "uri": "freedom://localhost", "assets": [ {"name": "geosite.dat", "uri": "content://..."} ] }
```

**授予文件访问权。** 只接受 `content://` URI，且每个 URI 还必须出现在 intent 的 `ClipData` 中并带有 `FLAG_GRANT_READ_URI_PERMISSION`；未以此方式授予的 URI 会被拒绝。请使用动作为 `CONNECT` 的 `startActivity` 或 `sendBroadcast`；shell（Termux、`adb`）无法授予 URI，因此无法发送文件，深度链接同样不行。

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

**隐私与同意。**

- 自定义数据库会改变每条 `geoip:` / `geosite:` / `ext:` 规则的匹配结果，因此确认对话框会明确说明，并列出每个文件的大小和 SHA-256 前缀。未经用户同意不会应用任何内容，唯一例外是设为“始终允许”的应用，无论发送什么 overrides 和文件都不会再次询问。
- 请求最多等待 10 分钟。等待期间文件位于应用私有缓存中，已检查并计算哈希；**拒绝、出错或超时都会立即删除它们**。
- 用户同意后，它们被移入会话目录（root：`/mnt/runtime` 下的 tmpfs，重启即消失；非 root：缓存目录），**绝不会**写入用户的数据目录或 `location_assets`。断开连接、重启或崩溃都会删除它们，引擎回到用户自己的数据库。
- 如果文件无法读取或未通过检查，则不会连接任何内容，并向用户说明原因。

**检测方式：** `getInfo` -> `api.level >= 3`，或 `api.features` 中包含 `"custom_assets"`。

---

### 以策略组作为节点：`policygroup://`（API level 3）

功能 `connect_policygroup`。连接请求的 `uri` 可以是**由请求自身定义的策略组**：Xray 用其负载均衡策略在多个节点之间分配所有流量。用法与 `chain://` 类似，但这些节点是互为备选，而不是依次串联的跳。

```
policygroup://localhost/?type=<策略>&members=<urlencode(节点 uri 的 JSON 数组)>
```

| 部分 | 含义 |
|---|---|
| `type` | `random`（默认）、`roundRobin`、`leastPing`、`leastLoad` |
| `members` | 2 到 256 个节点 URI（`vless://`、`trojan://`、`ss://` 等）或 `nodeid://<id>` |

- 成员不能是 `chain://`、`freedom://` 或另一个策略组。`nodeid://` 成员遵循与链相同的规则：只有经过验证且获准访问节点的应用才能使用；用户的真实节点会显示在对话框中，但绝不会返回给应用。
- `leastPing` 和 `leastLoad` 会启用 Xray 的 observatory（探测 `https://www.google.com/generate_204`）；如果所有成员都被判定为不可用，则使用第一个成员作为后备。
- 策略组仅在本次会话中存在，不会被保存，不会出现在用户的节点或策略组中，也不会与它们冲突。确认对话框会显示策略和节点数；未加密的 VLESS 或 Trojan 成员会触发常规警告。`overrides`、`exclude` 和 `assets` 的行为与其他节点相同。
- “应用提供本地 SOCKS5”这一规则取指向本机的那个成员，因此调用方应用会自动被排除在 VPN 之外。

```java
JSONArray members = new JSONArray()
    .put("vless://...@a.example.com:443?security=reality&...#A")
    .put("trojan://...@b.example.com:443?security=tls#B")
    .put("nodeid://3f9c2a");                      // the user's own node: needs node access (see Nodes)

String uri = "policygroup://localhost/?type=leastPing&members=" + Uri.encode(members.toString());

Intent i = new Intent("com.huskydg.magicv2ray.action.CONNECT").setPackage("com.huskydg.magicv2ray");
i.putExtra("uri", uri);
startActivity(i);
```

可通过 `getInfo` 读取：`api.connectUris` 列出该形式，`api.features` 包含 `connect_policygroup`。在 `activeNode` 中，策略组会连同其成员一起返回（对没有完整节点访问权限的应用则为匿名化形式）。
