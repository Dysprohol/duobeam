package com.duobeam.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.duobeam.Sender;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-wide controller for the sender, shared by the panel, notification, overlay and tile.
 * The sender is a daemon running with shell privileges, controlled over 127.0.0.1:PORT with a token.
 * It is launched through the built-in wireless debugging connection (after one-time pairing).
 */
final class StreamClient {

    interface Listener {
        void onChanged();
    }

    static final int PORT = 27500;
    private static StreamClient instance;

    private final Context app;
    private final Settings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // separate heartbeat executor: while io is busy (start takes a few seconds) the sender still sees the app as alive
    private final ExecutorService heartbeat = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile String status = "-";
    private volatile String daemonVersion;
    private volatile String backendError;
    private volatile boolean busy;
    private volatile String grantError;
    private volatile boolean granting;
    private volatile boolean foreignDaemon; // a sender is running that rejects our token (e.g. after app data was cleared)
    private volatile int tvState; // 0 unknown, 1 TV receiving from this device, 2 TV not receiving / unreachable
    private volatile long tvCheckedAt;
    private volatile int screensNow; // current mask of blanked panels (touch wake can change it)
    // status signature: notify only on visible changes; the frame counter changes every second and each
    // notification rebuilds the side bar panel, which would drop taps
    private volatile String statusSig = "";
    private final java.util.concurrent.atomic.AtomicBoolean startQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile int gen; // bumped by stop() so a queued start doesn't revive the service after Stop
    private volatile long lastStopAt;

    static synchronized StreamClient get(Context c) {
        if (instance == null) instance = new StreamClient(c.getApplicationContext());
        return instance;
    }

