// "App settings" tab: theme (system / light / dark) and primary colour (system / default / custom).
// The palette engine lives in theme.js; this file only wires the controls and the colour picker.
(function () {
    'use strict';
    var T = window.MVTheme;
    var $ = function (id) { return document.getElementById(id); };
    if (!T) return;
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();

    function init() {
    if (!$('app-theme')) return;

    var SWATCHES = ['#006E1C', '#00796B', '#0061A4', '#3F51B5', '#6750A4', '#9C27B0',
                    '#C2185B', '#BA1A1A', '#E65100', '#F9A825', '#795548', '#607D8B'];
    var themeSel = $('app-theme'), colorSel = $('app-color');
    var hsv = { h: 140, s: 100, v: 43 };

    // ---- HSV <-> hex
    function hsvToHex(h, s, v) {
        s /= 100; v /= 100;
        var f = function (n) { var k = (n + h / 60) % 6; return v - v * s * Math.max(0, Math.min(k, 4 - k, 1)); };
        return T.rgbToHex([f(5) * 255, f(3) * 255, f(1) * 255]);
    }
    function hexToHsv(hex) {
        var c = T.hexToRgb(hex).map(function (x) { return x / 255; });
        var max = Math.max.apply(null, c), min = Math.min.apply(null, c), d = max - min, h = 0;
        if (d) {
            if (max === c[0]) h = ((c[1] - c[2]) / d) % 6;
            else if (max === c[1]) h = (c[2] - c[0]) / d + 2;
            else h = (c[0] - c[1]) / d + 4;
            h = (h * 60 + 360) % 360;
        }
        return { h: h, s: max ? d / max * 100 : 0, v: max * 100 };
    }

    // ---- main controls
    function syncColorRow() {
        var p = T.get(), custom = p.color === 'custom' && T.colorSupported();
        $('app-color-current').hidden = !custom;
        $('app-color-dot').style.background = p.seed;
        $('app-color-hex').textContent = p.seed;
    }
    function syncControls() {
        var p = T.get();
        themeSel.value = p.theme;
        colorSel.value = p.color;
        colorSel.disabled = !T.colorSupported();
        syncColorRow();
    }
    themeSel.addEventListener('change', function () { T.set({ theme: themeSel.value }); });
    colorSel.addEventListener('change', function () {
        if (colorSel.value === 'custom') openPicker();
        else { T.set({ color: colorSel.value }); syncColorRow(); }
    });
    $('app-color-change').addEventListener('click', openPicker);

    // ---- picker
    function paintPicker(fromHex) {
        var hex = hsvToHex(hsv.h, hsv.s, hsv.v);
        $('cp-preview').style.background = hex;
        if (!fromHex) $('cp-hex').value = hex;
        $('cp-hue').value = hsv.h; $('cp-sat').value = hsv.s; $('cp-val').value = hsv.v;
        $('cp-sat').style.background = 'linear-gradient(to right,' + hsvToHex(hsv.h, 0, hsv.v) + ',' + hsvToHex(hsv.h, 100, hsv.v) + ')';
        $('cp-val').style.background = 'linear-gradient(to right,#000,' + hsvToHex(hsv.h, hsv.s, 100) + ')';
        document.querySelectorAll('.cp-swatch').forEach(function (b) { b.classList.toggle('selected', b.dataset.color === hex); });
    }
    function openPicker() {
        hsv = hexToHsv(T.get().seed);
        paintPicker();
        $('color-picker-modal').style.display = 'block';
    }
    function closePicker() {
        $('color-picker-modal').style.display = 'none';
        syncControls();                       // a cancelled "Choose custom color..." reverts the select
    }
    ['cp-hue', 'cp-sat', 'cp-val'].forEach(function (id) {
        $(id).addEventListener('input', function () {
            hsv = { h: +$('cp-hue').value, s: +$('cp-sat').value, v: +$('cp-val').value };
            paintPicker();
        });
    });
    $('cp-hex').addEventListener('input', function () {
        var v = this.value.trim();
        if (v[0] !== '#') v = '#' + v;
        if (/^#[0-9a-fA-F]{6}$/.test(v)) { hsv = hexToHsv(v); paintPicker(true); }
    });
    var box = $('cp-swatches');
    SWATCHES.forEach(function (hex) {
        var b = document.createElement('button');
        b.type = 'button'; b.className = 'cp-swatch'; b.dataset.color = hex; b.style.background = hex;
        b.setAttribute('aria-label', hex);
        b.addEventListener('click', function () { hsv = hexToHsv(hex); paintPicker(); });
        box.appendChild(b);
    });
    $('cp-cancel').addEventListener('click', closePicker);
    $('cp-ok').addEventListener('click', function () {
        T.set({ color: 'custom', seed: hsvToHex(hsv.h, hsv.s, hsv.v) });
        $('color-picker-modal').style.display = 'none';
        syncControls();
    });

    syncControls();
    }
})();
