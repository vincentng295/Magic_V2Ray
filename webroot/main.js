function applyI18n() {
    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (i18n[currentLang][key]) {
            el.innerHTML = i18n[currentLang][key];
        }
    });
    document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
        const key = el.getAttribute('data-i18n-placeholder');
        if (i18n[currentLang][key]) {
            el.setAttribute('placeholder', i18n[currentLang][key]);
        }
    });
    const select = document.getElementById('lang-select');
    if (select) select.value = currentLang;
}

function t(key, variables = {}) {
    let text = i18n[currentLang][key] || i18n['en'][key] || key;
    Object.keys(variables).forEach(v => {
        text = text.replace(new RegExp(`{${v}}`, 'g'), variables[v]);
    });
    return text;
}

function changeLanguage(lang) {
    if (!i18n[lang]) return;
    currentLang = lang;
    advSettings.lang = lang;
    applyI18n();
    updateStatusDisplay();
    renderProfiles();
    saveAdvancedSettingsForm(true); 
}

function showLoading(textKey) {
    const overlay = document.getElementById('loading-overlay');
    const textEl = document.getElementById('loading-text');
    if (overlay && textEl) {
        textEl.innerHTML = (typeof t === 'function' && i18n[currentLang]?.[textKey]) ? t(textKey) : textKey;
        overlay.style.display = 'flex';
    }
}

function hideLoading() {
    const overlay = document.getElementById('loading-overlay');
    if (overlay) {
        overlay.style.display = 'none';
    }
}
 
