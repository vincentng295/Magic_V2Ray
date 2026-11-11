package com.huskydg.magicv2ray;

import android.content.Context;

import com.topjohnwu.superuser.Shell;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs shell scripts as root through libsu (https://github.com/topjohnwu/libsu).
 *
 * Instead of spawning a fresh `su` process for every call, root scripts are sent to
 * a small pool (at most {@link #MAX_SHELLS}) of long-lived root shells that are opened
 * once, with the global mount namespace ({@link Shell#FLAG_MOUNT_MASTER}). The root
 * manager therefore only sees a handful of `su` requests per app process instead of
 * one per command.
 *
 * Each script is still written to a file in the app cache and executed in the shell as
 * {@code sh <file> </dev/null >out 2>err}. That keeps big payloads out of the argv limit
 * (profiles are written as base64 blobs), keeps stdin away from the script so a command
 * that reads stdin cannot swallow the rest of it, and keeps daemons a script leaves in the
 * background from ever writing into the shared shell's pipes (stdout/stderr go to files).
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

    /** Upper bound on simultaneously open root shells (= `su` processes). */
    private static final int MAX_SHELLS = 3;
    /** How long to wait for a root shell to open; covers the root manager's grant prompt. */
    private static final long OPEN_TIMEOUT_S = 90;

    private static final AtomicLong SEQ = new AtomicLong();
    private static Context appContext;
    private static volatile boolean nonRoot;    // non-root mode: scripts run as the app's own user

    private static final Object LOCK = new Object();        // guards idle + open
    private static final Object CREATE = new Object();      // serialises opening a shell (one grant prompt)
    private static final ArrayDeque<Shell> idle = new ArrayDeque<>();
    private static int open;                                // live root shells, idle or in use

    private RootShell() {
    }

    static synchronized void init(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    /** Single-quotes a value for POSIX sh. */
    static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static void setNonRoot(boolean v) {
        nonRoot = v;
    }

    /**
     * Makes sure a root shell is available (opening one if needed). Blocks while the
     * root manager shows its grant prompt, hence the generous timeout. Probes again on
     * every call while no shell is open (the user may have granted since).
     */
    static boolean hasRoot() {
        if (nonRoot) return true;
        try {
            Shell s = acquire();
            if (s == null) return false;
            release(s, true);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Runs a script as root. timeoutMs <= 0 means no timeout. */
    static Result run(String script, long timeoutMs) {
        File dir = new File(appContext.getCacheDir(), "sh");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        long n = SEQ.incrementAndGet();
        File f = new File(dir, "s" + n + ".sh");
        File fo = new File(dir, "s" + n + ".out");
        File fe = new File(dir, "s" + n + ".err");
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(script.getBytes(StandardCharsets.UTF_8));
            os.write('\n');
        } catch (IOException e) {
            return new Result(1, "", "cannot write script: " + e.getMessage());
        }
        try {
            if (nonRoot) {
                return execArgv(new String[]{"sh"}, "sh " + sq(f.getAbsolutePath()) + " </dev/null", timeoutMs);
            }
            return runRoot(f, fo, fe, timeoutMs);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            fo.delete();
            //noinspection ResultOfMethodCallIgnored
            fe.delete();
        }
    }

    private static Result runRoot(File script, File outFile, File errFile, long timeoutMs) {
        // Create the capture files as the app so they keep the app's owner / SELinux label;
        // root then only writes into them and the app can always read and delete them.
        try {
            if (!outFile.createNewFile() || !errFile.createNewFile()) {
                return new Result(1, "", "cannot create output files");
            }
        } catch (IOException e) {
            return new Result(1, "", "cannot create output files: " + e.getMessage());
        }
        //noinspection ResultOfMethodCallIgnored
        outFile.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        outFile.setWritable(true, false);
        //noinspection ResultOfMethodCallIgnored
        errFile.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        errFile.setWritable(true, false);

        final Shell sh;
        try {
            sh = acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, "", "interrupted");
        }
        if (sh == null) return new Result(1, "", "root access unavailable");

        String cmd = "sh " + sq(script.getAbsolutePath()) + " </dev/null >" + sq(outFile.getAbsolutePath())
                + " 2>" + sq(errFile.getAbsolutePath());
        final AtomicReference<Shell.Result> ref = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        int code;
        boolean healthy = true;
        try {
            sh.newJob().add(cmd).submit(Runnable::run, r -> {
                ref.set(r);
                done.countDown();
            });
            boolean finished;
            if (timeoutMs > 0) {
                finished = done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                done.await();
                finished = true;
            }
            if (finished && ref.get() != null) {
                code = ref.get().getCode();
            } else {
                // The job cannot be cancelled inside a persistent shell: drop the shell
                // (closing it ends its `su`) so the pool opens a fresh one next time.
                healthy = false;
                code = 124;
            }
        } catch (InterruptedException e) {
            healthy = false;
            Thread.currentThread().interrupt();
            code = 130;
        } catch (RuntimeException e) {
            healthy = false;
            return new Result(1, "", "root shell error: " + e);
        } finally {
            release(sh, healthy);
        }
        // A negative code means libsu itself failed to run the job (shell died).
        if (code < 0) code = 1;
        return new Result(code, readQuietly(outFile), readQuietly(errFile));
    }

    private static String readQuietly(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Takes an idle root shell, or opens a new one while fewer than MAX_SHELLS exist, or
     * waits for one to be released. Returns null when the device has no usable root.
     */
    private static Shell acquire() throws InterruptedException {
        synchronized (LOCK) {
            while (true) {
                Shell s;
                while ((s = idle.pollFirst()) != null) {
                    if (s.isAlive()) return s;
                    open--;                                 // died while idle: forget it
                }
                if (open < MAX_SHELLS) {
                    open++;                                 // reserve the slot, open it below
                    break;
                }
                LOCK.wait();
            }
        }
        Shell s = null;
        try {
            synchronized (CREATE) {
                s = openShell();
            }
        } finally {
            if (s == null) {
                synchronized (LOCK) {
                    open--;
                    LOCK.notifyAll();
                }
            }
        }
        return s;
    }

    private static Shell openShell() {
        try {
            Shell s = Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)      // global mount namespace, like `su -M`
                    .setTimeout(OPEN_TIMEOUT_S)
                    .build();
            if (s.isRoot()) return s;
            closeQuietly(s);                                // libsu fell back to a plain sh
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static void release(Shell s, boolean healthy) {
        boolean keep = healthy && s.isAlive();
        if (!keep) closeQuietly(s);
        synchronized (LOCK) {
            if (keep) {
                idle.addFirst(s);
            } else {
                open--;
            }
            LOCK.notifyAll();
        }
    }

    private static void closeQuietly(Shell s) {
        try {
            s.close();
        } catch (Exception ignored) {
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
