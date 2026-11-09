package com.huskydg.magicv2ray;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.IpPrefix;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
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
 */
public class MagicVpnService extends VpnService {

    static final String ACTION_START = "com.huskydg.magicv2ray.VPN_START";
    private static final String CHANNEL = "mv_vpn";
    private static final int NOTIF_ID = 1;

    private final Object lock = new Object();
    private ParcelFileDescriptor tun;
    private Process xray;
    private volatile boolean closing;

    boolean xrayAlive() {
        Process p = xray;
        return p != null && p.isAlive();
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
                NonRootEnv.ensure(this);
                JSONObject st = NonRootEnv.settings(this);
                openTun(st);
                resetLog();
                startXray();
                VpnController.setRunning();
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
            killXray();
            startXray();
        }
    }

    // ------------------------------------------------------------------- tunnel

    private void openTun(JSONObject st) throws IOException {
        Builder b = new Builder();
        b.setSession("Magic V2Ray");
        int mtu = st.optInt("mtu", 1350);
        b.setMtu(mtu >= 1280 && mtu <= 9000 ? mtu : 1350);
        boolean v6 = st.optBoolean("enableIPv6", false);

        b.addAddress("172.19.0.1", 30);
        b.addRoute("0.0.0.0", 0);
        b.addDnsServer("1.1.1.1");
        b.addDnsServer("1.0.0.1");
        // IPv6 always enters the tunnel: with "Enable IPv6" off it must not leak out of the
        // physical interface, so it is captured here and dropped by xray (see runtimeConfig).
        b.addAddress("fdfe:dcba:9876::1", 126);
        b.addRoute("::", 0);
        if (v6) b.addDnsServer("2606:4700:4700::1111");
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);

        // LAN / special-use ranges skip the tunnel (Bypass LAN). Needs API 33.
        if (Build.VERSION.SDK_INT >= 33) {
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

        tun = b.establish();
        if (tun == null) throw new IOException("VPN permission not granted");
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
        stopForeground(true);
    }

    // ------------------------------------------------------------- notification

    private void showForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification n = nb.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.vpn_notif_text))
                .setSmallIcon(R.drawable.ic_tile)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