function execShell(command, callback) {
    if (typeof ksu === "object" && typeof ksu.exec === "function") {
        const cbId = `cb_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
        window[cbId] = (errno, stdout, stderr) => {
            delete window[cbId];
            if (callback) callback(stdout.trim(), stderr.trim(), errno);
        };
        ksu.exec(command, "{}", cbId);
    } else {
        showToast("window.ksu not available", "error");
        if (callback) callback("ERROR", "", 1);
    }
}

function execShellAsync(cmd) {
    return new Promise((resolve) => {
        execShell(cmd, (output) => {
            resolve(output ? output.trim() : "");
        });
    });
}

// ---------------------------------------------------------------------------
// Shell-safety layer.
//
// execShell() hands its argument to a root shell. Any value that originates
// outside this file — a subscription payload, a node field, a category name,
// a routing rule — is attacker-controlled and MUST NOT be interpolated into a
// command as raw text. JSON does not escape the single quote, so a value
// containing ' escapes single-quote wrapping and executes as root.
//
// Two safe primitives, and nothing else may build a command containing
// untrusted text:
//   shQuote(s)          - POSIX single-quote escaping, for short literals.
//   writeFileB64(p, c)  - writes arbitrary bytes via base64, which cannot
//                         contain a quote or metacharacter by construction.
// ---------------------------------------------------------------------------

// Wraps a value in single quotes, escaping any embedded quote as '\''.
// Safe for any byte sequence; the result is a single shell word.
function shQuote(str) {
    return "'" + String(str).replace(/'/g, "'\\''") + "'";
}

// Writes `content` to `path` without ever placing it in the command line as
// text. Base64 output is [A-Za-z0-9+/=] only, so it cannot break quoting.
// Files are created 0600 under a 077 umask — they hold server credentials.
function writeFileB64(path, content, callback) {
    let encoded;
    try {
        encoded = utoa(String(content));
    } catch (e) {
        console.error('[writeFileB64] encode failed', e);
        if (callback) callback('', String(e), 1);
        return;
    }
    const p = shQuote(path);
    execShell(
        `umask 077; printf '%s' ${shQuote(encoded)} | base64 -d > ${p} && chmod 600 ${p}`,
        callback || (() => {})
    );
}

function saveProfiles() {
    writeFileB64(PROFILES_FILE, utoa(JSON.stringify(profiles)));
}

function saveActiveConfig() {
    if (activeConfig) {
        writeFileB64(ACTIVE_FILE, activeConfig);
    } else {
        execShell(`rm -f ${shQuote(ACTIVE_FILE)}`, () => {});
    }
}

// Builds the Xray config for a node and refuses to hand back anything that
// would not start. convert_uri_to_xray_json() signals failure by returning a
// JSON document with an `error` key; writing that to config.json used to
// produce an engine that exits on launch while the routing rules stayed
// applied — i.e. a silent total blackhole that also survived reboot.
// Returns { ok: true, config } or { ok: false, error }.
function resolveXrayConfigChecked(rawUri) {
    let configStr;
    try {
        configStr = _resolveXrayConfig(rawUri);
    } catch (e) {
        return { ok: false, error: e.message || String(e) };
    }
    if (!configStr || typeof configStr !== 'string') {
        return { ok: false, error: 'empty config' };
    }
    let parsed;
    try {
        parsed = JSON.parse(configStr);
    } catch (e) {
        return { ok: false, error: 'generated config is not valid JSON' };
    }
    if (parsed && parsed.error) {
        return { ok: false, error: parsed.error };
    }
    if (!parsed || !Array.isArray(parsed.outbounds) || parsed.outbounds.length === 0) {
        return { ok: false, error: 'generated config has no outbounds' };
    }
    return { ok: true, config: configStr };
}

// Regenerates config.json for the currently active node and restarts the
// engine only if it is already running. Used by every "settings changed"
// path so the write/validate/restart sequence exists in exactly one place.
function applyActiveConfig(options = {}) {
    const { force = false, onDone } = options;
    if (!activeConfig) {
        if (onDone) onDone(false);
        return;
    }
    const [category, id] = activeConfig.split(':');
    const node = profiles[category]?.nodes?.find(n => n.id === id);
    if (!node) {
        if (onDone) onDone(false);
        return;
    }

    const res = resolveXrayConfigChecked(node.rawUri);
    if (!res.ok) {
        showToast(t('toast_config_invalid', { reason: res.error }), 'error');
        if (onDone) onDone(false);
        return;
    }

    writeFileB64(CONFIG_JSON, res.config, () => {
        if (force) {
            execShell(`sh ${MODDIR}/proxy_control.sh restart`, () => {
                if (onDone) onDone(true);
            });
            return;
        }
        execShell(`sh ${MODDIR}/proxy_control.sh status`, (status) => {
            if (status === 'running') {
                toggleService('restart');
            }
            if (onDone) onDone(true);
        });
    });
}
 
// Loads profiles, the active-node pointer and advanced settings, in that
// order, then binds the settings form. This used to be two functions, the
// second monkey-patching the first at parse time; it only worked because of
// statement ordering relative to the DOMContentLoaded listener.
function loadState(callback) {
    execShell(`cat '${PROFILES_FILE}' 2>/dev/null || echo '{}'`, (profilesRaw) => {
        try {
            const parsed = JSON.parse(decodeBase64(profilesRaw));
            // MIGRATION PATCH: older builds stored a bare array per category.
            profiles = {};
            Object.keys(parsed).forEach(cat => {
                if (Array.isArray(parsed[cat])) {
                    profiles[cat] = { url: cat === "Manual" ? null : cat, nodes: parsed[cat] };
                } else {
                    profiles[cat] = parsed[cat];
                }
            });
        } catch (e) {
            console.warn("[loadState] profiles parse error, reset to {}");
            profiles = {};
        }

        execShell(`cat '${ACTIVE_FILE}' 2>/dev/null || echo ''`, (activeRaw) => {
            activeConfig = activeRaw.trim() || null;

            execShell(`cat '${SETTINGS_FILE}' 2>/dev/null || echo ''`, (settingsRaw) => {
                if (settingsRaw.trim()) {
                    try {
                        advSettings = JSON.parse(decodeBase64(settingsRaw.trim()));
                    } catch (e) {
                        console.warn("[loadState] settings corrupt, falling back to defaults.");
                    }
                }
                bindSettingsToFormView();
                if (callback) callback();
            });
        });
    });
}

document.addEventListener('DOMContentLoaded', () => {
    loadState(() => {
        applyI18n();
        updateStatusDisplay();
        renderProfiles();
    });
});
 
function updateStatusDisplay() {
    execShell(`sh ${MODDIR}/proxy_control.sh status`, (status, stderr, errno) => {
        const badge = document.getElementById('service-status');
        const s = status || 'stopped';
        badge.innerText = t('status_prefix') + s.toUpperCase();
        badge.className = `status-badge ${errno === 0 ? 'active' : 'inactive'}`;
        if (errno == 2) {
            showToast(t('toast_xray_core_crash'), "error");
        }
    });
}
 
function _markStatusPending() {
    const badge = document.getElementById('service-status');
    if (!badge) return;
    badge.innerText = t('status_loading');
    badge.className = 'status-badge active';
    setTimeout(updateStatusDisplay, 1200);
}

// Only these verbs may be forwarded to proxy_control.sh. `action` reaches us
// from inline handlers, so it is validated against a fixed list rather than
// interpolated straight into the command.
const PROXY_CONTROL_ACTIONS = [
    'start', 'stop', 'restart', 'status', 'reapply',
    'start_monitor_latency', 'stop_monitor_latency', 'reset_mobile_network',
    'gateway_start', 'gateway_stop', 'gateway_status'
];

async function toggleService(action) {
    if (action === 'start' || action === 'restart') {
        if (!activeConfig) {
            showToast(t('toast_no_active_config'), "error");
            return;
        }
        // Re-apply the mark rule for the live interface first, and only start
        // once that has actually completed — this used to be fire-and-forget,
        // racing the engine start against the routing rule it depends on.
        execShell(`sh ${MODDIR}/proxy_control.sh reapply`, () => {
            applyActiveConfig({
                force: true,
                onDone: (ok) => { if (ok) _markStatusPending(); }
            });
        });
        return;
    }
    if (!PROXY_CONTROL_ACTIONS.includes(action)) {
        console.error('[toggleService] refusing unknown action:', action);
        return;
    }
    execShell(`sh ${MODDIR}/proxy_control.sh ${action}`, () => {
        _markStatusPending();
    });
}

const extractUrisFromText = (text) => {
    let uris = [];
    const rawLines = text.split(/\r?\n/);

    rawLines.forEach(line => {
        let trimmedLine = line.trim();
        if (!trimmedLine) return;

        if (trimmedLine.startsWith('{') && trimmedLine.endsWith('}')) {
            // It must be one line
            try {
                const parsedObj = JSON.parse(trimmedLine);
                uris.push(convert_outbound_to_uri(parsedObj));
            } catch (e) {
                console.warn("Cannot parse JSON:", trimmedLine);
                console.warn(e);
            };
        } else if (!trimmedLine.includes('://') && /^[A-Za-z0-9+/=]+$/.test(trimmedLine)) {
            try {
                const decoded = decodeBase64(trimmedLine);
                if (decoded) {
                    uris = uris.concat(extractUrisFromText(decoded));
                }
            } catch (e) {
                console.warn("Line looks like Base64 but failed to decode safely:", trimmedLine);
            }
        } else if (trimmedLine.includes('://')) {
            // Accept known proxy protocol schemes
            if (/^(vmess|vless|trojan|ss|shadowsocks|wireguard|wg|hysteria2|hy2|socks|socks5|http):\/\//i.test(trimmedLine)) {
                uris.push(trimmedLine);
            }
        }
    });
    return uris;
};
 
function processImport() {
    const input = document.getElementById('import-input').value.trim();
    if (!input) return showToast(t('toast_empty_import'), "error");

    if (input.startsWith('http://') || input.startsWith('https://')) {
        let domain;
        try {
            domain = new URL(input).hostname;
        } catch (e) {
            return showToast(t('toast_invalid_sub'), "error");
        }
        fetchSubscription(domain, input);
    } else {
        const xrayConfigs = extractUrisFromText(input);
        parseAndAppendNodes("Manual", xrayConfigs, null);
    }

    document.getElementById('import-input').value = "";
}

async function fetchSubscription(category, url, isReload = false) {
    const status = await execShellAsync(`sh ${MODDIR}/proxy_control.sh status`);
    const viaProxy = (status === 'running') ? "--socks5-hostname 127.17.1.3:808" : "";

    // Certificate validation is ON by default. It used to be disabled
    // unconditionally (-k), which let any on-path attacker substitute the
    // subscription body — and subscription content reaches a root shell.
    // Hosts with a self-signed certificate can opt out per subscription.
    const allowInsecure = profiles[category]?.insecure === true;
    const tlsArgs = allowInsecure ? "-k" : "";
    if (allowInsecure) {
        showToast(t('toast_sub_insecure_warn'), 'info');
    }

    showLoading(`${t("toast_fetch_sub")}${category}...`);

    const cmd = `${MODDIR}/bin/curl ${viaProxy} ${tlsArgs} -sSL -f --max-redirs 3 ` +
                `--proto '=http,https' --proto-redir '=http,https' ` +
                `--max-time 15 ${shQuote(url)}`;

    execShell(cmd, (res) => {
        hideLoading();

        if (!res || res.trim() === "") {
            return showToast(t('toast_fetch_failed'), "error");
        }
        if (res.includes("Failed to connect") || res.includes("Could not resolve")) {
            return showToast(t('toast_fetch_reason') + res.split('\n')[0], "error");
        }

        // A subscription body is either a plain URI list or one big base64
        // blob wrapping that list.
        let parsedContent = res.trim();
        const cleanRes = parsedContent.replace(/[\s\r\n]+/g, '');
        if (/^[A-Za-z0-9+/=_-]+$/.test(cleanRes)) {
            const decodedAll = tryDecodeBase64(cleanRes);
            if (decodedAll && decodedAll.includes('://')) {
                parsedContent = decodedAll;
            }
        }

        parseAndAppendNodes(category, extractUrisFromText(parsedContent), url, isReload);
    });
}
 
function buildNodeKey(node) {
    if (node.protocol === 'vmess') {
        try {
            const b64 = (node.rawUri || '').substring('vmess://'.length).split('#')[0].trim();
            const obj = JSON.parse(decodeBase64(b64));
            delete obj.ps;
            return 'vmess|' + JSON.stringify(obj, Object.keys(obj).sort());
        } catch (e) {
            return ['vmess', node.address, node.port, node.uuid, node.security].join('|');
        }
    }
    const rawNoFragment = (node.rawUri || '').replace(/#.*$/, '');
    return [
        node.protocol || '',
        node.address  || '',
        node.port     || '',
        node.uuid     || '',
        node.security || '',
        rawNoFragment
    ].join('|');
}

function parseAndAppendNodes(category, xrayConfigs, url = null, isReload = false) {
    if (!Array.isArray(xrayConfigs) || xrayConfigs.length === 0) {
        return showToast(t('toast_no_configs_extracted'), "error");
    }

    if (isReload && profiles[category]) {
        profiles[category].nodes = [];
    }

    if (!profiles[category]) {
        profiles[category] = { url: url, nodes: [] };
    } else if (url) {
        profiles[category].url = url;
    }
 
    let importedCount = 0;
    const shouldDedup = profiles[category].dedup !== false; // default true
    const existingKeys = shouldDedup
        ? new Set(profiles[category].nodes.map(n => buildNodeKey(n)))
        : null;
    xrayConfigs.forEach(line => {
        const parsedNode = parseProxyUri(line);
        if (parsedNode) {
            const duplicate = shouldDedup && existingKeys.has(buildNodeKey(parsedNode));
            if (!duplicate) {
                profiles[category].nodes.push(parsedNode);
                if (shouldDedup) existingKeys.add(buildNodeKey(parsedNode));
                importedCount++;
            }
        }
    });

    if (isReload) {
        showToast(t('toast_reload_success', { count: profiles[category].nodes.length, cat: category }), "success");
        if (activeConfig && activeConfig.startsWith(category + ':')) {
            const [_, currentId] = activeConfig.split(':');
            const stillExists = profiles[category].nodes.some(n => n.id === currentId);
            if (!stillExists) {
                activeConfig = null;
                saveActiveConfig();
            }
        }
    } else {
        if (importedCount === 0) {
            showToast(t('toast_no_new_configs'), "info");
        } else {
            showToast(t('toast_imported_count', { count: importedCount, cat: category }), "info");
        }
    }
 
    saveProfiles();
    renderProfiles();
}

function reloadCategory(category) {
    const catData = profiles[category];
    if (!catData || !catData.url) {
        return showToast(t('toast_no_sub_url'), "info");
    }
    fetchSubscription(category, catData.url, true);
}
 
function parseProxyUri(uri) {
    try {
        uri = uri.trim();
        const protocolMatch = uri.match(/^([^:]+):\/\//);
        if (!protocolMatch) return null;
        const protocol = protocolMatch[1].toLowerCase();
        if (!['vless', 'vmess', 'trojan', 'ss', 'shadowsocks', 'wireguard', 'wg', 'hysteria2', 'hy2', 'socks', 'socks5', 'http'].includes(protocol)) return null;

        // vmess uses a base64-encoded JSON payload — parse it differently
        if (protocol === 'vmess') {
            const base64Part = uri.substring('vmess://'.length).split('#')[0].trim();
            let name = "Unnamed Node";
            const hashIdx = uri.lastIndexOf('#');
            if (hashIdx !== -1) {
                try { name = decodeURIComponent(uri.substring(hashIdx + 1)).trim(); } catch (e) {}
            }
            try {
                const rawJson = decodeBase64(base64Part);
                const c = JSON.parse(rawJson);
                if (!c.add || !c.port || !c.id) return null;
                if (c.ps) name = c.ps;
                return {
                    id: Math.random().toString(36).substr(2, 9),
                    name,
                    protocol: 'vmess',
                    address: c.add,
                    port: String(c.port),
                    uuid: c.id,
                    security: c.tls || "none",
                    rawUri: uri
                };
            } catch (e) {
                console.error("[parseProxyUri] vmess base64/JSON parse error:", e, uri);
                return null;
            }
        }

        // Shadowsocks: ss://base64(method:password)@host:port#name
        // or          ss://method:password@host:port#name
        if (protocol === 'ss' || protocol === 'shadowsocks') {
            try {
                let remaining = uri.substring(uri.indexOf('://') + 3);
                let name = "Unnamed Node";
                if (remaining.includes('#')) {
                    const hashIdx = remaining.lastIndexOf('#');
                    try { name = decodeURIComponent(remaining.substring(hashIdx + 1)).trim(); } catch(e) {}
                    remaining = remaining.substring(0, hashIdx);
                }
                // Remove plugin params (?plugin=...)
                const qIdx = remaining.indexOf('?');
                if (qIdx !== -1) remaining = remaining.substring(0, qIdx);

                let method = "aes-256-gcm", password = "", address = "", port = "8388";
                const atIdx = remaining.lastIndexOf('@');
                if (atIdx !== -1) {
                    const userPart = remaining.substring(0, atIdx);
                    const hostPart = remaining.substring(atIdx + 1);
                    // Try base64 decode userPart
                    let decoded = null;
                    try { decoded = decodeBase64(userPart); } catch(e) {}
                    if (decoded && decoded.includes(':')) {
                        const ci = decoded.indexOf(':');
                        method = decoded.substring(0, ci);
                        password = decoded.substring(ci + 1);
                    } else if (userPart.includes(':')) {
                        const ci = userPart.indexOf(':');
                        method = decodeURIComponent(userPart.substring(0, ci));
                        password = decodeURIComponent(userPart.substring(ci + 1));
                    }
                    const lastColon = hostPart.lastIndexOf(':');
                    address = hostPart.substring(0, lastColon);
                    port = hostPart.substring(lastColon + 1);
                } else {
                    // Entire remaining is base64
                    let decoded = null;
                    try { decoded = decodeBase64(remaining); } catch(e) {}
                    if (decoded) {
                        const atI = decoded.lastIndexOf('@');
                        if (atI !== -1) {
                            const u = decoded.substring(0, atI);
                            const h = decoded.substring(atI + 1);
                            const ci = u.indexOf(':');
                            if (ci !== -1) { method = u.substring(0, ci); password = u.substring(ci + 1); }
                            const lc = h.lastIndexOf(':');
                            address = h.substring(0, lc); port = h.substring(lc + 1);
                        }
                    }
                }
                if (!address) return null;
                return {
                    id: Math.random().toString(36).substr(2, 9),
                    name,
                    protocol: 'shadowsocks',
                    address,
                    port,
                    uuid: password,
                    security: method,
                    rawUri: uri
                };
            } catch(e) { return null; }
        }

        // WireGuard: wireguard://secretKey@host:port?publickey=...&...#name
        if (protocol === 'wireguard' || protocol === 'wg') {
            try {
                const fixedUri = uri.replace(/^(wg|wireguard):\/\//i, 'https://');
                const u = new URL(fixedUri);
                const p = new URLSearchParams(u.search);
                const name = u.hash ? decodeURIComponent(u.hash.substring(1)) : "WireGuard";
                return {
                    id: Math.random().toString(36).substr(2, 9),
                    name,
                    protocol: 'wireguard',
                    address: u.hostname,
                    port: u.port || "443",
                    uuid: u.username ? decodeURIComponent(u.username) : "",
                    security: "none",
                    rawUri: uri
                };
            } catch(e) { return null; }
        }

        // Hysteria2: hysteria2://password@host:port?...#name
        if (protocol === 'hysteria2' || protocol === 'hy2') {
            try {
                const fixedUri = uri.replace(/^(hy2|hysteria2):\/\//i, 'https://');
                const u = new URL(fixedUri);
                const name = u.hash ? decodeURIComponent(u.hash.substring(1)) : "Hysteria2";
                return {
                    id: Math.random().toString(36).substr(2, 9),
                    name,
                    protocol: 'hysteria2',
                    address: u.hostname,
                    port: u.port || "443",
                    uuid: decodeURIComponent(u.username),
                    security: "tls",
                    rawUri: uri
                };
            } catch(e) { return null; }
        }

        // SOCKS / SOCKS5: socks5://user:pass@host:port#name
        if (protocol === 'socks' || protocol === 'socks5') {
            try {
                const fixedUri = uri.replace(/^(socks5|socks):\/\//i, 'https://');
                const u = new URL(fixedUri);
                const name = u.hash ? decodeURIComponent(u.hash.substring(1)) : "SOCKS";
                return {
                    id: Math.random().toString(36).substr(2, 9),
                    name,
                    protocol: 'socks',
                    address: u.hostname,
                    port: u.port || "443",
                    uuid: u.username ? decodeURIComponent(u.username) : "",
                    security: "none",
                    rawUri: uri
                };
            } catch(e) { return null; }
        }

        // HTTP proxy: http://user:pass@host:port#name
        if (protocol === 'http') {
            try {
                const u = new URL(uri);
                const name = u.hash ? decodeURIComponent(u.hash.substring(1)) : "HTTP Proxy";
                return {
                    id: Math.random().toString(36).substr(2, 9),
                    name,
                    protocol: 'http',
                    address: u.hostname,
                    port: u.port || "8080",
                    uuid: u.username ? decodeURIComponent(u.username) : "",
                    security: "none",
                    rawUri: uri
                };
            } catch(e) { return null; }
        }

        // vless / trojan use standard user@host:port?params format
        let remaining = uri.substring(protocol.length + 3);
 
        let name = "Unnamed Node";
        if (remaining.includes('#')) {
            const hashIdx = remaining.lastIndexOf('#');
            name = decodeURIComponent(remaining.substring(hashIdx + 1)).trim();
            remaining = remaining.substring(0, hashIdx);
        }
 
        const atIndex = remaining.lastIndexOf('@');
        if (atIndex === -1) return null;
 
        const uuid = remaining.substring(0, atIndex);
        const hostAndParams = remaining.substring(atIndex + 1);
 
        const hostBlock = hostAndParams.split('?')[0];
        let address = hostBlock;
        let port = "443";
 
        if (hostBlock.startsWith('[')) {
            const bracketEnd = hostBlock.indexOf(']');
            address = hostBlock.substring(0, bracketEnd + 1);
            if (hostBlock[bracketEnd + 1] === ':') {
                port = hostBlock.substring(bracketEnd + 2);
            }
        } else if (hostBlock.includes(':')) {
            const lastColon = hostBlock.lastIndexOf(':');
            address = hostBlock.substring(0, lastColon);
            port = hostBlock.substring(lastColon + 1);
        }
 
        let security = "none";
        const secMatch = hostAndParams.match(/[?&]security=([^&]+)/);
        if (secMatch) security = secMatch[1];
 
        return {
            id: Math.random().toString(36).substr(2, 9),
            name,
            protocol,
            address,
            port,
            uuid,
            security,
            rawUri: uri
        };
    } catch (e) {
        console.error("[parseProxyUri] error:", e, uri);
        return null;
    }
}

function _resolveXrayConfig(rawUri) {
    let config_json = {};
    if (rawUri && rawUri.startsWith('chain://')) {
        const fakeRawUri = rawUri.replace(/^chain:\/\//i, 'https://');
        const u = new URL(fakeRawUri);
        const hop1Uri = u.searchParams.get('hop1') || '';
        const hop2Uri = u.searchParams.get('hop2') || '';
        config_json = convert_chain_uris_to_xray_json(hop1Uri, hop2Uri, advSettings);
    } else {
        config_json = convert_uri_to_xray_json(rawUri, advSettings);
    }
    return config_json;
}

function selectNode(category, id) {
    const node = profiles[category]?.nodes?.find(n => n.id === id);
    if (!node) return;
 
    // Reject a node whose config cannot be generated *before* making it
    // active — otherwise the next start writes an unusable config.json,
    // xray exits, and the routing rules blackhole the device.
    const res = resolveXrayConfigChecked(node.rawUri);
    if (!res.ok) {
        showToast(t('toast_config_invalid', { reason: res.error }), 'error');
        return;
    }

    activeConfig = `${category}:${id}`;
    saveActiveConfig();
    renderProfiles();

    // Write the config and restart only if the engine is already running.
    applyActiveConfig();
}
 
function removeCategory(category) {
    delete profiles[category];
    if (activeConfig && activeConfig.startsWith(category + ':')) {
        activeConfig = null;
        saveActiveConfig();
    }
    saveProfiles();
    renderProfiles();
}
 
// Builds a <button> whose handler is attached as a function reference.
// Category names and node fields come from subscriptions and free-text user
// input; interpolating them into an onclick="" attribute (as this used to do)
// let a name containing a double quote break out of the attribute and inject
// script into a page that holds a root exec bridge.
function _mkButton(label, className, handler) {
    const btn = document.createElement('button');
    if (className) btn.className = className;
    btn.textContent = label;
    btn.addEventListener('click', handler);
    return btn;
}

function renderProfiles() {
    const container = document.getElementById('profiles-container');
    container.innerHTML = "";
    const categories = Object.keys(profiles).filter(c => profiles[c]?.nodes?.length > 0);
    if (categories.length === 0) {
        const p = document.createElement('p');
        p.style.cssText = "color: var(--text-muted); font-size:14px; text-align:center; padding: 24px 0;";
        p.textContent = t('no_configs');
        container.appendChild(p);
        return;
    }
    for (const category of categories) {
        const group = document.createElement('div');
        group.className = "category-group";
        const hasUrl = !!profiles[category].url;
        const isExpanded = categoryExpandedState[category] || false;

        const header = document.createElement('div');
        header.className = 'category-header';
        header.style.cssText = "position: relative; display: flex; justify-content: space-between; align-items: center;";

        const title = document.createElement('strong');
        title.textContent = `${category} (${profiles[category].nodes.length})`;
        header.appendChild(title);

        const menuWrap = document.createElement('div');
        menuWrap.className = 'category-menu-container';
        menuWrap.style.cssText = "display: flex; align-items: center; gap: 8px;";

        const expandBtn = _mkButton(isExpanded ? "▽" : "▷", 'btn-menu-trigger',
            (e) => toggleCategoryExpand(e, category));
        expandBtn.style.cssText = "font-weight: bold; width: 28px;";
        menuWrap.appendChild(expandBtn);

        const kebab = _mkButton("⋮", 'btn-menu-trigger', function (e) { toggleCategoryMenu(e, this); });
        menuWrap.appendChild(kebab);

        const dropdown = document.createElement('div');
        dropdown.className = 'category-dropdown-menu';
        if (hasUrl) {
            dropdown.appendChild(_mkButton(t('menu_reload'), '',
                () => { reloadCategory(category); closeAllMenus(); }));
        }
        dropdown.appendChild(_mkButton(t('menu_edit_sub'), '',
            () => { openEditSubModal(category); closeAllMenus(); }));
        dropdown.appendChild(_mkButton(t('menu_deduplicate'), '',
            () => { deduplicateCategory(category); closeAllMenus(); }));
        dropdown.appendChild(_mkButton(t('menu_check_http'), 'btn-ping-category',
            (e) => checkHttpWithClose(e, category)));
        dropdown.appendChild(_mkButton(t('menu_check_ip'), 'btn-ping-category',
            (e) => checkIpWithClose(e, category)));
        dropdown.appendChild(_mkButton(t('menu_delete'), 'btn-delete-item',
            () => { removeCategory(category); closeAllMenus(); }));
        menuWrap.appendChild(dropdown);

        header.appendChild(menuWrap);
        group.appendChild(header);

        const listNode = document.createElement('div');
        listNode.className = 'nodes-list';
        listNode.style.display = isExpanded ? "block" : "none";
        group.appendChild(listNode);
        profiles[category].nodes.forEach(node => {
            const isSelected = activeConfig === `${category}:${node.id}`;
            const isChain = node.protocol === 'chain';
            const item = document.createElement('div');
            item.className = `config-item${isSelected ? ' selected' : ''}`;

            // Chain nodes show hop labels instead of address:port
            let metaLine;
            if (isChain) {
                try {
                    const fakeRawUri = node.rawUri.replace(/^chain:\/\//i, 'https://');
                    const u = new URL(fakeRawUri);
                    const hop1Uri = u.searchParams.get('hop1') || '';
                    const hop2Uri = u.searchParams.get('hop2') || '';
                    const proto1 = hop1Uri.split('://')[0].toUpperCase();
                    const proto2 = hop2Uri.split('://')[0].toUpperCase();
                    metaLine = `⛓ CHAIN: ${proto1} → ${proto2}`;
                } catch(e) {
                    metaLine = '⛓ CHAIN';
                }
            } else {
                // Assigned via textContent below, so no manual escaping here.
                metaLine = `${(node.protocol || '').toUpperCase()} | ${node.address}:${node.port}`;
            }

            const info = document.createElement('div');
            info.className = 'config-info';
            info.style.cssText = "flex: 1; display: flex; flex-direction: column;";
            const nameEl = document.createElement('div');
            nameEl.className = 'config-name';
            nameEl.textContent = node.name;
            const metaEl = document.createElement('div');
            metaEl.className = 'config-meta';
            metaEl.textContent = metaLine;
            info.appendChild(nameEl);
            info.appendChild(metaEl);
            info.addEventListener('click', () => selectNode(category, node.id));
            item.appendChild(info);

            const actions = document.createElement('div');
            actions.className = 'node-actions-container';
            if (isSelected) {
                const pin = document.createElement('span');
                pin.textContent = '📌';
                actions.appendChild(pin);
            }

            const nodeMenuWrap = document.createElement('div');
            nodeMenuWrap.className = 'node-menu-container';
            nodeMenuWrap.style.cssText = "display: flex; align-items: center; justify-content: flex-end; gap: 8px; position: relative;";

            const pingSpan = document.createElement('span');
            pingSpan.id = `ping-${category}-${node.id}`;
            pingSpan.className = 'ping-info';
            pingSpan.style.cssText = "text-align: right; white-space: nowrap;";
            nodeMenuWrap.appendChild(pingSpan);

            const nodeKebab = _mkButton("⋮", 'btn-menu-trigger', function (e) { toggleNodeMenu(e, this); });
            nodeKebab.style.flexShrink = '0';
            nodeMenuWrap.appendChild(nodeKebab);

            const nodeDropdown = document.createElement('div');
            nodeDropdown.className = 'node-dropdown-menu';
            // Chain nodes open the chain modal; regular nodes open edit-node-modal
            nodeDropdown.appendChild(_mkButton(t('menu_edit'), '', (e) => (
                isChain ? openProxyChainEditModal(e, category, node.id)
                        : openEditNodeModal(e, category, node.id)
            )));
            nodeDropdown.appendChild(_mkButton(t('menu_copy_payload'), '',
                (e) => copyNodePayloadUrl(e, category, node.id)));
            nodeDropdown.appendChild(_mkButton(t('menu_copy_full_config'), '',
                (e) => copyNodeFullConfig(e, category, node.id)));
            nodeDropdown.appendChild(_mkButton(t('menu_check_http'), 'btn-ping-category',
                (e) => checkSingleHttpWithClose(e, category, node.id)));
            nodeDropdown.appendChild(_mkButton(t('menu_check_ip'), 'btn-ping-category',
                (e) => checkSingleIpWithClose(e, category, node.id)));
            nodeDropdown.appendChild(_mkButton(t('menu_delete'), 'btn-delete-item',
                (e) => deleteNode(e, category, node.id)));
            nodeMenuWrap.appendChild(nodeDropdown);

            actions.appendChild(nodeMenuWrap);
            item.appendChild(actions);
            listNode.appendChild(item);
        });
        container.appendChild(group);
    }
}

function toggleCategoryExpand(event, category) {
    event.stopPropagation();
    categoryExpandedState[category] = !categoryExpandedState[category];
    renderProfiles();
}

function toggleCategoryMenu(event, button) {
    event.stopPropagation();
    const currentMenu = button.nextElementSibling;
    const isOpen = currentMenu.classList.contains('show');
    closeAllMenus();
    if (!isOpen) {
        currentMenu.classList.add('show');
    }
}

function toggleNodeMenu(event, button) {
    event.stopPropagation();
    const currentMenu = button.nextElementSibling;
    const isOpen = currentMenu.classList.contains('show');
    closeAllMenus();
    if (!isOpen) {
        currentMenu.classList.add('show');
    }
}

function deleteNode(event, category, id) {
    event.stopPropagation();
    closeAllMenus();
    if (!profiles[category]) return;
    profiles[category].nodes = profiles[category].nodes.filter(n => n.id !== id);
    if (activeConfig === `${category}:${id}`) {
        activeConfig = null;
        saveActiveConfig();
    }
    saveProfiles();
    renderProfiles();
}

function copyNodePayloadUrl(event, category, id) {
    event.stopPropagation();
    closeAllMenus();
    const node = profiles[category]?.nodes?.find(n => n.id === id);
    if (!node) return;
    navigator.clipboard.writeText(node.rawUri || '').then(() => {
        showToast(t('toast_node_payload_copied'), 'success');
    }).catch(() => {
        showToast(t('toast_node_payload_copy_fail'), 'error');
    });
}

function copyNodeFullConfig(event, category, id) {
    event.stopPropagation();
    closeAllMenus();
    const node = profiles[category]?.nodes?.find(n => n.id === id);
    if (!node) return;

    let configStr;
    try {
        configStr = _resolveXrayConfig(node.rawUri);
    } catch (e) {
        showToast(t('toast_node_config_gen_fail'), 'error');
        return;
    }

    try {
        const parsed = JSON.parse(configStr);
        if (parsed && parsed.error) {
            showToast(t('toast_node_config_gen_fail'), 'error');
            return;
        }
    } catch (e) { /* not JSON-parseable — fall through and copy as-is */ }

    navigator.clipboard.writeText(configStr).then(() => {
        showToast(t('toast_node_config_copied'), 'success');
    }).catch(() => {
        showToast(t('toast_node_config_copy_fail'), 'error');
    });
}

function getFullNodeDetails(node) {
    const uri = node.rawUri.trim();
    const protocol = node.protocol;
    let d = {
        name: node.name || "Unnamed Node",
        address: node.address || "",
        port: node.port || "443",
        uuid: node.uuid || "",
        encryption: "none",
        flow: "",
        network: "tcp",
        // TCP HTTP header
        tcpHeaderType: "none",
        tcpHttpHost: "",
        tcpHttpPath: "/",
        // KCP
        kcpHeader: "none",
        kcpHost: "",
        kcpSeed: "",
        // WS
        wsPath: "/",
        wsHost: "",
        // HTTPUpgrade
        httpupgradeHost: "",
        httpupgradePath: "/",
        // XHTTP
        xhttpMode: "auto",
        xhttpHost: "",
        xhttpPath: "/",
        xhttpExtra: "",
        // H2
        h2Host: "",
        h2Path: "/",
        // gRPC
        grpcMode: "gun",
        grpcAuth: "",
        grpcServiceName: "",
        // Security
        security: "none",
        sni: "",
        fingerprint: "chrome",
        alpn: "",
        publicKey: "",
        shortId: "",
        spiderX: "",
        pqv: "",
        allowInsecure: false,
        pcs: "",
        ech: "",
        alterId: "0",
        headerType: "none",
        // WireGuard
        wgSecretKey: "",
        wgPublicKey: "",
        wgPresharedKey: "",
        wgReserved: "",
        wgLocalAddress: "172.16.0.2/32",
        // Hysteria2
        hy2ObfsPassword: "",
        hy2PortHopping: "",
        hy2HopInterval: "",
        hy2BandwidthDown: "",
        hy2BandwidthUp: "",
        hy2Sni: "",
        // SOCKS / HTTP proxy auth
        proxyUsername: "",
        proxyPassword: "",
        // Shadowsocks method
        ssMethod: "aes-256-gcm"
    };

    if (protocol === 'vmess') {
        try {
            const base64Part = uri.includes("://") ? uri.split("://")[1] : uri;
            const rawJson = decodeBase64(base64Part.trim());
            const c = JSON.parse(rawJson);
            d.address = c.add || "";
            d.port = c.port || "443";
            d.uuid = c.id || "";
            d.encryption = c.scy || "none";
            d.network = c.net || "tcp";
            d.security = c.tls || "none";
            d.sni = c.sni || "";
            d.alpn = c.alpn || "";
            d.allowInsecure = c.allowInsecure === true || c.allowInsecure === "1" || c.allowInsecure === 1;
            d.pcs = c.pcs || "";
            d.ech = c.ech || "";
            d.alterId = c.aid !== undefined ? String(c.aid) : "0";
            d.headerType = c.type || "none";
            // Per-network fields
            if (c.net === 'tcp') {
                d.tcpHeaderType = c.type || "none";
                if (c.type === 'http') {
                    d.tcpHttpHost = c.host || "";
                    d.tcpHttpPath = c.path || "/";
                }
            } else if (c.net === 'kcp' || c.net === 'mkcp') {
                d.kcpHeader = c.type || "none";
                d.kcpHost = c.host || "";
                d.kcpSeed = c.seed || "";
            } else if (c.net === 'ws') {
                d.wsPath = c.path || "/";
                d.wsHost = c.host || "";
            } else if (c.net === 'httpupgrade') {
                d.httpupgradeHost = c.host || "";
                d.httpupgradePath = c.path || "/";
            } else if (c.net === 'xhttp' || c.net === 'splithttp') {
                d.xhttpMode = c.mode || "auto";
                d.xhttpHost = c.host || "";
                d.xhttpPath = c.path || "/";
                d.xhttpExtra = c.extra ? JSON.stringify(c.extra) : "";
            } else if (c.net === 'h2' || c.net === 'http') {
                d.h2Host = c.host || "";
                d.h2Path = c.path || "/";
            } else if (c.net === 'grpc') {
                d.grpcServiceName = c.path || "";
                d.grpcMode = c.mode || "gun";
                d.grpcAuth = c.authority || "";
            }
        } catch (e) { console.error("Error parsing vmess json", e); }
    } else {
        try {
            // Fix parser on old Chrome
            const fakeHttpUri = uri.replace(/^(vless|trojan|wg|wireguard|hy2|hysteria2|socks5|socks):\/\//i, 'https://');
            const u = new URL(fakeHttpUri);
            const p = new URLSearchParams(u.search);
            d.uuid = decodeURIComponent(u.username);
            d.address = u.hostname;
            d.port = u.port || "443";
            d.network = p.get('type') || 'tcp';
            d.security = p.get('security') || 'none';
            d.flow = p.get('flow') || '';
            d.sni = p.get('sni') || '';
            d.alpn = p.get('alpn') || '';
            d.fingerprint = p.get('fp') || 'chrome';
            d.allowInsecure = p.get('insecure') === '1' || p.get('allowInsecure') === '1' || p.get('allowInsecure') === 'true';
            d.pcs = p.get('pcs') || '';
            d.ech = p.get('ech') || '';

            // Per-network fields
            if (d.network === 'tcp') {
                d.tcpHeaderType = p.get('headerType') || 'none';
                if (d.tcpHeaderType === 'http') {
                    d.tcpHttpHost = p.get('host') || '';
                    d.tcpHttpPath = p.get('path') || '/';
                }
            } else if (d.network === 'kcp' || d.network === 'mkcp') {
                d.kcpHeader = p.get('headerType') || 'none';
                d.kcpHost = p.get('host') || '';
                d.kcpSeed = p.get('seed') || '';
            } else if (d.network === 'ws') {
                d.wsPath = p.get('path') || '/';
                d.wsHost = p.get('host') || '';
            } else if (d.network === 'httpupgrade') {
                d.httpupgradeHost = p.get('host') || '';
                d.httpupgradePath = p.get('path') || '/';
            } else if (d.network === 'xhttp' || d.network === 'splithttp') {
                d.xhttpMode = p.get('mode') || 'auto';
                d.xhttpHost = p.get('host') || '';
                d.xhttpPath = p.get('path') || '/';
                try { d.xhttpExtra = p.get('extra') ? JSON.stringify(JSON.parse(p.get('extra'))) : ''; } catch(e) { d.xhttpExtra = p.get('extra') || ''; }
            } else if (d.network === 'h2' || d.network === 'http') {
                d.h2Host = p.get('host') || '';
                d.h2Path = p.get('path') || '/';
            } else if (d.network === 'grpc') {
                d.grpcServiceName = p.get('serviceName') || p.get('path') || '';
                d.grpcMode = p.get('mode') || 'gun';
                d.grpcAuth = p.get('authority') || '';
            }

            if (d.security === 'reality') {
                d.publicKey = p.get('pbk') || '';
                d.shortId = p.get('sid') || '';
                d.spiderX = p.get('spx') || '';
                d.pqv = p.get('pqv') || '';
            }
        } catch (e) { console.error("Error parsing standard URL mapping", e); }
    }

    // Shadowsocks
    if (protocol === 'shadowsocks') {
        // Parse method directly from the URI (base64 or plain-text userinfo)
        try {
            const atIdx = uri.indexOf('@');
            if (atIdx !== -1) {
                const schemeEnd = uri.indexOf('://') + 3;
                const rawUser = uri.substring(schemeEnd, atIdx);
                let method = null;
                // Try base64 decode first
                try {
                    const decoded = decodeBase64(rawUser);
                    if (decoded && decoded.includes(':')) {
                        method = decoded.substring(0, decoded.indexOf(':'));
                    }
                } catch(e) {}
                // Fallback: plain-text URL-encoded
                if (!method) {
                    const plain = decodeURIComponent(rawUser);
                    if (plain.includes(':')) method = plain.substring(0, plain.indexOf(':'));
                }
                if (method) d.ssMethod = method;
            }
        } catch(e) {}
        // Final fallback to stored node.security
        if (!d.ssMethod) d.ssMethod = node.security || "aes-256-gcm";
        d.uuid = node.uuid || ""; // password

        // Transport/security: either the extended type=/security= query params
        // (this project's own format, same shape as vless/trojan), or a legacy
        // SIP003 plugin= param translated into the equivalent network/security
        // fields so it renders correctly in the standard Transport/Security UI.
        try {
            const qIdx = uri.indexOf('?');
            if (qIdx !== -1) {
                const hashIdx = uri.indexOf('#');
                const qEnd = (hashIdx !== -1 && hashIdx > qIdx) ? hashIdx : uri.length;
                const p = new URLSearchParams(uri.substring(qIdx + 1, qEnd));

                if (p.get('type')) {
                    d.network = p.get('type') || 'tcp';
                    d.security = p.get('security') || 'none';
                    d.sni = p.get('sni') || '';
                    d.alpn = p.get('alpn') || '';
                    d.fingerprint = p.get('fp') || 'chrome';
                    d.allowInsecure = p.get('insecure') === '1' || p.get('allowInsecure') === '1' || p.get('allowInsecure') === 'true';
                    d.pcs = p.get('pcs') || '';
                    d.ech = p.get('ech') || '';

                    if (d.network === 'tcp') {
                        d.tcpHeaderType = p.get('headerType') || 'none';
                        if (d.tcpHeaderType === 'http') {
                            d.tcpHttpHost = p.get('host') || '';
                            d.tcpHttpPath = p.get('path') || '/';
                        }
                    } else if (d.network === 'kcp' || d.network === 'mkcp') {
                        d.kcpHeader = p.get('headerType') || 'none';
                        d.kcpHost = p.get('host') || '';
                        d.kcpSeed = p.get('seed') || '';
                    } else if (d.network === 'ws') {
                        d.wsPath = p.get('path') || '/';
                        d.wsHost = p.get('host') || '';
                    } else if (d.network === 'httpupgrade') {
                        d.httpupgradeHost = p.get('host') || '';
                        d.httpupgradePath = p.get('path') || '/';
                    } else if (d.network === 'xhttp' || d.network === 'splithttp') {
                        d.xhttpMode = p.get('mode') || 'auto';
                        d.xhttpHost = p.get('host') || '';
                        d.xhttpPath = p.get('path') || '/';
                        try { d.xhttpExtra = p.get('extra') ? JSON.stringify(JSON.parse(p.get('extra'))) : ''; } catch(e) { d.xhttpExtra = p.get('extra') || ''; }
                    } else if (d.network === 'h2' || d.network === 'http') {
                        d.h2Host = p.get('host') || '';
                        d.h2Path = p.get('path') || '/';
                    } else if (d.network === 'grpc') {
                        d.grpcServiceName = p.get('serviceName') || p.get('path') || '';
                        d.grpcMode = p.get('mode') || 'gun';
                        d.grpcAuth = p.get('authority') || '';
                    }

                    if (d.security === 'reality') {
                        d.publicKey = p.get('pbk') || '';
                        d.shortId = p.get('sid') || '';
                        d.spiderX = p.get('spx') || '';
                        d.pqv = p.get('pqv') || '';
                    }
                } else {
                    const pluginStr = p.get('plugin');
                    if (pluginStr) {
                        const parts = pluginStr.split(';');
                        if (parts[0] === 'v2ray-plugin') {
                            const opts = {};
                            for (let i = 1; i < parts.length; i++) {
                                const seg = parts[i];
                                if (!seg) continue;
                                const eq = seg.indexOf('=');
                                if (eq === -1) opts[seg] = true;
                                else opts[seg.substring(0, eq)] = seg.substring(eq + 1);
                            }
                            // quic legacy mode isn't editable in the UI; still land
                            // on ws so the node opens with sane defaults.
                            d.network = 'ws';
                            d.security = opts.tls ? 'tls' : 'none';
                            d.wsHost = opts.host || '';
                            d.wsPath = opts.path || '/';
                            if (opts.tls) d.sni = opts.host || '';
                        }
                    }
                }
            }
        } catch(e) {}
    }

    // WireGuard
    if (protocol === 'wireguard') {
        try {
            const u = new URL(uri.replace(/^wg:\/\//, 'wireguard://'));
            const p = new URLSearchParams(u.search);
            d.wgSecretKey = u.username ? decodeURIComponent(u.username) : "";
            d.wgPublicKey = p.get('publickey') || p.get('PublicKey') || "";
            d.wgPresharedKey = p.get('presharedkey') || p.get('PreSharedKey') || "";
            d.wgReserved = p.get('reserved') || "";
            d.wgLocalAddress = p.get('address') || p.get('ip') || "172.16.0.2/32";
            d.wgMTU = p.get('mtu') || 1420;
        } catch(e) {}
    }

    // Hysteria2
    if (protocol === 'hysteria2') {
        try {
            const fixedUri = uri.replace(/^hy2:\/\//, 'hysteria2://');
            const u = new URL(fixedUri);
            const p = new URLSearchParams(u.search);
            d.uuid = decodeURIComponent(u.username);
            const obfs = p.get('obfs-password') || p.get('obfsPassword') || "";
            d.hy2ObfsPassword = obfs;
            d.hy2Sni = p.get('sni') || p.get('peer') || "";
            d.hy2BandwidthDown = p.get('down') || p.get('bandwidth') || "";
            d.hy2BandwidthUp = p.get('up') || "";
            d.hy2PortHopping = p.get('mport') || "";
            d.hy2HopInterval = p.get('hopInterval') || "";
        } catch(e) {}
    }

    // SOCKS
    if (protocol === 'socks') {
        try {
            const u = new URL(uri);
            d.proxyUsername = u.username ? decodeURIComponent(u.username) : "";
            d.proxyPassword = u.password ? decodeURIComponent(u.password) : "";
        } catch(e) {}
    }

    // HTTP proxy
    if (protocol === 'http') {
        try {
            const u = new URL(uri);
            d.proxyUsername = u.username ? decodeURIComponent(u.username) : "";
            d.proxyPassword = u.password ? decodeURIComponent(u.password) : "";
        } catch(e) {}
    }

    return d;
}

function serializeNodeDetailsToUri(d, protocol) {
    // Shadowsocks
    if (protocol === 'shadowsocks') {
        const method = d.ssMethod || "aes-256-gcm";
        const password = d.uuid || "";
        const userPart = btoa(`${method}:${password}`);
        let urlStr = `ss://${userPart}@${d.address}:${d.port}`;

        // Same transport/security query params as vless/trojan — covers
        // tcp/kcp/ws/httpupgrade/xhttp/h2/grpc plus tls/reality.
        let params = new URLSearchParams();
        params.set('type', d.network || 'tcp');
        params.set('security', d.security || 'none');
        if (d.security === 'tls' || d.security === 'reality') {
            if (d.sni) params.set('sni', d.sni);
            if (d.alpn) params.set('alpn', d.alpn);
            if (d.fingerprint) params.set('fp', d.fingerprint);
        }
        if (d.security === 'tls') {
            // "allowInsecure" was removed by Xray-core; never emit it into the exported URI.
            if (d.pcs) params.set('pcs', d.pcs);
            if (d.ech) params.set('ech', d.ech);
        }
        if (d.network === 'tcp' && d.tcpHeaderType && d.tcpHeaderType !== 'none') {
            params.set('headerType', d.tcpHeaderType);
            if (d.tcpHeaderType === 'http') {
                if (d.tcpHttpHost) params.set('host', d.tcpHttpHost);
                if (d.tcpHttpPath) params.set('path', d.tcpHttpPath);
            }
        } else if (d.network === 'kcp' || d.network === 'mkcp') {
            if (d.kcpHeader && d.kcpHeader !== 'none') params.set('headerType', d.kcpHeader);
            if (d.kcpHost) params.set('host', d.kcpHost);
            if (d.kcpSeed) params.set('seed', d.kcpSeed);
        } else if (d.network === 'ws') {
            if (d.wsPath) params.set('path', d.wsPath);
            if (d.wsHost) params.set('host', d.wsHost);
        } else if (d.network === 'httpupgrade') {
            if (d.httpupgradeHost) params.set('host', d.httpupgradeHost);
            if (d.httpupgradePath) params.set('path', d.httpupgradePath);
        } else if (d.network === 'xhttp' || d.network === 'splithttp') {
            if (d.xhttpMode && d.xhttpMode !== 'auto') params.set('mode', d.xhttpMode);
            if (d.xhttpHost) params.set('host', d.xhttpHost);
            if (d.xhttpPath) params.set('path', d.xhttpPath);
            if (d.xhttpExtra) { try { params.set('extra', d.xhttpExtra); } catch(e) {} }
        } else if (d.network === 'h2' || d.network === 'http') {
            if (d.h2Host) params.set('host', d.h2Host);
            if (d.h2Path) params.set('path', d.h2Path);
        } else if (d.network === 'grpc') {
            if (d.grpcServiceName) params.set('serviceName', d.grpcServiceName);
            if (d.grpcMode && d.grpcMode !== 'gun') params.set('mode', d.grpcMode);
            if (d.grpcAuth) params.set('authority', d.grpcAuth);
        }
        if (d.security === 'reality') {
            if (d.publicKey) params.set('pbk', d.publicKey);
            if (d.shortId) params.set('sid', d.shortId);
            if (d.spiderX) params.set('spx', d.spiderX);
            if (d.pqv) params.set('pqv', d.pqv);
        }
        let pStr = params.toString();
        if (pStr) urlStr += "?" + pStr;

        if (d.name) urlStr += "#" + encodeURIComponent(d.name);
        return urlStr;
    }

    // WireGuard
    if (protocol === 'wireguard') {
        const params = new URLSearchParams();
        if (d.wgPublicKey) params.set('publickey', d.wgPublicKey);
        if (d.wgPresharedKey) params.set('presharedkey', d.wgPresharedKey);
        if (d.wgReserved) params.set('reserved', d.wgReserved);
        if (d.wgLocalAddress) params.set('address', d.wgLocalAddress);
        if (d.wgMTU) params.set('mtu', d.wgMTU);
        const user = d.wgSecretKey ? encodeURIComponent(d.wgSecretKey) : "";
        let urlStr = `wireguard://${user}@${d.address}:${d.port}`;
        const pStr = params.toString();
        if (pStr) urlStr += "?" + pStr;
        if (d.name) urlStr += "#" + encodeURIComponent(d.name);
        return urlStr;
    }

    // Hysteria2
    if (protocol === 'hysteria2') {
        const params = new URLSearchParams();
        if (d.hy2ObfsPassword) { params.set('obfs', 'salamander'); params.set('obfs-password', d.hy2ObfsPassword); }
        if (d.hy2Sni) params.set('sni', d.hy2Sni);
        if (d.hy2BandwidthDown) params.set('down', d.hy2BandwidthDown);
        if (d.hy2BandwidthUp) params.set('up', d.hy2BandwidthUp);
        if (d.hy2PortHopping) params.set('mport', d.hy2PortHopping);
        if (d.hy2HopInterval) params.set('hopInterval', d.hy2HopInterval);
        const user = d.uuid ? encodeURIComponent(d.uuid) : "";
        let urlStr = `hysteria2://${user}@${d.address}:${d.port}`;
        const pStr = params.toString();
        if (pStr) urlStr += "?" + pStr;
        if (d.name) urlStr += "#" + encodeURIComponent(d.name);
        return urlStr;
    }

    // SOCKS
    if (protocol === 'socks') {
        let auth = "";
        if (d.proxyUsername) {
            auth = encodeURIComponent(d.proxyUsername);
            if (d.proxyPassword) auth += ":" + encodeURIComponent(d.proxyPassword);
            auth += "@";
        }
        let urlStr = `socks://${auth}${d.address}:${d.port}`;
        if (d.name) urlStr += "#" + encodeURIComponent(d.name);
        return urlStr;
    }

    // HTTP proxy
    if (protocol === 'http') {
        let auth = "";
        if (d.proxyUsername) {
            auth = encodeURIComponent(d.proxyUsername);
            if (d.proxyPassword) auth += ":" + encodeURIComponent(d.proxyPassword);
            auth += "@";
        }
        let urlStr = `http://${auth}${d.address}:${d.port}`;
        if (d.name) urlStr += "#" + encodeURIComponent(d.name);
        return urlStr;
    }

    if (protocol === 'vmess') {
        let c = {
            v: "2", ps: d.name, add: d.address, port: parseInt(d.port) || 443, id: d.uuid,
            aid: parseInt(d.alterId) || 0, scy: d.encryption || "none", net: d.network,
            tls: d.security === 'tls' ? 'tls' : 'none',
            sni: d.security === 'tls' ? d.sni : "",
            alpn: d.security === 'tls' ? d.alpn : "",
            type: "none", host: "", path: ""
        };
        if (d.security === 'tls') {
            // "allowInsecure" was removed by Xray-core; never emit it into the exported vmess link.
            if (d.pcs) c.pcs = d.pcs;
            if (d.ech) c.ech = d.ech;
        }
        if (d.network === 'tcp') {
            c.type = d.tcpHeaderType || "none";
            if (d.tcpHeaderType === 'http') { c.host = d.tcpHttpHost; c.path = d.tcpHttpPath; }
        } else if (d.network === 'kcp' || d.network === 'mkcp') {
            c.type = d.kcpHeader || "none";
            c.host = d.kcpHost || "";
            c.seed = d.kcpSeed || "";
        } else if (d.network === 'ws') {
            c.path = d.wsPath || "/"; c.host = d.wsHost || "";
        } else if (d.network === 'httpupgrade') {
            c.host = d.httpupgradeHost || ""; c.path = d.httpupgradePath || "/";
        } else if (d.network === 'xhttp' || d.network === 'splithttp') {
            c.mode = d.xhttpMode || "auto";
            c.host = d.xhttpHost || ""; c.path = d.xhttpPath || "/";
            if (d.xhttpExtra) { try { c.extra = JSON.parse(d.xhttpExtra); } catch(e) {} }
        } else if (d.network === 'h2' || d.network === 'http') {
            c.host = d.h2Host || ""; c.path = d.h2Path || "/";
        } else if (d.network === 'grpc') {
            c.path = d.grpcServiceName || "";
            c.mode = d.grpcMode || "gun";
            c.authority = d.grpcAuth || "";
        }
        return "vmess://" + utoa(JSON.stringify(c));
    } else {
        let urlStr = `${protocol}://${encodeURIComponent(d.uuid)}@${d.address}:${d.port}`;
        let params = new URLSearchParams();
        if (d.network && d.network !== 'tcp') params.set('type', d.network);
        if (d.security !== 'none') params.set('security', d.security);
        if (protocol === 'vless' && d.flow && (d.security === 'tls' || d.security === 'reality')) params.set('flow', d.flow);
        if (d.security === 'tls' || d.security === 'reality') {
            if (d.sni) params.set('sni', d.sni);
            if (d.alpn) params.set('alpn', d.alpn);
            if (d.fingerprint) params.set('fp', d.fingerprint);
        }
        if (d.security === 'tls') {
            // "allowInsecure" was removed by Xray-core; never emit it into the exported URI.
            if (d.pcs) params.set('pcs', d.pcs);
            if (d.ech) params.set('ech', d.ech);
        }
        // Per-network params
        if (d.network === 'tcp' && d.tcpHeaderType && d.tcpHeaderType !== 'none') {
            params.set('headerType', d.tcpHeaderType);
            if (d.tcpHeaderType === 'http') {
                if (d.tcpHttpHost) params.set('host', d.tcpHttpHost);
                if (d.tcpHttpPath) params.set('path', d.tcpHttpPath);
            }
        } else if (d.network === 'kcp' || d.network === 'mkcp') {
            if (d.kcpHeader && d.kcpHeader !== 'none') params.set('headerType', d.kcpHeader);
            if (d.kcpHost) params.set('host', d.kcpHost);
            if (d.kcpSeed) params.set('seed', d.kcpSeed);
        } else if (d.network === 'ws') {
            if (d.wsPath) params.set('path', d.wsPath);
            if (d.wsHost) params.set('host', d.wsHost);
        } else if (d.network === 'httpupgrade') {
            if (d.httpupgradeHost) params.set('host', d.httpupgradeHost);
            if (d.httpupgradePath) params.set('path', d.httpupgradePath);
        } else if (d.network === 'xhttp' || d.network === 'splithttp') {
            if (d.xhttpMode && d.xhttpMode !== 'auto') params.set('mode', d.xhttpMode);
            if (d.xhttpHost) params.set('host', d.xhttpHost);
            if (d.xhttpPath) params.set('path', d.xhttpPath);
            if (d.xhttpExtra) { try { params.set('extra', d.xhttpExtra); } catch(e) {} }
        } else if (d.network === 'h2' || d.network === 'http') {
            if (d.h2Host) params.set('host', d.h2Host);
            if (d.h2Path) params.set('path', d.h2Path);
        } else if (d.network === 'grpc') {
            if (d.grpcServiceName) params.set('serviceName', d.grpcServiceName);
            if (d.grpcMode && d.grpcMode !== 'gun') params.set('mode', d.grpcMode);
            if (d.grpcAuth) params.set('authority', d.grpcAuth);
        }
        if (d.security === 'reality') {
            if (d.publicKey) params.set('pbk', d.publicKey);
            if (d.shortId) params.set('sid', d.shortId);
            if (d.spiderX) params.set('spx', d.spiderX);
            if (d.pqv) params.set('pqv', d.pqv);
        }
        let pStr = params.toString();
        if (pStr) urlStr += "?" + pStr;
        if (d.name) urlStr += "#" + encodeURIComponent(d.name);
        return urlStr;
    }
}

