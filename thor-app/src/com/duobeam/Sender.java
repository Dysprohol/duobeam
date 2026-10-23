package com.duobeam;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Captures both device screens into one composed frame (OpenGL, constant frame rate), encodes it as HEVC,
 * muxes to fMP4 and streams it over TCP directly to the TV receiver (:8201).
 * Message: [len u32][type u8][payload]. Types: 0 = codec string + '\n' + init segment, 1 = key frame, 2 = frame,
 * 4 = stream restarting, 5 = audio frame, 6 = frame timestamp (media time us, capture ms, send ms; device clock),
 * 9 = clock ping reply.
 * TV -> device on the same socket: 8 = clock ping (8 bytes of TV time) for end-to-end latency measurement,
 * 10 = key frame request (the receiver dropped a frame and would otherwise freeze until the next key frame).
 *
 * Usage (as shell):
 *   CLASSPATH=/data/local/tmp/duobeam.jar app_process / com.duobeam.Sender tv=<TV-IP>:8201 layout=side
 * Options: bitrate=12000000 fps=60 iframe=2 wifilock=on|off codec=hevc audio=off|tv|both acodec=aac|opus|pcm avsync=<ms>
 *          maxsize=<W>x<H> maxpixels=<N> screen=<W>x<H> (receiver's decoder box, largest frame area and screen;
 *          bigger frames are scaled down to fit, default 3840x2160 and no area or screen limit)
 *
 * Daemon mode (controlled by the app over a local socket, every command prefixed with a token):
 *   ... com.duobeam.Sender daemon port=27500 token=XYZ
 *   line "<token> PING|STATUS|STOP|QUIT|START <options>|SCREENS [mask [touch]]" -> one reply line
 */
public final class Sender {

    public static final String VERSION = "0.8.9";
    private static final String NL = "\n";

    private static Map<String, String> opts;
    private static final Object SEND_LOCK = new Object();
    // TV connection as seen by the audio thread (null = not connected or init segment not sent yet)
    private static volatile DataOutputStream liveOut;
    private static volatile long videoPts0Us = -1;
    private static volatile boolean keyRequested;

    private static String opt(String k, String def) {
        String v = opts.get(k);
        return v != null ? v : def;
    }

    // --- start/stop/status, driven by the daemon commands below ---
    private static volatile boolean stopRequested;
    private static volatile int generation; // bumped by every stop; a stream loop runs only in the generation it started in
    // machine-readable status; the app localizes it:
    // STOPPED | STARTING | CONNECTED <tv> | NO_TV <tv> <error> | STREAMING <layout> <w>x<h> <fps> <frames> <wifilock 0/1> | ERROR <details>
    private static volatile String status = "STOPPED";
    private static Thread worker;
    private static boolean workaroundsApplied;

    // restart while streaming (e.g. layout change): TV gets a "switching" signal instead of "disconnected"
    private static volatile boolean switching;

    /** Thrown out of the stream loop when a captured display changed size; the worker restarts the stream at once. */
    private static final class DisplayChanged extends RuntimeException {
    }

