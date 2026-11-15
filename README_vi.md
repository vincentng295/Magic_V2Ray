# Magic V2Ray

[View English version](./README.md)

![screenshot_1](images/screenshot_1.jpg)

Một ứng dụng quản lý proxy Internet mạnh mẽ và hiện đại cho Android (hỗ trợ cả thiết bị **Root** và **Chưa Root / Non-Root**). Dự án giúp bạn định tuyến toàn bộ lưu lượng mạng của hệ thống và ứng dụng qua proxy để bảo mật kết nối, vượt tường lửa và chia sẻ mạng cho các thiết bị khác.

---

## Magic V2Ray là gì?

**Magic V2Ray** là giải pháp mạng nâng cao dành cho Android. Được thiết kế lại thành một ứng dụng Android độc lập, ứng dụng đóng gói sẵn toàn bộ các binary và tài nguyên quản lý proxy để tạo ra trải nghiệm mượt mà nhất.

Từ phiên bản **v2.0**, Magic V2Ray chính thức hỗ trợ **Chế độ Non-Root (VpnService)**, cho phép người dùng máy chưa root sử dụng lõi proxy an toàn và tiện lợi. Đối với máy đã root, ứng dụng vẫn giữ nguyên sức mạnh với cơ chế định tuyến tiến trình daemon cấp hệ thống.

Đi kèm với ứng dụng là một giao diện Web UI tối giản, giúp bạn dễ dàng tổ chức các cấu hình proxy, quản lý liên kết đăng ký (subscription) và kiểm soát mạng chỉ với vài thao tác.

---

## Các chế độ hoạt động chính

### 1. Chế độ Root (Daemon Engine)
Dành cho máy đã root (Magisk, KernelSU, APatch) với hiệu năng tối đa:
* **Kết nối bền vững, không lo văng app:** Hoạt động như một tiến trình hệ thống (system daemon). Trình quản lý bộ nhớ RAM (Low Memory Killer) của Android sẽ không tự ý tắt ứng dụng khi thiếu RAM.
* **Định tuyến toàn diện:** Đánh nhãn gói tin ngay tại tầng Netfilter, hỗ trợ định tuyến cho cả các ứng dụng/tiến trình hệ thống mà API VPN thông thường không thể can thiệp.
* **Không chiếm dụng ô VPN mặc định:** Hoạt động độc lập mà không bật biểu tượng chìa khóa VPN trên thanh trạng thái, không lo xung đột khi dùng chung với ứng dụng VPN khác.
* **Phát Hotspot / Tethering trực tiếp:** Đánh chặn lưu lượng tại chuỗi `PREROUTING`, tự động định tuyến toàn bộ các thiết bị bắt Wi-Fi Hotspot hoặc USB Tethering qua đường hầm proxy.
* **Chuyển mạng siêu tốc:** Phát hiện sự kiện chuyển đổi giữa Wi-Fi ↔ 4G/5G trực tiếp từ kernel (`ip monitor`) để áp dụng ngay lập tức các quy tắc mạng.

### 2. Chế độ Non-Root (VpnService)
Từ phiên bản **v2.0**, các thiết bị chưa root có thể chạy Magic V2Ray dễ dàng thông qua API `VpnService` tích hợp sẵn của Android:
* Chạy an toàn ở không gian người dùng (user-space).
* Dễ dàng cài đặt và sử dụng ngay mà không cần quyền Root hay module kernel.

---

## Tính năng nổi bật khác

