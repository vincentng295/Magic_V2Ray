// ===== External control: magicv2ray://connect, intents, trusted apps ("Access permissions" tab) =====
//
// Another app or a web page asks Magic V2Ray to use a node for a while:
//
//   magicv2ray://connect?uri=<urlencoded node uri>&name=<display name>&exclude=<package,package>
//   magicv2ray://disconnect
//   startActivity / sendBroadcast with the action com.huskydg.magicv2ray.action.CONNECT | DISCONNECT
//     and the extras uri, name, exclude                        (see ExternalSession.java)
//
// The native side only parks the request; this file asks the user and does the work. The node of such an
// "external session" is temporary: profiles and the selected node are never touched. The engine just runs
// another config.v2.json, and the config of the selected node waits in CONFIG_BASE_FILE, which is what
// ExternalSession.end() (notification button, tile, the disconnect link, the button here) puts back.
//
//   uri=socks://127.0.0.1:4311   needs nothing special: SOCKS nodes are already supported. The app that serves
//                                that port is kept out of the VPN (exclude=<package>, or the caller itself when
//                                it is a verified native app), otherwise its own traffic would loop back into it.
//
// A connect is ALWAYS confirmed, with the full uri and who asked, except for an app the user chose to
// "always trust" (only offered for explicit intents from verified native apps, never for links or browsers),
// and even then a plaintext VLESS / Trojan node, or an exclusion that is not the caller itself, is confirmed.

const EXT_SELF_PKG = 'com.huskydg.magicv2ray';
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

// Settings or the selection changed during a session: keep the saved copy of the selected node's config current.
async function extRefreshBase() {
    if (!extState().active) return;
    const b = await _extBuildConfig(_extBaseRawUri());
    if (b.ok && extState().active) await _extWrite(CONFIG_BASE_FILE, b.text);
}

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
    if (req.caller && !req.browser && _extIsLocalTarget(node.address)) set.add(req.caller);
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

