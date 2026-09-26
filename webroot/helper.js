// Preset DNS "hosts" overrides for well-known DoH/DoT resolver hostnames and
// the googleapis.cn mainland mirror. Xray resolves these itself before
// querying upstream, so pinning them here avoids resolver loops (a DoH
// hostname that needs DNS to resolve, resolved via that same DoH server)
// and keeps the mainland googleapis mirror pointed at the real service.
// Merged into dns.hosts for every generated config in convert_uri_to_xray_json().
const DEFAULT_DNS_HOSTS = {
    "domain:googleapis.cn": "googleapis.com",
    "dns.alidns.com": ["223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1"],
    "dns.sse.cisco.com": ["208.67.220.220", "208.67.222.222", "2620:119:35::35", "2620:119:53::53"],
    "dns.umbrella.com": ["208.67.220.220", "208.67.222.222", "2620:119:35::35", "2620:119:53::53"],
    "one.one.one.one": ["1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"],
    "1dot1dot1dot1.cloudflare-dns.com": ["1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"],
    "dns.cloudflare.com": ["162.159.61.8", "172.64.41.8", "2a06:98c1:52::8", "2803:f800:53::8"],
    "cloudflare-dns.com": ["104.16.248.249", "104.16.249.249", "2606:4700::6810:f8f9", "2606:4700::6810:f9f9"],
    "engage.cloudflareclient.com": ["162.159.192.1", "2606:4700:d0::a29f:c001"],
    "doh.pub": ["1.12.12.12", "120.53.53.53"],
    "dot.pub": ["1.12.12.12", "120.53.53.53"],
    "dns.google": ["8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"],
    "dns.quad9.net": ["9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"],
    "dns.sb": ["45.11.45.11", "185.222.222.222", "2a09::", "2a11::"],
    "common.dot.dns.yandex.net": ["77.88.8.8", "77.88.8.1", "2a02:6b8::feed:0ff", "2a02:6b8:0:1::feed:0ff"],
    "l0tl6ub9be.cloudflare-gateway.com": ["172.67.168.158", "64:ff9b::ac43:a89e" /* Need NAT64 */], // DoH DNS for VNPT
    "exkckr7pkk.cloudflare-gateway.com": ["162.159.141.236", "172.66.1.232", "2606:4700:7::1e4", "2a06:98c1:58::1e4"], // DoH DNS for Viettel, Mobifone, VNPT
};

// ===== Custom DNS hosts =====
// Two input formats are accepted:
//   1. JSON object:  { "example.com": ["1.2.3.4"], "domain:foo.cn": "foo.com" }
//   2. /etc/hosts-style text:  1.2.3.4 example.com www.example.com
//      (or "key value..." to create a domain alias, e.g.
//      "domain:googleapis.cn googleapis.com")
// An empty/null value, or a line prefixed with "!" or "-" in text format,
// means DELETE that key from DEFAULT_DNS_HOSTS — the only way to drop a
// built-in default without editing helper.js.
const HOSTS_KEY_PREFIX_RE = /^(domain|full|keyword|regexp|geosite|ext):/i;
const IPV4_RE = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;

function isIpv4Addr(s) { return IPV4_RE.test(s); }
function isIpv6Addr(s) {
    // Loose but good enough to distinguish an IP from a hostname — Xray
    // itself will reject a genuinely malformed address.
    return s.includes(":") && /^[0-9a-f:]+(\.[0-9.]+)?(%[\w.-]+)?$/i.test(s);
}
function isIpAddr(s) { return isIpv4Addr(s) || isIpv6Addr(s); }

function normalizeHostKey(key) {
    const k = String(key || "").trim();
    if (!k) return "";
    const m = k.match(HOSTS_KEY_PREFIX_RE);
    if (!m) return k.toLowerCase().replace(/\.$/, "");
    const prefix = m[0].toLowerCase();
    const rest = k.slice(m[0].length).trim();
    // regexp: keep case as-is — the pattern itself may be case-sensitive.
    return prefix === "regexp:" ? prefix + rest : prefix + rest.toLowerCase().replace(/\.$/, "");
}

// Returns: array of IPs | domain-alias string | null (= delete this key).
// Xray accepts either a string (an IP or a domain alias) or an array of IPs
// for a hosts value — a mixed/domain array is not valid config, so a domain
// found in the list always collapses to a single string.
function normalizeHostValue(value) {
    if (value === null || value === undefined) return null;
    const list = (Array.isArray(value) ? value : String(value).split(/[\s,]+/))
        .map(x => String(x).trim())
        .filter(Boolean);
    if (!list.length) return null;

    const ips = [];
    const domains = [];
    for (const item of list) (isIpAddr(item) ? ips : domains).push(item);

    if (ips.length) return [...new Set(ips)];
    return domains[0];
}

