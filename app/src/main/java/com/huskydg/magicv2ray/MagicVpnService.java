package com.huskydg.magicv2ray;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-root engine. Owns the VPN interface and one xray process:
 *
 *   VpnService.establish() --tun fd--> `xhuskydg_helper vpnrun` --exec--> xray (tun inbound)
 *
 * The fd travels over an abstract unix socket (see native/jni/vpnrun.cpp). Xray's own sockets
 * never enter the tunnel because this app is excluded from it, which replaces the fwmark /
 * ip rule machinery of the root engine. Per-app exclusion, LAN bypass and the MTU come from
 * the same settings the WebUI saves.
 *
 * "Apply proxy on" (networkMode 1 = WiFi/Ethernet only, 2 = mobile data only) is honoured by
 * watching the default network: on the unselected network the tunnel is re-established
 * without any route and xray is stopped, so the VPN key stays in the status bar while every
 * packet goes straight out of the physical interface. Coming back to the selected network
 * re-establishes the tunnel with its routes and starts xray again.
 */
public class MagicVpnService extends VpnService {

    static final String ACTION_START = "com.huskydg.magicv2ray.VPN_START";

    private final Object lock = new Object();
    private ParcelFileDescriptor tun;
    private Process xray;
    private volatile boolean closing;

    // ---- "Apply proxy on" (networkMode) ----
    private static final int NET_UNKNOWN = -1, NET_OTHER = 0, NET_MOBILE = 1;
    private static final long NET_DEBOUNCE_MS = 1500;
    private volatile int netMode;                 // 0 all, 1 WiFi/Ethernet only, 2 mobile only
    private volatile int netKind = NET_UNKNOWN;   // kind of the current default network
    private volatile boolean standby;             // VPN up, no routes, xray stopped
    private volatile Network netCur;              // default network netKind was read from
    private ConnectivityManager.NetworkCallback netCb;
    private volatile ScheduledExecutorService netExec;
    private volatile ScheduledFuture<?> netPending;

    boolean xrayAlive() {
        Process p = xray;
        return p != null && p.isAlive();
    }

    /** The engine is healthy: xray is alive, or it is deliberately idle on an unselected network. */
    boolean engineUp() {
        return standby || xrayAlive();
    }

