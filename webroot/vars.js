const MODDIR = "/data/adb/modules/magic_v2ray";
const DATADIR = "/data/adb/magic_v2ray";
const PROFILES_FILE = `${DATADIR}/profiles.base64`;
const SETTINGS_FILE = `${DATADIR}/settings.base64`;
const ACTIVE_FILE = `${DATADIR}/active_config.txt`;
const CONFIG_JSON = `${DATADIR}/config.v2.json`;
const IP_HUNT_FILE = `${DATADIR}/ip_hunt.list`;
// Custom Hosts tab — a genuine /etc/hosts-syntax file, not an app-invented
// format: "IP  hostname [hostname2 ...]  [# comment]", one mapping per
// line, so it looks and edits like a real hosts file (and stays usable if
// someone opens it outside the app). File starts with HOSTS_HEADER_TEXT
// (the classic Windows sample header/comment block); everything the app
// itself writes lives after that.
//
// The whole file is read once (a plain `cat`) and parsed/serialized
// entirely in JavaScript (see parseHostsEntries/serializeHostsFile in
// helper.js) — no awk/sed/grep/sort on the shell side. Writing is a single
// writeFileB64() with the freshly-serialized text. Doing the text work in
// JS instead of chained shell utilities avoids the whole class of
// quoting/field-separator bugs those brought, and costs nothing extra:
// what used to freeze the WebView was rendering a huge blob of text into
// one DOM textarea, not holding a parsed array in memory.
const HOSTS_FILE = `${DATADIR}/hosts`;
const HOSTS_HEADER_TEXT = `# Copyright (c) 1993-2009 Microsoft Corp.
#
# This is a sample HOSTS file used by Microsoft TCP/IP for Windows.
#
# This file contains the mappings of IP addresses to host names. Each
# entry should be kept on an individual line. The IP address should
# be placed in the first column followed by the corresponding host name.
# The IP address and the host name should be separated by at least one
# space.
#
# Additionally, comments (such as these) may be inserted on individual
# lines or following the machine name denoted by a '#' symbol.
#
# For example:
#
#      102.54.94.97     rhino.acme.com          # source server
#       38.25.63.10     x.acme.com              # x client host

# localhost name resolution is handled within DNS itself.
#\t127.0.0.1       localhost
#\t::1             localhost

# ==== Managed by Magic V2ray below this line — edit from the Custom Hosts tab ====`;


// Comma-separated interface names whose outbound traffic skips Xray
// entirely — stored as a plain file rather than inside settings.base64,
// same pattern as IP_HUNT_FILE: existence of the file means the feature is
// enabled, and its content (re-read live by service.sh) is the list.
const BYPASS_IFACE_FILE = `${DATADIR}/bypassIface.txt`;
// Default User-Agent sent when fetching subscription links, so hosts that
// gate content on the client (e.g. v2rayNG-only subs) still respond.
// Per-subscription override lives in profiles[category].useragent.
const DEFAULT_SUB_USERAGENT = "v2rayNG/2.2.3";
const STUB_DIR = "/dev/sysctl_stubs";
const TIME_RES_FILE = `${STUB_DIR}/run/time_res`;
const ADDR_INFO_FILE = `${STUB_DIR}/run/addr_info`;
// Refreshed by the UI while the Latency tab is visible. The backend probe
// loop exits on its own once this stops being updated, so closing the WebUI
// can no longer leave a per-second curl running until reboot.
const LATENCY_HB_FILE = `${STUB_DIR}/run/latency.hb`;
 