- **Phím tắt Quick Settings (Quick Settings Tile):** Bật/tắt nhanh proxy ngay từ thanh thông báo Quick Settings. Nhấn giữ phím tắt để mở nhanh ứng dụng.
- **Cập nhật Module trực tiếp (Live Module Update):** Hỗ trợ cập nhật trực tiếp cho phiên bản 176 trở lên, giúp nâng cấp service mượt mà mà không cần khởi động lại thiết bị.
- **Quản lý danh mục (Category Organizing):** Gom nhóm các proxy server vào các thư mục hoặc danh mục tùy chỉnh.
- **Nhập liên kết thông minh (Smart Subscription Import):** Dán nhanh các URL đăng ký, chuỗi cấu hình thô hoặc đoạn mã hỗn hợp.
- **Cập nhật 1-Click (Auto-Reload):** Lưu liên kết đăng ký để cập nhật lại toàn bộ server chỉ bằng một thao tác.
- **Chia sẻ điểm phát sóng không cần root:** Ở chế độ không cần root (Non-Root mode), Shizuku có thể chia sẻ proxy với các thiết bị kết nối qua điểm phát sóng hoặc cáp USB (Android 13 trở lên).
- **Chia sẻ Hotspot Native:** Chia sẻ kết nối proxy cho máy tính, console hoặc điện thoại khác qua Wi-Fi Hotspot với đầy đủ hỗ trợ IPv4 và IPv6.
- **Website nhà cung cấp:** Thêm website bán VPN thành một danh mục, mở bằng mini trình duyệt tích hợp, nhấn nút Đồng bộ để nhập node và ghim phím tắt ra màn hình chính. Xem [Deep Link](#deep-link-cho-nhà-cung-cấp).

---

## Cài đặt & Sử dụng

File APK phát hành của Magic V2Ray vừa là ứng dụng Android độc lập, vừa đóng vai trò là một file zip module có thể flash qua bộ quản lý Root.

### Dành cho máy Chưa Root (Non-Root)
1. Tải và cài đặt file `magic_v2ray-*-arm64-v8a.apk` (hoặc `-x86_64.apk`).
2. Mở app, chọn **Chế độ Non-Root (VpnService)**, cấp quyền VPN và bắt đầu sử dụng.

### Dành cho máy Đã Root (Magisk / KernelSU / APatch)
**Cách A: Flash qua Root Manager (Cài cả Module + App)**
1. Đổi tên file `magic_v2ray-*.apk` thành `magic_v2ray-*.zip`.
2. Flash file zip trong Magisk / KernelSU / APatch (Modules → Install from storage). Hệ thống sẽ cài module nền và tự động gọi `pm install` để cài ứng dụng.
3. Khởi động lại máy hoặc mở trực tiếp ứng dụng.

**Cách B: Cài file APK thông thường**
1. Cài đặt `magic_v2ray-*-arm64-v8a.apk` rồi mở app.
2. Cấp quyền Root. Ứng dụng sẽ tự động kiểm tra và cài đặt/cập nhật module daemon tại `/data/adb/modules/magic_v2ray` (cài đặt cá nhân được giữ tại `/data/adb/magic_v2ray`).

*Lưu ý: Nút **Action** của module (Magisk 27+ / KernelSU / APatch) cho phép mở nhanh ứng dụng và tự động cài `app.apk` nếu thiết bị chưa cài đặt.*

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
| Root | `/dev/sysctl_stubs/run/override/` (tmpfs, mất khi khởi động lại) | `/data/adb/magic_v2ray` |
| Non-root | `cache/override/` | `files/data/` (kể cả `config.v2.json`) |

- Engine đọc `config.v2.json`, `settings.base64`, `excludelist.txt`, `bypassIface.txt` trong thư mục phiên **thay cho** file cùng tên của bạn; file của bạn chỉ được đọc, không bị sửa. Ngắt kết nối = xóa thư mục phiên rồi khởi động lại engine trên thiết lập của bạn.
- Yêu cầu chờ xác nhận chỉ nằm trong RAM; phiên chỉ lưu metadata (tên, app gọi, danh sách loại trừ), không lưu server hay giá trị nào bị đổi.
- Phiên **không tự chạy lại sau khi khởi động lại** (root: `/dev` bị xóa và service dọn thư mục phiên khi khởi động; non-root: VpnService là `START_NOT_STICKY`, phiên cũ bị bỏ khi app mở lại). App cần dùng tiếp thì phải gửi lại yêu cầu `connect` sau mỗi lần khởi động. Một phiên tạm cũng không đổi cờ `enabled` quyết định cái gì chạy lại khi boot.
- Yêu cầu có `overrides` **luôn hỏi xác nhận**, kể cả app đã "luôn tin", và hộp thoại nêu rõ các mục bị đổi.

## Build từ mã nguồn

```sh
./build.sh          # Xuất các file APK (arm64-v8a, x86_64, universal) vào thư mục dist/
                    # (Mỗi file APK có thể đổi đuôi sang .zip để làm bộ cài module)

```

**Yêu cầu môi trường:**

* JDK 17+
* Gradle 8.7+ (Khuyến nghị 8.9)
* Android SDK (`ANDROID_HOME`, platform `android-35`)
* Android NDK (dành cho phần native helper)

Mã nguồn Web UI nằm tại `app/src/main/assets/webroot`. Ứng dụng cung cấp sẵn cầu nối tương thích với `window.ksu` (`exec`, `listPackages`, `getPackagesInfo`).

---

## Ghi nhận & Đóng góp

* **[Xray-core](https://github.com/XTLS/Xray-core):** Lõi xử lý proxy thế hệ mới (VLESS, VMess, Trojan, v.v.).
* **[curl-android](https://github.com/vvb2060/curl-android):** Công cụ `curl` và thư viện tĩnh `libcurl` biên dịch cho Android.

---

## Giấy phép (License)

Dự án này được phát hành dưới giấy phép **GNU General Public License v3.0 (GPL-3.0)**. Bằng cách sử dụng dự án này, bạn đồng ý với các điều khoản và điều kiện được quy định trong giấy phép. Để biết thêm chi tiết, vui lòng xem tệp [LICENSE](LICENSE) trong kho lưu trữ này.

Về cơ bản, bạn được tự do sử dụng, sửa đổi và phân phối phần mềm này, miễn là bạn giữ nguyên giấy phép và công khai mã nguồn trong dự án của chính bạn. Thêm nữa, việc sử dụng mã nguồn hoặc sao chép các tính năng của Magic V2Ray trong phần mềm nguồn đóng có thể vi phạm điều khoản giấy phép.