function openEditNodeModal(event, category, id) {
    event.stopPropagation();
    closeAllMenus();

    const node = profiles[category]?.nodes?.find(n => n.id === id);
    if (!node) return;

    currentEditingCategory = category;
    currentEditingNodeId = id;
    currentEditingProtocol = node.protocol;
    _populateEditModal(node);
}

function openNewNodeModal(protocol) {
    // Create a temporary empty node so we can reuse the same modal
    const tempId = '__new__' + Math.random().toString(36).substr(2, 9);
    // Ensure Manual category exists
    if (!profiles['Manual']) profiles['Manual'] = { url: null, nodes: [] };
    const emptyNode = {
        id: tempId,
        name: "",
        protocol,
        address: "",
        port: protocol === 'wireguard' ? "51820" : protocol === 'socks' ? "1080" : protocol === 'http' ? "8080" : "443",
        uuid: "",
        security: protocol === 'hysteria2' ? "tls" : "none",
        rawUri: `${protocol}://@:`
    };
    currentEditingCategory = 'Manual';
    currentEditingNodeId = tempId;
    currentEditingProtocol = protocol;
    _populateEditModal(emptyNode, true);
}

function _populateEditModal(node, isNew = false) {
    const d = isNew ? {
        name: "", address: "", port: node.port || "443", uuid: "", encryption: "auto",
        flow: "", network: "tcp", tcpHeaderType: "none", tcpHttpHost: "", tcpHttpPath: "/",
        kcpHeader: "none", kcpHost: "", kcpSeed: "", wsPath: "/", wsHost: "",
        httpupgradeHost: "", httpupgradePath: "/", xhttpMode: "auto", xhttpHost: "",
        xhttpPath: "/", xhttpExtra: "", h2Host: "", h2Path: "/", grpcMode: "gun",
        grpcAuth: "", grpcServiceName: "", security: node.security || "none", sni: "",
        fingerprint: "chrome", alpn: "", publicKey: "", shortId: "", spiderX: "", pqv: "", alterId: "0",
        headerType: "none", wgSecretKey: "", wgPublicKey: "", wgPresharedKey: "",
        wgReserved: "", wgLocalAddress: "172.16.0.2/32", hy2ObfsPassword: "",
        hy2PortHopping: "", hy2HopInterval: "", hy2BandwidthDown: "", hy2BandwidthUp: "",
        hy2Sni: "", proxyUsername: "", proxyPassword: "", ssMethod: "aes-256-gcm"
    } : getFullNodeDetails(node);

    const proto = node.protocol;

    document.getElementById('edit-remarks').value = d.name;
    document.getElementById('edit-address').value = d.address;
    document.getElementById('edit-port').value = d.port;
    document.getElementById('edit-uuid').value = d.uuid;
    const encSelect = document.getElementById('edit-encryption');
    const encVal = d.encryption || 'auto';
    encSelect.value = [...encSelect.options].some(o => o.value === encVal) ? encVal : 'auto';
    document.getElementById('edit-flow').value = d.flow;
    document.getElementById('edit-network').value = d.network;
    // TCP
    document.getElementById('edit-header-type').value = d.tcpHeaderType || 'none';
    document.getElementById('edit-tcp-http-host').value = d.tcpHttpHost;
    document.getElementById('edit-tcp-http-path').value = d.tcpHttpPath;
    // KCP
    document.getElementById('edit-kcp-header').value = d.kcpHeader || 'none';
    document.getElementById('edit-kcp-host').value = d.kcpHost;
    document.getElementById('edit-kcp-seed').value = d.kcpSeed;
    // WS
    document.getElementById('edit-ws-path').value = d.wsPath;
    document.getElementById('edit-ws-host').value = d.wsHost;
    // HTTPUpgrade
    document.getElementById('edit-httpupgrade-host').value = d.httpupgradeHost;
    document.getElementById('edit-httpupgrade-path').value = d.httpupgradePath;
    // XHTTP
    document.getElementById('edit-xhttp-mode').value = d.xhttpMode || 'auto';
    document.getElementById('edit-xhttp-host').value = d.xhttpHost;
    document.getElementById('edit-xhttp-path').value = d.xhttpPath;
    document.getElementById('edit-xhttp-extra').value = d.xhttpExtra;
    // H2
    document.getElementById('edit-h2-host').value = d.h2Host;
    document.getElementById('edit-h2-path').value = d.h2Path;
    // gRPC
    document.getElementById('edit-grpc-mode').value = d.grpcMode;
    document.getElementById('edit-grpc-auth').value = d.grpcAuth;
    document.getElementById('edit-grpc-service').value = d.grpcServiceName;
    // Security
    document.getElementById('edit-security').value = d.security;
    document.getElementById('edit-sni').value = d.sni;
    document.getElementById('edit-fingerprint').value = d.fingerprint;
    document.getElementById('edit-alpn').value = d.alpn;
    document.getElementById('edit-allowinsecure').checked = !!d.allowInsecure;
    document.getElementById('edit-pcs').value = d.pcs || '';
    document.getElementById('edit-ech').value = d.ech || '';
    document.getElementById('edit-pbk').value = d.publicKey;
    document.getElementById('edit-sid').value = d.shortId;
    document.getElementById('edit-spx').value = d.spiderX || '';
    document.getElementById('edit-pqv').value = d.pqv || '';
    document.getElementById('edit-alterid').value = d.alterId;
    // WireGuard
    document.getElementById('edit-wg-secret-key').value = d.wgSecretKey;
    document.getElementById('edit-wg-public-key').value = d.wgPublicKey;
    document.getElementById('edit-wg-preshared-key').value = d.wgPresharedKey;
    document.getElementById('edit-wg-reserved').value = d.wgReserved;
    document.getElementById('edit-wg-local-address').value = d.wgLocalAddress;
    document.getElementById('edit-wg-mtu').value = d.wgMTU || 1420;
    // Hysteria2
    document.getElementById('edit-hy2-obfs-password').value = d.hy2ObfsPassword;
    document.getElementById('edit-hy2-port-hopping').value = d.hy2PortHopping;
    document.getElementById('edit-hy2-hop-interval').value = d.hy2HopInterval;
    document.getElementById('edit-hy2-bandwidth-down').value = d.hy2BandwidthDown;
    document.getElementById('edit-hy2-bandwidth-up').value = d.hy2BandwidthUp;
    document.getElementById('edit-hy2-sni').value = d.hy2Sni;
    // SOCKS / HTTP proxy auth
    document.getElementById('edit-proxy-username').value = d.proxyUsername;
    document.getElementById('edit-proxy-password').value = d.proxyPassword;
    // SS method
    const ssMethodSel = document.getElementById('edit-ss-method');
    if (ssMethodSel) {
        const ssVal = d.ssMethod || 'aes-256-gcm';
        ssMethodSel.value = [...ssMethodSel.options].some(o => o.value === ssVal) ? ssVal : 'aes-256-gcm';
    }
    // Show/hide standard protocol fields
    const isSimpleProxy = (proto === 'socks' || proto === 'http');
    const isWireGuard = (proto === 'wireguard');
    const isHysteria2 = (proto === 'hysteria2');
    const isShadowsocks = (proto === 'shadowsocks');
    const isClassic = (proto === 'vmess' || proto === 'vless' || proto === 'trojan' || proto === 'shadowsocks');

    document.getElementById('field-group-uuid').style.display = (proto === 'wireguard') ? 'none' : 'flex';
    document.getElementById('field-group-encryption').style.display = (proto === 'vmess') ? 'flex' : 'none';
    document.getElementById('field-group-flow').style.display = (proto === 'vless') ? 'flex' : 'none';
    document.getElementById('field-group-alterid').style.display = (proto === 'vmess') ? 'flex' : 'none';
    document.getElementById('field-group-ss-method').style.display = isShadowsocks ? 'flex' : 'none';

    // Transport section: only for vmess/vless/trojan
    document.getElementById('section-transport-wrapper').style.display = isClassic ? 'block' : 'none';
    // Security section: only for vmess/vless/trojan
    document.getElementById('section-security-wrapper').style.display = isClassic ? 'block' : 'none';

    // WireGuard fields
    document.getElementById('subfields-wireguard').style.display = isWireGuard ? 'flex' : 'none';
    // Hysteria2 fields
    document.getElementById('subfields-hysteria2').style.display = isHysteria2 ? 'flex' : 'none';
    // Proxy auth fields
    document.getElementById('subfields-proxy-auth').style.display = isSimpleProxy ? 'flex' : 'none';

    // UUID label: "Password" for trojan/SS/Hysteria2, "ID" for vmess/vless
    const uuidLabel = document.querySelector('#edit-uuid')?.closest('.edit-item-field')?.querySelector('label');
    if (uuidLabel) {
        if (proto === 'trojan' || proto === 'shadowsocks' || proto === 'hysteria2') {
            uuidLabel.setAttribute('data-i18n', 'lbl_id');
        } else if (proto === 'http' || proto === 'socks') {
        // Hide uuid field entirely for http (auth handled by proxyUsername/Password)
            uuidLabel.style.display = 'none';
            document.getElementById('edit-uuid').closest('.edit-item-field').style.display = 'none';
        } else {
            uuidLabel.setAttribute('data-i18n', 'lbl_id');
        }
    }

    if (isNew) {
        document.getElementById('modal-edit-title-text').setAttribute('data-i18n', 'modal_edit_title');
    }

    updateEditFormVisibility();
    applyI18n();
    document.getElementById('edit-node-modal').style.display = 'block';
}

