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

    // Use the full config skeleton from hop2 (has correct dns/routing from settings)
    // but replace outbounds with our chained pair
    const fullConfig = hop2Config;
    fullConfig.outbounds = [
        hop2Out,
        hop1Out,
        {
            protocol: "freedom",
            tag: "direct",
            streamSettings: {
                sockopt: { mark: 255 }
            }
        },
        {
            protocol: "blackhole",
            tag: "block",
            settings: {
                response: { type: "http" }
            }
        }
    ];

    const hop1IsWg = hop1Out.protocol === 'wireguard';
    const hop2IsWg = hop2Out.protocol === 'wireguard';

    // Patch routing rules for WireGuard DNS resolution edge cases.
    //
    // Case A: WireGuard is hop1 (WG → any)
    //   hop1 dials direct, but its endpoint may be a domain.
    //   Xray resolves WG's peer endpoint via internal DNS (no inboundTag).
    //   The skeleton was built for hop2 (non-WG), so the WG-specific
    //   "port 53 → direct" rule is missing. Add it so WG can resolve
    //   its endpoint without looping through hop2.
    //
    // Case B: WireGuard is hop2 (any → WG)
    //   hop2 dials through hop1 (dialerProxy = "proxy-hop1"), BUT
    //   Xray resolves the WG peer endpoint via internal DNS before
    //   passing the connection through dialerProxy. On poisoned networks
    //   that resolution would fail if sent direct. We must route the
    //   WG-internal DNS (no inboundTag, port 53) through hop1 instead.
    //   We do this by sending it to "proxy-hop1" — hop1 is already up
    //   and can reach the real DNS server cleanly.
    //   
    //   NOTE: the existing "inboundTag: socks-test-in, port 53" rule is
    //   kept for user-traffic DNS; only the tagless internal WG DNS rule
    //   is added here.
    if (hop1IsWg || hop2IsWg) {
        // Remove any pre-existing tagless port-53 rule that convert_uri_to_xray_json
        // may have injected for the individual WireGuard config (hop2Config skeleton).
        // We always replace it with the correct chain-aware rule below.
        fullConfig.routing.rules = fullConfig.routing.rules.filter(r =>
            !(r.port === 53 && !r.inboundTag)
        );

        const wgDnsRule = {
            "type": "field",
            "port": 53,
            // hop1=WG: send direct (WG dials direct, needs real DNS pre-tunnel)
            // hop2=WG: send through hop1 (bypass poison; hop1 is already connected)
            "outboundTag": hop1IsWg ? "direct" : "proxy-hop1"
        };

        // Insert AFTER the inboundTag-scoped port-53 rule so that rule stays alive.
        // Tagless WG DNS rule must be narrower-first: the inboundTag rule (2 conditions)
        // sits above this one (1 condition) so socks-test-in DNS still routes correctly.
        const port53Idx = fullConfig.routing.rules.findIndex(r =>
            r.port === 53 && Array.isArray(r.inboundTag)
        );
        if (port53Idx !== -1) {
            fullConfig.routing.rules.splice(port53Idx + 1, 0, wgDnsRule);
        } else {
            fullConfig.routing.rules.unshift(wgDnsRule);
        }
    }

    return JSON.stringify(fullConfig, null, 2);
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

