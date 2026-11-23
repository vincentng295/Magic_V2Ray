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
        if (!ok) throw new AdbFailure("adb_pair_failed", "pairing was refused (wrong code or port?)");
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
            boolean connected;
            try {
                connected = b.autoConnect(app, CONNECT_TIMEOUT_MS);
            } catch (Exception e) {
                throw new AdbFailure("adb_connect_failed", "cannot reach Wireless debugging: " + e.getMessage());
            }
            if (!connected) {
                throw new AdbFailure("adb_connect_failed",
                        "Wireless debugging is off or Wi-Fi is not connected (it is only needed for this step)");
            }

            String apk = app.getApplicationInfo().sourceDir;
            String cmd = "(CLASSPATH='" + apk + "' setsid app_process /system/bin"
                    + " --nice-name=mv2r_hotspot " + HotspotMain.class.getName()
                    + " " + app.getPackageName() + " " + nonce
                    + " >" + LOG_FILE + " 2>&1 </dev/null &); echo launched uid=$(id -u)";
            String out = run(b, cmd);
            if (!out.contains("launched uid=2000")) {
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

    private static String run(AdbBridge b, String cmd) throws Exception {
        try (AdbStream s = b.openStream("shell:" + cmd);
             InputStream in = s.openInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while (o.size() < 8192 && (n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        }
    }

    @SuppressWarnings("unused")
    static int myUid() {
        return Process.myUid();
    }
}
