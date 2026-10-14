package com.duobeam.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** DuoBeam receiver for Android TV: waits for the handheld, shows its screens full-screen with game sound. */
public class ReceiverActivity extends Activity implements StreamServer.Listener, VideoOut.Listener {

    static final String VERSION = BuildConfig.VERSION_NAME;

    // ---- texts: English default, Polish with left/right on the remote (remembered) ----
    private static final Map<String, String[]> T = new HashMap<>();

    static {
        t("waiting", "Waiting for the handheld...", "Czekam na konsolę...");
        t("connected", "Handheld connected - starting picture...", "Konsola połączona - uruchamiam obraz...");
        t("gone", "Handheld disconnected - waiting...", "Konsola rozłączona - czekam...");
        t("stopped", "Handheld stopped sending - waiting...", "Konsola przestała nadawać - czekam...");
        t("switching", "Switching layout...", "Zmieniam układ...");
        t("addr", "This receiver's address (the DuoBeam app finds it with \"Find receiver\"):",
                "Adres tego odbiornika (apka DuoBeam znajdzie go przyciskiem \"Znajdź odbiornik\"):");
        t("steps", "On the handheld: open <b>DuoBeam</b> and tap <b>Start</b>.<br><small>First time only: tap \"Set up\" in DuoBeam and follow the steps.</small>",
                "Na konsoli: otwórz <b>DuoBeam</b> i naciśnij <b>Start</b>.<br><small>Tylko za pierwszym razem: naciśnij w DuoBeam \"Konfiguruj\" i postępuj według kroków.</small>");
        t("keys", "OK info  ·  ↑/↓ delay vs smoothness  ·  ←/→ language  ·  Back exit",
                "OK info  ·  ↑/↓ opóźnienie vs płynność  ·  ←/→ język  ·  Wstecz wyjście");
        t("mode", "Mode", "Tryb");
        t("mode0", "lowest delay", "najmniejsze opóźnienie");
        t("mode1", "balanced", "zrównoważony");
        t("mode2", "smoothest", "najpłynniejszy");
        t("flag", "EN", "PL");
        t("nodecoder", "This device can't play this picture size (%s). On the handheld pick another layout (e.g. top screen only).",
                "To urządzenie nie odtworzy obrazu tego rozmiaru (%s). Na konsoli wybierz inny układ (np. tylko górny ekran).");
        t("dec_restart", "Picture restarted: the decoder stopped (setup %d)", "Obraz uruchomiony ponownie: dekoder stanął (ustawienie %d)");
        t("dec_trouble", "This TV's decoder keeps stopping with this stream. On the handheld pick another layout or lower quality. "
                        + "Still stuck? Press OK and send a photo of the info box to the DuoBeam developer.",
                "Dekoder tego telewizora ciągle staje przy tym obrazie. Na konsoli wybierz inny układ albo niższą jakość. "
                        + "Dalej nie działa? Naciśnij OK i wyślij zdjęcie okienka z informacjami twórcy DuoBeam.");
        t("receiver", "receiver", "odbiornik");
        t("delay", "delay", "opóźnienie");
    }

    private static void t(String k, String en, String pl) {
        T.put(k, new String[] {en, pl});
    }

    private int lang, mode;
    private long toastUntil;
    private static final int[][] MODES = {{3, 500, 2}, {8, 1500, 0}, {18, 60000, 0}}; // {backlog frames, hold ms, max in decoder}

    private void applyMode() {
        VideoOut.backlogFrames = MODES[mode][0];
        VideoOut.backlogMs = MODES[mode][1];
        VideoOut.maxInFlight = MODES[mode][2];
    }

    private void setMode(int m) {
        mode = (m + MODES.length) % MODES.length;
        getSharedPreferences("tv", MODE_PRIVATE).edit().putInt("mode", mode).apply();
        applyMode();
        hud.setText(s("mode") + ": " + s("mode" + mode));
        hud.setVisibility(View.VISIBLE);
        hud.bringToFront();
        toastUntil = System.currentTimeMillis() + 2500;
    }

