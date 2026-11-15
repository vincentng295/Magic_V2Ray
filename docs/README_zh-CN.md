# Magic V2Ray

[English](./README.md) | [Tiếng Việt](./README_vi.md) | [Русский](./README_ru.md) | [简体中文](./README_zh-CN.md) | [繁體中文（台灣）](./README_zh-TW.md) | [繁體中文（香港）](./README_zh-HK.md)

![screenshot_1](images/screenshot_1.jpg)

一款强大、现代的 Android 互联网代理管理器（同时支持**已 Root** 和**未 Root** 设备）。它通过高性能代理内核转发系统与应用的流量，保护你的连接安全、绕过网络限制，并将网络共享给其他设备。

---

## 什么是 Magic V2Ray？

**Magic V2Ray** 是一款面向 Android 的高级网络客户端。它以独立 Android 应用的形式发布，内置管理工具和代理内核，为整台设备提供流畅的网络连接体验。

从 **v2.0** 开始，Magic V2Ray 新增了基于 Android `VpnService` 的**非 Root 模式**，让未 Root 的用户也能顺畅运行代理引擎。已 Root 的用户则继续享有内核级守护进程路由、持久后台运行以及底层数据包标记。

应用附带简洁的 Web UI，可以轻松整理代理配置、管理订阅并监控网络。

---

## 主要特性与运行模式

### 1. Root 模式（守护进程引擎）
专为已 Root 设备（Magisk、KernelSU、APatch）设计，追求最高稳定性和高级网络路由：
- **不死连接：** 以 Root 级系统守护进程运行。即使内存压力很大，系统的低内存杀手（LMK）也不会杀掉该进程。
- **深度路由覆盖：** 在 Netfilter 层拦截流量，可对普通 VPN 无法触及的系统 UID 进行透明代理。
- **不占用系统 VPN 槽位：** 无需占用 Android 标准 VPN 槽位，状态栏不会出现常驻的 VPN 钥匙图标，也不会与按应用分流的 VPN 冲突。
- **原生热点 / 网络共享：** 在 `PREROUTING` 链拦截流量，自动让通过 Wi-Fi 热点或 USB 共享上网的设备走代理隧道。
- **动态重连：** 直接监听内核网络事件（`ip monitor`），Wi-Fi ↔ 移动网络切换时即时响应。

### 2. 非 Root 模式（VpnService）
从 **v2.0** 开始，没有 Root 权限的设备也可以通过 Android 原生 `VpnService` 引擎顺畅运行 Magic V2Ray：
- 安全、标准的用户空间代理路由。
- 无需 Root，也无需自定义内核模块。

---

## 其他特性

