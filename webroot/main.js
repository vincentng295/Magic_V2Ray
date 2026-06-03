const MODDIR = "/data/adb/modules/magic_v2ray";
const DATADIR = "/data/adb/magic_v2ray";
const PROFILES_FILE = `${DATADIR}/profiles.base64`;
const ACTIVE_FILE = `${DATADIR}/active_config.txt`;
const CONFIG_JSON = `${DATADIR}/config.json`;
 
let profiles = {};
let activeConfig = null;
 
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
        if (callback) callback("");
    }
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
        updateStatusDisplay();
        renderProfiles();
    });
});
 
function updateStatusDisplay() {
    execShell(`sh ${MODDIR}/proxy_control.sh status`, (status) => {
        const badge = document.getElementById('service-status');
        const s = status || 'stopped';
        badge.innerText = `Status: ${s.toUpperCase()}`;
        badge.className = `status-badge ${s === 'running' ? 'active' : 'inactive'}`;
    });
}
 
function toggleService(action) {
    if (action === 'start' || action === 'restart') {
        // Re-apply config.json for selected node before starting
        if (activeConfig) {
            const [category, id] = activeConfig.split(':');
            const node = profiles[category]?.nodes?.find(n => n.id === id); 
            if (node) {
                const xrayConfig = convert_uri_to_xray_json(node.rawUri);
                execShell(`echo '${xrayConfig}' > '${CONFIG_JSON}'`, () => {
                    execShell(`sh ${MODDIR}/proxy_control.sh restart`, () => {
                        updateStatusDisplay();      
                    });
                });
                return;
            }
        }
    }
    execShell(`sh ${MODDIR}/proxy_control.sh ${action}`, () => {
        const badge = document.getElementById('service-status');
        badge.innerText = `Status: Loading...`;
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
    if (!input) return alert("Please paste a valid configuration or link.");
 
    if (input.startsWith('http://') || input.startsWith('https://')) {
        let domain;
        try {
            domain = new URL(input).hostname;
        } catch (e) {
            return alert("Invalid Subscription Link Format.");
        }
        fetchSubscription(domain, input);
    } else {
        const xrayConfigs = extractUrisFromText(input);
        parseAndAppendNodes("Manual", xrayConfigs, null);
    }
 
    document.getElementById('import-input').value = "";
}

function fetchSubscription(category, url, isReload = false) {
    const escapedUrl = url.replace(/'/g, "'\\''");
    execShell(`curl -sLk --max-time 15 '${escapedUrl}'`, (res) => {
        if (!res || res.trim() === "") {
            return alert("Failed to fetch.\nReason: Network unreachable or curl not available.");
        }
        if (res.includes("Failed to connect") || res.includes("Could not resolve")) {
            return alert("Failed to fetch.\nReason: " + res.split('\n')[0]);
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
        return alert("No valid configs extracted.\nSupported: vless://, vmess://, trojan://");
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
        alert(`Reloaded complete! Got ${profiles[category].nodes.length} node(s) for "${category}".`);
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
            alert("No new or valid configs extracted.");
        } else {
            alert(`Imported ${importedCount} node(s) into "${category}".`);
        }
    }
 
    saveProfiles();
    renderProfiles();
}

function reloadCategory(category) {
    const catData = profiles[category];
    if (!catData || !catData.url) {
        return alert("This category does not have a subscription URL to reload.");
    }
    fetchSubscription(category, catData.url, true);
}
 
function parseProxyUri(uri) {
    try {
        const protocolMatch = uri.match(/^([^:]+):\/\//);
        if (!protocolMatch) return null;
        const protocol = protocolMatch[1].toLowerCase();
        if (!['vless', 'vmess', 'trojan'].includes(protocol)) return null;
 
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
    xrayConfig = convert_uri_to_xray_json(node.rawUri);
 
    // dump xray config to file and restart service if running
    execShell(`echo '${xrayConfig}' > '${CONFIG_JSON}'`, () => {
        renderProfiles();
        execShell(`sh ${MODDIR}/proxy_control.sh status`, (status) => {
            if (status === 'running') toggleService('restart');
        });
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
            No configurations yet.<br>Paste a link or node string above.
        </p>`;
        return;
    }
    for (const category of categories) {
        const group = document.createElement('div');
        group.className = "category-group";
        const hasUrl = !!profiles[category].url;
        group.innerHTML = `
            <div class="category-header" style="position: relative; display: flex; justify-content: space-between; align-items: center;">
                <strong>📂 ${escapeHtml(category)} (${profiles[category].nodes.length})</strong>
                
                <div class="category-menu-container">
                    <button class="btn-menu-trigger" onclick="toggleCategoryMenu(event, this)">⋮</button>
                    <div class="category-dropdown-menu">
                        ${hasUrl ? `<button onclick="reloadCategory('${escapeAttr(category)}'); closeAllMenus();">🔄 Reload</button>` : ''}
                        <button class="btn-delete-item" onclick="removeCategory('${escapeAttr(category)}'); closeAllMenus();">🗑️ Delete</button>
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
            item.onclick = () => selectNode(category, node.id);
            item.innerHTML = `
                <div class="config-info">
                    <div class="config-name">${escapeHtml(node.name)}</div>
                    <div class="config-meta">${node.protocol.toUpperCase()} | ${escapeHtml(node.address)}:${escapeHtml(node.port)}</div>
                </div>
                ${isSelected ? '<span>📌</span>' : ''}
            `;
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

function closeAllMenus() {
    const menus = document.querySelectorAll('.category-dropdown-menu');
    menus.forEach(menu => menu.classList.remove('show'));
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