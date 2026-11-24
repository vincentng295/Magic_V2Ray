# Magic V2Ray API

[English](../API.md) | [Tiếng Việt](./API.md) | [Русский](../ru/API.md) | [简体中文](../zh-CN/API.md) | [繁體中文（台灣）](../zh-TW/API.md) | [繁體中文（香港）](../zh-HK/API.md)

[← README](README.md)

---

## Deep Link cho nhà cung cấp

Magic V2Ray đăng ký hai scheme `magicv2ray://` và `v2rayng://`. Các nhà bán VPN có thể đặt link này trên website hoặc trong tin nhắn bot để khách hàng thiết lập chỉ bằng một chạm. Link mở từ bên ngoài app luôn hỏi người dùng xác nhận trước khi thêm; riêng link Đồng bộ bên trong mini trình duyệt của chính nhà cung cấp thì nhập thẳng.

### Tự thêm nhà cung cấp: `magicv2ray://add-provider`

```
magicv2ray://add-provider?url=<website>&remark=<tên hiển thị>
```

| Tham số | Bắt buộc | Mô tả |
|---------|----------|-------|
| `url` | có | Website nhà cung cấp. Tự thêm `https://` nếu thiếu; chỉ nhận website `https`. |
| `remark` | không | Tên danh mục (dùng `name` cũng được). Mặc định là hostname của site. Nếu trùng tên, app thêm `(2)`, `(3)`... |

Ví dụ (nhớ mã hóa URL các giá trị):

```
magicv2ray://add-provider?url=vless4gtiktok.duckdns.org&remark=Free%20Server%20VLESS
```

Sau khi người dùng xác nhận, app tạo danh mục, tải `<title>` và icon của site rồi mở site trong mini trình duyệt. `add-privider` được chấp nhận như bí danh của `add-provider`.

### Đồng bộ node từ website nhà cung cấp

Trong mini trình duyệt, link sau sẽ nhập subscription vào **danh mục của chính nhà cung cấp đó** (node được thay mới, URL được lưu để Reload / tự cập nhật) rồi quay về app:

```
v2rayng://install-config?url=<URL subscription đã mã hóa>
```

`v2rayng://install-sub?...`, `magicv2ray://install-config?...` và `magicv2ray://install-sub?...` hoạt động tương tự. Lưu ý cho người làm website:

- Mã hóa URL subscription bằng `encodeURIComponent` (phải là `http(s)://`). Dấu `&` không mã hóa sẽ làm cụt URL.
- Đặt link sau một nút hoặc `<a href>` thông thường; gán `location.href = ...` bằng JavaScript cũng được.
- Link sang tên miền khác (đăng nhập Telegram, trang thanh toán...) và `tg:`, `mailto:`, `tel:` được mở bên ngoài mini trình duyệt. Các scheme khác bị chặn.

### Thêm subscription không qua danh mục nhà cung cấp

Ngoài mini trình duyệt (trình duyệt hay app bất kỳ), cùng các link install trên sẽ thêm một subscription thường sau khi người dùng xác nhận:

```
v2rayng://install-config?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
magicv2ray://install-sub?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
```

### Đoạn mã mẫu cho website nhà cung cấp

```html
<a href="magicv2ray://add-provider?url=example.com&remark=My%20VPN">Thêm vào Magic V2Ray</a>

<script>
  const sub = 'https://example.com/sub/USER_TOKEN';
  document.getElementById('sync').href =
    'v2rayng://install-config?url=' + encodeURIComponent(sub);
</script>
<a id="sync">Đồng bộ</a>
```

---

## Kết nối từ ứng dụng và website khác

Bất kỳ ứng dụng hoặc website nào cũng có thể yêu cầu Magic V2Ray đưa lưu lượng qua một máy chủ trong một lúc. Máy chủ này chỉ là **tạm thời**: node của bạn không bị đụng tới, và khi ngắt kết nối, node đã chọn trước đó được trả lại (chạy lại nếu trước đó đang chạy).

| Cách | Định dạng |
|---|---|
| Liên kết | `magicv2ray://connect?uri=<uri mã hóa>&name=<tên>&exclude=<package,package>` |
| Liên kết | `magicv2ray://disconnect` |
| App native | `startActivity` với action `com.huskydg.magicv2ray.action.CONNECT` / `.DISCONNECT` và extras `uri`, `name`, `exclude` |
| App native | `sendBroadcast` với cùng action và extras (connect sẽ hiện thông báo "chạm để xem xét", vì Android 10+ không cho mở hộp xác nhận từ nền) |