let profiles = {};
// Raw text of CONFIG_JSON, kept in memory while professional mode is on so
// the textarea has something to show without a re-read on every keystroke.
let customConfigText = "";
let activeConfig = null;
let advSettings = {
    loglevel: "none",
    sniffing: true,
    routeOnly: false,
    // Xray routing.domainStrategy. "auto" keeps the pre-existing behavior
    // (AsIs while Fake DNS is on, IPIfNonMatch otherwise); the other values
    // are the literal Xray strategies. Resolved in convert_uri_to_xray_json().
    domainStrategy: "auto",
    enableIPv6: false,
    // Xray dns.queryStrategy: UseIP | UseIPv4 | UseIPv6 | UseSystem.
    // Replaces the old boolean `preferIpv6` (true -> UseIPv6, false -> UseIPv4);
    // see resolveDnsQueryStrategy() in helper.js for the migration.
    queryStrategy: "UseIPv4",
    networkMode: 0,
    allowTether: true,
    // Network tab. false (default): LAN/private/special-use destinations skip
    // Xray. true: they are sent into Xray too, except loopback (127.0.0.0/8
    // and ::1/128). Read by service.sh (setting_is_true includeLan).
    includeLan: false,
    mux: false,
    mux_connections: 8,
    fragment: false,
    fragment_packets: "tlshello",
    fragment_length: "50-100",
    fragment_interval: "10-20",
    mtu: 1350,
    pinnedPeerCertSha256: "",
    dnsViaProxy: true,
    // Hijack DNS (Traffic Settings). When true, port-53 traffic from tun-in /
    // socks-test-in is routed to a `dns` outbound (tag dns-out) so Xray's own
    // DNS module answers it, instead of forwarding the raw packets to
    // proxy/direct. Xray's upstream DNS queries then follow dnsViaProxy.
    // Default ON; settings saved by older versions have no value (treated as on).
    hijackDns: true,
    // Xray dns.* engine options (Traffic Settings > DNS Engine Options).
    // Built into the config by buildDnsEngineOptions() in helper.js.
    dnsDisableCache: false,
    dnsServeStale: false,
    dnsServeExpiredTTL: 0,
    dnsDisableFallback: false,
    dnsDisableFallbackIfMatch: false,
    dnsParallelQuery: true, // speed up DNS (Xray's own default is false)
    dnsUseSystemHosts: false,
    localDns: false,
    fakeDnsLocal: false,
    // Professional mode: when true, config.json is taken verbatim from
    // CONFIG_JSON instead of being generated from the selected node.
    proMode: false,
    vpnDns: "1.1.1.1",
    // Comma-separated list; defaults to every LEGACY_DNS entry (helper.js).
    foreignDns: DEFAULT_FOREIGN_DNS,
    domesticDns: "223.5.5.5",
    // Custom DNS hosts. Stored verbatim as the user typed it (JSON object or
    // /etc/hosts-style text) so the textarea round-trips exactly; parsing
    // and merging with DEFAULT_DNS_HOSTS happens at config-generation time
    // in buildDnsHosts() (helper.js).
    customHosts: "",
    routingRules: [
        {
            "remarks": "阻断udp443",
            "locked": false,
            "domain": "",
            "ip": "",
            "port": "443",
            "protocol": "",
            "network": "udp",
            "outboundTag": "block",
            "enabled": true
        },
        {
            "remarks": "代理Google",
            "locked": false,
            "domain": "geosite:google",
            "ip": "",
            "port": "",
            "protocol": "",
            "network": "",
            "outboundTag": "proxy",
            "enabled": true
        },
        {
            "remarks": "绕过局域网IP",
            "locked": false,
            "domain": "",
            "ip": "geoip:private",
            "port": "",
            "protocol": "",
            "network": "",
            "outboundTag": "direct",
            "enabled": true
        },
        {
            "remarks": "绕过局域网域名",
            "locked": false,
            "domain": "geosite:private",
            "ip": "",
            "port": "",
            "protocol": "",
            "network": "",
            "outboundTag": "direct",
            "enabled": true
        },
        {
            "remarks": "绕过中国公共DNSIP",
            "locked": false,
            "domain": "",
            "ip": "223.5.5.5,223.6.6.6,2400:3200::1,2400:3200:baba::1,119.29.29.29,1.12.12.12,120.53.53.53,2402:4e00::,2402:4e00:1::,180.76.76.76,2400:da00::6666,114.114.114.114,114.114.115.115,114.114.114.119,114.114.115.119,114.114.114.110,114.114.115.110,180.184.1.1,180.184.2.2,101.226.4.6,218.30.118.6,123.125.81.6,140.207.198.6,1.2.4.8,210.2.4.8,52.80.66.66,117.50.22.22,2400:7fc0:849e:200::4,2404:c2c0:85d8:901::4,117.50.10.10,52.80.52.52,2400:7fc0:849e:200::8,2404:c2c0:85d8:901::8,117.50.60.30,52.80.60.30",
            "port": "",
            "protocol": "",
            "network": "",
            "outboundTag": "direct",
            "enabled": false
        },
        {
            "remarks": "绕过中国公共DNS域名",
            "locked": false,
            "domain": "domain:alidns.com,domain:doh.pub,domain:dot.pub,domain:360.cn,domain:onedns.net",
            "ip": "",
            "port": "",
            "protocol": "",
            "network": "",
            "outboundTag": "direct",
            "enabled": false
        },
        {
            "remarks": "绕过中国IP",
            "locked": false,
            "domain": "",
            "ip": "geoip:cn",
            "port": "",
            "protocol": "",
            "network": "",
            "outboundTag": "direct",
            "enabled": false
        },
        {
            "remarks": "绕过中国域名",
            "locked": false,
            "domain": "geosite:cn",
            "ip": "",
            "port": "",
            "protocol": "",
            "network": "",
            "outboundTag": "direct",
            "enabled": false
        }
    ]
};
// Settings owned by the Traffic Settings tab — what its "Reset to defaults"
// button restores. Network-tab switches (apply-on mode, IPv6, tether, LAN,
// bypass interfaces), routing rules / domainStrategy, custom hosts,
// professional mode and the UI language are deliberately not listed.
const TRAFFIC_SETTING_KEYS = [
    "loglevel", "sniffing", "routeOnly", "queryStrategy",
    "dnsViaProxy", "hijackDns", "pinnedPeerCertSha256",
    "mux", "mux_connections",
    "fragment", "fragment_packets", "fragment_length", "fragment_interval",
    "mtu",
    "localDns", "fakeDnsLocal", "vpnDns", "foreignDns", "domesticDns",
    "dnsDisableCache", "dnsServeStale", "dnsServeExpiredTTL",
    "dnsDisableFallback", "dnsDisableFallbackIfMatch",
    "dnsParallelQuery", "dnsUseSystemHosts"
];
// Snapshot taken here, before any saved settings are loaded over advSettings,
// so the defaults live in one place (the advSettings literal above).
const TRAFFIC_SETTINGS_DEFAULTS = Object.freeze(
    Object.fromEntries(TRAFFIC_SETTING_KEYS.map(k => [k, advSettings[k]]))
);

