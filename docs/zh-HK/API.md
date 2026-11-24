# Magic V2Ray API

[English](../API.md) | [Tiếng Việt](../vi/API.md) | [Русский](../ru/API.md) | [简体中文](../zh-CN/API.md) | [繁體中文（台灣）](../zh-TW/API.md) | [繁體中文（香港）](./API.md)

[← README](README.md)

---

## 供應商深層連結

Magic V2Ray 註冊了 `magicv2ray://` 與 `v2rayng://` 兩種 scheme。VPN 商家可以把這些連結放在網站或機器人訊息中，讓客戶一鍵完成設定。從應用程式外部開啟的連結在新增任何內容之前都會請用戶確認；只有供應商自己的迷你瀏覽器內的「同步」連結才會直接匯入。

### 新增供應商本身：`magicv2ray://add-provider`

```
magicv2ray://add-provider?url=<網站>&remark=<顯示名稱>
```

| 參數 | 是否必填 | 說明 |
|------|----------|------|
| `url` | 是 | 供應商網站。缺少時會自動補上 `https://`；僅接受 `https` 網站。 |
| `remark` | 否 | 分類名稱（也可使用 `name`）。預設為網站主機名稱。若名稱已存在，會附加 `(2)`、`(3)`…… |

範例（請對值進行 URL 編碼）：

```
magicv2ray://add-provider?url=vless4gtiktok.duckdns.org&remark=Free%20Server%20VLESS
```

用戶確認後，應用程式會建立分類，獲取網站的 `<title>` 與圖示，並在迷你瀏覽器中開啟該網站。`add-privider` 可作為 `add-provider` 的別名使用。

### 從供應商網站同步節點

在迷你瀏覽器內，如下連結會將訂閱匯入**該供應商的分類**（節點會被取代，網址會保留以便重新載入 / 自動更新），並返回應用程式：

```
v2rayng://install-config?url=<經 URL 編碼的訂閱網址>
```

`v2rayng://install-sub?...` 以及 `magicv2ray://install-config?...` / `magicv2ray://install-sub?...` 的作用相同。給網站作者的說明：

- 請用 `encodeURIComponent` 對訂閱網址進行編碼（必須是 `http(s)://`）。未編碼的 `&` 會截斷網址。
- 把連結放在一般按鈕或 `<a href>` 後面即可；用 JavaScript 的 `location.href = ...` 也可以。
- 指向其他域名的連結（Telegram 登入、付款頁面等）以及 `tg:`、`mailto:`、`tel:` 會在迷你瀏覽器之外開啟。其他 scheme 會被封鎖。

### 不經供應商分類新增訂閱

在迷你瀏覽器之外（任何瀏覽器或應用程式），同樣的 install 連結在用戶確認後會新增一般訂閱：

```
v2rayng://install-config?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
magicv2ray://install-sub?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
```

### 供應商網站範例代碼

```html
<a href="magicv2ray://add-provider?url=example.com&remark=My%20VPN">加入 Magic V2Ray</a>

<script>
  const sub = 'https://example.com/sub/USER_TOKEN';
  document.getElementById('sync').href =
    'v2rayng://install-config?url=' + encodeURIComponent(sub);
</script>
<a id="sync">同步</a>
```

---

## 從其他應用程式與網站連接

任何應用程式或網站都可以請求 Magic V2Ray 在一段時間內讓流量經過某個伺服器。該伺服器是**暫時**的：你自己的節點不會被更動，結束連接後會恢復你先前選擇的節點（若先前正在運行，則會重新運行）。

| 方式 | 格式 |
|---|---|
| 連結 | `magicv2ray://connect?uri=<urlencoded uri>&name=<名稱>&exclude=<package,package>` |
| 連結 | `magicv2ray://disconnect` |
| 原生應用程式 | 使用 `startActivity`，action 為 `com.huskydg.magicv2ray.action.CONNECT` / `.DISCONNECT`，extras 為 `uri`、`name`、`exclude` |
| 原生應用程式 | 使用 `sendBroadcast`，action 與 extras 相同（connect 會顯示一則「點按以查看」的通知，因為 Android 10+ 禁止從後台開啟確認視窗） |

