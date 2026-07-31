const MODDIR = "/data/adb/modules/magic_v2ray";
const DATADIR = "/data/adb/magic_v2ray";
const PROFILES_FILE = `${DATADIR}/profiles.base64`;
const SETTINGS_FILE = `${DATADIR}/settings.base64`;
const ACTIVE_FILE = `${DATADIR}/active_config.txt`;
const CONFIG_JSON = `${DATADIR}/config.v2.json`;
const IP_HUNT_FILE = `${DATADIR}/ip_hunt.list`;
const STUB_DIR = "/dev/sysctl_stubs";
const TIME_RES_FILE = `${STUB_DIR}/run/time_res`;
const ADDR_INFO_FILE = `${STUB_DIR}/run/addr_info`;
// Refreshed by the UI while the Latency tab is visible. The backend probe
// loop exits on its own once this stops being updated, so closing the WebUI
// can no longer leave a per-second curl running until reboot.
const LATENCY_HB_FILE = `${STUB_DIR}/run/latency.hb`;
 
let profiles = {};
let activeConfig = null;
let advSettings = {
    loglevel: "none",
    sniffing: true,
    routeOnly: false,
    enableIPv6: false,
    preferIpv6: false,
    networkMode: 0,
    allowTether: true,
    mux: false,
    mux_connections: 8,
    fragment: false,
    fragment_packets: "tlshello",
    fragment_length: "50-100",
    fragment_interval: "10-20",
    mtu: 1350,
    pinnedPeerCertSha256: "",
    dnsViaProxy: true,
    localDns: false,
    fakeDnsLocal: false,
    vpnDns: "1.1.1.1",
    foreignDns: "1.1.1.1",
    domesticDns: "223.5.5.5",
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
let currentLang = 'en';
let currentEditingCategory = null;
let currentEditingNodeId = null;
let currentEditingProtocol = null;
let categoryExpandedState = {};

// Routing Settings tab
let currentEditingRuleIndex = null;

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