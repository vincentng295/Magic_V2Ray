// "Backup & restore" (App settings tab): export / import the app's settings as one JSON file.
//
// The file is split into SCOPES (nodes, traffic, network, DNS, routing, hosts, exclude apps,
// appearance). Export lets you pick which scopes go into the file; import reads a file (or the
// clipboard), lists the scopes it contains and applies only the ones you tick. Keys are sorted
// into scopes by classify(), both when writing and when reading, so a crafted file cannot smuggle
// a setting into a scope the user did not select.
(function () {
    'use strict';
    var $ = function (id) { return document.getElementById(id); };
    var FORMAT = 'magic-v2ray-settings';
    var VERSION = 1;
    var MAX_TEXT = 2 * 1024 * 1024;

    var SCOPES = [
        { id: 'profiles',   label: 'sio_sc_profiles',   desc: 'sio_sc_profiles_d' },
        { id: 'traffic',    label: 'sio_sc_traffic',    desc: 'sio_sc_traffic_d' },
        { id: 'network',    label: 'sio_sc_network',    desc: 'sio_sc_network_d' },
        { id: 'dns',        label: 'sio_sc_dns',        desc: 'sio_sc_dns_d' },
        { id: 'routing',    label: 'sio_sc_routing',    desc: 'sio_sc_routing_d' },
        { id: 'hosts',      label: 'sio_sc_hosts',      desc: 'sio_sc_hosts_d' },
        { id: 'exclude',    label: 'sio_sc_exclude',    desc: 'sio_sc_exclude_d' },
        { id: 'appearance', label: 'sio_sc_appearance', desc: 'sio_sc_appearance_d' }
    ];
    var THEME_KEYS = ['theme', 'color', 'seed', 'bgTheme', 'bgDim'];   // the wallpaper itself is not exported

    // Which scope does an advSettings key belong to?
    function classify(k) {
        if (k === 'lang') return 'appearance';
        if (k === 'routingRules' || k === 'policyGroups') return 'routing';
        if (k === 'customHosts') return 'hosts';
        if (k === 'excludeApps' || k === 'excludeAutoApply') return 'exclude';
        if (/^(networkMode|allowTether|shizuku.*|bypass.*|upstreamIface|lanProxyCidrs|enableIPv6.*)$/.test(k)) return 'network';
        if (/^(dns.*|queryStrategy|hijackDns|localDns|fakeDnsLocal|foreignDns|directDns.*)$/.test(k)) return 'dns';
        return 'traffic';
    }

    var state = { mode: '', parsed: null };

    // ---------------------------------------------------------------- helpers
    function write(path, text) {
        return new Promise(function (res) { writeFileB64(path, text, function (o, e, n) { res(!n); }); });
    }
    function readText(path) {
        return execShellAsync("cat " + shQuote(path) + " 2>/dev/null");
    }
    function advSubset(scope) {
        var out = {};
        Object.keys(advSettings).forEach(function (k) {
            if (classify(k) === scope) out[k] = advSettings[k];
        });
        return out;
    }
    function clone(o) { return JSON.parse(JSON.stringify(o)); }

    // ---------------------------------------------------------------- collect (export)
    async function collect(scope) {
        if (scope === 'profiles') return { profiles: clone(profiles), activeConfig: activeConfig || null };
        if (scope === 'hosts') return { adv: clone(advSubset('hosts')), hostsText: await readText(HOSTS_FILE) };
        if (scope === 'exclude') return { adv: clone(advSubset('exclude')), list: await readText(EXCLUDE_LIST_FILE) };
        if (scope === 'appearance') {
            var p = window.MVTheme ? window.MVTheme.get() : {}, theme = {};
            THEME_KEYS.forEach(function (k) { if (p[k] !== undefined) theme[k] = p[k]; });
            return { adv: clone(advSubset('appearance')), theme: theme };
        }
        return { adv: clone(advSubset(scope)) };
    }

    async function buildExport(ids) {
        var scopes = {};
        for (var i = 0; i < ids.length; i++) scopes[ids[i]] = await collect(ids[i]);
        return JSON.stringify({ format: FORMAT, version: VERSION, exported: new Date().toISOString(), scopes: scopes }, null, 2);
    }

    // ---------------------------------------------------------------- apply (import)
    // Accepts only keys that classify() puts in `scope` and whose type matches the current value.
    function mergeAdv(scope, adv) {
        if (!adv || typeof adv !== 'object' || Array.isArray(adv)) return 0;
        var n = 0;
        Object.keys(adv).forEach(function (k) {
            if (k === '__proto__' || k === 'constructor' || k === 'prototype') return;
            if (classify(k) !== scope) return;
            var v = adv[k], cur = advSettings[k];
            if (v === undefined || v === null) return;
            if (cur !== undefined && cur !== null && typeof cur !== typeof v) return;
            if (typeof v === 'object' && Array.isArray(cur) !== Array.isArray(v)) return;
            advSettings[k] = v;
            n++;
        });
        return n;
    }

    function validProfiles(p) {
        if (!p || typeof p !== 'object' || Array.isArray(p)) return null;
        var out = {};
        Object.keys(p).forEach(function (cat) {
            if (cat === '__proto__' || cat.indexOf(':') !== -1) return;
            var c = p[cat];
            if (!c || typeof c !== 'object' || !Array.isArray(c.nodes)) return;
            out[cat] = c;
        });
        return out;
    }

    async function applyImport(ids) {
        var data = state.parsed.scopes, ok = true, touchedAdv = false, touchedEngine = false;
        for (var i = 0; i < ids.length; i++) {
            var id = ids[i], d = data[id];
            if (!d || typeof d !== 'object') continue;
            if (id === 'profiles') {
                var vp = validProfiles(d.profiles);
                if (!vp) { ok = false; continue; }
                profiles = vp;
                activeConfig = (typeof d.activeConfig === 'string' && d.activeConfig.indexOf(':') > 0) ? d.activeConfig : null;
                saveProfiles();
                saveActiveConfig();
                touchedEngine = true;
                continue;
            }
            if (id === 'hosts' && typeof d.hostsText === 'string' && d.hostsText.length <= MAX_TEXT) {
                ok = (await write(HOSTS_FILE, d.hostsText)) && ok;
            }
            if (id === 'exclude' && typeof d.list === 'string' && d.list.length <= MAX_TEXT) {
                ok = (await write(EXCLUDE_LIST_FILE, d.list)) && ok;
            }
            if (id === 'appearance' && d.theme && typeof d.theme === 'object' && window.MVTheme) {
                var upd = {};
                THEME_KEYS.forEach(function (k) { if (d.theme[k] !== undefined) upd[k] = d.theme[k]; });
                window.MVTheme.set(upd);
            }
            if (id === 'appearance' && !(d.adv && 'lang' in d.adv)) delete advSettings.lang;   // file says "follow system"
            mergeAdv(id, d.adv);
            touchedAdv = true;
            if (id !== 'appearance') touchedEngine = true;
        }
        if (touchedAdv) ok = (await write(SETTINGS_FILE, utoa(JSON.stringify(advSettings)))) && ok;
        return { ok: ok, engine: touchedEngine };
    }

    // ---------------------------------------------------------------- modal
    function renderScopes(list, enabledIds, checked) {
        var box = $('sio-scopes');
        box.textContent = '';
        list.forEach(function (s) {
            var on = enabledIds.indexOf(s.id) !== -1;
            var row = document.createElement('label');
            row.className = 'sio-scope' + (on ? '' : ' disabled');
            var cb = document.createElement('input');
            cb.type = 'checkbox'; cb.value = s.id; cb.checked = on && checked; cb.disabled = !on;
            cb.addEventListener('change', syncButtons);
            var txt = document.createElement('span');
            var b = document.createElement('b'); b.textContent = t(s.label);
            var sm = document.createElement('small'); sm.textContent = t(s.desc);
            txt.appendChild(b); txt.appendChild(sm);
            row.appendChild(cb); row.appendChild(txt);
            box.appendChild(row);
        });
        syncButtons();
    }
    function picked() {
        return Array.prototype.map.call($('sio-scopes').querySelectorAll('input:checked'), function (c) { return c.value; });
    }
    function syncButtons() {
        var none = picked().length === 0;
        ['sio-save', 'sio-copy', 'sio-apply'].forEach(function (id) { if ($(id)) $(id).disabled = none; });
    }
    function show(mode) {
        state.mode = mode;
        var exp = mode === 'export', step2 = mode === 'import-pick';
        $('sio-title').textContent = t(exp ? 'sio_export_title' : 'sio_import_title');
        $('sio-hint').textContent = t(exp ? 'sio_hint_export' : (step2 ? 'sio_hint_pick' : 'sio_hint_import'));
        $('sio-source-actions').hidden = mode !== 'import';
        $('sio-scope-wrap').hidden = mode === 'import';
        $('sio-actions-export').hidden = !exp;
        $('sio-actions-apply').hidden = !step2;
        $('sio-modal').style.display = 'block';
    }
    window.closeSettingsIoModal = function () { $('sio-modal').style.display = 'none'; state.parsed = null; };
    window.sioToggleAll = function (on) {
        $('sio-scopes').querySelectorAll('input:not(:disabled)').forEach(function (c) { c.checked = !!on; });
        syncButtons();
    };

    window.openSettingsExport = function () {
        renderScopes(SCOPES, SCOPES.map(function (s) { return s.id; }), true);
        show('export');
    };
    window.openSettingsImport = function () {
        state.parsed = null;
        show('import');
    };

    function parseFile(text) {
        var d;
        try { d = JSON.parse(String(text || '')); } catch (e) { return null; }
        if (!d || d.format !== FORMAT || !d.scopes || typeof d.scopes !== 'object') return null;
        var have = SCOPES.filter(function (s) { return d.scopes[s.id] && typeof d.scopes[s.id] === 'object'; })
                         .map(function (s) { return s.id; });
        return have.length ? { scopes: d.scopes, have: have, exported: d.exported } : null;
    }
    function loadParsed(text) {
        var p = parseFile(text);
        if (!p) { showToast(t('sio_toast_bad_file'), 'error'); return; }
        state.parsed = p;
        renderScopes(SCOPES, p.have, true);
        show('import-pick');
        if (p.exported) $('sio-hint').textContent = t('sio_hint_pick') + ' (' + String(p.exported).slice(0, 10) + ')';
    }

    window.sioImportFromFile = function () { var i = $('sio-file'); i.value = ''; i.click(); };
    window.sioImportFromClipboard = async function () {
        try { loadParsed(await navigator.clipboard.readText()); }
        catch (e) { showToast(t('toast_clipboard_empty'), 'error'); }
    };

    window.sioSave = async function () {
        var ids = picked();
        if (!ids.length) return;
        var text = await buildExport(ids);
        var stamp = new Date().toISOString().slice(0, 10);
        var k = window.ksu;
        if (k && typeof k.saveTextFile === 'function') {
            window.__sioSaveCb = function (code) {
                if (code === 0) { showToast(t('sio_toast_saved'), 'success'); window.closeSettingsIoModal(); }
            };
            k.saveTextFile('magic-v2ray-settings-' + stamp + '.json', text, '__sioSaveCb');
            return;
        }
        try {   // plain browser fallback
            var a = document.createElement('a');
            a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
            a.download = 'magic-v2ray-settings-' + stamp + '.json';
            document.body.appendChild(a); a.click(); a.remove();
            showToast(t('sio_toast_saved'), 'success');
            window.closeSettingsIoModal();
        } catch (e) { showToast(t('toast_rules_export_fail'), 'error'); }
    };
    window.sioCopy = async function () {
        var ids = picked();
        if (!ids.length) return;
        try {
            await navigator.clipboard.writeText(await buildExport(ids));
            showToast(t('sio_toast_copied'), 'success');
            window.closeSettingsIoModal();
        } catch (e) { showToast(t('toast_rules_export_fail'), 'error'); }
    };

    window.sioApply = async function () {
        var ids = picked();
        if (!ids.length || !state.parsed) return;
        var names = ids.map(function (id) { return t(SCOPES.filter(function (s) { return s.id === id; })[0].label); }).join(', ');
        if (!await showConfirm(t('sio_confirm_import', { scopes: names }))) return;
        var r = await applyImport(ids);
        window.closeSettingsIoModal();
        loadState(function () {
            applyI18n();
            applyRunModeUi();
            updateStatusDisplay();
            renderProfiles();
            syncAutocheckList();
            if (typeof loadHostsDomainList === 'function') loadHostsDomainList();
            if (typeof renderRoutingRules === 'function') renderRoutingRules();
            if (r.engine && document.getElementById('service-status').classList.contains('active')) applyActiveConfig();
            showToast(t(r.ok ? 'sio_toast_imported' : 'sio_toast_import_partial'), r.ok ? 'success' : 'error');
        });
    };

    document.addEventListener('DOMContentLoaded', function () {
        var f = $('sio-file');
        if (!f) return;
        f.addEventListener('change', function () {
            var file = f.files && f.files[0];
            if (!file) return;
            if (file.size > 8 * 1024 * 1024) { showToast(t('sio_toast_bad_file'), 'error'); return; }
            var r = new FileReader();
            r.onload = function (e) { loadParsed(e.target.result); };
            r.readAsText(file);
        });
    });
})();