- **本機 SOCKS5**：發送 `uri=socks://127.0.0.1:4311`。不需要任何新機制，SOCKS 節點本來就受到支援。
- **顯示名稱**（狀態標籤、通知、圖塊：`RUNNING - <名稱>`）：依序取 `name` 參數、調用方應用程式名稱、uri 的 remark、其主機名稱。它只是一個標籤，不會因此信任任何對象。
- **防止循環**：提供 SOCKS5 端口並通過本裝置連上互聯網的應用程式必須被排除在 VPN 之外，否則它的流量會繞回自身。請傳入 `exclude=<package>`；已驗證的、提供本機地址服務的原生調用方會被自動排除。非 Root 模式使用 VPN 的 disallowed-apps 清單，Root 模式則將 uid 加入 iptables 的 `XRAY_EXCLUDE_APP` 鏈。兩者都只在連接持續期間生效，不受「排除應用程式」開關影響。
- **結束連接**：`magicv2ray://disconnect`、通知中的按鈕、快速設定圖塊、應用程式內的「停止 / 中斷連接」按鈕。重新啟動裝置也會結束連接。
- **安全性**：任何網站都可以觸發 `connect` 連結，因此每次連接都需確認，並顯示完整的 uri、調用方與被排除的應用程式。只有當調用方資訊由 Android 提供（絕不使用可偽造的 `EXTRA_REFERRER`）且不是瀏覽器時，才算「已驗證」。明文 VLESS / Trojan 節點仍保留原有警告。**存取權限**分頁列出了設為「一律信任」的應用程式；該選項僅對來自已驗證原生應用程式的明確 intent 提供（連結與瀏覽器永遠不行），並綁定到應用程式的簽署憑證。即使是受信任的應用程式，遇到明文節點以及排除自身以外的應用程式時仍需確認。

### 資訊與狀態事件（API level 1）

應用程式可以查詢目前運行的內容，並在代理**中斷**（或連接）時收到通知，無論是由什麼原因引起：發出請求的應用程式、圖塊、通知按鈕、崩潰，還是 Root 服務。

**1. Info**（版本、支援的 API、目前狀態）

```java
// A) ContentProvider：同步，無介面，可在任何線程調用
Bundle b = getContentResolver().call(Uri.parse("content://com.huskydg.magicv2ray.api"), "getInfo", null, null);
String json = b.getString("json");        // 完整 JSON，見下文
int apiLevel = b.getInt("apiLevel");      // 另有：versionName、versionCode、mode、connected、known
// "getState" 僅返回狀態。query(content://com.huskydg.magicv2ray.api/info | /state) 返回一列數據。

// B) Ordered broadcast：回覆為 JSON（result data）+ extras json / versionName / apiLevel
Intent i = new Intent("com.huskydg.magicv2ray.action.GET_INFO").setPackage("com.huskydg.magicv2ray");
sendOrderedBroadcast(i, null, new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent r) { String json = getResultData(); }   // null = 未安裝
}, null, Activity.RESULT_CANCELED, null, null);
```

Android 11+：請在調用方應用程式的 manifest 中加入 `<queries><package android:name="com.huskydg.magicv2ray"/></queries>`。

```json
{ "app": "Magic V2Ray", "package": "com.huskydg.magicv2ray", "versionName": "v2.5-alpha", "versionCode": 192, "mode": "root",
  "api": { "level": 2,
           "actions": ["com.huskydg.magicv2ray.action.CONNECT", "...DISCONNECT", "...GET_INFO"],
           "schemes": ["magicv2ray://connect", "magicv2ray://disconnect"],
           "events": ["com.huskydg.magicv2ray.event.STATE_CHANGED"],
           "extras": ["uri", "name", "exclude", "overrides"],
           "overrides": ["traffic", "network", "exclude", "routing", "hosts"],
           "provider": { "authority": "com.huskydg.magicv2ray.api", "methods": ["getInfo", "getState", "getNodes", "getActiveNode", "getNodeAccess"], "uris": ["..."] },
           "features": ["connect", "disconnect", "overrides", "exclude", "info", "state_events", "state_provider", "nodes", "node_access_request", "connect_freedom", "connect_chain", "connect_nodeid"] },
  "state": { "connected": true, "known": true, "mode": "root",
             "session": { "active": true, "name": "My server", "caller": "com.example.app", "startedAt": 1731670000000 } } }
```

