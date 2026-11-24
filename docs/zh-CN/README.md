# Magic V2Ray

[English](../README.md) | [Tiếng Việt](../vi/README.md) | [Русский](../ru/README.md) | [简体中文](./README.md) | [繁體中文（台灣）](../zh-TW/README.md) | [繁體中文（香港）](../zh-HK/README.md)

![screenshot_1](../images/screenshot_1.jpg)

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
- **服务商网站：** 将 VPN 商家的网站添加为分类，在内置迷你浏览器中打开，点击其“同步”按钮即可导入节点，并可将其快捷方式固定到主屏幕。参见[深度链接](API.md#服务商深度链接)。

---

## 安装与使用

Magic V2Ray 以独立的 Android 应用（`.apk`）形式发布，同时也可作为可刷入的 Root 模块 zip 使用。

> [!IMPORTANT]
> 从 [modules.kernelsu.org](https://modules.kernelsu.org) 下载的 `.zip` 文件**仅是安装器**，并不是 APK。请前往[仓库的 Releases 页面](https://github.com/vincentng295/Magic_V2Ray/releases)下载 APK。

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

## API 与集成

面向网站、VPN 服务商和其他需要集成 Magic V2Ray 的应用（深度链接、连接 / 断开、节点访问、临时设置）的全部内容见 [API.md](API.md)：

- [服务商深度链接](API.md#服务商深度链接)
- [从其他应用和网站连接](API.md#从其他应用和网站连接)
  - [信息与状态事件（API level 1）](API.md#信息与状态事件api-level-1)
  - [节点与特殊 connect uri（API level 2，仅限应用）](API.md#节点与特殊-connect-uriapi-level-2仅限应用)
  - [在 Termux（或任意 shell）中使用](API.md#在-termux或任意-shell中使用)
  - [临时设置（`overrides`）](API.md#临时设置overrides)

---

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

本项目采用 **GNU General Public License v3.0（GPL-3.0）** 发布。使用本项目即表示你同意许可证中规定的条款和条件。更多详情请参阅本仓库中的 [LICENSE](../../LICENSE) 文件。

简而言之，你可以自由使用、修改和分发本软件，前提是在你自己的项目中保留相同的许可证并公开源代码。此外，在闭源软件中使用其源代码或复刻 Magic V2Ray 的功能，可能违反许可证条款。
