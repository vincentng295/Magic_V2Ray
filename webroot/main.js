const MODDIR = "/data/adb/modules/magic_v2ray";
const DATADIR = "/data/adb/magic_v2ray";
const PROFILES_FILE = `${DATADIR}/profiles.base64`;
const SETTINGS_FILE = `${DATADIR}/settings.base64`;
const ACTIVE_FILE = `${DATADIR}/active_config.txt`;
const CONFIG_JSON = `${DATADIR}/config.json`;
const MAGISK_BRIDGE_URL = "http://127.17.1.3/cgi-bin/exec";
const urlParams = new URLSearchParams(window.location.search);
const MAGISK_TOKEN = urlParams.get('token')
 
let profiles = {};
let activeConfig = null;
let advSettings = {
    loglevel: "debug",
    sniffing: true,
    routeOnly: false,
    preferIpv6: false,
    mux: false,
    mux_connections: 8,
    fragment: false,
    fragment_packets: "tlshello",
    fragment_length: "50-100",
    fragment_interval: "10-20",
    mtu: 1350,
    pinnedPeerCertSha256: "",
    dnsViaProxy: true,
    fakeDns: false
};
let currentLang = 'en';
let currentEditingCategory = null;
let currentEditingNodeId = null;
let currentEditingProtocol = null;

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
 
function execShell(command, callback) {
    if (typeof ksu === "object" && typeof ksu.exec === "function") {
        const cbId = `cb_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`;
        window[cbId] = (errno, stdout, stderr) => {
            delete window[cbId];
            if (callback) callback(errno === 0 ? stdout.trim() : "");
        };
        ksu.exec(command, "{}", cbId);
    } else {
        console.error("[execShell] window.ksu not available");
        const base64Command = utoa(command);
        fetch(`${MAGISK_BRIDGE_URL}?token=${encodeURIComponent(MAGISK_TOKEN)}`, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded"
            },
            body: "cmd=" + encodeURIComponent(base64Command)
        })
        .then(response => {
            if (!response.ok) throw new Error("CGI Server returned error status");
            return response.text();
        })
        .then(stdout => {
            if (callback) callback(stdout.trim());
        })
        .catch(err => {
            console.error("[execShell] CGI bridge execution failed:", err);
            if (callback) callback("");
        });
    }
}

function execShellAsync(cmd) {
    return new Promise((resolve) => {
        execShell(cmd, (output) => {
            resolve(output ? output.trim() : "");
        });
    });
}
 
function saveProfiles() {
    const json = JSON.stringify(profiles);
    const base64_encoded = utoa(json);
    execShell(`printf '%s' '${base64_encoded}' > '${PROFILES_FILE}'`, () => {});
}
 
