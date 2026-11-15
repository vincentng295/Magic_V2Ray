// ===== External control: magicv2ray://connect, intents, trusted apps ("Access permissions" tab) =====
//
// Another app or a web page asks Magic V2Ray to use a node, and/or other settings, for a while:
//
//   magicv2ray://connect?uri=<urlencoded node uri>&name=<display name>&exclude=<package,package>
//                       &overrides=<base64url JSON>
//   magicv2ray://disconnect
//   startActivity / sendBroadcast with the action com.huskydg.magicv2ray.action.CONNECT | DISCONNECT
//     and the extras uri, name, exclude, overrides              (see ExternalSession.java)
//
// `uri` is optional when `overrides` is given: the node the user selected keeps being used.
//
// overrides = { traffic: {...}, network: {...}, exclude: {...}, routing: {...}, hosts: {...} }, see
// ExternalSession.sanitizeOverrides for the exact (whitelisted) keys. They are applied to a COPY of the user's settings.
//
// NOTHING OF A SESSION IS EVER SAVED. The native side only parks the request; this file asks the user and does the work.
// The merged settings, the generated config, the exclude list, the bypass interfaces are written ONLY to OVERRIDE_DIR
// (root: tmpfs under /dev, gone at reboot; non-root: the cache dir) and the engine reads them instead of the user's files
// while the session runs. The user's files in DATADIR are never written, so ending the session (notification button, tile,
// the disconnect link, the button here) just deletes OVERRIDE_DIR and restarts the engine on the user's own setup, from the
// native side, without the WebUI. A session never resumes after a reboot: the app that wants it has to ask again.
//
//   uri=socks://127.0.0.1:4311   needs nothing special: SOCKS nodes are already supported. The app that serves
//                                that port is kept out of the VPN (exclude=<package>, or the caller itself when
//                                it is a verified native app), otherwise its own traffic would loop back into it.
//
// A connect is ALWAYS confirmed, showing who asked and the server (the full uri is under "Details", open by default
// when anything is unusual), except for an app the user chose to "always trust" (only offered for explicit intents from
// verified native apps, never for links or browsers), and even then a plaintext VLESS / Trojan node, or an exclusion that
// is not the caller itself, is confirmed. A request with setting overrides is confirmed every time, trusted app or not.

const EXT_SELF_PKG = 'com.huskydg.magicv2ray';
const EXT_SESSION_EXCLUDE_NAME = 'excludelist.session.txt';
const EXT_NAME_MAX = 60;
const EXT_GENERIC_REMARKS = ['SOCKS', 'HTTP Proxy', 'Unnamed Node', 'Hysteria2', 'MASQUE'];

let _extBusy = false;
let _extReconciling = false;

// ---------------------------------------------------------------------------
// native bridge helpers
// ---------------------------------------------------------------------------

// The running external session as {active:false} or {active:true, name, uri, caller, callerLabel, exclude, wasRunning}.
function extState() {
    const k = window.ksu;
    if (!k || typeof k.extState !== 'function') return { active: false };
    try {
        const s = JSON.parse(k.extState());
        return s && s.active ? s : { active: false };
    } catch (e) {
        return { active: false };
    }
}

