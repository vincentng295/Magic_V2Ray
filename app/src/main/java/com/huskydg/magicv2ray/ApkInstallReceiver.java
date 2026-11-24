package com.huskydg.magicv2ray;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/**
 * Result of the PackageInstaller session started by {@link ApkInstaller}.
 *
 * STATUS_PENDING_USER_ACTION: Android wants the user to confirm, the system dialog is started from here.
 * STATUS_SUCCESS: usually never seen when the app updates itself (its process is replaced).
 * Anything else is reported to the WebUI as an error.
 */
public class ApkInstallReceiver extends BroadcastReceiver {

    @SuppressWarnings("deprecation")
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ApkInstaller.ACTION_RESULT.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) {
                    ApkInstaller.emitError("failed", "the system gave no confirmation screen");
                    return;
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                    ApkInstaller.emit("confirm", 100, 0, 0, null, null);
                } catch (RuntimeException e) {
                    ApkInstaller.emitError("failed", "cannot show the install screen: " + e.getMessage());
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                ApkInstaller.emit("done", 100, 0, 0, null, null);
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                ApkInstaller.emitError("aborted", msg == null ? "cancelled" : msg);
                break;
            default:
                ApkInstaller.emitError("failed", msg == null ? ("install status " + status) : msg);
        }
    }
}
