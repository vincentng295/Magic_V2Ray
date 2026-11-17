package com.huskydg.magicv2ray;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Tunnel speed / totals for the "proxy is active" notification (opt-in, see
 * {@link ProxyNotifier#trafficEnabled}). One sampler per process, running only while the proxy is up
 * and the option is on:
 * <ul>
 *   <li>non-root: the VPN network's interface (normally tun0);</li>
 *   <li>root: {@value #ROOT_IFACE}, the TUN device of the root engine.</li>
 * </ul>
 * Counters come from TrafficStats (works for any interface name), then /sys/class/net, then, in root
 * mode only, a root `cat` (sampled every {@link #ROOT_CAT_EVERY} ticks to keep `su` calls rare).
 * tx = bytes pushed INTO the tunnel (upload), rx = bytes that came back out (download).
 */
final class TrafficMeter {

    static final String ROOT_IFACE = "xraytun0";
    static final Pattern IFACE_RE = Pattern.compile("^[A-Za-z0-9_.:-]{1,15}$");

    private static final long PERIOD_MS = 1000;
    private static final int ROOT_CAT_EVERY = 2;

    private static ScheduledExecutorService exec;
    private static Context app;
    private static volatile String line = "";     // "↑ 1.2 KB/s  ↓ 30 KB/s", "" until two samples exist
    private static volatile String totals = "";    // "Total ↑ 3.1 MB  ↓ 40 MB since the proxy started"

    // sampler state, touched only by the executor thread
    private static String lastIface = "";
    private static long prevT, prevTx = -1, prevRx = -1, baseTx, baseRx;
    private static long tick;
    private static boolean rootCatOnly;

    private TrafficMeter() {
    }

    /** Latest speed text for the notification, "" when there is nothing to show yet. */
    static String line() {
        return line;
    }

    static String totals() {
        return totals;
    }

    static synchronized boolean running() {
        return exec != null;
    }

    static synchronized void start(Context c) {
        if (exec != null) return;
        app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        resetState();
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mv-traffic-meter");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(TrafficMeter::sample, 0, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    static synchronized void stop() {
        if (exec == null) return;
        exec.shutdownNow();
        exec = null;
        line = "";
        totals = "";
    }

    private static void resetState() {
        line = "";
        totals = "";
        lastIface = "";
        prevT = 0;
        prevTx = prevRx = -1;
        tick = 0;
        rootCatOnly = false;
    }

    // ------------------------------------------------------------- sampling

    private static void sample() {
        try {
            Context c = app;
            final boolean nonRoot = RunMode.isNonRoot(c);
            final String iface = nonRoot ? vpnInterfaceName(c) : ROOT_IFACE;
            if (iface == null) {                         // no VPN network yet / any more
                clearSamples();
                return;
            }
            long[] v = rootCatOnly ? null : counters(iface);
            if (v == null && !nonRoot) {
                rootCatOnly = true;                      // the app cannot read it itself: ask root
                if (tick++ % ROOT_CAT_EVERY != 0) return;
                v = rootCat(c, iface);
            }
            final long now = System.nanoTime() / 1_000_000L;
            if (v == null) {
                clearSamples();
                return;
            }
            if (!iface.equals(lastIface) || prevTx < 0 || v[0] < prevTx || v[1] < prevRx) {
                // new interface, or the counters restarted (tun re-created): start a new baseline
                if (!iface.equals(lastIface) || prevTx < 0) {
                    baseTx = v[0];
                    baseRx = v[1];
                } else {
                    baseTx = 0;
                    baseRx = 0;
                }
                lastIface = iface;
                prevT = now;
                prevTx = v[0];
                prevRx = v[1];
                return;
            }
            final double dt = (now - prevT) / 1000.0;
            if (dt <= 0) return;
            final double up = (v[0] - prevTx) / dt;
            final double down = (v[1] - prevRx) / dt;
            prevT = now;
            prevTx = v[0];
            prevRx = v[1];
            line = "↑ " + rate(up) + "   ↓ " + rate(down);
            totals = "Σ ↑ " + bytes(v[0] - baseTx) + "   ↓ " + bytes(v[1] - baseRx);
            ProxyNotifier.repost(c);
        } catch (RuntimeException ignored) {
        }
    }

    private static void clearSamples() {
        prevTx = prevRx = -1;
        lastIface = "";
        if (!line.isEmpty()) {
            line = "";
            totals = "";
            ProxyNotifier.repost(app);
        }
    }

    /** {tx, rx} through TrafficStats, then sysfs; null when neither works. */
    private static long[] counters(String iface) {
        long tx = ifaceStat(iface, true), rx = ifaceStat(iface, false);
        if (tx >= 0 && rx >= 0) return new long[]{tx, rx};
        tx = sysfsStat(iface, "tx_bytes");
        rx = sysfsStat(iface, "rx_bytes");
        return tx >= 0 && rx >= 0 ? new long[]{tx, rx} : null;
    }

    private static long[] rootCat(Context c, String iface) {
        if (!IFACE_RE.matcher(iface).matches()) return null;
        RootShell.init(c);
        String base = "/sys/class/net/" + iface + "/statistics";
        RootShell.Result r = RootShell.run("cat " + base + "/tx_bytes " + base + "/rx_bytes 2>/dev/null", 5_000);
        if (r == null || !r.ok()) return null;
        String[] p = r.out.trim().split("\\s+");
        if (p.length < 2) return null;
        try {
            return new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------- interface helpers
    // (shared with KsuBridge.trafficStats, the Traffic Monitor tab)

    /** Interface name of the VPN network (normally "tun0"), or null when no VPN network exists. */
    static String vpnInterfaceName(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                String name = lp == null ? null : lp.getInterfaceName();
                if (name != null && IFACE_RE.matcher(name).matches()) return name;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * TrafficStats.getTxBytes/getRxBytes(iface), through reflection so the build does not depend on
     * the method being in the SDK stubs and a hidden-API refusal degrades to the sysfs fallback.
     * Returns -1 when unavailable.
     */
    static long ifaceStat(String iface, boolean tx) {
        try {
            java.lang.reflect.Method m = TrafficStats.class.getMethod(tx ? "getTxBytes" : "getRxBytes", String.class);
            Object r = m.invoke(null, iface);
            if (r instanceof Long && (Long) r >= 0) return (Long) r;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** /sys/class/net/<iface>/statistics/<name>; readable by apps on some devices only. -1 on failure. */
    static long sysfsStat(String iface, String name) {
        if (!IFACE_RE.matcher(iface).matches()) return -1;
        try (BufferedReader br = new BufferedReader(
                new FileReader("/sys/class/net/" + iface + "/statistics/" + name))) {
            String l = br.readLine();
            return l == null ? -1 : Long.parseLong(l.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ format

    static String bytes(double n) {
        if (n < 0 || Double.isNaN(n)) n = 0;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (n >= 1024 && i < u.length - 1) {
            n /= 1024;
            i++;
        }
        return i == 0 ? String.format(java.util.Locale.US, "%.0f %s", n, u[i])
                : String.format(java.util.Locale.US, n >= 100 ? "%.0f %s" : (n >= 10 ? "%.1f %s" : "%.2f %s"), n, u[i]);
    }

    static String rate(double bps) {
        return bytes(bps) + "/s";
    }
}
