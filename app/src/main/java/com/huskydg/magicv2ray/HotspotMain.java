package com.huskydg.magicv2ray;

import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Entry point of the "no Shizuku" hotspot helper. Started by the app over adb (see AdbHelper):
 *
 *   CLASSPATH=/data/app/.../base.apk setsid app_process /system/bin --nice-name=mv2r_hotspot \
 *       com.huskydg.magicv2ray.HotspotMain <package> <nonce>
 *
 * It runs as the shell user (UID 2000), builds the same {@link HotspotService} Shizuku would
 * have instantiated (test-network TUN + tethering upstream + downstream) and hands its Binder to the
 * app with an explicit broadcast carrying the one-time nonce. From there the app talks to it
 * exactly as it does with the Shizuku UserService (prepare / getTunFd / activate / stop).
 *
 * Like the Shizuku variant it touches no other class of the app: only the APK is on its class path.
 * It ends itself when nobody picked it up, or after the session ended (stop / app died).
 */
public final class HotspotMain {

    static final String ACTION = "com.huskydg.magicv2ray.action.HOTSPOT_BINDER";
    static final String EXTRA_BUNDLE = "bundle";
    static final String EXTRA_NONCE = "nonce";
    static final String KEY_BINDER = "binder";

    private static final long NOBODY_CAME_MS = 90_000;
    private static final long POLL_MS = 2_000;
    private static final int MAX_SENDS = 20;

    private HotspotMain() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: HotspotMain <package> <nonce>");
            System.exit(2);
            return;
        }
        final String pkg = args[0];
        final String nonce = args[1];

        Looper.prepareMainLooper();
        final HotspotService svc;
        try {
            svc = new HotspotService();
        } catch (Throwable t) {
            System.err.println("cannot create the service: " + t);
            t.printStackTrace();
            System.exit(1);
            return;
        }

        Thread t = new Thread(() -> supervise(svc, pkg, nonce), "mv-hotspot-main");
        t.setDaemon(true);
        t.start();
        System.out.println("hotspot helper up, uid " + android.os.Process.myUid() + ", pid "
                + android.os.Process.myPid());
        Looper.loop();
    }

    /** Delivers the Binder until the app has used it, then waits for the session to end. */
    private static void supervise(HotspotService svc, String pkg, String nonce) {
        final long start = SystemClock.elapsedRealtime();
        boolean picked = false;
        int sends = 0;
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            String state = stateOf(svc);
            if (!picked && !"IDLE".equals(state)) picked = true;

            if (!picked) {
                if (sends < MAX_SENDS) {
                    sends++;
                    try {
                        svc.sendBinderToApp(pkg, nonce);
                    } catch (Throwable e) {
                        System.err.println("broadcast " + sends + " failed: " + e);
                    }
                }
                if (SystemClock.elapsedRealtime() - start > NOBODY_CAME_MS) {
                    System.err.println("nobody picked the binder up, exiting");
                    exit(svc);
                }
            } else if ("IDLE".equals(state) || "ERROR".equals(state)) {
                // Session over (the app stopped it, or died and the lifetime binder fired).
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException ignored) {
                }
                exit(svc);
            }
        }
    }

    private static String stateOf(HotspotService svc) {
        try {
            return new JSONObject(svc.getStatus()).optString("state", "IDLE");
        } catch (Throwable t) {
            return "IDLE";
        }
    }

    private static void exit(HotspotService svc) {
        try {
            svc.destroy();                  // stop() + System.exit(0)
        } finally {
            System.exit(0);
        }
    }
}