    public static synchronized void startAsync(final String argsLine) {
        switching = worker != null;
        stopAsync();
        switching = false;
        stopRequested = false;
        status = "STARTING";
        worker = new Thread(() -> {
            try {
                // Workarounds creates an ActivityThread, which creates a Handler on the current thread
                if (Looper.myLooper() == null) Looper.prepare();
                int attempts = 0;
                while (true) {
                    long began = System.currentTimeMillis();
                    try {
                        run(argsLine.trim().split(" +"));
                        break;
                    } catch (DisplayChanged d) {
                        if (stopRequested) break;
                        Ln.i("display size changed (rotation), restarting the stream with the new size");
                        if (worker == Thread.currentThread()) status = "STARTING";
                    } catch (Throwable t) {
                        if (System.currentTimeMillis() - began > 30_000) attempts = 0; // ran long enough, reset the retry budget
                        if (stopRequested || ++attempts > 3) throw t;
                        Ln.w("stream interrupted (" + t + "), retry " + attempts + "/3");
                        if (worker == Thread.currentThread()) status = "STARTING";
                        Thread.sleep(1000);
                    }
                }
                if (worker == Thread.currentThread()) status = "STOPPED"; // a stale worker (outlived the join timeout) must not overwrite the new stream's status
            } catch (Throwable t) {
                Ln.e("DuoBeam: " + t.getMessage(), t);
                StringBuilder sb = new StringBuilder("ERROR ");
                for (Throwable c = t; c != null; c = c.getCause()) {
                    sb.append(c).append(c.getStackTrace().length > 0 ? " @ " + c.getStackTrace()[0] : "");
                    if (c.getCause() != null) sb.append(" | caused by: ");
                }
                if (worker == Thread.currentThread()) {
                    status = sb.toString();
                    ScreenPower.restoreAll(); // stream died on its own (not via STOP); screens and touch must come back
                }
            }
        }, "DuoBeamSender");
        worker.start();
    }