function updateEditFormVisibility() {
    const net = document.getElementById('edit-network').value;
    const sec = document.getElementById('edit-security').value;
    const tcpHeader = document.getElementById('edit-header-type').value;

    // TCP header-type row: only for tcp (and vmess tcp)
    const showTcpHeaderRow = (net === 'tcp');
    document.getElementById('field-group-header-type').style.display = showTcpHeaderRow ? 'flex' : 'none';
    // TCP HTTP subfields: only when tcp + http header
    document.getElementById('subfields-tcp-http').style.display = (showTcpHeaderRow && tcpHeader === 'http') ? 'flex' : 'none';

    // Per-network subfield panels
    document.getElementById('subfields-kcp').style.display = (net === 'kcp' || net === 'mkcp') ? 'flex' : 'none';
    document.getElementById('subfields-ws').style.display = (net === 'ws') ? 'flex' : 'none';
    document.getElementById('subfields-httpupgrade').style.display = (net === 'httpupgrade') ? 'flex' : 'none';
    document.getElementById('subfields-xhttp').style.display = (net === 'xhttp' || net === 'splithttp') ? 'flex' : 'none';
    document.getElementById('subfields-h2').style.display = (net === 'h2' || net === 'http') ? 'flex' : 'none';
    document.getElementById('subfields-grpc').style.display = (net === 'grpc') ? 'flex' : 'none';

    // Security subfields
    document.getElementById('subfields-tls').style.display = (sec === 'tls' || sec === 'reality') ? 'flex' : 'none';
    document.getElementById('subfields-tls-only').style.display = (sec === 'tls') ? 'flex' : 'none';
    document.getElementById('subfields-reality').style.display = (sec === 'reality') ? 'flex' : 'none';

    // Flow: vless only with tls or reality
    if (currentEditingProtocol === 'vless') {
        document.getElementById('field-group-flow').style.display = (sec === 'tls' || sec === 'reality') ? 'flex' : 'none';
    }

}