    private String s(String k) {
        String[] v = T.get(k);
        return v == null ? k : v[lang];
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private StreamServer server;
    private SurfaceView surfaceView;
    private volatile Surface surface;
    private FrameLayout videoBox;
    private LinearLayout idle, switching;
    private TextView stateText, addrLabel, addrText, stepsText, keysText, flagText, verText, hud, switchingText;
    private String stateKey = "waiting";
    private boolean hudOn;

    private volatile VideoOut video;
    private volatile AudioOut audio;
    private volatile boolean playing;
    private volatile long lastFrameAt, switchingUntil;
    private byte[] pendingInit;
    private String pendingCodec;

    // latency: frame timestamps from the sender (media us -> {capture, send, receive} in TV clock ms)
    private final Map<Long, double[]> marks = Collections.synchronizedMap(new HashMap<>());
    private final ArrayList<double[]> lat = new ArrayList<>();
    private int lastShown, lastRendered;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences p = getSharedPreferences("tv", MODE_PRIVATE);
        lang = p.getInt("lang", 0);
        mode = p.getInt("mode", 0);
        applyMode();
        buildUi();
        server = new StreamServer(this, VERSION);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemUi();
        server.start();
        main.post(tick);
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(tick);
        server.stop();
        releaseMedia();
        playing = false;
        super.onStop();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---- UI ----

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float size, boolean bold) {
        TextView t = new OutlineText(this, dp(size >= 30 ? 2.5f : 1.5f));
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static GradientDrawable panel(int fill, int radius, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(strokeW, 0x47BE96FF);
        return g;
    }

    private static LinearLayout.LayoutParams below(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = top;
        return lp;
    }

    // Same layout and sizes as tizen-receiver/index.html (1 dp here = 2 px there on a 1080p TV).
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.bg);

