package com.huskydg.magicv2ray;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Non-root only: answers xray's "which app owns this connection?" for routing rules with {@code process}.
 *
 * <p>Xray runs as a child process of this app and cannot read /proc/net (SELinux), but this app is the active VPN,
 * and a VPN app may call {@link ConnectivityManager#getConnectionOwnerUid} (API 29+). Xray reaches this server
 * through the abstract unix socket named in the env var MV_UID_SOCK (see common/net/find_uid_app.go in the xray
 * fork). One request per connection, one text line each way:
 * <pre>
 *   request : tcp|udp SRC_IP SRC_PORT DST_IP DST_PORT
 *   reply   : UID            (-1 = the system does not know the socket)
 *           | err MESSAGE
 * </pre>
 * Only the app's own UID may connect (checked with the peer credentials), so other apps cannot probe
 * which app owns which connection.
 */
final class UidLookupServer {
    /** Name of the abstract socket; xray gets it in MV_UID_SOCK. */
    final String name;

    private final LocalServerSocket server;
    private final ConnectivityManager cm;
    private final int myUid = android.os.Process.myUid();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean closed;

    UidLookupServer(Context c) throws IOException {
        cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        name = "mv-uid-" + android.os.Process.myPid() + "-" + System.nanoTime();
        server = new LocalServerSocket(name);
        Thread t = new Thread(this::acceptLoop, "mv-uid-accept");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!closed) {
            final LocalSocket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                return;                                   // closed
            }
            try {
                pool.execute(() -> handle(s));
            } catch (RuntimeException e) {                // pool shut down
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                return;
            }
        }
    }

    private void handle(LocalSocket s) {
        try (LocalSocket sock = s) {
            sock.setSoTimeout(2000);
            String reply;
            if (sock.getPeerCredentials().getUid() != myUid) {
                reply = "err forbidden";
            } else {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII), 256);
                reply = lookup(in.readLine());
            }
            OutputStream out = sock.getOutputStream();
            out.write((reply + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private String lookup(String line) {
        if (line == null) return "err empty request";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "err needs Android 10";
        String[] f = line.trim().split(" ");
        if (f.length != 5) return "err bad request";
        try {
            int proto;
            if (f[0].equals("tcp")) proto = OsConstants.IPPROTO_TCP;
            else if (f[0].equals("udp")) proto = OsConstants.IPPROTO_UDP;
            else return "err bad protocol";
            // numeric addresses only: getByName() never resolves a literal, and the others are rejected
            if (!isNumeric(f[1]) || !isNumeric(f[3])) return "err bad address";
            InetSocketAddress local = new InetSocketAddress(InetAddress.getByName(f[1]), Integer.parseInt(f[2]));
            InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName(f[3]), Integer.parseInt(f[4]));
            return Integer.toString(cm.getConnectionOwnerUid(proto, local, remote));
        } catch (Exception e) {                           // SecurityException (not the VPN owner), bad port, ...
            return "err " + e.getClass().getSimpleName();
        }
    }

    private static boolean isNumeric(String a) {
        if (a.isEmpty()) return false;
        for (int i = 0; i < a.length(); i++) {
            char ch = a.charAt(i);
            if (!(ch == ':' || ch == '.' || Character.digit(ch, 16) >= 0)) return false;
        }
        return true;
    }
}