function convert_uri_to_xray_json(uri, optional_settings) {
    const settings = optional_settings || {
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
        localDns: false,
        fakeDnsLocal: false,
        vpnDns: "1.1.1.1",
        foreignDns: "1.1.1.1",
        domesticDns: "223.5.5.5",
        routingRules: []
    };

    let outbound = null;
    uri = uri.trim();

    try {
        if (uri.startsWith('vmess://')) {
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
            const host = u.hostname;
            const port = +u.port || 443;
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
            // Extract user info portion (before the @)
            const atIdx = uri.indexOf('@');
            if (atIdx === -1) throw new Error("Invalid Shadowsocks URI: missing @");
            const schemeEnd = uri.indexOf('://') + 3;
            const rawUserPart = uri.substring(schemeEnd, atIdx);
            // Try base64-decode first; fall back to plain text
            let method, password;
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
            const ssQueryStr = qIdx !== -1 ? afterAt.substring(qIdx + 1) : '';
            const lastColon = hostPort.lastIndexOf(':');
            const ssHost = hostPort.substring(0, lastColon);
            const ssPort = parseInt(hostPort.substring(lastColon + 1)) || 443;

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
                address: u.hostname,
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

            outbound = {
                tag: "proxy",
                protocol: "hysteria",
                settings: hy2Settings,
                streamSettings: {
                    network: "hysteria",
                    security: "tls",
                    tlsSettings: {
                        serverName: p.get('sni') || u.hostname
                    },
                    hysteriaSettings: hySettings,
                    sockopt: { mark: 255, "dialerProxy": "direct" }
                }
            };
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
                        address: u.hostname,
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
                        address: u.hostname,
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
                    serverName: u.hostname
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

    // Resolve effective fakeip flag — new field (fakeDnsLocal) takes priority when
    // Local DNS is enabled; fall back to legacy fakeDns for backward compatibility.
    const useFakeIp = settings.localDns
        ? settings.fakeDnsLocal
        : false;

    let dnsServers;

    if (settings.localDns) {
        // Build a structured DNS server list from the explicit fields.
        dnsServers = [];

        // 1. FakeIP entry — sits first so it intercepts all domain queries.
        if (useFakeIp) {
            dnsServers.push({
                address: "fakeip",
                domains: ["regexp:.+"],
                expectIPs: ["geoip:!private"]
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

        // 4. Foreign DNS — fallback for everything else.
        if (settings.foreignDns && settings.foreignDns.trim()) {
            dnsServers.push(settings.foreignDns.trim());
        }

        // Ensure there is always at least one server so Xray doesn't error out.
        if (dnsServers.length === 0) {
            dnsServers.push("1.1.1.1");
        }
    } else {
        // Legacy / simple mode: two hardcoded servers, optional fakeip prepend.
        dnsServers = ["1.1.1.1", "8.8.8.8"];
        if (useFakeIp) {
            dnsServers.unshift({
                address: "fakeip",
                domains: ["regexp:.+"],
                expectIPs: ["geoip:!private"]
            });
        }
    }

    const fullConfig = {
        log: { 
            loglevel: settings.loglevel || "none" 
        }, 
        dns: {
            servers: dnsServers,
            queryStrategy: settings.preferIpv6 ? "UseIPv6" : "UseIPv4",
            ...(useFakeIp ? { fakedns: [{ ipPool: "198.18.0.0/15", poolSize: 65535 }] } : {})
        },
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
                    "destOverride": ["http", "tls", "quic"],
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
                        mark: 255
                    }
                }
            },
            {
                "protocol": "blackhole",
                "tag": "block",
                "settings": {
                    "response": { "type": "http" }
                }
            }
        ],
        routing: {
            "domainStrategy": useFakeIp ? "AsIs" : "IPIfNonMatch",
            "rules": [
                // Narrow rule first (2 conditions): user DNS from socks-test-in inbound.
                {
                    "type": "field",
                    "inboundTag": [
                        "socks-test-in",
                    ],
                    "port": 53,
                    "outboundTag": dnsOutboundTag
                },
                // Wider rule second (1 condition): tagless internal WireGuard DNS.
                // Must sit BELOW the inboundTag rule or it shadows it.
                // WireGuard needs to resolve its peer endpoint before the tunnel is up.
                // Xray's internal DNS for this comes from xray.system.* (no inboundTag),
                // so the inboundTag-scoped rule above won't catch it.
                ...(outbound.protocol === 'wireguard' ? [{
                    "type": "field",
                    "port": 53,
                    "outboundTag": "direct"
                }] : []),
                ...(useFakeIp ? [{
                    "type": "field",
                    "ip": ["198.18.0.0/15"],
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
                    ],
                    "network": "tcp,udp",
                    "outboundTag": "proxy"
                }
            ]
        }
    };

    return JSON.stringify(fullConfig, null, 2);
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
            }

            const queryStr = buildQuery(q);
            const nodeTag = (proto === 'vless' ? 'VLESS Node' : 'Trojan Node');
            return `${proto}://${pct(user)}@${host}:${port}?${queryStr}#${pct(nodeTag)}`;
        }

        if (proto === 'shadowsocks') {
            const srv = outbound.settings.servers[0];
            // userinfo = base64(method:password)  — SIP002 style
            const userInfo = btoa(`${srv.method}:${srv.password}`);
            const nodeTag = 'SS Node';

            // quic is legacy-only (never produced by the edit UI); keep routing it
            // through the old SIP003 plugin= form since Xray's own quic outbound
            // transport is effectively deprecated.
            if (net === 'quic') {
                const pluginParts = ['v2ray-plugin', 'mode=quic'];
                if (sec === 'tls') pluginParts.push('tls');
                const tlsS = ss.tlsSettings || {};
                if (tlsS.serverName) pluginParts.push(`host=${tlsS.serverName}`);
                const pluginQuery = `?plugin=${pct(pluginParts.join(';'))}`;
                return `ss://${userInfo}@${srv.address}:${srv.port}${pluginQuery}#${pct(nodeTag)}`;
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

            const queryStr = buildQuery(q);
            const suffix = queryStr ? `?${queryStr}` : '';
            return `ss://${userInfo}@${srv.address}:${srv.port}${suffix}#${pct(nodeTag)}`;
        }

        if (proto === 'wireguard') {
            const cfg  = outbound.settings;
            const peer = (cfg.peers || [])[0] || {};

            // endpoint → host:port
            const endpoint = peer.endpoint || 'unknown:51820';
            const lastColon = endpoint.lastIndexOf(':');
            const wgHost = endpoint.substring(0, lastColon);
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

            const q = {};
            if (tls.serverName) q.sni = tls.serverName;
            if (hy.down) q.down = hy.down;
            if (hy.up)   q.up   = hy.up;
            if (hy.udpIdleTimeout) q.udpIdleTimeout = hy.udpIdleTimeout;
            if (hy.udphop) {
                q.mport = hy.udphop.port;
                if (hy.udphop.interval) q.hopInterval = hy.udphop.interval;
            }

            const queryStr = buildQuery(q);
            const nodeTag  = 'Hysteria2 Node';
            const auth     = pct(hy.auth || '');
            return `hy2://${auth}@${cfg.address}:${cfg.port}?${queryStr}#${pct(nodeTag)}`;
        }

        if (proto === 'socks') {
            const srv = outbound.settings.servers[0];
            const nodeTag = 'SOCKS Node';
            if (srv.users?.length) {
                const u = srv.users[0];
                return `socks://${pct(u.user)}:${pct(u.pass)}@${srv.address}:${srv.port}#${pct(nodeTag)}`;
            }
            return `socks://${srv.address}:${srv.port}#${pct(nodeTag)}`;
        }
        if (proto === 'http') {
            const srv  = outbound.settings.servers[0];
            const scheme = sec === 'tls' ? 'https' : 'http';
            const nodeTag = 'HTTP Node';
            if (srv.users?.length) {
                const u = srv.users[0];
                return `${scheme}://${pct(u.user)}:${pct(u.pass)}@${srv.address}:${srv.port}#${pct(nodeTag)}`;
            }
            return `${scheme}://${srv.address}:${srv.port}#${pct(nodeTag)}`;
        }

        return `Error: unsupported protocol "${proto}"`;

    } catch (e) {
        return 'Error: ' + e.message;
    }
}