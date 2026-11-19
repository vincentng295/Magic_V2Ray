// "Updates" (App settings): auto update check switch, manual check, and the Dashboard banner.
// The network request is made by the host (ksu.checkUpdate, see UpdateChecker.java): the WebView itself has
// no network access. The APK link is opened in the browser through ksu.openUrl.
(function () {
    'use strict';
    var k = window.ksu;
    var $ = function (id) { return document.getElementById(id); };
    if (!k || typeof k.checkUpdate !== 'function' || typeof k.getUpdateInfo !== 'function') return;
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();

    var DISMISS_KEY = 'mv_update_dismissed';     // versionCode of the update the user chose "Later" for
    var info = { current: {}, last: null };
    var checking = false;

    function parse(text) {
        try { return JSON.parse(text); } catch (e) { return null; }
    }
    function store(key, value) {
        try {
            if (value == null) localStorage.removeItem(key);
            else localStorage.setItem(key, String(value));
        } catch (e) { /* storage unavailable */ }
    }
    function stored(key) {
        try { return localStorage.getItem(key); } catch (e) { return null; }
    }
    function safeUrl(u) {
        return /^https:\/\/[^\s'"]+$/i.test(u || '') ? u : '';
    }
    function openUrl(u) {
        u = safeUrl(u);
        if (!u) return;
        try { k.openUrl(u); } catch (e) { /* host without openUrl */ }
    }
    function loadInfo() {
        var o = parse(k.getUpdateInfo());
        info = { current: (o && o.current) || {}, last: (o && o.last) || null };
    }
    function newer() {
        var l = info.last;
        return l && l.ok && l.available ? l : null;
    }

    function render() {
        var upd = newer(), cur = info.current.name || '';
        var status = $('app-update-status');
        if (status && !checking) {
            if (upd) status.textContent = t('update_available', { version: upd.version, current: cur });
            else if (info.last && info.last.ok) status.textContent = t('update_uptodate', { version: cur });
            else status.textContent = t('update_current', { version: cur });
        }

        var row = $('app-update-actions');
        if (row) {
            row.style.display = upd ? '' : 'none';
            if (upd) {
                $('app-update-new').textContent = upd.version;
                $('app-update-dl').style.display = safeUrl(upd.url) ? '' : 'none';
                $('app-update-changelog').style.display = safeUrl(upd.changelog) ? '' : 'none';
            }
        }

        var banner = $('update-banner');
        if (banner) {
            var show = !!upd && String(upd.versionCode) !== stored(DISMISS_KEY);
            banner.style.display = show ? '' : 'none';
            if (show) {
                $('update-banner-title').textContent = t('update_banner_title', { version: upd.version });
                $('update-banner-sub').textContent = t('update_banner_sub', { current: cur });
                $('update-banner-dl').style.display = safeUrl(upd.url) ? '' : 'none';
            }
        }
    }

    // called by the host when a background check finished (MainActivity.updateChanged)
    window.onUpdateInfo = function () { loadInfo(); render(); };

    function init() {
        if (!$('update-box')) return;
        $('update-box').style.display = '';
        loadInfo();
        render();

        // ---- auto check
        var auto = $('app-auto-update');
        try { auto.checked = !!k.getAutoUpdateCheck(); } catch (e) { auto.checked = true; }
        auto.addEventListener('change', function () {
            try { k.setAutoUpdateCheck(auto.checked); } catch (e) { /* host without the option */ }
        });

        // ---- manual check
        var btn = $('btn-update-check');
        window.mvUpdateResult = function (json) {
            btn.disabled = false;
            checking = false;
            var r = parse(json);
            if (!r || !r.ok) {
                var err = (r && r.error) || '';
                $('app-update-status').textContent = t('update_failed', { error: err });
                if (typeof showToast === 'function') showToast(t('update_failed', { error: err }), 'error');
                return;
            }
            info.last = r;
            if (r.current) info.current = r.current;
            if (r.available) store(DISMISS_KEY, null);          // asked for it: show the banner again
            else if (typeof showToast === 'function') showToast(t('toast_update_uptodate'));
            render();
        };
        btn.addEventListener('click', function () {
            btn.disabled = true;
            checking = true;
            $('app-update-status').textContent = t('update_checking');
            try { k.checkUpdate('mvUpdateResult'); } catch (e) { window.mvUpdateResult(''); }
        });

        // ---- actions (settings row + dashboard banner)
        function download() { var u = newer(); if (u) openUrl(u.url); }
        $('app-update-dl').addEventListener('click', download);
        $('update-banner-dl').addEventListener('click', download);
        $('app-update-changelog').addEventListener('click', function () {
            var u = newer();
            if (u) openUrl(u.changelog);
        });
        $('update-banner-later').addEventListener('click', function () {
            var u = newer();
            if (u) store(DISMISS_KEY, u.versionCode);
            render();
        });

        // back from the background: a daily job may have refreshed the result meanwhile
        document.addEventListener('visibilitychange', function () {
            if (!document.hidden) { loadInfo(); render(); }
        });
    }
})();
