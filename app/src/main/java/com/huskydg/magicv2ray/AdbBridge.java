package com.huskydg.magicv2ray;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * The app's adb identity (RSA key + self-signed certificate, generated once and kept in private
 * storage) on top of libadb-android. Everything that touches the library's API lives in this file
 * and AdbHelper, so a version difference is a one-file fix.
 */
final class AdbBridge extends AbsAdbConnectionManager {

    private static final String PREFS = "adb_identity";
    private static final String K_KEY = "key";
    private static final String K_CERT = "cert";
    private static final String K_PAIRED = "paired";
    private static final String K_TCP = "tcp_mode";
    private static final String K_TCP_PORT = "tcp_port";
    static final int MIN_TCP_PORT = 1024;
    static final int MAX_TCP_PORT = 65535;
    private static final long TWENTY_YEARS_MS = 20L * 365 * 24 * 3600 * 1000;

    private static AdbBridge instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    static synchronized AdbBridge get(Context c) throws Exception {
        if (instance == null) instance = new AdbBridge(c.getApplicationContext());
        return instance;
    }

    private AdbBridge(Context c) throws Exception {
        HiddenApi.exemptForConscrypt();
        setApi(Build.VERSION.SDK_INT);

        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        PrivateKey k = null;
        Certificate cert = null;
        String ks = sp.getString(K_KEY, null);
        String cs = sp.getString(K_CERT, null);
        if (ks != null && cs != null) {
            try {
                k = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(ks, Base64.NO_WRAP)));
                cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Base64.decode(cs, Base64.NO_WRAP)));
            } catch (Exception broken) {
                k = null;
                cert = null;
            }
        }
        if (k == null || cert == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            long now = System.currentTimeMillis();
            byte[] der = AdbCert.selfSigned(kp, "MagicV2Ray", now - 24L * 3600 * 1000, now + TWENTY_YEARS_MS);
            cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
            k = kp.getPrivate();
            // A new identity is a new, unpaired client.
            sp.edit()
                    .putString(K_KEY, Base64.encodeToString(k.getEncoded(), Base64.NO_WRAP))
                    .putString(K_CERT, Base64.encodeToString(der, Base64.NO_WRAP))
                    .putBoolean(K_PAIRED, false)
                    .apply();
        }
        privateKey = k;
        certificate = cert;
    }

    // public (not protected): widening is legal whichever visibility the library declares
    @Override
    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    public Certificate getCertificate() {
        return certificate;
    }

    @Override
    public String getDeviceName() {
        return "MagicV2Ray";
    }

    static boolean isPaired(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(K_PAIRED, false);
    }

    static void markPaired(Context c, boolean paired) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_PAIRED, paired).apply();
    }

    /** TCP mode (default on): keep adbd listening on a classic port so the helper restarts without Wi-Fi. */
    static boolean isTcpMode(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(K_TCP, true);
    }

    static void setTcpMode(Context c, boolean on) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_TCP, on).apply();
    }

    /** True for a port TCP mode may use: 1024-65535 (below 1024 is privileged; adbd would not bind it safely). */
    static boolean validTcpPort(int port) {
        return port >= MIN_TCP_PORT && port <= MAX_TCP_PORT;
    }

    /**
     * The port TCP mode listens on. By default a random port in 1024-65535, picked the first time it is needed and
     * then kept (so every later start reconnects to the same port); the user can type another one or re-roll it.
     */
    static synchronized int tcpPort(Context c) {
        android.content.SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int p = sp.getInt(K_TCP_PORT, 0);
        if (validTcpPort(p)) return p;
        p = randomTcpPort();
        sp.edit().putInt(K_TCP_PORT, p).apply();
        return p;
    }

    /** @return false (nothing saved) when {@code port} is outside 1024-65535 */
    static boolean setTcpPort(Context c, int port) {
        if (!validTcpPort(port)) return false;
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(K_TCP_PORT, port).apply();
        return true;
    }

    /** A random port in 1024-65535 that nothing on this phone is listening on right now. */
    static int randomTcpPort() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < 50; i++) {
            int p = MIN_TCP_PORT + r.nextInt(MAX_TCP_PORT - MIN_TCP_PORT + 1);
            if (!AdbPorts.listening(p)) return p;
        }
        return MIN_TCP_PORT + r.nextInt(MAX_TCP_PORT - MIN_TCP_PORT + 1);
    }
}