    public static synchronized void stopAsync() {
        stopRequested = true;
        // A worker that outlives the 8 s join (e.g. blocked in send()) used to come back to life when the next start
        // cleared stopRequested, and kept the encoder; a stopped generation stays stopped. Closing the socket also
        // unblocks a send to a TV that no longer answers.
        generation++;
        Socket s = liveSock;
        // A restart (layout change) tells the TV first, so it shows its "switching layout" screen. Closing the socket
        // came before the worker's own message, so no receiver got it since 0.8.7 (seen on the PC receiver,
        // 2026-10-23). The write gets 300 ms, so a TV that vanished still cannot hold the stop up.
        final DataOutputStream o = liveOut;
        if (switching && o != null) {
            Thread t = new Thread(() -> {
                try {
                    send(o, 4, new byte[0]);
                } catch (IOException ignored) {
                }
            }, "DuoBeamSwitching");
            t.setDaemon(true);
            t.start();
            try {
                t.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (s != null) closeQuietly(s);
        if (!switching) ScreenPower.restoreAll();
        if (worker != null) {
            try {
                worker.join(8000); // the hardware encoder must be released before a new one starts
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }

    public static String getStatus() {
        return status;
    }

    // --- daemon: local control (127.0.0.1 + token only, so other apps on the device cannot capture the screen) ---
    static void daemon(int port, String token) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        Ln.i("DuoBeam daemon " + VERSION + " on 127.0.0.1:" + port);
        byte[] expected = token.getBytes("UTF-8");
        lastAuthAt = System.currentTimeMillis();
        startWatchdog();
        Runtime.getRuntime().addShutdownHook(new Thread(ScreenPower::restoreAll)); // restore screens on SIGTERM etc.
        while (true) {
            final Socket c = ss.accept();
            new Thread(() -> handle(c, expected), "DuoBeamCtl").start();
        }
    }

    private static void handle(Socket c, byte[] expected) {
        try (Socket s = c) {
            s.setSoTimeout(5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            Writer w = new OutputStreamWriter(s.getOutputStream(), "UTF-8");
            String line = r.readLine();
            if (line == null) return;
            String[] p = line.split(" ", 3);
            if (p.length < 2 || !MessageDigest.isEqual(p[0].getBytes("UTF-8"), expected)) {
                w.write("ERR auth" + NL);
                w.flush();
                return;
            }
            lastAuthAt = System.currentTimeMillis();
            String reply;
            boolean quit = false;
            switch (p[1]) {
                case "PING": reply = "PONG " + VERSION; break;
                case "STATUS": reply = getStatus().replace(NL, " "); break;
                case "START":
                    Ln.i("START " + (p.length > 2 ? p[2].replaceAll("tv=[^ ]+ ", "") : "")); // log restart options (TV address stripped)
                    startAsync(p.length > 2 ? p[2] : "");
                    reply = "OK";
                    break;
                case "STOP": stopAsync(); reply = "OK"; break;
                case "SCREENS": // SCREENS <mask: 1 top, 2 bottom> [touch 1/0]; no argument = query only
                    if (p.length > 2) {
                        String[] a = p[2].trim().split(" +");
                        boolean touch = a.length > 1 ? "1".equals(a[1]) : ScreenPower.touchAllowed();
                        ScreenPower.apply(Integer.parseInt(a[0]), touch);
                    }
                    reply = "SCREENS " + ScreenPower.mask();
                    break;
                case "QUIT": stopAsync(); ScreenPower.restoreAll(); reply = "OK"; quit = true; break;
                default: reply = "ERR unknown";
            }
            lastAuthAt = System.currentTimeMillis(); // a slow command (START waits for the encoder) does not count as app silence
            w.write(reply + NL);
            w.flush();
            if (quit) System.exit(0);
        } catch (IOException ignored) {
        }
    }

    // --- app heartbeat: stop streaming when the app goes away (data cleared, uninstalled, force-stopped) ---
    // the app pings every second from a dedicated thread, so ~5 s of silence means it is gone
    private static final long NO_APP_STOP_MS = 5_000, NO_APP_EXIT_MS = 60_000;
    private static volatile long lastAuthAt;

    private static void startWatchdog() {
        final String apk = System.getProperty("java.class.path", "");
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                long idle = System.currentTimeMillis() - lastAuthAt;
                boolean apkGone = !apk.isEmpty() && apk.endsWith(".apk") && !new java.io.File(apk).exists();
                if (apkGone) {
                    Ln.w("APK removed (uninstall/update), exiting");
                    stopAsync();
                    ScreenPower.restoreAll();
                    System.exit(0);
                }
                if (idle > NO_APP_STOP_MS && worker != null) {
                    Ln.w("no contact from app for " + idle / 1000 + " s, stopping stream");
                    stopAsync();
                    status = "STOPPED";
                }
                if (idle > NO_APP_EXIT_MS) {
                    Ln.w("no contact from app for " + idle / 1000 + " s, exiting");
                    ScreenPower.restoreAll();
                    System.exit(0);
                }
            }
        }, "DuoBeamWatchdog");
        t.setDaemon(true);
        t.start();
    }

    public static void startDaemonThread(final int port, final String token) {
        Thread t = new Thread(() -> {
            try {
                daemon(port, token);
            } catch (Throwable e) {
                Ln.e("daemon: " + e.getMessage(), e);
            }
        }, "DuoBeamDaemon");
        t.setDaemon(true);
        t.start();
    }

    public static void main(String... args) {
        int status = 0;
        try {
            prepareMainLooper();
            if (args.length > 0 && "daemon".equals(args[0])) {
                Map<String, String> d = new HashMap<>();
                for (String a : args) {
                    int i = a.indexOf('=');
                    if (i > 0) d.put(a.substring(0, i), a.substring(i + 1));
                }
                daemon(Integer.parseInt(d.containsKey("port") ? d.get("port") : "27500"), d.get("token"));
                return;
            }
            run(args);
        } catch (Throwable t) {
            Ln.e("DuoBeam: " + t.getMessage(), t);
            status = 1;
        } finally {
            System.exit(status);
        }
    }

    private static void prepareMainLooper() throws ReflectiveOperationException {
        Looper.prepare();
        synchronized (Looper.class) {
            Field f = Looper.class.getDeclaredField("sMainLooper");
            f.setAccessible(true);
            f.set(null, Looper.myLooper());
        }
    }

    private static Object acquireWifiLock() {
        // Disables Wi-Fi power save, which causes latency spikes. Try LOW_LATENCY first, fall back to HIGH_PERF.
        try {
            WifiManager wm = (WifiManager) FakeContext.get().getSystemService(Context.WIFI_SERVICE);
            for (int mode : new int[] {4 /* WIFI_MODE_FULL_LOW_LATENCY */, 3 /* WIFI_MODE_FULL_HIGH_PERF */}) {
                try {
                    WifiManager.WifiLock lock = wm.createWifiLock(mode, "duobeam");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                    Ln.i("wifi lock acquired, mode " + mode + ", held=" + lock.isHeld());
                    return lock;
                } catch (Throwable e) {
                    Ln.w("wifi lock mode " + mode + " failed: " + e);
                }
            }
        } catch (Throwable e) {
            Ln.w("wifi lock unavailable: " + e);
        }
        return null;
    }

    private static void run(String... args) throws Exception {
        opts = new HashMap<>();
        for (String a : args) {
            int i = a.indexOf('=');
            if (i > 0) opts.put(a.substring(0, i), a.substring(i + 1));
        }
        String tvOpt = opt("tv", "");
        if (!tvOpt.contains(":")) throw new IllegalArgumentException("missing tv=<TV-IP>:8201");
        String[] tv = tvOpt.split(":");
        // displays=both|top|bottom picks from the built-in screens; a list of display ids is also accepted
        int[] screens = Screens.ids();
        String which = opt("displays", "both");
        int[] displayIds;
        if ("both".equals(which)) {
            displayIds = screens;
        } else if ("top".equals(which)) {
            displayIds = new int[] {screens[0]};
        } else if ("bottom".equals(which)) {
            displayIds = new int[] {screens[screens.length - 1]};
        } else {
            String[] ids = which.split(",");
            displayIds = new int[ids.length];
            for (int i = 0; i < ids.length; i++) displayIds[i] = Integer.parseInt(ids[i].trim());
        }
        String layout = opt("layout", "side");
        int bitrate = Integer.parseInt(opt("bitrate", "12000000"));
        int fps = Integer.parseInt(opt("fps", "60"));
        int iframe = Integer.parseInt(opt("iframe", "2"));

        synchronized (Sender.class) {
            if (!workaroundsApplied) {
                Workarounds.apply();
                workaroundsApplied = true;
            }
        }
        Object wifiLock = "off".equals(opt("wifilock", "on")) ? null : acquireWifiLock();

        // receiver limits from its /status, passed on by the app; receivers before 0.8.4 report none
        int[] max = wxh(opt("maxsize", "3840x2160")), screen = wxh(opt("screen", "0x0"));
        DualCapture capture = new DualCapture(displayIds, layout, fps,
                new DualCapture.Limits(max[0], max[1], Long.parseLong(opt("maxpixels", "0")), screen[0], screen[1]));
        ThorAudio audio = null;
        MediaCodec codec = null;
        Size size;
        final String acodec = opt("acodec", "aac");
        final boolean opus = "opus".equals(acodec);
        final Fmp4 mux = new Fmp4();
        try {
        // set up audio before video: the init segment must already know whether there is an audio track
        String audioOpt = opt("audio", "off");
        audio = "off".equals(audioOpt) ? null : ThorAudio.open("both".equals(audioOpt), acodec);
        // PCM has no AAC encoder delay, so it needs a smaller offset
        final long avsyncUs = Long.parseLong(opt("avsync", "pcm".equals(acodec) ? "-20" : "-60")) * 1000;
        liveOut = null;
        videoPts0Us = -1;
        if (audio != null) {
            final ThorAudio a = audio;
            a.start((aac, pos) -> {
                DataOutputStream o = liveOut;
                long p0 = videoPts0Us;
                if (o == null || p0 < 0) return;
                // sample pos -> video media time (same System.nanoTime base) + capture/encoder delay offset
                long us = a.startNs() / 1000 + pos * 1_000_000L / ThorAudio.RATE - p0 + avsyncUs;
                if (us < 0) return;
                try {
                    send(o, 5, mux.audioFragment(aac, us * ThorAudio.RATE / 1_000_000L, ThorAudio.frameSamples(opus)));
                } catch (IOException ignored) {
                    // the video loop detects and handles the broken connection
                }
            });
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
        MediaCodecInfo.VideoCapabilities encCaps = null;
        try {
            encCaps = codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).getVideoCapabilities();
            capture.setAlignment(Math.max(encCaps.getWidthAlignment(), encCaps.getHeightAlignment()));
        } catch (Exception e) {
            Ln.w("encoder capabilities: " + e);
        }
        // Reported encoder limits can be wrong (scrcpy 4.1 stopped trusting them up front), so a size the encoder
        // refuses also steps the frame down on the long side, the same ladder scrcpy uses
        final int[] downsize = {2560, 1920, 1600, 1280, 1024, 800};
        int step = 0;
        while (true) {
            size = capture.prepare();
            int w = size.getWidth(), h = size.getHeight();
            if (encCaps == null || encCaps.isSizeSupported(w, h)) {
                try {
                    codec.configure(encoderFormat(w, h, bitrate, fps, iframe), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    break;
                } catch (Exception e) {
                    Ln.w("encoder refused " + w + "x" + h + ": " + e);
                    codec.reset();
                }
            } else {
                Ln.w("encoder does not list " + w + "x" + h);
            }
            int longSide = Math.max(w, h);
            while (step < downsize.length && downsize[step] >= longSide) step++;
            if (step >= downsize.length) throw new IllegalStateException("no frame size the encoder accepts (last " + w + "x" + h + ")");
            capture.setLongSideCap(downsize[step++]);
        }
        Surface input = codec.createInputSurface();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // a vendor key set in the format is silently ignored when the encoder does not list it
            StringBuilder vp = new StringBuilder();
            for (String k : codec.getSupportedVendorParameters()) {
                if (k.contains("latency") || k.contains("ref") || k.contains("ltr") || k.contains("layer") || k.contains("bframe")
                        || k.contains("b-frame") || k.contains("hier")) vp.append(' ').append(k);
            }
            Ln.i("encoder vendor keys (latency, references, layers):" + (vp.length() == 0 ? " none" : vp));
        }
        codec.start();
        capture.start(input);
        Ln.i("encoder " + codec.getName() + " " + size.getWidth() + "x" + size.getHeight() + " @" + bitrate + " bps, iframe " + iframe + " s");
        } catch (Throwable t) {
            cleanup(codec, capture, audio, wifiLock);
            throw t;
        }

        byte[] initMsg = null;
        Socket sock = null;
        DataOutputStream out = null;
        long lastConnectTry = 0, slot = -1, pts0 = -1, frames = 0, sent = 0, t0 = System.currentTimeMillis();
        long frameUs = 1_000_000L / fps;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        // Congestion control: the TV player runs without a buffer, so late frames cause stutter.
        // Frames older than MAX_AGE_MS are dropped; then wait for a fresh key frame and lower the bitrate.
        final long MAX_AGE_MS = 150;
        boolean waitKey = false;
        long skipped = 0, lastCongestion = 0, lastRateChange = 0, lastSyncReq = 0;
        int curBitrate = bitrate * 6 / 10;
        final long runStart = System.currentTimeMillis();
        // per-stage timing, logged every 10 s next to the frame counters
        final Stats encodeTime = new Stats(), sendTime = new Stats();
        long lastMarkSlot = -1000, lastDisplayCheck = System.currentTimeMillis();

        final int myGeneration = generation;
        try {
            while (!stopRequested && myGeneration == generation) {
                // connect to the TV (retry every 1 s); on connect send init and force a key frame
                if (out == null && System.currentTimeMillis() - lastConnectTry > 1000) {
                    lastConnectTry = System.currentTimeMillis();
                    try {
                        Socket s = new Socket();
                        s.setTcpNoDelay(true);
                        s.setSendBufferSize(192 * 1024); // small kernel queue so congestion shows up immediately as frame age
                        s.connect(new InetSocketAddress(tv[0], Integer.parseInt(tv[1])), 2000);
                        sock = s;
                        liveSock = s;
                        watchSends();
                        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 20));
                        Ln.i("connected to TV " + tv[0] + ":" + tv[1]);
                        status = "CONNECTED " + tv[0];
                        startPingReader(s);
                        if (audio != null) audio.attach(); // route audio to the TV only while it is connected
                        if (initMsg != null) {
                            send(out, 0, initMsg);
                            liveOut = out;
                        }
                        Bundle b = new Bundle();
                        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        codec.setParameters(b);
                    } catch (IOException e) {
                        Ln.w("TV connect failed: " + e.getMessage());
                        status = "NO_TV " + tv[0] + ":" + tv[1] + " " + e.getMessage();
                        if (audio != null) audio.detach(); // no receiver: keep audio on the device
                    }
                }

                // a rotated phone screen changes the display size; the virtual display keeps the old one, so the picture
                // would stay small inside the old frame until the stream restarts
                if (System.currentTimeMillis() - lastDisplayCheck > 500) {
                    lastDisplayCheck = System.currentTimeMillis();
                    if (capture.displaysChanged()) throw new DisplayChanged();
                }

                if (keyRequested) {
                    keyRequested = false;
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastSyncReq > 300) {
                        lastSyncReq = nowMs;
                        Bundle b = new Bundle();
                        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        codec.setParameters(b);
                    }
                }
                int idx = codec.dequeueOutputBuffer(info, 100_000);
                if (idx < 0) continue;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) encodeTime.add(System.nanoTime() / 1000 - info.presentationTimeUs);
                ByteBuffer bb = codec.getOutputBuffer(idx);
                byte[] data = Fmp4.toArray(bb, info.offset, info.size);
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;