- **SOCKS5 cục bộ**: gửi `uri=socks://127.0.0.1:4311`. Không cần cơ chế mới vì app đã hỗ trợ node SOCKS.
- **Tên hiển thị** (thanh trạng thái, thông báo, tile: `RUNNING - <tên>`): tham số `name`, rồi tên app gọi, rồi remark trong URI, cuối cùng là hostname. Chỉ là nhãn, không dùng để tin tưởng ai.
- **Chống vòng lặp**: app chạy SOCKS5 và ra mạng bằng chính thiết bị này phải được loại khỏi VPN, nếu không lưu lượng của nó quay vòng vào chính nó. Truyền `exclude=<package>`; app native đã xác minh mà phục vụ địa chỉ cục bộ sẽ được loại tự động. Chế độ non-root dùng danh sách disallowed-apps của VPN, chế độ root thêm uid vào chuỗi iptables `XRAY_EXCLUDE_APP`. Cả hai chỉ có hiệu lực trong lúc kết nối, bất kể công tắc Loại trừ ứng dụng.
- **Ngắt**: `magicv2ray://disconnect`, nút trong thông báo, tile Cài đặt nhanh, nút Dừng / Ngắt kết nối trong app. Khởi động lại máy cũng kết thúc phiên.
- **Bảo mật**: website nào cũng có thể bắn link `connect`, nên mọi kết nối đều phải xác nhận, hiện đủ uri, người gọi và app bị loại. Người gọi chỉ "đã xác minh" khi Android cung cấp (không bao giờ dùng `EXTRA_REFERRER` có thể giả mạo) và không phải trình duyệt. Node VLESS / Trojan không mã hóa vẫn giữ cảnh báo cũ. Tab **Cấp quyền truy cập** liệt kê các app "luôn tin"; tùy chọn này chỉ có với intent tường minh từ app native đã xác minh (không áp dụng cho link hay trình duyệt) và gắn với chứng chỉ ký của app. App đã tin vẫn bị hỏi với node không mã hóa và với mục loại trừ khác chính nó.

### Thông tin và sự kiện trạng thái (API level 1)

Ứng dụng khác có thể hỏi Magic V2Ray đang chạy phiên bản nào, hỗ trợ API gì, và được **báo khi proxy ngắt** (hoặc kết nối), bất kể ngắt bằng cách nào: app yêu cầu, Quick Settings tile, nút trên thông báo, crash, hoặc dừng từ root service.

**1. Info** (version, API hỗ trợ, trạng thái hiện tại)

```java
// A) ContentProvider: đồng bộ, không cần giao diện, gọi được từ mọi thread
Bundle b = getContentResolver().call(Uri.parse("content://com.huskydg.magicv2ray.api"), "getInfo", null, null);
String json = b.getString("json");        // JSON đầy đủ, xem bên dưới
int apiLevel = b.getInt("apiLevel");      // ngoài ra có: versionName, versionCode, mode, connected, known
// "getState" chỉ trả trạng thái. query(content://com.huskydg.magicv2ray.api/info hoặc /state) trả một dòng.

// B) Ordered broadcast: câu trả lời là JSON (result data) + extras json / versionName / apiLevel
Intent i = new Intent("com.huskydg.magicv2ray.action.GET_INFO").setPackage("com.huskydg.magicv2ray");
sendOrderedBroadcast(i, null, new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent r) { String json = getResultData(); }   // null = chưa cài Magic V2Ray
}, null, Activity.RESULT_CANCELED, null, null);
```

Android 11+: thêm `<queries><package android:name="com.huskydg.magicv2ray"/></queries>` vào manifest của app gọi.

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

| Trường | Ý nghĩa |
|---|---|
| `versionName`, `versionCode` | Phiên bản Magic V2Ray đang cài |
| `mode` | `root` hoặc `nonroot` |
| `api.level` | Tăng lên mỗi khi API có thêm thứ app khác cần kiểm tra |
| `api.actions`, `schemes`, `extras`, `overrides` | Các action, deep link, extras và nhóm `overrides` được hỗ trợ |
| `api.events`, `api.provider` | Sự kiện được phát và cách truy cập provider |
| `api.features` | Danh sách tính năng, dùng để kiểm tra từng tính năng |
| `state.connected` | Proxy đang chạy hay không |
| `state.known` | `false` khi root mode chưa đọc được trạng thái (chưa cấp root, service chưa chạy); khi đó `connected` chỉ là `false` mặc định |
| `state.session` | Phiên kết nối ngoài đang có (tên, app gọi, thời điểm bắt đầu); không có địa chỉ server hay cài đặt |

