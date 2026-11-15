# Magic V2Ray

[English](./README.md) | [Tiếng Việt](./README_vi.md) | [Русский](./README_ru.md) | [简体中文](./README_zh-CN.md) | [繁體中文（台灣）](./README_zh-TW.md) | [繁體中文（香港）](./README_zh-HK.md)

![screenshot_1](images/screenshot_1.jpg)

一款強大、現代的 Android 網際網路代理管理工具（同時支援**已 Root** 與**未 Root** 裝置）。它透過高效能代理核心轉送系統與應用程式的流量，保護你的連線安全、繞過網路限制，並將網路分享給其他裝置。

---

## 什麼是 Magic V2Ray？

**Magic V2Ray** 是一款針對 Android 的進階網路用戶端。它以獨立 Android 應用程式的形式發行，內建管理工具與代理核心，為整台裝置提供流暢的網路連線體驗。

從 **v2.0** 開始，Magic V2Ray 新增了以 Android `VpnService` 為基礎的**非 Root 模式**，讓未 Root 的使用者也能順暢執行代理引擎。已 Root 的使用者則仍可享有核心層級的常駐程式（daemon）路由、持續的背景執行以及底層封包標記。

應用程式附有簡潔的 Web UI，可輕鬆整理代理設定、管理訂閱並監控網路。

---

## 主要特色與運作模式

### 1. Root 模式（常駐程式引擎）
專為已 Root 的裝置（Magisk、KernelSU、APatch）設計，追求最高穩定性與進階網路路由：
- **不死連線：** 以 Root 層級的系統常駐程式執行。即使記憶體壓力很大，系統的低記憶體終結器（LMK）也不會終止該程序。
- **深度路由涵蓋：** 在 Netfilter 層攔截流量，可對一般 VPN 無法觸及的系統 UID 進行透明代理。
- **不佔用系統 VPN 欄位：** 不需佔用 Android 標準 VPN 欄位，狀態列不會出現常駐的 VPN 鑰匙圖示，也不會與依應用程式分流的 VPN 衝突。
- **原生熱點 / 網路共享：** 在 `PREROUTING` 鏈攔截流量，自動讓透過 Wi-Fi 熱點或 USB 網路共享上網的裝置走代理通道。
- **動態重新連線：** 直接監聽核心網路事件（`ip monitor`），Wi-Fi ↔ 行動網路切換時即時反應。

### 2. 非 Root 模式（VpnService）
從 **v2.0** 開始，沒有 Root 權限的裝置也可以透過 Android 原生 `VpnService` 引擎順暢執行 Magic V2Ray：
- 安全、標準的使用者空間代理路由。
- 不需要 Root，也不需要自訂核心模組。

---

## 其他功能

