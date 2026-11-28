package com.huskydg.magicv2ray;

import android.content.Context;
import android.os.IBinder;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbStream;

/**
 * Starts HotspotMain through the device's own Wireless debugging (adbd over TLS on this phone), so
 * no Shizuku and no PC are needed. Wireless debugging only has to be on (and Wi-Fi connected)
 * while the helper is being started; the helper is a detached process that keeps running
 * afterwards.
 */
final class AdbHelper {

    static final String LOG_FILE = "/data/local/tmp/mv2r_hotspot.log";
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long BINDER_TIMEOUT_S = 25;

    /** Thrown with a short machine-readable code the WebUI maps to a message. */
    static final class AdbFailure extends Exception {
        final String code;

        AdbFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private AdbHelper() {
    }

    static boolean isPaired(Context c) {
        return AdbBridge.isPaired(c);
    }

    /** Pairing with the code from Settings > Developer options > Wireless debugging > Pair device. */
    static void pair(Context c, int port, String code) throws Exception {
        AdbBridge b = AdbBridge.get(c);
        boolean ok = b.pair("127.0.0.1", port, code);
        if (!ok) throw new AdbFailure("adb_pair_failed", c.getString(R.string.adb_pair_refused));
        AdbBridge.markPaired(c, true);
    }

    /** Launches the helper and returns its Binder (as an IHotspotService). */
    static IHotspotService launch(Context app) throws Exception {
        if (!AdbBridge.isPaired(app)) throw new AdbFailure("adb_not_paired", "adb is not paired yet");

        byte[] r = new byte[16];
        new SecureRandom().nextBytes(r);
        StringBuilder nonce = new StringBuilder();
        for (byte x : r) nonce.append(String.format("%02x", x & 0xff));

        CompletableFuture<IBinder> binderFuture = HotspotBinderReceiver.expect(nonce.toString());
        AdbBridge b = AdbBridge.get(app);
        try {
            boolean connected = false;
            final int tcpPort = AdbBridge.tcpPort(app);      // random (kept) unless the user chose one (1024-65535)
            // 1. The TCP port a previous start left open (TCP mode): no Wi-Fi, no Wireless debugging.
            if (AdbPorts.listening(tcpPort)) connected = tryConnect(b, tcpPort, 2);
            // 2. Otherwise Wireless debugging, which needs Wi-Fi: the first start after every boot.
            if (!connected) {
                connectWireless(app, b);
                // 3. TCP mode: open the classic port now, BEFORE the helper starts. `tcpip` restarts
                //    adbd and takes down anything it spawned, so a helper started first would die.
                if (AdbBridge.isTcpMode(app)) {
                    boolean onTcp = openTcpPort(b, tcpPort);
                    closeQuietly(b);
                    if (onTcp) onTcp = tryConnect(b, tcpPort, 6);
                    if (!onTcp) connectWireless(app, b);     // adbd restarted underneath us: back to wireless
                }
            }

            String apk = app.getApplicationInfo().sourceDir;
            String cmd = "(CLASSPATH='" + apk + "' setsid app_process /system/bin"
                    + " --nice-name=mv2r_hotspot " + HotspotMain.class.getName()
                    + " " + app.getPackageName() + " " + nonce
                    + " >" + LOG_FILE + " 2>&1 </dev/null &); echo launched uid=$(id -u)";
            String out = run(b, cmd);
            // The reply is only a hint: the stream may close before it is read. The real proof is the
            // helper's broadcast below. A reply naming another uid means adbd is not the shell.
            if (out.contains("uid=") && !out.contains("uid=2000")) {
                throw new AdbFailure("adb_launch_failed", "unexpected shell reply: " + out.trim());
            }
        } finally {
            try {
                b.close();                       // the detached helper does not depend on this session
            } catch (Throwable ignored) {
            }
        }

        try {
            IBinder binder = binderFuture.get(BINDER_TIMEOUT_S, TimeUnit.SECONDS);
            return IHotspotService.Stub.asInterface(binder);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AdbFailure("adb_no_binder", "the helper started but never reported back; see " + LOG_FILE);
        } finally {
            HotspotBinderReceiver.cancel();
        }
    }

