package com.huskydg.magicv2ray;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Pairing from a notification. The pairing code changes every time the Settings dialog closes,
 * so leaving Settings to type it into the app would invalidate it. Instead this foreground service
 * finds the pairing port itself (mDNS _adb-tls-pairing._tcp) and shows a notification with a reply
 * field: type the code there while the Settings dialog stays on screen.
 */
public final class AdbPairingService extends Service {

    static final String ACTION_START = "com.huskydg.magicv2ray.action.ADB_PAIR_START";
    static final String ACTION_STOP = "com.huskydg.magicv2ray.action.ADB_PAIR_STOP";
    static final String KEY_CODE = "pairing_code";
    private static final String CHANNEL = "adb_pairing";
    private static final int ID = 7411;
    private static final String SERVICE_TYPE = "_adb-tls-pairing._tcp";

    /** Port of the pairing dialog that is open right now, or -1. */
    static volatile int port = -1;
    private static volatile boolean running;
    private static volatile AdbPairingService instance;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;

    static void start(Context c) {
        Intent i = new Intent(c, AdbPairingService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /** Re-posts the reply notification (a RemoteInput reply leaves it in "sending" until updated). */
    static void rearm() {
        AdbPairingService s = instance;
        if (s == null) return;
        boolean found = port > 0;
        s.notifyNow(s.build(found ? s.getString0("Enter the pairing code shown in Settings", "Nhập mã ghép nối đang hiện trong Cài đặt")
                : s.getString0("Open \"Pair device with pairing code\" again", "Hãy mở lại \"Ghép nối thiết bị bằng mã\""), found));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, AdbPairingService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        instance = this;
        ensureChannel();
        startForeground(ID, build(getString0("Searching for the pairing dialog...", "Đang tìm hộp thoại ghép nối..."), false));
        if (!running) {
            running = true;
            port = -1;
            startDiscovery();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        port = -1;
        stopDiscovery();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ discovery

    private void startDiscovery() {
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String t) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo i, int err) {
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo i) {
                            port = i.getPort();
                            notifyNow(build(getString0("Enter the pairing code shown in Settings",
                                    "Nhập mã ghép nối đang hiện trong Cài đặt"), true));
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                // The dialog was closed: its code is void now.
                port = -1;
                notifyNow(build(getString0("Open \"Pair device with pairing code\" again",
                        "Hãy mở lại \"Ghép nối thiết bị bằng mã\""), false));
            }

            @Override
            public void onDiscoveryStopped(String t) {
            }

            @Override
            public void onStartDiscoveryFailed(String t, int err) {
                notifyNow(build("mDNS discovery failed (" + err + ")", false));
            }

            @Override
            public void onStopDiscoveryFailed(String t, int err) {
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Throwable t) {
            notifyNow(build("mDNS discovery failed: " + t.getMessage(), false));
        }
    }

    private void stopDiscovery() {
        try {
            if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (Throwable ignored) {
        }
        discovery = null;
    }

    // ------------------------------------------------------------------ notification

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "adb pairing",
                NotificationManager.IMPORTANCE_HIGH));
    }

    private void notifyNow(Notification n) {
        getSystemService(NotificationManager.class).notify(ID, n);
    }

    private Notification build(String text, boolean withInput) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("adb pairing")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, AdbPairingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(new Notification.Action.Builder(null, getString0("Cancel", "Hủy"), stop).build());
        if (withInput) {
            RemoteInput ri = new RemoteInput.Builder(KEY_CODE)
                    .setLabel(getString0("Pairing code", "Mã ghép nối")).build();
            // RemoteInput results are written into the intent: it has to be mutable on Android 12+.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent reply = PendingIntent.getBroadcast(this, 2,
                    new Intent(this, AdbPairReplyReceiver.class).setPackage(getPackageName()), flags);
            b.addAction(new Notification.Action.Builder(null, getString0("Enter code", "Nhập mã"), reply)
                    .addRemoteInput(ri).build());
        }
        return b.build();
    }

    private static String getString0(String en, String vi) {
        return java.util.Locale.getDefault().getLanguage().equals("vi") ? vi : en;
    }

    static void postResult(Context c, String text) {
        ensureResultChannel(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_tile).setContentTitle("adb pairing").setContentText(text).setAutoCancel(true);
        c.getSystemService(NotificationManager.class).notify(ID + 1, b.build());
    }

    private static void ensureResultChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        c.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "adb pairing", NotificationManager.IMPORTANCE_HIGH));
    }
}