                byte[] msg;
                int type;
                boolean key = false;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    Fmp4.Init init = Fmp4.init(data, size.getWidth(), size.getHeight(), audio == null ? null : acodec);
                    byte[] cs = (init.codec + "\n").getBytes("US-ASCII");
                    initMsg = new byte[cs.length + init.data.length];
                    System.arraycopy(cs, 0, initMsg, 0, cs.length);
                    System.arraycopy(init.data, 0, initMsg, cs.length, init.data.length);
                    Ln.i("init " + init.codec);
                    msg = initMsg;
                    type = 0;
                } else {
                    key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (pts0 < 0) {
                        pts0 = info.presentationTimeUs;
                        videoPts0Us = pts0;
                    }
                    // quantize timestamps to the frame grid to avoid tiny gaps in the MSE buffer
                    long s = Math.round((info.presentationTimeUs - pts0) / (double) frameUs);
                    if (s <= slot) s = slot + 1;
                    slot = s;
                    msg = mux.fragment(data, 0, data.length, slot * frameUs, (int) frameUs, key);
                    type = key ? 1 : 2;
                    frames++;
                }
                boolean drop = false;
                if (type != 0 && out != null) {
                    long nowMs = System.currentTimeMillis();
                    long ageMs = (System.nanoTime() / 1000 - info.presentationTimeUs) / 1000;
                    if (ageMs > MAX_AGE_MS) {
                        drop = true;
                        if (!waitKey) {
                            waitKey = true;
                            lastCongestion = nowMs;
                            if (nowMs - lastRateChange > 1000 && curBitrate > 3_000_000) { // congestion: back off bitrate
                                curBitrate = Math.max(3_000_000, curBitrate * 7 / 10);
                                lastRateChange = nowMs;
                                Bundle b = new Bundle();
                                b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, curBitrate);
                                codec.setParameters(b);
                                Ln.i("congestion (frame age " + ageMs + " ms), bitrate " + curBitrate / 1000 + " kb/s");
                            }
                        }
                    } else if (waitKey && !key) {
                        drop = true; // decoding deltas without the key frame would show garbage
                    } else {
                        waitKey = false;
                    }
                    if (drop) {
                        skipped++;
                        if (nowMs - lastSyncReq > 300) { // request a fresh key frame so playback jumps to the present
                            lastSyncReq = nowMs;
                            Bundle b = new Bundle();
                            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                            codec.setParameters(b);
                        }
                    } else if (curBitrate < bitrate && nowMs - lastCongestion > 5000
                            && nowMs - lastRateChange > (nowMs - runStart < 20_000 ? 2000 : 5000)) { // ramp up faster right after start
                        curBitrate = Math.min(bitrate, curBitrate * 6 / 5);
                        lastRateChange = nowMs;
                        Bundle b = new Bundle();
                        b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, curBitrate);
                        codec.setParameters(b);
                        Ln.i("network stable, bitrate " + curBitrate / 1000 + " kb/s");
                    }
                }
                if (out != null && !drop) {
                    try {
                        long sendStart = System.nanoTime();
                        send(out, type, msg);
                        if (type != 0) sendTime.add((System.nanoTime() - sendStart) / 1000);
                        if (type == 0) liveOut = out;
                        else sent++;
                        // timestamp: frame at media time slot*frameUs showed new content at capWall (device clock), sent now.
                        // Only frames with a new source buffer: a repeated frame carries an old picture.
                        long srcNs = type != 0 && slot - lastMarkSlot >= 4 ? capture.sourceTimeNs(info.presentationTimeUs) : 0;
                        if (srcNs > 0) {
                            lastMarkSlot = slot;
                            long nowMs = System.currentTimeMillis();
                            long capWall = nowMs - (System.nanoTime() - srcNs) / 1_000_000;
                            send(out, 6, java.nio.ByteBuffer.allocate(24).putLong(slot * frameUs).putLong(capWall).putLong(nowMs).array());
                        }
                    } catch (IOException e) {
                        Ln.w("TV send failed: " + e.getMessage());
                        try { sock.close(); } catch (IOException ignored) { }
                        out = null;
                        liveOut = null;
                        if (audio != null) audio.detach(); // TV gone: route audio back to the device
                    }
                }
                if (frames > 0 && frames % fps == 0 && out != null) {
                    double el = (System.currentTimeMillis() - t0) / 1000.0;
                    status = String.format(java.util.Locale.ROOT, "STREAMING %s %dx%d %.1f %d %d", layout, size.getWidth(), size.getHeight(),
                            frames / el, sent, wifiLock != null ? 1 : 0);
                }
                if (frames > 0 && frames % (fps * 10) == 0) {
                    double el = (System.currentTimeMillis() - t0) / 1000.0;
                    Ln.i(String.format("frames %d (sent %d, dropped on congestion %d) in %.0f s = %.1f fps, bitrate %d kb/s",
                            frames, sent, skipped, el, frames / el, curBitrate / 1000));
                    Ln.i("timing ms p50/p95/max: source->render " + capture.latch.takeLine() + ", render->dequeued " + encodeTime.takeLine()
                            + ", send " + sendTime.takeLine() + "; skipped source buffers " + capture.skippedBuffers
                            + (capture.badTimestamps > 0 ? ", bad source timestamps " + capture.badTimestamps : ""));
                }
            }
        } finally {
            liveOut = null;
            if (out != null) {
                try {
                    // tell the TV to show a spinner instead of the idle screen (layout change or automatic retry after an error)
                    if (switching || !stopRequested) send(out, 4, new byte[0]);
                } catch (IOException ignored) {
                }
            }
            if (sock != null) {
                if (liveSock == sock) liveSock = null;
                closeQuietly(sock);
            }
            cleanup(codec, capture, audio, wifiLock);
        }
    }

    private static MediaFormat encoderFormat(int w, int h, int bitrate, int fps, int iframe) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC);
        format.setInteger(MediaFormat.KEY_WIDTH, w);
        format.setInteger(MediaFormat.KEY_HEIGHT, h);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 6 / 10); // start low and ramp up as the Wi-Fi link proves stable
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframe);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        format.setInteger(MediaFormat.KEY_LATENCY, 1);
        format.setInteger("vendor.qti-ext-enc-low-latency.enable", 1); // Qualcomm vendor low-latency encoder mode
        // No B-frames, so TV decoders never reorder (Amlogic HEVC needs a single reference frame, per moonlight-android's
        // MediaCodecHelper). The stream already had none: P-frames only, 1 reference, no reordering (c2.qti.hevc.encoder
        // on the Thor, checked 2026-10-14); this keeps an encoder default from adding them.
        if (android.os.Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        return format;
    }

    /** "1920x1080" -> {1920, 1080}; anything else -> {0, 0}. */
    private static int[] wxh(String s) {
        String[] p = s.split("x");
        try {
            return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (Exception e) {
            return new int[] {0, 0};
        }
    }

    /** Releases everything even if a step throws; the hardware encoder goes first since only one instance of this size is available. */
    private static void cleanup(MediaCodec codec, DualCapture capture, ThorAudio audio, Object wifiLock) {
        if (codec != null) {
            try { codec.stop(); } catch (Throwable e) { Ln.w("codec stop: " + e); }
            try { codec.release(); } catch (Throwable e) { Ln.w("codec release: " + e); }
        }
        try { capture.stop(); } catch (Throwable e) { Ln.w("capture stop: " + e); }
        if (audio != null) {
            try { audio.stop(); } catch (Throwable e) { Ln.w("audio stop: " + e); }
        }
        if (wifiLock != null) {
            try { ((WifiManager.WifiLock) wifiLock).release(); } catch (Throwable e) { Ln.w("wifi lock: " + e); }
        }
    }

    /** Reads clock pings (type 8) and key frame requests (type 10) from the TV; replies to pings with device time (type 9). */
    private static void startPingReader(final Socket s) {
        Thread t = new Thread(() -> {
            try {
                java.io.DataInputStream in = new java.io.DataInputStream(s.getInputStream());
                DataOutputStream o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64));
                while (true) {
                    int len = in.readInt();
                    // the TV only sends small messages; a bad length used to throw NegativeArraySizeException (0) or
                    // allocate up to 2 GB
                    if (len < 1 || len > 1 << 16) throw new IOException("bad message length " + len);
                    int type = in.readUnsignedByte();
                    byte[] p = new byte[len - 1];
                    in.readFully(p);
                    if (type == 10) keyRequested = true;
                    if (type == 8 && p.length == 8) {
                        byte[] r = java.nio.ByteBuffer.allocate(16).put(p).putLong(System.currentTimeMillis()).array();
                        send(o, 9, r);
                    }
                }
            } catch (IOException e) {
                // socket closed
            }
        }, "DuoBeamClock");
        t.setDaemon(true);
        t.start();
    }

    private static void send(DataOutputStream out, int type, byte[] payload) throws IOException {
        synchronized (SEND_LOCK) { // video and audio are sent from different threads; messages must not interleave
            sendSince = System.currentTimeMillis();
            try {
                out.writeInt(payload.length + 1);
                out.writeByte(type);
                out.write(payload);
                out.flush();
            } finally {
                sendSince = 0;
            }
        }
    }

    // A TV that vanished without closing the connection (power cut, Wi-Fi gone) leaves send() blocked once the socket
    // buffer is full, and nothing else ever unblocked it. Closing the socket makes the write fail, so the stream
    // reconnects; Stop does the same.
    private static volatile Socket liveSock;
    private static volatile long sendSince;
    private static Thread sendWatchdog;

    private static synchronized void watchSends() {
        if (sendWatchdog != null) return;
        sendWatchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                long since = sendSince;
                Socket s = liveSock;
                if (since != 0 && s != null && System.currentTimeMillis() - since > 5000) {
                    Ln.w("send blocked for " + (System.currentTimeMillis() - since) / 1000 + " s, closing the connection to the TV");
                    closeQuietly(s);
                }
            }
        }, "DuoBeamSendWatch");
        sendWatchdog.setDaemon(true);
        sendWatchdog.start();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
