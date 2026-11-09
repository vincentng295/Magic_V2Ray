// Glue for the Magic V2Ray APK host (WebView). Loaded first, before helper.js.
//
// The APK injects a `ksu`-compatible bridge (exec / listPackages /
// getPackagesInfo) so the rest of the WebUI runs unchanged. This file only adds
// what a plain WebView lacks: a clipboard that works without a permission
// prompt. It is a no-op when the bridge is absent (e.g. a desktop browser).
(function () {
    'use strict';
    var k = window.ksu;
    if (!k || typeof k.readClipboard !== 'function') return;
    var clip = {
        readText: function () {
            return new Promise(function (resolve, reject) {
                try { resolve(String(k.readClipboard() || '')); } catch (e) { reject(e); }
            });
        },
        writeText: function (text) {
            return new Promise(function (resolve, reject) {
                try { k.writeClipboard(String(text == null ? '' : text)); resolve(); } catch (e) { reject(e); }
            });
        }
    };
    try {
        Object.defineProperty(navigator, 'clipboard', { value: clip, configurable: true });
    } catch (e) { /* keep the native one */ }
})();
