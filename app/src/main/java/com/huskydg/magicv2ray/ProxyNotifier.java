package com.huskydg.magicv2ray;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;

/**
 * The "proxy is active" notification: shows the selected node and a "Turn off proxy" action.
 *
 * Non-root mode: it is the foreground notification of {@link MagicVpnService}.
 * Root mode: there is no service in the app, so it is a plain ongoing notification posted
 * whenever the app or the Quick Settings tile learns the proxy status (see {@link #showRoot}).
 */
final class ProxyNotifier {

    static final String CHANNEL = "mv_vpn";
    static final int NOTIF_ID = 1;
    static final String ACTION_STOP = "com.huskydg.magicv2ray.STOP_PROXY";
    private static final String PREFS = "proxy_state";
    private static final int REQ_NOTIF = 3;
    private static final int MAX_NAME = 120;

    private ProxyNotifier() {
    }

    // ------------------------------------------------------------ node name

    /** Name of the selected node, as last reported by the WebUI ("" when unknown). */
    static String node(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("node", "");
    }

    static void setNode(Context c, String name) {
        String n = name == null ? "" : name.replaceAll("[\\r\\n]+", " ").trim();
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME) + "…";
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("node", n).apply();
    }

    // --------------------------------------------------------- notification

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, c.getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW));
        }
    }

    /** @param standby VPN up but the proxy idle on the unselected network (non-root "Apply proxy on") */
    static Notification build(Context c, boolean standby) {
        String node = node(c);
        String text;
        if (node.isEmpty()) {
            text = c.getString(standby ? R.string.vpn_notif_paused : R.string.vpn_notif_text);
        } else {
            text = c.getString(R.string.notif_node, node);
            if (standby) text += "\n" + c.getString(R.string.vpn_notif_paused);
        }

        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getBroadcast(c, 1,
                new Intent(c, StopProxyReceiver.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
        return nb.setContentTitle(c.getString(standby ? R.string.notif_title_paused : R.string.notif_title_on))
                .setContentText(text.replace('\n', ' '))
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_tile)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, R.drawable.ic_tile),
                        c.getString(R.string.notif_stop), stop).build())
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    /** Root mode: post the notification while the proxy runs, remove it otherwise. */
    static void showRoot(Context c, boolean running) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        OomGuard.start(c);          // root mode: keep this process (and its notification) alive
        try {
            if (running) {
                ensureChannel(c);
                nm.notify(NOTIF_ID, build(c, false));
            } else {
                nm.cancel(NOTIF_ID);
            }
        } catch (RuntimeException ignored) {    // notifications blocked: nothing to show
        }
    }

    /** Turns the proxy off; blocks, call it from a worker thread. */
    static void stopProxy(Context c) {
        if (RunMode.isNonRoot(c)) {
            VpnController.stop();           // the service removes its own notification
            return;
        }
        RootShell.init(c);
        RootShell.run("sh " + RootShell.sq(ModuleInstaller.MODDIR + "/proxy_control.sh") + " stop 2>&1", 30_000);
        showRoot(c, false);
    }

    /**
     * Android 13+: asks once for the notification permission.
     * @return true when the dialog was shown (the answer arrives in onRequestPermissionsResult)
     */
    static boolean askPermissionOnce(Activity a) {
        if (Build.VERSION.SDK_INT < 33) return false;
        if (a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false;
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("asked_notif", false)) return false;
        p.edit().putBoolean("asked_notif", true).apply();
        a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        return true;
    }
}
