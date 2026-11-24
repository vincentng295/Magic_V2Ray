// Back navigation for the WebUI, built on the History API.
//
// Every tab change and every opened modal pushes one history entry, so the system Back button
// (MainActivity.onBackPressed -> WebView.goBack) first closes the top-most modal, then walks back
// through the tabs visited, and only when nothing is left does the app go to the background.
//
// `entries` mirrors the history entries above the base page, one to one. Entries are added by
// switchTab() / a modal becoming visible, and removed by the popstate they cause (user pressed
// Back) or by us calling history.back() when the page closed the modal itself (`suppress` makes
// that popstate a no-op).
(function () {
    'use strict';
    if (!window.history || typeof history.pushState !== 'function') return;

    // Modals closed by calling a global function / by clicking a button (so promises resolve,
    // temporary state is reset, ...). Anything else visible gets display:none as a last resort.
    var CLOSE_FN = {
        'edit-node-modal': 'closeEditNodeModal',
        'proxy-chain-modal': 'closeProxyChainModal',
        'import-routing-modal': 'closeImportRoutingModal',
        'routing-rule-modal': 'closeRoutingRuleModal',
        'hosts-modal': 'closeHostModal',
        'dns-modal': 'closeDnsModal',
        'edit-sub-modal': 'closeEditSubModal',
        'add-provider-modal': 'closeAddProviderModal',
        'add-category-modal': 'closeAddCategoryModal',
        'move-node-modal': 'closeMoveNodeModal',
        'policy-group-modal': 'closePolicyGroupModal',
        'sio-modal': 'closeSettingsIoModal',
        'geo-download-modal': 'geoCloseDownload'
    };
    var CLOSE_BTN = {
        'confirm-modal-overlay': 'confirm-modal-cancel',
        'traffic-limit-overlay': 'traffic-limit-cancel',
        'update-modal-overlay': 'update-modal-x',
        'ext-modal-overlay': 'ext-modal-cancel',          // Back = Deny
        'access-help-overlay': 'access-help-close',
        'color-picker-modal': 'cp-cancel'
    };
    var OVERLAYS = Object.keys(CLOSE_FN).concat(Object.keys(CLOSE_BTN));   // loading-overlay is never dismissible

    var entries = [];          // [{kind:'tab'|'modal', id, dead}]
    var suppress = 0;          // popstates caused by our own history.back()
    var currentTab = null;

    function activeTab() {
        var el = document.querySelector('.tab-content.active');
        return el ? el.id : null;
    }

    function isShown(el) {
        return !!el && getComputedStyle(el).display !== 'none';
    }

    function push(kind, id) {
        entries.push({ kind: kind, id: id, dead: false });
        try {
            history.pushState({ mv: entries.length }, '');
        } catch (e) {
            entries.pop();
        }
    }

    function dropTop() {
        entries.pop();
        suppress++;
        try {
            history.back();
        } catch (e) {
            suppress--;
        }
    }

    function dismiss(id) {
        var el = document.getElementById(id);
        if (!isShown(el)) return;
        try {
            var fn = CLOSE_FN[id] && window[CLOSE_FN[id]];
            if (typeof fn === 'function') { fn(); }
            else if (CLOSE_BTN[id]) {
                var b = document.getElementById(CLOSE_BTN[id]);
                if (b) b.click();
            }
        } catch (e) { /* fall through to the hard close */ }
        if (isShown(el)) el.style.display = 'none';
    }

    // ---- tabs --------------------------------------------------------------------------------

    var rawSwitchTab = window.switchTab;
    if (typeof rawSwitchTab === 'function') {
        window.switchTab = function (tabId, evt) {
            var r = rawSwitchTab.apply(this, arguments);
            var now = activeTab();
            if (now && now !== currentTab) {
                currentTab = now;
                // Modals do not survive a tab change made by code (e.g. a deep link).
                push('tab', now);
            }
            return r;
        };
    }

    // ---- modals ------------------------------------------------------------------------------

    function onOverlayChange(el) {
        var id = el.id;
        var shown = isShown(el);
        var live = -1;
        for (var i = entries.length - 1; i >= 0; i--) {
            if (entries[i].kind === 'modal' && entries[i].id === id && !entries[i].dead) { live = i; break; }
        }
        if (shown && live < 0) {
            push('modal', id);
        } else if (!shown && live >= 0) {
            if (live === entries.length - 1) dropTop();     // closed by the page: take our entry back
            else entries[live].dead = true;                 // something was pushed above it; skip it later
        }
    }

    function watchOverlays() {
        var mo = new MutationObserver(function (list) {
            var seen = [];
            list.forEach(function (m) {
                if (seen.indexOf(m.target) < 0) { seen.push(m.target); onOverlayChange(m.target); }
            });
        });
        OVERLAYS.forEach(function (id) {
            var el = document.getElementById(id);
            if (!el) return;
            mo.observe(el, { attributes: true, attributeFilter: ['style', 'class'] });
            if (isShown(el)) onOverlayChange(el);
        });
    }

    // ---- Back --------------------------------------------------------------------------------

    window.addEventListener('popstate', function () {
        if (suppress > 0) { suppress--; return; }
        var e = entries.pop();
        if (!e) return;
        if (e.dead) { dropDead(); return; }
        if (e.kind === 'modal') {
            dismiss(e.id);                                    // the observer finds no live entry: no-op
        } else {
            // back to the tab below this entry (the base page is the dashboard)
            var prev = 'tab-dashboard';
            for (var i = entries.length - 1; i >= 0; i--) {
                if (entries[i].kind === 'tab') { prev = entries[i].id; break; }
            }
            currentTab = prev;
            rawSwitchTab(prev);
        }
        dropDead();
    });

    // A dead entry (modal already closed) at the top is only a stale history step: step over it.
    function dropDead() {
        while (entries.length && entries[entries.length - 1].dead) dropTop();
    }

    function init() {
        currentTab = activeTab();
        try { history.replaceState({ mv: 0 }, ''); } catch (e) { /* ignore */ }
        watchOverlays();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