- **快速设置磁贴（Quick Settings Tile）：** 直接在通知栏中开关代理引擎。长按磁贴可打开应用。
- **模块热更新：** 完整支持 176 及更高版本——无需重启设备即可即时升级服务。
- **分类整理：** 将代理服务器归入自定义文件夹 / 分类。
- **智能订阅导入：** 轻松粘贴订阅链接、原始配置或混合文本。
- **一键自动刷新：** 轻点一下即可保持订阅为最新状态。
- **无需 Root 的热点共享：** 在非 Root 模式下，Shizuku 可将代理共享给热点 / USB 客户端（Android 13+）。
- **热点共享：** 通过 IPv4/IPv6 Wi-Fi 热点，将代理后的连接分享给笔记本电脑、游戏主机或其他手机。
- **服务商网站：** 将 VPN 商家的网站添加为分类，在内置迷你浏览器中打开，点击其“同步”按钮即可导入节点，并可将其快捷方式固定到主屏幕。参见[深度链接](#服务商深度链接)。

---

## 安装与使用

Magic V2Ray 以独立的 Android 应用（`.apk`）形式发布，同时也可作为可刷入的 Root 模块 zip 使用。

### 未 Root 用户
1. 下载并安装 `magic_v2ray-*-arm64-v8a.apk`（或 `-x86_64.apk`）。
2. 打开应用，选择 **Non-Root (VpnService) Mode**，授予 VPN 权限，然后启动连接。

### 已 Root 用户（Magisk / KernelSU / APatch）
**方式 A：通过 Root 管理器安装（模块 + 应用）**
1. 将 `magic_v2ray-*.apk` 重命名为 `magic_v2ray-*.zip`。
2. 在 Magisk / KernelSU / APatch 中刷入（Modules → Install from storage）。它会安装后台模块，并执行 `pm install` 安装应用界面。
3. 重启设备，或直接打开应用。

**方式 B：常规 APK 安装**
1. 安装 `magic_v2ray-*-arm64-v8a.apk` 并打开。
2. 授予 Root 权限。应用会自动检查并在 `/data/adb/modules/magic_v2ray` 中部署 Root 守护进程引擎（用户设置保存在 `/data/adb/magic_v2ray`）。

*注：模块的 **Action** 按钮（Magisk 27+ / KernelSU / APatch）会直接启动应用；如果尚未安装，会自动安装 `app.apk`。*

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
  "api": { "level": 1,
           "actions": ["com.huskydg.magicv2ray.action.CONNECT", "...DISCONNECT", "...GET_INFO"],
           "schemes": ["magicv2ray://connect", "magicv2ray://disconnect"],
           "events": ["com.huskydg.magicv2ray.event.STATE_CHANGED"],
           "extras": ["uri", "name", "exclude", "overrides"],
           "overrides": ["traffic", "network", "exclude", "routing", "hosts"],
           "provider": { "authority": "com.huskydg.magicv2ray.api", "methods": ["getInfo", "getState"], "uris": ["..."] },
           "features": ["connect", "disconnect", "overrides", "exclude", "info", "state_events", "state_provider"] },
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
               "rules": [ { "domain": "geosite:google", "outboundTag": "proxy", "remarks": "temp" } ] },
  "hosts":   { "mode": "merge", "entries": { "example.com": "1.2.3.4", "b.com": ["::1", "10.0.0.1"] } }
}
```

**隐私：临时设置绝不会被保存。**

| 模式 | 会话存放位置 | 绝不写入 |
|---|---|---|
| Root | `/dev/sysctl_stubs/run/override/`（tmpfs，重启后消失） | `/data/adb/magic_v2ray` |
| 非 Root | `cache/override/` | `files/data/`（连 `config.v2.json` 也不会） |

- 引擎会从会话目录读取 `config.v2.json`、`settings.base64`、`excludelist.txt` 和 `bypassIface.txt`，**取代**你的同名文件；你的文件只会被读取，不会被修改。断开连接即删除会话目录，并以你自己的配置重启引擎。
- 待确认的请求只保存在内存中；会话仅保存元数据（名称、调用方、排除列表），既不保存服务器，也不保存任何被覆盖的值。
- 会话**重启后不会恢复**（Root：`/dev` 会被清空，且服务在启动时清理该目录；非 Root：VpnService 为 `START_NOT_STICKY`，应用打开时会丢弃过期会话）。需要再次使用的应用必须在每次开机后重新发送 `connect`。临时会话也不会改变决定开机时恢复什么的 `enabled` 标志。
- 带有 `overrides` 的请求需要确认（对话框会列出将更改的内容）。设为“始终信任”的应用，只要发送的 `overrides` 与上次获准时相同，**就不会再次询问**。如果不同（新增或已更改），对话框会再次出现，提示该应用想要应用新的设置。**始终允许**会记住新的一组；**仅允许一次**则不会。只保存 `overrides` 的 SHA-256 指纹，绝不保存其值。链接和浏览器永远不能被信任。

## 从源码构建

```sh
./build.sh          # 输出 APK（arm64-v8a、x86_64、universal）-> dist/
                    # （每个 APK 也可以重命名为 .zip，用作模块安装包）

```

**环境要求：**

* JDK 17+
* Gradle 8.7+（推荐 8.9）
* Android SDK（`ANDROID_HOME`，目标平台 `android-35`）
* Android NDK（用于原生辅助组件）

Web UI 资源位于 `app/src/main/assets/webroot`。应用提供了兼容 `window.ksu` 的桥接接口（`exec`、`listPackages`、`getPackagesInfo`）。

---

## 致谢与贡献

* **[Xray-core](https://github.com/XTLS/Xray-core)：** 负责处理新一代代理协议（VLESS、VMess、Trojan 等）的底层引擎。
* **[curl-android](https://github.com/vvb2060/curl-android)：** 为 Android 预编译的 `curl` 命令行工具和 `libcurl` 静态库 prefab。

---

## 许可证

本项目采用 **GNU General Public License v3.0（GPL-3.0）** 发布。使用本项目即表示你同意许可证中规定的条款和条件。更多详情请参阅本仓库中的 [LICENSE](LICENSE) 文件。

简而言之，你可以自由使用、修改和分发本软件，前提是在你自己的项目中保留相同的许可证并公开源代码。此外，在闭源软件中使用其源代码或复刻 Magic V2Ray 的功能，可能违反许可证条款。
