package com.huskydg.magicv2ray;

import android.content.Context;
import android.os.Process;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Root mode only: keeps this app's process out of the low-memory killer by writing -1000 to
 * /proc/&lt;pid&gt;/oom_score_adj, so the "proxy active" notification (and the tile) survive.
 *
 * The activity manager rewrites oom_score_adj whenever the process changes state (foreground,
 * cached, ...), so the value is checked every few seconds and written again only when it was
 * changed back. The check reads the file directly; su is only spawned when a write is needed.
 */
final class OomGuard {

    private static final int PROTECTED = -1000;
    private static final long PERIOD_S = 10;
    private static final int MAX_FAILS = 3;

    private static ScheduledExecutorService exec;
    private static int fails;

    private OomGuard() {
    }

    /** Starts the watcher once per process; call again to retry after it gave up (no root yet). */
    static synchronized void start(Context ctx) {
        if (RunMode.isNonRoot(ctx)) return;
        fails = 0;
        if (exec != null) return;
        final Context app = ctx.getApplicationContext();
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mv-oom-guard");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> tick(app), 0, PERIOD_S, TimeUnit.SECONDS);
    }

    private static void tick(Context app) {
        try {
            if (RunMode.isNonRoot(app) || failsExceeded()) return;
            int pid = Process.myPid();
            if (current(pid) == PROTECTED) {
                resetFails();
                return;
            }
            RootShell.init(app);
            RootShell.Result r = RootShell.run("echo " + PROTECTED + " > /proc/" + pid + "/oom_score_adj", 10_000);
            if (r.ok() && current(pid) == PROTECTED) resetFails();
            else addFail();
        } catch (RuntimeException ignored) {
        }
    }

    private static int current(int pid) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/" + pid + "/oom_score_adj"))) {
            String l = br.readLine();
            return l == null ? Integer.MIN_VALUE : Integer.parseInt(l.trim());
        } catch (IOException | NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static synchronized boolean failsExceeded() {
        return fails >= MAX_FAILS;
    }

    private static synchronized void resetFails() {
        fails = 0;
    }

    private static synchronized void addFail() {
        fails++;
    }
}