    // ----------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        VpnController.svc = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showForeground();
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            new Thread(this::bringUp, "mv-vpn-up").start();
        } else if (!VpnController.isStarting()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {          // the user turned the VPN off from system settings / another VPN took over
        VpnController.setFailed("VPN permission revoked");
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        shutdown();
        if (VpnController.svc == this) VpnController.svc = null;
        super.onDestroy();
    }

    private void bringUp() {
        synchronized (lock) {
            try {
                closing = false;
                standby = false;
                NonRootEnv.ensure(this);
                JSONObject st = NonRootEnv.settings(this);
                int mode = st.optInt("networkMode", 0);
                netMode = mode >= 1 && mode <= 2 ? mode : 0;
                // this app is excluded from the VPN, so it sees the physical default network
                netKind = netMode == 0 ? NET_UNKNOWN : currentKind();
                boolean routed = wantRouted(netMode, netKind, true);
                openTun(st, routed);
                resetLog();
                if (routed) {
                    startXray();
                } else {
                    standby = true;
                    refreshNotification();
                }
                VpnController.setRunning();
                watchNetwork();
            } catch (Exception e) {
                VpnController.setFailed(String.valueOf(e.getMessage()));
                teardownLocked();
                stopSelf();
            }
        }
    }

    /** Stops everything; safe to call repeatedly and from any thread. */
    void shutdown() {
        synchronized (lock) {
            teardownLocked();
        }
        VpnController.setStopped();
        stopSelf();
    }

    /** New config, same tunnel: only the xray process is replaced. */
    void reloadXray() throws IOException {
        synchronized (lock) {
            if (tun == null) throw new IOException("VPN is not established");
            if (standby) return;        // idle on this network: the new config is read when it comes back
            killXray();
            startXray();
        }
    }

    // ------------------------------------------------------------------- tunnel

    /**
     * (Re)establishes the interface. {@code routed == false} builds it without routes and DNS
     * servers: the VPN stays connected (status-bar key, per-app exclusion) but captures nothing.
     * A second establish() atomically replaces the previous interface, so the old fd is closed.
     */
    private void openTun(JSONObject st, boolean routed) throws IOException {
        Builder b = new Builder();
        b.setSession("Magic V2Ray");
        int mtu = st.optInt("mtu", 1350);
        b.setMtu(mtu >= 1280 && mtu <= 9000 ? mtu : 1350);
        boolean v6 = st.optBoolean("enableIPv6", false);

        b.addAddress("198.18.0.1", 16); // Sync with root mode xraytun0
        b.addAddress("fdfe:dcba:9876::1", 64); // Sync with root mode xraytun0
        b.addAddress("2001:db8:7872:6179::1", 126); // Sync with root mode xraytun0
        if (routed) {
            b.addRoute("0.0.0.0", 0);
            b.addDnsServer("1.1.1.1");
            b.addDnsServer("1.0.0.1");
            // IPv6 always enters the tunnel: with "Enable IPv6" off it must not leak out of the
            // physical interface, so it is captured here and dropped by xray (see runtimeConfig).
            b.addRoute("::", 0);
            if (v6) b.addDnsServer("2606:4700:4700::1111");
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);

        // LAN / special-use ranges skip the tunnel (Bypass LAN). Needs API 33.
        if (routed && Build.VERSION.SDK_INT >= 33) {
            for (String cidr : NonRootEnv.lanBypass(st)) {
                try {
                    int slash = cidr.indexOf('/');
                    InetAddress a = InetAddress.getByName(cidr.substring(0, slash));
                    boolean isV6 = a.getAddress().length == 16;
                    if (isV6 && !v6) continue;
                    b.excludeRoute(new IpPrefix(a, Integer.parseInt(cidr.substring(slash + 1))));
                } catch (Exception ignored) {
                }
            }
        }

        // Xray runs under this uid: keeping the app out of the VPN is what stops its own
        // outgoing connections from looping back into the tunnel.
        try {
            b.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        List<String> skip = NonRootEnv.excludedApps(this, st);
        for (String pkg : skip) {
            try {
                b.addDisallowedApplication(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        ParcelFileDescriptor nt = b.establish();
        if (nt == null) throw new IOException("VPN permission not granted");
        ParcelFileDescriptor old = tun;
        tun = nt;
        if (old != null) {
            try {
                old.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------- apply proxy on ...

    /** Should traffic be routed through xray? {@code keep} is the answer when the network is unknown. */
    private static boolean wantRouted(int mode, int kind, boolean keep) {
        if (mode == 0 || kind == NET_UNKNOWN) return keep;
        return mode == 1 ? kind != NET_MOBILE : kind == NET_MOBILE;
    }

    private static int kindOf(NetworkCapabilities c) {
        if (c == null) return NET_UNKNOWN;
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return NET_OTHER;
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return NET_MOBILE;
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return NET_UNKNOWN;
        return NET_OTHER;                 // bluetooth / USB / other non-cellular links
    }

    private int currentKind() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            return n == null ? NET_UNKNOWN : kindOf(cm.getNetworkCapabilities(n));
        } catch (RuntimeException e) {
            return NET_UNKNOWN;
        }
    }

    private void watchNetwork() {
        if (netMode == 0 || netCb != null) return;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            netExec = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "mv-vpn-net"));
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                    netCur = n;
                    onNetKind(kindOf(c));
                }

                @Override
                public void onLost(Network n) {
                    // Only the network we are tracking may reset the kind: a late onLost() of the
                    // previous network must not wipe out the kind of the one that replaced it,
                    // otherwise applyNetwork() sees "unknown", keeps the old state and the
                    // selected network stays in standby (traffic, IPv6 included, goes direct).
                    if (n.equals(netCur)) netKind = NET_UNKNOWN;   // keep the state until a new network shows up
                }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (RuntimeException e) {
            stopNetWatch();
        }
    }

    private void stopNetWatch() {
        ConnectivityManager.NetworkCallback cb = netCb;
        netCb = null;
        if (cb != null) {
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(cb);
            } catch (RuntimeException ignored) {
            }
        }
        ScheduledExecutorService ex = netExec;
        netExec = null;
        netPending = null;
        if (ex != null) ex.shutdownNow();
    }

    /** Default network changed. Debounced: WiFi <-> mobile hand-overs flap for a moment. */
    private void onNetKind(int kind) {
        if (kind == NET_UNKNOWN) return;
        netKind = kind;
        ScheduledFuture<?> f = netPending;
        if (f != null) f.cancel(false);
        ScheduledExecutorService ex = netExec;
        if (ex == null) return;
        boolean want = wantRouted(netMode, kind, !standby);
        if (want == !standby) return;    // already in the right state
        // Arriving on the selected network: capture traffic at once. Waiting out the debounce
        // would leave a window where everything (IPv6 included) leaves directly on a network
        // that should be proxied, and connections opened then would outlive the switch.
        // Leaving it can wait: a few more seconds through the tunnel are harmless.
        long delay = want ? 0 : NET_DEBOUNCE_MS;
        try {
            netPending = ex.schedule(this::applyNetwork, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void applyNetwork() {
        synchronized (lock) {
            if (tun == null || closing) return;
            boolean want = wantRouted(netMode, netKind, !standby);
            if (want == !standby) return;
            try {
                if (want) {
                    // back on the selected network: routes first, then xray gets the new fd
                    openTun(NonRootEnv.settings(this), true);
                    startXray();
                    standby = false;
                } else {
                    // leaving it: the interface is replaced by a route-less one (traffic goes
                    // straight out), then xray, whose old tun fd just died, is stopped quietly
                    closing = true;
                    try {
                        openTun(NonRootEnv.settings(this), false);
                        killXray();
                    } finally {
                        closing = false;
                    }
                    standby = true;
                }
                refreshNotification();
            } catch (Exception e) {
                VpnController.setFailed(String.valueOf(e.getMessage()));
                teardownLocked();
                stopSelf();
            }
        }
    }

    // --------------------------------------------------------------------- xray

    /** Xray's output lives in a plain file under the app's own directory (stubs/logs/xray.log). */
    private static final long LOG_MAX_BYTES = 1024 * 1024;
    private static final long LOG_KEEP_BYTES = 256 * 1024;

    /** New session: start with an empty log. The file is kept after stop so the Log tab can still show it. */
    private void resetLog() {
        try {
            new FileOutputStream(NonRootEnv.logFile(this), false).close();    // truncate
        } catch (IOException ignored) {
        }
    }

    /** Cuts the file down to its newest LOG_KEEP_BYTES, starting at a line boundary. */
    private static void trimLog(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            long len = raf.length();
            if (len <= LOG_KEEP_BYTES) return;
            byte[] tail = new byte[(int) LOG_KEEP_BYTES];
            raf.seek(len - LOG_KEEP_BYTES);
            raf.readFully(tail);
            int off = 0;
            while (off < tail.length && tail[off] != '\n') off++;
            off = off < tail.length ? off + 1 : 0;
            raf.seek(0);
            raf.write(tail, off, tail.length - off);
            raf.setLength(tail.length - off);
        } catch (IOException ignored) {
        }
    }

    private void startXray() throws IOException {
        boolean v6 = NonRootEnv.settings(this).optBoolean("enableIPv6", false);
        File cfg = NonRootEnv.runtimeConfig(this, !v6);
        final String sock = "mv-tun-" + android.os.Process.myPid() + "-" + System.nanoTime();
        final LocalServerSocket server = new LocalServerSocket(sock);
        final Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    NonRootEnv.bin(this, "xhuskydg_helper").getPath(), "vpnrun", sock,
                    NonRootEnv.bin(this, "xray").getPath(), "run", "-c", cfg.getPath());
            pb.environment().put("XRAY_LOCATION_ASSET", new File(NonRootEnv.moddir(this), "bin").getPath());
            pb.directory(NonRootEnv.root(this));
            pb.redirectErrorStream(true);
            p = pb.start();
            p.getOutputStream().close();
        } catch (IOException e) {
            server.close();
            throw e;
        }

        // hand the tun fd to the helper as soon as it connects
        final CountDownLatch sent = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean();
        final FileDescriptor fd = tun.getFileDescriptor();
        Thread t = new Thread(() -> {
            try (LocalSocket s = server.accept()) {
                s.setFileDescriptorsForSend(new FileDescriptor[]{fd});
                OutputStream o = s.getOutputStream();
                o.write(0);
                o.flush();
                ok.set(true);
            } catch (IOException ignored) {
            } finally {
                sent.countDown();
            }
        }, "mv-vpn-fd");
        t.start();
        boolean done;
        try {
            done = sent.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            done = false;
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
        if (!done || !ok.get()) {
            p.destroyForcibly();
            throw new IOException("could not hand the VPN interface to xray");
        }

        pump(p);
        xray = p;
        new Thread(() -> {
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            if (xray == p && !closing) {            // not a stop / reload we asked for
                VpnController.setFailed("xray exited (code " + p.exitValue() + "), see the log tab");
                shutdown();
            }
        }, "mv-vpn-watch").start();

        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
        }
        if (!p.isAlive()) {
            xray = null;
            throw new IOException("xray exited right after start (code " + p.exitValue() + ")");
        }
    }

    /** Appends xray's stdout/stderr to the log file (O_APPEND, so the UI may truncate it any time). */
    private void pump(final Process p) {
        new Thread(() -> {
            File f = NonRootEnv.logFile(this);
            try (InputStream in = p.getInputStream(); FileOutputStream out = new FileOutputStream(f, true)) {
                byte[] b = new byte[4096];
                int n;
                long size = f.length();
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    size += n;
                    if (size > LOG_MAX_BYTES) {
                        trimLog(f);
                        size = f.length();
                    }
                }
            } catch (IOException ignored) {
            }
        }, "mv-vpn-log").start();
    }

    private void killXray() {
        Process p = xray;
        xray = null;
        if (p == null) return;
        closing = true;
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        closing = false;
    }

    private void teardownLocked() {
        closing = true;
        killXray();
        closing = true;
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
            tun = null;
        }
        standby = false;
        stopForeground(true);
        stopNetWatch();
    }

    // ------------------------------------------------------------- notification

    private void showForeground() {
        ProxyNotifier.ensureChannel(this);
        Notification n = ProxyNotifier.build(this, standby);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ProxyNotifier.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ProxyNotifier.NOTIF_ID, n);
        }
    }

    /** Swaps the notification text when the proxy pauses / resumes with the network. */
    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ProxyNotifier.NOTIF_ID, ProxyNotifier.build(this, standby));
    }

    /** The WebUI selected another node: show its name (only while the engine is up). */
    void updateNode() {
        if (VpnController.svc == this && !closing && engineUp()) refreshNotification();
    }
}