    private StreamClient(Context app) {
        this.app = app;
        this.settings = new Settings(app);
        L.load(app);
        // an app selected as the debug app is killed whenever Developer options are opened, so clear it
        io.execute(() -> {
            String dbg = android.provider.Settings.Global.getString(app.getContentResolver(), "debug_app");
            if (app.getPackageName().equals(dbg) && AdbLauncher.isPaired(app)) {
                try {
                    AdbLauncher.exec(app, "am clear-debug-app; settings delete global debug_app");
                } catch (Exception e) {
                    android.util.Log.w(AdbLauncher.TAG, "clear debug app: " + e);
                }
            }
        });
        // paired but missing the permission to toggle wireless debugging (e.g. interrupted grant): retry in background
        if (AdbLauncher.isPaired(app) && !AdbLauncher.canToggleWirelessDebugging(app)) grantAsync();
        // poll daemon state every second
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!busy) io.execute(StreamClient.this::poll);
                else heartbeat.execute(() -> cmd("PING", 800));
                main.postDelayed(this, 1000);
            }
        });
    }

    Settings settings() {
        return settings;
    }

    void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) l.onChanged();
        });
    }

    void refreshBackend() {
        notifyChanged();
    }

    /** Grants the app WRITE_SECURE_SETTINGS over the built-in connection (one-time). */
    void grantAsync() {
        granting = true;
        notifyChanged();
        io.execute(() -> {
            try {
                AdbLauncher.selfGrant(app);
                grantError = null;
            } catch (Exception e) {
                grantError = e.getMessage();
                android.util.Log.e(AdbLauncher.TAG, "selfGrant", e);
            }
            granting = false;
            notifyChanged();
        });
    }

    String grantError() {
        return grantError;
    }

    boolean granting() {
        return granting;
    }

    /** Localized status text; the sender reports status codes (STOPPED, STREAMING, ...). */
    String humanStatus() {
        String s = status;
        if (s.equals("-") && foreignDaemon) return L.s("st_foreign");
        if (s.equals("-")) return AdbLauncher.isPaired(app) ? L.s("st_off") : L.s("st_need_setup");
        if (s.startsWith("STOPPED")) return L.s("st_off");
        if (s.startsWith("STARTING")) return L.s("st_starting");
        if (s.startsWith("CONNECTED")) return L.s("st_connected");
        if (s.startsWith("STREAMING")) {
            String[] p = s.split(" ");
            String fps = "?";
            try {
                fps = String.valueOf(Math.round(Double.parseDouble(p[3])));
            } catch (Exception ignored) {
            }
            return L.f("st_streaming", settings.layoutName(), fps);
        }
        if (s.startsWith("NO_TV")) {
            String[] p = s.split(" ", 3);
            return L.f("st_no_tv", p.length > 1 ? p[1] : "?");
        }
        if (s.startsWith("ERROR") && s.contains("no frame size the encoder accepts")) return L.s("st_enc_size");
        if (s.startsWith("ERROR")) return L.f("st_error", s.length() > 6 ? s.substring(6) : "?");
        return s;
    }

    /** Whether setup is complete: paired and able to toggle wireless debugging. */
    boolean setupDone() {
        return AdbLauncher.isPaired(app) && AdbLauncher.canToggleWirelessDebugging(app);
    }

    // ---- daemon ----

    private String cmd(String c) {
        return cmd(c, 5000);
    }

    private String cmd(String c, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
            s.setSoTimeout(timeoutMs);
            Writer w = new OutputStreamWriter(s.getOutputStream(), "UTF-8");
            w.write(settings.token() + " " + c + "\n");
            w.flush();
            String r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8")).readLine();
            if (r != null && r.startsWith("ERR auth")) {
                foreignDaemon = true;
                return null;
            }
            if (r != null) foreignDaemon = false;
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private void poll() {
        String pong = cmd("PING");
        daemonVersion = pong != null && pong.startsWith("PONG ") ? pong.substring(5) : null;
        String s = daemonVersion != null ? cmd("STATUS") : null;
        setStatus(s != null ? s : "-");
        if (System.currentTimeMillis() - tvCheckedAt > 3000) checkTv();
        if (daemonVersion != null) {
            String sc = cmd("SCREENS");
            int m = 0;
            if (sc != null && sc.startsWith("SCREENS ")) {
                try {
                    m = Integer.parseInt(sc.substring(8).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (m != screensNow) {
                screensNow = m;
                notifyChanged();
            }
        }
    }

    private static String sigOf(String s) {
        if (s.startsWith("STREAMING")) { // ignore counters: layout + rounded fps only
            String[] p = s.split(" ");
            try {
                return "STREAMING " + p[1] + " " + Math.round(Double.parseDouble(p[3]));
            } catch (Exception e) {
                return "STREAMING";
            }
        }
        return s;
    }

    private void setStatus(String s) {
        status = s;
        String sig = sigOf(s);
        if (!sig.equals(statusSig)) {
            statusSig = sig;
            notifyChanged();
        }
    }

    long lastStopAt() {
        return lastStopAt;
    }

    // receiver player: "emss" (low latency, audio must be PCM) or "mse" (AAC/Opus); empty = unknown
    private volatile String receiverPlayer = "";
    // largest frame the receiver decodes, "WxH" from its /status; empty = not reported (receivers before 0.8.4)
    private volatile String receiverMax = "";
    // largest frame area its HEVC level allows and the screen it shows the picture at (receivers from 0.8.5)
    private volatile String receiverPixels = "", receiverScreen = "";
    // what failed on the receiver after its own fixes: "decoder" or "size WxH" (receivers from 0.8.5); "" = fine
    private volatile String receiverProblem = "";

    private String fetchTvStatus(int connectMs, int readMs) throws java.io.IOException {
        String host = settings.tv().split(":")[0];
        if (host.isEmpty()) throw new java.io.IOException("no TV");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://" + host + ":8202/status").openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        try (java.io.InputStream in = c.getInputStream()) {
            byte[] b = new byte[1024];
            int n = in.read(b);
            String body = n > 0 ? new String(b, 0, n, "UTF-8") : "";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"player\":\"(\\w+)\"").matcher(body);
            if (m.find()) receiverPlayer = m.group(1);
            java.util.regex.Matcher mx = java.util.regex.Pattern.compile("\"maxVideo\":\"(\\d+x\\d+)\"").matcher(body);
            receiverMax = mx.find() ? mx.group(1) : "";
            java.util.regex.Matcher mp = java.util.regex.Pattern.compile("\"maxPixels\":(\\d+)").matcher(body);
            receiverPixels = mp.find() ? mp.group(1) : "";
            java.util.regex.Matcher ms = java.util.regex.Pattern.compile("\"screen\":\"(\\d+x\\d+)\"").matcher(body);
            receiverScreen = ms.find() ? ms.group(1) : "";
            java.util.regex.Matcher mr = java.util.regex.Pattern.compile("\"problem\":\"([^\"]*)\"").matcher(body);
            receiverProblem = mr.find() ? mr.group(1) : "";
            return body;
        }
    }

    /** Sender start arguments, with the audio codec matched to the receiver's player. */
    private String startArgs() {
        String args = settings.buildArgs();
        if ("emss".equals(receiverPlayer) || "android".equals(receiverPlayer)) args = args.replaceAll("acodec=\\w+", "acodec=pcm");
        if (!receiverMax.isEmpty()) args += " maxsize=" + receiverMax;
        if (!receiverPixels.isEmpty()) args += " maxpixels=" + receiverPixels;
        if (!receiverScreen.isEmpty()) args += " screen=" + receiverScreen;
        return args;
    }

    /** Checks whether the TV is actually receiving video from this device by querying the receiver's status endpoint. */
    private void checkTv() {
        tvCheckedAt = System.currentTimeMillis();
        int ns = 0;
        if (isStreaming()) {
            try {
                String before = receiverPlayer, problemBefore = receiverProblem;
                String body = fetchTvStatus(800, 1200);
                if (!problemBefore.equals(receiverProblem)) notifyChanged();
                // receiver switched player (red remote button): restart the sender with a matching audio codec
                if (!before.isEmpty() && !before.equals(receiverPlayer) && settings.audio() != 0) main.post(this::start);
                boolean receiving = body.contains("\"src\":1") || body.matches("(?s).*\"src\":[1-9].*");
                ns = receiving ? 1 : 2;
            } catch (Exception e) {
                ns = 2;
            }
        }
        if (ns != tvState) {
            tvState = ns;
            notifyChanged();
        }
    }

    /** Receiver reception status line; empty when not streaming. */
    String tvLine() {
        if (!isStreaming()) return "";
        // the receiver tried its automatic fixes and still fails: say what and what to do
        String pr = receiverProblem;
        if (pr.equals("decoder")) return L.s("tv_problem_decoder");
        if (pr.startsWith("size ")) return L.f("tv_problem_size", pr.substring(5));
        return tvState == 1 ? L.s("tv_receiving") : tvState == 2 ? L.s("tv_not_receiving") : "";
    }

    boolean tvOk() {
        return tvState == 1;
    }

    private boolean daemonUp() {
        return ("PONG " + Sender.VERSION).equals(cmd("PING"));
    }

    /** Ensures a daemon of the current version is running, launching it if needed (2 attempts). Call on the io thread. */
    private boolean ensureDaemon() {
        String pong = cmd("PING");
        if (("PONG " + Sender.VERSION).equals(pong)) return true;
        if (pong != null) { // stale daemon left over from before an app update
            cmd("QUIT");
            sleep(500);
        }
        if (!AdbLauncher.isPaired(app)) {
            backendError = L.s("st_need_setup");
            return false;
        }
        backendError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attempt > 1) AdbLauncher.resetConnection(app); // retry with a fresh connection, as after an app restart
            try {
                AdbLauncher.launchDaemon(app, PORT, settings.token());
            } catch (Exception e) {
                backendError = e.getMessage();
                android.util.Log.e(AdbLauncher.TAG, "launchDaemon (attempt " + attempt + ")", e);
                continue;
            }
            for (int i = 0; i < 30; i++) {
                sleep(200);
                if (daemonUp()) return true;
            }
            android.util.Log.w(AdbLauncher.TAG, "sender not responding after launch (attempt " + attempt + ")");
        }
        if (backendError == null) backendError = "sender did not start (log: /data/local/tmp/duobeam-daemon.log)";
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ---- UI API ----

    boolean ready() {
        return daemonVersion != null || AdbLauncher.isPaired(app);
    }

    String status() {
        return status;
    }

    boolean isStreaming() {
        String s = status;
        return s.startsWith("STARTING") || s.startsWith("CONNECTED") || s.startsWith("STREAMING") || s.startsWith("NO_TV");
    }

    void start() {
        if (settings.tv().trim().isEmpty()) {
            setStatus("ERROR " + L.s("st_no_tv_set"));
            return;
        }
        final boolean wasStreaming = isStreaming();
        final int g = gen;
        String host = settings.tv().split(":")[0].trim();
        if (!host.isEmpty()) settings.addKnownReceiver(host); // later receiver scans also cover this host's subnet
        busy = true;
        setStatus("STARTING");
        // coalesce rapid requests (layout chips, double Start): one restart picks up the latest settings
        if (startQueued.getAndSet(true)) return;
        io.execute(() -> {
            startQueued.set(false);
            if (ensureDaemon()) {
                try {
                    fetchTvStatus(600, 800); // receiver player determines the audio codec
                } catch (Exception ignored) {
                    // receiver not responding yet: keep the last known player
                }
                cmd("START " + startArgs(), 12000); // the sender waits up to 8 s for the encoder to be released
                // on restart (layout/audio change) the sender keeps its screen state, so don't blank screens again
                if (!wasStreaming && settings.screensOff() != 0) cmd("SCREENS " + settings.screensArg());
                if (gen == g) main.post(() -> ControlService.ensureRunning(app)); // skip if Stop was requested meanwhile
            } else {
                setStatus("ERROR " + backendError);
            }
            busy = false;
            poll();
            notifyChanged();
        });
    }

    void stop() {
        gen++;
        lastStopAt = System.currentTimeMillis();
        io.execute(() -> {
            cmd("STOP");
            poll();
        });
        app.stopService(new Intent(app, ControlService.class));
        notifyChanged();
    }

    void toggle() {
        if (isStreaming()) stop();
        else start();
    }

    void setLayoutAndApply(int layout) {
        settings.setLayout(layout);
        if (isStreaming()) start();
        notifyChanged();
    }

    /** Persists the audio mode; while streaming, the sender restarts with the new setting. */
    void setAudioAndApply(int audio) {
        settings.setAudio(audio);
        if (isStreaming()) start();
        notifyChanged();
    }

    /** Persists which device panels to blank; applied immediately while streaming. */
    void setScreens(int mask) {
        settings.setScreensOff(mask);
        io.execute(() -> {
            if (isStreaming()) cmd("SCREENS " + settings.screensArg());
            poll();
        });
    }

    /** Toggles the side bar (from the app, side panel or notification) and notifies all listeners. */
    void setOverlay(boolean on) {
        settings.setOverlay(on);
        notifyChanged();
    }

    void setScreenTouch(boolean on) {
        settings.setScreenTouch(on);
        // send the live screen state (e.g. one woken by triple-tap), not the saved one, or it would blank again
        io.execute(() -> {
            if (isStreaming()) cmd("SCREENS " + screensNow + " " + (on ? 1 : 0));
            poll();
        });
        notifyChanged();
    }

    int screensNow() {
        return screensNow;
    }

    void nextLayout() {
        setLayoutAndApply(settings.layout() + 1);
    }
}
