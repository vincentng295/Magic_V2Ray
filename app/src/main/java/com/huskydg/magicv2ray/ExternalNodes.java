package com.huskydg.magicv2ray;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * API level 2, apps only: what nodes does the user have, which one is connected, and the permission an app needs
 * for that.
 *
 * <ul>
 *   <li><b>Permission</b>: an app has to be allowed by the user ({@link ExternalNodeAccessActivity}) before it can
 *       read anything. Two levels: {@link #LEVEL_ANONYMOUS} (default: name, protocol and an id, the node is
 *       {@code nodeid://<id>} instead of {@code vless://...}) and {@link #LEVEL_FULL} (the real node links). Allowed
 *       once ({@link #ONCE_TTL_MS}, memory only) or always (tied to the app's signing certificate).</li>
 *   <li><b>Who</b>: the caller is the package Android reports for the binder call / startActivityForResult, never
 *       something the caller wrote. A deep link, a website or a broadcast can not read nodes.</li>
 *   <li><b>nodeid://</b>: an app that may read nodes can also use them without ever seeing them: a connect request
 *       may carry {@code nodeid://<id>} as the node, or as a hop of a chain.</li>
 * </ul>
 *
 * The nodes are read from the same files the WebUI writes (profiles.base64, active_config.txt), so nothing is
 * mirrored anywhere else.
 */
final class ExternalNodes {

    static final String ACTION_REQUEST_ACCESS = "com.huskydg.magicv2ray.action.REQUEST_NODE_ACCESS";
    /** Optional extra of the request: the level the app would like, "anonymous" (default) or "full". */
    static final String EXTRA_LEVEL = "level";
    static final String RESULT_GRANTED = "granted";
    static final String RESULT_LEVEL = "level";
    static final String RESULT_PERSISTENT = "persistent";
    static final String RESULT_ERROR = "error";

    static final String LEVEL_NONE = "";
    static final String LEVEL_ANONYMOUS = "anonymous";
    static final String LEVEL_FULL = "full";

    static final String NODEID_PREFIX = "nodeid://";
    static final String FREEDOM_URI = "freedom://localhost";
    static final String CHAIN_PREFIX = "chain://localhost/?hopList=";

    /** {@code policygroup://localhost/?type=<strategy>&members=<urlencode(JSON array of node uris)>} (API level 3). */
    static final String POLICY_PREFIX = "policygroup://localhost/?type=";
    static final int POLICY_MIN_MEMBERS = 2;
    static final int POLICY_MAX_MEMBERS = 256;
    static final int MAX_POLICY_URI = 1024 * 1024;
    /** Xray balancer strategies, the same four as a saved policy group. */
    static final String[] POLICY_TYPES = {"random", "roundRobin", "leastPing", "leastLoad"};

    static final int CHAIN_MIN_HOPS = 2;
    static final int CHAIN_MAX_HOPS = 16;
    static final int MAX_URI = 8192;
    /** A chain carries up to {@link #CHAIN_MAX_HOPS} node uris, url-encoded inside one uri. */
    static final int MAX_CHAIN_URI = 128 * 1024;

    /** "Allow once" lasts this long, or until the Magic V2Ray process ends. */
    static final long ONCE_TTL_MS = 10 * 60_000L;

    private static final String PREFS = "ext_nodes";
    private static final Pattern ID_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern HOP_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://\\S+$");
    private static final long MAX_FILE = 32L * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static final Map<String, Grant> ONCE = new HashMap<>();

    private static final class Grant {
        final String level;
        final String sig;
        final long expires;

        Grant(String level, String sig, long expires) {
            this.level = level;
            this.sig = sig;
            this.expires = expires;
        }
    }

    private ExternalNodes() {
    }

    // =============================================================== uris

    static boolean isNodeId(String uri) {
        return uri != null && uri.regionMatches(true, 0, NODEID_PREFIX, 0, NODEID_PREFIX.length());
    }

    static boolean isChain(String uri) {
        return uri != null && uri.regionMatches(true, 0, "chain://", 0, 8);
    }

    static boolean isFreedom(String uri) {
        return uri != null && uri.regionMatches(true, 0, "freedom://", 0, 10);
    }

    static boolean isPolicyGroup(String uri) {
        return uri != null && uri.regionMatches(true, 0, "policygroup://", 0, 14);
    }

    /** The strategy of a policygroup:// uri ("random" when absent), or null when it is not one of {@link #POLICY_TYPES}. */
    static String policyGroupType(String uri) {
        if (!isPolicyGroup(uri)) return null;
        try {
            String t = Uri.parse(uri.trim()).getQueryParameter("type");
            if (t == null || t.isEmpty()) return "random";
            for (String k : POLICY_TYPES) if (k.equals(t)) return k;
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * The members of a {@code policygroup://} uri, or null when it is not valid: a known {@code type}, 2 to 256 members,
     * each a node uri or a {@code nodeid://}, never a chain, a freedom node or another group. Xray picks among the members
     * with the strategy; every member is a complete outbound of its own.
     */
    static List<String> policyGroupMembers(String uri) {
        if (!isPolicyGroup(uri) || uri.length() > MAX_POLICY_URI || policyGroupType(uri) == null) return null;
        try {
            String list = Uri.parse(uri.trim()).getQueryParameter("members");
            if (list == null) return null;
            JSONArray arr = new JSONArray(list);
            if (arr.length() < POLICY_MIN_MEMBERS || arr.length() > POLICY_MAX_MEMBERS) return null;
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.get(i);
                if (!(v instanceof String)) return null;
                String m = ((String) v).trim();
                if (m.length() > MAX_URI || !HOP_RE.matcher(m).matches()) return null;
                if (isChain(m) || isFreedom(m) || isPolicyGroup(m) || m.regionMatches(true, 0, "pgroup://", 0, 9)) return null;
                if (isNodeId(m) && !validNodeIdUri(m)) return null;
                out.add(m);
            }
            return out;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    static String buildPolicyGroup(String type, List<String> members) {
        JSONArray a = new JSONArray();
        for (String m : members) a.put(m);
        return POLICY_PREFIX + Uri.encode(type) + "&members=" + Uri.encode(a.toString());
    }

    static String idOf(String nodeIdUri) {
        return nodeIdUri.substring(NODEID_PREFIX.length()).trim();
    }

    /** {@code nodeid://<id>} with a well-formed id. */
    static boolean validNodeIdUri(String uri) {
        return isNodeId(uri) && ID_RE.matcher(idOf(uri)).matches();
    }

    /**
     * The hops of a {@code chain://localhost/?hopList=<urlencode(JSON array)>} uri (the old {@code hop1} / {@code hop2}
     * form is still read), or null when it is not a valid chain: 2 to 16 hops, each a node uri or a {@code nodeid://},
     * never another chain or a freedom node.
     */
    static List<String> chainHops(String uri) {
        if (!isChain(uri)) return null;
        try {
            Uri u = Uri.parse(uri.trim());
            List<String> hops = new ArrayList<>();
            String list = u.getQueryParameter("hopList");
            if (list != null) {
                JSONArray arr = new JSONArray(list);
                if (arr.length() > CHAIN_MAX_HOPS) return null;
                for (int i = 0; i < arr.length(); i++) {
                    Object v = arr.get(i);
                    if (!(v instanceof String)) return null;
                    hops.add(((String) v).trim());
                }
            } else {
                String a = u.getQueryParameter("hop1");
                String b = u.getQueryParameter("hop2");
                if (a == null || b == null) return null;
                hops.add(a.trim());
                hops.add(b.trim());
            }
            if (hops.size() < CHAIN_MIN_HOPS || hops.size() > CHAIN_MAX_HOPS) return null;
            for (String h : hops) {
                if (h.length() > MAX_URI || !HOP_RE.matcher(h).matches()) return null;
                if (isChain(h) || isFreedom(h)) return null;
                if (isNodeId(h) && !validNodeIdUri(h)) return null;
            }
            return hops;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    static String buildChain(List<String> hops) {
        JSONArray a = new JSONArray();
        for (String h : hops) a.put(h);
        return CHAIN_PREFIX + Uri.encode(a.toString());
    }

    static boolean hasNodeRefs(String uri) {
        if (isNodeId(uri)) return true;
        if (isPolicyGroup(uri)) {
            List<String> ms = policyGroupMembers(uri);
            if (ms != null) for (String m : ms) if (isNodeId(m)) return true;
            return false;
        }
        List<String> hops = chainHops(uri);
        if (hops == null) return false;
        for (String h : hops) if (isNodeId(h)) return true;
        return false;
    }

    /** Whether a connect request's uri is one this build understands (called before the request is parked). */
    static boolean validConnectUri(String uri) {
        if (uri == null) return false;
        if (isChain(uri)) return uri.length() <= MAX_CHAIN_URI && chainHops(uri) != null;
        if (isPolicyGroup(uri)) return policyGroupMembers(uri) != null;
        if (isNodeId(uri)) return validNodeIdUri(uri);
        return uri.length() <= MAX_URI && uri.matches("^[A-Za-z][A-Za-z0-9+.-]*://\\S+$");
    }

    /**
     * The uri with every {@code nodeid://} replaced by the user's real node, for the confirmation dialog (warnings, the
     * server shown) only. Null when the caller may not use nodes or a node does not exist. Never shown or returned to
     * an app.
     */
    static String expandRefs(Context c, String pkg, String uri) {
        if (!hasNodeRefs(uri)) return uri;
        if (LEVEL_NONE.equals(accessLevel(c, pkg))) return null;
        Map<String, JSONObject> byId = nodesById(c);
        if (byId == null) return null;
        if (isNodeId(uri)) {
            JSONObject n = byId.get(idOf(uri));
            return n == null ? null : n.optString("rawUri");
        }
        if (isPolicyGroup(uri)) {
            List<String> ms = policyGroupMembers(uri);
            if (ms == null) return null;
            List<String> res = new ArrayList<>();
            for (String m : ms) {
                if (!isNodeId(m)) { res.add(m); continue; }
                JSONObject n = byId.get(idOf(m));
                if (n == null) return null;
                String raw = n.optString("rawUri");
                if (isChain(raw) || isFreedom(raw) || raw.regionMatches(true, 0, "pgroup://", 0, 9)) return null;
                res.add(raw);
            }
            return buildPolicyGroup(policyGroupType(uri), res);
        }
        List<String> hops = chainHops(uri);
        if (hops == null) return null;
        List<String> out = new ArrayList<>();
        for (String h : hops) {
            if (isNodeId(h)) {
                JSONObject n = byId.get(idOf(h));
                if (n == null) return null;
                String raw = n.optString("rawUri");
                if (isChain(raw) || isFreedom(raw)) return null;       // a chain can not be a hop
                out.add(raw);
            } else {
                out.add(h);
            }
        }
        return buildChain(out);
    }

    /** Host the device dials first: the node's, the entry hop's for a chain, nothing for freedom://. */
    static String entryHost(String uri) {
        if (uri == null || isFreedom(uri)) return "";
        String target = uri;
        if (isPolicyGroup(uri)) {
            List<String> ms = policyGroupMembers(uri);
            if (ms == null || ms.isEmpty()) return "";
            target = ms.get(0);
        } else if (isChain(uri)) {
            List<String> hops = chainHops(uri);
            if (hops == null || hops.isEmpty()) return "";
            target = hops.get(0);
        }
        try {
            String h = Uri.parse(target).getHost();
            return h == null ? "" : h;
        } catch (RuntimeException e) {
            return "";
        }
    }

    // =============================================================== permission

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int rank(String level) {
        return LEVEL_FULL.equals(level) ? 2 : LEVEL_ANONYMOUS.equals(level) ? 1 : 0;
    }

    private static String cleanLevel(String level) {
        return LEVEL_FULL.equals(level) ? LEVEL_FULL : LEVEL_ANONYMOUS;
    }

    /** What {@code pkg} may read now: {@link #LEVEL_NONE}, {@link #LEVEL_ANONYMOUS} or {@link #LEVEL_FULL}. */
    static String accessLevel(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return LEVEL_NONE;
        String sig = ExternalSession.signature(c, pkg);
        if (sig.isEmpty()) return LEVEL_NONE;           // not installed / no certificate: nothing to tie a grant to
        String once = LEVEL_NONE;
        synchronized (LOCK) {
            Grant g = ONCE.get(pkg);
            if (g != null) {
                if (g.expires < System.currentTimeMillis()) ONCE.remove(pkg);
                else if (g.sig.equals(sig)) once = g.level;
            }
        }
        String perm = permanentLevel(c, pkg, sig);
        return rank(once) >= rank(perm) ? once : perm;
    }

    private static String permanentLevel(Context c, String pkg, String sig) {
        String v = sp(c).getString(pkg, null);
        if (v == null) return LEVEL_NONE;
        try {
            JSONObject o = new JSONObject(v);
            if (!sig.equals(o.optString("sig", ""))) return LEVEL_NONE;    // reinstalled by someone else
            return cleanLevel(o.optString("level"));
        } catch (JSONException e) {
            return LEVEL_NONE;
        }
    }

    static boolean isPermanent(Context c, String pkg) {
        String sig = ExternalSession.signature(c, pkg);
        return !sig.isEmpty() && !LEVEL_NONE.equals(permanentLevel(c, pkg, sig));
    }

    /** The user chose in {@link ExternalNodeAccessActivity}. {@code always} = kept until revoked, else {@link #ONCE_TTL_MS}. */
    static boolean grant(Context c, String pkg, String level, boolean always) {
        if (pkg == null || pkg.isEmpty()) return false;
        String sig = ExternalSession.signature(c, pkg);
        if (sig.isEmpty()) return false;
        level = cleanLevel(level);
        if (always) {
            try {
                long since = System.currentTimeMillis();
                String old = sp(c).getString(pkg, null);
                if (old != null) since = new JSONObject(old).optLong("since", since);
                sp(c).edit().putString(pkg, new JSONObject().put("sig", sig).put("level", level).put("since", since)
                        .toString()).apply();
            } catch (JSONException e) {
                return false;
            }
            synchronized (LOCK) {
                ONCE.remove(pkg);       // the lasting choice replaces any earlier one-time grant
            }
        } else {
            synchronized (LOCK) {
                ONCE.put(pkg, new Grant(level, sig, System.currentTimeMillis() + ONCE_TTL_MS));
            }
        }
        return true;
    }

    static void revoke(Context c, String pkg) {
        if (pkg == null) return;
        sp(c).edit().remove(pkg).apply();
        synchronized (LOCK) {
            ONCE.remove(pkg);
        }
    }

    /** pkg -> {level, since} of the lasting grants, for the "Access permissions" tab. */
    static Map<String, JSONObject> permanentGrants(Context c) {
        Map<String, JSONObject> out = new HashMap<>();
        for (Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            try {
                JSONObject v = new JSONObject(String.valueOf(e.getValue()));
                out.put(e.getKey(), new JSONObject().put("level", cleanLevel(v.optString("level")))
                        .put("since", v.optLong("since")));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    // =============================================================== reading the user's files

    private static String dataDir(Context c) {
        return RunMode.isNonRoot(c) ? NonRootEnv.datadir(c).getPath() : ModuleInstaller.DATADIR;
    }

    /** File text, "" when missing, null when it could not be read at all (root shell refused). */
    private static String readFile(Context c, String path) {
        try {
            if (RunMode.isNonRoot(c)) {
                File f = new File(path);
                if (!f.isFile()) return "";
                if (f.length() > MAX_FILE) return null;
                try (InputStream in = new FileInputStream(f)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    return new String(bo.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            RootShell.init(c.getApplicationContext());
            RootShell.Result r = RootShell.run("[ -f " + RootShell.sq(path) + " ] && cat " + RootShell.sq(path)
                    + " 2>/dev/null; exit 0", 10_000);
            return r.code == 0 ? (r.out == null ? "" : r.out) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** {category: {url, nodes:[...]}} as the WebUI saved it; null when the file can not be read. */
    private static JSONObject profiles(Context c) {
        String raw = readFile(c, dataDir(c) + "/profiles.base64");
        if (raw == null) return null;
        raw = raw.trim().replace('-', '+').replace('_', '/');
        if (raw.isEmpty()) return new JSONObject();
        try {
            StringBuilder sb = new StringBuilder(raw);
            while (sb.length() % 4 != 0) sb.append('=');
            return new JSONObject(new String(Base64.decode(sb.toString(), Base64.DEFAULT), StandardCharsets.UTF_8));
        } catch (JSONException | IllegalArgumentException e) {
            return new JSONObject();
        }
    }

    /** Every node of every category (policy groups left out), each with its "category" added. Null = unreadable. */
    private static List<JSONObject> nodes(Context c) {
        JSONObject p = profiles(c);
        if (p == null) return null;
        List<JSONObject> out = new ArrayList<>();
        Iterator<String> it = p.keys();
        while (it.hasNext()) {
            String cat = it.next();
            Object v = p.opt(cat);
            JSONArray arr = v instanceof JSONArray ? (JSONArray) v
                    : v instanceof JSONObject ? ((JSONObject) v).optJSONArray("nodes") : null;
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject n = arr.optJSONObject(i);
                if (n == null) continue;
                String id = n.optString("id", "");
                String raw = n.optString("rawUri", "");
                if (!ID_RE.matcher(id).matches() || raw.isEmpty() || raw.startsWith("pgroup://")) continue;
                try {
                    out.add(new JSONObject(n.toString()).put("category", cat));
                } catch (JSONException ignored) {
                }
            }
        }
        return out;
    }

    private static Map<String, JSONObject> nodesById(Context c) {
        List<JSONObject> all = nodes(c);
        if (all == null) return null;
        Map<String, JSONObject> m = new HashMap<>();
        for (JSONObject n : all) if (!m.containsKey(n.optString("id"))) m.put(n.optString("id"), n);
        return m;
    }

    /** The id of the node the user selected ("category:id" in active_config.txt), "" when none (the direct node). */
    private static String selectedId(Context c) {
        String s = readFile(c, dataDir(c) + "/active_config.txt");
        if (s == null) return "";
        s = s.trim();
        int i = s.indexOf(':');
        return i < 0 ? "" : s.substring(i + 1);
    }

    // =============================================================== what an app gets

    /** The raw uri written the way the API shows it: a chain always as {@code hopList}. */
    private static String normalized(String raw) {
        if (isChain(raw)) {
            List<String> hops = chainHops(raw);
            if (hops != null) return buildChain(hops);
        }
        return raw;
    }

    /** What is shown instead of a uri the app may not see: the node it is, or just its kind. */
    private static String anonymize(String raw, List<JSONObject> all) {
        if (isFreedom(raw)) return FREEDOM_URI;
        if (all != null) {
            for (JSONObject n : all) if (raw.equals(n.optString("rawUri"))) return NODEID_PREFIX + n.optString("id");
        }
        if (isPolicyGroup(raw)) {
            List<String> ms = policyGroupMembers(raw);
            if (ms == null) return "policygroup://hidden";
            List<String> out = new ArrayList<>();
            for (String m : ms) out.add(anonymize(m, all));
            return buildPolicyGroup(policyGroupType(raw), out);
        }
        if (isChain(raw)) {
            List<String> hops = chainHops(raw);
            if (hops == null) return "chain://hidden";
            List<String> out = new ArrayList<>();
            for (String h : hops) out.add(anonymize(h, all));
            return buildChain(out);
        }
        int i = raw.indexOf("://");
        return (i > 0 ? raw.substring(0, i).toLowerCase(java.util.Locale.ROOT) : "node") + "://hidden";
    }

    private static String schemeOf(String raw) {
        int i = raw.indexOf("://");
        String s = i > 0 ? raw.substring(0, i).toLowerCase(java.util.Locale.ROOT) : "";
        return s.equals("shadowsocks") ? "ss" : s.equals("socks5") ? "socks" : s.equals("hy2") ? "hysteria2" : s;
    }

    private static JSONObject nodeJson(JSONObject n, String level, List<JSONObject> all) throws JSONException {
        String raw = n.optString("rawUri");
        JSONObject j = new JSONObject()
                .put("id", n.optString("id"))
                .put("name", n.optString("name", ""))
                .put("protocol", n.optString("protocol", schemeOf(raw)));
        if (LEVEL_FULL.equals(level)) {
            j.put("uri", normalized(raw))
                    .put("category", n.optString("category", ""))
                    .put("address", n.optString("address", ""))
                    .put("port", n.optString("port", ""));
        } else {
            j.put("uri", anonymize(raw, all));
        }
        return j;
    }

    /**
     * {@code {level, activeId, nodes:[{id, name, protocol, uri, (full: category, address, port)}]}}.
     * Anonymous: {@code uri} is {@code nodeid://<id>}. Returns null when the user's files can not be read.
     */
    static JSONObject nodesJson(Context c, String level) {
        List<JSONObject> all = nodes(c);
        if (all == null) return null;
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject n : all) arr.put(nodeJson(n, level, all));
            return new JSONObject().put("level", level).put("activeId", selectedId(c)).put("nodes", arr);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * The node in use: {@code {connected, known, source, session?, node}}. {@code source} is "session" (an app set it
     * for a while), "selected" (the user's own pick) or "direct" (the built-in no-proxy node). A node an app set
     * that is not one of the user's is hidden from an anonymous reader (its uri becomes {@code <scheme>://hidden}).
     */
    static JSONObject activeJson(Context c, String level) {
        Context app = c.getApplicationContext();
        List<JSONObject> all = nodes(app);
        if (all == null) return null;
        try {
            JSONObject st = ExternalApi.state(app);
            JSONObject session = st.optJSONObject("session");
            boolean inSession = session != null && session.optBoolean("active");
            String raw = "";
            String source;
            String name = "";
            if (inSession) {
                source = "session";
                name = session.optString("name", "");
                String s = readFile(app, ExternalSession.overrideDir(app) + "/session.json");
                if (s != null && !s.trim().isEmpty()) {
                    try {
                        raw = new JSONObject(s).optString("rawUri", "");
                    } catch (JSONException ignored) {
                    }
                }
            } else {
                String id = selectedId(app);
                for (JSONObject n : all) if (id.equals(n.optString("id"))) { raw = n.optString("rawUri"); break; }
                source = raw.isEmpty() ? "direct" : "selected";
            }
            if (raw.isEmpty()) raw = FREEDOM_URI;

            JSONObject match = null;
            for (JSONObject n : all) if (raw.equals(n.optString("rawUri"))) { match = n; break; }
            JSONObject node;
            if (match != null) {
                node = nodeJson(match, level, all);
            } else {
                node = new JSONObject().put("id", "").put("name", name).put("protocol", isFreedom(raw) ? "freedom" : schemeOf(raw))
                        .put("uri", LEVEL_FULL.equals(level) ? (isFreedom(raw) ? FREEDOM_URI : normalized(raw)) : anonymize(raw, all));
                if (LEVEL_FULL.equals(level) && !isFreedom(raw) && !isChain(raw)) {
                    String host = entryHost(raw);
                    int port = -1;
                    try {
                        port = Uri.parse(raw).getPort();
                    } catch (RuntimeException ignored) {
                    }
                    node.put("category", "").put("address", host).put("port", port > 0 ? String.valueOf(port) : "");
                }
            }
            JSONObject out = new JSONObject()
                    .put("connected", st.optBoolean("connected"))
                    .put("known", st.optBoolean("known"))
                    .put("source", source)
                    .put("node", node);
            if (inSession) {
                out.put("session", new JSONObject().put("name", session.optString("name", ""))
                        .put("caller", session.optString("caller", "")));
            }
            return out;
        } catch (JSONException e) {
            return null;
        }
    }
}