Nên kiểm tra `api.level` hoặc một tên trong `features` thay vì so sánh version của app. Dữ liệu trả về không bao giờ chứa địa chỉ server hay cài đặt.

**2. Sự kiện trạng thái**: broadcast `com.huskydg.magicv2ray.event.STATE_CHANGED`

| Extra | Giá trị |
|---|---|
| `state` / `connected` | `"connected"` / `"disconnected"` và `true` / `false` |
| `reason` | `started`, `session_started`, `session_ended` (phiên ngoài đã kết thúc), `stopped` (dừng bình thường), `error` (engine lỗi, mất quyền VPN), `crashed` |
| `session`, `sessionName`, `caller` | Có phiên ngoài liên quan hay không / tên phiên / package của app đã bắt đầu phiên |
| `mode` | `root` hoặc `nonroot` |
| `time`, `seq` | Thời điểm (epoch ms) và số thứ tự, tăng dần theo mỗi tiến trình Magic V2Ray |

```java
registerReceiver(new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
        if (!i.getBooleanExtra("connected", false)) {
            onMagicV2RayDisconnected(i.getStringExtra("reason"));
        }
    }
}, new IntentFilter("com.huskydg.magicv2ray.event.STATE_CHANGED"), Context.RECEIVER_EXPORTED);
```

- **Ai nhận được**: receiver đăng ký lúc chạy (`registerReceiver`) của mọi app nhận như broadcast thường; app sở hữu phiên có thể nhận cùng một sự kiện hai lần, hãy loại trùng theo `seq` + `time`. App đã bắt đầu phiên ngoài còn được gửi **trực tiếp** nên khai báo receiver trong manifest cũng nhận được. Với phiên bắt đầu bằng `sendBroadcast` trên Android dưới 14, Magic V2Ray không biết app nào gọi, hãy dùng receiver đăng ký lúc chạy hoặc observer bên dưới.
- **Dùng observer thay cho receiver**: `registerContentObserver(Uri.parse("content://com.huskydg.magicv2ray.api/state"), false, observer)`, khi được gọi thì đọc `getState`.
- **Thời điểm báo**: ngắt chỉ được báo nếu sau 2 giây engine vẫn tắt, nên restart hoặc reload (đổi node, đổi cài đặt) không bị tính là ngắt. Kết thúc phiên ngoài (`session_ended`) được báo ngay. Khởi động thất bại khi trước đó chưa chạy thì không có sự kiện.
- **Root mode**: root service gọi `am broadcast ... ENGINE_CHANGED` tới app, app đọc lại trạng thái thật rồi mới phát sự kiện. Nội dung broadcast đó không được tin, nên app khác gửi giả cũng chỉ khiến app kiểm tra lại trạng thái. Cách này cũng bắt được crash và lệnh dừng từ shell.
- **Giới hạn**: ở non-root, nếu tiến trình Magic V2Ray bị hệ thống kill thì VPN chết theo và không gửi được sự kiện nào. Khi việc này quan trọng, hãy gọi `getState` định kỳ.
- **Riêng tư**: sự kiện và info chỉ có trạng thái, không có địa chỉ server, uri node hay cài đặt.

### Node và các uri connect đặc biệt (API level 2, chỉ dành cho ứng dụng)

`api.level` là `2` khi có các feature `nodes`, `node_access_request`, `connect_freedom`, `connect_chain` và `connect_nodeid`.

**1. Quyền truy cập.** Ứng dụng chỉ đọc được node sau khi người dùng cho phép. Người gọi là package mà chính Android báo cho lệnh gọi đó, nên website, liên kết hay broadcast không bao giờ đọc được node và không ai giả danh được ứng dụng khác. Người dùng chọn ứng dụng thấy **gì** và **trong bao lâu**:

| Lựa chọn | Ý nghĩa |
|---|---|
| Thông tin ẩn danh (mặc định) | tên, giao thức và một id; node là `nodeid://<id>` thay vì `vless://...`. Không có máy chủ, cổng hay thông tin đăng nhập. |
| Thông tin node đầy đủ | liên kết node thật (`vless://...`), gồm cả máy chủ và thông tin đăng nhập. |
| Cho phép một lần | có hiệu lực 10 phút, chỉ lưu trong bộ nhớ. |
| Vĩnh viễn | giữ đến khi bị thu hồi trong tab **Quyền truy cập**; gắn với chứng chỉ ký của ứng dụng. |
| Hủy bỏ | không cấp gì cả. |

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

| Phương thức | Trả về |
|---|---|
| `getNodeAccess` | `granted`, `level`, `persistent`; không bao giờ hiện hộp thoại |
| `getNodes` | `json` = `{level, activeId, nodes:[{id, name, protocol, uri, (chỉ mức đầy đủ: category, address, port)}]}`, kèm `count`, `activeId` |
| `getActiveNode` | `json` = `{connected, known, source, session?, node}`, kèm `connected`, `source` |

`source` là `session` (một ứng dụng đặt node tạm thời), `selected` (node người dùng tự chọn) hoặc `direct` (node không proxy dựng sẵn). Ứng dụng ở mức ẩn danh không bao giờ thấy uri của node do ứng dụng đặt mà không phải node của người dùng: nó trả về dạng `<scheme>://hidden`. Chain luôn hiện dưới dạng `chain://localhost/?hopList=...`.

**2. Uri connect.** `uri` của một yêu cầu connect (liên kết, intent hoặc broadcast) còn có thể là:

| uri | Ý nghĩa |
|---|---|
| `freedom://localhost` | node "không proxy" dựng sẵn: xray chỉ làm bộ định tuyến, mọi quy tắc định tuyến vẫn áp dụng |
| `chain://localhost/?hopList=<urlencode(JSON array of node uris)>` | chuỗi proxy 2 đến 16 hop: `["vless://...", "vmess://...", "socks://...", ...]`. Mục đầu tiên được thiết bị quay số trực tiếp, mỗi hop sau đi qua đường hầm của hop trước, mục cuối là hop thoát. |
| `nodeid://<id>` | một node của chính người dùng, đứng riêng hoặc làm hop của chain. Chỉ dành cho ứng dụng đã xác minh và đã được phép đọc node (mức nào cũng được), nên ứng dụng ở mức ẩn danh vẫn dùng được node mà nó không bao giờ nhìn thấy. |

Vẫn có hộp thoại xác nhận như thường lệ: nó hiện các giao thức của chain (hoặc "trực tiếp"), và hop đầu quyết định ứng dụng có phải bị loại khỏi VPN hay không (`freedom://` không có máy chủ nào để lưu lượng quay vòng).

### Dùng từ Termux (hoặc shell bất kỳ)

Shell không đăng ký receiver được và không gọi provider được nếu không có root, nên dùng `am`. Các lệnh dưới đây suy ra từ cách API hoạt động; riêng lệnh info và vòng poll phụ thuộc việc `am` của Termux in kết quả broadcast giống `am` của hệ thống.

```sh
# Info: `am broadcast` là ordered broadcast và in câu trả lời dạng data="<json>"
am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray
# Có root thì gọi thẳng provider:
su -c 'content call --uri content://com.huskydg.magicv2ray.api --method getInfo'

# Connect / disconnect (connect vẫn cần người dùng xác nhận)
am start -a com.huskydg.magicv2ray.action.CONNECT -p com.huskydg.magicv2ray \
  --es uri 'socks://127.0.0.1:5353' --es name 'Termux' --es exclude com.termux
am start -a com.huskydg.magicv2ray.action.DISCONNECT -p com.huskydg.magicv2ray
```

Shell không nhận được `STATE_CHANGED`, nên hãy poll info. Script dưới đây kiểm tra API level, rồi báo mỗi lần trạng thái đổi cho tới khi Magic V2Ray ngắt:

```sh
#!/data/data/com.termux/files/usr/bin/sh
info() { am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray 2>/dev/null \
         | sed -n 's/.*data="\(.*\)".*/\1/p'; }
J=$(info)
[ -n "$J" ] || { echo "Magic V2Ray chưa cài hoặc quá cũ (không có API level 1)"; exit 1; }
echo "$J" | jq -r '"Magic V2Ray \(.versionName), API \(.api.level), \(.mode)"'
last=
while sleep 2; do
  c=$(info | jq -r '.state.connected')
  [ "$c" = "$last" ] || { echo "connected=$c"; last=$c; }
  [ "$c" = "false" ] && break
done
echo "Magic V2Ray đã ngắt"
```