    /**
     * Root mode: starts HotspotMain straight from the root shell, no pairing and no Wireless debugging.
     * The helper is dropped to the shell user (uid 2000) with `su 2000` so it runs exactly like the adb
     * variant (the tethering code expects "com.android.shell"); when that is not possible it simply runs
     * as root, which the platform also accepts for every permission HotspotService needs.
     */
    static IHotspotService launchRoot(Context app) throws Exception {
        byte[] r = new byte[16];
        new SecureRandom().nextBytes(r);
        StringBuilder nonce = new StringBuilder();
        for (byte x : r) nonce.append(String.format("%02x", x & 0xff));

        CompletableFuture<IBinder> binderFuture = HotspotBinderReceiver.expect(nonce.toString());
        try {
            String apk = app.getApplicationInfo().sourceDir;
            String inner = "(CLASSPATH='" + apk + "' setsid app_process /system/bin"
                    + " --nice-name=mv2r_hotspot " + HotspotMain.class.getName()
                    + " " + app.getPackageName() + " " + nonce
                    + " >" + LOG_FILE + " 2>&1 </dev/null &)";
            String script = "rm -f " + LOG_FILE + "\n"
                    + "INNER=" + RootShell.sq(inner) + "\n"
                    + "U=$(su 2000 -c 'id -u' </dev/null 2>/dev/null)\n"
                    + "if [ \"$U\" = 2000 ]; then\n"
                    + "  su 2000 -c \"$INNER\" </dev/null\n"
                    + "  echo launched uid=2000\n"
                    + "else\n"
                    + "  sh -c \"$INNER\" </dev/null\n"
                    + "  echo launched uid=$(id -u)\n"
                    + "fi\n";
            RootShell.Result res = RootShell.run(script, 20_000);
            if (!res.ok() || !res.out.contains("launched uid=")) {
                throw new AdbFailure("root_launch_failed", "root shell: exit " + res.code + " " + res.err.trim());
            }
        } catch (AdbFailure f) {
            HotspotBinderReceiver.cancel();
            throw f;
        }

        try {
            IBinder binder = binderFuture.get(BINDER_TIMEOUT_S, TimeUnit.SECONDS);
            return IHotspotService.Stub.asInterface(binder);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AdbFailure("adb_no_binder", "the helper started but never reported back; see " + LOG_FILE);
        } finally {
            HotspotBinderReceiver.cancel();
        }
    }

    /** Wireless debugging (TLS) connection; throws AdbFailure when there is none. */
    private static void connectWireless(Context app, AdbBridge b) throws AdbFailure {
        boolean connected = false;
        String why = "";
        int port = AdbPorts.findConnectPort(app, CONNECT_TIMEOUT_MS);
        if (port > 0) {
            try {
                connected = b.connect("127.0.0.1", port);
            } catch (Exception e) {
                why = e.getMessage();
            }
        }
        if (!connected) {                        // last resort: the library's own discovery
            try {
                connected = b.autoConnect(app, CONNECT_TIMEOUT_MS);
            } catch (Exception e) {
                if (why == null || why.isEmpty()) why = e.getMessage();
            }
        }
        if (!connected) {
            throw new AdbFailure("adb_connect_failed",
                    "no Wireless debugging port found (is it on, with Wi-Fi connected?)"
                            + (why == null || why.isEmpty() ? "" : ": " + why));
        }
    }

    private static boolean tryConnect(AdbBridge b, int port, int attempts) {
        for (int i = 0; i < attempts; i++) {
            try {
                if (b.connect("127.0.0.1", port)) return true;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Asks adbd (over the live wireless connection) to listen on {@code port} (TCP mode), and waits for it. */
    private static boolean openTcpPort(AdbBridge b, int port) {
        AdbStream s = null;
        try {
            s = b.openStream("tcpip:" + port);
            InputStream in = s.openInputStream();
            byte[] buf = new byte[256];
            while (in.read(buf) > 0) {
                // "restarting in TCP mode port: <port>"; adbd then drops the connection
            }
        } catch (Exception expected) {
            // the connection dying is how adbd confirms the restart
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
        for (int i = 0; i < 40; i++) {           // up to 10 s
            if (AdbPorts.listening(port)) return true;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void closeQuietly(AdbBridge b) {
        try {
            b.close();
        } catch (Throwable ignored) {
        }
    }

    /** How long {@link #run} waits for the shell to answer before it gives up (and carries on). */
    private static final long RUN_TIMEOUT_MS = 8_000;

    /**
     * Runs a shell command over adb and returns what it printed. It does NOT wait for the stream to
     * close: adbd keeps a `shell:` stream open while a background child still holds the pty, so a
     * detached helper ("... &") used to block this read forever and the UI sat on "Starting..." with
     * the helper already up. It stops as soon as the reply line "launched uid=..." is complete, or
     * after {@link #RUN_TIMEOUT_MS}; the real proof that the helper runs is its Binder, not this reply.
     */
    private static String run(AdbBridge b, String cmd) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        AdbStream s = null;
        try {
            s = b.openStream("shell:" + cmd);
            final InputStream in = s.openInputStream();
            Thread t = new Thread(() -> {
                byte[] buf = new byte[1024];
                int n;
                try {
                    while (o.size() < 8192 && (n = in.read(buf)) > 0) {
                        String text;
                        synchronized (o) {
                            o.write(buf, 0, n);
                            text = o.toString("UTF-8");
                        }
                        int at = text.indexOf("launched uid=");
                        if (at >= 0 && text.indexOf('\n', at) >= 0) break;
                    }
                } catch (Exception closedOrFailed) {
                    // "Stream closed" is how the library reports the shell ending: not an error here
                } finally {
                    done.countDown();
                }
            }, "mv-adb-run");
            t.setDaemon(true);
            t.start();
            try {
                done.await(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) {
                try {
                    s.close();                  // also unblocks the reader thread
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            synchronized (o) {
                return o.toString("UTF-8");
            }
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    @SuppressWarnings("unused")
    static int myUid() {
        return Process.myUid();
    }
}