| 欄位 | 意義 |
|---|---|
| `versionName`、`versionCode` | 目前安裝的 Magic V2Ray 版本 |
| `mode` | `root` 或 `nonroot` |
| `api.level` | 每當 API 新增了其他應用程式需要檢測的內容時遞增 |
| `api.actions`、`schemes`、`extras`、`overrides` | 支援的 action、深層連結、extras 以及 `overrides` 分組 |
| `api.events`、`api.provider` | 發出的事件以及 provider 的存取方式 |
| `api.features` | 功能清單，可用於逐項檢測 |
| `state.connected` | 代理是否正在運行 |
| `state.known` | Root 模式下尚無法讀取狀態時（未授予 Root、服務未運行）為 `false`；此時 `connected` 只是預設的 `false` |
| `state.session` | 目前的外部連接會話（名稱、調用方、開始時間）；不含伺服器地址與設定 |

請檢測 `api.level` 或 `features` 中的名稱，而不是比較應用程式版本號。返回的數據絕不包含伺服器地址或設定。

**2. 狀態事件**：廣播 `com.huskydg.magicv2ray.event.STATE_CHANGED`

| Extra | 值 |
|---|---|
| `state` / `connected` | `"connected"` / `"disconnected"` 以及 `true` / `false` |
| `reason` | `started`、`session_started`、`session_ended`（外部會話已結束）、`stopped`（正常停止）、`error`（引擎錯誤、VPN 權限遺失）、`crashed` |
| `session`、`sessionName`、`caller` | 是否涉及外部會話 / 會話名稱 / 發起會話的應用程式套件名稱 |
| `mode` | `root` 或 `nonroot` |
| `time`、`seq` | 時間（epoch ms）與序號，在每個 Magic V2Ray 進程內遞增 |

```java
registerReceiver(new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
        if (!i.getBooleanExtra("connected", false)) {
            onMagicV2RayDisconnected(i.getStringExtra("reason"));
        }
    }
}, new IntentFilter("com.huskydg.magicv2ray.event.STATE_CHANGED"), Context.RECEIVER_EXPORTED);
```

- **誰能收到**：任何應用程式在運行時註冊的 receiver（`registerReceiver`）都會將其作為一般廣播收到；擁有該會話的應用程式可能會收到同一事件兩次，請依 `seq` + `time` 去除重複。發起外部會話的應用程式還會被**明確**投遞，因此在 manifest 中宣告的 receiver 也能收到。對於在 Android 14 以下通過 `sendBroadcast` 發起的會話，Magic V2Ray 無從得知調用方是哪個應用程式，請使用運行時註冊的 receiver 或下方的 observer。
- **用 observer 取代 receiver**：`registerContentObserver(Uri.parse("content://com.huskydg.magicv2ray.api/state"), false, observer)`，被回調時再讀取 `getState`。
- **通知時機**：只有在 2 秒後引擎仍處於關閉狀態時才會通知中斷，因此重新啟動或重新載入（切換節點、更改設定）不會被視為中斷。外部會話結束（`session_ended`）會立即通知。如果啟動失敗且先前並未在運行，則不產生事件。
- **Root 模式**：Root 服務通過 `am broadcast ... ENGINE_CHANGED` 通知應用程式，應用程式重新讀取真實狀態後再發出事件。該廣播的內容不被信任，因此其他應用程式偽造發送也只會讓應用程式重新檢查狀態。如此同樣能捕捉崩潰以及來自 shell 的停止指令。
- **限制**：在非 Root 模式下，如果 Magic V2Ray 進程被系統終止，VPN 也會隨之結束，無法發送任何事件。若此事很重要，請定期調用 `getState`。
- **隱私**：事件與 info 只包含狀態，不含伺服器地址、節點 uri 或設定。

