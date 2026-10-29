// ===== Exclude Apps tab =====
//
// Apps ticked here bypass Xray entirely (handled at the OS level by
// service.sh, same family of switches as the Network tab). This file only
// owns the UI + persistence:
//
//   advSettings.excludeApps       master switch      (settings.base64)
//   advSettings.excludeAutoApply  UI preference      (settings.base64)
//   EXCLUDE_LIST_FILE             one package name per line
//
// Pending edits live in `_exclude` (a draft) and are copied into advSettings
// only on commit. That way an unrelated save elsewhere (e.g. the Traffic
// Settings tab, which writes the whole advSettings object) can never
// persist half-edited exclude state without the engine restart it needs.
//
// The KernelSU API is called through window.ksu directly (not the npm
// `kernelsu` module). Its list-returning methods hand back JSON strings, so
// every call goes through _ksuCall(), which parses them.

const EXCLUDE_PAGE_SIZE = 40;
const EXCLUDE_AUTO_APPLY_DELAY_MS = 1000;
const EXCLUDE_PKG_RE = /^[A-Za-z0-9_.]+$/;

const _exclude = {
    inited: false,
    enabled: false,        // draft of advSettings.excludeApps
    set: new Set(),        // draft of the package list
    dirty: false,          // draft differs from what was last committed
    apps: [],              // [{ packageName, appLabel, isSystem }]
    filtered: [],
    loadedCount: 0,
    endReached: false,
    filter: 'user',        // user | system | all | excluded
    query: '',
    searchTimer: null,
    applyTimer: null,
    committing: false
};

// ---------------------------------------------------------------------------
// window.ksu access
// ---------------------------------------------------------------------------

// Calls window.ksu[name](...args). Returns the parsed value (JSON strings are
// parsed), or null if the method is missing or throws.
function _ksuCall(name, ...args) {
    try {
        const api = window.ksu;
        if (!api || typeof api[name] !== 'function') return null;
        const res = api[name](...args);
        if (typeof res === 'string') {
            try { return JSON.parse(res); } catch (e) { return res; }
        }
        return res;
    } catch (e) {
        console.warn(`[exclude] ksu.${name} failed`, e);
        return null;
    }
}

// Installed apps with labels. Prefers the KernelSU package API; falls back to
// `pm list packages` (no labels, no system flag from the API) if it is
// unavailable, so the tab is still usable on older WebUI hosts.
function _loadInstalledApps(callback) {
    const names = _ksuCall('listPackages', 'all');

    if (Array.isArray(names) && names.length) {
        const unique = Array.from(new Set(names.filter(n => EXCLUDE_PKG_RE.test(n))));
        const infoByPkg = {};
        for (let i = 0; i < unique.length; i += 200) {
            const chunk = unique.slice(i, i + 200);
            const infos = _ksuCall('getPackagesInfo', JSON.stringify(chunk));
            if (Array.isArray(infos)) infos.forEach(x => { if (x && x.packageName) infoByPkg[x.packageName] = x; });
        }
        callback(unique.map(pkg => {
            const x = infoByPkg[pkg] || {};
            return {
                packageName: pkg,
                appLabel: x.appLabel || pkg,
                isSystem: x.isSystem === true
            };
        }));
        return;
    }

    // Fallback: pm. "-3" = third-party only; the set difference gives system apps.
    execShell(`pm list packages -3 | cut -d: -f2; echo ---; pm list packages | cut -d: -f2`, (out) => {
        const [userPart = '', allPart = ''] = (out || '').split('---');
        const toList = s => s.split('\n').map(l => l.trim()).filter(n => EXCLUDE_PKG_RE.test(n));
        const user = new Set(toList(userPart));
        const all = Array.from(new Set(toList(allPart)));
        callback(all.map(pkg => ({ packageName: pkg, appLabel: pkg, isSystem: !user.has(pkg) })));
    });
}

// ---------------------------------------------------------------------------
// Tab lifecycle
// ---------------------------------------------------------------------------