Lưu ý: từ Android 10, `am start` chạy nền cần cấp cho Termux quyền "Hiển thị trên các ứng dụng khác". Magic V2Ray có nhận ra Termux là app gọi (để hiện "Luôn tin cậy") hay không tùy thứ Android báo cho `am`; app gọi không xác định thì luôn phải xác nhận. Cài `jq` bằng `pkg install jq`.

### Thiết lập tạm (`overrides`)

Ngoài node, `connect` có thể kèm tham số `overrides` để **đổi tạm** Thiết lập lưu lượng, Thiết lập mạng, Loại trừ ứng dụng, Định tuyến và Hosts tùy chỉnh trong lúc kết nối. `uri` là tùy chọn khi có `overrides` (khi đó dùng node bạn đang chọn).

- Liên kết: `magicv2ray://connect?uri=...&overrides=<base64url của JSON>`; intent / broadcast: extra `overrides` là chuỗi JSON.
- Chỉ các khóa trong danh sách trắng và đúng kiểu / khoảng giá trị mới được nhận, phần còn lại bị bỏ (xem `ExternalSession.sanitizeOverrides`). Không thể đổi: `exposeSocks*`, chế độ chuyên nghiệp, ngôn ngữ / giao diện. Quy tắc định tuyến chỉ nhận `outboundTag` là `proxy`, `direct`, `block`.

```json
{
  "traffic": { "mux": true, "mtu": 1400, "directDns": "quic+local://223.5.5.5" },
  "network": { "networkMode": 1, "allowTether": false, "bypassIfaces": ["tailscale0"], "bypassVpnService": true },
  "exclude": { "apps": ["com.example.app"], "mode": "add", "enabled": true },
  "routing": { "domainStrategy": "IPIfNonMatch", "mode": "prepend",
               "rules": [ { "domain": "geosite:google", "outboundTag": "proxy", "remarks": "tạm" } ] },
  "hosts":   { "mode": "merge", "entries": { "example.com": "1.2.3.4", "b.com": ["::1", "10.0.0.1"] } }
}
```

**Riêng tư: thiết lập tạm KHÔNG BAO GIỜ được lưu.**

| Chế độ | Nơi giữ phiên | Không bao giờ ghi vào |
|---|---|---|
| Root | `/mnt/runtime/magicv2ray/run/override/` (tmpfs, mất khi khởi động lại) | `/data/adb/magic_v2ray` |
| Non-root | `cache/override/` | `files/data/` (kể cả `config.v2.json`) |

- Engine đọc `config.v2.json`, `settings.base64`, `excludelist.txt`, `bypassIface.txt` trong thư mục phiên **thay cho** file cùng tên của bạn; file của bạn chỉ được đọc, không bị sửa. Ngắt kết nối = xóa thư mục phiên rồi khởi động lại engine trên thiết lập của bạn.
- Yêu cầu chờ xác nhận chỉ nằm trong RAM; phiên chỉ lưu metadata (tên, app gọi, danh sách loại trừ), không lưu server hay giá trị nào bị đổi.
- Phiên **không tự chạy lại sau khi khởi động lại** (root: `/mnt/runtime` bị xóa và service dọn thư mục phiên khi khởi động; non-root: VpnService là `START_NOT_STICKY`, phiên cũ bị bỏ khi app mở lại). App cần dùng tiếp thì phải gửi lại yêu cầu `connect` sau mỗi lần khởi động. Một phiên tạm cũng không đổi cờ `enabled` quyết định cái gì chạy lại khi boot.
- Yêu cầu có `overrides` được hỏi xác nhận (hộp thoại nêu rõ các mục bị đổi). App đã "luôn tin" thì **không bị hỏi lại khi gửi đúng `overrides` như lần được cho phép trước**. Nếu `overrides` khác (mới hoặc đã đổi), hộp thoại hiện lại với thông báo "ứng dụng đang muốn áp dụng thiết lập MỚI". Chọn **Luôn cho phép** để ghi nhớ bộ `overrides` mới; **Cho phép một lần** thì không ghi nhớ. Chỉ lưu dấu vân tay (SHA-256) của `overrides`, không lưu giá trị nào. Liên kết và trình duyệt không bao giờ được "luôn tin".