// Calls a bridge method whose last argument is a callback name; resolves {code, out, err}.
function _extCall(method, ...args) {
    return new Promise(resolve => {
        const k = window.ksu;
        if (!k || typeof k[method] !== 'function') { resolve({ code: 1, out: '', err: 'no bridge' }); return; }
        const cb = `cb_ext_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
        window[cb] = (code, out, err) => { delete window[cb]; resolve({ code, out, err }); };
        k[method](...args, cb);
    });
}

function _extExec(cmd) {
    return new Promise(resolve => execShell(cmd, (out, err, errno) => resolve({ out, err, errno })));
}

function _extWrite(path, content) {
    return new Promise(resolve => writeFileB64(path, content, (out, err, errno) => resolve(!errno)));
}

// A request approved in the native dialog (ExternalPromptActivity) runs silently in the background: the user is
// in the app that asked, so the result is a system toast, not a WebUI toast / loading overlay.
function _extMsg(req, text, type) {
    if (req && req.approved === true && window.ksu && typeof window.ksu.toast === 'function') window.ksu.toast(text);
    else showToast(text, type);
}

function _extSleep(ms) {
    return new Promise(r => setTimeout(r, ms));
}

// ---------------------------------------------------------------------------
// config building
// ---------------------------------------------------------------------------

// Final config text for a node uri, built exactly like applyActiveConfig() does. -> {ok:true, text} | {ok:false, error}
async function _extBuildConfig(rawUri) {
    const res = resolveXrayConfigChecked(rawUri);
    if (!res.ok) return res;
    const text = await resolveOutboundViaSystemDns(res.config).then(injectGatewayLocalHosts).then(injectLocalDnsHost);
    return { ok: true, text };
}

// The node the user selected (what the engine returns to once the session ends).
function _extBaseRawUri() {
    if (activeConfig) {
        const i = activeConfig.indexOf(':');
        const node = profiles[activeConfig.slice(0, i)]?.nodes?.find(n => n.id === activeConfig.slice(i + 1));
        if (node && node.rawUri) return node.rawUri;
    }
    return DIRECT_NODE_URI;
}

// Settings or the selection changed during a session: nothing to do. The user's own files are untouched and are what
// comes back when the session ends (applyActiveConfig() still refreshes them, without restarting the engine).
function extRefreshBase() { /* intentionally empty */ }

// ---------------------------------------------------------------------------
// request -> name, exclusions, warnings
// ---------------------------------------------------------------------------

function _extRemark(node) {
    const hasHash = /#[^#]+$/.test(node.rawUri || '');
    const vmess = node.protocol === 'vmess' && node.name && node.name !== 'Unnamed Node';
    const name = (node.name || '').trim();
    return (hasHash || vmess) && name && !EXT_GENERIC_REMARKS.includes(name) ? name : '';
}

// Name shown in the status bar, notification and tile: the `name` parameter, then the calling app,
// then the remark of the uri, finally the host. It is for display only and proves nothing about the caller.
function _extDisplayName(req, node) {
    node = node || {};
    const given = String(req.name || '').replace(/\s+/g, ' ').trim();
    const raw = given || req.callerLabel || _extRemark(node) || node.address || t('ext_unnamed');
    return String(raw).slice(0, EXT_NAME_MAX);
}

// Does the node point at this device (loopback, or one of its own addresses)? Then the app serving it must stay
// out of the VPN, or its outgoing traffic is captured again and sent back into itself.
function _extIsLocalTarget(address) {
    const a = String(address || '').toLowerCase().replace(/^\[|\]$/g, '');
    if (!a) return false;
    if (a === 'localhost' || a === '::1' || a === '::' || a === '0.0.0.0' || /^127\./.test(a)) return true;
    try {
        return JSON.parse(window.ksu.localAddresses()).some(x => x.addr === a);
    } catch (e) {
        return false;
    }
}

function _extExcludes(req, node) {
    const set = new Set(Array.isArray(req.exclude) ? req.exclude : []);
    // verified native caller that serves the local SOCKS5: exclude it by itself
    if (node && req.caller && !req.browser && _extIsLocalTarget(node.address)) set.add(req.caller);
    set.delete(EXT_SELF_PKG);                 // the app itself is never inside its own VPN
    return Array.from(set);
}

function _extAppLabels(pkgs) {
    const out = {};
    try {
        const infos = JSON.parse(window.ksu.getPackagesInfo(JSON.stringify(pkgs)));
        infos.forEach(x => { if (x && x.packageName) out[x.packageName] = x.appLabel; });
    } catch (e) { /* labels are a nicety */ }
    return out;
}

// ---------------------------------------------------------------------------
// the confirmation dialog
// ---------------------------------------------------------------------------

// Like Shizuku's permission dialog: who asks, three buttons (always / once / deny).
// Resolves {ok, trust}. Tapping outside the dialog counts as Deny.
function _extConfirm(info) {
    return new Promise(resolve => {
        const $ = id => document.getElementById(id);
        const overlay = $('ext-modal-overlay');
        const always = $('ext-modal-always');
        const once = $('ext-modal-ok');
        const deny = $('ext-modal-cancel');
        if (!overlay || !always || !once || !deny) { resolve({ ok: false, trust: false }); return; }   // never connect without asking

        const { req, node, name, excludes, warnings } = info;
        const set = (id, text) => { $(id).textContent = text; };
        const native = !!(req.caller && !req.browser);

        // full description of the caller (Details)
        let caller;
        if (native) caller = `${req.callerLabel || req.caller} (${req.caller})`;
        else if (req.caller) caller = t('ext_caller_browser', { app: req.callerLabel || req.caller });
        else caller = t('ext_caller_unknown');
        set('ext-caller', caller);
        set('ext-name', name);
        set('ext-uri', req.uri || '');

        // the question, with the app name in bold: "Allow <b>app</b> to connect through Magic V2Ray?"
        const who = native ? (req.callerLabel || req.caller)
            : req.caller ? t('ext_who_web') : t('ext_who_unknown');
        const q = $('ext-question');
        q.textContent = '';
        const MARK = '\u0001';
        t('ext_question', { app: MARK }).split(MARK).forEach((part, i, arr) => {
            q.append(document.createTextNode(part));
            if (i < arr.length - 1) { const b = document.createElement('strong'); b.textContent = who; q.append(b); }
        });
        set('ext-caller-pkg', native ? req.caller : '');
        $('ext-caller-pkg').style.display = native ? '' : 'none';
        set('ext-node', node ? `${node.protocol} · ${node.address}:${node.port}` : '');

        // icon of a verified native app; anything else gets the generic one
        const icon = $('ext-icon'), fallback = $('ext-icon-fallback');
        icon.style.display = 'none'; fallback.style.display = '';
        icon.onload = () => { icon.style.display = ''; fallback.style.display = 'none'; };
        icon.onerror = () => { icon.style.display = 'none'; fallback.style.display = ''; icon.onerror = null; };
        icon.removeAttribute('src');
        if (native) icon.src = 'ksu://icon/' + req.caller;

        const labels = excludes.length ? _extAppLabels(excludes) : {};
        set('ext-exclude', excludes.length
            ? excludes.map(p => (labels[p] && labels[p] !== p) ? `${labels[p]} (${p})` : p).join('\n')
            : t('ext_none'));

        const warn = $('ext-warnings');
        warn.textContent = warnings.join('\n\n');
        warn.style.display = warnings.length ? '' : 'none';

        // anything beyond "this app asks for its own server" is shown right away, not hidden behind a tap
        $('ext-details').open = warnings.length > 0 || excludes.some(p => p !== req.caller);

        always.style.display = (req.trustable && req.caller) ? '' : 'none';

        overlay.style.display = 'flex';
        overlay.scrollTop = 0;

        const cleanup = (ok, trust) => {
            overlay.style.display = 'none';
            always.onclick = once.onclick = deny.onclick = overlay.onclick = null;
            resolve({ ok, trust });
        };
        always.onclick = () => cleanup(true, true);
        once.onclick = () => cleanup(true, false);
        deny.onclick = () => cleanup(false, false);
        overlay.onclick = (e) => { if (e.target === overlay) cleanup(false, false); };
    });
}

// ---------------------------------------------------------------------------
// connect
// ---------------------------------------------------------------------------

// Called by the native side (and once at start-up) when a request is waiting.
async function checkExternalRequest() {
    const k = window.ksu;
    if (!k || typeof k.takeExternalRequest !== 'function' || _extBusy) return;
    let req;
    try { req = JSON.parse(String(k.takeExternalRequest() || '')); } catch (e) { return; }
    if (!req || req.action !== 'connect') return;
    _extBusy = true;
    try {
        await _extHandleConnect(req);
    } finally {
        _extBusy = false;
        setTimeout(checkExternalRequest, 300);      // another request may have arrived meanwhile
    }
}
window.checkExternalRequest = checkExternalRequest;

// ---------------------------------------------------------------------------
// temporary overrides: a merged COPY of the user's settings, never persisted
// ---------------------------------------------------------------------------

// Files whose write would make an override permanent. While an overlay is applied (advSettings / hostsFileText hold the
// merged copy) any write to them is dropped, so a save triggered by the UI in that window cannot persist session values.
let _extOverlayDepth = 0;
const _extGuardedWrite = writeFileB64;
writeFileB64 = function (path, content, callback) {
    if (_extOverlayDepth > 0 && [SETTINGS_FILE, HOSTS_FILE, EXCLUDE_LIST_FILE, BYPASS_IFACE_FILE, CONFIG_JSON].includes(path)) {
        if (callback) callback('', '', 0);
        return;
    }
    return _extGuardedWrite(path, content, callback);
};

function _extOvSections(ov) {
    return ['traffic', 'network', 'exclude', 'routing', 'hosts'].filter(k => ov && ov[k]);
}

// The user's settings with the overrides applied, as a new object. advSettings itself is not changed.
function _extMergeSettings(ov) {
    const m = JSON.parse(JSON.stringify(advSettings));
    if (ov.traffic) Object.assign(m, ov.traffic);
    if (ov.network) {
        const { bypassIfaces, ...rest } = ov.network;
        Object.assign(m, rest);
    }
    if (ov.exclude) m.excludeApps = ov.exclude.enabled !== false;
    if (ov.routing) {
        if (ov.routing.domainStrategy) m.domainStrategy = ov.routing.domainStrategy;
        if (Array.isArray(ov.routing.rules)) {
            m.routingRules = ov.routing.mode === 'prepend' ? ov.routing.rules.concat(m.routingRules || []) : ov.routing.rules;
        }
    }
    return m;
}

// Text of the hosts file the engine config is generated from. -> string, or null when hosts are not overridden.
function _extMergeHostsText(ov) {
    if (!ov.hosts || !ov.hosts.entries) return null;
    const entries = Object.entries(ov.hosts.entries).map(([domain, ips]) => ({ domain, ips: Array.isArray(ips) ? ips : [ips] }));
    if (ov.hosts.mode !== 'merge') return serializeHostsFile(entries);
    const lines = [];
    entries.forEach(e => e.ips.forEach(ip => lines.push(`${ip}\t${e.domain}`)));
    const base = hostsFileText || serializeHostsFile([]);
    return base.replace(/\n*$/, '\n') + lines.join('\n') + (lines.length ? '\n' : '');
}

// Runs `fn` with advSettings / hostsFileText swapped for the merged copy, and puts the user's own back afterwards, whatever
// happens. `fn` may await: persisting is blocked for that whole window (see _extGuardedWrite).
async function _extWithOverlay(ov, fn) {
    if (!_extOvSections(ov).length) return fn(advSettings);
    const savedSettings = advSettings, savedHosts = hostsFileText;
    const merged = _extMergeSettings(ov);
    const hosts = _extMergeHostsText(ov);
    _extOverlayDepth++;
    try {
        advSettings = merged;
        if (hosts !== null) hostsFileText = hosts;
        return await fn(merged);
    } finally {
        advSettings = savedSettings;
        hostsFileText = savedHosts;
        _extOverlayDepth--;
    }
}

// ---------------------------------------------------------------------------
// connect
// ---------------------------------------------------------------------------

async function _extHandleConnect(req) {
    const silent = req.approved === true;     // already confirmed in the native dialog over the calling app
    // Professional mode owns config.json: a session would overwrite the hand-written config.
    if (advSettings.proMode) { _extMsg(req, t('ext_err_promode'), 'error'); return; }
    const ov = req.overrides && typeof req.overrides === 'object' ? req.overrides : {};
    const hasUri = !!String(req.uri || '').trim();
    // No uri: only settings are overridden and the node the user selected keeps being used.
    const node = hasUri ? parseProxyUri(String(req.uri)) : null;
    if (hasUri && !node) { _extMsg(req, t('ext_err_bad_uri'), 'error'); return; }
    const rawUri = hasUri ? node.rawUri : _extBaseRawUri();

    // Built with the overrides applied, so a config that cannot be generated is refused before anything changes.
    const built = await _extWithOverlay(ov, () => _extBuildConfig(rawUri));
    if (!built.ok) { _extMsg(req, t('toast_config_invalid', { reason: built.error }), 'error'); return; }

    const name = _extDisplayName(req, node);
    const excludes = _extExcludes(req, node);
    const warnings = [];
    if (hasUri && isPlainVlessUri(rawUri)) warnings.push(t('warn_plain_vless', { name }));
    if (hasUri && isPlainTrojanUri(rawUri)) warnings.push(t('warn_plain_trojan', { name }));
    if (node && _extIsLocalTarget(node.address) && !excludes.length) warnings.push(t('ext_warn_loop'));
    if (_extOvSections(ov).length) warnings.push(t('ext_warn_overrides', { sections: _extOvSections(ov).join(', ') }));

    // No prompt when the native dialog already asked, or for an app the user trusts, when nothing in the request
    // goes beyond what that app is (setting overrides always go beyond that).
    const auto = silent || (req.trusted === true && !warnings.length && excludes.every(p => p === req.caller));
    let trust = false;
    if (!auto) {
        const r = await _extConfirm({ req, node, name, excludes, warnings });
        if (!r.ok) return;
        trust = r.trust;
    }
    if (!await _extStart(req, ov, name, excludes, built.text)) return;

    if (trust && req.trustable && req.caller) window.ksu.trustAdd(req.caller);
    _markStatusPending();
    renderExtBanner();
    _extMsg(req, t('ext_connected', { name }), 'success');
    // The user stays where they are: a silent request never brings Magic V2Ray forward. Only when the app had to be
    // started for it (cold start) does it step back again, to the app that asked.
    if (silent ? req.cold === true : (req.caller && !req.browser)) {
        if (typeof window.ksu.moveToBack === 'function') window.ksu.moveToBack();
    }
}

// Session files gone: the engine falls back to the user's own files, which were never touched.
async function _extCleanFiles() {
    await _extExec(`rm -rf ${shQuote(OVERRIDE_DIR)}`);
}

// The effective Bypass-interface file content of a session, or null when the session does not touch it.
// "-" = no bypass interface (an empty file would mean "default list" to the engine).
async function _extBypassContent(ov) {
    const n = ov.network;
    if (!n || (n.bypassIfaces === undefined && n.bypassVpnService === undefined)) return null;
    let list;
    if (n.bypassIfaces !== undefined) {
        list = n.bypassIfaces.slice();
    } else {
        const cur = await _extExec(`if [ -f ${shQuote(BYPASS_IFACE_FILE)} ]; then cat ${shQuote(BYPASS_IFACE_FILE)}; fi`);
        list = sanitizeBypassIfaceList(cur.out).split(',').filter(x => x && x !== VPN_IFACE_PATTERN);
    }
    const vpn = n.bypassVpnService !== undefined ? n.bypassVpnService : advSettings.bypassVpnService;
    list = list.filter(x => x !== VPN_IFACE_PATTERN);
    if (vpn) list.push(VPN_IFACE_PATTERN);
    return list.length ? list.join(',') : '-';
}

// Effective exclude list of a session, or null when it does not override the list.
async function _extExcludeListContent(ov) {
    const e = ov.exclude;
    if (!e) return null;
    let apps = Array.isArray(e.apps) ? e.apps.slice() : [];
    if (e.mode === 'add') {
        const cur = await _extExec(`cat ${shQuote(EXCLUDE_LIST_FILE)} 2>/dev/null`);
        const have = String(cur.out || '').split('\n').map(x => x.trim()).filter(Boolean);
        apps = have.concat(apps.filter(a => !have.includes(a)));
    }
    return apps.length ? apps.join('\n') + '\n' : '';
}

async function _extStart(req, ov, name, excludes, configText) {
    const k = window.ksu;
    const prev = extState();
    const silent = req.approved === true;
    if (!silent) showLoading(t('toast_reload_xray'));
    const sections = _extOvSections(ov);
    try {
        const wasRunning = prev.active
            ? prev.wasRunning       // a session is already on: what it remembered is still the user's own state
            : (await _extExec(`sh ${MODDIR}/proxy_control.sh status`)).out === 'running';

        // Everything below is computed first, then written to OVERRIDE_DIR only.
        const merged = await _extWithOverlay(ov, async s => JSON.parse(JSON.stringify(s)));
        const bypass = await _extBypassContent(ov);
        const exList = await _extExcludeListContent(ov);

        const dir = shQuote(OVERRIDE_DIR);
        const prep = await _extExec(`umask 077; rm -rf ${dir}; mkdir -p ${dir} && chmod 700 ${dir}`);
        let ok = prep.errno === 0;
        ok = ok && await _extWrite(`${OVERRIDE_DIR}/config.v2.json`, configText);
        ok = ok && await _extWrite(`${OVERRIDE_DIR}/settings.base64`, utoa(JSON.stringify(merged)));
        if (ok && excludes.length) ok = await _extWrite(`${OVERRIDE_DIR}/${EXT_SESSION_EXCLUDE_NAME}`, excludes.join('\n') + '\n');
        if (ok && exList !== null) ok = await _extWrite(`${OVERRIDE_DIR}/excludelist.txt`, exList);
        if (ok && bypass !== null) ok = await _extWrite(`${OVERRIDE_DIR}/bypassIface.txt`, bypass);
        if (!ok) {
            await _extCleanFiles();
            if (prev.active) await _extCall('extEnd');
            _extMsg(req, t('ext_err_write'), 'error');
            return false;
        }

        const begun = k.extBegin(JSON.stringify({
            name, caller: req.caller || '', callerLabel: req.callerLabel || '', exclude: excludes, sections, wasRunning
        }));
        if (!begun) {
            await _extCleanFiles();
            _extMsg(req, t('ext_err_write'), 'error');
            return false;
        }

        // A full restart: the exclusion list and the network settings are read when the VPN / the iptables rules are built.
        const r = await _extExec(`sh ${MODDIR}/proxy_control.sh restart`);
        let up = false;
        for (let i = 0; i < 4 && !up && r.errno === 0; i++) {
            up = (await _extExec(`sh ${MODDIR}/proxy_control.sh status`)).out === 'running';
            if (!up) await _extSleep(1500);
        }
        if (!up) {
            await _extCall('extEnd');            // back to the user's own setup (and its running / stopped state)
            _extMsg(req, t('ext_err_start', { reason: r.err || r.out || '' }), 'error');
            _markStatusPending();
            renderExtBanner();
            return false;
        }
        return true;
    } finally {
        if (!silent) hideLoading();
    }
}

// ---------------------------------------------------------------------------
// disconnect, reconcile, banner
// ---------------------------------------------------------------------------

// Ends the session: the node selected before it comes back (running again if it was running before).
async function endExternalSession() {
    if (!extState().active) return;
    showLoading(t('ext_disconnecting'));
    try {
        await _extCall('extEnd');
    } finally {
        hideLoading();
    }
    _markStatusPending();
    renderExtBanner();
    showToast(t('ext_ended'), 'success');
}

// The session lost its engine (reboot, crash, stopped some other way): forget it and keep the old config.
async function extReconcile() {
    if (_extReconciling || !extState().active) return;
    _extReconciling = true;
    try {
        const st = (await _extExec(`sh ${MODDIR}/proxy_control.sh status`)).out;
        const hasBase = (await _extExec(`[ -s ${shQuote(OVERRIDE_DIR + '/config.v2.json')} ] && echo y`)).out === 'y';
        if (st === 'running' && hasBase) return;
        const r = await _extCall('extAbandon');     // too young a session is left alone (its engine may be starting)
        if (r.code === 0) {
            renderExtBanner();
            updateStatusDisplay();
        }
    } finally {
        _extReconciling = false;
    }
}

// The session changed behind the WebUI's back (notification button, tile, disconnect link, broadcast).
window.onExtSessionChanged = function () {
    updateStatusDisplay();
    renderExtBanner();
    if (document.getElementById('tab-access')?.classList.contains('active')) renderAccessTab();
};

function renderExtBanner() {
    const s = extState();
    const box = document.getElementById('ext-banner');
    if (!box) return;
    box.style.display = s.active ? '' : 'none';
    if (!s.active) return;
    document.getElementById('ext-banner-name').textContent = s.name || t('ext_unnamed');
    document.getElementById('ext-banner-from').textContent =
        s.callerLabel ? t('ext_banner_from', { app: s.callerLabel }) : t('ext_banner_unknown');
}

// ---------------------------------------------------------------------------
// "Access permissions" tab: the running session and the apps trusted to connect without a prompt
// ---------------------------------------------------------------------------

function renderAccessTab() {
    const k = window.ksu;
    if (!k || typeof k.trustList !== 'function') return;

    const s = extState();
    const sess = document.getElementById('access-session-box');
    if (sess) {
        sess.style.display = s.active ? '' : 'none';
        if (s.active) {
            document.getElementById('access-session-name').textContent = s.name || t('ext_unnamed');
            document.getElementById('access-session-from').textContent =
                s.callerLabel ? t('ext_banner_from', { app: s.callerLabel }) : t('ext_banner_unknown');
        }
    }

    let list = [];
    try { list = JSON.parse(k.trustList()); } catch (e) { /* empty */ }
    const box = document.getElementById('access-trusted-list');
    const empty = document.getElementById('access-trusted-empty');
    if (!box) return;
    box.textContent = '';
    list.sort((a, b) => String(a.label).localeCompare(String(b.label)));

    const q = (document.getElementById('access-search-input')?.value || '').trim().toLowerCase();
    const shown = q ? list.filter(a => `${a.label || ''} ${a.pkg}`.toLowerCase().includes(q)) : list;
    if (empty) empty.style.display = shown.length ? 'none' : '';

    // One card per app, like Shizuku's list: icon, name, package, badges and a switch. Switching it off revokes the trust
    // (the app has to ask again); an app can only be trusted again from its own connection request.
    shown.forEach(app => {
        const row = document.createElement('div');
        row.className = 'setting-item-row access-card';

        const icon = document.createElement('img');
        icon.className = 'access-icon';
        icon.alt = '';
        icon.src = 'ksu://icon/' + app.pkg;
        icon.onerror = () => { icon.style.visibility = 'hidden'; icon.onerror = null; };

        const text = document.createElement('div');
        text.className = 'access-text';
        const name = document.createElement('div');
        name.className = 'access-name';
        name.textContent = app.label || app.pkg;
        const pkg = document.createElement('div');
        pkg.className = 'access-pkg';
        pkg.textContent = app.pkg;
        const badges = document.createElement('div');
        badges.className = 'access-badges';
        const badge = (cls, txt) => { const b = document.createElement('span'); b.className = 'access-badge ' + cls; b.textContent = txt; badges.appendChild(b); };
        badge('ok', t('access_badge_allowed'));
        if (app.installed === false) badge('warn', t('access_not_installed'));
        const since = app.since ? new Date(app.since).toLocaleDateString() : '';
        if (since) badge('', t('access_since', { date: since }));
        text.append(name, pkg, badges);

        const sw = document.createElement('input');
        sw.type = 'checkbox';
        sw.checked = true;
        sw.setAttribute('aria-label', app.label || app.pkg);
        sw.addEventListener('change', () => {
            if (sw.checked) { sw.checked = false; return; }       // cannot be re-granted from here
            k.trustRemove(app.pkg);
            showToast(t('access_revoked', { name: app.label || app.pkg }), 'success');
            row.classList.add('access-card-leaving');
            setTimeout(renderAccessTab, 220);
        });

        row.append(icon, text, sw);
        box.appendChild(row);
    });
}

function openAccessHelp() {
    const o = document.getElementById('access-help-overlay');
    if (!o) return;
    const close = () => { o.style.display = 'none'; o.onclick = null; };
    document.getElementById('access-help-close').onclick = close;
    o.onclick = (e) => { if (e.target === o) close(); };
    o.style.display = 'flex';
}
window.openAccessHelp = openAccessHelp;
