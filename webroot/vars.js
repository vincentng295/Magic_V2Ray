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
    loglevel: "none",
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
