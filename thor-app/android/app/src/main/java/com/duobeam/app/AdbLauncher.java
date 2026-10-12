package com.duobeam.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import io.github.muntashirakon.adb.AdbStream;

/**
 * Obtains shell-uid access by connecting to wireless debugging on the same device and launching the sender
 * via app_process. After the first pairing the app grants itself WRITE_SECURE_SETTINGS so it can re-enable
 * wireless debugging on its own (e.g. after a reboot).
 */
final class AdbLauncher {

    static final String PAIRING_SERVICE = "_adb-tls-pairing._tcp";
    static final String TAG = "DuoBeam";
    private static final String PREFS = "adb";

    private AdbLauncher() {
    }

    static boolean isPaired(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("paired", false);
    }

    static void setPaired(Context c, boolean v) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paired", v).apply();
    }

    static boolean canToggleWirelessDebugging(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean devOptionsOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "development_settings_enabled", 0) == 1;
    }

    static boolean wirelessDebuggingOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
    }

    /** Turns on wireless debugging; no-op without WRITE_SECURE_SETTINGS. */
    static void enableWirelessDebugging(Context c) {
        if (!canToggleWirelessDebugging(c) || wirelessDebuggingOn(c)) return;
        Settings.Global.putInt(c.getContentResolver(), "development_settings_enabled", 1);
        Settings.Global.putInt(c.getContentResolver(), "adb_enabled", 1);
        Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
        try {
            Thread.sleep(2500); // give adbd time to start its TLS service
        } catch (InterruptedException ignored) {
        }
    }

    static void pair(Context c, int port, String code) throws Exception {
        Log.i(TAG, "adb: pairing on port " + port);
        if (!AdbManager.get(c).pair("127.0.0.1", port, code.trim())) throw new IOException("pairing rejected");
        Log.i(TAG, "adb: paired");
        setPaired(c, true);
    }

    static synchronized AdbManager connect(Context c) throws Exception {
        AdbManager m = AdbManager.get(c);
        if (m.isConnected()) return m;
        enableWirelessDebugging(c);
        if (!wirelessDebuggingOn(c)) throw new IOException("wireless debugging is off");
        // Own port lookup: the library's connectTls does not find the service on the same device.
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // 1) System property (Android 11+, readable by apps); more reliable than mDNS, which can be flaky.
        for (int i = 0; i < 6; i++) {
            int prop = readTlsPortProperty();
            if (prop > 0) {
                Log.i(TAG, "adb: port from service.adb.tls.port = " + prop);
                if (tryConnect(c, m, prop)) {
                    p.edit().putInt("connect_port", prop).apply();
                    return m;
                }
                break;
            }
            try {
                Thread.sleep(500); // freshly enabled debugging publishes the port after a short delay
            } catch (InterruptedException ignored) {
            }
        }
        // 2) last working port, 3) mDNS
        int cached = p.getInt("connect_port", 0);
        if (cached > 0 && tryConnect(c, m, cached)) return m;
        int port = findConnectPort(c, 8000);
        Log.i(TAG, "adb: connect port from mDNS = " + port);
        if (port > 0 && tryConnect(c, m, port)) {
            p.edit().putInt("connect_port", port).apply();
            return m;
        }
        throw new IOException(port > 0 ? "could not connect to wireless debugging (port " + port + ")" : "wireless debugging port not found");
    }

    static int readTlsPortProperty() {
        try {
            Process pr = Runtime.getRuntime().exec(new String[] {"getprop", "service.adb.tls.port"});
            try (InputStream in = pr.getInputStream()) {
                byte[] b = new byte[32];
                int n = in.read(b);
                String s = n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
                return s.isEmpty() ? 0 : Integer.parseInt(s);
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean tryConnect(Context c, AdbManager m, int port) throws IOException {
        try {
            boolean ok = m.connect("127.0.0.1", port);
            Log.i(TAG, "adb: connect 127.0.0.1:" + port + " = " + ok);
            return ok;
        } catch (io.github.muntashirakon.adb.AdbPairingRequiredException e) {
            // adbd no longer knows our key; pairing must be redone
            Log.w(TAG, "adb: pairing lost");
            setPaired(c, false);
            throw new IOException("pairing lost - tap Set up and pair again");
        } catch (Exception e) {
            Log.w(TAG, "adb: connect 127.0.0.1:" + port + " failed: " + e);
            return false;
        }
    }

    /** Wireless debugging port of this device: _adb-tls-connect via mDNS, filtered by our own IP addresses. */
    /** IP addresses of this device, as NsdServiceInfo.getHost() prints them. */
    private static java.util.Set<String> ownAddresses() {
        java.util.Set<String> mine = new java.util.HashSet<>();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) mine.add(a.getHostAddress());
        } catch (Exception ignored) {
        }
        return mine;
    }

    static int findConnectPort(Context c, long timeoutMs) {
        java.util.Set<String> mine = ownAddresses();
        NsdManager nsd = c.getSystemService(NsdManager.class);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger found = new java.util.concurrent.atomic.AtomicInteger(0);
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo i) { }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { }

                    @Override
                    public void onServiceResolved(NsdServiceInfo i) {
                        String host = i.getHost() != null ? i.getHost().getHostAddress() : "";
                        Log.i(TAG, "adb: mDNS " + i.getServiceName() + " " + host + ":" + i.getPort());
                        if (mine.contains(host) && found.compareAndSet(0, i.getPort())) done.countDown();
                    }
                });
            }
        };
        nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, l);
        try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        stopDiscovery(c, l);
        return found.get();
    }

    /** Drops the ADB connection; the next command reconnects. */
    static synchronized void resetConnection(Context c) {
        try {
            AdbManager.get(c).disconnect();
        } catch (Exception ignored) {
        }
    }

    /** Runs a shell command as the shell uid and returns its output. */
    static String exec(Context c, String cmd) throws Exception {
        Log.i(TAG, "adb shell: " + cmd.replaceAll("token=\\S+", "token=***"));
        AdbStream s = connect(c).openStream("shell:" + cmd);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = s.openInputStream()) {
            byte[] b = new byte[4096];
            long deadline = System.currentTimeMillis() + 15_000;
            int n;
            while (System.currentTimeMillis() < deadline && (n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (IOException ignored) {
            // stream is closed when the command exits
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        String r = out.toString("UTF-8");
        Log.i(TAG, "adb shell -> " + r.trim());
        return r;
    }

    /** Grants WRITE_SECURE_SETTINGS via the shell, once after pairing, so wireless debugging can be toggled. */
    static void selfGrant(Context c) throws Exception {
        if (canToggleWirelessDebugging(c)) return;
        String out = exec(c, "pm grant " + c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS && echo GRANT_OK");
        if (!canToggleWirelessDebugging(c)) {
            throw new IOException("pm grant failed: " + (out.trim().isEmpty() ? "no shell output" : out.trim()));
        }
    }

    /** Starts the sender daemon as a separate shell-uid process that outlives the ADB connection. */
    static void launchDaemon(Context c, int port, String token) throws Exception {
        String apk = c.getApplicationInfo().sourceDir;
        // nohup + setsid detach the process from the shell. "sleep 2" keeps the shell alive until the sender
        // has detached; otherwise closing the ADB stream kills the shell first.
        exec(c, "CLASSPATH=" + apk + " nohup setsid app_process / com.duobeam.Sender daemon port=" + port + " token=" + token
                + " </dev/null >/data/local/tmp/duobeam-daemon.log 2>&1 & sleep 2");
    }

    interface PortCallback {
        void onPort(int port);
    }

    /**
     * Discovers the pairing port, which the "Pair with pairing code" dialog advertises via mDNS. Each opening of the
     * dialog gets a new port; cb gets 0 when the dialog closes. Only this device's own service counts: other phones
     * on the network can have their pairing dialog open too.
     */
    static NsdManager.DiscoveryListener discoverPairingPort(Context c, PortCallback cb) {
        NsdManager nsd = c.getSystemService(NsdManager.class);
        java.util.Set<String> mine = ownAddresses();
        java.util.concurrent.atomic.AtomicReference<String> current = new java.util.concurrent.atomic.AtomicReference<>();
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }

            @Override
            public void onServiceLost(NsdServiceInfo i) {
                if (i.getServiceName() != null && i.getServiceName().equals(current.get())) {
                    current.set(null);
                    cb.onPort(0);
                }
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { }

                    @Override
                    public void onServiceResolved(NsdServiceInfo i) {
                        String host = i.getHost() != null ? i.getHost().getHostAddress() : "";
                        Log.i(TAG, "adb: pairing mDNS " + i.getServiceName() + " " + host + ":" + i.getPort());
                        if (!mine.isEmpty() && !mine.contains(host)) return;
                        current.set(i.getServiceName());
                        cb.onPort(i.getPort());
                    }
                });
            }
        };
        nsd.discoverServices(PAIRING_SERVICE, NsdManager.PROTOCOL_DNS_SD, l);
        return l;
    }

    static void stopDiscovery(Context c, NsdManager.DiscoveryListener l) {
        try {
            c.getSystemService(NsdManager.class).stopServiceDiscovery(l);
        } catch (Exception ignored) {
        }
    }
}