- **快速設定圖塊（Quick Settings Tile）：** 直接在通知欄中開關代理引擎。長按圖塊可開啟應用程式。
- **模組熱更新：** 完整支援 176 及更新版本——無須重新啟動裝置即可即時升級服務。
- **分類整理：** 將代理伺服器歸入自訂資料夾 / 分類。
- **智慧訂閱匯入：** 輕鬆貼上訂閱網址、原始設定或混合文字。
- **一鍵自動重新整理：** 輕點一下即可讓訂閱保持在最新狀態。
- **免 Root 的熱點分享：** 在非 Root 模式下，Shizuku 可將代理分享給熱點 / USB 用戶端（Android 13+）。
- **熱點分享：** 透過 IPv4/IPv6 Wi-Fi 熱點，將代理後的連線分享給筆記型電腦、遊戲主機或其他手機。
- **供應商網站：** 將 VPN 業者的網站新增為分類，在內建迷你瀏覽器中開啟，點選其「同步」按鈕即可匯入節點，並可將其捷徑釘選到主畫面。請參閱[深層連結](#供應商深層連結)。

---

## 安裝與使用

Magic V2Ray 以獨立的 Android 應用程式（`.apk`）形式發行，同時也可作為可刷入的 Root 模組 zip 使用。

### 未 Root 的使用者
1. 下載並安裝 `magic_v2ray-*-arm64-v8a.apk`（或 `-x86_64.apk`）。
2. 開啟應用程式，選擇 **Non-Root (VpnService) Mode**，授予 VPN 權限，然後啟動連線。

### 已 Root 的使用者（Magisk / KernelSU / APatch）
**方式 A：透過 Root 管理器安裝（模組 + 應用程式）**
1. 將 `magic_v2ray-*.apk` 重新命名為 `magic_v2ray-*.zip`。
2. 在 Magisk / KernelSU / APatch 中刷入（Modules → Install from storage）。它會安裝背景模組，並執行 `pm install` 安裝應用程式介面。
3. 重新啟動裝置，或直接開啟應用程式。

**方式 B：一般 APK 安裝**
1. 安裝 `magic_v2ray-*-arm64-v8a.apk` 並開啟。
2. 授予 Root 權限。應用程式會自動檢查並在 `/data/adb/modules/magic_v2ray` 中部署 Root 常駐程式引擎（使用者設定保存在 `/data/adb/magic_v2ray`）。

*註：模組的 **Action** 按鈕（Magisk 27+ / KernelSU / APatch）會直接啟動應用程式；若尚未安裝，會自動安裝 `app.apk`。*

---

## 供應商深層連結

Magic V2Ray 註冊了 `magicv2ray://` 與 `v2rayng://` 兩種 scheme。VPN 業者可以把這些連結放在網站或機器人訊息中，讓客戶一鍵完成設定。從應用程式外部開啟的連結在新增任何內容之前都會請使用者確認；只有供應商自己的迷你瀏覽器內的「同步」連結才會直接匯入。

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

使用者確認後，應用程式會建立分類，取得網站的 `<title>` 與圖示，並在迷你瀏覽器中開啟該網站。`add-privider` 可作為 `add-provider` 的別名使用。

### 從供應商網站同步節點

在迷你瀏覽器內，如下連結會將訂閱匯入**該供應商的分類**（節點會被取代，網址會保留以便重新載入 / 自動更新），並返回應用程式：

```
v2rayng://install-config?url=<經 URL 編碼的訂閱網址>
```

`v2rayng://install-sub?...` 以及 `magicv2ray://install-config?...` / `magicv2ray://install-sub?...` 的作用相同。給網站作者的說明：

- 請用 `encodeURIComponent` 對訂閱網址進行編碼（必須是 `http(s)://`）。未編碼的 `&` 會截斷網址。
- 把連結放在一般按鈕或 `<a href>` 後面即可；用 JavaScript 的 `location.href = ...` 也可以。
- 指向其他網域的連結（Telegram 登入、付款頁面等）以及 `tg:`、`mailto:`、`tel:` 會在迷你瀏覽器之外開啟。其他 scheme 會被封鎖。

### 不經供應商分類新增訂閱

在迷你瀏覽器之外（任何瀏覽器或應用程式），同樣的 install 連結在使用者確認後會新增一般訂閱：

```
v2rayng://install-config?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
magicv2ray://install-sub?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
```

### 供應商網站範例程式碼

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

## 從其他應用程式與網站連線

任何應用程式或網站都可以請求 Magic V2Ray 在一段時間內讓流量經過某個伺服器。該伺服器是**暫時**的：你自己的節點不會被更動，結束連線後會恢復你先前選擇的節點（若先前正在執行，則會重新執行）。

| 方式 | 格式 |
|---|---|
| 連結 | `magicv2ray://connect?uri=<urlencoded uri>&name=<名稱>&exclude=<package,package>` |
| 連結 | `magicv2ray://disconnect` |
| 原生應用程式 | 使用 `startActivity`，action 為 `com.huskydg.magicv2ray.action.CONNECT` / `.DISCONNECT`，extras 為 `uri`、`name`、`exclude` |
| 原生應用程式 | 使用 `sendBroadcast`，action 與 extras 相同（connect 會顯示一則「點按以檢視」的通知，因為 Android 10+ 禁止從背景開啟確認視窗） |

- **本機 SOCKS5**：傳送 `uri=socks://127.0.0.1:4311`。不需要任何新機制，SOCKS 節點本來就受到支援。
- **顯示名稱**（狀態標籤、通知、圖塊：`RUNNING - <名稱>`）：依序取 `name` 參數、呼叫端應用程式名稱、uri 的 remark、其主機名稱。它只是一個標籤，不會因此信任任何對象。
- **防止迴圈**：提供 SOCKS5 連接埠並透過本裝置連上網際網路的應用程式必須被排除在 VPN 之外，否則它的流量會繞回自身。請傳入 `exclude=<package>`；已驗證的、提供本機位址服務的原生呼叫端會被自動排除。非 Root 模式使用 VPN 的 disallowed-apps 清單，Root 模式則將 uid 加入 iptables 的 `XRAY_EXCLUDE_APP` 鏈。兩者都只在連線持續期間生效，不受「排除應用程式」開關影響。
- **結束連線**：`magicv2ray://disconnect`、通知中的按鈕、快速設定圖塊、應用程式內的「停止 / 中斷連線」按鈕。重新啟動裝置也會結束連線。
- **安全性**：任何網站都可以觸發 `connect` 連結，因此每次連線都需確認，並顯示完整的 uri、呼叫端與被排除的應用程式。只有當呼叫端資訊由 Android 提供（絕不使用可偽造的 `EXTRA_REFERRER`）且不是瀏覽器時，才算「已驗證」。明文 VLESS / Trojan 節點仍保留原有警告。**存取權限**分頁列出了設為「一律信任」的應用程式；該選項僅對來自已驗證原生應用程式的明確 intent 提供（連結與瀏覽器永遠不行），並綁定到應用程式的簽署憑證。即使是受信任的應用程式，遇到明文節點以及排除自身以外的應用程式時仍需確認。

### 資訊與狀態事件（API level 1）

應用程式可以查詢目前執行的內容，並在代理**中斷**（或連線）時收到通知，無論是由什麼原因引起：發出請求的應用程式、圖塊、通知按鈕、當機，還是 Root 服務。

**1. Info**（版本、支援的 API、目前狀態）

```java
// A) ContentProvider：同步，無介面，可在任何執行緒呼叫
Bundle b = getContentResolver().call(Uri.parse("content://com.huskydg.magicv2ray.api"), "getInfo", null, null);
String json = b.getString("json");        // 完整 JSON，見下文
int apiLevel = b.getInt("apiLevel");      // 另有：versionName、versionCode、mode、connected、known
// "getState" 僅回傳狀態。query(content://com.huskydg.magicv2ray.api/info | /state) 回傳一列資料。

// B) Ordered broadcast：回覆為 JSON（result data）+ extras json / versionName / apiLevel
Intent i = new Intent("com.huskydg.magicv2ray.action.GET_INFO").setPackage("com.huskydg.magicv2ray");
sendOrderedBroadcast(i, null, new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent r) { String json = getResultData(); }   // null = 未安裝
}, null, Activity.RESULT_CANCELED, null, null);
```

Android 11+：請在呼叫端應用程式的 manifest 中加入 `<queries><package android:name="com.huskydg.magicv2ray"/></queries>`。

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

| 欄位 | 意義 |
|---|---|
| `versionName`、`versionCode` | 目前安裝的 Magic V2Ray 版本 |
| `mode` | `root` 或 `nonroot` |
| `api.level` | 每當 API 新增了其他應用程式需要檢測的內容時遞增 |
| `api.actions`、`schemes`、`extras`、`overrides` | 支援的 action、深層連結、extras 以及 `overrides` 分組 |
| `api.events`、`api.provider` | 發出的事件以及 provider 的存取方式 |
| `api.features` | 功能清單，可用於逐項檢測 |
| `state.connected` | 代理是否正在執行 |
| `state.known` | Root 模式下尚無法讀取狀態時（未授予 Root、服務未執行）為 `false`；此時 `connected` 只是預設的 `false` |
| `state.session` | 目前的外部連線工作階段（名稱、呼叫端、開始時間）；不含伺服器位址與設定 |

請檢測 `api.level` 或 `features` 中的名稱，而不是比較應用程式版本號。回傳的資料絕不包含伺服器位址或設定。

**2. 狀態事件**：廣播 `com.huskydg.magicv2ray.event.STATE_CHANGED`

| Extra | 值 |
|---|---|
| `state` / `connected` | `"connected"` / `"disconnected"` 以及 `true` / `false` |
| `reason` | `started`、`session_started`、`session_ended`（外部工作階段已結束）、`stopped`（正常停止）、`error`（引擎錯誤、VPN 權限遺失）、`crashed` |
| `session`、`sessionName`、`caller` | 是否涉及外部工作階段 / 工作階段名稱 / 發起工作階段的應用程式套件名稱 |
| `mode` | `root` 或 `nonroot` |
| `time`、`seq` | 時間（epoch ms）與序號，在每個 Magic V2Ray 程序內遞增 |

```java
registerReceiver(new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
        if (!i.getBooleanExtra("connected", false)) {
            onMagicV2RayDisconnected(i.getStringExtra("reason"));
        }
    }
}, new IntentFilter("com.huskydg.magicv2ray.event.STATE_CHANGED"), Context.RECEIVER_EXPORTED);
```

- **誰能收到**：任何應用程式在執行時期註冊的 receiver（`registerReceiver`）都會將其作為一般廣播收到；擁有該工作階段的應用程式可能會收到同一事件兩次，請依 `seq` + `time` 去除重複。發起外部工作階段的應用程式還會被**明確**投遞，因此在 manifest 中宣告的 receiver 也能收到。對於在 Android 14 以下透過 `sendBroadcast` 發起的工作階段，Magic V2Ray 無從得知呼叫端是哪個應用程式，請使用執行時期註冊的 receiver 或下方的 observer。
- **用 observer 取代 receiver**：`registerContentObserver(Uri.parse("content://com.huskydg.magicv2ray.api/state"), false, observer)`，被回呼時再讀取 `getState`。
- **通知時機**：只有在 2 秒後引擎仍處於關閉狀態時才會通知中斷，因此重新啟動或重新載入（切換節點、變更設定）不會被視為中斷。外部工作階段結束（`session_ended`）會立即通知。如果啟動失敗且先前並未在執行，則不產生事件。
- **Root 模式**：Root 服務透過 `am broadcast ... ENGINE_CHANGED` 通知應用程式，應用程式重新讀取真實狀態後再發出事件。該廣播的內容不被信任，因此其他應用程式偽造傳送也只會讓應用程式重新檢查狀態。如此同樣能捕捉當機以及來自 shell 的停止指令。
- **限制**：在非 Root 模式下，如果 Magic V2Ray 程序被系統終止，VPN 也會隨之結束，無法傳送任何事件。若此事很重要，請定期呼叫 `getState`。
- **隱私**：事件與 info 只包含狀態，不含伺服器位址、節點 uri 或設定。

### 在 Termux（或任何 shell）中使用

shell 無法註冊 receiver，在沒有 Root 時也無法使用 provider，因此需要使用 `am`。下列指令是根據 API 的運作方式推導出來的；其中 info 指令與輪詢迴圈取決於 Termux 的 `am` 是否像系統 `am` 一樣印出廣播結果。

```sh
# Info：`am broadcast` 是 ordered broadcast，會以 data="<json>" 的形式輸出回覆
am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray
# 有 Root 時，可以直接呼叫 provider：
su -c 'content call --uri content://com.huskydg.magicv2ray.api --method getInfo'

# Connect / disconnect（connect 仍需使用者確認）
am start -a com.huskydg.magicv2ray.action.CONNECT -p com.huskydg.magicv2ray \
  --es uri 'socks://127.0.0.1:5353' --es name 'Termux' --es exclude com.termux
am start -a com.huskydg.magicv2ray.action.DISCONNECT -p com.huskydg.magicv2ray
```

shell 收不到 `STATE_CHANGED`，所以請輪詢 info。下面的指令碼會檢查 API level，然後在每次狀態變化時回報，直到 Magic V2Ray 中斷：

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
echo "Magic V2Ray 已中斷連線"
```

注意：從 Android 10 起，在背景執行 `am start` 需要為 Termux 授予「顯示在其他應用程式上層」權限。Magic V2Ray 能否將 Termux 識別為呼叫端（從而提供「一律信任」），取決於 Android 為 `am` 回報的資訊；未知的呼叫端一律需要確認。請使用 `pkg install jq` 安裝 `jq`。

### 暫時設定（`overrides`）

除了節點之外，`connect` 還可以攜帶 `overrides` 參數，在連線期間**暫時變更**流量設定、網路設定、排除的應用程式、路由與自訂 hosts。存在 `overrides` 時，`uri` 為選填（此時使用你目前選取的節點）。

- 連結：`magicv2ray://connect?uri=...&overrides=<JSON 的 base64url>`；intent / broadcast：`overrides` extra 為 JSON 字串。
- 只接受白名單內、型別與數值範圍正確的鍵，其餘一律捨棄（請參閱 `ExternalSession.sanitizeOverrides`）。不可覆寫：`exposeSocks*`、專業模式、語言 / 主題。路由規則的 `outboundTag` 僅接受 `proxy`、`direct` 或 `block`。

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

| 模式 | 工作階段存放位置 | 絕不寫入 |
|---|---|---|
| Root | `/dev/sysctl_stubs/run/override/`（tmpfs，重新啟動後消失） | `/data/adb/magic_v2ray` |
| 非 Root | `cache/override/` | `files/data/`（連 `config.v2.json` 也不會） |

- 引擎會從工作階段目錄讀取 `config.v2.json`、`settings.base64`、`excludelist.txt` 與 `bypassIface.txt`，**取代**你的同名檔案；你的檔案只會被讀取，不會被修改。中斷連線即刪除工作階段目錄，並以你自己的設定重新啟動引擎。
- 待確認的請求只儲存在記憶體中；工作階段僅儲存中繼資料（名稱、呼叫端、排除清單），既不儲存伺服器，也不儲存任何被覆寫的值。
- 工作階段**重新啟動後不會恢復**（Root：`/dev` 會被清空，且服務在啟動時清理該目錄；非 Root：VpnService 為 `START_NOT_STICKY`，應用程式開啟時會捨棄過期的工作階段）。需要再次使用的應用程式必須在每次開機後重新傳送 `connect`。暫時工作階段也不會改變決定開機時恢復什麼的 `enabled` 旗標。
- 帶有 `overrides` 的請求需要確認（對話方塊會列出將變更的內容）。設為「一律信任」的應用程式，只要傳送的 `overrides` 與上次獲准時相同，**就不會再次詢問**。如果不同（新增或已變更），對話方塊會再次出現，提示該應用程式想要套用新的設定。**一律允許**會記住新的一組；**僅允許一次**則不會。只儲存 `overrides` 的 SHA-256 指紋，絕不儲存其值。連結與瀏覽器永遠不能被信任。

## 從原始碼建置

```sh
./build.sh          # 輸出 APK（arm64-v8a、x86_64、universal）-> dist/
                    # （每個 APK 也可以重新命名為 .zip，用作模組安裝包）

```

**環境需求：**

* JDK 17+
* Gradle 8.7+（建議 8.9）
* Android SDK（`ANDROID_HOME`，目標平台 `android-35`）
* Android NDK（用於原生輔助元件）

Web UI 資源位於 `app/src/main/assets/webroot`。應用程式提供了相容 `window.ksu` 的橋接介面（`exec`、`listPackages`、`getPackagesInfo`）。

---

## 致謝與貢獻

* **[Xray-core](https://github.com/XTLS/Xray-core)：** 負責處理新一代代理協定（VLESS、VMess、Trojan 等）的底層引擎。
* **[curl-android](https://github.com/vvb2060/curl-android)：** 為 Android 預先編譯的 `curl` 命令列工具與 `libcurl` 靜態函式庫 prefab。

---

## 授權條款

本專案採用 **GNU General Public License v3.0（GPL-3.0）** 發行。使用本專案即表示你同意授權條款中規定的條款與條件。更多詳情請參閱本儲存庫中的 [LICENSE](LICENSE) 檔案。

簡而言之，你可以自由使用、修改與散布本軟體，前提是在你自己的專案中保留相同的授權條款並公開原始碼。此外，在閉源軟體中使用其原始碼或仿製 Magic V2Ray 的功能，可能違反授權條款。
