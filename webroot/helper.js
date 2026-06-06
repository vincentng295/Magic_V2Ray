// Helper to decode Base64 safely for both Browser and Node.js environments
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

function utoa(str) {
    const bytes = new TextEncoder().encode(str);
    const binString = String.fromCodePoint(...bytes);
    return btoa(binString);
}

function convert_uri_to_xray_json(uri, optional_settings) {
    const settings = optional_settings || {
        loglevel: "debug",
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

    const b64decode = s => {
        try { 
            return decodeURIComponent(escape(atob(s.trim()))); 
        } catch { 
            return null; 
        }
    };

    let outbound = null;
    uri = uri.trim();

    try {
        if (uri.startsWith('vmess://')) {
            const c = JSON.parse(b64decode(uri.substring(8)));
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
                    alpn: c.alpn ? c.alpn.split(',') : undefined
                };
                if (settings.pinnedPeerCertSha256) {
                    outbound.streamSettings.tlsSettings.pinnedPeerCertSha256 = [settings.pinnedPeerCertSha256];
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
            const u = new URL(uri);
            const p = new URLSearchParams(u.search);
            const user = decodeURIComponent(u.username);
            const host = u.hostname;
            const port = +u.port;
            const net = p.get('type') || 'tcp';
            const sec = p.get('security') || 'none';

            outbound = {
                tag: "proxy",
                protocol: proto,
                settings: proto === 'trojan' 
                    ? { servers: [{ address: host, port, password: user }] }
                    : { vnext: [{ address: host, port, users: [{ id: user, encryption: "none", flow: p.get('flow') || undefined }] }] },
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
                        spiderX: p.get('spx') || ""
                    };
                } else {
                    outbound.streamSettings.tlsSettings = {
                        serverName: p.get('sni') || "",
                        alpn: p.get('alpn') ? p.get('alpn').split(',') : undefined,
                        fingerprint: p.get('fp') || undefined
                    };
                    if (settings.pinnedPeerCertSha256) {
                        outbound.streamSettings.tlsSettings.pinnedPeerCertSha256 = [settings.pinnedPeerCertSha256];
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
                    headers: { Host: p.get('host') || "" }
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
    } catch (e) {
        return JSON.stringify({ error: "Unable to parse URI: " + e.message }, null, 2);
    }

    if (!outbound) {
        return JSON.stringify({ error: "Unsupported or malformed URI" }, null, 2);
    }

    if (settings.mux) {
        outbound.streamSettings.mux = {
            enabled: true,
            concurrency: parseInt(settings.mux_connections) || 8
        };
    }

    if (settings.fragment) {
        outbound.streamSettings.sockopt.fragment = {
            packets: settings.fragment_packets || "tlshello",
            length: settings.fragment_length || "50-100",
            interval: settings.fragment_interval || "10-20"
        };
    }

    const dnsOutboundTag = settings.dnsViaProxy ? "proxy" : "direct";

    let dnsServers = [
        "1.1.1.1",
        "8.8.8.8"
    ];

    if (settings.fakeDns) {
        dnsServers.unshift({
            "address": "fakeip",
            "domains": ["regexp:.+"],
            "expectIPs": ["geoip:!private"]
        });
    }

    const fullConfig = {
        log: { 
            loglevel: settings.loglevel || "debug" 
        }, 
        dns: {
            servers: dnsServers,
            queryStrategy: settings.preferIpv6 ? "UseIPv6" : "UseIPv4"
        },
        inbounds: [
            {
                "tag": "socks-test-in",
                "port": 10808,
                "listen": "127.0.0.1",
                "protocol": "socks",
                "settings": {
                    "auth": "noauth",
                    "udp": true
                },
                "sniffing": {
                    "enabled": settings.sniffing,
                    "destOverride": ["http", "tls", "quic"],
                    "routeOnly": settings.routeOnly
                }
            },
            {
                "tag": "http-test-in",
                "port": 10809,
                "listen": "127.0.0.1",
                "protocol": "http",
                "settings": {
                    "allowTransparent": false
                }
            }
        ],
        outbounds: [
            outbound, 
            { 
                "protocol": "freedom", 
                "tag": "direct",
                "streamSettings": {
                    "sockopt": { 
                        mark: 255
                    }
                }
            }
        ],
        routing: {
            "domainStrategy": settings.fakeDns ? "AsIs" : "IPIfNonMatch",
            "rules": [
                {
                    "type": "field",
                    "inboundTag": [
                        "socks-test-in",
                        "http-test-in",
                    ],
                    "port": 53,
                    "outboundTag": dnsOutboundTag
                },
                ...(settings.fakeDns ? [{
                    "type": "field",
                    "ip": ["198.18.0.0/15"],
                    "outboundTag": "proxy"
                }] : []),
                {
                    "type": "field",
                    "inboundTag": [
                        "socks-test-in",
                        "http-test-in",
                    ],
                    "network": "tcp,udp",
                    "outboundTag": "proxy"
                }
            ]
        }
    };

    return JSON.stringify(fullConfig, null, 2);
}