function closeEditNodeModal() {
    document.getElementById('edit-node-modal').style.display = 'none';
    currentEditingCategory = null;
    currentEditingNodeId = null;
    currentEditingProtocol = null;
}

function _collectEditFormData() {
    return {
        name: document.getElementById('edit-remarks').value.trim() || "Unnamed Node",
        address: document.getElementById('edit-address').value.trim(),
        port: document.getElementById('edit-port').value.trim() || "443",
        uuid: document.getElementById('edit-uuid').value.trim(),
        encryption: document.getElementById('edit-encryption').value.trim(),
        flow: document.getElementById('edit-flow').value,
        network: document.getElementById('edit-network').value,
        // TCP
        tcpHeaderType: document.getElementById('edit-header-type').value,
        tcpHttpHost: document.getElementById('edit-tcp-http-host').value.trim(),
        tcpHttpPath: document.getElementById('edit-tcp-http-path').value.trim() || "/",
        // KCP
        kcpHeader: document.getElementById('edit-kcp-header').value,
        kcpHost: document.getElementById('edit-kcp-host').value.trim(),
        kcpSeed: document.getElementById('edit-kcp-seed').value.trim(),
        // WS
        wsPath: document.getElementById('edit-ws-path').value.trim() || "/",
        wsHost: document.getElementById('edit-ws-host').value.trim(),
        // HTTPUpgrade
        httpupgradeHost: document.getElementById('edit-httpupgrade-host').value.trim(),
        httpupgradePath: document.getElementById('edit-httpupgrade-path').value.trim() || "/",
        // XHTTP
        xhttpMode: document.getElementById('edit-xhttp-mode').value,
        xhttpHost: document.getElementById('edit-xhttp-host').value.trim(),
        xhttpPath: document.getElementById('edit-xhttp-path').value.trim() || "/",
        xhttpExtra: document.getElementById('edit-xhttp-extra').value.trim(),
        // H2
        h2Host: document.getElementById('edit-h2-host').value.trim(),
        h2Path: document.getElementById('edit-h2-path').value.trim() || "/",
        // gRPC
        grpcMode: document.getElementById('edit-grpc-mode').value,
        grpcAuth: document.getElementById('edit-grpc-auth').value.trim(),
        grpcServiceName: document.getElementById('edit-grpc-service').value.trim(),
        // Security
        security: document.getElementById('edit-security').value,
        sni: document.getElementById('edit-sni').value.trim(),
        fingerprint: document.getElementById('edit-fingerprint').value,
        alpn: document.getElementById('edit-alpn').value.trim(),
        // "allowInsecure" was removed by Xray-core; always save as false, ignoring the (disabled) checkbox.
        allowInsecure: false,
        pcs: document.getElementById('edit-pcs').value.trim(),
        ech: document.getElementById('edit-ech').value.trim(),
        publicKey: document.getElementById('edit-pbk').value.trim(),
        shortId: document.getElementById('edit-sid').value.trim(),
        spiderX: document.getElementById('edit-spx').value.trim(),
        pqv: document.getElementById('edit-pqv').value.trim(),
        alterId: document.getElementById('edit-alterid').value.trim() || "0",
        headerType: document.getElementById('edit-header-type').value,
        // WireGuard
        wgSecretKey: document.getElementById('edit-wg-secret-key').value.trim(),
        wgPublicKey: document.getElementById('edit-wg-public-key').value.trim(),
        wgPresharedKey: document.getElementById('edit-wg-preshared-key').value.trim(),
        wgReserved: document.getElementById('edit-wg-reserved').value.trim(),
        wgLocalAddress: document.getElementById('edit-wg-local-address').value.trim() || "172.16.0.2/32",
        wgMTU: parseInt(document.getElementById('edit-wg-mtu').value, 10) || 1420,
        // Hysteria2
        hy2ObfsPassword: document.getElementById('edit-hy2-obfs-password').value.trim(),
        hy2PortHopping: document.getElementById('edit-hy2-port-hopping').value.trim(),
        hy2HopInterval: document.getElementById('edit-hy2-hop-interval').value.trim(),
        hy2BandwidthDown: document.getElementById('edit-hy2-bandwidth-down').value.trim(),
        hy2BandwidthUp: document.getElementById('edit-hy2-bandwidth-up').value.trim(),
        hy2Sni: document.getElementById('edit-hy2-sni').value.trim(),
        // SOCKS/HTTP proxy auth
        proxyUsername: document.getElementById('edit-proxy-username').value.trim(),
        proxyPassword: document.getElementById('edit-proxy-password').value.trim(),
        // Shadowsocks
        ssMethod: document.getElementById('edit-ss-method').value
    };
}

function saveEditedNode() {
    if (!currentEditingCategory || !currentEditingNodeId) return;

    const isNew = currentEditingNodeId.startsWith('__new__');
    const d = _collectEditFormData();
    const proto = currentEditingProtocol;
    const newUri = serializeNodeDetailsToUri(d, proto);

    // Determine security/uuid for stored node summary
    let storedSecurity = d.security;
    let storedUuid = d.uuid;
    if (proto === 'shadowsocks') { storedSecurity = d.ssMethod; storedUuid = d.uuid; }
    if (proto === 'hysteria2') { storedSecurity = 'tls'; }

    const nodeEntry = {
        id: isNew ? Math.random().toString(36).substr(2, 9) : currentEditingNodeId,
        name: d.name,
        protocol: proto,
        address: d.address,
        port: d.port,
        uuid: storedUuid,
        security: storedSecurity,
        rawUri: newUri
    };

    if (isNew) {
        if (!profiles['Manual']) profiles['Manual'] = { url: null, nodes: [] };
        profiles['Manual'].nodes.push(nodeEntry);
        showToast(t('toast_new_node_saved'), "success");
    } else {
        const nodeIdx = profiles[currentEditingCategory]?.nodes?.findIndex(n => n.id === currentEditingNodeId);
        if (nodeIdx === -1) return;
        profiles[currentEditingCategory].nodes[nodeIdx] = nodeEntry;
    }

    const wasActive = !isNew && activeConfig === `${currentEditingCategory}:${currentEditingNodeId}`;

    saveProfiles();
    closeEditNodeModal();
    renderProfiles();

    if (wasActive) applyActiveConfig();
}

function openEditSubModal(category) {
    const catData = profiles[category];
    if (!catData) return;

    document.getElementById('edit-sub-cat-name').value = category;
    document.getElementById('edit-sub-url').value = catData.url || '';
    document.getElementById('edit-sub-dedup').checked = catData.dedup !== false; // default true
    document.getElementById('edit-sub-insecure').checked = catData.insecure === true; // default false
    document.getElementById('edit-sub-modal').dataset.originalCat = category;
    document.getElementById('edit-sub-modal').style.display = 'block';
}

function closeEditSubModal() {
    document.getElementById('edit-sub-modal').style.display = 'none';
}

function saveEditedSubscription() {
    const modal = document.getElementById('edit-sub-modal');
    const originalCat = modal.dataset.originalCat;
    const newName = document.getElementById('edit-sub-cat-name').value.trim();
    const newUrl = document.getElementById('edit-sub-url').value.trim();
    const newDedup = document.getElementById('edit-sub-dedup').checked;
    const newInsecure = document.getElementById('edit-sub-insecure').checked;

    if (!newName) return;
    if (!profiles[originalCat]) return;

    // Rename category if name changed
    if (newName !== originalCat) {
        profiles[newName] = { ...profiles[originalCat] };
        delete profiles[originalCat];

        // Update activeConfig if it referenced old category name
        if (activeConfig && activeConfig.startsWith(originalCat + ':')) {
            activeConfig = newName + ':' + activeConfig.split(':')[1];
            saveActiveConfig();
        }
    }

    profiles[newName].url = newUrl || null;
    profiles[newName].dedup = newDedup;
    profiles[newName].insecure = newInsecure;

    saveProfiles();
    closeEditSubModal();
    renderProfiles();
}

function deduplicateCategory(category) {
    const catData = profiles[category];
    if (!catData || !catData.nodes) return;

    const seen = new Set();
    const before = catData.nodes.length;
    catData.nodes = catData.nodes.filter(node => {
        const key = buildNodeKey(node);
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
    const removed = before - catData.nodes.length;

    // If active node was removed, clear it
    if (activeConfig && activeConfig.startsWith(category + ':')) {
        const [_, currentId] = activeConfig.split(':');
        if (!catData.nodes.some(n => n.id === currentId)) {
            activeConfig = null;
            saveActiveConfig();
        }
    }

    saveProfiles();
    renderProfiles();
    showToast(t('toast_dedup_done', { removed, total: catData.nodes.length }), removed > 0 ? 'success' : 'info');
}

function closeAllMenus() {
    document.querySelectorAll('.category-dropdown-menu').forEach(menu => menu.classList.remove('show'));
    document.querySelectorAll('.node-dropdown-menu').forEach(menu => menu.classList.remove('show'));
    closeImportAddMenu();
}

function toggleImportAddMenu(event) {
    event.stopPropagation();
    const dropdown = document.getElementById('import-add-dropdown');
    const isOpen = dropdown.classList.contains('show');
    closeAllMenus();
    if (!isOpen) {
        dropdown.classList.add('show');
    }
}

function closeImportAddMenu() {
    const dropdown = document.getElementById('import-add-dropdown');
    if (dropdown) dropdown.classList.remove('show');
}

// Proxy Chain Modal

function _getAllNodeOptions() {
    const options = [];
    Object.keys(profiles).forEach(category => {
        const nodes = profiles[category]?.nodes;
        if (!nodes || nodes.length === 0) return;
        nodes.forEach(node => {
            // Chain nodes cannot be used as hops inside another chain
            if (node.protocol === 'chain') return;
            options.push({
                value: `${category}:${node.id}`,
                label: `[${category}] ${node.name || node.address} — ${(node.protocol || '').toUpperCase()}`
            });
        });
    });
    return options;
}

function _populateChainSelects() {
    const opts = _getAllNodeOptions();
    ['chain-hop1', 'chain-hop2'].forEach(selId => {
        const sel = document.getElementById(selId);
        if (!sel) return;
        const prev = sel.value;
        sel.innerHTML = `<option value="">${t('chain_select_node')}</option>`;
        opts.forEach(o => {
            const opt = document.createElement('option');
            opt.value = o.value;
            opt.textContent = o.label;
            sel.appendChild(opt);
        });
        if (prev) sel.value = prev;
    });
}

function openProxyChainModal() {
    _populateChainSelects();
    document.getElementById('chain-name').value = '';
    document.getElementById('chain-hop1').value = '';
    document.getElementById('chain-hop2').value = '';
    const modal = document.getElementById('proxy-chain-modal');
    delete modal.dataset.editingId;
    delete modal.dataset.editingCat;
    applyI18n();
    modal.style.display = 'block';
}

function closeProxyChainModal() {
    const modal = document.getElementById('proxy-chain-modal');
    modal.style.display = 'none';
    delete modal.dataset.editingId;
    delete modal.dataset.editingCat;
}

function saveProxyChain() {
    const hop1Key = document.getElementById('chain-hop1').value;
    const hop2Key = document.getElementById('chain-hop2').value;

    if (!hop1Key || !hop2Key) {
        showToast(t('toast_chain_select_nodes'), 'error');
        return;
    }
    if (hop1Key === hop2Key) {
        showToast(t('toast_chain_same_node'), 'error');
        return;
    }

    const [cat1, id1] = hop1Key.split(':');
    const [cat2, id2] = hop2Key.split(':');
    const hop1Node = profiles[cat1]?.nodes?.find(n => n.id === id1);
    const hop2Node = profiles[cat2]?.nodes?.find(n => n.id === id2);
    if (!hop1Node || !hop2Node) return;

    // Chain nodes cannot be used as hops inside another chain
    if (hop1Node.protocol === 'chain' || hop2Node.protocol === 'chain') {
        showToast(t('toast_chain_no_chain_hop'), 'error');
        return;
    }

    const chainName = document.getElementById('chain-name').value.trim()
        || `${hop1Node.name || hop1Node.address} → ${hop2Node.name || hop2Node.address}`;

    // Store chain as a synthetic node in Manual category
    if (!profiles['Manual']) profiles['Manual'] = { url: null, nodes: [] };

    // If editing an existing chain node, update it in-place
    const editingId = document.getElementById('proxy-chain-modal').dataset.editingId;
    const editingCat = document.getElementById('proxy-chain-modal').dataset.editingCat;

    const chainEntry = {
        id: editingId || Math.random().toString(36).substr(2, 9),
        name: chainName,
        protocol: 'chain',
        address: hop2Node.address,
        port: hop2Node.port,
        uuid: '',
        security: '',
        rawUri: `chain://localhost/?hop1=${encodeURIComponent(hop1Node.rawUri)}&hop2=${encodeURIComponent(hop2Node.rawUri)}`
    };

    if (editingId && editingCat) {
        const idx = profiles[editingCat]?.nodes?.findIndex(n => n.id === editingId);
        if (idx !== undefined && idx !== -1) {
            profiles[editingCat].nodes[idx] = chainEntry;
            // Regenerate config if this chain is active
            if (activeConfig === `${editingCat}:${editingId}`) {
                applyActiveConfig();
            }
        }
    } else {
        profiles['Manual'].nodes.push(chainEntry);
    }

    saveProfiles();
    closeProxyChainModal();
    renderProfiles();
    showToast(t('toast_chain_saved'), 'success');
}

function openProxyChainEditModal(event, category, id) {
    event.stopPropagation();
    closeAllMenus();
    const node = profiles[category]?.nodes?.find(n => n.id === id);
    if (!node || node.protocol !== 'chain') return;

    _populateChainSelects();

    // Pre-fill the modal with existing chain data
    document.getElementById('chain-name').value = node.name || '';

    // Parse hop URIs out of rawUri to match selects
    try {
        const fakeRawUri = node.rawUri.replace(/^chain:\/\//i, 'https://');
        const u = new URL(fakeRawUri);
        const hop1Uri = u.searchParams.get('hop1') || '';
        const hop2Uri = u.searchParams.get('hop2') || '';

        // Match URIs back to category:id keys
        const opts = _getAllNodeOptions();
        let hop1Key = '', hop2Key = '';
        Object.keys(profiles).forEach(cat => {
            profiles[cat]?.nodes?.forEach(n => {
                if (n.rawUri === hop1Uri) hop1Key = `${cat}:${n.id}`;
                if (n.rawUri === hop2Uri) hop2Key = `${cat}:${n.id}`;
            });
        });
        document.getElementById('chain-hop1').value = hop1Key;
        document.getElementById('chain-hop2').value = hop2Key;
    } catch(e) {}

    // Store editing context on the modal element
    document.getElementById('proxy-chain-modal').dataset.editingId = id;
    document.getElementById('proxy-chain-modal').dataset.editingCat = category;

    applyI18n();
    document.getElementById('proxy-chain-modal').style.display = 'block';
}

async function importFromClipboard() {
    try {
        const text = await navigator.clipboard.readText();
        if (!text || !text.trim()) {
            showToast(t('toast_clipboard_empty'), 'error');
            return;
        }
        const uris = extractUrisFromText(text.trim());
        if (uris.length === 0) {
            showToast(t('toast_no_configs_extracted'), 'error');
            return;
        }
        parseAndAppendNodes('Manual', uris, null);
        showToast(t('toast_clipboard_imported', { count: uris.length }), 'success');
    } catch(e) {
        showToast(t('toast_clipboard_empty'), 'error');
    }
}

function importFromFile() {
    document.getElementById('import-file-input').click();
}

function handleFileImport(event) {
    const file = event.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (e) => {
        const text = e.target.result;
        const uris = extractUrisFromText(text);
        if (uris.length === 0) {
            showToast(t('toast_no_configs_extracted'), 'error');
        } else {
            parseAndAppendNodes('Manual', uris, null);
        }
    };
    reader.readAsText(file);
    // Reset so same file can be imported again
    event.target.value = '';
}

document.addEventListener('click', () => {
    closeAllMenus();
});

// ---------------------------------------------------------------------------
// Background-work gating.
//
// Every recurring task in this UI drives a root shell exec, and some of them
// drive a network probe on the device. None of that should keep running when
// the user is not looking at it. Two levels:
//
//   * visibilitychange - the WebUI is backgrounded (home button, app switch).
//     Timers are suspended; the backend latency probe then stops on its own
//     once its heartbeat goes stale.
//   * pagehide - the WebUI is being torn down. Stop the backend probe
//     explicitly rather than waiting for the heartbeat timeout.
// ---------------------------------------------------------------------------
function _suspendBackgroundWork() {
    stopLatencyPolling();
    stopLogAutoRefresh();
}

function _resumeBackgroundWork() {
    const activeTab = document.querySelector('.tab-content.active')?.id;
    if (activeTab === 'tab-latency') {
        syncLatencyMonitorState();
    } else if (activeTab === 'tab-log') {
        startLogAutoRefresh();
    }
}

document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        _suspendBackgroundWork();
    } else {
        _resumeBackgroundWork();
    }
});