        videoBox = new FrameLayout(this);
        videoBox.setBackgroundColor(Color.BLACK);
        videoBox.setVisibility(View.INVISIBLE);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                surface = h.getSurface();
                if (pendingInit != null) {
                    final byte[] init = pendingInit;
                    final String codec = pendingCodec;
                    pendingInit = null;
                    new Thread(() -> startMedia(codec, init)).start();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                surface = null;
                releaseMedia();
            }
        });
        videoBox.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(videoBox, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // waiting screen: darkened edges and a card in the middle
        idle = new LinearLayout(this);
        idle.setGravity(Gravity.CENTER);
        GradientDrawable vignette = new GradientDrawable();
        vignette.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        vignette.setGradientRadius(dp(560));
        vignette.setColors(new int[] {0x00070418, 0xA6070418});
        idle.setBackground(vignette);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(36), dp(28), dp(36), dp(28));
        card.setBackground(panel(0x8C140A30, dp(20), dp(1)));
        idle.addView(card, new LinearLayout.LayoutParams(dp(590), -2));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        head.addView(icon, new LinearLayout.LayoutParams(dp(75), dp(75)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(18), 0, 0, 0);
        TextView title = text(42, true);
        title.setText("DuoBeam");
        titles.addView(title);
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFFFF7AD9);
        dotBg.setStroke(dp(1), Color.BLACK);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(9), dp(9));
        dl.rightMargin = dp(5);
        stateRow.addView(dot, dl);
        ObjectAnimator pulse = ObjectAnimator.ofFloat(dot, "alpha", 0.35f, 1f);
        pulse.setDuration(800);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
        stateText = text(18, false);
        stateRow.addView(stateText);
        titles.addView(stateRow);
        head.addView(titles);
        card.addView(head);

        addrLabel = text(15, false);
        card.addView(addrLabel, below(dp(18)));
        addrText = text(48, true);
        addrText.setLetterSpacing(0.03f);
        card.addView(addrText);
        stepsText = text(16, false);
        stepsText.setLineSpacing(0, 1.3f);
        card.addView(stepsText, below(dp(12)));

        keysText = text(12, false);
        card.addView(keysText, below(dp(20)));
        verText = text(12, false);
        verText.setAlpha(0.8f);
        card.addView(verText, below(dp(2)));
        root.addView(idle, new FrameLayout.LayoutParams(-1, -1));

        switching = new LinearLayout(this);
        switching.setOrientation(LinearLayout.VERTICAL);
        switching.setGravity(Gravity.CENTER);
        switching.setBackgroundColor(0xEB140A30);
        ProgressBar ring = new ProgressBar(this);
        switching.addView(ring, new LinearLayout.LayoutParams(dp(60), dp(60)));
        switchingText = text(22, false);
        switching.addView(switchingText, below(dp(17)));
        switching.setVisibility(View.GONE);
        root.addView(switching, new FrameLayout.LayoutParams(-1, -1));

        hud = text(11, false);
        hud.setTypeface(Typeface.MONOSPACE);
        hud.setGravity(Gravity.START);
        hud.setBackground(panel(0xB3140A30, dp(6), dp(1)));
        hud.setPadding(dp(7), dp(4), dp(7), dp(4));
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.setMargins(dp(12), dp(12), 0, 0);
        root.addView(hud, hl);

        flagText = text(17, true);
        flagText.setBackground(panel(0x59000000, dp(9), dp(1)));
        flagText.setPadding(dp(9), dp(4), dp(9), dp(4));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        fl.setMargins(0, dp(20), dp(24), 0);
        root.addView(flagText, fl);

        setContentView(root);
        applyLang();
    }

    private void applyLang() {
        stateText.setText(stateKey.startsWith("!") ? stateKey.substring(1) : s(stateKey));
        addrLabel.setText(s("addr"));
        addrText.setText(localIp());
        stepsText.setText(Html.fromHtml(s("steps"), Html.FROM_HTML_MODE_LEGACY));
        keysText.setText(s("keys"));
        flagText.setText(s("flag"));
        verText.setText(s("receiver") + " v" + VERSION + " (Android TV)");
        switchingText.setText(s("switching"));
    }

    private void setState(String key) {
        stateKey = key;
        stateText.setText(key.startsWith("!") ? key.substring(1) : s(key));
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "?";
    }

    private void showVideo(boolean on) {
        videoBox.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        idle.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) switching.setVisibility(View.GONE);
        flagText.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    /** Fit the picture into the screen, keeping its shape (the handheld's layouts are wide or tall). */
    private void fitVideo(int w, int h) {
        View root = (View) videoBox.getParent();
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw == 0 || sh == 0 || w == 0 || h == 0) return;
        float k = Math.min(sw / (float) w, sh / (float) h);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * k), Math.round(h * k), Gravity.CENTER);
        surfaceView.setLayoutParams(lp);
    }

    // ---- stream ----

    @Override
    public void onSourceChanged(boolean connected) {
        main.post(() -> {
            if (connected) {
                setState("connected");
                return;
            }
            releaseMedia();
            playing = false;
            if (System.currentTimeMillis() < switchingUntil) return; // layout change - the handheld reconnects in a moment
            showVideo(false);
            switching.setVisibility(View.GONE);
            setState("gone");
        });
    }

    @Override
    public void onMessage(int type, byte[] p) {
        switch (type) {
            case 0: { // new stream: codec line + init segment
                int nl = 0;
                while (nl < p.length && p[nl] != '\n') nl++;
                String codec = new String(p, 0, nl);
                byte[] init = new byte[p.length - nl - 1];
                System.arraycopy(p, nl + 1, init, 0, init.length);
                releaseMedia();
                // a new stream starts its own delay figures (the old samples mixed two streams in the HUD and /status)
                marks.clear();
                synchronized (lat) {
                    lat.clear();
                }
                if (surface == null) {
                    pendingCodec = codec;
                    pendingInit = init;
                    main.post(() -> videoBox.setVisibility(View.VISIBLE)); // creates the surface
                } else {
                    startMedia(codec, init);
                }
                break;
            }
            case 1:
            case 2: {
                VideoOut v = video;
                if (v != null) v.frame(p, 0, p.length, type == 1);
                lastFrameAt = System.currentTimeMillis();
                break;
            }
            case 4:
                switchingUntil = System.currentTimeMillis() + 8000;
                main.post(() -> {
                    switching.setVisibility(View.VISIBLE);
                    switching.bringToFront();
                });
                break;
            case 5: {
                AudioOut a = audio;
                if (a == null) break;
                int[] m = Mp4.mdat(p, 0, p.length);
                if (m == null) break;
                byte[] pcm = new byte[m[1]];
                System.arraycopy(p, m[0], pcm, 0, m[1]);
                a.block(pcm);
                break;
            }
            case 6: { // frame timestamp from the sender (its clock) -> our clock
                double off = server.clockOffset;
                if (Double.isNaN(off) || p.length != 24) break;
                ByteBuffer bb = ByteBuffer.wrap(p);
                long mt = bb.getLong();
                double cap = bb.getLong() - off, send = bb.getLong() - off;
                marks.put(mt, new double[] {cap, send, System.currentTimeMillis()});
                if (marks.size() > 64) marks.clear();
                break;
            }
            default:
                break;
        }
    }

    private void startMedia(String codec, byte[] init) {
        Surface sf = surface;
        if (sf == null) {
            pendingCodec = codec;
            pendingInit = init;
            return;
        }
        Mp4.Init in;
        try {
            in = Mp4.parseInit(init, 0, init.length);
        } catch (Exception e) {
            return;
        }
        String[] err = new String[1];
        curCodec = codec;
        curInit = init;
        VideoOut v = VideoOut.create(in, sf, this, err, decoderLevel);
        video = v;
        if (codec.contains(",pcm")) audio = new AudioOut();
        main.post(() -> {
            if (v == null) {
                showVideo(false);
                setState("!" + String.format(s("nodecoder"), in.width + "x" + in.height));
                return;
            }
            fitVideo(in.width, in.height);
        });
    }

    // decoder setup level that worked on this TV (VideoOut.LEVEL_*); kept while the app runs so a new stream does not
    // stall again before finding it
    private static volatile int decoderLevel = VideoOut.LEVEL_FULL;
    private volatile String curCodec;
    private volatile byte[] curInit;
    private long lastRecoverAt;
    private int recoveries;
    // restarts in the last minute; 3 or more = the automatic fixes did not help, so the TV says so plainly
    private final java.util.ArrayDeque<Long> recoverTimes = new java.util.ArrayDeque<>();
    private boolean decoderTrouble;

    private void showToast(String msg) {
        hud.setText(msg);
        hud.setVisibility(View.VISIBLE);
        hud.bringToFront();
        toastUntil = System.currentTimeMillis() + 3000;
    }

    /** The decoder stopped giving frames back: recreate it at the next setup level and ask for a fresh keyframe. */
    private void recoverDecoder(VideoOut v, long now) {
        if (now - lastRecoverAt < 3000 || curInit == null) return;
        lastRecoverAt = now;
        recoveries++;
        recoverTimes.add(now);
        decoderLevel = Math.min(VideoOut.LEVEL_PLAIN, v.level + 1);
        showToast(String.format(s("dec_restart"), decoderLevel));
        android.util.Log.w("DuoBeamTV", "decoder " + v.name + " stalled at setup level " + v.level + ", recreating at level " + decoderLevel);
        final String codec = curCodec;
        final byte[] init = curInit;
        new Thread(() -> {
            releaseMedia();
            startMedia(codec, init);
            server.requestKeyframe();
        }, "DuoBeamTV-recover").start();
    }

    private void releaseMedia() {
        VideoOut v = video;
        video = null;
        if (v != null) v.release();
        AudioOut a = audio;
        audio = null;
        if (a != null) a.release();
    }

    @Override
    public void onFrameShown(long ptsUs) {
        if (!playing) {
            playing = true;
            main.post(() -> showVideo(true));
        }
        VideoOut v = video;
        if (v == null || !v.renderCallbacks) addLatency(ptsUs, System.currentTimeMillis());
    }

    @Override
    public void onFrameRendered(long ptsUs, long renderNs) {
        addLatency(ptsUs, System.currentTimeMillis() - (System.nanoTime() - renderNs) / 1e6);
    }

    /** One meter sample: the frame with a sender mark reached the screen surface at shownMs (TV clock). */
    private void addLatency(long ptsUs, double shownMs) {
        double[] m = marks.remove(ptsUs);
        if (m == null) return;
        synchronized (lat) {
            lat.add(new double[] {shownMs - m[0], m[1] - m[0], m[2] - m[1], shownMs - m[2]});
            if (lat.size() > 120) lat.remove(0); // ~8 s at one mark per 4 frames
        }
    }

    private static double pct(ArrayList<double[]> l, int k, int p) {
        ArrayList<Double> v = new ArrayList<>();
        for (double[] x : l) v.add(x[k]);
        Collections.sort(v);
        return v.isEmpty() ? Double.NaN : v.get(Math.min(v.size() - 1, v.size() * p / 100));
    }

    private long lastKeyReq;

    @Override
    public void onNeedKeyframe() {
        long now = System.currentTimeMillis();
        if (now - lastKeyReq < 300) return;
        lastKeyReq = now;
        server.requestKeyframe();
    }

    private String displayInfo;

    /** Refresh rate and whether the TV offers a game mode (ALLM / minimal post-processing) to apps. */
    private String displayInfo() {
        if (displayInfo == null) {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            String allm = android.os.Build.VERSION.SDK_INT >= 30 ? (d.isMinimalPostProcessingSupported() ? "yes" : "no") : "?";
            displayInfo = Math.round(d.getRefreshRate()) + " Hz, game mode " + allm;
        }
        return displayInfo;
    }

    private static double median(ArrayList<double[]> l, int k) {
        ArrayList<Double> v = new ArrayList<>();
        for (double[] x : l) v.add(x[k]);
        Collections.sort(v);
        return v.isEmpty() ? Double.NaN : v.get(v.size() / 2);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            while (!recoverTimes.isEmpty() && now - recoverTimes.peekFirst() > 60_000) recoverTimes.pollFirst();
            decoderTrouble = recoverTimes.size() >= 3;
            server.problem = decoderTrouble ? "decoder" : "";
            if (decoderTrouble && !hudOn && now >= toastUntil) { // stays until a minute passes without a restart
                hud.setText(s("dec_trouble"));
                hud.setVisibility(View.VISIBLE);
                hud.bringToFront();
            } else if (now >= toastUntil && !hudOn && hud.getVisibility() == View.VISIBLE) {
                hud.setVisibility(View.GONE); // mode toast over
            }
            if (playing && server.connected() && now - lastFrameAt > 3000) { // stream stalled
                playing = false;
                showVideo(false);
                setState("stopped");
            }
            if (switching.getVisibility() == View.VISIBLE && now > switchingUntil && !playing) switching.setVisibility(View.GONE);
            VideoOut v = video;
            if (v != null && v.stalled()) recoverDecoder(v, now);
            String tvStages = v == null ? null : v.waitTime.line() + " / " + v.decodeTime.line() + " / " + (v.renderCallbacks ? v.renderTime.line() : "-");
            String latJson = "null";
            synchronized (lat) {
                if (!lat.isEmpty()) {
                    latJson = "{\"total\":" + Math.round(median(lat, 0)) + ",\"totalP95\":" + Math.round(pct(lat, 0, 95)) + ",\"handheld\":"
                            + Math.round(median(lat, 1)) + ",\"wifi\":" + Math.round(median(lat, 2)) + ",\"tv\":" + Math.round(median(lat, 3))
                            + ",\"n\":" + lat.size() + "}";
                }
            }
            server.extraStatus = ",\"lat\":" + latJson + ",\"tvStages\":" + (tvStages == null ? "null" : "\"" + tvStages + "\"")
                    + ",\"renderCallbacks\":" + (v != null && v.renderCallbacks) + ",\"shown\":" + (v == null ? 0 : v.shown)
                    + ",\"rendered\":" + (v == null ? 0 : v.rendered) + ",\"display\":\"" + displayInfo() + "\""
                    + ",\"decoder\":" + (v == null ? "null" : "{\"name\":\"" + v.name + "\",\"level\":" + v.level + ",\"cap\":" + v.cap() + "}")
                    + ",\"recoveries\":" + recoveries;
            if (now < toastUntil) {
                // mode toast stays for a moment
            } else if (hudOn) {
                AudioOut a = audio;
                int shown = v == null ? 0 : v.shown;
                StringBuilder sb = new StringBuilder();
                sb.append(v == null ? "-" : v.width + "x" + v.height).append("  ").append((shown - lastShown) * 2).append(" fps")
                        .append(v != null && v.renderCallbacks ? ", on screen " + Math.max(0, v.rendered - lastRendered) * 2 : "").append("  ")
                        .append(displayInfo());
                synchronized (lat) {
                    if (!lat.isEmpty()) {
                        sb.append("\n").append(s("delay")).append(' ').append(Math.round(median(lat, 0))).append(" ms, p95 ")
                                .append(Math.round(pct(lat, 0, 95))).append("  (handheld ").append(Math.round(median(lat, 1)))
                                .append(" / Wi-Fi ").append(Math.round(median(lat, 2))).append(" / TV ").append(Math.round(median(lat, 3))).append(')');
                    }
                }
                if (tvStages != null) sb.append("\nTV wait / decode / render p50/p95/max ").append(tvStages);
                if (v != null) sb.append("\ndecoder ").append(v.name).append("  setup ").append(v.level).append("  in decoder ")
                        .append(v.cap() == 0 ? "no limit" : "max " + v.cap()).append(recoveries > 0 ? "  restarts " + recoveries : "");
                if (v != null) {
                    sb.append("\ndropped ").append(v.dropped).append("  queue max ").append(v.maxQueue).append("  catch-ups ").append(v.catchUps);
                    v.maxQueue = 0;
                }
                if (a != null) sb.append("  audio underruns ").append(a.underruns).append(" drops ").append(a.drops);
                sb.append("\nclock rtt ").append(Double.isNaN(server.clockRtt) ? "-" : Math.round(server.clockRtt) + " ms");
                sb.append("\n").append(s("mode")).append(": ").append(s("mode" + mode));
                hud.setText(sb.toString());
                lastShown = shown;
                lastRendered = v == null ? 0 : v.rendered;
            }
            main.postDelayed(this, 500);
        }
    };

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                hudOn = !hudOn;
                hud.setVisibility(hudOn ? View.VISIBLE : View.GONE);
                hud.bringToFront();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                setMode(mode + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                setMode(mode - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                lang = 1 - lang;
                getSharedPreferences("tv", MODE_PRIVATE).edit().putInt("lang", lang).apply();
                applyLang();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }
}
