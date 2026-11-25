# Magic V2Ray

[English](../README.md) | [Tiếng Việt](../vi/README.md) | [Русский](../ru/README.md) | [简体中文](../zh-CN/README.md) | [繁體中文（台灣）](./README.md) | [繁體中文（香港）](../zh-HK/README.md)

![screenshot_1](../images/screenshot_1.jpg)

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
- **供應商網站：** 將 VPN 業者的網站新增為分類，在內建迷你瀏覽器中開啟，點選其「同步」按鈕即可匯入節點，並可將其捷徑釘選到主畫面。請參閱[深層連結](API.md#供應商深層連結)。

---

## 安裝與使用

Magic V2Ray 以獨立的 Android 應用程式（`.apk`）形式發行，同時也可作為可刷入的 Root 模組 zip 使用。

> [!IMPORTANT]
> 從 [modules.kernelsu.org](https://modules.kernelsu.org) 下載的 `.zip` 檔案**僅是安裝器**，並不是 APK。請前往[儲存庫的 Releases 頁面](https://github.com/vincentng295/Magic_V2Ray/releases)下載 APK。

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

## API 與整合

供網站、VPN 商家與其他要整合 Magic V2Ray 的應用程式使用的全部內容（深層連結、連線 / 中斷、節點存取、暫時設定）都記載於 [API.md](API.md)：

- [供應商深層連結](API.md#供應商深層連結)
- [從其他應用程式與網站連線](API.md#從其他應用程式與網站連線)
  - [資訊與狀態事件（API level 1）](API.md#資訊與狀態事件api-level-1)
  - [節點與特殊 connect uri（API level 2，僅限應用程式）](API.md#節點與特殊-connect-uriapi-level-2僅限應用程式)
  - [在 Termux（或任何 shell）中使用](API.md#在-termux或任何-shell中使用)
  - [暫時設定（`overrides`）](API.md#暫時設定overrides)

---

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
* **[xray-rust](https://github.com/aimalygin/xray-rust)：** 行動裝置優先的 Rust 實作，支援 VLESS、REALITY、Vision、Trojan、Shadowsocks 2022、VMess AEAD、Hysteria 2、WireGuard，以及 TUN、路由和 DNS，適用於可嵌入的代理用戶端。已作為可選的 `xray-rs` 核心整合到應用程式中。
* **[curl-android](https://github.com/vvb2060/curl-android)：** 為 Android 預先編譯的 `curl` 命令列工具與 `libcurl` 靜態函式庫 prefab。

---

## 授權條款

本專案採用 **GNU General Public License v3.0（GPL-3.0）** 發行。使用本專案即表示你同意授權條款中規定的條款與條件。更多詳情請參閱本儲存庫中的 [LICENSE](../../LICENSE) 檔案。

簡而言之，你可以自由使用、修改與散布本軟體，前提是在你自己的專案中保留相同的授權條款並公開原始碼。此外，在閉源軟體中使用其原始碼或仿製 Magic V2Ray 的功能，可能違反授權條款。