window.addEventListener('pagehide', () => {
    _suspendBackgroundWork();
    // Best-effort: the exec may not complete if we are killed immediately,
    // which is exactly why the backend also self-terminates on heartbeat
    // timeout rather than relying on this.
    if (document.getElementById('latency-monitor-toggle')?.checked) {
        execShell(`sh ${MODDIR}/proxy_control.sh stop_monitor_latency`, () => {});
    }
});
 
// NOTE: the profile/node list is now built with createElement + textContent,
// so no manual escaping is needed there. escapeAttr() was deleted outright —
// it only escaped ' and was being interpolated into "-quoted attributes,
// which was the XSS vector. Do not reintroduce string-built event handlers.
function escapeHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
 
function switchTab(tabId, evt) {
    document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));

    document.getElementById(tabId).classList.add('active');
    // Was reading the deprecated global `event`, which is undefined in strict
    // mode and on non-Chromium engines. Fall back to matching by tab id.
    const trigger = (evt && evt.currentTarget)
        || document.querySelector(`.tab-btn[data-tab="${tabId}"]`);
    if (trigger) trigger.classList.add('active');

    if (tabId === 'tab-log') {
        startLogAutoRefresh();
    } else {
        stopLogAutoRefresh();
    }

    if (tabId === 'tab-latency') {
        syncLatencyMonitorState();
        syncIpHunterState();
    } else {
        stopLatencyPolling();
    }

    if (tabId === 'tab-routing') {
        renderRoutingRules();
    }
}

function toggleSubSettingField(triggerId, subPanelId) {
    const isChecked = document.getElementById(triggerId).checked;
    document.getElementById(subPanelId).style.display = isChecked ? 'block' : 'none';
}

function updateDnsGroupVisibility() {
    const localDnsOn = document.getElementById('set-localdns').checked;
    const fakeDnsLocalOn = document.getElementById('set-fakedns-local').checked;

    const subFields = document.getElementById('dns-sub-fields');
    subFields.style.display = localDnsOn ? '' : 'none';

    // Fake DNS depends on Local DNS
    const fakeDnsRow = document.getElementById('dns-row-fakedns-local');
    if (localDnsOn) {
        fakeDnsRow.classList.remove('setting-row-disabled');
        document.getElementById('set-fakedns-local').disabled = false;
    } else {
        fakeDnsRow.classList.add('setting-row-disabled');
        document.getElementById('set-fakedns-local').disabled = true;
    }

    // VPN DNS is disabled when Fake DNS is on
    const vpnDnsRow = document.getElementById('dns-row-vpndns');
    const vpnDnsInput = document.getElementById('set-vpndns');
    if (fakeDnsLocalOn && localDnsOn) {
        vpnDnsRow.classList.add('setting-row-disabled');
        vpnDnsInput.disabled = true;
    } else {
        vpnDnsRow.classList.remove('setting-row-disabled');
        vpnDnsInput.disabled = false;
    }
}

function bindSettingsToFormView() {
    currentLang = advSettings.lang || "en";
    applyI18n();

    document.getElementById('set-loglevel').value = advSettings.loglevel || "none";
    document.getElementById('set-sniffing').checked = advSettings.sniffing;
    document.getElementById('set-routeonly').checked = advSettings.routeOnly;
    document.getElementById('set-enableipv6').checked = advSettings.enableIPv6;
    document.getElementById('set-preferipv6').checked = advSettings.preferIpv6;
    // `x || true` is always true — the checkbox could never render unchecked
    // even though the value was being persisted correctly.
    document.getElementById('set-dnsviaproxy').checked = advSettings.dnsViaProxy !== false;
    document.getElementById('set-pinned-cert').value = advSettings.pinnedPeerCertSha256 || "";

    // DNS group
    document.getElementById('set-localdns').checked = advSettings.localDns || false;
    document.getElementById('set-fakedns-local').checked = advSettings.fakeDnsLocal || false;
    document.getElementById('set-vpndns').value = advSettings.vpnDns || "1.1.1.1";
    document.getElementById('set-foreign-dns').value = advSettings.foreignDns || "1.1.1.1";
    document.getElementById('set-domestic-dns').value = advSettings.domesticDns || "223.5.5.5";
    updateDnsGroupVisibility();
    
    document.getElementById('set-mux').checked = advSettings.mux;
    document.getElementById('set-mux-connections').value = advSettings.mux_connections;
    toggleSubSettingField('set-mux', 'mux-sub-fields');

    document.getElementById('set-fragment').checked = advSettings.fragment;
    document.getElementById('set-fragment-packets').value = advSettings.fragment_packets || "tlshello";
    document.getElementById('set-fragment-length').value = advSettings.fragment_length || "50-100";
    document.getElementById('set-fragment-interval').value = advSettings.fragment_interval || "10-20";
    toggleSubSettingField('set-fragment', 'fragment-sub-fields');

    document.getElementById('set-mtu').value = advSettings.mtu || 1350;
    document.getElementById('set-networkmode').value = advSettings.networkMode ?? 0;
    document.getElementById('set-allowtether').checked = advSettings.allowTether !== false;

    if (!Array.isArray(advSettings.routingRules)) advSettings.routingRules = [];
    renderRoutingRules();
}

function saveAdvancedSettingsForm(isLangOnly = false) {
    advSettings.loglevel = document.getElementById('set-loglevel').value;
    advSettings.sniffing = document.getElementById('set-sniffing').checked;
    advSettings.routeOnly = document.getElementById('set-routeonly').checked;
    advSettings.enableIPv6 = document.getElementById('set-enableipv6').checked;
    advSettings.preferIpv6 = document.getElementById('set-preferipv6').checked;
    advSettings.dnsViaProxy = document.getElementById('set-dnsviaproxy').checked;
    advSettings.pinnedPeerCertSha256 = document.getElementById('set-pinned-cert').value.trim();

    // DNS group
    advSettings.localDns = document.getElementById('set-localdns').checked;
    advSettings.fakeDnsLocal = document.getElementById('set-fakedns-local').checked;
    advSettings.vpnDns = document.getElementById('set-vpndns').value.trim() || "1.1.1.1";
    advSettings.foreignDns = document.getElementById('set-foreign-dns').value.trim();
    advSettings.domesticDns = document.getElementById('set-domestic-dns').value.trim();
    
    advSettings.mux = document.getElementById('set-mux').checked;
    advSettings.mux_connections = parseInt(document.getElementById('set-mux-connections').value) || 8;

    advSettings.fragment = document.getElementById('set-fragment').checked;
    advSettings.fragment_packets = document.getElementById('set-fragment-packets').value;
    advSettings.fragment_length = document.getElementById('set-fragment-length').value || "50-100";
    advSettings.fragment_interval = document.getElementById('set-fragment-interval').value || "10-20";

    advSettings.mtu = parseInt(document.getElementById('set-mtu').value) || 1350;
    advSettings.networkMode = parseInt(document.getElementById('set-networkmode').value) || 0;
    advSettings.allowTether = document.getElementById('set-allowtether').checked;

    advSettings.lang = currentLang;

    writeFileB64(SETTINGS_FILE, utoa(JSON.stringify(advSettings)), () => {
        if (isLangOnly) return;
        showToast(t('toast_settings_saved'), "success");
        applyActiveConfig();
    });
}

// ===== Routing Settings tab =====

function _summarizeRoutingRule(rule) {
    const parts = [];
    if (rule.domain && rule.domain.trim()) parts.push(`domain: ${rule.domain.trim()}`);
    if (rule.ip && rule.ip.trim()) parts.push(`ip: ${rule.ip.trim()}`);
    if (rule.port && String(rule.port).trim()) parts.push(`port: ${String(rule.port).trim()}`);
    if (rule.protocol && rule.protocol.trim()) parts.push(`protocol: [${rule.protocol.trim()}]`);
    if (rule.network && rule.network.trim()) parts.push(`network: [${rule.network.trim()}]`);
    return parts.join('  ·  ') || t('rule_no_conditions');
}

function renderRoutingRules() {
    const container = document.getElementById('routing-rules-container');
    const emptyState = document.getElementById('routing-empty-state');
    if (!container) return;

    const rules = Array.isArray(advSettings.routingRules) ? advSettings.routingRules : [];
    container.innerHTML = '';

    if (rules.length === 0) {
        if (emptyState) emptyState.style.display = 'block';
        return;
    }
    if (emptyState) emptyState.style.display = 'none';

    rules.forEach((rule, index) => {
        const row = document.createElement('div');
        row.className = `routing-rule-row${rule.enabled === false ? ' rule-disabled' : ''}`;

        const info = document.createElement('div');
        info.className = 'routing-rule-info';
        info.onclick = () => editRoutingRule(index);

        const name = document.createElement('div');
        name.className = 'routing-rule-name';
        name.textContent = (rule.remarks && rule.remarks.trim()) || t('rule_untitled');
        info.appendChild(name);

        const meta = document.createElement('div');
        meta.className = 'routing-rule-meta';
        meta.textContent = _summarizeRoutingRule(rule);
        info.appendChild(meta);

        const badge = document.createElement('span');
        const tag = rule.outboundTag || 'proxy';
        badge.className = `routing-rule-outbound-badge tag-${tag}`;
        badge.textContent = tag;
        info.appendChild(badge);

        row.appendChild(info);

        const actions = document.createElement('div');
        actions.className = 'routing-rule-actions';

        const editBtn = document.createElement('button');
        editBtn.className = 'routing-rule-icon-btn';
        editBtn.title = t('menu_edit');
        editBtn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/></svg>';
        editBtn.onclick = (e) => { e.stopPropagation(); editRoutingRule(index); };
        actions.appendChild(editBtn);

        const delBtn = document.createElement('button');
        delBtn.className = 'routing-rule-icon-btn btn-delete-item';
        delBtn.title = t('btn_delete');
        delBtn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/></svg>';
        delBtn.onclick = (e) => { e.stopPropagation(); deleteRoutingRule(index); };
        actions.appendChild(delBtn);

        const toggle = document.createElement('input');
        toggle.type = 'checkbox';
        toggle.checked = rule.enabled !== false;
        toggle.onchange = () => toggleRoutingRuleEnabled(index);
        actions.appendChild(toggle);

        row.appendChild(actions);
        container.appendChild(row);
    });
}

function openAddRoutingRuleModal() {
    currentEditingRuleIndex = null;
    document.getElementById('routing-rule-modal-title').setAttribute('data-i18n', 'modal_add_rule_title');
    document.getElementById('routing-rule-modal-title').innerHTML = t('modal_add_rule_title');
    document.getElementById('rule-remarks').value = '';
    document.getElementById('rule-locked').checked = false;
    document.getElementById('rule-domain').value = '';
    document.getElementById('rule-ip').value = '';
    document.getElementById('rule-port').value = '';
    document.getElementById('rule-protocol').value = '';
    document.getElementById('rule-network').value = '';
    document.getElementById('rule-outbound').value = 'proxy';
    document.getElementById('routing-rule-modal').style.display = 'block';
}

function editRoutingRule(index) {
    const rule = advSettings.routingRules[index];
    if (!rule) return;
    currentEditingRuleIndex = index;
    document.getElementById('routing-rule-modal-title').setAttribute('data-i18n', 'modal_edit_rule_title');
    document.getElementById('routing-rule-modal-title').innerHTML = t('modal_edit_rule_title');
    document.getElementById('rule-remarks').value = rule.remarks || '';
    document.getElementById('rule-locked').checked = !!rule.locked;
    document.getElementById('rule-domain').value = rule.domain || '';
    document.getElementById('rule-ip').value = rule.ip || '';
    document.getElementById('rule-port').value = rule.port || '';
    document.getElementById('rule-protocol').value = rule.protocol || '';
    document.getElementById('rule-network').value = rule.network || '';
    document.getElementById('rule-outbound').value = rule.outboundTag || 'proxy';
    document.getElementById('routing-rule-modal').style.display = 'block';
}

function closeRoutingRuleModal() {
    document.getElementById('routing-rule-modal').style.display = 'none';
    currentEditingRuleIndex = null;
}

function saveRoutingRule() {
    const domain = document.getElementById('rule-domain').value.trim();
    const ip = document.getElementById('rule-ip').value.trim();
    const port = document.getElementById('rule-port').value.trim();
    const protocol = document.getElementById('rule-protocol').value.trim();

    if (!domain && !ip && !port && !protocol) {
        showToast(t('toast_rule_needs_condition'), 'error');
        return;
    }

    const rule = {
        remarks: document.getElementById('rule-remarks').value.trim(),
        locked: document.getElementById('rule-locked').checked,
        domain,
        ip,
        port,
        protocol,
        network: document.getElementById('rule-network').value,
        outboundTag: document.getElementById('rule-outbound').value,
        enabled: currentEditingRuleIndex !== null ? (advSettings.routingRules[currentEditingRuleIndex].enabled !== false) : true
    };

    if (!Array.isArray(advSettings.routingRules)) advSettings.routingRules = [];

    if (currentEditingRuleIndex === null) {
        advSettings.routingRules.push(rule);
    } else {
        advSettings.routingRules[currentEditingRuleIndex] = rule;
    }

    closeRoutingRuleModal();
    renderRoutingRules();
    persistRoutingRules();
    showToast(t('toast_rule_saved'), 'success');
}