// Starting point offered by the "Load template" button in professional mode.
// Mirrors the skeleton convert_uri_to_xray_json() produces, so a user can
// edit rather than invent: fwmark 255 on every dialing outbound, the tun-in
// inbound bound to xraytun0, and the socks-test-in inbound the latency/IP
// test buttons dial into.
const CUSTOM_CONFIG_TEMPLATE = `{
  "log": {
    "loglevel": "warning"
  },
  "dns": {
    "servers": ["1.1.1.1", "8.8.8.8"],
    "queryStrategy": "UseIPv4"
  },
  "inbounds": [
    {
      "tag": "socks-test-in",
      "port": 808,
      "listen": "127.17.1.3",
      "protocol": "socks",
      "settings": { "auth": "noauth", "udp": true },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": false }
    },
    {
      "tag": "tun-in",
      "protocol": "tun",
      "settings": { "name": "xraytun0", "mtu": 8500 },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": false }
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "example.com",
            "port": 443,
            "users": [
              { "id": "00000000-0000-0000-0000-000000000000", "encryption": "none", "flow": "" }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "tls",
        "tlsSettings": { "serverName": "example.com" },
        "sockopt": { "mark": 255, "dialerProxy": "direct" }
      }
    },
    {
      "tag": "direct",
      "protocol": "freedom",
      "streamSettings": {
        "sockopt": { "mark": 255, "domainStrategy": "UseIP" }
      }
    },
    {
      "tag": "block",
      "protocol": "blackhole",
      "settings": { "response": { "type": "http" } }
    }
  ],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      { "type": "field", "port": 53, "outboundTag": "direct" },
      { "type": "field", "ip": ["geoip:private"], "domain": ["geosite:private"], "outboundTag": "direct" },
      { "type": "field", "inboundTag": ["socks-test-in", "tun-in"], "network": "tcp,udp", "outboundTag": "proxy" }
    ]
  }
}`;