### 節點與特殊 connect uri（API level 2，僅限應用程式）

當存在 features `nodes`、`node_access_request`、`connect_freedom`、`connect_chain` 和 `connect_nodeid` 時，`api.level` 為 `2`。

**1. 權限。** 應用程式必須經用戶允許才能讀取節點。調用者是 Android 為該調用本身報告的套件名稱，因此網站、連結或廣播永遠無法讀取節點，也沒有任何東西能冒充其他應用程式。用戶決定應用程式能看到**甚麼**以及**多久**：

| 選項 | 含義 |
|---|---|
| 匿名資訊（預設） | 名稱、協議和 id；節點顯示為 `nodeid://<id>` 而不是 `vless://...`。沒有伺服器、連接埠和憑證。 |
| 完整節點資訊 | 真實的節點連結（`vless://...`），包含伺服器和憑證。 |
| 允許一次 | 有效 10 分鐘，只儲存在記憶體中。 |
| 一律允許 | 保留到在「存取權限」分頁中撤銷；與應用程式的簽章憑證綁定。 |
| 取消 | 不授予任何權限。 |

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
| `getNodeAccess` | `granted`、`level`、`persistent`；從不彈出對話框 |
| `getNodes` | `json` = `{level, activeId, nodes:[{id, name, protocol, uri, (僅完整等級：category, address, port)}]}`，另有 `count`、`activeId` |
| `getActiveNode` | `json` = `{connected, known, source, session?, node}`，另有 `connected`、`source` |

`source` 為 `session`（應用程式暫時設定的節點）、`selected`（用戶自己選的節點）或 `direct`（內建的無代理節點）。匿名讀取者永遠看不到由應用程式設定、且不屬於用戶自己節點的 uri：它會返回為 `<scheme>://hidden`。鏈一律顯示為 `chain://localhost/?hopList=...`。

**2. Connect uri。** connect 請求（連結、intent 或廣播）的 `uri` 還可以是：

| uri | 含義 |
|---|---|
| `freedom://localhost` | 內建的「無代理」節點：xray 只作為路由器，所有路由規則仍然生效 |
| `chain://localhost/?hopList=<urlencode(JSON array of node uris)>` | 2 到 16 跳的代理鏈：`["vless://...", "vmess://...", "socks://...", ...]`。第一項由裝置直接撥出，之後每一跳都經過前一跳的隧道，最後一項是出口。 |
| `nodeid://<id>` | 用戶自己的某個節點，可單獨使用，也可作為鏈中的一跳。僅限已驗證且獲准讀取節點（任一等級）的應用程式，因此匿名等級的應用程式也能使用它從未見過的節點。 |

照常需要確認：對話框會顯示鏈的協議（或「直連」），由第一跳決定是否要讓該應用程式繞過 VPN（`freedom://` 沒有可供流量迴圈的伺服器）。

### 在 Termux（或任何 shell）中使用

shell 無法註冊 receiver，在沒有 Root 時也無法使用 provider，因此需要使用 `am`。下列指令是根據 API 的運作方式推導出來的；其中 info 指令與輪詢循環取決於 Termux 的 `am` 是否像系統 `am` 一樣印出廣播結果。

```sh
# Info：`am broadcast` 是 ordered broadcast，會以 data="<json>" 的形式輸出回覆
am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray
# 有 Root 時，可以直接調用 provider：
su -c 'content call --uri content://com.huskydg.magicv2ray.api --method getInfo'

# Connect / disconnect（connect 仍需用戶確認）
am start -a com.huskydg.magicv2ray.action.CONNECT -p com.huskydg.magicv2ray \
  --es uri 'socks://127.0.0.1:5353' --es name 'Termux' --es exclude com.termux
am start -a com.huskydg.magicv2ray.action.DISCONNECT -p com.huskydg.magicv2ray
```