// input: object | JSON string | /etc/hosts-style text.
// Returns { hosts, errors } — errors are informational only and never block
// config generation: a broken line is skipped, the rest is still applied.
function parseCustomHosts(input) {
    const hosts = {};
    const errors = [];
    if (!input) return { hosts, errors };

    let raw = input;
    if (typeof raw === "string") {
        const trimmed = raw.trim();
        if (!trimmed) return { hosts, errors };
        if (trimmed.startsWith("{")) {
            try {
                raw = JSON.parse(trimmed);
            } catch (e) {
                return { hosts, errors: ["JSON: " + e.message] };
            }
        }
    }

    if (typeof raw === "object") {
        for (const [k, v] of Object.entries(raw)) {
            const key = normalizeHostKey(k);
            if (!key) continue;
            hosts[key] = normalizeHostValue(v);
        }
        return { hosts, errors };
    }

    // --- Text format ---
    String(raw).split(/\r?\n/).forEach((line, i) => {
        const text = line.replace(/(^|\s)#.*$/, "").trim();
        if (!text) return;

        // Delete a default: "!one.one.one.one" or "-doh.pub"
        if (/^[!-]/.test(text)) {
            const key = normalizeHostKey(text.slice(1));
            if (key) hosts[key] = null;
            return;
        }

        const parts = text.split(/[\s,]+/).filter(Boolean);
        if (parts.length < 2) {
            errors.push(`L${i + 1}: "${text}"`);
            return;
        }

        if (isIpAddr(parts[0])) {
            // /etc/hosts order: IP first, so invert into hostname -> [IP...]
            const ip = parts[0];
            for (const name of parts.slice(1)) {
                const key = normalizeHostKey(name);
                if (!key) continue;
                const prev = Array.isArray(hosts[key]) ? hosts[key] : [];
                hosts[key] = [...new Set([...prev, ip])];
            }
        } else {
            const key = normalizeHostKey(parts[0]);
            if (!key) { errors.push(`L${i + 1}: "${text}"`); return; }
            hosts[key] = normalizeHostValue(parts.slice(1));
        }
    });

    return { hosts, errors };
}

// Custom entries override the defaults key-by-key; any key not mentioned is
// left untouched, so googleapis.cn and every built-in DoH resolver survive
// unless the user deliberately removes one with "!key".
function mergeDnsHosts(base, custom) {
    const out = { ...(base || {}) };
    for (const [k, v] of Object.entries(custom || {})) {
        if (v === null) delete out[k];
        else out[k] = v;
    }
    return out;
}

// Single entry point used by convert_uri_to_xray_json(). Wrapped in
// try/catch so a malformed hosts file can never break config generation.
// Source moved from settings.customHosts (settings.base64) to the Custom
// Hosts tab's own file (HOSTS_FILE, real /etc/hosts syntax) — main.js
// keeps the small in-memory `hostsFileText` cache in sync with disk on
// every write, so generation here can stay synchronous.
function buildDnsHosts(settings) {
    try {
        const hosts = (typeof hostsFileText !== 'undefined' && hostsFileText)
            ? parseStandardHostsFile(hostsFileText)
            : parseCustomHosts(settings && settings.customHosts).hosts; // pre-migration fallback
        return mergeDnsHosts(DEFAULT_DNS_HOSTS, hosts);
    } catch (e) {
        return { ...DEFAULT_DNS_HOSTS };
    }
}

// ===== Custom Hosts tab helpers =====
// HOSTS_FILE is a genuine /etc/hosts file: "IP hostname [hostname2 ...]
// [# comment]", one mapping per line. A hostname that needs several IPs
// gets several lines (exactly how real hosts files, and DNS A records,
// already work) rather than one comma-packed line — so the file stays
// something a person could open and recognize outside this app too.
// There is no domain-to-domain aliasing here (unlike the old
// settings.customHosts format): that was a Xray-only extension with no
// equivalent in real hosts syntax, so the tab that edits a real hosts file
// doesn't offer it.

// Parses a whole hosts-file text into { hostname: [ip, ip, ...] } for
// dns.hosts generation. Comment-only and blank lines are skipped; a
// trailing "# ..." on a data line is stripped first. A line whose first
// token isn't a real IP is ignored rather than erroring, so a hand-edited
// file with typos never breaks config generation — it just drops that line.
function parseStandardHostsFile(text) {
    const hosts = {};
    String(text || '').split(/\r?\n/).forEach(rawLine => {
        const line = rawLine.replace(/#.*/, '').trim();
        if (!line) return;
        const parts = line.split(/\s+/).filter(Boolean);
        if (parts.length < 2) return;
        const ip = parts[0];
        if (!isIpAddr(ip)) return;
        for (const name of parts.slice(1)) {
            const key = normalizeHostKey(name);
            if (!key) continue;
            const prev = Array.isArray(hosts[key]) ? hosts[key] : [];
            hosts[key] = [...new Set([...prev, ip])];
        }
    });
    return hosts;
}

// Parses one data line (never a comment/header line) for the list UI.
// Returns null if the line isn't a valid "IP host..." mapping.
function parseStandardHostsDataLine(line) {
    const clean = String(line || '').replace(/#.*/, '').trim();
    if (!clean) return null;
    const parts = clean.split(/\s+/).filter(Boolean);
    if (parts.length < 2) return null;
    const ip = parts[0];
    if (!isIpAddr(ip)) return null;
    const hostnames = parts.slice(1).map(normalizeHostKey).filter(Boolean);
    if (!hostnames.length) return null;
    return { ip, hostnames };
}

// Parses a whole hosts-file text into an array of { domain, ips: [...] }
// entries, sorted by domain. Lines for the same hostname — whether spread
// across several lines or repeated within one multi-hostname line — are
// merged into a single entry. Pure JS: no awk/sed/grep/sort involved, so
// there's exactly one place that defines how a line is read.
function parseHostsEntries(text) {
    const map = new Map(); // domain -> Set(ip)
    String(text || '').split(/\r?\n/).forEach(rawLine => {
        const parsed = parseStandardHostsDataLine(rawLine);
        if (!parsed) return;
        parsed.hostnames.forEach(domain => {
            if (!map.has(domain)) map.set(domain, new Set());
            map.get(domain).add(parsed.ip);
        });
    });
    return Array.from(map.entries())
        .map(([domain, ipSet]) => ({ domain, ips: Array.from(ipSet) }))
        .sort((a, b) => a.domain.localeCompare(b.domain));
}

// Serializes entries back into the full file text, under the standard
// header — one "ip<TAB>domain" line per IP, exactly the shape
// parseHostsEntries() reads back.
function serializeHostsFile(entries) {
    const lines = [];
    (entries || []).forEach(e => (e.ips || []).forEach(ip => lines.push(`${ip}\t${e.domain}`)));
    return HOSTS_HEADER_TEXT + '\n' + (lines.length ? lines.join('\n') + '\n' : '');
}

const LEGACY_DNS = [
    "1.1.1.1",       // Cloudflare DNS (Public)
    "8.8.8.8",       // Google DNS (Public)
    "https+local://l0tl6ub9be.cloudflare-gateway.com/dns-query", // DoH DNS for VNPT
    "https+local://exkckr7pkk.cloudflare-gateway.com/dns-query", // DoH DNS for Viettel, Mobifone, VNPT
]

// FakeDNS address pools. The IPv6 pool is only added when IPv6 is enabled
// (see buildFakeDnsPools). Both are IETF "benchmarking" ranges (RFC2544 /
// RFC5180) rather than RFC1918/ULA space on purpose: Chromium's Private/
// Local Network Access only flags RFC1918 and fc00::/7 (ULA) as
// private/local, so a benchmarking pool never triggers Chrome's "wants to
// access devices on your local network" prompt the way an fc00::/7-based
// pool did. Neither range needs (or gets) a LAN-bypass carve-out in
// service.sh, since neither is in LAN_BYPASS_V4/V6 to begin with.
const FAKEDNS_POOL_V4 = "198.18.0.0/15";
const FAKEDNS_POOL_V6 = "2001:2::/48";

// dns.fakedns entries for the current settings.
function buildFakeDnsPools(settings) {
    return [
        { ipPool: FAKEDNS_POOL_V4, poolSize: 65535 },
        { ipPool: FAKEDNS_POOL_V6, poolSize: 65535 },
    ];
}

// Foreign DNS field default: every LEGACY_DNS entry, comma-separated.
const DEFAULT_FOREIGN_DNS = LEGACY_DNS.join(", ");

// Splits a comma-separated DNS list (as typed in a text field) into a clean
// array: trims each entry and drops empty ones.
function splitDnsList(str) {
    return String(str || "").split(",").map(s => s.trim()).filter(Boolean);
}

// Helper to decode Base64 safely for both Browser and Node.js environments.
// Accepts both the standard and the URL-safe alphabet and tolerates missing
// padding, because subscription providers emit all three variants.
function decodeBase64(str) {
    str = str.trim().replace(/-/g, '+').replace(/_/g, '/');
    while (str.length % 4) str += '=';
    if (typeof Buffer !== 'undefined') {
        return Buffer.from(str, 'base64').toString('utf-8');
    }
    return decodeURIComponent(atob(str).split('').map(c => {
        return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
    }).join(''));
}

// Same as decodeBase64 but yields null instead of throwing, for callers that
// treat "not base64" as a normal branch rather than an error.
function tryDecodeBase64(str) {
    try {
        const out = decodeBase64(str);
        return out || null;
    } catch (e) {
        return null;
    }
}

function utoa(str) {
    const bytes = new TextEncoder().encode(str);
    const binString = String.fromCodePoint(...bytes);
    return btoa(binString);
}

// ---------------------------------------------------------------------------
// IPv6 host helpers.
//
// `new URL(...)`'s `.hostname` (and any manual host:port split on a URI)
// always keeps an IPv6 literal wrapped in brackets, e.g. "[2001:db8::1]",
// because that's the only unambiguous way to embed it in a combined
// "host:port" string. Xray-core's *standalone* address fields (vless/trojan
// vnext/servers address, shadowsocks server address, hysteria "settings"
// address, socks/http server address) are NOT combined with a port in the
// same string, so they want the bare address instead — passing the
// brackets through verbatim makes Xray treat "[2001:db8::1]" as a literal
// (unresolvable) domain name rather than an IPv6 address. WireGuard's
// "endpoint" field is the one exception: it IS a combined host:port string,
// so it must keep the brackets and is left untouched by this helper.
// ---------------------------------------------------------------------------
function unwrapIPv6(host) {
    if (typeof host === 'string' && host.length > 2 && host.charAt(0) === '[' && host.charAt(host.length - 1) === ']') {
        return host.slice(1, -1);
    }
    return host;
}

// Inverse of unwrapIPv6: re-wrap a bare IPv6 literal in brackets before it
// is interpolated back into a "host:port" position inside a URI (the
// convert_outbound_to_uri direction). Without this, "2001:db8::1:8388"
// would be ambiguous / wrongly split by any later host:port parser.
function bracketIPv6(address) {
    if (typeof address === 'string' && address.indexOf(':') !== -1 && address.charAt(0) !== '[') {
        return `[${address}]`;
    }
    return address;
}

// Some subscription generators / third-party tools emit wg://, hysteria2://
// (and occasionally vless/trojan/socks/http) URIs with a bare IPv6 host —
// no brackets — e.g. "wg://key@2606:4700::1:51820". The WHATWG URL parser
// cannot make sense of that (ambiguous colons) and throws immediately, so
// the node is rejected outright. Detect that shape and add brackets before
// handing the URI to `new URL()`, so these still parse instead of failing.
// A correctly-formed URI (already bracketed, or a plain domain/IPv4 host
// with at most one colon for the port) is returned unchanged.
function normalizeUriIPv6Host(uri) {
    const m = uri.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/(?:[^@/?#]*@)?)([^/?#]+)([/?#].*)?$/);
    if (!m) return uri;
    const prefix = m[1];
    const authority = m[2];
    const rest = m[3] || '';
    if (authority.charAt(0) === '[' || (authority.match(/:/g) || []).length < 2) return uri;
    // Split off a trailing :port (1-5 digits) if present; the remainder is the host.
    const portMatch = authority.match(/^(.*):(\d{1,5})$/);
    const host = portMatch ? portMatch[1] : authority;
    const port = portMatch ? `:${portMatch[2]}` : '';
    return `${prefix}[${host}]${port}${rest}`;
}


/**
 * Build an Xray config for a 2-hop proxy chain.
 *
 * Data flow:
 *   Client → [proxy-hop1 outbound] → Hop1 server → [proxy-hop2 outbound] → Hop2 server → Internet
 *
 * Xray mechanism: dialerProxy
 *   - proxy-hop2 has  sockopt.dialerProxy = "proxy-hop1"
 *     → its TCP/UDP connection is made *through* the hop1 outbound
 *   - proxy-hop1 has  sockopt.dialerProxy = "direct"   (normal behaviour)
 *
 * The resulting outbounds array is:
 *   [ proxy-hop2 (tagged "proxy"),  proxy-hop1 (tagged "proxy-hop1"),  freedom "direct" ]
 *
 * The routing rule sends all traffic to "proxy" (hop2), which in turn
 * dials through hop1 automatically.
 */
function convert_chain_uris_to_xray_json(hop1Uri, hop2Uri, optional_settings) {
    // Parse each hop individually — reuse existing single-URI logic
    const hop1ConfigStr = convert_uri_to_xray_json(hop1Uri, optional_settings);
    const hop2ConfigStr = convert_uri_to_xray_json(hop2Uri, optional_settings);

    let hop1Config, hop2Config;
    try { hop1Config = JSON.parse(hop1ConfigStr); } catch(e) { return hop1ConfigStr; }
    try { hop2Config = JSON.parse(hop2ConfigStr); } catch(e) { return hop2ConfigStr; }

    if (hop1Config.error) return hop1ConfigStr;
    if (hop2Config.error) return hop2ConfigStr;

    // Extract the outbound objects parsed by convert_uri_to_xray_json
    // (first element is always the proxy outbound)
    const hop1Out = hop1Config.outbounds[0];
    const hop2Out = hop2Config.outbounds[0];

    // Tag the two hops distinctly
    hop1Out.tag = "proxy-hop1";
    hop2Out.tag = "proxy";

    // Hop1 dials directly to the internet
    hop1Out.streamSettings = hop1Out.streamSettings || {};
    hop1Out.streamSettings.sockopt = hop1Out.streamSettings.sockopt || {};
    hop1Out.streamSettings.sockopt.mark = 255;
    hop1Out.streamSettings.sockopt.dialerProxy = "direct";

    // Hop2 dials through hop1
    hop2Out.streamSettings = hop2Out.streamSettings || {};
    hop2Out.streamSettings.sockopt = hop2Out.streamSettings.sockopt || {};
    hop2Out.streamSettings.sockopt.mark = 255;
    hop2Out.streamSettings.sockopt.dialerProxy = "proxy-hop1";

    // Base the final config on hop2's full config (log/dns/inbounds/routing
    // are identical between the two single-hop conversions since both were
    // built from the same optional_settings) and swap in the two-hop
    // outbound chain ahead of hop2's own direct/block outbounds.
    const finalConfig = hop2Config;
    finalConfig.outbounds = [hop2Out, hop1Out, ...hop2Config.outbounds.slice(1)];

    return JSON.stringify(finalConfig, null, 2);
}

// Converts the user-editable Routing Settings list (advSettings.routingRules)
// into Xray "field" routing rule objects. Only domain/ip matching is supported
// (no process/package name yet). Disabled rules are skipped entirely.
function _buildCustomRoutingRules(routingRules) {
    if (!Array.isArray(routingRules)) return [];

    const splitCsv = v => (typeof v === 'string' ? v : '')
        .split(',')
        .map(s => s.trim())
        .filter(Boolean);

    return routingRules
        .filter(r => r && r.enabled !== false)
        .map(r => {
            const rule = { "type": "field" };
            const domain = splitCsv(r.domain);
            const ip = splitCsv(r.ip);
            const protocol = splitCsv(r.protocol);

            if (domain.length) rule.domain = domain;
            if (ip.length) rule.ip = ip;
            if (r.port && String(r.port).trim()) rule.port = String(r.port).trim();
            if (r.network && String(r.network).trim()) rule.network = String(r.network).trim();
            if (protocol.length) rule.protocol = protocol;
            rule.outboundTag = r.outboundTag || "proxy";

            return rule;
        })
        // A rule with no matching conditions at all would be a no-op (or worse,
        // an accidental catch-all) — drop it defensively.
        .filter(rule => rule.domain || rule.ip || rule.port || rule.protocol);
}

// FinalMask (streamSettings.finalmask) is network-agnostic — it sits next to
// tcpSettings/tlsSettings, not inside them — so it is carried in links as one
// JSON blob rather than per-transport params: the `finalmask` query param for
// vless/trojan/ss/hysteria2, and the `finalmask` key inside the vmess JSON.
// Pulled out here once instead of in every protocol branch below.
function _extractFinalMaskFromUri(uri) {
    try {
        if (/^vmess:\/\//i.test(uri)) {
            const payload = uri.substring(8).split('#')[0];
            const json = tryDecodeBase64(payload);
            if (!json) return null;
            const c = JSON.parse(json);
            if (!c || !c.finalmask) return null;
            return typeof c.finalmask === 'string' ? JSON.parse(c.finalmask) : c.finalmask;
        }
        const qIdx = uri.indexOf('?');
        if (qIdx === -1) return null;
        const hashIdx = uri.indexOf('#');
        const qEnd = (hashIdx !== -1 && hashIdx > qIdx) ? hashIdx : uri.length;
        const raw = new URLSearchParams(uri.substring(qIdx + 1, qEnd)).get('finalmask');
        if (!raw) return null;
        return JSON.parse(raw);
    } catch (e) {
        // Malformed finalmask must never take the whole node down — the node
        // still works, just without the extra masking layer.
        return null;
    }
}

// Merge a user-supplied FinalMaskObject onto whatever a protocol branch already
// produced (Hysteria2's obfs builds finalmask.udp = [salamander] on its own).
// The user's tcp/udp layers are appended after the built-in ones so the
// innermost-first ordering of the array is preserved, and quicParams is a
// shallow field-wise merge with the user's values winning.
function _mergeFinalMask(streamSettings, userMask) {
    if (!userMask || typeof userMask !== 'object' || Array.isArray(userMask)) return;
    const current = streamSettings.finalmask || {};
    const merged = { ...current };

    ['tcp', 'udp'].forEach(k => {
        const add = userMask[k];
        if (!Array.isArray(add) || add.length === 0) return;
        merged[k] = Array.isArray(current[k]) ? current[k].concat(add) : add.slice();
    });

    if (userMask.quicParams && typeof userMask.quicParams === 'object') {
        merged.quicParams = { ...(current.quicParams || {}), ...userMask.quicParams };
    }

    // Carry through any other top-level key verbatim for forward compatibility
    // with finalmask fields this UI doesn't model yet.
    Object.keys(userMask).forEach(k => {
        if (k !== 'tcp' && k !== 'udp' && k !== 'quicParams') merged[k] = userMask[k];
    });

    if (Object.keys(merged).length > 0) streamSettings.finalmask = merged;
}

// dns.queryStrategy. Honors the explicit value; settings saved by older
// versions only have the boolean `preferIpv6`, so fall back to what that
// flag used to produce (UseIPv6 / UseIPv4) and keep behavior unchanged.
function resolveDnsQueryStrategy(settings) {
    if (settings && DNS_QUERY_STRATEGIES.includes(settings.queryStrategy)) return settings.queryStrategy;
    return (settings && settings.preferIpv6) ? "UseIPv6" : "UseIPv4";
}

// Xray dns.* engine options. Only keys that differ from Xray's own defaults
// are emitted (so older cores that don't know newer fields such as
// serveStale / enableParallelQuery / useSystemHosts never see them), except
// enableParallelQuery, whose default here is ON while Xray's is OFF.
function buildDnsEngineOptions(settings) {
    const s = settings || {};
    const o = {};
    const disableCache = s.dnsDisableCache === true;
    const disableFallback = s.dnsDisableFallback === true;
    if (disableCache) o.disableCache = true;
    // serveStale is constrained by disableCache, so it is meaningless then.
    if (!disableCache && s.dnsServeStale === true) {
        o.serveStale = true;
        const ttl = parseInt(s.dnsServeExpiredTTL, 10);
        if (ttl > 0) o.serveExpiredTTL = ttl;
    }
    if (disableFallback) o.disableFallback = true;
    // disableFallbackIfMatch is redundant once fallback is fully disabled.
    if (!disableFallback && s.dnsDisableFallbackIfMatch === true) o.disableFallbackIfMatch = true;
    if (s.dnsParallelQuery !== false) o.enableParallelQuery = true;
    if (s.dnsUseSystemHosts === true) o.useSystemHosts = true;
    return o;
}

// Tag applied to Xray's own internal DNS client (dns.tag in the DnsObject).
// Every network request the DNS module makes to an upstream server on its
// own behalf (as opposed to a client's port-53 packet arriving at
// socks-test-in/tun-in) is stamped with this inboundTag, which lets
// _buildDnsUpstreamRoutingRules() route each configured upstream
// individually instead of lumping them all into one proxy/direct choice.
// Note: an upstream configured with a "+local" scheme (https+local://,
// tcp+local://, quic+local://) bypasses Xray's dispatcher/routing entirely
// and dials out via the OS directly — it can never be matched by a routing
// rule, tagged or not, so it is intentionally skipped below.
const DNS_MODULE_TAG = "dns_out";

// Extracts a routable {ip} or {domain} target from one configured DNS
// server entry (a bare IP, a bare hostname, or a "scheme://host[:port]/..."
// URL such as a DoH endpoint). Returns null for anything that can't be
// matched by a routing rule (empty value, or a "+local" scheme).
function _dnsServerRouteTarget(addr) {
    let host = String(addr || '').trim();
    if (!host) return null;

    const schemeMatch = host.match(/^([a-zA-Z][a-zA-Z0-9+.-]*):\/\//);
    if (schemeMatch) {
        if (schemeMatch[1].toLowerCase().includes('+local')) return null;
        try {
            host = new URL(host).hostname;
        } catch (e) {
            return null;
        }
    }

    host = unwrapIPv6(host);
    if (!host) return null;
    return isIpAddr(host) ? { ip: host } : { domain: host };
}

// Builds one "field" routing rule per configured DNS upstream so that the
// DNS module's own upstream queries are split the same way the resolution
// itself already is: domesticDns always goes direct, foreignDns/vpnDns go
// via settings.dnsViaProxy — instead of every upstream sharing one blanket
// choice. Only meaningful in "Local DNS" (structured) mode, since legacy
// mode has no domestic/foreign distinction to preserve.
function _buildDnsUpstreamRoutingRules(settings, useFakeIp) {
    if (!settings || !settings.localDns) return [];

    const rules = [];
    const pushRule = (addr, outboundTag) => {
        const target = _dnsServerRouteTarget(addr);
        if (!target) return;
        rules.push({
            "type": "field",
            "inboundTag": [DNS_MODULE_TAG],
            "outboundTag": outboundTag,
            ...(target.ip ? { "ip": [target.ip] } : { "domain": [target.domain] })
        });
    };

    if (settings.domesticDns && settings.domesticDns.trim()) {
        pushRule(settings.domesticDns.trim(), "direct");
    }

    const foreignOutboundTag = settings.dnsViaProxy ? "proxy" : "direct";
    splitDnsList(settings.foreignDns).forEach(addr => pushRule(addr, foreignOutboundTag));

    if (!useFakeIp && settings.vpnDns && settings.vpnDns.trim()) {
        pushRule(settings.vpnDns.trim(), foreignOutboundTag);
    }

    return rules;
}

function convert_uri_to_xray_json(uri, optional_settings) {
    const settings = optional_settings || {
        loglevel: "none",
        sniffing: true,
        routeOnly: false,
        queryStrategy: "UseIPv4",
        mux: false,
        mux_connections: 8,
        fragment: false,
        fragment_packets: "tlshello",
        fragment_length: "50-100",
        fragment_interval: "10-20",
        mtu: 1350,
        pinnedPeerCertSha256: "",
        dnsViaProxy: true,
        hijackDns: false,
        dnsDisableCache: false,
        dnsServeStale: false,
        dnsServeExpiredTTL: 0,
        dnsDisableFallback: false,
        dnsDisableFallbackIfMatch: false,
        dnsParallelQuery: true,
        dnsUseSystemHosts: false,
        localDns: false,
        fakeDnsLocal: false,
        domainStrategy: "auto",
        vpnDns: "1.1.1.1",
        foreignDns: DEFAULT_FOREIGN_DNS,
        domesticDns: "223.5.5.5",
        routingRules: []
    };

    let outbound = null;
    uri = uri.trim();
    uri = normalizeUriIPv6Host(uri);

    // Built-in "no proxy" node (freedom://…): xray-core is used purely as a
    // router. The `proxy` outbound is a freedom outbound, so both `proxy` and
    // `direct` egress on the underlying network; mark 255 keeps its own
    // sockets out of the tun redirect, exactly like the real `direct`
    // outbound built further down.
    const isDirectOnly = /^freedom:\/\//i.test(uri);

    try {
        if (isDirectOnly) {
            outbound = {
                tag: "proxy",
                protocol: "freedom",
                streamSettings: {
                    sockopt: {
                        mark: 255,
                        "domainStrategy": "UseIP"
                    }
                }
            };
        } else if (uri.startsWith('vmess://')) {
            // Strip any #remark fragment before decoding — some providers append one.
            const vmessPayload = uri.substring(8).split('#')[0];
            const vmessJson = tryDecodeBase64(vmessPayload);
            if (!vmessJson) throw new Error("Cannot parse VMESS Base64");
            const c = JSON.parse(vmessJson);
            if (!c) throw new Error("Cannot parse VMESS Base64");
            
            outbound = {
                tag: "proxy",
                protocol: "vmess",
                settings: {
                    vnext: [{
                        address: c.add,
                        port: +c.port,
                        users: [{ 
                            id: c.id, 
                            alterId: +c.aid || 0 
                        }]
                    }]
                },
                streamSettings: {
                    network: c.net || "tcp",
                    security: c.tls || "none",
                    sockopt: { mark: 255, "dialerProxy": "direct" }
                }
            };

            if (c.tls === 'tls') {
                outbound.streamSettings.tlsSettings = {
                    serverName: c.sni || "",
                    alpn: c.alpn ? c.alpn.split(',') : undefined,
                    // "allowInsecure" was removed by Xray-core; never emit it into config.json.
                    ...(c.ech ? { echConfigList: c.ech } : {})
                };
                const nodePcs = c.pcs || settings.pinnedPeerCertSha256;
                if (nodePcs) {
                    outbound.streamSettings.tlsSettings.pinnedPeerCertSha256 = nodePcs;
                }
            }

            const vmessNet = c.net || "tcp";

            if (vmessNet === 'tcp') {
                if (c.type && c.type !== 'none') {
                    const tcpHeader = { type: c.type };
                    if (c.type === 'http') {
                        tcpHeader.request = {
                            path: c.path ? c.path.split(',') : ["/"],
                            headers: c.host ? { Host: c.host.split(',') } : {}
                        };
                    }
                    outbound.streamSettings.tcpSettings = { header: tcpHeader };
                }
            } else if (vmessNet === 'kcp' || vmessNet === 'mkcp') {
                outbound.streamSettings.kcpSettings = {
                    header: { type: c.type || "none" },
                    ...(c.seed ? { seed: c.seed } : {})
                };
            } else if (vmessNet === 'ws') {
                outbound.streamSettings.wsSettings = {
                    path: c.path || "/",
                    headers: { Host: c.host || "" }
                };
            } else if (vmessNet === 'httpupgrade') {
                outbound.streamSettings.httpupgradeSettings = {
                    path: c.path || "/",
                    host: c.host || ""
                };
            } else if (vmessNet === 'xhttp' || vmessNet === 'splithttp') {
                const xhttpSettings = {
                    path: c.path || "/",
                    host: c.host || ""
                };
                if (c.mode && c.mode !== 'auto') xhttpSettings.mode = c.mode;
                if (c.extra) { try { Object.assign(xhttpSettings, typeof c.extra === 'string' ? JSON.parse(c.extra) : c.extra); } catch(e) {} }
                outbound.streamSettings.xhttpSettings = xhttpSettings;
            } else if (vmessNet === 'h2' || vmessNet === 'http') {
                outbound.streamSettings.httpSettings = {
                    path: c.path || "/",
                    host: c.host ? c.host.split(',').map(h => h.trim()) : []
                };
            } else if (vmessNet === 'grpc') {
                outbound.streamSettings.grpcSettings = {
                    serviceName: c.path || "",
                    multiMode: c.mode === 'multi',
                    ...(c.authority ? { authority: c.authority } : {})
                };
            } else if (vmessNet === 'quic') {
                outbound.streamSettings.quicSettings = {
                    header: { type: c.type || "none" }
                };
            }
        }
        else if (uri.startsWith('vless://') || uri.startsWith('trojan://')) {
            const proto = uri.startsWith('vless://') ? 'vless' : 'trojan';
            // Fix parser on old Chrome
            const fakeHttpUri = uri.replace(/^(vless|trojan):\/\//i, 'https://');
            const u = new URL(fakeHttpUri);
            const p = new URLSearchParams(u.search);
            const user = decodeURIComponent(u.username);
            const host = unwrapIPv6(u.hostname);
            const port = +u.port || 443;
            const net = p.get('type') || 'tcp';
            const sec = p.get('security') || 'none';

            outbound = {
                tag: "proxy",
                protocol: proto,
                settings: proto === 'trojan' 
                    ? { servers: [{ address: host, port, password: user }] }
                    // VLESS Encryption (post-quantum, XTLS/Xray-core#5067): the
                    // outbound-side counterpart of the inbound `decryption`
                    // field. Empty/absent means the feature is off — same as
                    // the previous hardcoded "none".
                    : { vnext: [{ address: host, port, users: [{ id: user, encryption: p.get('encryption') || "none", flow: p.get('flow') || undefined }] }] },
                streamSettings: { 
                    network: net, 
                    security: sec,
                    sockopt: { mark: 255, "dialerProxy": "direct" }
                }
            };

            if (sec === 'tls' || sec === 'reality') {
                if (sec === 'reality') {
                    outbound.streamSettings.realitySettings = {
                        serverName: p.get('sni') || "",
                        fingerprint: p.get('fp') || "chrome",
                        publicKey: p.get('pbk') || "",
                        shortId: p.get('sid') || "",
                        spiderX: p.get('spx') || "",
                        ...(p.get('pqv') ? { mldsa65Verify: p.get('pqv') } : {})
                    };
                } else {
                    outbound.streamSettings.tlsSettings = {
                        serverName: p.get('sni') || "",
                        alpn: p.get('alpn') ? p.get('alpn').split(',') : undefined,
                        fingerprint: p.get('fp') || undefined,
                        // "allowInsecure" was removed by Xray-core; never emit it into config.json.
                        ...(p.get('ech') ? { echConfigList: p.get('ech') } : {})
                    };
                    const nodePcs = p.get('pcs') || settings.pinnedPeerCertSha256;
                    if (nodePcs) {
                        outbound.streamSettings.tlsSettings.pinnedPeerCertSha256 = nodePcs;
                    }
                }
            }

            if (net === 'tcp') {
                const headerType = p.get('headerType') || 'none';
                if (headerType && headerType !== 'none') {
                    const tcpHeader = { type: headerType };
                    if (headerType === 'http') {
                        const httpPath = p.get('path') || '/';
                        const httpHost = p.get('host') || '';
                        tcpHeader.request = {
                            path: httpPath.split(','),
                            headers: httpHost ? { Host: httpHost.split(',') } : {}
                        };
                    }
                    outbound.streamSettings.tcpSettings = { header: tcpHeader };
                }
            } else if (net === 'kcp' || net === 'mkcp') {
                outbound.streamSettings.kcpSettings = {
                    header: { type: p.get('headerType') || 'none' },
                    ...(p.get('seed') ? { seed: p.get('seed') } : {})
                };
            } else if (net === 'ws') {
                outbound.streamSettings.wsSettings = {
                    path: p.get('path') || "/",
                    host: p.get('host') || ""
                };
            } else if (net === 'httpupgrade') {
                outbound.streamSettings.httpupgradeSettings = {
                    path: p.get('path') || "/",
                    host: p.get('host') || ""
                };
            } else if (net === 'xhttp' || net === 'splithttp') {
                const xhttpSettings = {
                    path: p.get('path') || "/",
                    host: p.get('host') || ""
                };
                const mode = p.get('mode');
                if (mode && mode !== 'auto') xhttpSettings.mode = mode;
                const extra = p.get('extra');
                if (extra) { try { Object.assign(xhttpSettings, JSON.parse(extra)); } catch(e) {} }
                outbound.streamSettings.xhttpSettings = xhttpSettings;
            } else if (net === 'h2' || net === 'http') {
                outbound.streamSettings.httpSettings = {
                    path: p.get('path') || "/",
                    host: p.get('host') ? p.get('host').split(',').map(h => h.trim()) : []
                };
            } else if (net === 'grpc') {
                outbound.streamSettings.grpcSettings = {
                    serviceName: p.get('serviceName') || p.get('path') || "",
                    multiMode: p.get('mode') === 'multi',
                    ...(p.get('authority') ? { authority: p.get('authority') } : {})
                };
            }
        }
        else if (uri.startsWith('ss://') || uri.startsWith('shadowsocks://')) {
            const schemeEnd = uri.indexOf('://') + 3;
            // Extract user info portion (before the @). Use the *last* @: a
            // plain-text (non-base64) userinfo whose password contains a
            // literal, un-percent-encoded '@' would otherwise get split at
            // the wrong position, corrupting both password and host.
            let atIdx = uri.lastIndexOf('@');
            // Legacy pre-SIP002 form: the ENTIRE "method:password@host:port"
            // is base64-encoded, so there's no literal '@' in the URI at
            // all (it's hidden inside the encoded blob). Decode the whole
            // payload first and locate the '@' inside that instead of
            // rejecting the URI outright.
            let decodedWhole = null;
            if (atIdx === -1) {
                const wholePayload = uri.substring(schemeEnd).replace(/#.*$/, '').replace(/\?.*$/, '');
                try { decodedWhole = decodeBase64(wholePayload); } catch {}
                if (!decodedWhole || decodedWhole.lastIndexOf('@') === -1) {
                    throw new Error("Invalid Shadowsocks URI: missing @");
                }
            }

            let method, password, ssHost, ssPort, ssQueryStr;
            if (decodedWhole !== null) {
                // decodedWhole = "method:password@host:port"
                const dAt = decodedWhole.lastIndexOf('@');
                const userPart = decodedWhole.substring(0, dAt);
                const hostPort = decodedWhole.substring(dAt + 1);
                const ci = userPart.indexOf(':');
                method = ci !== -1 ? userPart.substring(0, ci) : userPart;
                password = ci !== -1 ? userPart.substring(ci + 1) : "";
                const lastColon = hostPort.lastIndexOf(':');
                ssHost = unwrapIPv6(hostPort.substring(0, lastColon));
                ssPort = parseInt(hostPort.substring(lastColon + 1)) || 443;
                ssQueryStr = '';
            } else {
                const rawUserPart = uri.substring(schemeEnd, atIdx);
                // Try base64-decode first; fall back to plain text
                try {
                    const decoded = decodeBase64(rawUserPart);
                    if (decoded && decoded.includes(':')) {
                        const ci = decoded.indexOf(':');
                        method = decoded.substring(0, ci);
                        password = decoded.substring(ci + 1);
                    } else {
                        throw new Error("not base64 method:pass");
                    }
                } catch {
                    // Plain-text method:password (URL-encoded)
                    const plain = decodeURIComponent(rawUserPart);
                    const ci = plain.indexOf(':');
                    method = ci !== -1 ? plain.substring(0, ci) : plain;
                    password = ci !== -1 ? plain.substring(ci + 1) : "";
                }
                // Parse host:port from after-@ portion (strip fragment, keep query for plugin parsing)
                let afterAt = uri.substring(atIdx + 1).replace(/#.*$/, '');
                const qIdx = afterAt.indexOf('?');
                const hostPort = qIdx !== -1 ? afterAt.substring(0, qIdx) : afterAt;
                ssQueryStr = qIdx !== -1 ? afterAt.substring(qIdx + 1) : '';
                const lastColon = hostPort.lastIndexOf(':');
                ssHost = unwrapIPv6(hostPort.substring(0, lastColon));
                ssPort = parseInt(hostPort.substring(lastColon + 1)) || 443;
            }

            outbound = {
                tag: "proxy",
                protocol: "shadowsocks",
                settings: {
                    servers: [{
                        address: ssHost,
                        port: ssPort,
                        method: method,
                        password: password || ""
                    }]
                },
                streamSettings: {
                    network: "tcp",
                    sockopt: { mark: 255, "dialerProxy": "direct" }
                }
            };

            if (ssQueryStr) {
                const ssParams = new URLSearchParams(ssQueryStr);
                const netType = ssParams.get('type');

                if (netType) {
                    // Extended format (this project's own convention, same shape as
                    // vless/trojan): ?type=...&security=...&... — supports the full
                    // range of Xray transports (tcp/kcp/ws/httpupgrade/xhttp/h2/grpc)
                    // and security layers (tls/reality) for Shadowsocks outbounds.
                    const net = netType || 'tcp';
                    const sec = ssParams.get('security') || 'none';
                    outbound.streamSettings.network = net;
                    outbound.streamSettings.security = sec;

                    if (sec === 'tls' || sec === 'reality') {
                        if (sec === 'reality') {
                            outbound.streamSettings.realitySettings = {
                                serverName: ssParams.get('sni') || "",
                                fingerprint: ssParams.get('fp') || "chrome",
                                publicKey: ssParams.get('pbk') || "",
                                shortId: ssParams.get('sid') || "",
                                spiderX: ssParams.get('spx') || "",
                                ...(ssParams.get('pqv') ? { mldsa65Verify: ssParams.get('pqv') } : {})
                            };
                        } else {
                            outbound.streamSettings.tlsSettings = {
                                serverName: ssParams.get('sni') || "",
                                alpn: ssParams.get('alpn') ? ssParams.get('alpn').split(',') : undefined,
                                fingerprint: ssParams.get('fp') || undefined,
                                // "allowInsecure" was removed by Xray-core; never emit it into config.json.
                                ...(ssParams.get('ech') ? { echConfigList: ssParams.get('ech') } : {})
                            };
                            const nodePcs = ssParams.get('pcs') || settings.pinnedPeerCertSha256;
                            if (nodePcs) {
                                outbound.streamSettings.tlsSettings.pinnedPeerCertSha256 = nodePcs;
                            }
                        }
                    }

                    if (net === 'tcp') {
                        const headerType = ssParams.get('headerType') || 'none';
                        if (headerType && headerType !== 'none') {
                            const tcpHeader = { type: headerType };
                            if (headerType === 'http') {
                                const httpPath = ssParams.get('path') || '/';
                                const httpHost = ssParams.get('host') || '';
                                tcpHeader.request = {
                                    path: httpPath.split(','),
                                    headers: httpHost ? { Host: httpHost.split(',') } : {}
                                };
                            }
                            outbound.streamSettings.tcpSettings = { header: tcpHeader };
                        }
                    } else if (net === 'kcp' || net === 'mkcp') {
                        outbound.streamSettings.kcpSettings = {
                            header: { type: ssParams.get('headerType') || 'none' },
                            ...(ssParams.get('seed') ? { seed: ssParams.get('seed') } : {})
                        };
                    } else if (net === 'ws') {
                        outbound.streamSettings.wsSettings = {
                            path: ssParams.get('path') || "/",
                            host: ssParams.get('host') || ""
                        };
                    } else if (net === 'httpupgrade') {
                        outbound.streamSettings.httpupgradeSettings = {
                            path: ssParams.get('path') || "/",
                            host: ssParams.get('host') || ""
                        };
                    } else if (net === 'xhttp' || net === 'splithttp') {
                        const xhttpSettings = {
                            path: ssParams.get('path') || "/",
                            host: ssParams.get('host') || ""
                        };
                        const mode = ssParams.get('mode');
                        if (mode && mode !== 'auto') xhttpSettings.mode = mode;
                        const extra = ssParams.get('extra');
                        if (extra) { try { Object.assign(xhttpSettings, JSON.parse(extra)); } catch(e) {} }
                        outbound.streamSettings.xhttpSettings = xhttpSettings;
                    } else if (net === 'h2' || net === 'http') {
                        outbound.streamSettings.httpSettings = {
                            path: ssParams.get('path') || "/",
                            host: ssParams.get('host') ? ssParams.get('host').split(',').map(h => h.trim()) : []
                        };
                    } else if (net === 'grpc') {
                        outbound.streamSettings.grpcSettings = {
                            serviceName: ssParams.get('serviceName') || ssParams.get('path') || "",
                            multiMode: ssParams.get('mode') === 'multi',
                            ...(ssParams.get('authority') ? { authority: ssParams.get('authority') } : {})
                        };
                    }
                } else {
                    // Legacy SIP003 plugin= convention (e.g.
                    // plugin=v2ray-plugin;tls;host=...;path=...), still understood
                    // for backward compatibility with subscriptions from other
                    // panels. Xray-core doesn't spawn the plugin binary; instead the
                    // plugin's underlying transport (websocket, optionally over
                    // TLS) is expressed natively via streamSettings.
                    const pluginStr = ssParams.get('plugin');
                    if (pluginStr) {
                        const pluginParts = pluginStr.split(';');
                        const pluginName = pluginParts[0];
                        if (pluginName === 'v2ray-plugin') {
                            const pOpts = {};
                            for (let i = 1; i < pluginParts.length; i++) {
                                const seg = pluginParts[i];
                                if (!seg) continue;
                                const eqIdx = seg.indexOf('=');
                                if (eqIdx === -1) {
                                    pOpts[seg] = true;
                                } else {
                                    pOpts[seg.substring(0, eqIdx)] = seg.substring(eqIdx + 1);
                                }
                            }
                            const pluginNet = pOpts.mode === 'quic' ? 'quic' : 'ws';
                            outbound.streamSettings.network = pluginNet;
                            if (pOpts.tls) {
                                outbound.streamSettings.security = 'tls';
                                outbound.streamSettings.tlsSettings = {
                                    serverName: pOpts.host || ssHost
                                    // "allowInsecure" was removed by Xray-core; never emit it into config.json.
                                };
                                if (settings.pinnedPeerCertSha256) {
                                    outbound.streamSettings.tlsSettings.pinnedPeerCertSha256 = settings.pinnedPeerCertSha256;
                                }
                            }
                            if (pluginNet === 'ws') {
                                outbound.streamSettings.wsSettings = {
                                    path: pOpts.path || '/',
                                    headers: pOpts.host ? { Host: pOpts.host } : {}
                                };
                            } else if (pluginNet === 'quic') {
                                outbound.streamSettings.quicSettings = {
                                    header: { type: 'none' }
                                };
                            }
                        }
                    }
                }
            }
        }
        else if (uri.startsWith('wg://') || uri.startsWith('wireguard://')) {
            // Fix parser on old Chrome
            const fakeHttpUri = uri.replace(/^(wg|wireguard):\/\//i, 'https://');
            const u = new URL(fakeHttpUri);
            const p = new URLSearchParams(u.search);
            
            outbound = {
                tag: "proxy",
                protocol: "wireguard",
                settings: {
                    secretKey: decodeURIComponent(u.username + (u.password ? ':' + u.password : '')),
                    peers: [{
                        endpoint: `${u.hostname}:${u.port || 443}`,
                        publicKey: p.get('publickey') || p.get('public_key') || p.get('pk') || "",
                        ...(p.get('presharedkey') || p.get('preshared_key') ? {
                            preSharedKey: p.get('presharedkey') || p.get('preshared_key')
                        } : {})
                    }],
                    mtu: parseInt(p.get('mtu')) || settings.mtu || 1420,
                    address: p.get('address') ? p.get('address').split(',') : ["10.0.0.2/32"]
                },
                streamSettings: {
                    sockopt: { mark: 255, "dialerProxy": "direct" }
                }
            };
            if (p.get('reserved')) {
                try {
                    outbound.settings.reserved = JSON.parse(p.get('reserved'));
                } catch {
                    outbound.settings.reserved = p.get('reserved').split(',').map(Number);
                }
            }
        }
        else if (uri.startsWith('hy2://') || uri.startsWith('hysteria2://')) {
            // Fix parser on old Chrome
            const fakeHttpUri = uri.replace(/^(hy2|hysteria2):\/\//i, 'https://');
            const u = new URL(fakeHttpUri);
            const p = new URLSearchParams(u.search);

            // Xray-core's "settings" block for protocol "hysteria" only carries
            // the connection target — auth/bandwidth/masquerade live under
            // streamSettings.hysteriaSettings instead.
            const hy2Settings = {
                version: 2,
                address: unwrapIPv6(u.hostname),
                port: +u.port || 443
            };

            const hySettings = {
                version: 2,
                auth: decodeURIComponent(u.username)
            };
            // udpIdleTimeout must be 2-600s if present; default matches Xray's own default of 60
            const udpIdleTimeout = parseInt(p.get('udpIdleTimeout')) || 60;
            hySettings.udpIdleTimeout = Math.min(600, Math.max(2, udpIdleTimeout));
            // Bandwidth hints (Xray expects strings like "100 mbps")
            if (p.get('down') || p.get('bandwidth')) {
                hySettings.down = p.get('down') || p.get('bandwidth');
            }
            if (p.get('up')) {
                hySettings.up = p.get('up');
            }
            // Port hopping: mport param carries the range (e.g. "20000-30000")
            if (p.get('mport')) {
                hySettings.udphop = {
                    port: p.get('mport'),
                    interval: parseInt(p.get('hopInterval')) || 30
                };
            }

            const hyTlsSettings = {
                serverName: p.get('sni') || p.get('peer') || unwrapIPv6(u.hostname)
            };
            const hyAlpn = p.get('alpn');
            if (hyAlpn) hyTlsSettings.alpn = hyAlpn.split(',').map(s => s.trim()).filter(Boolean);

            outbound = {
                tag: "proxy",
                protocol: "hysteria",
                settings: hy2Settings,
                streamSettings: {
                    network: "hysteria",
                    security: "tls",
                    tlsSettings: hyTlsSettings,
                    hysteriaSettings: hySettings,
                    sockopt: { mark: 255, "dialerProxy": "direct" }
                }
            };

            // Obfuscation (Salamander). Accepts the SIP008/mihomo-style
            // ?obfs=salamander&obfs-password=... convention (also tolerating
            // the camelCase ?obfsPassword= variant this project's own edit
            // form emits/reads). This was previously ignored entirely, so a
            // Hysteria2 node configured with obfs silently ran WITHOUT
            // obfuscation — connecting fine to a plain server but failing
            // (or exposing the traffic pattern) against an obfs-only one.
            //
            // Xray-core used to expose this as a flat streamSettings.udpmasks
            // array; that field has since been folded into the unified
            // "finalmask" packet-masking subsystem, which nests UDP masks
            // under streamSettings.finalmask.udp (alongside a sibling "tcp"
            // array and a "quicParams" block for congestion/udpHop). V2RayNG
            // and current Xray-core builds only understand the new shape —
            // udpmasks is no longer read, so obfuscation silently vanished
            // even though this code thought it had set it.
            const obfsType = (p.get('obfs') || '').toLowerCase();
            const obfsPassword = p.get('obfs-password') || p.get('obfsPassword') || '';
            if (obfsPassword && (obfsType === 'salamander' || !obfsType)) {
                outbound.streamSettings.finalmask = {
                    udp: [{
                        type: 'salamander',
                        settings: { password: obfsPassword }
                    }]
                };
            }
        }
        else if (uri.startsWith('socks://') || uri.startsWith('socks5://')) {
            // Fix parser on old Chrome
            const fakeHttpUri = uri.replace(/^(socks5|socks):\/\//i, 'https://');
            const u = new URL(fakeHttpUri);
            
            outbound = {
                tag: "proxy",
                protocol: "socks",
                settings: {
                    servers: [{
                        address: unwrapIPv6(u.hostname),
                        port: +u.port || 443,
                        users: u.username ? [{
                            user: decodeURIComponent(u.username),
                            pass: decodeURIComponent(u.password || "")
                        }] : undefined
                    }]
                },
                streamSettings: {
                    network: "tcp",
                    sockopt: { mark: 255, "dialerProxy": "direct" }
                }
            };
        }
        else if (uri.startsWith('http://') || uri.startsWith('https://')) {
            const u = new URL(uri);
            
            outbound = {
                tag: "proxy",
                protocol: "http",
                settings: {
                    servers: [{
                        address: unwrapIPv6(u.hostname),
                        port: +u.port || (u.protocol === 'https:' ? 443 : 80),
                        users: u.username ? [{
                            user: decodeURIComponent(u.username),
                            pass: decodeURIComponent(u.password || "")
                        }] : undefined
                    }]
                },
                streamSettings: {
                    network: "tcp",
                    security: u.protocol === 'https:' ? "tls" : "none",
                    sockopt: { mark: 255, "dialerProxy": "direct" }
                }
            };
            if (u.protocol === 'https:') {
                outbound.streamSettings.tlsSettings = {
                    serverName: unwrapIPv6(u.hostname)
                };
            }
        }
    } catch (e) {
        return JSON.stringify({ error: "Unable to parse URI: " + e.message }, null, 2);
    }

    if (!outbound) {
        return JSON.stringify({ error: "Unsupported or malformed URI" }, null, 2);
    }

    // FinalMask — applied after every protocol branch so it works uniformly for
    // vmess/vless/trojan/ss/hysteria2, and merges with (rather than clobbers)
    // the salamander layer Hysteria2's obfs param may already have built.
    // None of the transport obfuscation layers below mean anything for a
    // freedom outbound, so the built-in no-proxy node skips them all.
    if (outbound.streamSettings && !isDirectOnly) {
        _mergeFinalMask(outbound.streamSettings, _extractFinalMaskFromUri(uri));
    }

    if (settings.mux && !isDirectOnly) {
        outbound.streamSettings.mux = {
            enabled: true,
            concurrency: parseInt(settings.mux_connections) || 8
        };
    }

    if (settings.fragment && !isDirectOnly) {
        outbound.streamSettings.sockopt.fragment = {
            packets: settings.fragment_packets || "tlshello",
            length: settings.fragment_length || "50-100",
            interval: settings.fragment_interval || "10-20"
        };
    }

    const dnsOutboundTag = settings.dnsViaProxy ? "proxy" : "direct";
    // Hijack DNS: client DNS (port 53 from tun-in / socks-test-in) goes to the
    // `dns` outbound so Xray's DNS module resolves it; the module's own
    // upstream queries (tagless, port 53) then leave via proxy/direct.
    const hijackDns = settings.hijackDns === true;

    // Resolve effective fakeip flag — new field (fakeDnsLocal) takes priority when
    // Local DNS is enabled; fall back to legacy fakeDns for backward compatibility.
    const useFakeIp = settings.localDns
        ? settings.fakeDnsLocal
        : false;

    // Sniffing destOverride. With Fake DNS on, apps connect to 198.18.0.0/15
    // addresses; "fakedns" lets the sniffer map such an IP back to the domain
    // it was allocated for, so routing rules and the proxy see the real name.
    // Only added when the FakeDNS pool exists (Xray refuses it otherwise).
    const sniffDestOverride = useFakeIp
        ? ["http", "tls", "quic", "fakedns"]
        : ["http", "tls", "quic"];

    let dnsServers;

    if (settings.localDns) {
        // Build a structured DNS server list from the explicit fields.
        dnsServers = [];

        // 1. FakeDNS entry — sits first so it intercepts all domain queries.
        // Xray only recognises the literal address "fakedns" for this; any
        // other string (e.g. "fakeip", the sing-box spelling) is treated as
        // a hostname and turned into a UDP DNS client for "<name>:53".
        // No expectIPs here: FakeDNS answers come from 198.18.0.0/15, which
        // is inside geoip:private, so "geoip:!private" would discard every
        // fake answer and silently fall through to the real resolvers.
        if (useFakeIp) {
            dnsServers.push({
                address: "fakedns",
                domains: ["regexp:.+"]
            });
        }

        // 2. Domestic DNS — for local/domestic domain resolution, routed direct.
        if (settings.domesticDns && settings.domesticDns.trim()) {
            dnsServers.push({
                address: settings.domesticDns.trim(),
                domains: ["geosite:cn", "geosite:private"],
                expectIPs: ["geoip:cn", "geoip:private"],
                skipFallback: true
            });
        }

        // 3. VPN DNS — only included when FakeIP is NOT active (grayed out in UI when FakeIP is on).
        if (!useFakeIp && settings.vpnDns && settings.vpnDns.trim()) {
            dnsServers.push({
                address: settings.vpnDns.trim(),
                domains: ["regexp:.+"]
            });
        }

        // 4. Foreign DNS — fallback for everything else. Accepts several
        // servers separated by commas; each becomes its own dns.servers entry.
        splitDnsList(settings.foreignDns).forEach(addr => dnsServers.push(addr));

        // Ensure there is always at least one server so Xray doesn't error out.
        if (dnsServers.length === 0) {
            dnsServers.push("1.1.1.1");
        }
    } else {
        // Legacy / simple mode: two hardcoded servers, optional fakedns prepend.
        // Copy first — unshift() on the shared LEGACY_DNS array would stack
        // one more fakedns entry on it every time a config is generated.
        dnsServers = [...LEGACY_DNS];
        if (useFakeIp) {
            dnsServers.unshift({
                address: "fakedns",
                domains: ["regexp:.+"]
            });
        }
    }

    // routing.domainStrategy: honor an explicit Xray strategy from the Routing
    // tab; anything else ("auto", missing on settings saved by older
    // versions, or a corrupted value) falls back to the original behavior.
    const ROUTING_DOMAIN_STRATEGIES = ["AsIs", "IPIfNonMatch", "IPOnDemand"];
    const routingDomainStrategy = ROUTING_DOMAIN_STRATEGIES.includes(settings.domainStrategy)
        ? settings.domainStrategy
        : (useFakeIp ? "AsIs" : "IPIfNonMatch");

    const fullConfig = {
        log: { 
            loglevel: settings.loglevel || "none" 
        }, 
        dns: {
            hosts: buildDnsHosts(settings),
            servers: dnsServers,
            queryStrategy: resolveDnsQueryStrategy(settings),
            ...buildDnsEngineOptions(settings),
            // Tags every upstream query the DNS module itself issues with
            // DNS_MODULE_TAG, so _buildDnsUpstreamRoutingRules() can route
            // each configured server individually below instead of every
            // upstream sharing one proxy/direct choice.
            tag: DNS_MODULE_TAG,
        },
        ...(useFakeIp ? { fakedns: buildFakeDnsPools(settings) } : {}),
        inbounds: [
            {
                "tag": "socks-test-in",
                "port": 808,
                "listen": "127.17.1.3",
                "protocol": "socks",
                "settings": {
                    "auth": "noauth",
                    "udp": true
                },
                "sniffing": {
                    "enabled": settings.sniffing,
                    "destOverride": sniffDestOverride,
                    "routeOnly": settings.routeOnly
                }
            },
            {
                // Real device-wide traffic path (replaces hev-socks5-tunnel).
                // openxtun opens xraytun0 and hands the fd to xray via
                // XRAY_TUN_FD; addr/mtu/up/default-route on that interface
                // are managed by service.sh (configure_tun_iface), so no
                // gateway/autoSystemRoutingTable/autoOutboundsInterface here.
                "tag": "tun-in",
                "protocol": "tun",
                "settings": {
                    "name": "xraytun0",
                    "mtu": 8500
                },
                "sniffing": {
                    "enabled": settings.sniffing,
                    "destOverride": sniffDestOverride,
                    "routeOnly": settings.routeOnly
                }
            },
        ],
        outbounds: [
            outbound, 
            { 
                "protocol": "freedom", 
                "tag": "direct",
                "streamSettings": {
                    "sockopt": { 
                        mark: 255,
                        "domainStrategy": "UseIP"
                    }
                }
            },
            {
                "protocol": "blackhole",
                "tag": "block",
                "settings": {
                    "response": { "type": "http" }
                }
            },
            ...(hijackDns ? [{ "protocol": "dns", "tag": "dns-out" }] : [])
        ],
        routing: {
            "domainStrategy": routingDomainStrategy,
            "rules": [
                // Narrow rule first (2 conditions): user DNS from socks-test-in
                // (manual test path) and tun-in (real device DNS, now that
                // traffic reaches xray via tun-in instead of hev bridging
                // into socks-test-in).
                {
                    "type": "field",
                    "inboundTag": [
                        "socks-test-in",
                        "tun-in",
                    ],
                    "port": 53,
                    // Hijack ON: hand the query to Xray's DNS module (dns-out).
                    "outboundTag": hijackDns ? "dns-out" : dnsOutboundTag
                },
                // Per-upstream rules (2b): route each configured domestic/
                // foreign DNS server's OWN outbound queries individually
                // (domestic -> direct, foreign -> dnsViaProxy) instead of
                // lumping every upstream into the single blanket rule below.
                // Only produced in "Local DNS" mode and only while hijack is
                // on — legacy mode has no domestic/foreign split to honor,
                // and with hijack off the DNS module never sees client
                // traffic in the first place.
                ...(hijackDns ? _buildDnsUpstreamRoutingRules(settings, useFakeIp) : []),
                // Wider rule third (1 condition): tagless internal DNS from Xray
                // itself (app/dns, no inboundTag — tag is a synthetic
                // "xray.system.*"), or any DNS_MODULE_TAG query not matched by a
                // more specific rule above (e.g. a "+local" upstream, whose
                // traffic never reaches the dispatcher/routing engine anyway,
                // or a server address not covered by the per-upstream rules).
                // Must sit BELOW both rules above so those still win first.
                //
                // This traffic is generated whenever Xray needs to resolve a
                // domain internally and no inboundTag is attached — e.g.
                // WireGuard resolving its peer endpoint before the tunnel is
                // up, or (for ANY outbound protocol) the routing engine
                // resolving a destination domain to evaluate IP-based rules
                // under domainStrategy "IPIfNonMatch"/"AsIs". Without this
                // rule such queries fall through every other rule (none of
                // which match a tagless packet) and land on Xray's *default*
                // outbound — outbounds[0], i.e. "proxy" — which is wrong:
                // it's Xray's own housekeeping DNS, not a domain the user
                // wants proxied, and can pointlessly route it through the
                // tunnel (or worse, contribute to a self-referential dial if
                // the tunnel itself is mid-setup). Send it direct
                // unconditionally, for every protocol, not just WireGuard.
                {
                    "type": "field",
                    "port": 53,
                    // Hijack ON: Xray's upstream DNS queries follow "Resolve DNS via
                    // proxy" (proxy/direct); otherwise always direct.
                    "outboundTag": hijackDns ? dnsOutboundTag : "direct"
                },
                ...(useFakeIp ? [{
                    "type": "field",
                    "ip": buildFakeDnsPools(settings).map(p => p.ipPool),
                    "outboundTag": "proxy"
                }] : []),
                // User-defined routing rules (Routing Settings tab). Evaluated in the
                // order the user arranged them, above the private-network bypass so a
                // custom rule can override it if the user explicitly wants to.
                ..._buildCustomRoutingRules(settings.routingRules),
                {
                    "type": "field",
                    "ip": [
                        "geoip:private"
                    ],
                    "domain": [
                        "geosite:private"
                    ],
                    "outboundTag": "direct"
                },
                {
                    "type": "field",
                    "inboundTag": [
                        "socks-test-in",
                        "tun-in",
                    ],
                    "network": "tcp,udp",
                    "outboundTag": "proxy"
                }
            ]
        }
    };

    return JSON.stringify(fullConfig, null, 2);
}

// Inverse of _extractFinalMaskFromUri: render streamSettings.finalmask back
// into the compact JSON blob that rides in a link. Layers already expressed by
// a dedicated param (Hysteria2's obfs=salamander) are dropped so a round trip
// doesn't stack the same mask twice.
function _serializeFinalMaskForUri(ss, opts) {
    const fm = ss && ss.finalmask;
    if (!fm || typeof fm !== 'object' || Array.isArray(fm)) return null;
    const out = {};

    ['tcp', 'udp'].forEach(k => {
        if (!Array.isArray(fm[k])) return;
        let arr = fm[k];
        if (k === 'udp' && opts && opts.dropUdpSalamander) {
            let dropped = false;
            arr = arr.filter(m => {
                if (!dropped && m && m.type === 'salamander') { dropped = true; return false; }
                return true;
            });
        }
        if (arr.length) out[k] = arr;
    });

    if (fm.quicParams && typeof fm.quicParams === 'object' && Object.keys(fm.quicParams).length) {
        out.quicParams = fm.quicParams;
    }
    Object.keys(fm).forEach(k => {
        if (k !== 'tcp' && k !== 'udp' && k !== 'quicParams') out[k] = fm[k];
    });

    return Object.keys(out).length ? JSON.stringify(out) : null;
}

function convert_outbound_to_uri(outbound) {
    const proto   = (outbound.protocol || '').toLowerCase();
    const ss      = outbound.streamSettings || {};
    const net     = ss.network || 'tcp';
    const sec     = ss.security || 'none';

    // Percent-encode everything except unreserved chars (RFC 3986)
    const pct = s => encodeURIComponent(String(s));

    // Build query string from a plain object (skips null/undefined values)
    function buildQuery(obj) {
        return Object.entries(obj)
            .filter(([, v]) => v !== null && v !== undefined && v !== '')
            .map(([k, v]) => `${k}=${pct(v)}`)
            .join('&');
    }

    try {
        if (proto === 'vmess') {
            const vnext = outbound.settings.vnext[0];
            const user  = vnext.users[0];
            const tls   = ss.tlsSettings || {};
            const netSettings = ss[`${net}Settings`] || ss.wsSettings || {};

            const obj = {
                v:    '2',
                ps:   '',
                add:  vnext.address,
                port: String(vnext.port),
                id:   user.id,
                aid:  String(user.alterId || 0),
                net,
                type: 'none',
                host: '',
                path: '',
                tls:  sec === 'tls' ? 'tls' : '',
                sni:  tls.serverName || '',
                alpn: tls.alpn ? tls.alpn.join(',') : ''
            };

            if (net === 'ws') {
                obj.path = (ss.wsSettings || {}).path || '/';
                obj.host = (ss.wsSettings || {}).headers?.Host || (ss.wsSettings || {}).host || '';
            } else if (net === 'httpupgrade') {
                obj.path = (ss.httpupgradeSettings || {}).path || '/';
                obj.host = (ss.httpupgradeSettings || {}).host || '';
            } else if (net === 'xhttp' || net === 'splithttp') {
                const xs = ss.xhttpSettings || {};
                obj.path = xs.path || '/';
                obj.host = xs.host || '';
                if (xs.mode) obj.mode = xs.mode;
            } else if (net === 'h2' || net === 'http') {
                const hs = ss.httpSettings || {};
                obj.path = hs.path || '/';
                obj.host = Array.isArray(hs.host) ? hs.host.join(',') : (hs.host || '');
            } else if (net === 'grpc') {
                const gs = ss.grpcSettings || {};
                obj.path = gs.serviceName || '';
                obj.mode = gs.multiMode ? 'multi' : 'gun';
            } else if (net === 'kcp' || net === 'mkcp') {
                const ks = ss.kcpSettings || {};
                obj.type = (ks.header || {}).type || 'none';
                if (ks.seed) obj.seed = ks.seed;
            } else if (net === 'tcp') {
                const tcpH = (ss.tcpSettings || {}).header || {};
                obj.type = tcpH.type || 'none';
                if (tcpH.type === 'http') {
                    obj.path = (tcpH.request?.path || []).join(',') || '/';
                    const hHost = tcpH.request?.headers?.Host;
                    obj.host = Array.isArray(hHost) ? hHost.join(',') : (hHost || '');
                }
            }

            const fmStr = _serializeFinalMaskForUri(ss);
            if (fmStr) { try { obj.finalmask = JSON.parse(fmStr); } catch (e) {} }

            return 'vmess://' + btoa(JSON.stringify(obj));
        }

        if (proto === 'vless' || proto === 'trojan') {
            let user, host, port;
            if (proto === 'vless') {
                const vnext = outbound.settings.vnext[0];
                user = vnext.users[0].id;
                host = vnext.address;
                port = vnext.port;
            } else {
                const srv = outbound.settings.servers[0];
                user = srv.password;
                host = srv.address;
                port = srv.port;
            }

            const q = {};
            q.type = net;
            q.security = sec;

            if (sec === 'tls') {
                const tls = ss.tlsSettings || {};
                if (tls.serverName)  q.sni = tls.serverName;
                if (tls.fingerprint) q.fp  = tls.fingerprint;
                if (tls.alpn?.length) q.alpn = tls.alpn.join(',');
            } else if (sec === 'reality') {
                const r = ss.realitySettings || {};
                if (r.serverName)  q.sni = r.serverName;
                if (r.fingerprint) q.fp  = r.fingerprint;
                if (r.publicKey)   q.pbk = r.publicKey;
                if (r.shortId)     q.sid = r.shortId;
                if (r.spiderX)     q.spx = r.spiderX;
                if (r.mldsa65Verify) q.pqv = r.mldsa65Verify;
            }

            if (net === 'ws') {
                const ws = ss.wsSettings || {};
                q.path = ws.path || '/';
                q.host = ws.headers?.Host || ws.host || '';
            } else if (net === 'httpupgrade') {
                const hu = ss.httpupgradeSettings || {};
                q.path = hu.path || '/';
                q.host = hu.host || '';
            } else if (net === 'xhttp' || net === 'splithttp') {
                const xs = ss.xhttpSettings || {};
                q.path = xs.path || '/';
                q.host = xs.host || '';
                if (xs.mode && xs.mode !== 'auto') q.mode = xs.mode;
            } else if (net === 'h2' || net === 'http') {
                const hs = ss.httpSettings || {};
                q.path = hs.path || '/';
                q.host = Array.isArray(hs.host) ? hs.host.join(',') : (hs.host || '');
            } else if (net === 'grpc') {
                const gs = ss.grpcSettings || {};
                q.serviceName = gs.serviceName || '';
                if (gs.multiMode) q.mode = 'multi';
                if (gs.authority) q.authority = gs.authority;
            } else if (net === 'kcp' || net === 'mkcp') {
                const ks = ss.kcpSettings || {};
                q.headerType = (ks.header || {}).type || 'none';
                if (ks.seed) q.seed = ks.seed;
            } else if (net === 'tcp') {
                const tcpH = (ss.tcpSettings || {}).header || {};
                if (tcpH.type && tcpH.type !== 'none') {
                    q.headerType = tcpH.type;
                    if (tcpH.type === 'http') {
                        q.path = (tcpH.request?.path || []).join(',') || '/';
                        const hHost = tcpH.request?.headers?.Host;
                        q.host = Array.isArray(hHost) ? hHost.join(',') : (hHost || '');
                    }
                }
            }

            if (proto === 'vless') {
                const flow = outbound.settings.vnext[0].users[0].flow;
                if (flow) q.flow = flow;
                const enc = outbound.settings.vnext[0].users[0].encryption;
                if (enc && enc !== 'none') q.encryption = enc;
            }

            const fmStr = _serializeFinalMaskForUri(ss);
            if (fmStr) q.finalmask = fmStr;

            const queryStr = buildQuery(q);
            const nodeTag = (proto === 'vless' ? 'VLESS Node' : 'Trojan Node');
            return `${proto}://${pct(user)}@${bracketIPv6(host)}:${port}?${queryStr}#${pct(nodeTag)}`;
        }

        if (proto === 'shadowsocks') {
            const srv = outbound.settings.servers[0];
            // userinfo = base64(method:password)  — SIP002 style
            const userInfo = btoa(`${srv.method}:${srv.password}`);
            const nodeTag = 'SS Node';
            // srv.address is the bare IPv6 form (see unwrapIPv6 on the parse
            // side) — re-bracket it before it lands in a host:port position.
            const ssHost = bracketIPv6(srv.address);

            // quic is legacy-only (never produced by the edit UI); keep routing it
            // through the old SIP003 plugin= form since Xray's own quic outbound
            // transport is effectively deprecated.
            if (net === 'quic') {
                const pluginParts = ['v2ray-plugin', 'mode=quic'];
                if (sec === 'tls') pluginParts.push('tls');
                const tlsS = ss.tlsSettings || {};
                if (tlsS.serverName) pluginParts.push(`host=${tlsS.serverName}`);
                const pluginQuery = `?plugin=${pct(pluginParts.join(';'))}`;
                return `ss://${userInfo}@${ssHost}:${srv.port}${pluginQuery}#${pct(nodeTag)}`;
            }

            // Every other transport (tcp/kcp/ws/httpupgrade/xhttp/h2/grpc) plus
            // tls/reality security is expressed the same way as vless/trojan —
            // this project's own parser understands these params natively, and
            // it covers cases (grpc, h2, httpupgrade, xhttp, tcp+http header,
            // mKCP obfuscation) that the old SIP003 plugin= convention never could.
            const q = {};
            q.type = net;
            q.security = sec;

            if (sec === 'tls') {
                const tls = ss.tlsSettings || {};
                if (tls.serverName)  q.sni = tls.serverName;
                if (tls.fingerprint) q.fp  = tls.fingerprint;
                if (tls.alpn?.length) q.alpn = tls.alpn.join(',');
            } else if (sec === 'reality') {
                const r = ss.realitySettings || {};
                if (r.serverName)  q.sni = r.serverName;
                if (r.fingerprint) q.fp  = r.fingerprint;
                if (r.publicKey)   q.pbk = r.publicKey;
                if (r.shortId)     q.sid = r.shortId;
                if (r.spiderX)     q.spx = r.spiderX;
                if (r.mldsa65Verify) q.pqv = r.mldsa65Verify;
            }

            if (net === 'ws') {
                const ws = ss.wsSettings || {};
                q.path = ws.path || '/';
                q.host = ws.headers?.Host || ws.host || '';
            } else if (net === 'httpupgrade') {
                const hu = ss.httpupgradeSettings || {};
                q.path = hu.path || '/';
                q.host = hu.host || '';
            } else if (net === 'xhttp' || net === 'splithttp') {
                const xs = ss.xhttpSettings || {};
                q.path = xs.path || '/';
                q.host = xs.host || '';
                if (xs.mode && xs.mode !== 'auto') q.mode = xs.mode;
            } else if (net === 'h2' || net === 'http') {
                const hs = ss.httpSettings || {};
                q.path = hs.path || '/';
                q.host = Array.isArray(hs.host) ? hs.host.join(',') : (hs.host || '');
            } else if (net === 'grpc') {
                const gs = ss.grpcSettings || {};
                q.serviceName = gs.serviceName || '';
                if (gs.multiMode) q.mode = 'multi';
                if (gs.authority) q.authority = gs.authority;
            } else if (net === 'kcp' || net === 'mkcp') {
                const ks = ss.kcpSettings || {};
                q.headerType = (ks.header || {}).type || 'none';
                if (ks.seed) q.seed = ks.seed;
            } else if (net === 'tcp') {
                const tcpH = (ss.tcpSettings || {}).header || {};
                if (tcpH.type && tcpH.type !== 'none') {
                    q.headerType = tcpH.type;
                    if (tcpH.type === 'http') {
                        q.path = (tcpH.request?.path || []).join(',') || '/';
                        const hHost = tcpH.request?.headers?.Host;
                        q.host = Array.isArray(hHost) ? hHost.join(',') : (hHost || '');
                    }
                }
            }

            const fmStr = _serializeFinalMaskForUri(ss);
            if (fmStr) q.finalmask = fmStr;

            const queryStr = buildQuery(q);
            const suffix = queryStr ? `?${queryStr}` : '';
            return `ss://${userInfo}@${ssHost}:${srv.port}${suffix}#${pct(nodeTag)}`;
        }

        if (proto === 'wireguard') {
            const cfg  = outbound.settings;
            const peer = (cfg.peers || [])[0] || {};

            // endpoint → host:port. Already bracketed if it's IPv6 and came
            // from this project's own parser; bracketIPv6() is a no-op in
            // that case and only kicks in for a raw, unbracketed IPv6
            // endpoint from some other source.
            const endpoint = peer.endpoint || 'unknown:51820';
            const lastColon = endpoint.lastIndexOf(':');
            const wgHost = bracketIPv6(endpoint.substring(0, lastColon));
            const wgPort = endpoint.substring(lastColon + 1);

            // secretKey — encode +, /, = per the WireGuard URI convention
            const secretKey = (cfg.secretKey || '').replace(/\+/g, '%2B').replace(/\//g, '%2F').replace(/=/g, '%3D');

            const q = {};
            if (peer.publicKey) {
                q.publickey = peer.publicKey.replace(/\+/g, '%2B').replace(/\//g, '%2F').replace(/=/g, '%3D');
            }
            if (peer.preSharedKey) {
                q.presharedkey = peer.preSharedKey;
            }
            if (cfg.address?.length) {
                // Each address may contain / and : — percent-encode the whole joined string
                q.address = pct(cfg.address.join(','));
            }
            if (cfg.mtu) q.mtu = cfg.mtu;
            if (cfg.reserved?.length) {
                q.reserved = pct(cfg.reserved.join(','));
            }
            if (cfg.dns) {
                q.dns = Array.isArray(cfg.dns) ? cfg.dns.join(',') : cfg.dns;
            }

            // Build query manually (publickey and address are already pct-encoded above)
            const queryStr = Object.entries(q)
                .filter(([, v]) => v !== null && v !== undefined && v !== '')
                .map(([k, v]) => `${k}=${v}`)
                .join('&');

            const nodeTag = 'WireGuard Profile';
            return `wireguard://${secretKey}@${wgHost}:${wgPort}/?${queryStr}#${pct(nodeTag)}`;
        }

        if (proto === 'hysteria' || proto === 'hysteria2') {
            const cfg = outbound.settings || {};
            const tls = ss.tlsSettings || {};
            const hy  = ss.hysteriaSettings || {};
            // Obfuscation lives in streamSettings.finalmask.udp (formerly
            // the flat streamSettings.udpmasks array, now folded into the
            // unified finalmask packet-masking subsystem) — not inside
            // hysteriaSettings. Read it back so a round-tripped/shared URI
            // doesn't silently drop obfs. Also fall back to the legacy
            // udpmasks field so configs saved before this fix still export.
            const salamander = ((ss.finalmask && ss.finalmask.udp) || ss.udpmasks || [])
                .find(m => m && m.type === 'salamander');

            const q = {};
            if (tls.serverName) q.sni = tls.serverName;
            if (tls.alpn?.length) q.alpn = tls.alpn.join(',');
            if (tls.allowInsecure) q.insecure = '1';
            if (hy.down) q.down = hy.down;
            if (hy.up)   q.up   = hy.up;
            if (hy.udpIdleTimeout) q.udpIdleTimeout = hy.udpIdleTimeout;
            if (hy.udphop) {
                q.mport = hy.udphop.port;
                if (hy.udphop.interval) q.hopInterval = hy.udphop.interval;
            }
            if (salamander && salamander.settings && salamander.settings.password) {
                q.obfs = 'salamander';
                q['obfs-password'] = salamander.settings.password;
            }
            const fmStr = _serializeFinalMaskForUri(ss, { dropUdpSalamander: !!salamander });
            if (fmStr) q.finalmask = fmStr;

            const queryStr = buildQuery(q);
            const nodeTag  = 'Hysteria2 Node';
            const auth     = pct(hy.auth || '');
            const hyHost   = bracketIPv6(cfg.address);
            return `hy2://${auth}@${hyHost}:${cfg.port}?${queryStr}#${pct(nodeTag)}`;
        }

        if (proto === 'socks') {
            const srv = outbound.settings.servers[0];
            const nodeTag = 'SOCKS Node';
            const socksHost = bracketIPv6(srv.address);
            if (srv.users?.length) {
                const u = srv.users[0];
                return `socks://${pct(u.user)}:${pct(u.pass)}@${socksHost}:${srv.port}#${pct(nodeTag)}`;
            }
            return `socks://${socksHost}:${srv.port}#${pct(nodeTag)}`;
        }
        if (proto === 'http') {
            const srv  = outbound.settings.servers[0];
            const scheme = sec === 'tls' ? 'https' : 'http';
            const nodeTag = 'HTTP Node';
            const httpHost = bracketIPv6(srv.address);
            if (srv.users?.length) {
                const u = srv.users[0];
                return `${scheme}://${pct(u.user)}:${pct(u.pass)}@${httpHost}:${srv.port}#${pct(nodeTag)}`;
            }
            return `${scheme}://${httpHost}:${srv.port}#${pct(nodeTag)}`;
        }

        return `Error: unsupported protocol "${proto}"`;

    } catch (e) {
        return 'Error: ' + e.message;
    }
}