function saveActiveConfig() {
    if (activeConfig) {
        const escaped = activeConfig.replace(/'/g, "'\\''");
        execShell(`printf '%s' '${escaped}' > '${ACTIVE_FILE}'`, () => {});
    } else {
        execShell(`rm -f '${ACTIVE_FILE}'`, () => {});
    }
}
 
function loadState(callback) {
    execShell(`cat '${PROFILES_FILE}' 2>/dev/null || echo '{}'`, (profilesRaw) => {
        try {
            const parsed = JSON.parse(decodeURIComponent(escape(atob(profilesRaw))));
            // MIGRATION PATCH
            profiles = {};
            Object.keys(parsed).forEach(cat => {
                if (Array.isArray(parsed[cat])) {
                    profiles[cat] = { url: cat === "Manual" ? null : cat, nodes: parsed[cat] };
                } else {
                    profiles[cat] = parsed[cat];
                }
            });
        } catch (e) {
            console.warn("[loadState] profiles.json parse error, reset to {}");
            profiles = {};
        }

        execShell(`cat '${ACTIVE_FILE}' 2>/dev/null || echo ''`, (activeRaw) => {
            activeConfig = activeRaw.trim() || null;
            if (callback) callback();
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
    execShell(`sh ${MODDIR}/proxy_control.sh status`, (status) => {
        const badge = document.getElementById('service-status');
        const s = status || 'stopped';
        badge.innerText = t('status_prefix') + s.toUpperCase();
        badge.className = `status-badge ${s === 'running' ? 'active' : 'inactive'}`;
    });
}
 
async function toggleService(action) {
    if (action === 'start' || action === 'restart') {
        if (activeConfig) {
            const checkCmd = "/system/bin/ip route get 8.8.8.8 mark 255";
            const currentRoute = await execShellAsync(checkCmd);
            if (!currentRoute || currentRoute.toLowerCase().includes("unreachable") || currentRoute.toLowerCase().includes("network is down")) {
                showToast(t("toast_network_unreachable"), "error");
                return;
            }

            const [category, id] = activeConfig.split(':');
            const node = profiles[category]?.nodes?.find(n => n.id === id); 
            if (node) {
                const xrayConfig = convert_uri_to_xray_json(node.rawUri, advSettings);
                execShell(`echo '${xrayConfig}' > '${CONFIG_JSON}'`, () => {
                    execShell(`sh ${MODDIR}/proxy_control.sh restart`, () => {
                        updateStatusDisplay();      
                    });
                });
            }
        } else {
            showToast(t('toast_no_active_config'), "error");
        }
        return;
    }
    execShell(`sh ${MODDIR}/proxy_control.sh ${action}`, () => {
        const badge = document.getElementById('service-status');
        badge.innerText = t('status_loading');
        badge.className = 'status-badge active';
        setTimeout(updateStatusDisplay, 1200);
    });
}

const extractUrisFromText = (text) => {
    let uris = [];
    const rawLines = text.split(/\r?\n/);

    rawLines.forEach(line => {
        let trimmedLine = line.trim();
        if (!trimmedLine) return;

        if (!trimmedLine.includes('://') && /^[A-Za-z0-9+/=]+$/.test(trimmedLine)) {
            try {
                const decoded = decodeBase64(trimmedLine);
                if (decoded) {
                    uris = uris.concat(extractUrisFromText(decoded));
                }
            } catch (e) {
                console.warn("Line looks like Base64 but failed to decode safely:", trimmedLine);
            }
        } else if (trimmedLine.includes('://')) {
            uris.push(trimmedLine);
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
    const escapedUrl = url.replace(/'/g, "'\\''");
    const extraArgs = (status === 'running')? "--socks5 127.0.0.1:10808" : "";
    execShell(`curl ${extraArgs} -sLk --max-time 15 '${escapedUrl}'`, (res) => {
        if (!res || res.trim() === "") {
            return showToast(t('toast_fetch_failed'), "error");
        }
        if (res.includes("Failed to connect") || res.includes("Could not resolve")) {
            return showToast(t('toast_fetch_reason') + res.split('\n')[0], "error");
        }

        let parsedContent = res.trim();
        const cleanRes = parsedContent.replace(/[\s\r\n]+/g, '');
        if (/^[A-Za-z0-9+/=]+$/.test(cleanRes)) {
            try {
                const decodedAll = decodeBase64(cleanRes);
                if (decodedAll && decodedAll.includes('://')) {
                    parsedContent = decodedAll;
                }
            } catch (e) {
                console.log("Not a pure single Base64 block, parsing line by line...");
            }
        }

        const xrayConfigs = extractUrisFromText(parsedContent);
        parseAndAppendNodes(category, xrayConfigs, url, isReload);
    });
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
    xrayConfigs.forEach(line => {
        const parsedNode = parseProxyUri(line);
        if (parsedNode) {
            const duplicate = profiles[category].nodes.some(
                n => n.rawUri === parsedNode.rawUri
            );
            if (!duplicate) {
                profiles[category].nodes.push(parsedNode);
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
        if (!['vless', 'vmess', 'trojan'].includes(protocol)) return null;

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
 
function selectNode(category, id) {
    const node = profiles[category]?.nodes?.find(n => n.id === id);
    if (!node) return;
 
    activeConfig = `${category}:${id}`;
    saveActiveConfig();
    xrayConfig = convert_uri_to_xray_json(node.rawUri, advSettings);
 
    // dump xray config to file and restart service if running
    execShell(`sh ${MODDIR}/proxy_control.sh status`, (status) => {
        renderProfiles();
        if (status === 'running') toggleService('restart');
    });
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
 
function renderProfiles() {
    const container = document.getElementById('profiles-container');
    container.innerHTML = "";
    const categories = Object.keys(profiles).filter(c => profiles[c]?.nodes?.length > 0);
    if (categories.length === 0) {
        container.innerHTML = `<p style="color: var(--text-muted); font-size:14px; text-align:center; padding: 24px 0;">
            ${t('no_configs')}
        </p>`;
        return;
    }
    for (const category of categories) {
        const group = document.createElement('div');
        group.className = "category-group";
        const hasUrl = !!profiles[category].url;
        group.innerHTML = `
            <div class="category-header" style="position: relative; display: flex; justify-content: space-between; align-items: center;">
                <strong>${escapeHtml(category)} (${profiles[category].nodes.length})</strong>
                <div class="category-menu-container">
                    <button class="btn-menu-trigger" onclick="toggleCategoryMenu(event, this)">⋮</button>
                    <div class="category-dropdown-menu">
                        ${hasUrl ? `<button onclick="reloadCategory('${escapeAttr(category)}'); closeAllMenus();">${t('menu_reload')}</button>` : ''}
                        <button class="btn-delete-item" onclick="removeCategory('${escapeAttr(category)}'); closeAllMenus();">${t('menu_delete')}</button>
                    </div>
                </div>
            </div>
            <div class="nodes-list"></div>
        `;
        const listNode = group.querySelector('.nodes-list');
        profiles[category].nodes.forEach(node => {
            const isSelected = activeConfig === `${category}:${node.id}`;
            const item = document.createElement('div');
            item.className = `config-item${isSelected ? ' selected' : ''}`;
            item.innerHTML = `
                <div class="config-info" style="flex: 1; display: flex; flex-direction: column;">
                    <div class="config-name">${escapeHtml(node.name)}</div>
                    <div class="config-meta">${node.protocol.toUpperCase()} | ${escapeHtml(node.address)}:${escapeHtml(node.port)}</div>
                </div>
                <div class="node-actions-container">
                    ${isSelected ? '<span>📌</span>' : ''}
                    <div class="node-menu-container">
                        <button class="btn-menu-trigger" onclick="toggleNodeMenu(event, this)">⋮</button>
                        <div class="node-dropdown-menu">
                            <button onclick="openEditNodeModal(event, '${escapeAttr(category)}', '${node.id}')">${t('menu_edit')}</button>
                            <button class="btn-delete-item" onclick="deleteNode(event, '${escapeAttr(category)}', '${node.id}')">${t('menu_delete')}</button>
                        </div>
                    </div>
                </div>
            `;
            
            item.querySelector('.config-info').onclick = () => selectNode(category, node.id);
            listNode.appendChild(item);
        });
        container.appendChild(group);
    }
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
        alterId: "0",
        headerType: "none"
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
            const u = new URL(uri);
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
            }
        } catch (e) { console.error("Error parsing standard URL mapping", e); }
    }
    return d;
}

function serializeNodeDetailsToUri(d, protocol) {
    if (protocol === 'vmess') {
        let c = {
            v: "2", ps: d.name, add: d.address, port: parseInt(d.port) || 443, id: d.uuid,
            aid: parseInt(d.alterId) || 0, scy: d.encryption || "none", net: d.network,
            tls: d.security === 'tls' ? 'tls' : 'none',
            sni: d.security === 'tls' ? d.sni : "",
            alpn: d.security === 'tls' ? d.alpn : "",
            type: "none", host: "", path: ""
        };
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
    const d = getFullNodeDetails(node);

    document.getElementById('edit-remarks').value = d.name;
    document.getElementById('edit-address').value = d.address;
    document.getElementById('edit-port').value = d.port;
    document.getElementById('edit-uuid').value = d.uuid;
    document.getElementById('edit-encryption').value = d.encryption;
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
    document.getElementById('edit-pbk').value = d.publicKey;
    document.getElementById('edit-sid').value = d.shortId;
    document.getElementById('edit-alterid').value = d.alterId;
    document.getElementById('field-group-encryption').style.display = (node.protocol === 'trojan') ? 'none' : 'flex';
    document.getElementById('field-group-flow').style.display = (node.protocol === 'vless') ? 'flex' : 'none';
    document.getElementById('field-group-alterid').style.display = (node.protocol === 'vmess') ? 'flex' : 'none';
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

function saveEditedNode() {
    if (!currentEditingCategory || !currentEditingNodeId) return;

    const nodeIdx = profiles[currentEditingCategory]?.nodes?.findIndex(n => n.id === currentEditingNodeId);
    if (nodeIdx === -1) return;

    const d = {
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
        publicKey: document.getElementById('edit-pbk').value.trim(),
        shortId: document.getElementById('edit-sid').value.trim(),
        alterId: document.getElementById('edit-alterid').value.trim() || "0",
        headerType: document.getElementById('edit-header-type').value
    };

    const newUri = serializeNodeDetailsToUri(d, currentEditingProtocol);

    profiles[currentEditingCategory].nodes[nodeIdx] = {
        id: currentEditingNodeId,
        name: d.name,
        protocol: currentEditingProtocol,
        address: d.address,
        port: d.port,
        uuid: d.uuid,
        security: d.security,
        rawUri: newUri
    };

    saveProfiles();
    closeEditNodeModal();
    renderProfiles();

    if (activeConfig === `${currentEditingCategory}:${currentEditingNodeId}`) {
        const xrayConfig = convert_uri_to_xray_json(newUri, advSettings);
        execShell(`echo '${xrayConfig}' > '${CONFIG_JSON}'`, () => {
            execShell(`sh ${MODDIR}/proxy_control.sh status`, (status) => {
                if (status === 'running') {
                    toggleService('restart');
                }
            });
        });
    }
}

function closeAllMenus() {
    document.querySelectorAll('.category-dropdown-menu').forEach(menu => menu.classList.remove('show'));
    document.querySelectorAll('.node-dropdown-menu').forEach(menu => menu.classList.remove('show'));
}

document.addEventListener('click', () => {
    closeAllMenus();
});
 
function escapeHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
 
function escapeAttr(str) {
    return String(str).replace(/'/g, "\\'");
}

function switchTab(tabId) {
    document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));
    
    document.getElementById(tabId).classList.add('active');
    event.currentTarget.classList.add('active');
}

function toggleSubSettingField(triggerId, subPanelId) {
    const isChecked = document.getElementById(triggerId).checked;
    document.getElementById(subPanelId).style.display = isChecked ? 'block' : 'none';
}

const originalLoadState = loadState;
loadState = function(callback) {
    originalLoadState(() => {
        execShell(`cat '${SETTINGS_FILE}' 2>/dev/null || echo ''`, (settingsRaw) => {
            if (settingsRaw.trim()) {
                try {
                    advSettings = JSON.parse(decodeURIComponent(escape(atob(settingsRaw.trim()))));
                } catch (e) {
                    console.warn("[loadState] Custom settings corrupt, fallback to defaults.");
                }
            }
            bindSettingsToFormView();
            if (callback) callback();
        });
    });
};

function bindSettingsToFormView() {
    currentLang = advSettings.lang || "en";
    applyI18n();

    document.getElementById('set-loglevel').value = advSettings.loglevel || "debug";
    document.getElementById('set-sniffing').checked = advSettings.sniffing;
    document.getElementById('set-routeonly').checked = advSettings.routeOnly;
    document.getElementById('set-preferipv6').checked = advSettings.preferIpv6;
    document.getElementById('set-dnsviaproxy').checked = advSettings.dnsViaProxy || true;
    document.getElementById('set-fakedns').checked = advSettings.fakeDns || false;
    document.getElementById('set-pinned-cert').value = advSettings.pinnedPeerCertSha256 || "";
    
    document.getElementById('set-mux').checked = advSettings.mux;
    document.getElementById('set-mux-connections').value = advSettings.mux_connections;
    toggleSubSettingField('set-mux', 'mux-sub-fields');

    document.getElementById('set-fragment').checked = advSettings.fragment;
    document.getElementById('set-fragment-packets').value = advSettings.fragment_packets || "tlshello";
    document.getElementById('set-fragment-length').value = advSettings.fragment_length || "50-100";
    document.getElementById('set-fragment-interval').value = advSettings.fragment_interval || "10-20";
    toggleSubSettingField('set-fragment', 'fragment-sub-fields');

    document.getElementById('set-mtu').value = advSettings.mtu || 1350;
}

function saveAdvancedSettingsForm(isLangOnly = false) {
    advSettings.loglevel = document.getElementById('set-loglevel').value;
    advSettings.sniffing = document.getElementById('set-sniffing').checked;
    advSettings.routeOnly = document.getElementById('set-routeonly').checked;
    advSettings.preferIpv6 = document.getElementById('set-preferipv6').checked;
    advSettings.dnsViaProxy = document.getElementById('set-dnsviaproxy').checked;
    advSettings.fakeDns = document.getElementById('set-fakedns').checked;
    advSettings.pinnedPeerCertSha256 = document.getElementById('set-pinned-cert').value.trim();
    
    advSettings.mux = document.getElementById('set-mux').checked;
    advSettings.mux_connections = parseInt(document.getElementById('set-mux-connections').value) || 8;

    advSettings.fragment = document.getElementById('set-fragment').checked;
    advSettings.fragment_packets = document.getElementById('set-fragment-packets').value;
    advSettings.fragment_length = document.getElementById('set-fragment-length').value || "50-100";
    advSettings.fragment_interval = document.getElementById('set-fragment-interval').value || "10-20";

    advSettings.mtu = parseInt(document.getElementById('set-mtu').value) || 1350;

    advSettings.lang = currentLang;

    const jsonStr = JSON.stringify(advSettings);
    const base64Encoded = utoa(jsonStr);
    
    execShell(`printf '%s' '${base64Encoded}' > '${SETTINGS_FILE}'`, () => {
        if (isLangOnly) {
            return;
        }
        showToast(t('toast_settings_saved'), "success");
        
        if (activeConfig) {
            const [category, id] = activeConfig.split(':');
            const node = profiles[category]?.nodes?.find(n => n.id === id); 
            if (node) {
                const xrayConfig = convert_uri_to_xray_json(node.rawUri, advSettings);
                execShell(`echo '${xrayConfig}' > '${CONFIG_JSON}'`, () => {
                    execShell(`sh ${MODDIR}/proxy_control.sh status`, (status) => {
                        if (status === 'running') {
                            toggleService('restart');
                        }
                    });
                });
                return;
            }
        }
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