package com.huskydg.magicv2ray;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Receives the code typed into the pairing notification and pairs with the port the service found. */
public final class AdbPairReplyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        Bundle r = RemoteInput.getResultsFromIntent(intent);
        final CharSequence cs = r == null ? null : r.getCharSequence(AdbPairingService.KEY_CODE);
        final String code = cs == null ? "" : cs.toString().trim();
        final int port = AdbPairingService.port;
        final Context app = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            String msg;
            boolean done = false;
            try {
                if (port <= 0) {
                    msg = "The pairing dialog is closed (its code is void). Open it again and retry.";
                } else if (code.isEmpty()) {
                    msg = "No code entered.";
                } else {
                    AdbHelper.pair(app, port, code);
                    msg = "Paired. You can start sharing now.";
                    done = true;
                }
            } catch (Throwable t) {
                msg = "Pairing failed: " + t.getMessage();
            }
            AdbPairingService.postResult(app, msg);
            if (done) AdbPairingService.stop(app);
            else AdbPairingService.rearm();             // reply field again for another try
            pr.finish();
        }, "mv-adb-pair").start();
    }
}
