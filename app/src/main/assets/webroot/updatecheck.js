// "Updates" (App settings): auto update check switch, manual check, and the Dashboard banner.
// The network request is made by the host (ksu.checkUpdate, see UpdateChecker.java): the WebView itself has
// no network access. The update itself is downloaded into the app cache and installed by the host
// (ksu.installUpdate, see ApkInstaller.java); progress comes back in window.mvApkInstall. Without
// installUpdate (older host) the APK link is opened in the browser through ksu.openUrl.
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
    // dismissal token: alpha builds can share a versionCode, so the version string is part of it
    function tok(u) { return u.versionCode + ':' + u.version; }
    function newer() {
        var l = info.last;
        return l && l.ok && l.available ? l : null;
    }

    // ---- minimal, safe Markdown -> HTML for release notes (everything is escaped first; no links/images)
    function esc(x) {
        return String(x).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
    function inline(x) {
        x = esc(x);
        x = x.replace(/`([^`]+)`/g, '<code>$1</code>');
        x = x.replace(/\*\*([^*]+)\*\*|__([^_]+)__/g, function (m, a, b) { return '<strong>' + (a || b) + '</strong>'; });
        x = x.replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1');
        return x;
    }
    function markdown(src) {
        var lines = String(src).replace(/\r/g, '').split('\n'), html = '', para = [], list = null;
        function flushP() { if (para.length) { html += '<p>' + inline(para.join(' ')) + '</p>'; para = []; } }
        function flushL() { if (list) { html += '</' + list + '>'; list = null; } }
        lines.forEach(function (ln) {
            var m;
            if (/^\s*$/.test(ln)) { flushP(); flushL(); }
            else if ((m = /^(#{1,6})\s+(.*)$/.exec(ln))) {
                flushP(); flushL();
                var lv = Math.min(m[1].length + 1, 4);
                html += '<h' + lv + '>' + inline(m[2]) + '</h' + lv + '>';
            } else if ((m = /^\s*(?:[-*+]|\d+\.)\s+(.*)$/.exec(ln))) {
                flushP();
                var tag = /^\s*\d/.test(ln) ? 'ol' : 'ul';
                if (list !== tag) { flushL(); html += '<' + tag + '>'; list = tag; }
                html += '<li>' + inline(m[1]) + '</li>';
            } else { flushL(); para.push(ln.trim()); }
        });
        flushP(); flushL();
        return html;
    }

    // ---- popup
    function showModal(upd) {
        var ov = $('update-modal-overlay');
        if (!ov) return;
        $('update-modal-sub').textContent = t('update_modal_sub', { version: upd.version, code: upd.versionCode });
        var box = $('update-modal-notes');
        if (upd.notes) box.innerHTML = markdown(upd.notes);
        else box.innerHTML = '<p class="update-notes-empty">' + esc(t('update_notes_none')) + '</p>';
        box.scrollTop = 0;
        $('update-modal-dl').style.display = safeUrl(upd.url) ? '' : 'none';
        ov.style.display = 'flex';
    }
    function hideModal() {
        var ov = $('update-modal-overlay');
        if (ov) ov.style.display = 'none';
    }
    var shownFor = null;     // versionCode the popup was already shown for in this page session
    function maybeShowModal() {
        var upd = newer();
        if (!upd || tok(upd) === stored(DISMISS_KEY)) return;
        if (shownFor === tok(upd)) return;
        shownFor = tok(upd);
        showModal(upd);
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
            var show = !!upd && tok(upd) !== stored(DISMISS_KEY);
            banner.style.display = show ? '' : 'none';
            if (show) {
                $('update-banner-title').textContent = t('update_banner_title', { version: upd.version });
                $('update-banner-sub').textContent = t('update_banner_sub', { current: cur });
                $('update-banner-dl').style.display = safeUrl(upd.url) ? '' : 'none';
            }
        }
    }

    // ---- in-app download + install (progress comes from ApkInstaller through MainActivity.apkEvent)
    var DL_IDS = ['app-update-dl', 'update-banner-dl', 'update-modal-dl'];
    var installing = false;
    function setBusy(label) {
        installing = true;
        DL_IDS.forEach(function (id) {
            var b = $(id);
            if (!b) return;
            b.disabled = true;
            b.textContent = label;
        });
        var status = $('app-update-status');
        if (status) status.textContent = label;
    }
    function setIdle() {
        installing = false;
        DL_IDS.forEach(function (id) {
            var b = $(id);
            if (!b) return;
            b.disabled = false;
            var key = b.getAttribute('data-i18n');
            if (key) b.textContent = t(key);
        });
        render();
    }
    function toast(msg, kind) {
        if (typeof showToast === 'function') showToast(msg, kind);
    }
    window.mvApkInstall = function (json) {
        var r = parse(json);
        if (!r) return;
        switch (r.state) {
            case 'downloading':
                setBusy(t('apk_dl_progress', { percent: r.percent || 0 }));
                break;
            case 'verifying':
                setBusy(t('apk_verifying'));
                break;
            case 'installing':
                setBusy(t('apk_installing'));
                break;
            case 'confirm':
                setIdle();
                toast(t('apk_confirm'));
                break;
            case 'need_permission':
                setIdle();
                toast(t('apk_need_perm'));
                break;
            case 'error':
                setIdle();
                if (r.code === 'aborted') toast(t('apk_cancelled'));
                else toast(t('apk_failed', { error: r.error || '' }), 'error');
                break;
            default:       // 'done' (rare: the app is replaced by the new version)
                setIdle();
        }
    };

    // called by the host when a background check finished (MainActivity.updateChanged)
    window.onUpdateInfo = function () { loadInfo(); render(); maybeShowModal(); };

    function init() {
        if (!$('update-box')) return;
        $('update-box').style.display = '';
        loadInfo();
        render();
        maybeShowModal();

        // The dynamic texts above are built with t(); they were produced before the UI language was
        // applied (or stay stale after a language switch), so rebuild them whenever applyI18n runs.
        if (typeof window.applyI18n === 'function' && !window.applyI18n.__mvUpdate) {
            var origApply = window.applyI18n;
            window.applyI18n = function () {
                var r = origApply.apply(this, arguments);
                try { render(); } catch (e) { /* page not ready */ }
                return r;
            };
            window.applyI18n.__mvUpdate = true;
        }
        setTimeout(render, 0);   // after the startup language pass

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
            if (r.available) { shownFor = null; maybeShowModal(); }
        };
        btn.addEventListener('click', function () {
            btn.disabled = true;
            checking = true;
            $('app-update-status').textContent = t('update_checking');
            try { k.checkUpdate('mvUpdateResult'); } catch (e) { window.mvUpdateResult(''); }
        });

        // ---- actions (settings row + dashboard banner)
        function download() {
            var u = newer();
            if (!u || installing) return;
            if (typeof k.installUpdate === 'function') {
                setBusy(t('apk_dl_progress', { percent: 0 }));
                try { k.installUpdate(); } catch (e) { setIdle(); openUrl(u.url); }
            } else {
                openUrl(u.url);          // host without in-app install: the browser downloads it
            }
        }
        $('app-update-dl').addEventListener('click', download);
        $('update-modal-dl').addEventListener('click', function () { download(); hideModal(); });
        function dismiss() {
            var u = newer();
            if (u) store(DISMISS_KEY, tok(u));       // do not nag again for this version
            hideModal();
            render();
        }
        $('update-modal-close').addEventListener('click', dismiss);
        $('update-modal-x').addEventListener('click', dismiss);
        // the banner / settings row open the popup with the notes
        $('update-banner-title').addEventListener('click', function () { var u = newer(); if (u) showModal(u); });
        $('update-banner-sub').addEventListener('click', function () { var u = newer(); if (u) showModal(u); });
        $('update-banner-dl').addEventListener('click', download);
        $('app-update-changelog').addEventListener('click', function () {
            var u = newer();
            if (u) openUrl(u.changelog);
        });
        $('update-banner-later').addEventListener('click', function () {
            var u = newer();
            if (u) store(DISMISS_KEY, tok(u));
            render();
        });

        // back from the background: a daily job may have refreshed the result meanwhile
        document.addEventListener('visibilitychange', function () {
            if (!document.hidden) { loadInfo(); render(); }
        });
    }
})();