shell 收不到 `STATE_CHANGED`，所以請輪詢 info。下面的腳本會檢查 API level，然後在每次狀態變化時回報，直到 Magic V2Ray 中斷：

```sh
#!/data/data/com.termux/files/usr/bin/sh
info() { am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray 2>/dev/null \
         | sed -n 's/.*data="\(.*\)".*/\1/p'; }
J=$(info)
[ -n "$J" ] || { echo "Magic V2Ray 未安裝或版本過舊（沒有 API level 1）"; exit 1; }
echo "$J" | jq -r '"Magic V2Ray \(.versionName), API \(.api.level), \(.mode)"'
last=
while sleep 2; do
  c=$(info | jq -r '.state.connected')
  [ "$c" = "$last" ] || { echo "connected=$c"; last=$c; }
  [ "$c" = "false" ] && break
done
echo "Magic V2Ray 已中斷連接"
```

注意：從 Android 10 起，在後台運行 `am start` 需要為 Termux 授予「顯示在其他應用程式上層」權限。Magic V2Ray 能否將 Termux 識別為調用方（從而提供「一律信任」），取決於 Android 為 `am` 回報的資訊；未知的調用方一律需要確認。請使用 `pkg install jq` 安裝 `jq`。

### 暫時設定（`overrides`）

除了節點之外，`connect` 還可以攜帶 `overrides` 參數，在連接期間**暫時更改**流量設定、網絡設定、排除的應用程式、路由與自訂 hosts。存在 `overrides` 時，`uri` 為選填（此時使用你目前選取的節點）。

- 連結：`magicv2ray://connect?uri=...&overrides=<JSON 的 base64url>`；intent / broadcast：`overrides` extra 為 JSON 字串。
- 只接受白名單內、類型與數值範圍正確的鍵，其餘一律丟棄（請參閱 `ExternalSession.sanitizeOverrides`）。不可覆蓋：`exposeSocks*`、專業模式、語言 / 主題。路由規則的 `outboundTag` 僅接受 `proxy`、`direct` 或 `block`。

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

**隱私：暫時設定絕不會被儲存。**

| 模式 | 會話存放位置 | 絕不寫入 |
|---|---|---|
| Root | `/mnt/runtime/magicv2ray/run/override/`（tmpfs，重新啟動後消失） | `/data/adb/magic_v2ray` |
| 非 Root | `cache/override/` | `files/data/`（連 `config.v2.json` 也不會） |

- 引擎會從會話目錄讀取 `config.v2.json`、`settings.base64`、`excludelist.txt` 與 `bypassIface.txt`，**取代**你的同名檔案；你的檔案只會被讀取，不會被修改。中斷連接即刪除會話目錄，並以你自己的設定重新啟動引擎。
- 待確認的請求只儲存在記憶體中；會話僅儲存元數據（名稱、調用方、排除清單），既不儲存伺服器，也不儲存任何被覆蓋的值。
- 會話**重新啟動後不會恢復**（Root：`/mnt/runtime` 會被清空，且服務在啟動時清理該目錄；非 Root：VpnService 為 `START_NOT_STICKY`，應用程式開啟時會丟棄過期的會話）。需要再次使用的應用程式必須在每次開機後重新發送 `connect`。暫時會話也不會改變決定開機時恢復什麼的 `enabled` 旗標。
- 帶有 `overrides` 的請求需要確認（對話框會列出將更改的內容）。設為「一律信任」的應用程式，只要發送的 `overrides` 與上次獲准時相同，**就不會再次詢問**。如果不同（新增或已更改），對話框會再次出現，提示該應用程式想要套用新的設定。**一律允許**會記住新的一組；**僅允許一次**則不會。只儲存 `overrides` 的 SHA-256 指紋，絕不儲存其值。連結與瀏覽器永遠不能被信任。