function _extConfirm(info) {
    return new Promise(resolve => {
        const overlay = document.getElementById('ext-modal-overlay');
        const ok = document.getElementById('ext-modal-ok');
        const cancel = document.getElementById('ext-modal-cancel');
        if (!overlay || !ok || !cancel) { resolve({ ok: false, trust: false }); return; }   // never connect without asking

        const { req, node, name, excludes, warnings } = info;
        const set = (id, text) => { document.getElementById(id).textContent = text; };

        let caller;
        if (req.caller && !req.browser) caller = `${req.callerLabel || req.caller} (${req.caller})`;
        else if (req.caller) caller = t('ext_caller_browser', { app: req.callerLabel || req.caller });
        else caller = t('ext_caller_unknown');
        set('ext-caller', caller);
        set('ext-name', name);
        set('ext-node', `${node.protocol} · ${node.address}:${node.port}`);
        set('ext-uri', req.uri);

        const labels = excludes.length ? _extAppLabels(excludes) : {};
        set('ext-exclude', excludes.length
            ? excludes.map(p => (labels[p] && labels[p] !== p) ? `${labels[p]} (${p})` : p).join('\n')
            : t('ext_none'));

        const warn = document.getElementById('ext-warnings');
        warn.textContent = warnings.join('\n\n');
        warn.style.display = warnings.length ? '' : 'none';

        const trustRow = document.getElementById('ext-trust-row');
        const trustBox = document.getElementById('ext-trust');
        trustBox.checked = false;
        trustRow.style.display = (req.trustable && req.caller) ? '' : 'none';

        ok.textContent = t('ext_btn_connect');
        overlay.style.display = 'flex';
        overlay.scrollTop = 0;

        const cleanup = (result) => {
            overlay.style.display = 'none';
            ok.onclick = null;
            cancel.onclick = null;
            resolve({ ok: result, trust: result && trustBox.checked });
        };
        ok.onclick = () => cleanup(true);
        cancel.onclick = () => cleanup(false);
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

async function _extHandleConnect(req) {
    // Professional mode owns config.json: a session would overwrite the hand-written config.
    if (advSettings.proMode) { showToast(t('ext_err_promode'), 'error'); return; }
    const node = parseProxyUri(String(req.uri || ''));
    if (!node) { showToast(t('ext_err_bad_uri'), 'error'); return; }
    const rawUri = node.rawUri;
    const built = await _extBuildConfig(rawUri);
    if (!built.ok) { showToast(t('toast_config_invalid', { reason: built.error }), 'error'); return; }

    const name = _extDisplayName(req, node);
    const excludes = _extExcludes(req, node);
    const warnings = [];
    if (isPlainVlessUri(rawUri)) warnings.push(t('warn_plain_vless', { name }));
    if (isPlainTrojanUri(rawUri)) warnings.push(t('warn_plain_trojan', { name }));
    if (_extIsLocalTarget(node.address) && !excludes.length) warnings.push(t('ext_warn_loop'));

    // No prompt only for an app the user trusts, when nothing in the request goes beyond what that app is.
    const auto = req.trusted === true && !warnings.length && excludes.every(p => p === req.caller);
    let trust = false;
    if (!auto) {
        const r = await _extConfirm({ req, node, name, excludes, warnings });
        if (!r.ok) return;
        trust = r.trust;
    }
    if (!await _extStart(req, rawUri, name, excludes, built.text)) return;

    if (trust && req.trustable && req.caller) window.ksu.trustAdd(req.caller);
    _markStatusPending();
    renderExtBanner();
    showToast(t('ext_connected', { name }), 'success');
    // came from a native app: go back to it
    if (req.caller && !req.browser && typeof window.ksu.moveToBack === 'function') window.ksu.moveToBack();
}

async function _extCleanFiles() {
    await _extExec(`rm -f ${shQuote(CONFIG_BASE_FILE)} ${shQuote(EXCLUDE_SESSION_FILE)}`);
}

async function _extStart(req, rawUri, name, excludes, configText) {
    const k = window.ksu;
    const prev = extState();
    showLoading(t('toast_reload_xray'));
    try {
        let wasRunning;
        if (prev.active) {
            wasRunning = prev.wasRunning;        // a session is already on: the saved base config is still the right one
        } else {
            wasRunning = (await _extExec(`sh ${MODDIR}/proxy_control.sh status`)).out === 'running';
            const base = await _extBuildConfig(_extBaseRawUri());
            if (!base.ok) { showToast(t('toast_config_invalid', { reason: base.error }), 'error'); return false; }
            if (!await _extWrite(CONFIG_BASE_FILE, base.text)) { showToast(t('ext_err_write'), 'error'); return false; }
        }

        const wrote = excludes.length
            ? await _extWrite(EXCLUDE_SESSION_FILE, excludes.join('\n') + '\n')
            : (await _extExec(`rm -f ${shQuote(EXCLUDE_SESSION_FILE)}`)).errno === 0;
        if (!wrote || !await _extWrite(CONFIG_JSON, configText)) {
            if (!prev.active) await _extCleanFiles();
            else await _extCall('extEnd');
            showToast(t('ext_err_write'), 'error');
            return false;
        }

        const begun = k.extBegin(JSON.stringify({
            name, uri: rawUri, caller: req.caller || '', callerLabel: req.callerLabel || '', exclude: excludes, wasRunning
        }));
        if (!begun) {
            if (!prev.active) await _extCleanFiles();
            showToast(t('ext_err_write'), 'error');
            return false;
        }

        // A full restart: the exclusion list is read when the VPN / the iptables rules are built.
        const r = await _extExec(`sh ${MODDIR}/proxy_control.sh restart`);
        let up = false;
        for (let i = 0; i < 4 && !up && r.errno === 0; i++) {
            up = (await _extExec(`sh ${MODDIR}/proxy_control.sh status`)).out === 'running';
            if (!up) await _extSleep(1500);
        }
        if (!up) {
            await _extCall('extEnd');            // back to the selected node (and its running / stopped state)
            showToast(t('ext_err_start', { reason: r.err || r.out || '' }), 'error');
            _markStatusPending();
            renderExtBanner();
            return false;
        }
        return true;
    } finally {
        hideLoading();
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
        const hasBase = (await _extExec(`[ -f ${shQuote(CONFIG_BASE_FILE)} ] && echo y`)).out === 'y';
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
    if (empty) empty.style.display = list.length ? 'none' : '';
    list.sort((a, b) => String(a.label).localeCompare(String(b.label)));

    list.forEach(app => {
        const row = document.createElement('div');
        row.className = 'setting-item-row exclude-row';

        const label = document.createElement('div');
        label.className = 'exclude-label';
        const icon = document.createElement('img');
        icon.className = 'exclude-icon';
        icon.alt = '';
        icon.src = 'ksu://icon/' + app.pkg;
        icon.onerror = () => { icon.style.visibility = 'hidden'; icon.onerror = null; };
        const text = document.createElement('span');
        text.className = 'exclude-text';
        const name = document.createElement('span');
        name.className = 'exclude-name';
        name.textContent = app.label || app.pkg;
        const pkg = document.createElement('small');
        const since = app.since ? new Date(app.since).toLocaleDateString() : '';
        pkg.textContent = app.installed === false
            ? `${app.pkg} · ${t('access_not_installed')}`
            : (since ? `${app.pkg} · ${t('access_since', { date: since })}` : app.pkg);
        text.append(name, pkg);
        label.append(icon, text);

        const btn = document.createElement('button');
        btn.className = 'log-filter-chip';
        btn.textContent = t('access_revoke');
        btn.addEventListener('click', async () => {
            if (!await showConfirm(t('access_revoke_confirm', { name: app.label || app.pkg }))) return;
            k.trustRemove(app.pkg);
            renderAccessTab();
        });

        row.append(label, btn);
        box.appendChild(row);
    });
}
