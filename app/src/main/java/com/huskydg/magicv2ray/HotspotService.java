package com.huskydg.magicv2ray;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Privileged half of "Share proxy via Shizuku". Runs in a Shizuku UserService, i.e. as the shell
 * user (UID 2000), in its own process; the app never calls anything in here directly, only through
 * {@link IHotspotService}. It must not touch any other class of this app: it is created by
 * app_process with nothing but the APK on its class path, no Application, no resources.
 *
 * The technique is the one the Shizzi project (github.com/carlelieser/shizzi) proved out:
 *
 *   1. TestNetworkManager creates a TUN and registers it as a "test network".
 *   2. TetheringManager.setPreferTestNetworks(true) makes the tethering stack pick that network
 *      as the hotspot / USB upstream (this is the load-bearing call, API 33+ or a new enough
 *      tethering module).
 *   3. TetheringManager.startTethering() brings up the downstream.
 *
 * Shell may do all of that (MANAGE_TEST_NETWORKS, TETHER_PRIVILEGED) but may not configure
 * routing itself, so the packets that arrive on the TUN need a userspace TCP/IP stack.
 * Shizzi ships a Go/gVisor one; Magic V2Ray already carries one, Xray's own `tun` inbound, so
 * this class only hands the TUN fd to the app (getTunFd) and the app feeds it to a small xray
 * "bridge" process that forwards into the main engine.
 *
 * Fail-closed is the main design rule: if tethering ever reports an upstream other than the TUN,
 * hotspot clients would reach the internet directly and bypass the proxy, so the session is
 * ended (see {@link #watchdogLoop}) rather than tolerated.
 */
public final class HotspotService extends IHotspotService.Stub {

    /** Bump together with the AIDL: the app refuses a service process built from other code. */
    static final int CONTRACT_VERSION = 1;

    private static final String TAG = "MagicHotspot";
    private static final String SHELL_PACKAGE = "com.android.shell";

    // Documentation ranges (RFC 5737 / 3849): they cannot collide with a real network, and they are
    // unrelated to the 198.18.0.1/16 the non-root VpnService uses.
    private static final String TUN_ADDRESS = "192.0.2.2";
    private static final int TUN_PREFIX = 24;
    private static final String TUN_ADDRESS_V6 = "2001:db8::2";
    private static final int TUN_PREFIX_V6 = 64;
    /** Keep in sync with the "mtu" of the bridge's tun inbound (NonRootEnv.hotspotConfig). */
    static final int TUN_MTU = 1500;
    // Tethering clients take their DNS from the upstream network; hijacked by Xray if enabled.
    private static final String[] TUN_DNS = {"1.1.1.1", "2606:4700:4700::1111"};

    static final int TETHERING_WIFI = 0;
    static final int TETHERING_USB = 1;

    private static final long AVAILABILITY_TIMEOUT_MS = 10_000;
    private static final long DOWNSTREAM_SETTLE_MS = 10_000;
    private static final long UPSTREAM_SETTLE_MS = 8_000;
    private static final long UPSTREAM_RELEASE_MS = 4_000;
    private static final long STOP_SETTLE_MS = 3_000;
    private static final long START_TIMEOUT_MS = 15_000;
    private static final long WATCHDOG_INTERVAL_MS = 5_000;
    private static final int DOWNSTREAM_STOP_ATTEMPTS = 3;
    private static final int LOG_LINES = 60;

    /** An upstream drifting to another interface leaks, so it is fatal after very few strikes. */
    private static final int DRIFT_STRIKES = 2;
    /** An absent upstream forwards nothing (nothing leaks), so it is tolerated much longer. */
    private static final int ABSENT_STRIKES = 12;

    private enum State { IDLE, PREPARED, STARTING, ACTIVE, ERROR }

    private final Context baseContext;
    private Context shellContext;

    private final Object lock = new Object();
    private final Binder testNetworkLifetime = new Binder();
    private final Deque<String> log = new ArrayDeque<>();
    private final Set<Integer> started = new LinkedHashSet<>();

    private State state = State.IDLE;
    private String detail = "not started";
    private boolean wantWifi = true;
    private boolean wantUsb = false;

    private Object tunInterface;               // android.net.TestNetworkInterface
    private ParcelFileDescriptor tunFd;
    private String interfaceName;
    private Network testNetwork;
    private ConnectivityManager.NetworkCallback keepAlive;
    private boolean preferSet;
    private long activeSince;

    private IBinder clientLifetime;
    private IBinder.DeathRecipient clientDeath;
    private Thread shutdownHook;
    private volatile Thread watchdog;

    private volatile int clientCount;
    private long clientCountAt;

    @SuppressWarnings("unused")             // Shizuku instantiates this one
    public HotspotService() {
        this(acquireSystemContext());
    }

    @SuppressWarnings("unused")             // Shizuku v13+ tries this one first
    public HotspotService(Context context) {
        this.baseContext = context;
    }

    // ------------------------------------------------------------------ AIDL

    @Override
    public int getContractVersion() {
        return CONTRACT_VERSION;
    }

    @Override
    public String checkCompatibility() {
        JSONObject o = new JSONObject();
        try {
            String code = "ok";
            if (Build.VERSION.SDK_INT < 30) {
                code = "sdk";
            } else if (!contextUsable(o)) {
                code = "context";
            } else if (!testNetworkAvailable()) {
                code = "no_test_network";
            } else if (!preferTestNetworksAvailable()) {
                code = "no_prefer";     // API 30-32 without a new enough tethering module
            }
            o.put("ok", "ok".equals(code));
            o.put("code", code);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("uid", android.os.Process.myUid());
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    @Override
    public String prepare(String options, IBinder lifetime) {
        synchronized (lock) {
            if (state != State.IDLE && state != State.ERROR) {
                return statusLocked();
            }
            parseOptions(options);
            state = State.STARTING;
            detail = "creating the test network";
            started.clear();
            note("prepare: wifi=" + wantWifi + " usb=" + wantUsb + ", sdk " + Build.VERSION.SDK_INT
                    + ", " + Build.MANUFACTURER + " " + Build.MODEL);
            try {
                JSONObject compat = new JSONObject(checkCompatibility());
                if (!compat.optBoolean("ok")) {
                    throw new IllegalStateException("not supported on this device: " + compat.optString("code"));
                }
                bringUpTun();
                watchClient(lifetime);
                installShutdownHook();
                state = State.PREPARED;
                detail = "tun up: " + interfaceName;
                note(detail + " (mtu " + TUN_MTU + ")");
            } catch (Throwable t) {
                fail("prepare", t);
            }
            return statusLocked();
        }
    }

    @Override
    public ParcelFileDescriptor getTunFd() {
        synchronized (lock) {
            if (tunFd == null || state == State.IDLE || state == State.ERROR) {
                throw new IllegalStateException("no TUN: prepare() has not succeeded");
            }
            try {
                return ParcelFileDescriptor.dup(tunFd.getFileDescriptor());
            } catch (IOException e) {
                throw new IllegalStateException("cannot duplicate the TUN fd: " + e.getMessage());
            }
        }
    }

    @Override
    public String activate() {
        synchronized (lock) {
            if (state != State.PREPARED) {
                return statusLocked();
            }
            state = State.STARTING;
            detail = "starting the downstream";
            try {
                // A stale flag from a killed session would make the next one fail in odd ways.
                try {
                    setPreferTestNetworks(false);
                } catch (Throwable t) {
                    note("could not clear the stale test-network preference: " + rootMessage(t));
                }
                setPreferTestNetworks(true);
                preferSet = true;
                note("tethering now prefers test networks");

                restartDownstream();
                verifyUpstream(interfaceName);
                note("upstream verified: " + interfaceName + " is the sole upstream");

                state = State.ACTIVE;
                activeSince = System.currentTimeMillis();
                detail = "hotspot clients route through " + interfaceName;
                startWatchdog();
            } catch (Throwable t) {
                fail("activate", t);
            }
            return statusLocked();
        }
    }

    @Override
    public String stop() {
        synchronized (lock) {
            note("stop requested");
            String problem = teardownLocked();
            if (problem == null) {
                state = State.IDLE;
                detail = "stopped";
            } else {
                state = State.ERROR;
                detail = problem;
            }
            return statusLocked();
        }
    }

    @Override
    public String getStatus() {
        synchronized (lock) {
            return statusLocked();
        }
    }

    /** Reserved Shizuku transaction: the app unbound with remove=true, or it died. */
    @Override
    public void destroy() {
        try {
            stop();
        } catch (Throwable t) {
            Log.w(TAG, "destroy: " + t);
        }
        System.exit(0);
    }

    // ------------------------------------------------------------- start-up

    private void parseOptions(String options) {
        wantWifi = true;
        wantUsb = false;
        try {
            JSONObject o = new JSONObject(options == null || options.isEmpty() ? "{}" : options);
            wantWifi = o.optBoolean("wifi", true);
            wantUsb = o.optBoolean("usb", false);
        } catch (JSONException ignored) {
        }
        if (!wantWifi && !wantUsb) wantWifi = true;
    }

    private void bringUpTun() throws Exception {
        List<LinkAddress> addresses = new ArrayList<>();
        addresses.add(linkAddress(InetAddress.getByName(TUN_ADDRESS), TUN_PREFIX));
        addresses.add(linkAddress(InetAddress.getByName(TUN_ADDRESS_V6), TUN_PREFIX_V6));

        tunInterface = createTunInterface(addresses);
        tunFd = (ParcelFileDescriptor) tunInterface.getClass().getMethod("getFileDescriptor").invoke(tunInterface);
        interfaceName = (String) tunInterface.getClass().getMethod("getInterfaceName").invoke(tunInterface);

        setupTestNetwork(interfaceName);
        testNetwork = awaitNetwork(interfaceName, AVAILABILITY_TIMEOUT_MS);
        requestKeepAlive();
    }

    private void watchClient(IBinder lifetime) {
        if (lifetime == null) return;
        clientLifetime = lifetime;
        clientDeath = () -> {
            note("the app went away, ending the session");
            stop();
        };
        try {
            lifetime.linkToDeath(clientDeath, 0);
        } catch (Throwable t) {
            clientDeath = null;
            clientLifetime = null;
            note("could not watch the app process: " + rootMessage(t));
        }
    }

    /**
     * Last line of defence when this process exits with a session up (Shizuku stopped, killed
     * by the user...): drop the downstream so clients are not left on a dead upstream.
     */
    private void installShutdownHook() {
        if (shutdownHook != null) return;
        Thread hook = new Thread(() -> {
            try {
                stopTethering(snapshotStarted());
            } catch (Throwable ignored) {
            }
        }, "mv-hotspot-exit");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHook = hook;
        } catch (Throwable t) {
            note("shutdown hook not installed: " + rootMessage(t));
        }
    }

    private void removeShutdownHook() {
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- teardown

    /** Releases everything this session created. Returns a problem description or null. Holds {@link #lock}. */
    private String teardownLocked() {
        stopWatchdog();
        unwatchClient();
        removeShutdownHook();

        String problem = null;
        Set<Integer> types = snapshotStarted();
        if (!types.isEmpty()) {
            problem = releaseDownstream(types);
        }
        started.clear();

        if (preferSet) {
            releaseUpstreamSelection(interfaceName);
            preferSet = false;
        }

        if (keepAlive != null) {
            try {
                connectivity().unregisterNetworkCallback(keepAlive);
            } catch (Throwable t) {
                note("unregisterNetworkCallback: " + rootMessage(t));
            }
            keepAlive = null;
        }
        if (testNetwork != null) {
            try {
                teardownTestNetwork(testNetwork);
            } catch (Throwable t) {
                note("teardownTestNetwork: " + rootMessage(t));
            }
            testNetwork = null;
        }
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ignored) {
            }
            tunFd = null;
        }
        tunInterface = null;
        interfaceName = null;
        activeSince = 0;
        clientCount = 0;

        note(problem == null ? "session stopped; downstream confirmed down" : "teardown: " + problem);
        return problem;
    }

    private void unwatchClient() {
        IBinder b = clientLifetime;
        IBinder.DeathRecipient d = clientDeath;
        clientLifetime = null;
        clientDeath = null;
        if (b != null && d != null) {
            try {
                b.unlinkToDeath(d, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    /** A failed start or a failed session: undo what was done and report why. Holds {@link #lock}. */
    private void fail(String where, Throwable t) {
        String why = where + ": " + rootMessage(t);
        Log.e(TAG, why, t);
        note("FAILED " + why);
        String teardown = teardownLocked();
        state = State.ERROR;
        detail = teardown == null ? why : why + "; teardown incomplete: " + teardown;
    }

    private Set<Integer> snapshotStarted() {
        synchronized (started) {
            return new LinkedHashSet<>(started);
        }
    }

    // ------------------------------------------------------------- watchdog

    private void startWatchdog() {
        stopWatchdog();
        final String expected = interfaceName;
        Thread t = new Thread(() -> watchdogLoop(expected), "mv-hotspot-watchdog");
        t.setDaemon(true);
        watchdog = t;
        t.start();
    }

    private void stopWatchdog() {
        Thread t = watchdog;
        watchdog = null;
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    private void watchdogLoop(String expected) {
        int strikes = 0;
        String lastKind = "";
        int round = 0;
        while (watchdog == Thread.currentThread()) {
            try {
                Thread.sleep(WATCHDOG_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (watchdog != Thread.currentThread()) return;

            Observation obs = observe();
            List<String> live = obs.liveNames(expected);
            String kind;
            if (obs.timedOut) kind = "timeout";
            else if (live.isEmpty()) kind = "absent";
            else if (allEqual(live, expected)) kind = "ok";
            else kind = "drift";

            if ("ok".equals(kind)) {
                strikes = 0;
            } else {
                strikes = kind.equals(lastKind) ? strikes + 1 : 1;
                int allowed = "absent".equals(kind) ? ABSENT_STRIKES : DRIFT_STRIKES;
                if (strikes >= allowed) {
                    String problem = "drift".equals(kind)
                            ? "upstream drifted from " + expected + " to " + live
                            : "upstream check " + kind + " " + strikes + " times, expected " + expected;
                    synchronized (lock) {
                        if (watchdog != Thread.currentThread()) return;
                        watchdog = null;
                        fail("watchdog", new IllegalStateException(problem));
                    }
                    return;
                }
                note("watchdog strike " + strikes + "/" + allowed + ": " + kind + " " + live);
            }
            lastKind = kind;

            if ((++round & 1) == 0) {                   // clients are a nicety: every other round
                refreshClients(obs);
            }
        }
    }

    private static boolean allEqual(List<String> names, String expected) {
        for (String n : names) if (!n.equals(expected)) return false;
        return true;
    }

    // ------------------------------------------------------------- status

    private String statusLocked() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state.name());
            o.put("detail", detail);
            o.put("interface", interfaceName == null ? JSONObject.NULL : interfaceName);
            long[] traffic = interfaceName == null ? new long[]{0, 0} : readCounters(interfaceName);
            o.put("bytesUp", traffic[0]);
            o.put("bytesDown", traffic[1]);
            o.put("clientCount", state == State.ACTIVE ? clientCount : 0);
            o.put("activeSince", activeSince);
            o.put("wifi", wantWifi);
            o.put("usb", wantUsb);
            JSONArray lines = new JSONArray();
            for (String l : log) lines.put(l);
            o.put("log", lines);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    private void note(String line) {
        Log.i(TAG, line);
        synchronized (log) {
            log.addLast(line);
            while (log.size() > LOG_LINES) log.removeFirst();
        }
    }

    /** {up, down} in bytes from the clients' point of view (kernel tx = towards xray = upload). */
    private static long[] readCounters(String iface) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File("/proc/net/dev")))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (!s.startsWith(iface + ":")) continue;
                String[] f = s.substring(iface.length() + 1).trim().split("\\s+");
                if (f.length > 8) return new long[]{Long.parseLong(f[8]), Long.parseLong(f[0])};
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return new long[]{0, 0};
    }

    // ----------------------------------------------------- test network (hidden API)

    private Context shell() {
        if (shellContext == null) shellContext = asShellContext(baseContext);
        return shellContext;
    }

    private ConnectivityManager connectivity() {
        return (ConnectivityManager) shell().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @SuppressLint("WrongConstant")
    private Object testNetworkManager() {
        return shell().getSystemService("test_network");
    }

    /** The shell-attributed Context is the one thing every other check depends on. */
    private boolean contextUsable(JSONObject into) {
        try {
            shell();
            return true;
        } catch (Throwable t) {
            try {
                into.put("reason", rootMessage(t));
            } catch (JSONException ignored) {
            }
            return false;
        }
    }

    private boolean testNetworkAvailable() {
        try {
            Class.forName("android.net.TestNetworkManager");
            return testNetworkManager() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean preferTestNetworksAvailable() {
        try {
            Class.forName("android.net.TetheringManager").getMethod("setPreferTestNetworks", boolean.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static LinkAddress linkAddress(InetAddress address, int prefix) throws Exception {
        // The (InetAddress, int) constructor is package-private.
        Constructor<LinkAddress> c = LinkAddress.class.getDeclaredConstructor(InetAddress.class, int.class);
        c.setAccessible(true);
        return c.newInstance(address, prefix);
    }

    private Object createTunInterface(List<LinkAddress> addresses) throws Exception {
        Object manager = testNetworkManager();
        if (manager == null) throw new IllegalStateException("the test_network service is unavailable");
        Class<?> owner = Class.forName("android.net.TestNetworkManager");

        // Two overloads exist across releases (Collection, LinkAddress[]); both get both families.
        try {
            Method m = owner.getMethod("createTunInterface", Collection.class);
            Object r = m.invoke(manager, addresses);
            if (r != null) return r;
        } catch (NoSuchMethodException ignored) {
        } catch (InvocationTargetException e) {
            note("createTunInterface(Collection): " + rootMessage(e));
        }
        Method m = owner.getMethod("createTunInterface", Class.forName("[Landroid.net.LinkAddress;"));
        Object arr = Array.newInstance(LinkAddress.class, addresses.size());
        for (int i = 0; i < addresses.size(); i++) Array.set(arr, i, addresses.get(i));
        Object r = m.invoke(manager, arr);
        if (r == null) throw new IllegalStateException("createTunInterface returned null");
        return r;
    }

    private void setupTestNetwork(String iface) throws Exception {
        Object manager = testNetworkManager();
        if (manager == null) throw new IllegalStateException("the test_network service is unavailable");
        Class<?> owner = Class.forName("android.net.TestNetworkManager");

        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(iface);
        Method addDns = LinkProperties.class.getMethod("addDnsServer", InetAddress.class);
        for (String dns : TUN_DNS) addDns.invoke(lp, InetAddress.getByName(dns));

        // (LinkProperties, boolean isMetered, IBinder): needed so the network carries IPv6 DNS
        // servers, without which tethering does not provision IPv6 downstream.
        try {
            Method m = owner.getMethod("setupTestNetwork", LinkProperties.class, boolean.class, IBinder.class);
            m.invoke(manager, lp, true, testNetworkLifetime);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (InvocationTargetException e) {
            note("setupTestNetwork(LinkProperties): " + rootMessage(e));
        }
        Method m = owner.getMethod("setupTestNetwork", String.class, IBinder.class);
        m.invoke(manager, iface, testNetworkLifetime);
    }

    private void teardownTestNetwork(Network network) throws Exception {
        Object manager = testNetworkManager();
        if (manager == null) return;
        Class.forName("android.net.TestNetworkManager")
                .getMethod("teardownTestNetwork", Network.class).invoke(manager, network);
    }

    private Network awaitNetwork(String iface, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ConnectivityManager cm = connectivity();
        while (System.currentTimeMillis() < deadline) {
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null && iface.equals(lp.getInterfaceName())) return n;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("test network '" + iface + "' did not become available within " + timeoutMs + " ms");
    }

    /** Without an outstanding request the test network is lingered away within seconds. */
    private void requestKeepAlive() {
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(transportTest())
                .build();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
        };
        try {
            connectivity().requestNetwork(request, cb);
            keepAlive = cb;
        } catch (RuntimeException e) {
            throw new IllegalStateException("could not hold the test network, it would be lingered away: " + e.getMessage(), e);
        }
    }

    private static int transportTest() {
        try {
            return NetworkCapabilities.class.getField("TRANSPORT_TEST").getInt(null);
        } catch (Throwable t) {
            return 7;                           // AOSP value
        }
    }

    // ------------------------------------------------------ tethering (hidden API)

    private Object tetheringManager() {
        @SuppressLint("WrongConstant") Object m = shell().getSystemService("tethering");
        if (m == null) throw new IllegalStateException("the tethering service is unavailable");
        return m;
    }

    private void setPreferTestNetworks(boolean prefer) throws Exception {
        Method m = Class.forName("android.net.TetheringManager").getMethod("setPreferTestNetworks", boolean.class);
        m.invoke(tetheringManager(), prefer);
    }

    private void restartDownstream() throws Exception {
        List<Integer> types = new ArrayList<>();
        if (wantWifi) types.add(TETHERING_WIFI);
        if (wantUsb) types.add(TETHERING_USB);

        // Stopping first makes the tethering stack re-run upstream selection. USB is only stopped
        // when it is up: stopping it with nothing running can reset the USB function.
        List<Integer> toStop = new ArrayList<>();
        Set<Integer> up = tetheredTypes();
        for (int t : types) {
            if (t != TETHERING_USB || up.contains(TETHERING_USB)) toStop.add(t);
        }
        stopTethering(toStop);

        List<String> failures = new ArrayList<>();
        for (int t : types) {
            String[] r = startTethering(t);
            if ("ok".equals(r[0])) {
                synchronized (started) {
                    started.add(t);
                }
                note(typeLabel(t) + " started");
            } else {
                failures.add(typeLabel(t) + " did not start (" + r[1] + ")");
            }
        }
        if (snapshotStarted().isEmpty()) {
            throw new IllegalStateException(join(failures, "; "));
        }
        for (String f : failures) note(f);

        long deadline = System.currentTimeMillis() + DOWNSTREAM_SETTLE_MS;
        Set<Integer> want = snapshotStarted();
        while (System.currentTimeMillis() < deadline) {
            Set<Integer> now = tetheredTypes();
            for (int t : want) {
                if (now.contains(t)) {
                    note("downstream tethered");
                    return;
                }
            }
            Thread.sleep(500);
        }
        String hint = want.contains(TETHERING_USB) && !want.contains(TETHERING_WIFI)
                ? " (is a USB cable connected to a computer?)" : "";
        throw new IllegalStateException("no downstream became tethered within " + DOWNSTREAM_SETTLE_MS + " ms" + hint);
    }

    private static String typeLabel(int type) {
        return type == TETHERING_USB ? "USB tethering" : "hotspot";
    }

    private void stopTethering(Collection<Integer> types) {
        if (types.isEmpty()) return;
        try {
            Method m = Class.forName("android.net.TetheringManager").getMethod("stopTethering", int.class);
            Object mgr = tetheringManager();
            for (int type : types) {
                try {
                    m.invoke(mgr, type);
                } catch (Throwable t) {
                    note("stopTethering(" + type + "): " + rootMessage(t));
                }
            }
        } catch (Throwable t) {
            note("stopTethering unavailable: " + rootMessage(t));
        }
        sleep(STOP_SETTLE_MS);
    }

    /** {"ok"|"fail", detail}. */
    private String[] startTethering(int type) {
        String[] viaTethering = startViaTetheringManager(type);
        if ("ok".equals(viaTethering[0]) || type != TETHERING_WIFI) return viaTethering;
        String[] viaWifi = startViaWifiManager();
        if ("ok".equals(viaWifi[0])) return viaWifi;
        return new String[]{"fail", "tethering[" + viaTethering[1] + "]; wifiManager[" + viaWifi[1] + "]"};
    }

    private String[] startViaTetheringManager(int type) {
        String[] exempt = startTetheringRequest(type, true);
        if ("ok".equals(exempt[0])) return exempt;
        String[] plain = startTetheringRequest(type, false);
        if ("ok".equals(plain[0])) return plain;
        return new String[]{"fail", "exempt[" + exempt[1] + "]; plain[" + plain[1] + "]"};
    }

    private String[] startTetheringRequest(int type, boolean exemptFromEntitlement) {
        final Object request;
        try {
            request = buildTetheringRequest(type, exemptFromEntitlement);
        } catch (Throwable t) {
            return new String[]{"fail", "buildTetheringRequest: " + rootMessage(t)};
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {"no callback"};
        try {
            Class<?> callbackClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
            Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        String n = method.getName();
                        if ("onTetheringStarted".equals(n)) {
                            result[0] = "ok";
                            latch.countDown();
                        } else if ("onTetheringFailed".equals(n)) {
                            Object code = args != null && args.length > 0 ? args[0] : null;
                            result[0] = "onTetheringFailed(error=" + code + ")";
                            latch.countDown();
                        } else if ("hashCode".equals(n)) {
                            return System.identityHashCode(proxy);
                        } else if ("equals".equals(n)) {
                            return args != null && args.length == 1 && proxy == args[0];
                        } else if ("toString".equals(n)) {
                            return "StartTetheringCallback";
                        }
                        return null;
                    });
            Class<?> requestClass = Class.forName("android.net.TetheringManager$TetheringRequest");
            Method start = Class.forName("android.net.TetheringManager")
                    .getMethod("startTethering", requestClass, Executor.class, callbackClass);
            final Handler main = new Handler(Looper.getMainLooper());
            start.invoke(tetheringManager(), request, (Executor) main::post, callback);
            boolean done = latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new String[]{done && "ok".equals(result[0]) ? "ok" : "fail", done ? result[0] : "timed out"};
        } catch (Throwable t) {
            return new String[]{"fail", "startTethering: " + rootMessage(t)};
        }
    }

    private static Object buildTetheringRequest(int type, boolean exemptFromEntitlement) throws Exception {
        Class<?> builderClass = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
        Object builder = builderClass.getConstructor(int.class).newInstance(type);
        if (exemptFromEntitlement) {
            try {
                builderClass.getDeclaredMethod("setExemptFromEntitlementCheck", boolean.class).invoke(builder, true);
            } catch (Throwable ignored) {
            }
        }
        return builderClass.getMethod("build").invoke(builder);
    }

    /** WifiManager.startTetheredHotspot(null): the saved hotspot configuration. */
    private String[] startViaWifiManager() {
        try {
            Object wifi = shell().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return new String[]{"fail", "wifi service unavailable"};
            Class<?> cfg = Class.forName("android.net.wifi.SoftApConfiguration");
            Method m = wifi.getClass().getMethod("startTetheredHotspot", cfg);
            Object accepted = m.invoke(wifi, new Object[]{null});
            return Boolean.TRUE.equals(accepted)
                    ? new String[]{"ok", "startTetheredHotspot accepted"}
                    : new String[]{"fail", "startTetheredHotspot returned false"};
        } catch (Throwable t) {
            return new String[]{"fail", "startTetheredHotspot: " + rootMessage(t)};
        }
    }

    /** Stops the given downstreams, retrying while one stays tethered (the stop lands asynchronously). */
    private String releaseDownstream(Set<Integer> types) {
        boolean accepted = false;
        for (int attempt = 1; attempt <= DOWNSTREAM_STOP_ATTEMPTS; attempt++) {
            stopTethering(types);
            accepted = true;
            Set<Integer> still = tetheredTypes();
            boolean any = false;
            for (int t : types) any |= still.contains(t);
            if (!any) return null;
            if (attempt == DOWNSTREAM_STOP_ATTEMPTS) return "downstream still tethered after " + attempt + " attempts";
            note("downstream still tethered; retrying stopTethering");
        }
        return accepted ? null : "downstream did not release";
    }

    private void releaseUpstreamSelection(String iface) {
        try {
            setPreferTestNetworks(false);
        } catch (Throwable t) {
            note("could not clear the test-network preference: " + rootMessage(t)
                    + "; the upstream stays selected and the next start may fail");
            return;
        }
        if (iface == null) return;
        long deadline = System.currentTimeMillis() + UPSTREAM_RELEASE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!observe().names.contains(iface)) {
                note("upstream released: tethering moved off " + iface);
                return;
            }
            sleep(500);
        }
        note("upstream still reads " + iface + " after " + UPSTREAM_RELEASE_MS + " ms; releasing anyway");
    }

    /** Tethering must report the TUN, and only the TUN, as its upstream before the session counts as up. */
    private void verifyUpstream(String iface) throws InterruptedException {
        long deadline = System.currentTimeMillis() + UPSTREAM_SETTLE_MS;
        List<String> live = observe().liveNames(iface);
        while (System.currentTimeMillis() < deadline) {
            if (!live.isEmpty() && allEqual(live, iface)) return;
            Thread.sleep(500);
            live = observe().liveNames(iface);
        }
        throw new IllegalStateException("expected only " + iface + " as the tethering upstream, tethering reports " + live
                + " (clients would bypass the proxy, so the session is not started)");
    }

    // ------------------------------------------------------------ dumpsys

    private static final String[] UPSTREAM_HINTS = {
            "current upstream interface(s):", "current upstream:", "selected upstream:",
            "upstream network:", "mCurrentUpstream"};
    private static final Pattern INTERFACE_PATTERN = Pattern.compile(
            "\\b(testtun\\d+|wlan\\d+|rmnet[a-z_]*\\d*|eth\\d+|ap\\d+|swlan\\d+|ccmni\\d+|seth_\\w+|wwan\\d+|usb\\d+|rndis\\d+|ncm\\d+)\\b");
    private static final Pattern TETHERED_STATE = Pattern.compile("^\\s*(\\w+)\\s+-\\s+TetheredState\\s+-");
    private static final Pattern USB_IFACE = Pattern.compile("^(rndis|ncm|usb)\\d*$");
    private static final Pattern UNSHARED_IFACE = Pattern.compile("^(bt-pan|eth\\d+)$");
    private static final Pattern CLIENTS = Pattern.compile("getConnectedClientList\\(\\)\\.size\\(\\):\\s*(\\d+)");

    private static final class Observation {
        final String raw;
        final List<String> names;
        final boolean timedOut;

        Observation(String raw, List<String> names, boolean timedOut) {
            this.raw = raw;
            this.names = names;
            this.timedOut = timedOut;
        }

        /** Upstream names that still exist; the TUN is trusted even before /sys shows it. */
        List<String> liveNames(String owned) {
            List<String> live = new ArrayList<>();
            for (String n : names) {
                if (n.equals(owned) || new File("/sys/class/net/" + n).exists()) live.add(n);
            }
            return live;
        }
    }

    private Observation observe() {
        String[] r = dumpsys("tethering", 3_000);
        boolean timedOut = r[1] != null;
        String raw = r[0];
        List<String> names = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            boolean hint = false;
            for (String h : UPSTREAM_HINTS) {
                if (t.regionMatches(true, 0, h, 0, h.length())) {
                    hint = true;
                    break;
                }
            }
            if (!hint) continue;
            Matcher m = INTERFACE_PATTERN.matcher(t);
            while (m.find()) {
                if (!names.contains(m.group(1))) names.add(m.group(1));
            }
        }
        return new Observation(raw, names, timedOut);
    }

    private Set<Integer> tetheredTypes() {
        Set<Integer> out = new LinkedHashSet<>();
        Observation o = observe();
        if (o.timedOut) return out;
        for (String line : o.raw.split("\n")) {
            Matcher m = TETHERED_STATE.matcher(line);
            if (!m.find()) continue;
            String name = m.group(1);
            if (USB_IFACE.matcher(name).matches()) out.add(TETHERING_USB);
            else if (!UNSHARED_IFACE.matcher(name).matches()) out.add(TETHERING_WIFI);   // any other name is the hotspot
        }
        return out;
    }

    private void refreshClients(Observation tethering) {
        long now = System.currentTimeMillis();
        if (now - clientCountAt < 10_000) return;
        int wifi = 0;
        if (wantWifi) {
            String[] w = dumpsys("wifi", 3_000);
            if (w[1] != null) return;
            Matcher m = CLIENTS.matcher(w[0]);
            while (m.find()) wifi = Math.max(wifi, Integer.parseInt(m.group(1)));
        }
        int usb = 0;
        if (wantUsb && !tethering.timedOut) {
            for (String line : tethering.raw.split("\n")) {
                Matcher m = TETHERED_STATE.matcher(line);
                if (m.find() && USB_IFACE.matcher(m.group(1)).matches()) usb++;
            }
        }
        clientCountAt = now;
        int total = wifi + usb;
        if (total != clientCount) note("clients: " + total + " now sharing");
        clientCount = total;
    }

    /** {stdout, timeoutMarker-or-null}. */
    private static String[] dumpsys(String service, long deadlineMs) {
        final StringBuilder out = new StringBuilder();
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", service).redirectErrorStream(true).start();
            final InputStream in = p.getInputStream();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "mv-dumpsys");
            reader.setDaemon(true);
            reader.start();
            boolean exited = p.waitFor(deadlineMs, TimeUnit.MILLISECONDS);
            if (!exited) p.destroyForcibly();
            reader.join(500);
            synchronized (out) {
                return new String[]{out.toString(), exited ? null : "timeout"};
            }
        } catch (IOException e) {
            return new String[]{"", "io: " + e.getMessage()};
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return new String[]{"", "interrupted"};
        }
    }

    // ------------------------------------------------------------ helpers

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while ((c instanceof InvocationTargetException) && c.getCause() != null) c = c.getCause();
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    // ------------------------------------------------- shell-identity Context

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Context acquireSystemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object systemMain = at.getMethod("systemMain").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(systemMain);
        } catch (Exception e) {
            throw new IllegalStateException("cannot acquire the system context: " + rootMessage(e), e);
        }
    }

    /**
     * The services check the caller's package against its UID, and the system context's package
     * is "android": re-attribute a context to com.android.shell so those checks line up with the
     * shell UID this process really runs as.
     */
    private static Context asShellContext(Context context) {
        try {
            Context rebased = context.createPackageContext(SHELL_PACKAGE, 0);
            if (Build.VERSION.SDK_INT >= 31) {
                java.lang.reflect.Field f = rebased.getClass().getDeclaredField("mAttributionSource");
                f.setAccessible(true);
                Object current = f.get(rebased);
                if (current == null) throw new IllegalStateException("mAttributionSource was null");
                f.set(rebased, current.getClass().getMethod("withPackageName", String.class).invoke(current, SHELL_PACKAGE));
            } else {
                java.lang.reflect.Field f = rebased.getClass().getDeclaredField("mOpPackageName");
                f.setAccessible(true);
                f.set(rebased, SHELL_PACKAGE);
            }
            return rebased;
        } catch (Exception e) {
            throw new IllegalStateException("cannot attribute the context to " + SHELL_PACKAGE + ": " + rootMessage(e), e);
        }
    }
}
