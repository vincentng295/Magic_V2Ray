# Release Notes — Version 1.10

## Overview & Highlights

Version 1.10 focuses on **security hardening, core lifecycle stability, and battery optimization**.

This release patches a **critical root remote code execution (RCE)** vulnerability alongside severe UI/process management bugs.

> **Important Note:** This release contains **no breaking changes to the active iptables/ip-rule routing datapath**, ensuring a zero-risk upgrade path with no risk of breaking existing connectivity or causing bootloops.

> **Action Required:** Upgrading is **strongly recommended** for all users. If you have ever imported subscriptions or nodes from third-party links, update immediately to address critical security vulnerabilities.

---

## Security Fixes (Critical)

* **Root Command Injection via Subscription Content (CVE-level):** Fixed an issue where Xray configuration writes allowed unescaped single quotes. Malicious nodes or subscriptions containing shell metacharacters in fields (e.g., `address`, `path`, `host`, `id`, `password`) could break out of quotes and execute arbitrary commands as **root**. Config and state writes now stream through a safe base64 pipe.
* **Strict TLS Enforcement for Subscriptions:** Disabled `curl -k` by default to prevent Man-in-the-Middle (MitM) payload injection. Subscriptions with self-signed certificates can explicitly opt out using the new **"Skip TLS certificate check"** switch.
* **HTTPS Update Manifest:** Updated `updateJson` URL scheme from HTTP to HTTPS to prevent malicious update source hijacking.
* **XSS-to-Root Mitigation:** Node fields and category names are now rendered using DOM APIs/text nodes instead of raw HTML interpolation, preventing script execution within root-privileged web bridges.
* **Probe File Hardening:** Connectivity test probe configurations containing server credentials are now stored in the module's private `tmpfs` with strict `0600` permissions and automatic process/file cleanup.

---

## Reliability & Stability

* **Crash Recovery & Service Tracking:** Fixed an issue where stale process mounts locked the service status into a perpetual "crashed" state. Implemented PID-file fallbacks and proper mount reconciliation on service restart/stop.
* **Network Interface & Latency Monitor Isolation:** Separated PID tracking between the interface monitor and latency monitor to prevent Wi-Fi ↔ Mobile switching routines from dying silently.
* **IP Hunter Loop Protection:** Added backoff limits and exponential backoff handling to prevent infinite telephony restart loops when using unmatchable IP prefixes.
* **Config Parsing Safeguards:** Malformed URIs and invalid Base64 Vmess links are now caught and rejected prior to service application, preventing system black-holes on boot.
* **Installer Teardown & Module Uninstall (`uninstall.sh`):** Fixed uninstallation logic to ensure complete cleanup of leftover server addresses, keys, netfilter rules, routing policies, and sysctl configs upon module removal.

---

## Battery & Resource Optimization

* **Smart Latency Probe Timeout:** Latency checks now rely on a UI heartbeat signal and automatically stop within ~15s after closing the WebUI. Reduced probe frequency from 1s to 2s.
* **Background Activity Suppression:** Suspended polling routines and active root shell execution when the WebUI is hidden or backgrounded (`visibilitychange` / `pagehide`).
* **Debounced Network Handlers:** Consolidated rapid kernel route change events during Wi-Fi ↔ Mobile handovers to reduce process fork spikes (~35 events coalesced into ~2 runs).
* **Orphaned Process Elimination:** Enforced strict process lifetimes to prevent background leaks from `ip monitor`, Xray crashes, and node probe tasks.