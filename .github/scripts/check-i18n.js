#!/usr/bin/env node
//
// Checks that every i18n key actually used by the Web UI is defined in
// every language in webroot/i18n.js.
//
// This used to be embedded inline in ci.yml as `node -e '...'`. Embedding a
// regex containing literal quote characters inside a single-quoted shell
// string meant the quotes couldn't be written directly, so the key-matching
// regex used `.` (match any character) as a stand-in for the actual quote
// character around each key. That over-matched: it picked up fragments of
// unrelated code as if they were translation keys (e.g. "ey", "extKey"),
// which look exactly like real missing-key failures but aren't. Keeping this
// as its own file means the exact script CI runs can be run and debugged
// locally with a plain `node .github/scripts/check-i18n.js`.

const fs = require('fs');

const i18n = eval('(function(){' + fs.readFileSync('webroot/i18n.js', 'utf8') + '; return i18n;})()');
const src = fs.readFileSync('webroot/main.js', 'utf8') + fs.readFileSync('webroot/index.html', 'utf8');

const used = new Set();
for (const m of src.matchAll(/\bt\(\s*['"]([a-zA-Z0-9_]+)['"]/g)) used.add(m[1]);
for (const m of src.matchAll(/data-i18n(?:-placeholder)?="([a-zA-Z0-9_]+)"/g)) used.add(m[1]);

const langs = Object.keys(i18n);
let bad = 0;
for (const k of used) {
    const missing = langs.filter(l => !(k in i18n[l]));
    if (missing.length) {
        console.log('::error::i18n key "' + k + '" missing in: ' + missing.join(', '));
        bad++;
    }
}

if (bad) process.exit(1);
console.log('i18n OK: ' + used.size + ' keys x ' + langs.length + ' languages');