function toggleRoutingRuleEnabled(index) {
    const rule = advSettings.routingRules[index];
    if (!rule) return;
    rule.enabled = rule.enabled === false; // flip: was disabled -> enable, else disable
    renderRoutingRules();
    persistRoutingRules();
}

async function deleteRoutingRule(index) {
    if (!advSettings.routingRules[index]) return;
    const ok = await showConfirm(t('confirm_delete_rule'));
    if (!ok) return;
    advSettings.routingRules.splice(index, 1);
    renderRoutingRules();
    persistRoutingRules();
    showToast(t('toast_rule_deleted'), 'success');
}

// Persists advSettings (including routingRules) to disk and, if a proxy is
// currently active, regenerates its Xray config and restarts if running.
function persistRoutingRules() {
    writeFileB64(SETTINGS_FILE, utoa(JSON.stringify(advSettings)), () => {
        applyActiveConfig();
    });
}

// ===== Import / Export Routing Presets (JSON) =====

function _rtSplitCsv(v) {
    return (typeof v === 'string' ? v : '')
        .split(',')
        .map(s => s.trim())
        .filter(Boolean);
}

function _rtToCsv(v) {
    if (Array.isArray(v)) return v.map(s => String(s).trim()).filter(Boolean).join(',');
    return typeof v === 'string' ? v : '';
}

function openImportRoutingModal() {
    document.getElementById('import-routing-textarea').value = '';
    const urlInput = document.getElementById('import-routing-url');
    if (urlInput) urlInput.value = '';
    document.getElementById('import-routing-modal').style.display = 'block';
}

function closeImportRoutingModal() {
    document.getElementById('import-routing-modal').style.display = 'none';
}

// Fetches a routing preset JSON array from a URL (same curl path used for
// subscriptions, including the local SOCKS5 hop when the proxy is running so
// the fetch itself doesn't leak outside the tunnel) and drops the raw result
// into the textarea for review before the user confirms the import.
async function fetchRoutingPresetFromUrl() {
    const urlInput = document.getElementById('import-routing-url');
    const url = (urlInput?.value || '').trim();
    if (!url) return showToast(t('toast_empty_import'), 'error');

    if (!url.startsWith('http://') && !url.startsWith('https://')) {
        return showToast(t('toast_invalid_sub'), 'error');
    }

    const status = await execShellAsync(`sh ${MODDIR}/proxy_control.sh status`);
    const escapedUrl = url.replace(/'/g, "'\\''");
    const extraArgs = (status === 'running') ? "--socks5-hostname 127.17.1.3:808" : "";

    showLoading('toast_fetch_sub');
    execShell(`${MODDIR}/bin/curl ${extraArgs} -sLk --max-time 15 '${escapedUrl}'`, (res) => {
        hideLoading();
        if (!res || res.trim() === "") {
            return showToast(t('toast_fetch_failed'), 'error');
        }
        if (res.includes("Failed to connect") || res.includes("Could not resolve")) {
            return showToast(t('toast_fetch_reason') + res.split('\n')[0], 'error');
        }

        try {
            const parsed = JSON.parse(res.trim());
            if (!Array.isArray(parsed)) throw new Error('not an array');
            document.getElementById('import-routing-textarea').value = JSON.stringify(parsed, null, 2);
            showToast(t('toast_rules_fetch_ok'), 'success');
        } catch (e) {
            showToast(t('toast_rules_import_invalid'), 'error');
        }
    });
}

function importRoutingRulesFromJson() {
    const raw = document.getElementById('import-routing-textarea').value.trim();
    if (!raw) return;

    let parsed;
    try {
        parsed = JSON.parse(raw);
    } catch (e) {
        showToast(t('toast_rules_import_invalid'), 'error');
        return;
    }

    if (!Array.isArray(parsed)) {
        showToast(t('toast_rules_import_invalid'), 'error');
        return;
    }

    const imported = parsed.map(raw => ({
        remarks: raw.remarks || '',
        locked: !!raw.locked,
        domain: _rtToCsv(raw.domain),
        ip: _rtToCsv(raw.ip),
        port: raw.port !== undefined && raw.port !== null ? String(raw.port) : '',
        protocol: _rtToCsv(raw.protocol),
        network: raw.network || '',
        outboundTag: raw.outboundTag || 'proxy',
        enabled: raw.enabled !== false
    }));

    if (!Array.isArray(advSettings.routingRules)) advSettings.routingRules = [];
    // Presets replace unlocked rules; locked rules are preserved as-is.
    const keptLocked = advSettings.routingRules.filter(r => r.locked);
    advSettings.routingRules = keptLocked.concat(imported);

    closeImportRoutingModal();
    renderRoutingRules();
    persistRoutingRules();
    showToast(t('toast_rules_imported'), 'success');
}

function exportRoutingRulesToClipboard() {
    const rules = Array.isArray(advSettings.routingRules) ? advSettings.routingRules : [];
    const exportArr = rules.map(rule => {
        const obj = {};
        if (rule.remarks) obj.remarks = rule.remarks;
        obj.enabled = rule.enabled !== false;
        obj.locked = !!rule.locked;

        const domain = _rtSplitCsv(rule.domain);
        if (domain.length) obj.domain = domain;
        const ip = _rtSplitCsv(rule.ip);
        if (ip.length) obj.ip = ip;
        if (rule.port && String(rule.port).trim()) obj.port = String(rule.port).trim();
        const protocol = _rtSplitCsv(rule.protocol);
        if (protocol.length) obj.protocol = protocol;
        if (rule.network && String(rule.network).trim()) obj.network = rule.network.trim();

        obj.outboundTag = rule.outboundTag || 'proxy';
        return obj;
    });

    const jsonStr = JSON.stringify(exportArr);
    navigator.clipboard.writeText(jsonStr).then(() => {
        showToast(t('toast_rules_exported'), 'success');
    }).catch(() => {
        showToast(t('toast_rules_export_fail'), 'error');
    });
}

// Promise-based confirm dialog backed by a DOM modal — KSU webui does not
// support window.confirm()/window.alert(). Usage: if (await showConfirm(msg)) { ... }
function showConfirm(message, options = {}) {
    return new Promise(resolve => {
        const overlay = document.getElementById('confirm-modal-overlay');
        const okBtn = document.getElementById('confirm-modal-ok');
        const cancelBtn = document.getElementById('confirm-modal-cancel');
        if (!overlay || !okBtn || !cancelBtn) {
            // Fallback should the modal markup ever be missing.
            resolve(true);
            return;
        }

        document.getElementById('confirm-modal-message').textContent = message;
        okBtn.textContent = options.okText || t('confirm_ok');
        cancelBtn.textContent = options.cancelText || t('confirm_cancel');
        overlay.style.display = 'flex';

        const cleanup = (result) => {
            overlay.style.display = 'none';
            okBtn.onclick = null;
            cancelBtn.onclick = null;
            resolve(result);
        };
        okBtn.onclick = () => cleanup(true);
        cancelBtn.onclick = () => cleanup(false);
    });
}

function showToast(message, type = 'success') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast-item toast-${type}`;
    toast.innerText = message;

    container.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('show');
    }, 10);

    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => {
            toast.remove();
        }, 300);
    }, 3000);
}

// Probe slots. Only NODE_TEST_CONCURRENCY probes run at once, so a small pool
// of listen address/port pairs is enough and — unlike the old
// `index % 250` scheme — cannot collide once a category exceeds 250 nodes.
const NODE_TEST_CONCURRENCY = 10;
const NODE_TEST_SLOTS = 16;
const _nodeTestSlotBusy = new Array(NODE_TEST_SLOTS).fill(false);

function _acquireTestSlot() {
    for (let i = 0; i < NODE_TEST_SLOTS; i++) {
        if (!_nodeTestSlotBusy[i]) { _nodeTestSlotBusy[i] = true; return i; }
    }
    return -1;
}

function _releaseTestSlot(slot) {
    if (slot >= 0 && slot < NODE_TEST_SLOTS) _nodeTestSlotBusy[slot] = false;
}

function _buildXrayTestInbound(node, slot) {
    const testIp = `127.17.1.${10 + slot}`;
    const testPort = 21000 + slot;
    // Probe configs contain the node's full credentials. They used to be
    // written to /dev at the default umask (0644, world-traversable dir);
    // they now live in the module's private tmpfs, 0600.
    const tmpFile = `${STUB_DIR}/run/nodetest/${slot}.json`;
    const rawConfigStr = _resolveXrayConfig(node.rawUri);
    const xrayConfigObj = JSON.parse(rawConfigStr);
    if (xrayConfigObj.error) throw new Error(xrayConfigObj.error);
    xrayConfigObj.inbounds = [{
        tag: "socks-test-in",
        port: testPort,
        listen: testIp,
        protocol: "socks",
        settings: { auth: "noauth", udp: true }
    }];
    return { testIp, testPort, tmpFile, configB64: utoa(JSON.stringify(xrayConfigObj)) };
}

// Builds the probe script. The spawned xray is bounded three ways: an explicit
// kill, a `trap` covering the abnormal-exit paths, and an outer `timeout` in
// case the shell itself is torn down. Previously an interrupted probe left a
// root xray running with the node's credentials in memory.
function _buildProbeScript(tmpFile, configB64, innerCmd) {
    return `
        umask 077
        mkdir -p ${shQuote(STUB_DIR + '/run/nodetest')} 2>/dev/null
        chmod 700 ${shQuote(STUB_DIR + '/run/nodetest')} 2>/dev/null
        XPID=""
        cleanup() { [ -n "$XPID" ] && kill -9 "$XPID" 2>/dev/null; rm -f ${shQuote(tmpFile)}; }
        trap cleanup EXIT HUP INT TERM
        printf '%s' ${shQuote(configB64)} | base64 -d > ${shQuote(tmpFile)}
        ${MODDIR}/bin/xray run -c ${shQuote(tmpFile)} >/dev/null 2>&1 &
        XPID=$!
        sleep 1
        ${innerCmd}
        cleanup
        trap - EXIT
        printf '%s' "$RES"
    `;
}

function _setPingSpan(pingSpan, text, colorVar) {
    if (!pingSpan) return;
    pingSpan.innerText = text;
    pingSpan.style.color = colorVar;
}

// Runs one probe against a node in an isolated xray instance.
// `mode` is 'http' (latency in ms) or 'ip' (egress IP address).
async function _execNodeProbe(node, pingSpan, mode) {
    _setPingSpan(pingSpan, "...", "var(--text-muted)");

    const slot = _acquireTestSlot();
    if (slot === -1) {
        _setPingSpan(pingSpan, "?", "var(--red, #ff1744)");
        return;
    }

    try {
        let built;
        try {
            built = _buildXrayTestInbound(node, slot);
        } catch (e) {
            _setPingSpan(pingSpan, "?", "var(--red, #ff1744)");
            return;
        }
        const { testIp, testPort, tmpFile, configB64 } = built;

        const innerCmd = mode === 'http'
            ? `RES=$(${MODDIR}/bin/curl --socks5-hostname ${testIp}:${testPort} -s ` +
              `-w "%{time_starttransfer}" --max-time 3 -o /dev/null ` +
              `http://gstatic.com/generate_204 2>/dev/null)`
            : `RES=$(${MODDIR}/bin/curl --socks5-hostname ${testIp}:${testPort} -s ` +
              `--max-time 3 https://icanhazip.com 2>/dev/null)`;

        // Hard ceiling on the whole probe so a wedged xray or curl cannot
        // hold the slot — or a root process — indefinitely.
        const output = await execShellAsync(
            `timeout 15 sh -c ${shQuote(_buildProbeScript(tmpFile, configB64, innerCmd))}`
        );

        if (mode === 'http') {
            const val = parseFloat(output.trim());
            if (!isNaN(val) && val > 0) {
                _setPingSpan(pingSpan, `${Math.round(val * 1000)}ms`, "var(--green, #00e676)");
            } else {
                _setPingSpan(pingSpan, "?", "var(--red, #ff1744)");
            }
        } else {
            const ip = output.trim();
            if (ip) {
                _setPingSpan(pingSpan, ip, "var(--green, #00e676)");
            } else {
                _setPingSpan(pingSpan, "?", "var(--red, #ff1744)");
            }
        }
    } finally {
        _releaseTestSlot(slot);
    }
}


async function _pingCategory(category, mode) {
    const catData = profiles[category];
    if (!catData || !catData.nodes || catData.nodes.length === 0) return;

    await parallelWithLimit(catData.nodes, NODE_TEST_CONCURRENCY, async (node) => {
        const pingSpan = document.getElementById(`ping-${category}-${node.id}`);
        await _execNodeProbe(node, pingSpan, mode);
    });
}

const pingCategoryCheckHttp = (category) => _pingCategory(category, 'http');
const pingCategoryCheckIp = (category) => _pingCategory(category, 'ip');

// --- Single-node (per-config) HTTP / IP checks ---

async function _checkSingleNode(category, nodeId, mode) {
    const node = profiles[category]?.nodes?.find(n => n.id === nodeId);
    if (!node) return;
    const pingSpan = document.getElementById(`ping-${category}-${node.id}`);
    await _execNodeProbe(node, pingSpan, mode);
}

const checkSingleNodeHttp = (category, nodeId) => _checkSingleNode(category, nodeId, 'http');
const checkSingleNodeIp = (category, nodeId) => _checkSingleNode(category, nodeId, 'ip');

async function checkHttpWithClose(event, category) {
    showLoading(`${t("toast_check_http")}${category}...`);
    const btn = event.currentTarget;
    closeAllMenus();
    btn.disabled = true;
    await new Promise(r => setTimeout(r, 150));
    try {
        await pingCategoryCheckHttp(category);
    } finally {
        btn.disabled = false;
        hideLoading();
    }
}

async function checkIpWithClose(event, category) {
    showLoading(`${t("toast_check_ip")}${category}...`);
    const btn = event.currentTarget;
    closeAllMenus();
    btn.disabled = true;
    await new Promise(r => setTimeout(r, 150));
    try {
        await pingCategoryCheckIp(category);
    } finally {
        btn.disabled = false;
        hideLoading();
    }
}

async function checkSingleHttpWithClose(event, category, nodeId) {
    const catData = profiles[category];
    const node = catData?.nodes?.find(n => n.id === nodeId);
    showLoading(`${t("toast_check_http")}${node ? node.name : ''}...`);
    const btn = event.currentTarget;
    closeAllMenus();
    btn.disabled = true;
    await new Promise(r => setTimeout(r, 150));
    try {
        await checkSingleNodeHttp(category, nodeId);
    } finally {
        btn.disabled = false;
        hideLoading();
    }
}

async function checkSingleIpWithClose(event, category, nodeId) {
    const catData = profiles[category];
    const node = catData?.nodes?.find(n => n.id === nodeId);
    showLoading(`${t("toast_check_ip")}${node ? node.name : ''}...`);
    const btn = event.currentTarget;
    closeAllMenus();
    btn.disabled = true;
    await new Promise(r => setTimeout(r, 150));
    try {
        await checkSingleNodeIp(category, nodeId);
    } finally {
        btn.disabled = false;
        hideLoading();
    }
}

async function parallelWithLimit(items, limit, fn) {
    const promises = [];
    const executing = new Set();
    
    for (const item of items) {
        const p = Promise.resolve().then(() => fn(item));
        promises.push(p);
        executing.add(p);
        
        const clean = () => executing.delete(p);
        p.then(clean, clean);
        
        if (executing.size >= limit) {
            await Promise.race(executing);
        }
    }
    return Promise.all(promises);
}

function _logClassifyLine(text) {
    const t = text.toLowerCase();
    if (/\berror\b/.test(t))   return 'error';
    if (/\bwarning\b/.test(t)) return 'warning';
    if (/\bdebug\b/.test(t))   return 'debug';
    // Xray access log: looks like "2024/01/01 00:00:00 accepted tcp:..."
    if (/accepted|rejected/.test(t)) return 'access';
    return 'info';
}

function _logRenderLines() {
    const output = document.getElementById('log-output');
    const emptyState = document.getElementById('log-empty-state');
    if (!output) return;

    if (_logAllLines.length === 0) {
        emptyState && (emptyState.style.display = '');
        // clear existing line nodes
        output.querySelectorAll('.log-line').forEach(el => el.remove());
        document.getElementById('log-line-count').textContent = '— lines';
        return;
    }

    emptyState && (emptyState.style.display = 'none');

    // Diff: only append new lines (avoid full re-render flicker)
    const existingCount = output.querySelectorAll('.log-line').length;
    const newLines = _logAllLines.slice(existingCount);

    newLines.forEach((text, i) => {
        const lineNum = existingCount + i + 1;
        const level = _logClassifyLine(text);
        const div = document.createElement('div');
        div.className = `log-line log-line--${level}${level === 'access' ? ' log-line--access' : ''}`;
        div.dataset.level = level;

        // apply current filter
        if (_logCurrentFilter !== 'all' && _logCurrentFilter !== level) {
            div.classList.add('log-hidden');
        }

        const numSpan = document.createElement('span');
        numSpan.className = 'log-line-num';
        numSpan.textContent = lineNum;

        const txtSpan = document.createElement('span');
        txtSpan.className = 'log-line-text';
        txtSpan.textContent = text;

        div.appendChild(numSpan);
        div.appendChild(txtSpan);
        output.appendChild(div);
    });

    // Update line count
    const visibleCount = output.querySelectorAll('.log-line:not(.log-hidden)').length;
    document.getElementById('log-line-count').textContent =
        `${_logAllLines.length} lines${_logCurrentFilter !== 'all' ? ` (${visibleCount} shown)` : ''}`;

    // Auto-scroll to bottom if tail mode is on
    if (_logTailEnabled) {
        output.scrollTop = output.scrollHeight;
    }
}

