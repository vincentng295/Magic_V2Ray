// "Export app logs" (App settings tab).
//
// The WebUI builds the two files that need knowledge of its own state:
//   settings.txt  every option of the Traffic / Network settings tabs with its ENGLISH label
//                 and value (read from the form, so it matches what the user sees whatever
//                 the UI language is), plus run mode, engine status, exclude apps, routing, ...
//   config.txt    the active node's URI payload, UUIDs / passwords replaced with xxxxx
// and hands them to the APK host (ksu.exportLogs -> LogExporter.java), which adds app.log,
// xray.log, config.json, deviceinfo.txt, network.txt, ... and saves one zip to
// Download/MagicV2Ray.
(function () {
    'use strict';
    var $ = function (id) { return document.getElementById(id); };
    var MASK = 'xxxxx';
    var UUID_RE = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
    var SECRET_PARAM_RE = /^(password|pass|passwd|pwd|auth|auth-str|obfs-password|psk|secret|secretkey|privatekey|private_key|private-key|key|token|id|uuid|sid)$/i;

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();

    function init() {
        var btn = $('btn-export-logs');
        if (!btn) return;
        // Only the APK host can write the zip; a plain browser has no ksu.exportLogs.
        if (!(window.ksu && typeof window.ksu.exportLogs === 'function')) { btn.disabled = true; return; }
        btn.addEventListener('click', exportLogs);
    }

    // ------------------------------------------------------------ english labels

    function enText(el) {
        var key = el && el.getAttribute && el.getAttribute('data-i18n');
        var en = (typeof i18n === 'object' && i18n && i18n.en) || {};
        if (key && en[key]) return en[key];
        return el ? String(el.textContent || '').replace(/\s+/g, ' ').trim() : '';
    }

    function isHiddenWithin(el, root) {
        for (var n = el; n && n !== root; n = n.parentElement) {
            if (n.hidden || (n.style && n.style.display === 'none')) return true;
        }
        return false;
    }

    function controlValue(ctl) {
        if (ctl.type === 'checkbox') return ctl.checked ? 'ON' : 'OFF';
        if (ctl.type === 'radio') return ctl.checked ? 'selected' : null;
        if (ctl.tagName === 'SELECT') {
            var o = ctl.options[ctl.selectedIndex];
            if (!o) return '';
            var key = o.getAttribute('data-i18n');
            var en = (typeof i18n === 'object' && i18n && i18n.en) || {};
            var text = (key && en[key]) || String(o.textContent || '').trim() || o.value;
            return text === o.value ? text : text + ' [' + o.value + ']';
        }
        var v = String(ctl.value == null ? '' : ctl.value);
        if (v === '') return '(empty)';
        if (v.length > 300) {
            return v.length + ' chars, ' + v.split(/\r?\n/).length + ' lines: ' + v.slice(0, 120).replace(/\r?\n/g, ' / ') + ' ...';
        }
        return v.replace(/\r?\n/g, ' / ');
    }

    // Lists every control of a settings tab as "Label: value", grouped by the tab's boxes.
    function describeTab(tabId) {
        var tab = $(tabId);
        if (!tab) return ['(tab not found: ' + tabId + ')'];
        var out = [], lastGroup = null;
        tab.querySelectorAll('.setting-item-row').forEach(function (row) {
            var ctls = row.querySelectorAll('input:not([type=button]):not([type=submit]), select, textarea');
            if (!ctls.length) return;
            var box = row.closest('.settings-group-box');
            var h4 = box && box.querySelector('h4');
            var group = h4 ? enText(h4) : '';
            if (group !== lastGroup) {
                if (out.length) out.push('');
                out.push('[' + (group || 'General') + ']');
                lastGroup = group;
            }
            var labelEl = row.querySelector('h4') || row.querySelector('label span[data-i18n]') ||
                          row.querySelector('label[data-i18n]') || row.querySelector('label');
            var label = labelEl ? enText(labelEl) : '';
            ctls.forEach(function (ctl, i) {
                var val = controlValue(ctl);
                if (val === null) return;
                var name = label || ctl.id || ctl.name || '(unnamed)';
                if (ctls.length > 1 && ctl.id) name += ' <' + ctl.id + '>';
                var flags = '';
                if (ctl.disabled) flags += ' [disabled]';
                if (isHiddenWithin(ctl, tab)) flags += ' [hidden]';
                out.push('  ' + name + ': ' + val + flags);
            });
        });
        return out;
    }

    // ------------------------------------------------------------ masking

    function maskText(s) { return String(s == null ? '' : s).replace(UUID_RE, MASK); }

    function safeDecode(s) { try { return decodeURIComponent(s); } catch (e) { return s; } }

    // URI payload with the credential hidden: the userinfo part (UUID / password / auth), secret
    // query parameters, and any UUID-shaped string left anywhere else.
    function maskUri(uri) {
        uri = String(uri == null ? '' : uri).trim();
        if (!uri) return '(empty)';
        try {
            if (/^chain:\/\//i.test(uri)) {
                var q = new URL(uri.replace(/^chain:\/\//i, 'https://')).searchParams;
                return 'chain://\n    hop1 = ' + maskUri(q.get('hop1')) + '\n    hop2 = ' + maskUri(q.get('hop2'));
            }
            if (/^vmess:\/\//i.test(uri)) {
                var body = tryDecodeBase64(uri.slice(8));
                var j = body && JSON.parse(body);
                if (j && typeof j === 'object') {
                    ['id', 'uuid'].forEach(function (k) { if (j[k]) j[k] = MASK; });
                    return 'vmess:// (decoded JSON) ' + maskText(JSON.stringify(j));
                }
            }
            if (/^ss:\/\//i.test(uri) && uri.split('#')[0].indexOf('@') < 0) {
                // legacy ss://BASE64(method:password@host:port)#name
                var dec = tryDecodeBase64(uri.slice(5).split('#')[0]);
                if (dec && dec.indexOf('@') > 0) {
                    var tail = dec.slice(dec.lastIndexOf('@') + 1);
                    var method = dec.split(':')[0];
                    return 'ss:// (decoded) ' + method + ':' + MASK + '@' + tail + (uri.indexOf('#') >= 0 ? ' #' + uri.split('#').slice(1).join('#') : '');
                }
            }
        } catch (e) { /* fall through to the generic masking */ }
        return maskText(uri
            .replace(/^([a-z][a-z0-9+.-]*:\/\/)([^@\/?#]*)@/i, '$1' + MASK + '@')
            .replace(/([?&])([^=&#]+)=([^&#]*)/g, function (all, sep, k) {
                return SECRET_PARAM_RE.test(safeDecode(k)) ? sep + k + '=' + MASK : all;
            }));
    }

    // ------------------------------------------------------------ settings.txt

    function onOff(v) { return v ? 'ON' : 'OFF'; }

    function countByScheme(prof) {
        var by = {}, cats = 0, nodes = 0;
        Object.keys(prof || {}).forEach(function (cat) {
            cats++;
            ((prof[cat] && prof[cat].nodes) || []).forEach(function (n) {
                nodes++;
                var m = /^([a-z0-9]+):\/\//i.exec(String(n.rawUri || ''));
                var s = m ? m[1].toLowerCase() : 'other';
                by[s] = (by[s] || 0) + 1;
            });
        });
        return { cats: cats, nodes: nodes, by: by };
    }

    function buildSettingsText(engineStatus, excludeList) {
        var L = [];
        var nonRoot = typeof MV_NONROOT !== 'undefined' && MV_NONROOT;
        var a = (typeof advSettings === 'object' && advSettings) || {};
        var theme = (window.MVTheme && MVTheme.get && MVTheme.get()) || {};
        var verEl = $('about-version');

        L.push('Magic V2Ray - settings snapshot');
        L.push('Generated: ' + new Date().toString());
        L.push('Labels are in English; values are what the UI shows now (unsaved edits included).');
        L.push('');
        L.push('== App ==');
        L.push('  Root mode: ' + (nonRoot ? 'NO - Non-root (Android VpnService)' : 'YES - Root (iptables / policy routing)'));
        L.push('  Engine status: ' + (engineStatus || '(unknown)'));
        L.push('  Module version: ' + ((verEl && verEl.textContent) || '(unknown)'));
        L.push('  UI language: ' + (typeof currentLang !== 'undefined' ? currentLang : '(unknown)'));
        L.push('  Theme: ' + (theme.theme || 'system') + ', primary color: ' + (theme.color || 'default') +
               (theme.color === 'custom' ? ' (' + theme.seed + ')' : ''));
        L.push('  Professional mode (custom config.json): ' + onOff(a.proMode));
        var pc = countByScheme(typeof profiles === 'object' ? profiles : {});
        L.push('  Saved nodes: ' + pc.nodes + ' in ' + pc.cats + ' categories' +
               (pc.nodes ? ' (' + Object.keys(pc.by).map(function (k) { return k + ' x' + pc.by[k]; }).join(', ') + ')' : ''));

        L.push('');
        L.push('== Traffic settings ==');
        L = L.concat(describeTab('tab-settings'));

        L.push('');
        L.push('== Network settings ==');
        L = L.concat(describeTab('tab-network'));
        L.push('');
        L.push('[Bypass LAN]');
        L.push('  Ranges sent through Xray instead of bypassed: ' +
               (String(a.lanProxyCidrs || '').trim() || '(none - every listed LAN range bypasses Xray)'));

        L.push('');
        L.push('== Exclude apps ==');
        L.push('  Enable: ' + onOff(a.excludeApps));
        L.push('  Apply changes automatically: ' + onOff(a.excludeAutoApply));
        L.push('  Excluded packages (' + excludeList.length + '):');
        excludeList.forEach(function (p) { L.push('    ' + p); });

        L.push('');
        L.push('== Routing settings ==');
        var rules = Array.isArray(a.routingRules) ? a.routingRules : [];
        L.push('  Rules: ' + rules.length + ' (' + rules.filter(function (r) { return r && r.enabled !== false; }).length + ' enabled)');
        rules.forEach(function (r, i) {
            if (!r) return;
            var m = [];
            ['domain', 'ip', 'port', 'network', 'protocol'].forEach(function (k) {
                if (r[k]) m.push(k + '=' + String(r[k]).slice(0, 120) + (String(r[k]).length > 120 ? '...' : ''));
            });
            L.push('    ' + (i + 1) + '. [' + (r.enabled === false ? 'off' : 'on') + '] ' + (r.remarks || '') +
                   ' | ' + (m.join(' ') || '(match all)') + ' -> ' + (r.outboundTag || '?'));
        });
        L.push('  Policy groups: ' + (Array.isArray(a.policyGroups) ? a.policyGroups.length : 0));

        L.push('');
        L.push('== Custom hosts ==');
        var ch = String(a.customHosts || '');
        L.push('  ' + (ch ? ch.length + ' chars, ' + ch.split(/\r?\n/).length + ' lines (content not exported)' : '(empty)'));

        L.push('');
        L.push('== Raw settings (customHosts / routingRules omitted) ==');
        var raw = {};
        Object.keys(a).forEach(function (k) {
            if (k === 'customHosts' || k === 'routingRules') return;
            raw[k] = a[k];
        });
        L.push(JSON.stringify(raw, null, 2));
        return L.join('\n') + '\n';
    }

    // ------------------------------------------------------------ config.txt

    function buildConfigText() {
        var L = [];
        L.push('Magic V2Ray - active config');
        L.push('UUIDs, passwords and keys are replaced with ' + MASK + '.');
        L.push('');
        var uris = [], title = '';
        try {
            if (typeof activeConfig === 'string' && activeConfig) {
                var i = activeConfig.indexOf(':');
                var cat = activeConfig.slice(0, i), id = activeConfig.slice(i + 1);
                var node = profiles[cat] && (profiles[cat].nodes || []).find(function (n) { return n.id === id; });
                if (node) {
                    title = 'Active node: ' + (node.name || node.address || id) + '  (category: ' + cat + ')';
                    var raw = String(node.rawUri || '');
                    if (raw.indexOf('pgroup://') === 0) {
                        var g = _findPolicyGroup(raw.slice('pgroup://'.length));
                        title += '\nPolicy group: ' + (g && (g.name || g.id) || raw);
                        (g ? _policyGroupNodes(g) : []).slice(0, 30).forEach(function (n) { uris.push(n.rawUri); });
                    } else {
                        uris.push(raw);
                    }
                }
            }
        } catch (e) { L.push('(could not read the active node: ' + e + ')'); }
        if (!title) title = 'Active node: (none - built-in direct node, or nothing selected)';
        L.push(title);
        L.push('');
        uris.forEach(function (u, idx) {
            L.push('URI payload' + (uris.length > 1 ? ' #' + (idx + 1) : '') + ':');
            L.push('  ' + maskUri(u));
            var flags = [];
            try { if (isPlainVlessUri(u)) flags.push('VLESS without TLS / encryption (plaintext)'); } catch (e) { /* ignore */ }
            try { if (isPlainTrojanUri(u)) flags.push('Trojan without TLS (plaintext)'); } catch (e) { /* ignore */ }
            if (flags.length) L.push('  WARNING: ' + flags.join('; '));
            L.push('');
        });
        L.push('The generated Xray config is in config.json.');
        return L.join('\n') + '\n';
    }

    // ------------------------------------------------------------ flow

    var busy = false;

    function exportLogs() {
        if (busy) return;
        if (!(window.ksu && typeof window.ksu.exportLogs === 'function')) {
            showToast(t('toast_export_logs_fail', { reason: 'window.ksu.exportLogs not available' }), 'error');
            return;
        }
        busy = true;
        var btn = $('btn-export-logs');
        if (btn) btn.disabled = true;
        showToast(t('toast_export_logs_busy'), 'info');

        var done = function () { busy = false; if (btn) btn.disabled = false; };
        var engineStatus = '', excludeList = [];

        execShell('sh ' + MODDIR + '/proxy_control.sh status', function (status, stderr, errno) {
            engineStatus = (status || 'stopped') + (errno === 0 ? ' (running)' : ' (exit code ' + errno + ')');
            execShell('cat ' + shQuote(EXCLUDE_LIST_FILE) + ' 2>/dev/null', function (raw) {
                excludeList = String(raw || '').split(/\r?\n/).map(function (l) { return l.replace(/#.*/, '').trim(); })
                    .filter(function (l) { return /^[A-Za-z0-9_.]+$/.test(l); });
                var files;
                try {
                    files = { 'settings.txt': buildSettingsText(engineStatus, excludeList), 'config.txt': buildConfigText() };
                } catch (e) {
                    done();
                    showToast(t('toast_export_logs_fail', { reason: String(e && e.message || e) }), 'error');
                    return;
                }
                var cb = 'cb_exportlogs_' + Date.now();
                window[cb] = function (code, out, err) {
                    delete window[cb];
                    done();
                    if (code === 0) showToast(out ? t('toast_export_logs_ok', { path: out }) : t('toast_export_logs_saved'), 'success');
                    else if (err === 'cancelled') showToast(t('toast_export_logs_cancelled'), 'info');
                    else showToast(t('toast_export_logs_fail', { reason: err || '?' }), 'error');
                };
                try {
                    window.ksu.exportLogs(JSON.stringify(files), cb);
                } catch (e) {
                    delete window[cb];
                    done();
                    showToast(t('toast_export_logs_fail', { reason: String(e && e.message || e) }), 'error');
                }
            });
        });
    }
})();
