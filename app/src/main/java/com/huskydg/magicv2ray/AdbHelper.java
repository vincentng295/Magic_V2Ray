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
    static final int TCP_PORT = 5555;
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
            // 1. The classic port a previous start left open (TCP mode): no Wi-Fi, no Wireless debugging.
            if (AdbPorts.listening(TCP_PORT)) connected = tryConnect(b, TCP_PORT, 2);
            // 2. Otherwise Wireless debugging, which needs Wi-Fi: the first start after every boot.
            if (!connected) {
                connectWireless(app, b);
                // 3. TCP mode: open the classic port now, BEFORE the helper starts. `tcpip` restarts
                //    adbd and takes down anything it spawned, so a helper started first would die.
                if (AdbBridge.isTcpMode(app)) {
                    boolean onTcp = openTcpPort(b);
                    closeQuietly(b);
                    if (onTcp) onTcp = tryConnect(b, TCP_PORT, 6);
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

    /** Asks adbd (over the live wireless connection) to also listen on TCP_PORT, and waits for it. */
    private static boolean openTcpPort(AdbBridge b) {
        AdbStream s = null;
        try {
            s = b.openStream("tcpip:" + TCP_PORT);
            InputStream in = s.openInputStream();
            byte[] buf = new byte[256];
            while (in.read(buf) > 0) {
                // "restarting in TCP mode port: 5555"; adbd then drops the connection
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
            if (AdbPorts.listening(TCP_PORT)) return true;
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

    private static String run(AdbBridge b, String cmd) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        AdbStream s = null;
        try {
            s = b.openStream("shell:" + cmd);
            InputStream in = s.openInputStream();
            byte[] buf = new byte[1024];
            int n;
            while (o.size() < 8192 && (n = in.read(buf)) > 0) o.write(buf, 0, n);
        } catch (java.io.IOException closed) {
            // "Stream closed" is how the library reports the shell ending: not an error here
        } catch (Exception ignored) {
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            return o.toString("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    @SuppressWarnings("unused")
    static int myUid() {
        return Process.myUid();
    }
}