function refreshLog() {
    const tailLines = document.getElementById('log-tail-lines')?.value || 200;
    const btn = document.getElementById('btn-log-refresh');
    btn && btn.querySelector('svg') && btn.classList.add('spinning');

    const dot = document.getElementById('log-status-dot');

    execShell(
        `tail -n ${tailLines} '${DATADIR}/xray.log' 2>/dev/null || echo ''`,
        (output) => {
            btn && btn.classList.remove('spinning');

            if (!output || !output.trim()) {
                _logAllLines = [];
                _logRenderLines();
                dot && dot.classList.remove('live');
                return;
            }

            const newLines = output.split('\n').filter(l => l.length > 0);

            // If line count changed, do a full replace (e.g. log rotated or tail shrunk)
            if (newLines.length < _logAllLines.length) {
                // Log was cleared/rotated — full re-render
                document.getElementById('log-output')?.querySelectorAll('.log-line')
                    .forEach(el => el.remove());
                _logAllLines = [];
            }

            _logAllLines = newLines;
            _logRenderLines();
            dot && dot.classList.add('live');
        }
    );
}

function startLogAutoRefresh() {
    stopLogAutoRefresh();
    refreshLog();
    const isEnabled = document.getElementById('log-autorefresh-toggle')?.checked ?? false;
    if (!isEnabled) return;
    const interval = parseInt(document.getElementById('log-autorefresh-interval')?.value || 5000);
    _logAutoRefreshTimer = setInterval(refreshLog, interval);
}

function stopLogAutoRefresh() {
    if (_logAutoRefreshTimer) {
        clearInterval(_logAutoRefreshTimer);
        _logAutoRefreshTimer = null;
    }
    const dot = document.getElementById('log-status-dot');
    dot && dot.classList.remove('live');
}

function toggleLogAutoRefresh() {
    const isEnabled = document.getElementById('log-autorefresh-toggle')?.checked;
    if (isEnabled) {
        startLogAutoRefresh();
    } else {
        stopLogAutoRefresh();
    }
}

function updateLogRefreshInterval() {
    const isEnabled = document.getElementById('log-autorefresh-toggle')?.checked;
    if (isEnabled) startLogAutoRefresh();
}

function toggleLogTail() {
    _logTailEnabled = !_logTailEnabled;
    const btn = document.getElementById('btn-log-tail');
    if (btn) {
        btn.dataset.active = _logTailEnabled ? 'true' : 'false';
        btn.title = _logTailEnabled ? 'Auto-scroll ON' : 'Auto-scroll OFF';
    }
    if (_logTailEnabled) {
        const output = document.getElementById('log-output');
        output && (output.scrollTop = output.scrollHeight);
    }
}

function setLogFilter(level) {
    _logCurrentFilter = level;

    // Update chip active state
    document.querySelectorAll('.log-filter-chip').forEach(chip => {
        chip.classList.toggle('active', chip.dataset.level === level);
    });

    // Show/hide lines
    document.querySelectorAll('#log-output .log-line').forEach(line => {
        const lineLevel = line.dataset.level;
        if (level === 'all' || lineLevel === level) {
            line.classList.remove('log-hidden');
        } else {
            line.classList.add('log-hidden');
        }
    });

    // Update count
    const total = _logAllLines.length;
    const visible = document.querySelectorAll('#log-output .log-line:not(.log-hidden)').length;
    const countEl = document.getElementById('log-line-count');
    if (countEl) {
        countEl.textContent = `${total} lines${level !== 'all' ? ` (${visible} shown)` : ''}`;
    }
}

function clearLogView() {
    _logAllLines = [];
    document.getElementById('log-output')?.querySelectorAll('.log-line')
        .forEach(el => el.remove());
    const emptyState = document.getElementById('log-empty-state');
    emptyState && (emptyState.style.display = '');
    document.getElementById('log-line-count').textContent = '— lines';
    showToast(t('toast_log_cleared'), 'info');
}

function copyLogToClipboard() {
    const text = _logAllLines.join('\n');
    if (!text) return;
    navigator.clipboard.writeText(text).then(() => {
        showToast(t('toast_log_copied'), 'success');
    }).catch(() => {
        showToast(t('toast_log_copy_fail'), 'error');
    });
}

/* ===== Network Latency Monitor ===== */

// Reflects real backend state (whether service.sh's monitor loop is alive)
// by checking for the existence of TIME_RES_FILE, rather than trusting local UI state,
// so the toggle stays correct across tab switches / page reloads.
function syncLatencyMonitorState() {
    execShell(`[ -f '${TIME_RES_FILE}' ] && echo 1 || echo 0`, (output) => {
        const isRunning = output.trim() === '1';
        const toggle = document.getElementById('latency-monitor-toggle');
        if (toggle) toggle.checked = isRunning;

        renderLatencyChart();

        if (isRunning) {
            startLatencyPolling();
        } else {
            stopLatencyPolling();
            const dot = document.getElementById('latency-status-dot');
            dot && dot.classList.remove('live');
            renderNetworkInterfaceInfo('');
        }
    });
}

function toggleLatencyMonitor() {
    const enabled = document.getElementById('latency-monitor-toggle')?.checked;

    if (enabled) {
        execShell(`sh ${MODDIR}/proxy_control.sh start_monitor_latency`, () => {
            _latencySamples = [];
            renderLatencyChart();
            updateLatencyStats();
            startLatencyPolling();
            showToast(t('toast_latency_started'), 'success');
        });
    } else {
        stopLatencyPolling();
        const dot = document.getElementById('latency-status-dot');
        dot && dot.classList.remove('live');
        renderNetworkInterfaceInfo('');
        execShell(`sh ${MODDIR}/proxy_control.sh stop_monitor_latency`, () => {
            showToast(t('toast_latency_stopped'), 'info');
        });
    }
}

function startLatencyPolling() {
    stopLatencyPolling();
    pollLatency();
    _latencyPollTimer = setInterval(pollLatency, LATENCY_POLL_MS);
}

function stopLatencyPolling() {
    if (_latencyPollTimer) {
        clearInterval(_latencyPollTimer);
        _latencyPollTimer = null;
    }
}

const _LATENCY_ADDR_SPLIT_MARKER = '___ADDR_INFO_SPLIT___';

function pollLatency() {
    // One exec does all three jobs: refresh the liveness heartbeat, read the
    // latest probe result, and read the interface address dump. Sending the
    // heartbeat separately would have doubled the wakeup cost of polling.
    execShell(
        `[ -f ${shQuote(LATENCY_HB_FILE)} ] && date +%s > ${shQuote(LATENCY_HB_FILE)}; ` +
        `cat ${shQuote(TIME_RES_FILE)} 2>/dev/null; ` +
        `echo '${_LATENCY_ADDR_SPLIT_MARKER}'; ` +
        `cat ${shQuote(ADDR_INFO_FILE)} 2>/dev/null`,
        (output) => {
            const dot = document.getElementById('latency-status-dot');
            const parts = (output || '').split(_LATENCY_ADDR_SPLIT_MARKER);
            const raw = (parts[0] || '').trim();
            const addrRaw = parts[1] || '';
            const num = parseFloat(raw);
            // service.sh writes seconds (curl's %{time_starttransfer}); empty/0/NaN means
            // the probe timed out or never completed within curl's --max-time window.
            const ms = (raw !== '' && !isNaN(num) && num > 0) ? Math.round(num * 1000) : null;

            _latencySamples.push({ ms });
            if (_latencySamples.length > LATENCY_MAX_SAMPLES) {
                _latencySamples.shift();
            }

            dot && dot.classList.add('live');
            updateLatencyStats();
            renderLatencyChart();
            renderNetworkInterfaceInfo(addrRaw);
        }
    );
}

// Parses the `ip addr show <iface>` style dump written to ADDR_INFO_FILE.
// Picks the interface name from the "N: name: <FLAGS> ..." header line,
// the first global-scope IPv4 (`inet`), and the first global-scope IPv6
// (`inet6 ... scope global`, skipping link-local `scope link` addresses).
function parseAddrInfo(raw) {
    const lines = (raw || '').split('\n');
    let iface = null;
    let ipv4 = null;
    let ipv6 = null;

    for (const line of lines) {
        const headerMatch = line.match(/^\d+:\s+([^:@]+)[:@]/);
        if (headerMatch && !iface) {
            iface = headerMatch[1].trim();
            continue;
        }
        const t = line.trim();
        if (!ipv4) {
            const m4 = t.match(/^inet\s+([\d.]+)\/\d+/);
            if (m4) ipv4 = m4[1];
        }
        if (!ipv6) {
            const m6 = t.match(/^inet6\s+([0-9a-fA-F:]+)\/\d+\s+scope\s+global/);
            if (m6) ipv6 = m6[1];
        }
    }

    return { iface, ipv4, ipv6 };
}

function renderNetworkInterfaceInfo(raw) {
    const ifaceEl = document.getElementById('latency-iface-value');
    const ipv4El = document.getElementById('latency-ipv4-value');
    const ipv6El = document.getElementById('latency-ipv6-value');
    if (!ifaceEl && !ipv4El && !ipv6El) return;

    const { iface, ipv4, ipv6 } = parseAddrInfo(raw);
    const none = t('latency_net_none');

    ifaceEl && (ifaceEl.textContent = iface || none);
    ipv4El && (ipv4El.textContent = ipv4 || none);
    ipv6El && (ipv6El.textContent = ipv6 || none);
}

/* ===== Mobile IP Hunter ===== */

// Keeps only well-formed IPv4 prefix octets (1-4 dot-separated groups),
// dropping anything else typed into the field, then rejoins with ';'
// to match the format the hunter script expects (e.g. "10.120;10.121").
function sanitizeIpHunterPrefixes(raw) {
    return (raw || '')
        .split(/[;,\s]+/)
        .map(p => p.trim())
        .filter(p => /^\d{1,3}(\.\d{1,3}){0,3}$/.test(p))
        .join(';');
}

// Reflects real backend state by checking for the existence of IP_HUNT_FILE,
// same approach as syncLatencyMonitorState, so the toggle and field stay
// correct across tab switches / page reloads.
function syncIpHunterState() {
    execShell(
        `if [ -f '${IP_HUNT_FILE}' ]; then echo 1; cat '${IP_HUNT_FILE}'; else echo 0; fi`,
        (output) => {
            const toggle = document.getElementById('ip-hunter-toggle');
            const input = document.getElementById('ip-hunter-prefixes');
            const lines = (output || '').split('\n');
            const enabled = lines[0].trim() === '1';
            const content = lines.slice(1).join('\n').trim();

            if (toggle) toggle.checked = enabled;
            if (input) {
                input.value = content;
            }
        }
    );
}

function toggleIpHunter() {
    const toggle = document.getElementById('ip-hunter-toggle');
    const enabled = !!toggle?.checked;

    if (enabled) {
        saveIpHunterPrefixes();
        toggleService('reset_mobile_network');
    } else {
        execShell(`rm -f '${IP_HUNT_FILE}'`, () => {
            showToast(t('toast_ip_hunter_disabled'), 'info');
        });
    }
}

function onIpHunterInputChange() {
    const toggle = document.getElementById('ip-hunter-toggle');
    if (!toggle?.checked) return;

    if (_ipHunterSaveTimer) clearTimeout(_ipHunterSaveTimer);
    _ipHunterSaveTimer = setTimeout(saveIpHunterPrefixes, 600);
}

function saveIpHunterPrefixes() {
    const input = document.getElementById('ip-hunter-prefixes');
    const sanitized = sanitizeIpHunterPrefixes(input?.value || '');
    if (input) input.value = sanitized;

    if (!sanitized) {
        showToast(t('toast_ip_hunter_invalid'), 'error');
        return;
    }

    execShell(`mkdir -p ${shQuote(DATADIR)} && chmod 700 ${shQuote(DATADIR)}`, () => {
        writeFileB64(IP_HUNT_FILE, sanitized, () => {
            showToast(t('toast_ip_hunter_saved'), 'success');
        });
    });
}

function updateLatencyStats() {
    const last = _latencySamples[_latencySamples.length - 1];
    const valueEl = document.getElementById('latency-current-value');
    if (valueEl) {
        const hasLast = last && last.ms !== null;
        valueEl.textContent = hasLast ? `${last.ms} ms` : (last ? t('latency_timeout_label') : '— ms');
        valueEl.classList.toggle('latency-current-value--timeout', !!last && !hasLast);
    }

    const valid = _latencySamples.filter(s => s.ms !== null).map(s => s.ms);
    const lossPct = _latencySamples.length
        ? Math.round(((_latencySamples.length - valid.length) / _latencySamples.length) * 100)
        : 0;

    const avgEl = document.getElementById('latency-stat-avg');
    const minEl = document.getElementById('latency-stat-min');
    const maxEl = document.getElementById('latency-stat-max');
    const lossEl = document.getElementById('latency-stat-loss');

    avgEl && (avgEl.textContent = valid.length ? `${Math.round(valid.reduce((a, b) => a + b, 0) / valid.length)} ms` : '—');
    minEl && (minEl.textContent = valid.length ? `${Math.min(...valid)} ms` : '—');
    maxEl && (maxEl.textContent = valid.length ? `${Math.max(...valid)} ms` : '—');
    lossEl && (lossEl.textContent = _latencySamples.length ? `${lossPct}%` : '—');
}

function renderLatencyChart() {
    const svg = document.getElementById('latency-chart-svg');
    const emptyState = document.getElementById('latency-empty-state');
    if (!svg) return;

    if (_latencySamples.length === 0) {
        svg.innerHTML = '';
        emptyState && (emptyState.style.display = '');
        return;
    }
    emptyState && (emptyState.style.display = 'none');

    const wrap = document.getElementById('latency-chart-wrap');
    const width = Math.max(wrap ? wrap.clientWidth : 600, 100);
    const height = 200;
    const pad = 10;
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);

    const n = LATENCY_MAX_SAMPLES;
    const slotW = (width - pad * 2) / Math.max(n - 1, 1);
    const validValues = _latencySamples.filter(s => s.ms !== null).map(s => s.ms);
    const dataMax = validValues.length ? Math.max(...validValues) : 300;
    const chartMax = Math.max(3000, Math.ceil((dataMax * 1.25) / 100) * 100);
    const top = pad;
    const bottom = height - pad;
    const usableH = bottom - top;
    const yFor = (ms) => bottom - (Math.min(ms, chartMax) / chartMax) * usableH;

    const parts = [];

    // Gridlines + scale labels
    [0, 0.5, 1].forEach((f) => {
        const y = bottom - f * usableH;
        parts.push(`<line x1="${pad}" y1="${y}" x2="${width - pad}" y2="${y}" stroke="var(--md-outline-variant)" stroke-width="1" stroke-dasharray="3,4"/>`);
        parts.push(`<text x="${pad + 4}" y="${Math.max(y - 4, 10)}" font-size="10" fill="var(--md-on-surface-variant)" font-family="var(--md-font-mono)">${Math.round(f * chartMax)}ms</text>`);
    });

    // Green line segments for successful samples, red bands for timeouts
    let linePoints = [];
    const flushLine = () => {
        if (linePoints.length > 1) {
            parts.push(`<polyline points="${linePoints.join(' ')}" fill="none" stroke="var(--md-success)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`);
        } else if (linePoints.length === 1) {
            const [x, y] = linePoints[0].split(',');
            parts.push(`<circle cx="${x}" cy="${y}" r="2.5" fill="var(--md-success)"/>`);
        }
        linePoints = [];
    };

    _latencySamples.forEach((s, i) => {
        const x = pad + i * slotW;
        if (s.ms === null) {
            flushLine();
            const bandW = Math.max(slotW, 3);
            parts.push(`<rect x="${x - bandW / 2}" y="${top}" width="${bandW}" height="${usableH}" fill="var(--md-error)" opacity="0.28"/>`);
        } else {
            linePoints.push(`${x},${yFor(s.ms)}`);
        }
    });
    flushLine();

    // Highlight the most recent successful sample
    for (let i = _latencySamples.length - 1; i >= 0; i--) {
        if (_latencySamples[i].ms !== null) {
            const x = pad + i * slotW;
            const y = yFor(_latencySamples[i].ms);
            parts.push(`<circle cx="${x}" cy="${y}" r="3.5" fill="var(--md-success)" stroke="var(--md-surface)" stroke-width="1.5"/>`);
            break;
        }
    }

    svg.innerHTML = parts.join('');
}