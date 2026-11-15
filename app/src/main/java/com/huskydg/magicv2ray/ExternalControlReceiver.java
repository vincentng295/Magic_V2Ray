package com.huskydg.magicv2ray;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/**
 * {@code sendBroadcast} entry of the external control API (see {@link ExternalSession}).
 *
 * <ul>
 *   <li>DISCONNECT ends a running external session; needs no UI.</li>
 *   <li>CONNECT only parks the request: a broadcast cannot open the confirmation from the background
 *       (Android 10+), so a notification asks the user to review it. Android 14+ only tells us who
 *       sent a broadcast when the sender opts in; without that the caller is "unknown" and is
 *       never trusted.</li>
 * </ul>
 */
public class ExternalControlReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final Context app = context.getApplicationContext();
        String action = intent.getAction();
        if (ExternalSession.ACTION_DISCONNECT.equals(action)) {
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    ExternalSession.end(app);
                } finally {
                    pr.finish();
                }
            }, "mv-ext-end").start();
            return;
        }
        if (!ExternalSession.ACTION_CONNECT.equals(action)) return;

        String caller = null;
        if (Build.VERSION.SDK_INT >= 34) {
            caller = getSentFromPackage();
            if (caller != null && caller.equals(app.getPackageName())) caller = null;
        }
        JSONObject req = ExternalSession.request(app, intent, caller, true);
        if (req == null) return;
        ExternalSession.queue(app, req);

        MainActivity m = MainActivity.current;
        if (m != null && MainActivity.resumed) {
            m.pokeWeb("window.checkExternalRequest&&window.checkExternalRequest()");
        } else {
            ExternalSession.notifyPending(app, req);
        }
    }
}
