package com.huskydg.magicv2ray;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;

/**
 * Shizuku UserService that runs as the shell user (UID 2000) and owns everything that needs
 * shell privileges for "Share proxy via Shizuku": the test-network TUN, the tethering upstream
 * preference and the hotspot / USB downstream.
 *
 * Every transaction id is explicit (AIDL does not allow mixing). 16777114 is the "destroy"
 * method Shizuku itself calls; the others are ours.
 *
 * Start-up is two-phase on purpose: prepare() creates the TUN without routing anything into it,
 * the app starts the xray bridge on the fd from getTunFd(), and only then activate() lets the
 * tethering stack select the TUN. Clients are never routed into a TUN nobody is reading.
 */
interface IHotspotService {

    /** Reserved by Shizuku: tear everything down and System.exit(). */
    void destroy() = 16777114;

    int getContractVersion() = 1;

    /** JSON: {"ok":bool,"reason":string,"sdk":int,"uid":int}. */
    String checkCompatibility() = 2;

    /**
     * Creates the TUN and registers it as a test network. {@code options} is JSON
     * ({"wifi":bool,"usb":bool}); {@code lifetime} is a Binder owned by the app, when it dies the
     * session is torn down. Returns the status JSON.
     */
    String prepare(String options, IBinder lifetime) = 3;

    /** A dup of the TUN fd. Valid between prepare() and stop(). */
    ParcelFileDescriptor getTunFd() = 4;

    /** Makes tethering prefer the TUN and starts the downstream(s). Returns the status JSON. */
    String activate() = 5;

    String stop() = 6;

    String getStatus() = 7;
}