// Built-in pseudo-node pinned at the top of the node list. Selecting it means
// "run xray-core as a pure router": the `proxy` outbound is a freedom outbound
// just like `direct`, so every routing rule still applies but nothing is
// tunnelled — traffic leaves on the underlying network either way.
// It is represented by activeConfig === null, i.e. the absence of a selected
// node, so nothing new is written to ACTIVE_FILE and service.sh keeps seeing
// the file layout it already knows.
const DIRECT_NODE_URI = "freedom://direct";

const DNS_QUERY_STRATEGIES = ["UseIP", "UseIPv4", "UseIPv6", "UseSystem"];
const DNS_QUERY_STRATEGY_HINT_KEYS = {
    UseIP: 'hint_qs_useip',
    UseIPv4: 'hint_qs_useipv4',
    UseIPv6: 'hint_qs_useipv6',
    UseSystem: 'hint_qs_usesystem'
};

let currentLang = 'en';
let currentEditingCategory = null;
let currentEditingNodeId = null;
let currentEditingProtocol = null;
let categoryExpandedState = {};

// Routing Settings tab
let currentEditingRuleIndex = null;
// Debounce timer for saving after rules are reordered (moveRoutingRule).
let _routingPersistTimer = null;

// Logging
let _logAutoRefreshTimer = null;
let _logTailEnabled = true;
let _logCurrentFilter = 'all';
let _logLastLineCount = 0;
let _logAllLines = [];

// Network latency monitor.
// Poll cadence matches the backend probe interval (service.sh
// LATENCY_INTERVAL) — there is nothing new to read between probes, so a
// faster poll was pure wakeup cost.
const LATENCY_MAX_SAMPLES = 60;
const LATENCY_POLL_MS = 2000;
let _latencyPollTimer = null;
let _latencySamples = [];

// Mobile IP Hunter
let _ipHunterSaveTimer = null;

// Custom Hosts tab.
// `hostsEntries` is the whole file, parsed once into
// [{ domain, ips: [...] }, ...] (see parseHostsEntries in helper.js) and
// kept in memory — re-derived from `hostsFileText` on load and rewritten
// in place on every add/edit/delete, never re-read from disk mid-session.
// `_hostsFilteredEntries` is that array filtered by the current search
// query; the visible list pages through it entirely in JS (instant, no
// shell round-trip), only ever appending the current page's ~40 rows to
// the DOM as the user scrolls.
const HOSTS_PAGE_SIZE = 40;
// Raw text cache, used only by buildDnsHosts() (helper.js) to generate
// dns.hosts synchronously at config-build time.
let hostsFileText = "";
let hostsEntries = [];
let _hostsFilteredEntries = [];
let _hostsSearchQuery = "";
let _hostsSearchTimer = null;
let _hostsLoadedCount = 0;
let _hostsListEndReached = false;
// null = "add" mode; otherwise the normalized domain currently being edited.
let currentEditingHostDomain = null;

// About tab (see initAboutTab in main.js).
// A link whose URL is empty is hidden, so SUPPORT_TELEGRAM_URL can stay ""
// until the support group link is filled in (e.g. "https://t.me/yourgroup").
const ABOUT_AUTHOR_GITHUB = "vincentng295";
const ABOUT_REPO_URL = "https://github.com/vincentng295/Magic_V2Ray";
const ABOUT_WEBSITE_URL = "https://magicv2ray.duckdns.org/";
const SUPPORT_TELEGRAM_URL = "";
const ABOUT_LINKS = {
    author: `https://github.com/${ABOUT_AUTHOR_GITHUB}`,
    telegram: SUPPORT_TELEGRAM_URL,
    repo: ABOUT_REPO_URL,
    releases: `${ABOUT_REPO_URL}/releases/latest`,
    issues: `${ABOUT_REPO_URL}/issues`,
    website: ABOUT_WEBSITE_URL,
    xray: "https://github.com/XTLS/Xray-core",
    openxtun: "https://github.com/vincentng295/openxtun",
    curl: "https://github.com/vvb2060/curl-android"
};
