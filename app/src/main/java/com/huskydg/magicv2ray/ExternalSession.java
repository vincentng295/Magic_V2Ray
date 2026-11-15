package com.huskydg.magicv2ray;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Control from other apps and websites: {@code magicv2ray://connect?uri=..&name=..&exclude=..},
 * {@code magicv2ray://disconnect}, and the same through an explicit intent / broadcast.
 *
 * <p>A connect request is only ever a <em>request</em>: it is parked here, handed to the WebUI
 * (which owns config generation and shows the confirmation) and never acted on by itself. The
 * node of an external session is temporary: the user's profiles and selected node are not touched.
 * Only the generated config.v2.json is swapped (the previous one is kept in config.base.json), so
 * ending the session, from anywhere, puts the previous node back without needing the WebUI.
 *
 * <p>Who called: Android does not tell a website from the browser that opened it, so a caller is
 * only "verified" when the system gave us its package (the activity referrer, never the
 * spoofable EXTRA_REFERRER) and it is not a browser. The name shown for a session is for display
 * only, nothing is trusted because of it.
 */
final class ExternalSession {

    static final String ACTION_CONNECT = "com.huskydg.magicv2ray.action.CONNECT";
    static final String ACTION_DISCONNECT = "com.huskydg.magicv2ray.action.DISCONNECT";
    /** Our own notification: "an app wants to connect, tap to review" (the request is already parked). */
    static final String ACTION_REVIEW = "com.huskydg.magicv2ray.action.REVIEW";

    static final String EXTRA_URI = "uri";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_EXCLUDE = "exclude";
    /** Temporary setting overrides: JSON object (extra) or base64url / url-encoded JSON (deep link), see {@link #sanitizeOverrides}. */
    static final String EXTRA_OVERRIDES = "overrides";

    static final String BASE_FILE = "config.base.json";
    static final String SESSION_EXCLUDE_FILE = "excludelist.session.txt";

    private static final int MAX_OVERRIDES = 256 * 1024;
    private static final String PREFS = "ext_session";
    private static final String TRUST_PREFS = "ext_trust";
    private static final String K_SESSION = "session";
    private static final long PENDING_TTL_MS = 10 * 60_000L;
    private static final long MIN_AGE_BEFORE_ABANDON_MS = 15_000L;
    private static final int MAX_URI = 8192;
    private static final int MAX_NAME = 60;
    private static final int MAX_EXCLUDE = 8;
    private static final Pattern PKG_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    private static final String REVIEW_CHANNEL = "mv_ext";
    private static final int REVIEW_NOTIF_ID = 2;

    /**
     * The request waiting for the WebUI. Memory only: it can carry hosts, routes and a server address, which must not be
     * written to disk. (A process that dies in between simply loses the request, which expires after 10 minutes anyway.)
     */
    private static String pendingJson = "";
    private static final Object LOCK = new Object();
    /** Caller of the request the WebUI took last, if that caller may be trusted ("always trust this app"). */
    private static volatile String lastTakenTrustable;

    private ExternalSession() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // =============================================================== who is calling

    /**
     * Package of the app that started {@code a} with {@code i}, or null when unknown. The system's
     * referrer is only used when the intent carries no EXTRA_REFERRER(_NAME): those override
     * Activity.getReferrer() and any caller can set them.
     */
    static String callerOf(Activity a, Intent i) {
        if (i == null || i.hasExtra(Intent.EXTRA_REFERRER) || i.hasExtra(Intent.EXTRA_REFERRER_NAME)) return null;
        Uri r = a.getReferrer();
        if (r == null || !"android-app".equals(r.getScheme())) return null;
        String pkg = r.getAuthority();
        if (pkg == null || pkg.isEmpty() || pkg.equals(a.getPackageName())) return null;
        return pkg;
    }

    static String labelOf(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg;
        }
    }

    /** Browsers (and anything else that opens web links) cannot say which page asked. */
    static boolean isBrowser(Context c, String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    // =============================================================== requests

    /**
     * The request carried by {@code i}, as JSON for the WebUI, or null when it is not a connect
     * request (a disconnect is returned as {"action":"disconnect"}).
     *
     * @param caller   verified package of the caller (see {@link #callerOf}), or null
     * @param broadcast the intent came through the broadcast receiver, not an activity start
     */
    static JSONObject request(Context c, Intent i, String caller, boolean broadcast) {
        if (i == null) return null;
        String action = i.getAction();
        String source;
        String uri;
        String name;
        String exclude;
        String overrides;
        boolean connect;
        Uri d = i.getData();
        if (Intent.ACTION_VIEW.equals(action) && d != null && "magicv2ray".equals(d.getScheme())
                && ("connect".equals(d.getHost()) || "disconnect".equals(d.getHost()))) {
            source = "deeplink";
            connect = "connect".equals(d.getHost());
            uri = d.getQueryParameter(EXTRA_URI);
            name = d.getQueryParameter(EXTRA_NAME);
            exclude = d.getQueryParameter(EXTRA_EXCLUDE);
            overrides = d.getQueryParameter(EXTRA_OVERRIDES);
        } else if (ACTION_CONNECT.equals(action) || ACTION_DISCONNECT.equals(action)) {
            source = broadcast ? "broadcast" : "intent";
            connect = ACTION_CONNECT.equals(action);
            uri = i.getStringExtra(EXTRA_URI);
            name = i.getStringExtra(EXTRA_NAME);
            exclude = excludeExtra(i);
            overrides = i.getStringExtra(EXTRA_OVERRIDES);
        } else {
            return null;
        }
        try {
            JSONObject o = new JSONObject();
            if (!connect) return o.put("action", "disconnect").put("source", source);
            uri = uri == null ? "" : uri.trim();
            JSONObject ov = sanitizeOverrides(overrides);
            // The node is optional when settings are overridden: the node the user selected keeps being used.
            if (uri.isEmpty() ? ov.length() == 0
                    : (uri.length() > MAX_URI || !uri.matches("^[A-Za-z][A-Za-z0-9+.-]*://\\S+$"))) return null;
            boolean browser = caller != null && isBrowser(c, caller);
            // Trust is only offered for calls that app code made on purpose (an explicit action):
            // a link can come from anywhere (a chat message, a web page), whichever app opened it.
            // An app the user chose to "always trust" (explicit intent from a verified native app, tied to its signing
            // certificate) is not asked again when it also sends overrides. Links and browsers are never trustable.
            boolean trustable = !"deeplink".equals(source) && caller != null && !browser;
            o.put("action", "connect")
                    .put("uri", uri)
                    .put("name", clean(name, MAX_NAME))
                    .put("exclude", new JSONArray(parseExclude(exclude)))
                    .put("overrides", ov)
                    .put("source", source)
                    .put("caller", caller == null ? "" : caller)
                    .put("callerLabel", caller == null ? "" : labelOf(c, caller))
                    .put("browser", browser)
                    .put("trustable", trustable)
                    .put("trusted", trustable && isTrusted(c, caller))
                    .put("ts", System.currentTimeMillis());
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    private static String excludeExtra(Intent i) {
        String[] arr = i.getStringArrayExtra(EXTRA_EXCLUDE);
        if (arr != null) return String.join(",", arr);
        return i.getStringExtra(EXTRA_EXCLUDE);
    }

    private static List<String> parseExclude(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split("[,\\s;]+")) {
            if (PKG_RE.matcher(s).matches() && s.length() <= 200 && !out.contains(s)) out.add(s);
            if (out.size() >= MAX_EXCLUDE) break;
        }
        return out;
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ---- setting overrides

    /** key -> type: b = boolean, i:min:max = integer, s:max = string, e:a|b|c = one of. Only these may be overridden. */
    private static final java.util.Map<String, String> TRAFFIC_KEYS = new java.util.LinkedHashMap<>();
    static {
        String[] t = {
            "loglevel", "e:debug|info|warning|error|none", "sniffing", "b", "routeOnly", "b",
            "queryStrategy", "e:UseIP|UseIPv4|UseIPv6|UseSystem", "dnsViaProxy", "b", "hijackDns", "b",
            "pinnedPeerCertSha256", "s:512", "mux", "b", "mux_connections", "i:1:1024",
            "fragment", "b", "fragment_packets", "s:32", "fragment_length", "s:32", "fragment_interval", "s:32",
            "mtu", "i:576:9000", "localDns", "b", "fakeDnsLocal", "b",
            "foreignDns", "s:4000", "directDns", "s:500", "directDnsDomains", "s:8000",
            "dnsDisableCache", "b", "dnsServeStale", "b", "dnsServeExpiredTTL", "i:0:86400",
            "dnsDisableFallback", "b", "dnsDisableFallbackIfMatch", "b", "dnsParallelQuery", "b", "dnsUseSystemHosts", "b"
        };
        for (int i = 0; i < t.length; i += 2) TRAFFIC_KEYS.put(t[i], t[i + 1]);
    }
    // Deliberately NOT overridable: exposeSocks* (opens a LAN proxy), proMode (hand-written config), shizukuShare*,
    // language / theme, anything not listed here.
    private static final java.util.Map<String, String> NETWORK_KEYS = new java.util.LinkedHashMap<>();
    static {
        String[] t = {
            "networkMode", "i:0:2", "allowTether", "b", "enableIPv6", "b", "enableIPv6ULA", "b",
            "bypassVpnService", "b", "upstreamIface", "s:32", "lanProxyCidrs", "s:2000"
        };
        for (int i = 0; i < t.length; i += 2) NETWORK_KEYS.put(t[i], t[i + 1]);
    }
    private static final Pattern IFACE_RE = Pattern.compile("^[A-Za-z0-9_.:+-]{1,15}$");
    private static final Pattern DOMAIN_RE = Pattern.compile("^[A-Za-z0-9*_.-]{1,253}$");
    private static final Pattern IP_RE = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final Pattern CIDR_LIST_RE = Pattern.compile("^[0-9A-Fa-f:./\\s]*$");
    private static final java.util.Set<String> BUILTIN_TAGS = new java.util.HashSet<>(java.util.Arrays.asList("proxy", "direct", "block"));
    private static final int MAX_RULES = 500;
    private static final int MAX_HOSTS = 2000;
    private static final int MAX_EXCLUDE_OVERRIDE = 500;

    private static Object typed(String spec, Object v) {
        try {
            if (spec.equals("b")) return v instanceof Boolean ? v : null;
            if (spec.startsWith("i:")) {
                String[] p = spec.split(":");
                if (!(v instanceof Number)) return null;
                long n = ((Number) v).longValue();
                return n >= Long.parseLong(p[1]) && n <= Long.parseLong(p[2]) ? (Object) n : null;
            }
            if (spec.startsWith("s:")) {
                return v instanceof String ? clean((String) v, Integer.parseInt(spec.substring(2))) : null;
            }
            if (spec.startsWith("e:")) {
                return v instanceof String && java.util.Arrays.asList(spec.substring(2).split("\\|")).contains(v) ? v : null;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static JSONObject pickKeys(JSONObject in, java.util.Map<String, String> allowed) throws JSONException {
        JSONObject out = new JSONObject();
        if (in == null) return out;
        for (java.util.Map.Entry<String, String> e : allowed.entrySet()) {
            if (!in.has(e.getKey())) continue;
            Object v = typed(e.getValue(), in.get(e.getKey()));
            if (v != null) out.put(e.getKey(), v);       // a wrong type or an out-of-range value is dropped, never coerced
        }
        return out;
    }

    private static String ruleStr(JSONObject r, String k, int max) {
        Object v = r.opt(k);
        return v instanceof String ? clean((String) v, max) : "";
    }

    /**
     * Decodes and normalises the {@code overrides} of a request into what the WebUI may apply: only whitelisted keys of
     * the right type and range survive, everything else is dropped. Returns an empty object when there is nothing valid.
     *
     * <pre>
     * { "traffic": { "mux": true, "mtu": 1400, "directDns": "...", ... },
     *   "network": { "networkMode": 1, "allowTether": false, "bypassIfaces": ["tailscale0"], ... },
     *   "exclude": { "apps": ["pkg", ...], "mode": "replace" | "add", "enabled": true },
     *   "routing": { "domainStrategy": "IPIfNonMatch", "mode": "replace" | "prepend",
     *                "rules": [ { "domain": "geosite:google", "ip": "", "port": "", "protocol": "", "network": "",
     *                             "outboundTag": "proxy" | "direct" | "block", "remarks": "", "enabled": true } ] },
     *   "hosts":   { "mode": "replace" | "merge", "entries": { "example.com": "1.2.3.4", "b.com": ["::1", "10.0.0.1"] } } }
     * </pre>
     */
    static JSONObject sanitizeOverrides(String raw) {
        JSONObject out = new JSONObject();
        if (raw == null) return out;
        raw = raw.trim();
        if (raw.isEmpty() || raw.length() > MAX_OVERRIDES * 2) return out;
        try {
            String json = raw;
            if (!raw.startsWith("{")) {      // deep link: base64url
                byte[] b = android.util.Base64.decode(raw, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                json = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (json.length() > MAX_OVERRIDES) return out;
            JSONObject in = new JSONObject(json);

            JSONObject traffic = pickKeys(in.optJSONObject("traffic"), TRAFFIC_KEYS);
            if (traffic.length() > 0) out.put("traffic", traffic);

            JSONObject inNet = in.optJSONObject("network");
            JSONObject net = pickKeys(inNet, NETWORK_KEYS);
            if (net.has("lanProxyCidrs") && !CIDR_LIST_RE.matcher(net.getString("lanProxyCidrs")).matches()) net.remove("lanProxyCidrs");
            if (net.has("upstreamIface")) {
                String u = net.getString("upstreamIface");
                if (!u.equals("auto") && !IFACE_RE.matcher(u).matches()) net.remove("upstreamIface");
            }
            JSONArray bi = inNet == null ? null : inNet.optJSONArray("bypassIfaces");
            if (bi != null) {
                JSONArray list = new JSONArray();
                for (int i = 0; i < bi.length() && list.length() < 8; i++) {
                    String n = bi.optString(i, "");
                    if (IFACE_RE.matcher(n).matches()) list.put(n);
                }
                net.put("bypassIfaces", list);      // an empty list means "no bypass interface"
            }
            if (net.length() > 0) out.put("network", net);

            JSONObject inEx = in.optJSONObject("exclude");
            if (inEx != null) {
                JSONObject ex = new JSONObject();
                JSONArray apps = new JSONArray();
                JSONArray ia = inEx.optJSONArray("apps");
                for (int i = 0; ia != null && i < ia.length() && apps.length() < MAX_EXCLUDE_OVERRIDE; i++) {
                    String p = ia.optString(i, "");
                    if (PKG_RE.matcher(p).matches() && p.length() <= 200) apps.put(p);
                }
                ex.put("apps", apps);
                ex.put("mode", "add".equals(inEx.optString("mode")) ? "add" : "replace");
                ex.put("enabled", !inEx.has("enabled") || inEx.optBoolean("enabled", true));
                out.put("exclude", ex);
            }

            JSONObject inRt = in.optJSONObject("routing");
            if (inRt != null) {
                JSONObject rt = new JSONObject();
                String ds = inRt.optString("domainStrategy", "");
                if (ds.equals("AsIs") || ds.equals("IPIfNonMatch") || ds.equals("IPOnDemand")) rt.put("domainStrategy", ds);
                JSONArray ir = inRt.optJSONArray("rules");
                if (ir != null) {
                    JSONArray rules = new JSONArray();
                    for (int i = 0; i < ir.length() && rules.length() < MAX_RULES; i++) {
                        JSONObject r = ir.optJSONObject(i);
                        if (r == null) continue;
                        String tag = ruleStr(r, "outboundTag", 16);
                        if (!BUILTIN_TAGS.contains(tag)) continue;   // nodes / policy groups are the user's, not an app's
                        JSONObject o = new JSONObject();
                        o.put("remarks", ruleStr(r, "remarks", 80)).put("locked", false)
                                .put("domain", ruleStr(r, "domain", 4000)).put("ip", ruleStr(r, "ip", 4000))
                                .put("port", ruleStr(r, "port", 200)).put("protocol", ruleStr(r, "protocol", 100))
                                .put("network", ruleStr(r, "network", 20)).put("outboundTag", tag)
                                .put("enabled", !r.has("enabled") || r.optBoolean("enabled", true));
                        rules.put(o);
                    }
                    rt.put("rules", rules);
                    rt.put("mode", "prepend".equals(inRt.optString("mode")) ? "prepend" : "replace");
                }
                if (rt.length() > 0) out.put("routing", rt);
            }

            JSONObject inHo = in.optJSONObject("hosts");
            JSONObject he = inHo == null ? null : inHo.optJSONObject("entries");
            if (he != null) {
                JSONObject entries = new JSONObject();
                java.util.Iterator<String> it = he.keys();
                while (it.hasNext() && entries.length() < MAX_HOSTS) {
                    String dom = it.next();
                    if (!DOMAIN_RE.matcher(dom).matches()) continue;
                    Object v = he.get(dom);
                    JSONArray ips = new JSONArray();
                    if (v instanceof String) {
                        if (IP_RE.matcher((String) v).matches()) ips.put(v);
                    } else if (v instanceof JSONArray) {
                        for (int i = 0; i < ((JSONArray) v).length() && ips.length() < 8; i++) {
                            String ip = ((JSONArray) v).optString(i, "");
                            if (IP_RE.matcher(ip).matches()) ips.put(ip);
                        }
                    }
                    if (ips.length() > 0) entries.put(dom, ips);
                }
                out.put("hosts", new JSONObject().put("entries", entries)
                        .put("mode", "merge".equals(inHo.optString("mode")) ? "merge" : "replace"));
            }
        } catch (JSONException | IllegalArgumentException e) {
            return new JSONObject();
        }
        return out;
    }

    // ---- parking a request until the WebUI takes it

    static void queue(Context c, JSONObject req) {
        synchronized (LOCK) {
            pendingJson = req.toString();
        }
    }

    /** The parked request as JSON (\"\" if none or expired); returns it once. */
    static String take(Context c) {
        synchronized (LOCK) {
            String s = pendingJson;
            pendingJson = "";
            lastTakenTrustable = null;
            if (s == null || s.isEmpty()) return "";
            try {
                JSONObject o = new JSONObject(s);
                if (System.currentTimeMillis() - o.optLong("ts") > PENDING_TTL_MS) return "";
                if (o.optBoolean("trustable")) lastTakenTrustable = o.optString("caller");
            } catch (JSONException e) {
                return "";
            }
            return s;
        }
    }

    /** Posts "app X wants to connect, tap to review" for requests that arrive while nothing is on screen. */
    static void notifyPending(Context c, JSONObject req) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        REVIEW_CHANNEL, c.getString(R.string.ext_review_channel), NotificationManager.IMPORTANCE_HIGH));
            }
            String who = req.optString("callerLabel", "");
            if (who.isEmpty()) who = c.getString(R.string.ext_unknown_app);
            String name = req.optString("name", "");
            if (name.isEmpty()) name = c.getString(R.string.ext_unnamed);
            PendingIntent open = PendingIntent.getActivity(c, 2,
                    new Intent(c, ExternalPromptActivity.class).setAction(ACTION_REVIEW)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, REVIEW_CHANNEL)
                    : new Notification.Builder(c);
            nm.notify(REVIEW_NOTIF_ID, nb.setContentTitle(c.getString(R.string.ext_review_title))
                    .setContentText(c.getString(R.string.ext_review_text, who, name))
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .build());
        } catch (RuntimeException ignored) {    // notifications blocked: the request still waits for the next app launch
        }
    }

    static void clearPendingNotification(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(REVIEW_NOTIF_ID);
    }

    // =============================================================== trusted apps

    /** SHA-256 of the app's signing certificate(s): trust follows the app, not just the package name. */
    @SuppressWarnings("deprecation")
    static String signature(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = pi.signingInfo;
                sigs = si == null ? null : si.getApkContentsSigners();
            } else {
                sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length == 0) return "";
            List<String> hashes = new ArrayList<>();
            for (Signature s : sigs) {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : h) sb.append(String.format("%02x", b));
                hashes.add(sb.toString());
            }
            Collections.sort(hashes);
            return String.join(",", hashes);
        } catch (Exception e) {
            return "";
        }
    }

    private static SharedPreferences trustSp(Context c) {
        return c.getApplicationContext().getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE);
    }

    static boolean isTrusted(Context c, String pkg) {
        String v = trustSp(c).getString(pkg, null);
        if (v == null) return false;
        try {
            String sig = new JSONObject(v).optString("sig", "");
            return !sig.isEmpty() && sig.equals(signature(c, pkg));     // reinstalled by someone else: not trusted
        } catch (JSONException e) {
            return false;
        }
    }

    /** Only the caller of the request the WebUI took last may be added, and only when it was trustable. */
    static boolean trust(Context c, String pkg) {
        if (pkg == null || !pkg.equals(lastTakenTrustable)) return false;
        String sig = signature(c, pkg);
        if (sig.isEmpty()) return false;
        try {
            trustSp(c).edit().putString(pkg, new JSONObject().put("sig", sig)
                    .put("since", System.currentTimeMillis()).toString()).apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /** "Always allow" chosen in {@link ExternalPromptActivity}: only for a trustable request (explicit intent, verified native caller). */
    static boolean trustApproved(Context c, JSONObject req) {
        if (req == null || !req.optBoolean("trustable")) return false;
        String pkg = req.optString("caller", "");
        if (pkg.isEmpty()) return false;
        lastTakenTrustable = pkg;
        return trust(c, pkg);
    }

    static void untrust(Context c, String pkg) {
        if (pkg != null) trustSp(c).edit().remove(pkg).apply();
    }

    /** [{pkg,label,since,installed}] for the "Access" tab; entries of uninstalled apps are kept until revoked. */
    static JSONArray trustedJson(Context c) {
        JSONArray out = new JSONArray();
        for (java.util.Map.Entry<String, ?> e : trustSp(c).getAll().entrySet()) {
            try {
                JSONObject v = new JSONObject(String.valueOf(e.getValue()));
                boolean installed;
                try {
                    c.getPackageManager().getPackageInfo(e.getKey(), 0);
                    installed = true;
                } catch (PackageManager.NameNotFoundException nf) {
                    installed = false;
                }
                out.put(new JSONObject().put("pkg", e.getKey())
                        .put("label", installed ? labelOf(c, e.getKey()) : e.getKey())
                        .put("since", v.optLong("since"))
                        .put("installed", installed));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    // =============================================================== the session

    private static int bootCount(Context c) {
        try {
            return android.provider.Settings.Global.getInt(c.getContentResolver(), "boot_count", -1);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The session metadata, or null. A session from before the last reboot is dropped: it never resumes by itself. */
    private static JSONObject read(Context c) {
        String s = sp(c).getString(K_SESSION, "");
        if (s == null || s.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(s);
            int was = o.optInt("boot", -1), now = bootCount(c);
            if (was != -1 && now != -1 && was != now) {
                sp(c).edit().remove(K_SESSION).apply();
                purgeNonRootDir(c);
                return null;
            }
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean isActive(Context c) {
        return read(c) != null;
    }

    /** Display name of the running external session, "" when there is none. */
    static String name(Context c) {
        JSONObject s = read(c);
        return s == null ? "" : s.optString("name", "");
    }

    static String callerLabel(Context c) {
        JSONObject s = read(c);
        return s == null ? "" : s.optString("callerLabel", "");
    }

    /** {"active":false} or {"active":true,name,caller,callerLabel,exclude,sections,wasRunning,startedAt}. No server address, no settings. */
    static JSONObject state(Context c) {
        JSONObject s = read(c);
        try {
            return s == null ? new JSONObject().put("active", false) : new JSONObject(s.toString()).put("active", true);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** The WebUI has written the session files into {@link #overrideDir}: remember the session (metadata only). */
    static boolean begin(Context c, String json) {
        try {
            JSONObject in = new JSONObject(json);
            JSONObject prev = read(c);
            JSONObject s = new JSONObject()
                    .put("name", clean(in.optString("name"), MAX_NAME))
                    .put("caller", in.optString("caller"))
                    .put("callerLabel", clean(in.optString("callerLabel"), MAX_NAME))
                    .put("exclude", in.optJSONArray("exclude") == null ? new JSONArray() : in.getJSONArray("exclude"))
                    .put("sections", in.optJSONArray("sections") == null ? new JSONArray() : in.getJSONArray("sections"))
                    // a second connect while one is active keeps what the first one remembered
                    .put("wasRunning", prev != null ? prev.optBoolean("wasRunning") : in.optBoolean("wasRunning"))
                    .put("startedAt", System.currentTimeMillis())
                    .put("boot", bootCount(c));
            // Deliberately not stored: the node uri and every overridden value. They exist only in override/ (RAM or cache).
            sp(c).edit().putString(K_SESSION, s.toString()).apply();
            clearPendingNotification(c);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * Where a session keeps its files. Root: tmpfs under /dev (gone at reboot); never /data/adb/magic_v2ray.
     * Non-root: the cache directory; never files/data.
     */
    static String overrideDir(Context c) {
        return RunMode.isNonRoot(c) ? NonRootEnv.overrideDir(c).getPath() : "/dev/sysctl_stubs/run/override";
    }

    private static void purgeNonRootDir(Context c) {
        File d = NonRootEnv.overrideDir(c);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    /**
     * App start: an override directory with no live session is a leftover (crash, reboot in non-root mode where the
     * cache survives). Delete it. Root mode needs nothing: /dev is wiped by the reboot and the service clears it at start.
     */
    static void sweepStale(Context c) {
        Context app = c.getApplicationContext();
        if (!isActive(app) && RunMode.isNonRoot(app)) purgeNonRootDir(app);
    }

    /** Session files gone (the user's own files were never touched). Same shell in both modes. */
    private static void purgeFiles(Context c) {
        if (RunMode.isNonRoot(c)) {
            purgeNonRootDir(c);
            return;
        }
        RootShell.init(c);
        RootShell.run("rm -rf " + RootShell.sq(overrideDir(c)) + "\n", 30_000);
    }

    /**
     * Files left by older versions, which kept a session inside the data dir: put the user's config back.
     * Only runs when such a leftover exists.
     */
    private static void restoreLegacy(Context c) {
        RootShell.init(c);
        String d = RunMode.isNonRoot(c) ? NonRootEnv.datadir(c).getPath() : ModuleInstaller.DATADIR;
        RootShell.run("D=" + RootShell.sq(d) + "\n"
                + "if [ -f \"$D/" + BASE_FILE + "\" ]; then cp -f \"$D/" + BASE_FILE + "\" \"$D/config.v2.json\" && chmod 600 \"$D/config.v2.json\"; fi\n"
                + "rm -f \"$D/" + BASE_FILE + "\" \"$D/excludelist.session.txt\"\n", 30_000);
    }

    /**
     * Ends the session and goes back to the user's own setup: session files are deleted FIRST (so the engine falls back to
     * the user's untouched config / settings), then the engine is restarted when it was running before, stopped when not.
     * Blocks, call it from a worker thread.
     */
    static void end(Context c) {
        Context app = c.getApplicationContext();
        JSONObject s;
        synchronized (LOCK) {
            s = read(app);
            if (s == null) return;
            sp(app).edit().remove(K_SESSION).apply();
        }
        purgeFiles(app);
        restoreLegacy(app);
        if (s.optBoolean("wasRunning")) {
            if (RunMode.isNonRoot(app)) {
                VpnController.handle(app, null, "restart");
            } else {
                RootShell.run("sh " + RootShell.sq(ModuleInstaller.MODDIR + "/proxy_control.sh") + " restart 2>&1", 60_000);
                ProxyNotifier.showRoot(app, true);
            }
        } else {
            ProxyNotifier.stopEngine(app);
        }
        pingUi(app);
    }

    /**
     * The session outlived its engine (crash, stopped some other way): drop it without touching the engine. Too young
     * a session is left alone, its engine may still be starting.
     */
    static boolean abandon(Context c) {
        Context app = c.getApplicationContext();
        synchronized (LOCK) {
            JSONObject s = read(app);
            if (s == null) return false;
            if (System.currentTimeMillis() - s.optLong("startedAt") < MIN_AGE_BEFORE_ABANDON_MS) return false;
            sp(app).edit().remove(K_SESSION).apply();
        }
        purgeFiles(app);
        restoreLegacy(app);
        pingUi(app);
        return true;
    }

    /** Lets a live WebUI refresh its status chip / banner after the session changed behind its back. */
    static void pingUi(Context c) {
        MainActivity m = MainActivity.current;
        if (m != null) m.pokeWeb("window.onExtSessionChanged&&window.onExtSessionChanged()");
    }
}
