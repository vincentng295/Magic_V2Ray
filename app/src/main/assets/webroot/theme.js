// App appearance: light / dark / system, and the accent palette (system / default / custom).
// Loaded first (before first paint) so the page never flashes the wrong theme. Everything is
// driven by CSS variables set on <html>; style.css only holds the dark/green fallback values.
//
// Settings live in the APK host (SharedPreferences, via ksu.getAppSettings / setAppSettings) so
// they are readable synchronously and by native code; a plain browser falls back to localStorage.
(function (global) {
    'use strict';

    var DEFAULT_SEED = '#006E1C';                 // Material baseline green
    var DEFAULTS = { theme: 'system', color: 'default', seed: DEFAULT_SEED, bg: false, bgTheme: false, bgSeed: '', bgDim: 35 };
    var BG_KEY = 'mv_bg_image';                   // wallpaper data URL (downscaled JPEG), per-device
    var MIN_SDK_COLOR = 33;                       // "Primary color" needs Android 13+

    // ---------------------------------------------------------------- colour math (CIELAB)
    var WP = [0.95047, 1, 1.08883];
    function lin(c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
    function gam(c) { return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055; }
    function hexToRgb(h) {
        h = String(h).replace('#', '');
        return [0, 2, 4].map(function (i) { return parseInt(h.substr(i, 2), 16); });
    }
    function rgbToHex(rgb) {
        return '#' + rgb.map(function (v) {
            v = Math.max(0, Math.min(255, Math.round(v)));
            return (v < 16 ? '0' : '') + v.toString(16);
        }).join('').toUpperCase();
    }
    function hexToLch(hex) {
        var c = hexToRgb(hex).map(function (v) { return lin(v / 255); });
        var X = 0.4124564 * c[0] + 0.3575761 * c[1] + 0.1804375 * c[2];
        var Y = 0.2126729 * c[0] + 0.7151522 * c[1] + 0.0721750 * c[2];
        var Z = 0.0193339 * c[0] + 0.1191920 * c[1] + 0.9503041 * c[2];
        var f = function (t) { return t > 216 / 24389 ? Math.cbrt(t) : (24389 / 27 * t + 16) / 116; };
        var fx = f(X / WP[0]), fy = f(Y / WP[1]), fz = f(Z / WP[2]);
        var a = 500 * (fx - fy), b = 200 * (fy - fz);
        return { L: 116 * fy - 16, C: Math.hypot(a, b), h: (Math.atan2(b, a) * 180 / Math.PI + 360) % 360 };
    }
    // Returns linear-light RGB (may be out of gamut) for L*, C, h.
    function lchToLin(L, C, h) {
        var hr = h * Math.PI / 180, a = C * Math.cos(hr), b = C * Math.sin(hr);
        var fy = (L + 16) / 116, fx = fy + a / 500, fz = fy - b / 200;
        var inv = function (t) { return t * t * t > 216 / 24389 ? t * t * t : (116 * t - 16) / (24389 / 27); };
        var X = WP[0] * inv(fx), Y = WP[1] * inv(fy), Z = WP[2] * inv(fz);
        return [3.2404542 * X - 1.5371385 * Y - 0.4985314 * Z,
               -0.9692660 * X + 1.8760108 * Y + 0.0415560 * Z,
                0.0556434 * X - 0.2040259 * Y + 1.0572252 * Z];
    }
    function inGamut(c) { return c.every(function (v) { return v >= -0.0005 && v <= 1.0005; }); }
    // Colour at tone (L*) with the requested chroma, chroma reduced until it fits sRGB.
    function lch(L, C, h) {
        if (L <= 0) return '#000000';
        if (L >= 100) return '#FFFFFF';
        var lo = 0, hi = C, c = lchToLin(L, C, h);
        if (!inGamut(c)) {
            for (var i = 0; i < 24; i++) {
                var mid = (lo + hi) / 2;
                if (inGamut(lchToLin(L, mid, h))) lo = mid; else hi = mid;
            }
            c = lchToLin(L, lo, h);
        }
        return rgbToHex(c.map(function (v) { return gam(Math.max(0, Math.min(1, v))) * 255; }));
    }
    function mix(h1, h2, f) {
        var a = hexToRgb(h1), b = hexToRgb(h2);
        return rgbToHex(a.map(function (v, i) { return v + (b[i] - v) * f; }));
    }

    // ---------------------------------------------------------------- palettes
    // A palette is tone(n) -> hex, n in 0..100.
    function tonal(h, C) {
        var cache = {};
        return function (t) { return cache[t] || (cache[t] = lch(t, C, h)); };
    }
    function paletteFromSeed(seedHex) {
        var s = hexToLch(seedHex);
        var gray = s.C < 6;                                   // no usable hue: stay neutral
        var h = s.h;
        var a1 = gray ? 6 : Math.min(90, Math.max(64, s.C));
        return {
            a1: tonal(h, a1), a2: tonal(h, gray ? 4 : 15), a3: tonal((h + 60) % 360, gray ? 4 : 28),
            n1: tonal(h, gray ? 1 : 3), n2: tonal(h, gray ? 2 : 7)
        };
    }
    // Android's system_accentN_* / system_neutralN_* shades -> tone(n) by interpolation.
    var SHADE_TONE = { 0: 100, 10: 99, 50: 95, 100: 90, 200: 80, 300: 70, 400: 60, 500: 50, 600: 40, 700: 30, 800: 20, 900: 10, 1000: 0 };
    function fromShades(shades) {
        var pts = Object.keys(SHADE_TONE).map(function (k) { return [SHADE_TONE[k], shades[k]]; })
            .filter(function (p) { return p[1]; }).sort(function (a, b) { return a[0] - b[0]; });
        return function (t) {
            for (var i = 0; i < pts.length - 1; i++) {
                if (t >= pts[i][0] && t <= pts[i + 1][0]) {
                    var span = pts[i + 1][0] - pts[i][0];
                    return span ? mix(pts[i][1], pts[i + 1][1], (t - pts[i][0]) / span) : pts[i][1];
                }
            }
            return pts[t < pts[0][0] ? 0 : pts.length - 1][1];
        };
    }
    function paletteFromSystem(sys) {
        if (!sys || !sys.a1 || !sys.n1) return null;
        return { a1: fromShades(sys.a1), a2: fromShades(sys.a2 || sys.a1), a3: fromShades(sys.a3 || sys.a1),
                 n1: fromShades(sys.n1), n2: fromShades(sys.n2 || sys.n1) };
    }

    // ---------------------------------------------------------------- scheme
    function buildScheme(p, dark) {
        var s = dark ? {
            '--md-primary': p.a1(80), '--md-on-primary': p.a1(20), '--md-primary-container': p.a1(30), '--md-on-primary-container': p.a1(90),
            '--md-secondary': p.a2(80), '--md-on-secondary': p.a2(20), '--md-secondary-container': p.a2(30), '--md-on-secondary-container': p.a2(90),
            '--md-tertiary': p.a3(80), '--md-on-tertiary': p.a3(20), '--md-tertiary-container': p.a3(30), '--md-on-tertiary-container': p.a3(90),
            '--md-error': '#F2B8B5', '--md-on-error': '#601410', '--md-error-container': '#8C1D18', '--md-on-error-container': '#F9DEDC',
            '--md-success': '#6DD58C', '--md-success-container': '#0A3818', '--md-on-success-container': '#6DD58C',
            '--md-warning': '#FFC457', '--blue': '#82B4FF',
            '--md-surface': p.n1(6), '--md-surface-1': p.n1(10), '--md-surface-2': p.n1(12), '--md-surface-3': p.n1(15),
            '--md-surface-4': p.n1(17), '--md-surface-5': p.n1(19),
            '--md-on-surface': p.n1(90), '--md-on-surface-variant': p.n2(80), '--md-outline': p.n2(60), '--md-outline-variant': p.n2(30)
        } : {
            '--md-primary': p.a1(40), '--md-on-primary': p.a1(100), '--md-primary-container': p.a1(90), '--md-on-primary-container': p.a1(10),
            '--md-secondary': p.a2(40), '--md-on-secondary': p.a2(100), '--md-secondary-container': p.a2(90), '--md-on-secondary-container': p.a2(10),
            '--md-tertiary': p.a3(40), '--md-on-tertiary': p.a3(100), '--md-tertiary-container': p.a3(90), '--md-on-tertiary-container': p.a3(10),
            '--md-error': '#BA1A1A', '--md-on-error': '#FFFFFF', '--md-error-container': '#FFDAD6', '--md-on-error-container': '#410002',
            '--md-success': '#1E7A3C', '--md-success-container': '#C9F0D3', '--md-on-success-container': '#0A3818',
            '--md-warning': '#8A5100', '--blue': '#1C5DB5',
            '--md-surface': p.n1(98), '--md-surface-1': p.n1(96), '--md-surface-2': p.n1(94), '--md-surface-3': p.n1(92),
            '--md-surface-4': p.n1(91), '--md-surface-5': p.n1(90),
            '--md-on-surface': p.n1(10), '--md-on-surface-variant': p.n2(30), '--md-outline': p.n2(50), '--md-outline-variant': p.n2(80)
        };
        // r, g, b triplets for rgba(var(--x-rgb), alpha) in style.css
        ['primary', 'on-surface', 'error', 'success'].forEach(function (k) {
            s['--md-' + k + '-rgb'] = hexToRgb(s['--md-' + k]).join(', ');
        });
        return s;
    }

    // ---------------------------------------------------------------- settings + host bridge
    var host = global.ksu;
    function hostHas(fn) { return host && typeof host[fn] === 'function'; }
    function sdk() { return hostHas('sdk') ? Number(host.sdk()) || 0 : 0; }
    function colorSupported() { return !hostHas('sdk') || sdk() >= MIN_SDK_COLOR; }   // plain browser: allow (preview)

    function sanitize(o) {
        o = o || {};
        var seed = /^#[0-9a-fA-F]{6}$/.test(o.seed || '') ? o.seed.toUpperCase() : DEFAULT_SEED;
        return {
            theme: ['system', 'light', 'dark'].indexOf(o.theme) >= 0 ? o.theme : DEFAULTS.theme,
            color: ['system', 'default', 'custom'].indexOf(o.color) >= 0 ? o.color : DEFAULTS.color,
            seed: seed,
            bg: o.bg === true,
            bgTheme: o.bgTheme === true,
            bgSeed: /^#[0-9a-fA-F]{6}$/.test(o.bgSeed || '') ? o.bgSeed.toUpperCase() : '',
            bgDim: Math.max(0, Math.min(80, Number(o.bgDim) >= 0 ? Math.round(Number(o.bgDim)) : DEFAULTS.bgDim))
        };
    }
    function getImage() { try { return (global.localStorage && localStorage.getItem(BG_KEY)) || ''; } catch (e) { return ''; } }
    function putImage(d) {
        try {
            if (!global.localStorage) return false;
            if (d) localStorage.setItem(BG_KEY, d); else localStorage.removeItem(BG_KEY);
            return true;
        } catch (e) { return false; }
    }
    function load() {
        var raw = '';
        try {
            raw = hostHas('getAppSettings') ? host.getAppSettings() : (global.localStorage && localStorage.getItem('mv_app_settings'));
        } catch (e) { /* ignore */ }
        try { return sanitize(raw ? JSON.parse(raw) : null); } catch (e) { return sanitize(null); }
    }
    function save(p) {
        var raw = JSON.stringify(p);
        try {
            if (hostHas('setAppSettings')) host.setAppSettings(raw);
            else if (global.localStorage) localStorage.setItem('mv_app_settings', raw);
        } catch (e) { /* ignore */ }
    }
    function systemIsDark() {
        try { if (hostHas('isSystemNight')) return !!host.isSystemNight(); } catch (e) { /* fall through */ }
        return !!(global.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
    }
    function systemPalette() {
        try {
            var raw = hostHas('getSystemPalette') ? host.getSystemPalette() : '';
            return raw ? paletteFromSystem(JSON.parse(raw)) : null;
        } catch (e) { return null; }
    }

    var prefs = load();
    var img = getImage();

    function resolve(p) {
        var dark = p.theme === 'dark' || (p.theme === 'system' && systemIsDark());
        var mode = colorSupported() ? p.color : 'default';
        var pal = mode === 'system' ? systemPalette() : null;
        var bgOn = p.bg && !!img;
        if (bgOn && p.bgTheme && p.bgSeed) pal = paletteFromSeed(p.bgSeed);       // theme colour from wallpaper
        else if (!pal) pal = paletteFromSeed(mode === 'custom' ? p.seed : DEFAULT_SEED);
        return { dark: dark, scheme: buildScheme(pal, dark) };
    }
    function apply() {
        var r = resolve(prefs), root = document.documentElement;
        var bgOn = prefs.bg && !!img;
        Object.keys(r.scheme).forEach(function (k) { root.style.setProperty(k, r.scheme[k]); });
        // Wallpaper: cards turn translucent so the picture shows through; --mv-bg-dim darkens/lightens it.
        if (bgOn) {
            ['--md-surface-1', '--md-surface-2', '--md-surface-3'].forEach(function (k) {
                root.style.setProperty(k, 'rgba(' + hexToRgb(r.scheme[k]).join(',') + ',' + (r.dark ? 0.72 : 0.78) + ')');
            });
            root.style.setProperty('--mv-bg-image', 'url("' + img + '")');
            root.style.setProperty('--mv-bg-dim', 'rgba(' + hexToRgb(r.scheme['--md-surface']).join(',') + ',' + (prefs.bgDim / 100) + ')');
            root.style.setProperty('--mv-surface-glass', 'rgba(' + hexToRgb(r.scheme['--md-surface']).join(',') + ',0.6)');
        } else {
            ['--mv-bg-image', '--mv-bg-dim', '--mv-surface-glass'].forEach(function (k) { root.style.removeProperty(k); });
        }
        root.classList.toggle('has-bg', bgOn);
        root.setAttribute('data-theme', r.dark ? 'dark' : 'light');
        root.style.colorScheme = r.dark ? 'dark' : 'light';
        try { if (hostHas('setChrome')) host.setChrome(r.scheme['--md-surface'], !r.dark); } catch (e) { /* ignore */ }
        return r;
    }

    function update(patch) {
        prefs = sanitize(Object.assign({}, prefs, patch));
        save(prefs);
        return apply();
    }

    global.MVTheme = {
        get: function () { return Object.assign({}, prefs); },
        set: update,
        apply: apply,
        hasImage: function () { return !!img; },
        getImage: function () { return img; },
        // dataUrl: downscaled JPEG; seed: dominant colour of it. Returns false when it could not be stored.
        setImage: function (dataUrl, seed) {
            if (!putImage(dataUrl)) return false;
            img = dataUrl;
            update({ bg: true, bgSeed: seed || '' });
            return true;
        },
        clearImage: function () { putImage(''); img = ''; update({ bg: false, bgTheme: false, bgSeed: '' }); },
        colorSupported: colorSupported,
        defaultSeed: DEFAULT_SEED,
        // exposed for the picker + tests
        hexToRgb: hexToRgb, rgbToHex: rgbToHex, hexToLch: hexToLch, lch: lch,
        paletteFromSeed: paletteFromSeed, paletteFromSystem: paletteFromSystem, buildScheme: buildScheme
    };
    // Native calls this when the system dark mode (or dynamic colours) change.
    global.mvThemeSystemChanged = function () { if (prefs.theme === 'system' || prefs.color === 'system') apply(); };
    if (global.matchMedia) {
        try { matchMedia('(prefers-color-scheme: dark)').addEventListener('change', global.mvThemeSystemChanged); } catch (e) { /* old engine */ }
    }
    document.addEventListener('visibilitychange', function () { if (!document.hidden) global.mvThemeSystemChanged(); });

    apply();
    if (typeof module !== 'undefined' && module.exports) module.exports = global.MVTheme;
})(typeof window !== 'undefined' ? window : globalThis);
