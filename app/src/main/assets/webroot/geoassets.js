// Routing settings > "Geo databases": import / download / remove geoip.dat, geosite.dat and any extra
// *.dat that rules reach through "ext:file.dat:tag".
//
// The files live in GEO_ASSETS_DIR (<DATADIR>/location_assets), which the engine reads through
// XRAY_LOCATION_ASSET (root: the service hands xray an fd of that directory; non-root: the plain path).
// It is separate from the module dir on purpose: the app checks everything in the module dir against its
// own APK and would put the bundled databases back. geoip.dat / geosite.dat the user has no file for are
// filled with a copy of the bundled ones, so removing a user file brings a bundled copy back.
//
// Picking a file is native (ksu.importGeoFile): a 20 MB database must not travel through the WebView.
(function () {
    'use strict';
    var $ = function (id) { return document.getElementById(id); };
    var NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\.dat$/;
    // Defaults offered in the download box (daily builds of the community lists).
    var PRESETS = {
        'geoip.dat': 'https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat',
        'geosite.dat': 'https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat'
    };
    var busy = false;

    function fmtSize(n) {
        n = Number(n) || 0;
        if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
        if (n >= 1024) return Math.round(n / 1024) + ' KB';
        return n + ' B';
    }
    function fmtDate(sec) {
        var d = new Date((Number(sec) || 0) * 1000);
        if (isNaN(d.getTime()) || !sec) return '';
        return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    }

    // name|size|mtime|link, one line per file; *.new are half-written temp files and are skipped.
    async function list() {
        var out = await execShellAsync(
            'cd ' + shQuote(GEO_ASSETS_DIR) + ' 2>/dev/null || exit 0\n' +
            'for f in *.dat; do\n' +
            '  [ -e "$f" ] || continue\n' +
            '  b=0; [ -f ' + shQuote(MODDIR + '/bin/') + '"$f" ] && cmp -s "$f" ' + shQuote(MODDIR + '/bin/') + '"$f" && b=1\n' +
            '  s=$(stat -L -c %s "$f" 2>/dev/null); m=$(stat -L -c %Y "$f" 2>/dev/null)\n' +
            '  echo "$f|$s|$m|$b"\n' +
            'done');
        var rows = [];
        out.split('\n').forEach(function (line) {
            var p = line.trim().split('|');
            if (p.length === 4 && NAME_RE.test(p[0])) rows.push({ name: p[0], size: p[1], mtime: p[2], bundled: p[3] === '1' });
        });
        return rows;
    }

    async function refresh() {
        var box = $('geo-assets-list');
        if (!box) return;
        var rows = await list();
        box.textContent = '';
        if (!rows.length) {
            var e = document.createElement('div');
            e.className = 'routing-strategy-hint';
            e.textContent = t('geo_empty');
            box.appendChild(e);
            return;
        }
        rows.forEach(function (r) {
            var row = document.createElement('div');
            row.className = 'setting-item-row';
            var lab = document.createElement('label');
            var nm = document.createElement('span');
            nm.textContent = r.name;
            var sm = document.createElement('small');
            sm.textContent = [r.bundled ? t('geo_bundled') : t('geo_custom'), fmtSize(r.size), r.bundled ? '' : fmtDate(r.mtime)]
                .filter(Boolean).join(' · ');
            lab.appendChild(nm);
            lab.appendChild(sm);
            row.appendChild(lab);
            // a copy identical to the bundled file has nothing to remove
            if (!r.bundled) {
                var b = document.createElement('button');
                b.type = 'button';
                b.className = 'btn btn-secondary';
                b.textContent = t('geo_btn_delete');
                b.onclick = function () { remove(r.name); };
                row.appendChild(b);
            }
            box.appendChild(row);
        });
    }

    // The engine reads the databases when it starts, so a change needs a restart (only if it is running).
    async function offerRestart() {
        var status = await execShellAsync(MODDIR + '/bin/xhuskydg_helper proxy_control status');
        if (status !== 'running') return;
        if (!(await showConfirm(t('geo_confirm_restart')))) return;
        showLoading(t('toast_reload_xray'));
        _forceRestart({ onlyIfRunning: true }, function () { refresh(); });
    }

    function importFile(target) {
        if (busy) return;
        if (!window.ksu || typeof ksu.importGeoFile !== 'function') { showToast(t('geo_toast_failed', { err: 'unsupported' }), 'error'); return; }
        busy = true;
        var cb = 'geoImportCb_' + Date.now();
        window[cb] = function (code, name, err) {
            delete window[cb];
            busy = false;
            if (code === 0) {
                showToast(t('geo_toast_imported', { name: name }), 'success');
                refresh();
                offerRestart();
            } else if (err !== 'cancelled') {
                showToast(t('geo_toast_failed', { err: err }), 'error');
            }
        };
        ksu.importGeoFile(target || '', cb);
    }

    async function remove(name) {
        if (!NAME_RE.test(name)) return;
        if (!(await showConfirm(t('geo_confirm_delete', { name: name })))) return;
        var D = shQuote(GEO_ASSETS_DIR), N = shQuote(name);
        // geoip.dat / geosite.dat: put a copy of the bundled file back right away
        var T = shQuote(GEO_ASSETS_DIR + '/' + name + '.new');
        var recopy = (name === 'geoip.dat' || name === 'geosite.dat')
            ? 'cp ' + shQuote(MODDIR + '/bin/' + name) + ' ' + T + ' && chmod 644 ' + T + ' && chown 0:0 ' + T + ' 2>/dev/null; ' +
              'chcon u:object_r:system_file:s0 ' + T + ' 2>/dev/null; mv -f ' + T + ' ' + D + '/' + N + ' 2>/dev/null\n' : '';
        await execShellAsync('rm -f ' + D + '/' + N + '\n' + recopy + 'echo ok');
        showToast(t('geo_toast_deleted', { name: name }), 'success');
        await refresh();
        offerRestart();
    }

    async function download() {
        if (busy) return;
        var target = $('geo-dl-target').value;
        var url = ($('geo-dl-url').value || '').trim();
        if (!NAME_RE.test(target)) return;
        if (!/^https?:\/\/[^\s'"`$\\]+$/i.test(url)) { showToast(t('geo_err_url'), 'error'); return; }
        busy = true;
        var btn = $('geo-dl-btn');
        if (btn) btn.disabled = true;
        showToast(t('geo_downloading'), 'success');
        var D = shQuote(GEO_ASSETS_DIR), T = shQuote(GEO_ASSETS_DIR + '/' + target + '.new'), F = shQuote(GEO_ASSETS_DIR + '/' + target);
        var out = await execShellAsync(
            'umask 022\n' +
            'mkdir -p ' + D + ' && chmod 755 ' + D + '\n' +
            shQuote(MODDIR + '/bin/curl') + ' -fL --max-redirs 5 --connect-timeout 15 --max-time 600 -o ' + T + ' ' + shQuote(url) + ' 2>/dev/null || { rm -f ' + T + '; echo FAIL_DL; exit 0; }\n' +
            // same sanity checks as the native importer: not tiny, and a protobuf list (first byte 0x0a)
            '[ "$(stat -c %s ' + T + ' 2>/dev/null || echo 0)" -ge 16 ] || { rm -f ' + T + '; echo FAIL_SIZE; exit 0; }\n' +
            '[ "$(head -c1 ' + T + ' | od -An -tx1 | tr -d \' \')" = "0a" ] || { rm -f ' + T + '; echo FAIL_FORMAT; exit 0; }\n' +
            'chmod 644 ' + T + '; chown 0:0 ' + T + ' 2>/dev/null; chcon u:object_r:system_file:s0 ' + T + ' 2>/dev/null\n' +
            'mv -f ' + T + ' ' + F + ' && echo OK || { rm -f ' + T + '; echo FAIL_MV; }');
        busy = false;
        if (btn) btn.disabled = false;
        if (out.indexOf('OK') >= 0 && out.indexOf('FAIL') < 0) {
            showToast(t('geo_toast_downloaded', { name: target }), 'success');
            await refresh();
            offerRestart();
        } else {
            var why = out.indexOf('FAIL_FORMAT') >= 0 ? t('geo_err_format')
                : out.indexOf('FAIL_SIZE') >= 0 ? t('geo_err_size') : t('geo_err_download');
            showToast(t('geo_toast_failed', { err: why }), 'error');
        }
    }

    function targetChanged() {
        var sel = $('geo-dl-target'), inp = $('geo-dl-url');
        if (!sel || !inp) return;
        var cur = (inp.value || '').trim();
        var isPreset = !cur || Object.keys(PRESETS).some(function (k) { return PRESETS[k] === cur; });
        if (isPreset && PRESETS[sel.value]) inp.value = PRESETS[sel.value];
    }

    window.geoImport = importFile;
    window.geoDownload = download;
    window.geoTargetChanged = targetChanged;
    window.geoRefresh = refresh;

    // refresh the list whenever the Routing tab is opened
    var prev = window.switchTab;
    if (typeof prev === 'function') {
        window.switchTab = function (id) {
            var r = prev.apply(this, arguments);
            if (id === 'tab-routing') refresh();
            return r;
        };
    }
    document.addEventListener('DOMContentLoaded', targetChanged);
})();
