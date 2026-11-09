package com.huskydg.magicv2ray;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs shell scripts as root through the device's `su` binary.
 *
 * Each script is written to a file in the app cache and executed with
 * {@code su -c "sh <file> </dev/null"}. That keeps big payloads out of the
 * argv limit (profiles are written as base64 blobs) and keeps stdin away from
 * the script so a command that reads stdin cannot swallow the rest of it.
 */
final class RootShell {

    static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        boolean ok() {
            return code == 0;
        }
    }

    private static final AtomicLong SEQ = new AtomicLong();
    private static Context appContext;
    private static String[] suPrefix;       // e.g. {"su","-M"}; null until detected
    private static boolean suChecked;
    private static volatile boolean nonRoot;    // non-root mode: scripts run as the app's own user

    private RootShell() {
    }

    static synchronized void init(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    /** Single-quotes a value for POSIX sh. */
    static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Finds a working su invocation. Prefers the global mount namespace (-M,
     * supported by Magisk and KernelSU) and falls back to plain su. Blocks while
     * the root manager shows its grant prompt, hence the generous timeout.
     */
    static void setNonRoot(boolean v) {
        nonRoot = v;
    }

    static synchronized boolean hasRoot() {
        if (nonRoot) return true;
        if (suPrefix != null) return true;
        if (suChecked) {
            // A previous attempt failed; probe again (the user may have granted since).
            suChecked = false;
        }
        String[][] candidates = {{"su", "-M"}, {"su"}};
        for (String[] c : candidates) {
            Result r = execArgv(c, "id -u", 90_000);
            if (r.code == 127) break;                       // su binary not found
            if (r.code == 0 && r.out.trim().equals("0")) {
                suPrefix = c;
                return true;
            }
        }
        suChecked = true;
        return false;
    }

    /** Runs a script as root. timeoutMs <= 0 means no timeout. */
    static Result run(String script, long timeoutMs) {
        String[] prefix;
        synchronized (RootShell.class) {
            prefix = nonRoot ? new String[]{"sh"} : suPrefix;
        }
        if (prefix == null) {
            if (!hasRoot()) return new Result(1, "", "root access unavailable");
            synchronized (RootShell.class) {
                prefix = suPrefix;
            }
        }
        File dir = new File(appContext.getCacheDir(), "sh");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "s" + SEQ.incrementAndGet() + ".sh");
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(script.getBytes(StandardCharsets.UTF_8));
            fo.write('\n');
        } catch (IOException e) {
            return new Result(1, "", "cannot write script: " + e.getMessage());
        }
        try {
            return execArgv(prefix, "sh " + sq(f.getAbsolutePath()) + " </dev/null", timeoutMs);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static Result execArgv(String[] prefix, String cmdLine, long timeoutMs) {
        String[] argv = new String[prefix.length + 2];
        System.arraycopy(prefix, 0, argv, 0, prefix.length);
        argv[prefix.length] = "-c";
        argv[prefix.length + 1] = cmdLine;

        final Process p;
        try {
            p = new ProcessBuilder(argv).start();
        } catch (IOException e) {
            return new Result(127, "", String.valueOf(e.getMessage()));
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        Collector so = new Collector(p.getInputStream());
        Collector se = new Collector(p.getErrorStream());
        so.start();
        se.start();

        int code;
        try {
            if (timeoutMs > 0) {
                if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    code = p.exitValue();
                } else {
                    p.destroyForcibly();
                    code = 124;
                }
            } else {
                code = p.waitFor();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            code = 130;
        }
        // A backgrounded child can keep the pipes open after su exits; don't wait for it.
        so.finish();
        se.finish();
        return new Result(code, so.text(), se.text());
    }

    private static final class Collector extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Collector(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] b = new byte[8192];
            try {
                int n;
                while ((n = in.read(b)) > 0) {
                    synchronized (buf) {
                        buf.write(b, 0, n);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        void finish() {
            try {
                join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            synchronized (buf) {
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
