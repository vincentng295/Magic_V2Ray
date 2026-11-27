package com.huskydg.magicv2ray;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Files that travel with an external connect request (API level 3).
 *
 * <ul>
 *   <li><b>{@code overridesUri}</b>: the {@code overrides} JSON in a file, for sets too big for an intent extra
 *       (Binder transactions stop at about 1 MB). Rules and hosts have no count limit; only the file size has a ceiling.</li>
 *   <li><b>{@code assets}</b>: custom {@code .dat} databases (geoip.dat, geosite.dat, any file reached through
 *       {@code ext:file.dat:tag}) that replace the user's own for the length of the session.</li>
 * </ul>
 *
 * <p>Files are never read from a path: the caller hands over {@code content://} URIs and grants read access with
 * {@code Intent.FLAG_GRANT_READ_URI_PERMISSION} + {@code ClipData}. Only a URI that is also in the intent's ClipData is
 * opened, so a caller cannot point Magic V2Ray at something it was not allowed to read itself, and links / browsers can
 * never carry files at all.
 *
 * <p>Life of an asset: <em>staged</em> in the app cache while the request waits for the user's answer (checked, hashed,
 * 10 minutes at most), then <em>installed</em> into the session directory (root: tmpfs, non-root: cache) when the user
 * agrees, and deleted with the session. The staged copy is deleted at once on deny, on error, after the install and at the
 * next app start. Nothing is ever written to the user's data directory or to {@code location_assets}.
 */
final class ExternalAssets {

    /** Safety ceiling for the overrides JSON file. Rules and hosts themselves are not counted. */
    static final long MAX_OVERRIDES_FILE_BYTES = 64L * 1024 * 1024;
    /** One .dat file (the real geoip.dat is about 20 MB). Same ceiling as the file picker in Routing settings. */
    static final long MAX_ASSET_BYTES = GeoAssets.MAX_BYTES;
    /** All .dat files of one request together: in root mode they live in RAM (tmpfs) for the length of the session. */
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    static final int MAX_FILES = 32;
    static final long STAGE_TTL_MS = 10 * 60_000L;

    private static final Pattern ID_RE = Pattern.compile("^[0-9a-f]{16}$");
    private static final String STAGE_DIR = "ext_stage";

    private ExternalAssets() {
    }

    /** Thrown with a message that is shown to the user. */
    static final class FileError extends Exception {
        FileError(String m) {
            super(m);
        }
    }

    /** What this build accepts, for {@code getInfo}. */
    static JSONObject limits() throws JSONException {
        return new JSONObject()
                .put("overridesFileBytes", MAX_OVERRIDES_FILE_BYTES)
                .put("assetBytes", MAX_ASSET_BYTES)
                .put("assetsTotalBytes", MAX_TOTAL_BYTES)
                .put("assetFiles", MAX_FILES)
                .put("rules", "unlimited")
                .put("hosts", "unlimited");
    }

    // =============================================================== reading the caller's URIs

    /** The URIs that the caller granted with the intent (ClipData + data). Only these may be opened. */
    private static Set<String> granted(Intent i) {
        Set<String> out = new HashSet<>();
        ClipData cd = i.getClipData();
        for (int k = 0; cd != null && k < cd.getItemCount(); k++) {
            Uri u = cd.getItemAt(k).getUri();
            if (u != null) out.add(u.toString());
        }
        Uri d = i.getData();
        if (d != null) out.add(d.toString());
        return out;
    }

    private static Uri checkedUri(Context c, Intent i, String raw) throws FileError {
        if (raw == null || raw.trim().isEmpty()) throw new FileError("empty file uri");
        Uri u = Uri.parse(raw.trim());
        if (!"content".equalsIgnoreCase(u.getScheme()) || u.getAuthority() == null) {
            throw new FileError("only content:// uris are accepted");
        }
        // A file of Magic V2Ray itself is never a source (its providers answer to anybody).
        if (u.getAuthority().startsWith(c.getPackageName())) throw new FileError("this uri belongs to Magic V2Ray itself");
        if (!granted(i).contains(u.toString())) {
            throw new FileError("uri not granted: add it to the intent's ClipData with FLAG_GRANT_READ_URI_PERMISSION");
        }
        return u;
    }

