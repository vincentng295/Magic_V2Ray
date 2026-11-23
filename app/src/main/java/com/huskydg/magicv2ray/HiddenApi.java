package com.huskydg.magicv2ray;

import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * libadb-android derives the pairing secrets with the platform's Conscrypt.exportKeyingMaterial,
 * which is not in the public SDK (MuntashirAkon/libadb-android#40): pairing from the same device
 * fails with NoSuchMethodException on Android 9+ unless the hidden-API check is lifted for it.
 */
final class HiddenApi {
    private static boolean done;

    private HiddenApi() {
    }

    static synchronized void exemptForConscrypt() {
        if (done || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/");
            done = true;
        } catch (Throwable ignored) {
        }
    }
}
