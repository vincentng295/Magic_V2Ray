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
    /** API level 3: content:// uri of a file holding the overrides JSON (no size trouble with an intent). Needs a ClipData grant. */
    static final String EXTRA_OVERRIDES_URI = "overridesUri";
    /** API level 3: JSON array [{"name":"geoip.dat","uri":"content://..."}] of custom .dat databases. Needs a ClipData grant. */
    static final String EXTRA_ASSETS = "assets";

    static final String BASE_FILE = "config.base.json";
    static final String SESSION_EXCLUDE_FILE = "excludelist.session.txt";

    /** Inline overrides (extra / deep link). An intent cannot carry much more anyway; bigger sets go through overridesUri. */
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
    // What take() needs from the parked request, kept next to it so a request with a huge rule set is not parsed a second time.
    private static long pendingTs;
    private static boolean pendingTrustable;
    private static String pendingCaller = "";
    private static String pendingOvHash = "";
    private static String pendingStageId = "";
    private static final Object LOCK = new Object();
    /** Caller of the request the WebUI took last, if that caller may be trusted ("always trust this app"). */
    private static volatile String lastTakenTrustable;
    /** Fingerprint of the overrides of that same request ("" when it had none). */
    private static volatile String lastTakenOvHash = "";

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
            lastError = "";
            JSONObject ov;
            String stageId = "";
            if ("deeplink".equals(source)) {
                ov = sanitizeOverrides(overrides);      // a link can carry neither files nor big sets
            } else {
                // API level 3: the overrides may sit in a file (overridesUri) and custom .dat databases may come along
                // (assets). Both are content:// uris the caller granted; they are read here, so never on the main thread
                // (see hasFileParts).
                String ovUri = i.getStringExtra(EXTRA_OVERRIDES_URI);
                String assetsRaw = i.getStringExtra(EXTRA_ASSETS);
                try {
                    ov = ovUri != null && !ovUri.trim().isEmpty()
                            ? sanitizeOverrides(ExternalAssets.readOverridesText(c, i, ovUri), ExternalAssets.MAX_OVERRIDES_FILE_BYTES)
                            : sanitizeOverrides(overrides);
                    if (assetsRaw != null && !assetsRaw.trim().isEmpty()) {
                        String id = ExternalAssets.newId();
                        JSONArray staged = ExternalAssets.stage(c, i, assetsRaw, id);
                        if (staged.length() > 0) {
                            stageId = id;
                            ov.put("assets", staged);       // name, size, sha256 only: part of the fingerprint
                        }
                    }
                } catch (ExternalAssets.FileError e) {
                    lastError = e.getMessage();
                    return null;
                }
            }
            // The node is optional when settings are overridden: the node the user selected keeps being used.
            // freedom://localhost, chain://localhost/?hopList=<urlencode(JSON array)> and nodeid://<id> are accepted too
            if (uri.isEmpty() ? ov.length() == 0 : !ExternalNodes.validConnectUri(uri)) {
                ExternalAssets.discard(c, stageId);
                return null;
            }
            boolean browser = caller != null && isBrowser(c, caller);
            // Trust is only offered for calls that app code made on purpose (an explicit action):
            // a link can come from anywhere (a chat message, a web page), whichever app opened it.
            // An app the user chose to "always allow" (explicit intent from a verified native app, tied to its signing
            // certificate) is not asked again, whatever settings it sends with the request. Links and browsers are
            // never trustable.
            boolean trustable = !"deeplink".equals(source) && caller != null && !browser;
            String ovHash = overridesHash(ov);
            boolean callerTrusted = trustable && isTrusted(c, caller);
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
                    .put("trusted", callerTrusted)
                    .put("ovHash", ovHash)
                    .put("stageId", stageId)
                    .put("ts", System.currentTimeMillis());
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Why the last {@link #request} was refused because of its files (shown to the user), "" if not that. Read once. */
    private static volatile String lastError = "";

    static String takeError() {
        String e = lastError;
        lastError = "";
        return e;
    }

    /**
     * Does this intent carry files (API level 3: {@code overridesUri}, {@code assets})? Then {@link #request} reads and
     * hashes them, which can take a while: the caller has to run it off the main thread. Deep links never carry files.
     */
    static boolean hasFileParts(Intent i) {
        if (i == null || !ACTION_CONNECT.equals(i.getAction())) return false;
        String a = i.getStringExtra(EXTRA_OVERRIDES_URI), b = i.getStringExtra(EXTRA_ASSETS);
        return (a != null && !a.trim().isEmpty()) || (b != null && !b.trim().isEmpty());
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
            "mux", "b", "mux_connections", "i:1:1024",
            "fragment", "b", "fragment_packets", "s:32", "fragment_length", "s:32", "fragment_interval", "s:32",
            "mtu", "i:1280:9000", "localDns", "b", "fakeDnsLocal", "b",
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
    // API level 3: no limit on the number of routing rules, hosts, or addresses per host. What stays is a ceiling on bytes:
    // MAX_OVERRIDES for inline text, ExternalAssets.MAX_OVERRIDES_FILE_BYTES for a file. Text fields of a rule (domain / ip
    // lists) are only bounded by that, too. The list of excluded apps keeps its cap: it is a list of installed packages.
    private static final int MAX_EXCLUDE_OVERRIDE = 500;

    private static void upd(java.security.MessageDigest md, String t) {
        md.update(t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Key-sorted, whitespace-free text of a JSON value, fed to the digest piece by piece (a big rule set must not be built
     * as one string first). The bytes are the same as the old string version, so fingerprints of apps the user already
     * trusts stay valid.
     */
    private static void canon(Object v, java.security.MessageDigest md) throws JSONException {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            java.util.List<String> keys = new java.util.ArrayList<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            java.util.Collections.sort(keys);
            upd(md, "{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) upd(md, ",");
                upd(md, JSONObject.quote(keys.get(i)));
                upd(md, ":");
                canon(o.get(keys.get(i)), md);
            }
            upd(md, "}");
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            upd(md, "[");
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) upd(md, ",");
                canon(a.get(i), md);
            }
            upd(md, "]");
        } else if (v instanceof String) {
            upd(md, JSONObject.quote((String) v));
        } else {
            upd(md, String.valueOf(v));
        }
    }

    /**
     * SHA-256 of the sanitized overrides, "" when there are none. A trusted app is only let through silently with the
     * overrides it was last allowed with; this fingerprint is what is compared (the overrides themselves are not stored).
     * Custom .dat files count too: {@code overrides.assets} holds each file's name, size and SHA-256.
     */
    static String overridesHash(JSONObject ov) {
        if (ov == null || ov.length() == 0) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            canon(ov, md);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (JSONException | java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

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

    /** A port or port range of a rule ("443", "1000-2000", "53,443"): a string, or a plain JSON number. */
    private static String rulePort(JSONObject r, String k) {
        Object v = r.opt(k);
        if (v instanceof Integer || v instanceof Long) return String.valueOf(v);
        return v instanceof String ? clean((String) v, 200) : "";
    }

    /**
     * The {@code process} list of a rule: apps the rule applies to, as a comma separated string or (like domain / ip) a
     * JSON array. Only Android package names and the token {@code __unknown_apps__} survive; anything else is dropped,
     * so an app cannot smuggle raw UIDs or free text into the rule. The WebUI resolves the packages to UIDs when the
     * config is built, and a rule whose apps are none of them installed is dropped there, never widened.
     */
    private static String ruleProcess(JSONObject r) {
        Object v = r.opt("process");
        java.util.List<String> raw = new java.util.ArrayList<>();
        if (v instanceof String) {
            for (String t : ((String) v).split("[,\\s]+")) raw.add(t);
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) raw.add(a.optString(i, ""));
        } else {
            return "";
        }
        java.util.LinkedHashSet<String> keep = new java.util.LinkedHashSet<>();
        for (String t : raw) {
            t = t == null ? "" : t.trim();
            if (t.isEmpty() || t.length() > 200) continue;
            if (t.equals("__unknown_apps__") || PKG_RE.matcher(t).matches()) keep.add(t);
        }
        return android.text.TextUtils.join(",", keep);
    }

    /**
     * A domain / ip list of a rule: the comma separated string the WebUI keeps, or (level 3) a JSON array of strings, which
     * is how a long list is naturally written. No length limit besides the size of the request itself.
     */
    private static String ruleList(JSONObject r, String k) {
        Object v = r.opt(k);
        if (v instanceof String) return clean((String) v, Integer.MAX_VALUE);
        if (!(v instanceof JSONArray)) return "";
        JSONArray a = (JSONArray) v;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            String e = clean(a.optString(i, ""), Integer.MAX_VALUE).replace(",", " ").trim();
            if (e.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(e);
        }
        return sb.toString();
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
     *                             "sourceIP": "192.168.43.0/24", "sourcePort": "1000-2000",
     *                             "process": ["com.example.app", "__unknown_apps__"],
     *                             "outboundTag": "proxy" | "direct" | "block", "remarks": "", "enabled": true } ] },
     *   "hosts":   { "mode": "replace" | "merge", "entries": { "example.com": "1.2.3.4", "b.com": ["::1", "10.0.0.1"] } } }
     * </pre>
     */
    static JSONObject sanitizeOverrides(String raw) {
        return sanitizeOverrides(raw, MAX_OVERRIDES);
    }

    /** {@code maxChars}: ceiling on the decoded JSON text (a deep link's base64 may be a third longer). */
    static JSONObject sanitizeOverrides(String raw, long maxChars) {
        JSONObject out = new JSONObject();
        if (raw == null) return out;
        raw = raw.trim();
        if (raw.isEmpty() || raw.length() > maxChars * 2) return out;
        try {
            String json = raw;
            if (!raw.startsWith("{")) {      // deep link: base64url
                byte[] b = android.util.Base64.decode(raw, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                json = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (json.length() > maxChars) return out;
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
                    for (int i = 0; i < ir.length(); i++) {
                        JSONObject r = ir.optJSONObject(i);
                        if (r == null) continue;
                        String tag = ruleStr(r, "outboundTag", 16);
                        if (!BUILTIN_TAGS.contains(tag)) continue;   // nodes / policy groups are the user's, not an app's
                        JSONObject o = new JSONObject();
                        o.put("remarks", ruleStr(r, "remarks", 80)).put("locked", false)
                                .put("domain", ruleList(r, "domain")).put("ip", ruleList(r, "ip"))
                                .put("port", rulePort(r, "port")).put("protocol", ruleStr(r, "protocol", 100))
                                .put("sourceIP", ruleList(r, "sourceIP")).put("sourcePort", rulePort(r, "sourcePort"))
                                .put("network", ruleStr(r, "network", 20)).put("process", ruleProcess(r))
                                .put("outboundTag", tag)
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
                while (it.hasNext()) {
                    String dom = it.next();
                    if (!DOMAIN_RE.matcher(dom).matches()) continue;
                    Object v = he.get(dom);
                    JSONArray ips = new JSONArray();
                    if (v instanceof String) {
                        if (IP_RE.matcher((String) v).matches()) ips.put(v);
                    } else if (v instanceof JSONArray) {
                        for (int i = 0; i < ((JSONArray) v).length(); i++) {
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
        String stale;
        synchronized (LOCK) {
            stale = pendingStageId;     // a newer request replaces the parked one: its staged files go with it
            pendingJson = req.toString();
            pendingTs = req.optLong("ts");
            pendingTrustable = req.optBoolean("trustable");
            pendingCaller = req.optString("caller");
            pendingOvHash = req.optString("ovHash", "");
            pendingStageId = req.optString("stageId", "");
        }
        if (!stale.isEmpty() && !stale.equals(req.optString("stageId", ""))) ExternalAssets.discard(c, stale);
    }

    /** Whether a request is parked and not taken yet (it is left parked). */
    static boolean hasPending() {
        synchronized (LOCK) {
            return pendingJson != null && !pendingJson.isEmpty();
        }
    }

    /** The parked request as JSON (\"\" if none or expired); returns it once. */
    static String take(Context c) {
        String expired = "";
        String out;
        synchronized (LOCK) {
            String s = pendingJson;
            pendingJson = "";
            lastTakenTrustable = null;
            lastTakenOvHash = "";
            String stage = pendingStageId;
            pendingStageId = "";
            if (s == null || s.isEmpty()) return "";
            if (System.currentTimeMillis() - pendingTs > PENDING_TTL_MS) {
                expired = stage;
                out = "";
            } else {
                if (pendingTrustable) {
                    lastTakenTrustable = pendingCaller;
                    lastTakenOvHash = pendingOvHash;
                }
                out = s;
            }
        }
        if (!expired.isEmpty()) ExternalAssets.discard(c, expired);
        return out;
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

    /** Fingerprint of the overrides this trusted app was last allowed with, "" if none. */
    static String trustedOverridesHash(Context c, String pkg) {
        String v = trustSp(c).getString(pkg, null);
        if (v == null) return "";
        try {
            return new JSONObject(v).optString("ov", "");
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * Only the caller of the request the WebUI took last may be added, and only when it was trustable.
     * "Always allow" also remembers that request's overrides: the app is let through silently with exactly those.
     * A request without overrides leaves the remembered ones alone.
     */
    static boolean trust(Context c, String pkg) {
        if (pkg == null || !pkg.equals(lastTakenTrustable)) return false;
        String sig = signature(c, pkg);
        if (sig.isEmpty()) return false;
        try {
            String ov = lastTakenOvHash.isEmpty() ? trustedOverridesHash(c, pkg) : lastTakenOvHash;
            long since = System.currentTimeMillis();
            String old = trustSp(c).getString(pkg, null);
            if (old != null) since = new JSONObject(old).optLong("since", since);     // re-allowing keeps the original date
            trustSp(c).edit().putString(pkg, new JSONObject().put("sig", sig)
                    .put("since", since).put("ov", ov).toString()).apply();
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
        lastTakenOvHash = req.optString("ovHash", "");
        return trust(c, pkg);
    }

    /** Revokes everything the app was allowed: connecting without a prompt and reading nodes. */
    static void untrust(Context c, String pkg) {
        if (pkg != null) trustSp(c).edit().remove(pkg).apply();
        ExternalNodes.revoke(c, pkg);
        ExternalAppLog.add(c, pkg, ExternalAppLog.K_REVOKED, "");
    }

    /**
     * [{pkg,label,since,installed,connect,nodes}] for the "Access" tab: apps trusted to connect (connect) and/or allowed
     * to read nodes (nodes = "anonymous" | "full", "" = no). Entries of uninstalled apps are kept until revoked.
     */
    static JSONArray trustedJson(Context c) {
        java.util.Map<String, JSONObject> rows = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, ?> e : trustSp(c).getAll().entrySet()) {
            try {
                JSONObject v = new JSONObject(String.valueOf(e.getValue()));
                rows.put(e.getKey(), new JSONObject().put("since", v.optLong("since")).put("connect", true).put("nodes", ""));
            } catch (JSONException ignored) {
            }
        }
        for (java.util.Map.Entry<String, JSONObject> e : ExternalNodes.permanentGrants(c).entrySet()) {
            try {
                JSONObject r = rows.get(e.getKey());
                if (r == null) {
                    r = new JSONObject().put("since", e.getValue().optLong("since")).put("connect", false);
                    rows.put(e.getKey(), r);
                }
                r.put("nodes", e.getValue().optString("level"));
            } catch (JSONException ignored) {
            }
        }
        // apps that were never always-allowed but have a log (asked once, denied...): listed so their log can be read
        for (Object[] lp : ExternalAppLog.packages(c)) {
            String pkg = (String) lp[0];
            if (rows.containsKey(pkg)) continue;
            try {
                rows.put(pkg, new JSONObject().put("since", (Long) lp[1]).put("connect", false).put("nodes", ""));
            } catch (JSONException ignored) {
            }
        }
        JSONArray out = new JSONArray();
        for (java.util.Map.Entry<String, JSONObject> e : rows.entrySet()) {
            try {
                boolean installed;
                try {
                    c.getPackageManager().getPackageInfo(e.getKey(), 0);
                    installed = true;
                } catch (PackageManager.NameNotFoundException nf) {
                    installed = false;
                }
                out.put(e.getValue().put("pkg", e.getKey())
                        .put("label", installed ? labelOf(c, e.getKey()) : e.getKey())
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
     * Where a session keeps its files. Root: the runtime dir under /mnt (tmpfs, gone at reboot); never /data/adb/magic_v2ray.
     * Non-root: the cache directory; never files/data.
     */
    static String overrideDir(Context c) {
        return RunMode.isNonRoot(c) ? NonRootEnv.overrideDir(c).getPath() : ModuleInstaller.STUB_DIR + "/run/override";
    }

    private static void purgeNonRootDir(Context c) {
        ExternalAssets.deleteTree(NonRootEnv.overrideDir(c));       // includes assets/ (custom .dat files)
    }

    /** The WebUI agreed: puts the staged custom .dat files into the session directory. Blocking. Null = ok, else the reason. */
    static String installAssets(Context c, String stageId) {
        return ExternalAssets.install(c.getApplicationContext(), stageId);
    }

    /** The request was denied, failed or replaced: the staged files are deleted. */
    static void discardStage(Context c, String stageId) {
        ExternalAssets.discard(c.getApplicationContext(), stageId);
    }

    /**
     * App start: an override directory with no live session is a leftover (crash, reboot in non-root mode where the
     * cache survives). Delete it. Root mode needs nothing: /dev is wiped by the reboot and the service clears it at start.
     */
    static void sweepStale(Context c) {
        Context app = c.getApplicationContext();
        ExternalAssets.sweepAll(app);       // staged files of a request the process did not survive
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
        try {
            ExternalApi.sessionEnded(app, new JSONObject(s.toString()).put("active", true));
        } catch (JSONException ignored) {
        }
        purgeFiles(app);
        restoreLegacy(app);
        if (s.optBoolean("wasRunning")) {
            if (RunMode.isNonRoot(app)) {
                VpnController.handle(app, null, "restart");
            } else {
                RootShell.run(RootShell.sq(ModuleInstaller.HELPER) + " proxy_control restart 2>&1", 60_000);
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
