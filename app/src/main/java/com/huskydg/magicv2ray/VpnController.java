package com.huskydg.magicv2ray;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Non-root counterpart of proxy_control.sh. The WebUI keeps calling
 * {@code sh <MODDIR>/proxy_control.sh <verb>}; in non-root mode KsuBridge hands those commands
 * to {@link #intercept} instead of a shell, and the answers match the script's contract
 * (status prints running / crashed / stopped and sets the same exit codes).
 */
final class VpnController {

    static final int REQ_VPN = 2;
    private static final Pattern CTL_RE = Pattern.compile("proxy_control\\.sh['\"]?\\s+([a-z_]+)");

    private static final int STOPPED = 0, STARTING = 1, RUNNING = 2, FAILED = 3;

    static volatile MagicVpnService svc;
    private static volatile int state = STOPPED;
    private static volatile String lastError = "";
    private static volatile CountDownLatch permLatch;
    private static volatile boolean permGranted;

    private VpnController() {
    }

    static boolean isStarting() {
        return state == STARTING;
    }

    static void setRunning() {
        state = RUNNING;
    }

    static void setStopped() {
        state = STOPPED;
    }

    static void setFailed(String why) {
        lastError = why == null ? "" : why;
        state = FAILED;
    }

    static boolean isRunning() {
        MagicVpnService s = svc;
        return s != null && state == RUNNING && s.xrayAlive();
    }

    /** Returns the result for a proxy_control.sh command line, or null when it is something else. */
    static RootShell.Result intercept(Context ctx, Activity host, String command) {
        Matcher m = CTL_RE.matcher(command == null ? "" : command);
        if (!m.find()) return null;
        return handle(ctx, host, m.group(1));
    }

    static RootShell.Result handle(Context ctx, Activity host, String verb) {
        switch (verb) {
            case "status":
                if (isRunning()) return new RootShell.Result(0, "running\n", "");
                if (svc != null && state == RUNNING) return new RootShell.Result(2, "crashed\n", "");
                return new RootShell.Result(1, "stopped\n", "");
            case "start":
                if (isRunning()) return new RootShell.Result(0, "running\n", "");
                return start(ctx, host);
            case "stop":
                stop();
                return new RootShell.Result(0, "stopped\n", "");
            case "restart":
                stop();
                return start(ctx, host);
            case "reload":
                return reload(ctx, host);
            default:
                // reapply, the monitors, latency heartbeat, reset_mobile_network: root-engine only
                return new RootShell.Result(0, "", "");
        }
    }

    private static RootShell.Result start(Context ctx, Activity host) {
        if (!NonRootEnv.configReady(ctx)) return new RootShell.Result(1, "", "no config");
        if (VpnService.prepare(ctx) != null) {
            if (host == null) return new RootShell.Result(1, "", "VPN permission required: open the app once");
            if (!awaitPermission(host)) return new RootShell.Result(1, "", "VPN permission denied");
        }
        state = STARTING;
        lastError = "";
        Intent i = new Intent(ctx, MagicVpnService.class).setAction(MagicVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (RuntimeException e) {
            state = STOPPED;
            return new RootShell.Result(1, "", "cannot start the VPN service: " + e.getMessage());
        }
        for (int n = 0; n < 300 && state == STARTING; n++) sleep(100);   // up to 30 s
        if (state == RUNNING) return new RootShell.Result(0, "started\n", "");
        return new RootShell.Result(1, "", lastError.isEmpty() ? "VPN did not start" : lastError);
    }

    static void stop() {
        MagicVpnService s = svc;
        if (s == null) {
            state = STOPPED;
            return;
        }
        s.shutdown();
        for (int n = 0; n < 100 && svc != null; n++) sleep(100);
    }

    private static RootShell.Result reload(Context ctx, Activity host) {
        MagicVpnService s = svc;
        // reload only swaps a live xray: when the core is stopped it must stay stopped
        if (s == null || state != RUNNING) return new RootShell.Result(0, "stopped\n", "");
        try {
            s.reloadXray();
            return new RootShell.Result(0, "reloaded\n", "");
        } catch (IOException e) {
            return new RootShell.Result(1, "", String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------- VPN permission

    private static boolean awaitPermission(Activity host) {
        permGranted = false;
        final CountDownLatch l = new CountDownLatch(1);
        permLatch = l;
        host.runOnUiThread(() -> {
            Intent i = VpnService.prepare(host);
            if (i == null) {
                permGranted = true;
                l.countDown();
            } else {
                host.startActivityForResult(i, REQ_VPN);
            }
        });
        try {
            l.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return permGranted;
    }

    /** From MainActivity.onActivityResult. */
    static void onPermissionResult(boolean granted) {
        permGranted = granted;
        CountDownLatch l = permLatch;
        if (l != null) l.countDown();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