    private static InputStream open(Context c, Uri u) throws FileError {
        try {
            InputStream in = c.getContentResolver().openInputStream(u);
            if (in == null) throw new FileError("cannot open " + u);
            return in;
        } catch (SecurityException e) {
            throw new FileError("no permission to read " + u);
        } catch (java.io.FileNotFoundException e) {
            throw new FileError("cannot open " + u);
        }
    }

    /** The text of the overrides file (UTF-8), at most {@link #MAX_OVERRIDES_FILE_BYTES}. */
    static String readOverridesText(Context c, Intent i, String uri) throws FileError {
        Uri u = checkedUri(c, i, uri);
        try (InputStream in = open(c, u)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] b = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > MAX_OVERRIDES_FILE_BYTES) {
                    throw new FileError("overrides file is too large (limit " + (MAX_OVERRIDES_FILE_BYTES >> 20) + " MB)");
                }
                out.write(b, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FileError("cannot read the overrides file: " + e.getMessage());
        }
    }

    // =============================================================== staging

    static File stageRoot(Context c) {
        return new File(c.getCacheDir(), STAGE_DIR);
    }

    static String newId() {
        byte[] b = new byte[8];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static File stageDir(Context c, String id) {
        return ID_RE.matcher(id == null ? "" : id).matches() ? new File(stageRoot(c), id) : null;
    }

    /**
     * Reads every asset of {@code assetsJson} ([{"name":"geoip.dat","uri":"content://..."}]) into the stage directory
     * {@code id}, checking name, header and size and hashing each file. Returns [{name, size, sha256}] (no content).
     * On any problem the stage is deleted and a {@link FileError} says why. Blocking: never call it on the main thread.
     */
    static JSONArray stage(Context c, Intent i, String assetsJson, String id) throws FileError {
        sweep(c);
        File dir = stageDir(c, id);
        if (dir == null) throw new FileError("bad stage id");
        JSONArray list;
        try {
            list = new JSONArray(assetsJson);
        } catch (JSONException e) {
            throw new FileError("assets must be a JSON array of {name, uri}");
        }
        if (list.length() == 0) return new JSONArray();
        if (list.length() > MAX_FILES) throw new FileError("too many asset files (limit " + MAX_FILES + ")");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new FileError("cannot create the staging directory");
        JSONArray out = new JSONArray();
        Set<String> names = new HashSet<>();
        long sum = 0;
        try {
            for (int k = 0; k < list.length(); k++) {
                JSONObject a = list.optJSONObject(k);
                if (a == null) throw new FileError("assets must be a JSON array of {name, uri}");
                String name = a.optString("name", "");
                if (!GeoAssets.NAME_RE.matcher(name).matches()) {
                    throw new FileError("invalid asset name \"" + name + "\" (letters, digits, . _ - only, must end in .dat)");
                }
                if (!names.add(name)) throw new FileError("asset \"" + name + "\" is listed twice");
                Uri u = checkedUri(c, i, a.optString("uri", ""));
                File dst = new File(dir, name);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long total = 0;
                int first = -1;
                try (InputStream in = open(c, u); FileOutputStream fo = new FileOutputStream(dst)) {
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        if (first < 0) first = b[0] & 0xFF;
                        total += n;
                        sum += n;
                        if (total > MAX_ASSET_BYTES) {
                            throw new FileError(name + " is too large (limit " + (MAX_ASSET_BYTES >> 20) + " MB)");
                        }
                        if (sum > MAX_TOTAL_BYTES) {
                            throw new FileError("the asset files are too large together (limit " + (MAX_TOTAL_BYTES >> 20) + " MB)");
                        }
                        md.update(b, 0, n);
                        fo.write(b, 0, n);
                    }
                }
                String bad = GeoAssets.validate(total, first);
                if (bad != null) throw new FileError(name + ": " + bad);
                StringBuilder hex = new StringBuilder();
                for (byte x : md.digest()) hex.append(String.format("%02x", x));
                out.put(new JSONObject().put("name", name).put("size", total).put("sha256", hex.toString()));
            }
            return out;
        } catch (FileError e) {
            discard(c, id);
            throw e;
        } catch (IOException | JSONException | NoSuchAlgorithmException e) {
            discard(c, id);
            throw new FileError("cannot read the asset files: " + e.getMessage());
        }
    }

    /** Deletes a stage directory (deny, error, after the install). Safe for an unknown or empty id. */
    static void discard(Context c, String id) {
        File d = stageDir(c, id);
        if (d != null) deleteTree(d);
    }

    /** Deletes stages older than the request TTL (they belong to requests nobody answered). */
    static void sweep(Context c) {
        File[] fs = stageRoot(c).listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        for (File f : fs) {
            if (now - f.lastModified() > STAGE_TTL_MS) deleteTree(f);
        }
    }

    /** App start: no request survives a restart of the process, so every stage is a leftover. */
    static void sweepAll(Context c) {
        File[] fs = stageRoot(c).listFiles();
        if (fs != null) for (File f : fs) deleteTree(f);
    }

    static void deleteTree(File f) {
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // =============================================================== installing into the session

    /**
     * Moves the staged files into {@code <session dir>/assets}, where the engine picks them up. The session directory has
     * to exist already (the WebUI created it). Returns an error text, or null on success. The stage is deleted either way.
     * Blocking: call it from a worker thread.
     */
    static String install(Context c, String id) {
        File src = stageDir(c, id);
        if (src == null || !src.isDirectory()) return "the staged files are gone (the request took too long)";
        File[] files = src.listFiles();
        try {
            if (files == null || files.length == 0) return "no staged files";
            if (RunMode.isNonRoot(c)) {
                File dst = new File(NonRootEnv.overrideDir(c), "assets");
                if (!dst.isDirectory() && !dst.mkdirs()) return "cannot create " + dst;
                for (File f : files) {
                    File to = new File(dst, f.getName());
                    if (!f.renameTo(to)) {                       // same filesystem (both in the cache), but be safe
                        try (InputStream in = new FileInputStream(f); FileOutputStream out = new FileOutputStream(to)) {
                            byte[] b = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(b)) > 0) out.write(b, 0, n);
                        } catch (IOException e) {
                            return "cannot write " + to + ": " + e.getMessage();
                        }
                    }
                }
                return null;
            }
            RootShell.init(c);
            StringBuilder sh = new StringBuilder("umask 022\n");
            sh.append("D=").append(RootShell.sq(ExternalSession.overrideDir(c) + "/assets")).append('\n');
            // 755: xray runs as another uid and walks the directory through the fd the service opens as root.
            sh.append("mkdir -p \"$D\" && chmod 755 \"$D\" && chown 0:0 \"$D\" || { echo 'cannot create the session assets dir' >&2; exit 1; }\n");
            for (File f : files) {
                String n = RootShell.sq(f.getName());
                sh.append("cp ").append(RootShell.sq(f.getAbsolutePath())).append(" \"$D/\"").append(n).append(".new")
                        .append(" || { echo 'copy failed' >&2; exit 1; }\n");
                sh.append("chmod 644 \"$D/\"").append(n).append(".new && chown 0:0 \"$D/\"").append(n).append(".new")
                        .append(" || { echo 'chmod failed' >&2; exit 1; }\n");
                sh.append("chcon u:object_r:system_file:s0 \"$D/\"").append(n).append(".new 2>/dev/null\n");
                sh.append("mv -f \"$D/\"").append(n).append(".new \"$D/\"").append(n)
                        .append(" || { echo 'replace failed' >&2; exit 1; }\n");
            }
            sh.append("exit 0\n");
            RootShell.Result r = RootShell.run(sh.toString(), 120_000);
            if (r.ok()) return null;
            String e = r.err.trim();
            return e.isEmpty() ? "installer exited with code " + r.code : e;
        } finally {
            deleteTree(src);
        }
    }
}