function onExcludeTabOpened() {
    if (_exclude.inited) return;
    _exclude.inited = true;

    _exclude.enabled = advSettings.excludeApps === true;
    document.getElementById('set-exclude-enabled').checked = _exclude.enabled;
    document.getElementById('set-exclude-autoapply').checked = advSettings.excludeAutoApply === true;
    _syncExcludeChrome();

    execShell(`cat ${shQuote(EXCLUDE_LIST_FILE)} 2>/dev/null`, (raw) => {
        _exclude.set = new Set(_parseExcludeList(raw));
        _loadInstalledApps((apps) => {
            _exclude.apps = apps.sort((a, b) =>
                a.appLabel.localeCompare(b.appLabel, undefined, { sensitivity: 'base' }));
            renderExcludeList();
        });
    });
}

function _parseExcludeList(text) {
    return String(text || '')
        .split(/\r?\n/)
        .map(l => l.replace(/#.*/, '').trim())
        .filter(n => EXCLUDE_PKG_RE.test(n));
}

// Counter, pending bar and dimming - everything that depends on draft state.
function _syncExcludeChrome() {
    const autoApply = document.getElementById('set-exclude-autoapply').checked;

    const installed = new Set(_exclude.apps.map(a => a.packageName));
    const count = _exclude.apps.length
        ? Array.from(_exclude.set).filter(p => installed.has(p)).length
        : _exclude.set.size;
    document.getElementById('exclude-count').textContent = t('exclude_count', { count });

    document.getElementById('exclude-apply-bar').style.display = autoApply ? 'none' : '';
    const btn = document.getElementById('btn-exclude-apply');
    btn.disabled = !_exclude.dirty;
    document.getElementById('exclude-pending-text').style.visibility = _exclude.dirty ? 'visible' : 'hidden';

    document.getElementById('exclude-list-wrap').classList.toggle('exclude-list-dimmed', !_exclude.enabled);
}

// ---------------------------------------------------------------------------
// List rendering (filtered in memory, DOM filled a page at a time on scroll)
// ---------------------------------------------------------------------------

function renderExcludeList() {
    const q = _exclude.query.toLowerCase();
    _exclude.filtered = _exclude.apps.filter(a => {
        if (_exclude.filter === 'user' && a.isSystem) return false;
        if (_exclude.filter === 'system' && !a.isSystem) return false;
        if (_exclude.filter === 'excluded' && !_exclude.set.has(a.packageName)) return false;
        return !q || a.appLabel.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q);
    });

    _exclude.loadedCount = 0;
    _exclude.endReached = false;
    const container = document.getElementById('exclude-list-container');
    container.innerHTML = '';
    container.scrollTop = 0;

    document.getElementById('exclude-empty-state').style.display =
        _exclude.filtered.length ? 'none' : 'block';

    loadExcludePage();
    _syncExcludeChrome();
}

function loadExcludePage() {
    if (_exclude.endReached) return;
    const page = _exclude.filtered.slice(_exclude.loadedCount, _exclude.loadedCount + EXCLUDE_PAGE_SIZE);
    if (!page.length) { _exclude.endReached = true; return; }

    const container = document.getElementById('exclude-list-container');
    page.forEach(app => container.appendChild(_buildExcludeRow(app)));
    _exclude.loadedCount += page.length;
    if (_exclude.loadedCount >= _exclude.filtered.length) _exclude.endReached = true;
}

function onExcludeListScroll() {
    const c = document.getElementById('exclude-list-container');
    if (c.scrollTop + c.clientHeight >= c.scrollHeight - 160) loadExcludePage();
}

function _buildExcludeRow(app) {
    const row = document.createElement('div');
    row.className = 'setting-item-row toggle-row exclude-row';
    row.dataset.pkg = app.packageName;

    const label = document.createElement('label');
    label.className = 'exclude-label';

    const icon = document.createElement('img');
    icon.className = 'exclude-icon';
    icon.loading = 'lazy';
    icon.alt = '';
    icon.src = 'ksu://icon/' + app.packageName;
    icon.onerror = () => { icon.style.visibility = 'hidden'; icon.onerror = null; };

    const text = document.createElement('span');
    text.className = 'exclude-text';
    const name = document.createElement('span');
    name.className = 'exclude-name';
    name.textContent = app.appLabel;
    const pkg = document.createElement('small');
    pkg.textContent = app.packageName;
    text.append(name, pkg);

    label.append(icon, text);

    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = _exclude.set.has(app.packageName);
    cb.setAttribute('aria-label', app.appLabel);
    cb.addEventListener('change', () => {
        if (cb.checked) _exclude.set.add(app.packageName);
        else _exclude.set.delete(app.packageName);
        // On the "Excluded" filter the row stays until the next re-render, so
        // an accidental untick can be undone in place.
        _onExcludeDraftChanged();
    });

    // Tapping the label toggles the checkbox via the wrapping row.
    label.addEventListener('click', (e) => { e.preventDefault(); cb.click(); });

    row.append(label, cb);
    return row;
}

// ---------------------------------------------------------------------------
// Filters / bulk actions
// ---------------------------------------------------------------------------

function setExcludeFilter(filter) {
    _exclude.filter = filter;
    document.querySelectorAll('#exclude-filter-bar .log-filter-chip').forEach(el =>
        el.classList.toggle('active', el.dataset.filter === filter));
    renderExcludeList();
}

function onExcludeSearchInput() {
    clearTimeout(_exclude.searchTimer);
    _exclude.searchTimer = setTimeout(() => {
        const raw = document.getElementById('exclude-search-input').value.trim();
        // Strip HTML-significant characters and cap length so this value stays
        // safe even if a future change reflects it outside a textContent sink.
        _exclude.query = raw.replace(/[<>"'&]/g, '').slice(0, 100);
        renderExcludeList();
    }, 250);
}

// Acts on the apps currently matching the filter + search, not the whole device.
function excludeSelectVisible(select) {
    _exclude.filtered.forEach(a => {
        if (select) _exclude.set.add(a.packageName);
        else _exclude.set.delete(a.packageName);
    });
    document.querySelectorAll('#exclude-list-container .exclude-row').forEach(row => {
        row.querySelector('input[type="checkbox"]').checked = _exclude.set.has(row.dataset.pkg);
    });
    _onExcludeDraftChanged();
}

// ---------------------------------------------------------------------------
// Draft -> disk
// ---------------------------------------------------------------------------

function onExcludeEnabledChange(cb) {
    _exclude.enabled = cb.checked;
    _onExcludeDraftChanged();
}

// The auto-apply flag is a UI preference, so it is saved on its own and never
// restarts the engine. Pending edits are left in the draft.
function onExcludeAutoApplyChange(cb) {
    advSettings.excludeAutoApply = cb.checked;
    writeFileB64(SETTINGS_FILE, utoa(JSON.stringify(advSettings)), () => {});
    // Switching auto-apply ON with edits already pending: apply them now.
    if (cb.checked && _exclude.dirty) _scheduleExcludeApply();
    _syncExcludeChrome();
}

function _onExcludeDraftChanged() {
    _exclude.dirty = true;
    _syncExcludeChrome();
    if (document.getElementById('set-exclude-autoapply').checked) _scheduleExcludeApply();
}

// Debounced so ticking several apps in a row costs one restart, not many.
function _scheduleExcludeApply() {
    clearTimeout(_exclude.applyTimer);
    _exclude.applyTimer = setTimeout(applyExcludeChanges, EXCLUDE_AUTO_APPLY_DELAY_MS);
}

// Writes the list + settings, then fully restarts the engine: the exclusion
// is enforced by the OS-level rules that apply_routing_rules builds, so a
// soft xray reload would not pick it up.
function applyExcludeChanges() {
    clearTimeout(_exclude.applyTimer);
    if (_exclude.committing) {            // a commit is in flight; run again after it
        _scheduleExcludeApply();
        return;
    }
    if (!_exclude.dirty) return;
    _exclude.committing = true;
    _exclude.dirty = false;
    _syncExcludeChrome();

    // Keep packages that are not installed right now (e.g. app uninstalled
    // temporarily) so the selection comes back with them.
    const lines = Array.from(_exclude.set).filter(p => EXCLUDE_PKG_RE.test(p)).sort();
    advSettings.excludeApps = _exclude.enabled;

    execShell(`mkdir -p ${shQuote(DATADIR)} && chmod 700 ${shQuote(DATADIR)}`, () => {
        writeFileB64(EXCLUDE_LIST_FILE, lines.length ? lines.join('\n') + '\n' : '', () => {
            writeFileB64(SETTINGS_FILE, utoa(JSON.stringify(advSettings)), () => {
                showToast(t('toast_settings_saved'), 'success');
                applyActiveConfig({
                    force: true,
                    onDone: () => {
                        _exclude.committing = false;
                        if (_exclude.dirty && document.getElementById('set-exclude-autoapply').checked) {
                            _scheduleExcludeApply();
                        }
                    }
                });
            });
        });
    });
}
