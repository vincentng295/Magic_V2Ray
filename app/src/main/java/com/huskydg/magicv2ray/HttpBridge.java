package com.huskydg.magicv2ray;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The HTTP client behind {@code ksu.httpRequest} / {@code ksu.httpDownload}. It replaces every
 * {@code xhuskydg_helper curl ...} the WebUI used to run in a root shell: the request is made in the app
 * process (system trust store, system resolver) and only the result goes back to the page.
 *
 * Options (JSON):
 * <pre>
 *   url              http(s) URL (required)
 *   method           GET (default) | POST | HEAD
 *   headers          {name: value}
 *   body             request body (POST), UTF-8
 *   socks            "127.x.y.z:port": go through a loopback SOCKS5 inbound. The host name is sent to the
 *                    proxy unresolved (curl's --socks5-hostname); without it the request is direct
 *   schemes          "https" (default) | "http,https": what the URL and every redirect may use
 *   maxRedirects     default 3 (curl --max-redirs); 0 = do not follow, the 3xx answer is returned as is;
 *                    only GET / HEAD follow redirects
 *   timeoutMs        whole-request limit, default 15000 (curl --max-time)
 *   connectTimeoutMs default 15000
 *   maxBytes         response size limit, default 4 MB (request) / 256 MB (download) (curl --max-filesize)
 *   failOnError      default true: an HTTP status >= 400 is a failure (curl -f)
 *   allowInsecureTls default false: skip certificate and host name checks (curl -k), per request
 *   encoding         "text" (default, UTF-8) | "base64" for a binary body
 * </pre>
 *
 * Result meta (JSON): {ok, status, finalUrl, ttfbMs, totalMs, size, error}. ttfbMs is the time until the
 * response headers arrived (curl's time_starttransfer), totalMs until the last byte (time_total).
 */
final class HttpBridge {

    static final class Reply {
        final int code;        // 0 = ok, 1 = failed
        final String body;     // response body; for a download the path of the file
        final String meta;     // JSON

        Reply(int code, String body, String meta) {
            this.code = code;
            this.body = body;
            this.meta = meta;
        }
    }

    private static final String DEFAULT_UA = "curl/8.5.0";
    private static final int DEF_TIMEOUT_MS = 15_000;
    private static final int MAX_TIMEOUT_MS = 600_000;
    private static final long DEF_MAX_BYTES = 4L << 20;
    private static final long MAX_MEM_BYTES = 16L << 20;
    private static final long MAX_DL_BYTES = 256L << 20;
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern SOCKS_RE = Pattern.compile("^(127(?:\\.[0-9]{1,3}){3}):([0-9]{1,5})$");

    private static volatile SSLSocketFactory trustAll;

    private HttpBridge() {
    }

    /** Blocking; never throws. */
    static Reply request(String optionsJson) {
        return run(optionsJson, null);
    }

    /**
     * Blocking; never throws. Streams the body into the app cache and returns its path as {@code body}.
     * The caller moves it where it belongs (a root shell for the geo directory) and deletes it.
     */
    static Reply download(Context c, String optionsJson) {
        File dir = new File(c.getCacheDir(), "dl");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "dl_" + Long.toHexString(new SecureRandom().nextLong()) + ".part");
        return run(optionsJson, f);
    }

    private static Reply run(String optionsJson, File dest) {
        final long t0 = System.nanoTime();
        JSONObject meta = new JSONObject();
        try {
            JSONObject o = new JSONObject(optionsJson == null || optionsJson.isEmpty() ? "{}" : optionsJson);

            String method = o.optString("method", "GET").toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("POST") && !method.equals("HEAD")) {
                return fail(meta, t0, "unsupported method");
            }
            Set<String> schemes = new HashSet<>();
            for (String s : o.optString("schemes", "https").split(",")) {
                s = s.trim().toLowerCase(Locale.ROOT);
                if (s.equals("http") || s.equals("https")) schemes.add(s);
            }
            if (schemes.isEmpty()) return fail(meta, t0, "no scheme allowed");

            URL url = checkedUrl(null, o.optString("url", ""), schemes);

            int timeout = (int) clamp(o.optLong("timeoutMs", DEF_TIMEOUT_MS), 1000, MAX_TIMEOUT_MS);
            int connectTimeout = (int) Math.min(clamp(o.optLong("connectTimeoutMs", 15_000), 1000, MAX_TIMEOUT_MS), timeout);
            int maxRedirects = (int) clamp(o.optLong("maxRedirects", 3), 0, 10);
            boolean failOnError = o.optBoolean("failOnError", true);
            boolean insecure = o.optBoolean("allowInsecureTls", false);
            boolean b64 = "base64".equals(o.optString("encoding", "text"));
            long cap = dest != null ? clamp(o.optLong("maxBytes", MAX_DL_BYTES), 1, MAX_DL_BYTES)
                    : clamp(o.optLong("maxBytes", DEF_MAX_BYTES), 1, MAX_MEM_BYTES);
            Proxy proxy = socksProxy(o.optString("socks", ""));
            byte[] payload = "POST".equals(method) ? o.optString("body", "").getBytes(StandardCharsets.UTF_8) : null;
            JSONObject headers = o.optJSONObject("headers");
            final long deadline = t0 + timeout * 1_000_000L;

            for (int hop = 0; ; hop++) {
                HttpURLConnection c = (HttpURLConnection) url.openConnection(proxy);
                try {
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(connectTimeout);
                    c.setReadTimeout(timeout);
                    c.setUseCaches(false);
                    c.setRequestMethod(method);
                    // One connection per request: a pooled one would hide the connect / TLS time that
                    // the latency numbers are meant to include.
                    c.setRequestProperty("Connection", "close");
                    c.setRequestProperty("User-Agent", DEFAULT_UA);
                    if (headers != null) {
                        Iterator<String> it = headers.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            String v = headers.optString(k, "");
                            if (!HEADER_NAME.matcher(k).matches() || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) continue;
                            c.setRequestProperty(k, v);
                        }
                    }
                    if (insecure && c instanceof HttpsURLConnection) {
                        HttpsURLConnection hc = (HttpsURLConnection) c;
                        hc.setSSLSocketFactory(trustAllFactory());
                        hc.setHostnameVerifier((h, s) -> true);
                    }
                    if (payload != null) {
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(payload.length);
                        try (OutputStream os = c.getOutputStream()) {
                            os.write(payload);
                        }
                    }

                    int status = c.getResponseCode();
                    long ttfb = (System.nanoTime() - t0) / 1_000_000L;

                    boolean redirect = status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
                    String loc = c.getHeaderField("Location");
                    if (redirect && loc != null && !"POST".equals(method) && maxRedirects > 0) {
                        if (hop >= maxRedirects) return fail(meta, t0, "too many redirects");
                        url = checkedUrl(url, loc.trim(), schemes);
                        continue;
                    }

                    put(meta, "status", status);
                    put(meta, "finalUrl", url.toString());
                    put(meta, "ttfbMs", ttfb);
                    if (failOnError && status >= 400) return fail(meta, t0, "HTTP " + status);

                    long size = 0;
                    ByteArrayOutputStream mem = dest == null ? new ByteArrayOutputStream() : null;
                    InputStream in = "HEAD".equals(method) ? null : (status >= 400 ? c.getErrorStream() : c.getInputStream());
                    try (OutputStream out = dest != null ? new FileOutputStream(dest) : mem) {
                        if (in != null) {
                            try {
                                byte[] buf = new byte[16 * 1024];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    size += n;
                                    if (size > cap) throw new IOException("response too large");
                                    if (System.nanoTime() > deadline) throw new SocketTimeoutException("timed out");
                                    out.write(buf, 0, n);
                                }
                            } finally {
                                in.close();
                            }
                        }
                    }
                    put(meta, "size", size);
                    put(meta, "totalMs", (System.nanoTime() - t0) / 1_000_000L);
                    put(meta, "ok", true);
                    String body;
                    if (dest != null) body = dest.getPath();
                    else if (b64) body = Base64.encodeToString(mem.toByteArray(), Base64.NO_WRAP);
                    else body = new String(mem.toByteArray(), StandardCharsets.UTF_8);
                    return new Reply(0, body, meta.toString());
                } finally {
                    c.disconnect();
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            if (dest != null) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
            String m = e.getMessage();
            return fail(meta, t0, e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m));
        } catch (JSONException e) {
            return fail(meta, t0, "bad options");
        }
    }

    private static Reply fail(JSONObject meta, long t0, String error) {
        put(meta, "ok", false);
        put(meta, "totalMs", (System.nanoTime() - t0) / 1_000_000L);
        put(meta, "error", error);
        return new Reply(1, "", meta.toString());
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Resolves {@code spec} against {@code base} and accepts it only with an allowed scheme and a host. */
    private static URL checkedUrl(URL base, String spec, Set<String> schemes) throws IOException {
        URL u;
        try {
            u = base == null ? new URL(spec) : new URL(base, spec);
        } catch (MalformedURLException e) {
            throw new IOException("invalid URL");
        }
        String p = u.getProtocol() == null ? "" : u.getProtocol().toLowerCase(Locale.ROOT);
        if (!schemes.contains(p)) throw new IOException("scheme not allowed: " + p);
        if (u.getHost() == null || u.getHost().isEmpty()) throw new IOException("invalid URL");
        if (u.getUserInfo() != null) throw new IOException("credentials in the URL are not allowed");
        return u;
    }

    /** Only a loopback IPv4 SOCKS5 inbound (the app's own Xray) may be used as a proxy. */
    private static Proxy socksProxy(String spec) throws IOException {
        if (spec == null || spec.isEmpty()) return Proxy.NO_PROXY;
        Matcher m = SOCKS_RE.matcher(spec);
        if (!m.matches()) throw new IOException("invalid SOCKS address");
        int port = Integer.parseInt(m.group(2));
        if (port < 1 || port > 65535) throw new IOException("invalid SOCKS port");
        // literal address: nothing is resolved here
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(m.group(1), port));
    }

    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
    private static SSLSocketFactory trustAllFactory() throws GeneralSecurityException {
        SSLSocketFactory f = trustAll;
        if (f != null) return f;
        TrustManager[] tm = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new SecureRandom());
        return trustAll = ctx.getSocketFactory();
    }
}
