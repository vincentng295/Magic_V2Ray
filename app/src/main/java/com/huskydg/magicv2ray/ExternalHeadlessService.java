package com.huskydg.magicv2ray;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the process alive, in the foreground, while {@link ExternalHeadless} runs an approved external request
 * without showing the app. It lives for a few seconds and stops itself (see ExternalHeadless.shutdown).
 */
public class ExternalHeadlessService extends Service {

    /** 1 = proxy status (ProxyNotifier), 2 = connection request (ExternalSession). */
    private static final int NOTIF_ID = 3;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // must be in the foreground within seconds of startForegroundService()
        ProxyNotifier.ensureChannel(this);
        Notification n = new Notification.Builder(this, ProxyNotifier.CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.ext_review_title))
                .setSmallIcon(R.drawable.ic_tile)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        ExternalHeadless.attach(this);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        ExternalHeadless.onServiceDestroyed(this);
        super.onDestroy();
    }
}
