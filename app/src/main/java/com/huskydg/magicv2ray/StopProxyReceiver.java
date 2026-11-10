package com.huskydg.magicv2ray;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The "Turn off proxy" button of the status notification. */
public class StopProxyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ProxyNotifier.ACTION_STOP.equals(intent.getAction())) return;
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                ProxyNotifier.stopProxy(app);
            } finally {
                pr.finish();
            }
        }, "mv-notif-stop").start();
    }
}
