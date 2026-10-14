package com.duobeam.tv;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Hardware HEVC decoding straight to a Surface, shown as soon as decoded (no pacing).
 * Async MediaCodec: the decoder says when it has room; frames wait in a short queue, so a decoder that frees its
 * input slots unevenly (e.g. MStar TV chips) does not cost frames. Only a real backlog (> MAX_QUEUE) is dropped,
 * and then the sender is asked for a fresh keyframe.
 */
final class VideoOut {

    interface Listener {
        void onFrameShown(long ptsUs);

        /** The frame reached the display surface at renderNs (System.nanoTime base); later than onFrameShown. */
        void onFrameRendered(long ptsUs, long renderNs);

        /** Frames had to be dropped - ask the sender for a fresh keyframe instead of waiting up to 2 s. */
        void onNeedKeyframe();
    }

    private static final String TAG = "DuoBeamTV";
    private static final int MAX_QUEUE = 20; // ~1/3 s at 60 fps
    // mode (up/down on the remote): how much waiting backlog is tolerated before jumping to a fresh keyframe
    // lowest delay: >3 frames for 0.5 s | balanced: >8 frames for 1.5 s | smoothest: only a huge backlog
    static volatile int backlogFrames = 3;
    static volatile long backlogMs = 500;
    // frames handed to the decoder but not shown yet; some TV decoders work through everything they are given
    // before showing the newest frame, so fewer in flight = less delay (0 = no limit)
    static volatile int maxInFlight = 2;
    private static final int MAX_CAP = 16;
    private int inFlight, capBoost;
    private long lastSilenceAt;
    private final ArrayDeque<Long> silences = new ArrayDeque<>();
    private volatile long lastOutputAt = System.currentTimeMillis(), lastInputAt;
    private volatile boolean failed;
    private long backlogSince;

    private static final class Frame {
        final byte[] data;
        final long pts;
        final boolean key;
        final long arrivedNs = System.nanoTime();

        Frame(byte[] data, long pts, boolean key) {
            this.data = data;
            this.pts = pts;
            this.key = key;
        }
    }

    // per-stage timing on the TV: network -> decoder input (queue wait), decoder input -> output, output -> on the surface
    final Stats waitTime = new Stats(), decodeTime = new Stats(), renderTime = new Stats();
    private final java.util.HashMap<Long, Long> queuedAt = new java.util.HashMap<>(), outputAt = new java.util.HashMap<>();
    volatile boolean renderCallbacks; // some decoders never report rendered frames
    volatile int rendered; // frames that reached the screen; shown - rendered = dropped by the compositor

    private final MediaCodec codec;
    private final HandlerThread thread;
    private final Listener listener;
    private final Object lock = new Object();
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final ArrayDeque<Integer> freeInputs = new ArrayDeque<>();
    private volatile boolean running = true;
    private boolean waitKey = true;
    final int width, height;
    private final byte[] csd;
    volatile int shown, dropped, maxQueue, catchUps;

    private VideoOut(MediaCodec codec, HandlerThread thread, int width, int height, byte[] csd, Listener l, String name, int level,
                     boolean lowLatencyFeature) {
        this.codec = codec;
        this.thread = thread;
        this.width = width;
        this.height = height;
        this.csd = csd;
        this.listener = l;
        this.name = name;
        this.level = level;
        this.lowLatencyFeature = lowLatencyFeature;
    }

    // Decoder setup levels, tried from the first one the caller asks for; a level that fails to configure, or a decoder
    // that then stalls (see stalled()), moves on to the next. Same order as Moonlight's MediaCodecHelper: vendor
    // low-latency keys can stop a decoder's output entirely ("vdec-lowlatency" on an Amlogic Xiaomi box) and
    // KEY_LOW_LATENCY made c2.amlogic HEVC a slideshow on Android TV 14 (moonlight-android #1584).
    static final int LEVEL_FULL = 0, LEVEL_STANDARD = 1, LEVEL_PLAIN = 2;
    final String name;
    final int level;
    private final boolean lowLatencyFeature;

    /** Null if no decoder on this device can handle the size (message for the user in error[0]). */
    static VideoOut create(Mp4.Init init, Surface surface, Listener l, String[] error, int firstLevel) {
        MediaCodecInfo info = pickDecoder(init.width, init.height);
        if (info == null) {
            error[0] = "no HEVC decoder for " + init.width + "x" + init.height;
            return null;
        }
        boolean llFeature = hasLowLatencyFeature(info);
        Exception last = null;
        for (int level = Math.max(LEVEL_FULL, Math.min(LEVEL_PLAIN, firstLevel)); level <= LEVEL_PLAIN; level++) {
            HandlerThread ht = new HandlerThread("DuoBeamTV-video");
            ht.start();
            MediaCodec c = null;
            try {
                c = MediaCodec.createByCodecName(info.getName());
                VideoOut v = new VideoOut(c, ht, init.width, init.height, init.csd, l, info.getName(), level, llFeature);
                c.setCallback(v.callback, new Handler(ht.getLooper()));
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, init.width, init.height);
                f.setByteBuffer("csd-0", ByteBuffer.wrap(init.csd));
                applyLevel(f, info.getName(), level);
                c.configure(f, surface, null, 0);
                c.setOnFrameRenderedListener((codec, pts, renderNs) -> v.onRendered(pts, renderNs), new Handler(ht.getLooper()));
                c.start();
                Log.i(TAG, "decoder " + info.getName() + " " + init.width + "x" + init.height + " setup level " + level
                        + (llFeature ? " (FEATURE_LowLatency)" : ""));
                return v;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "decoder " + info.getName() + " level " + level + " failed: " + e);
                if (c != null) c.release();
                ht.quitSafely();
            }
        }
        error[0] = "decoder: " + last;
        return null;
    }

    private static void applyLevel(MediaFormat f, String name, int level) {
        if (level >= LEVEL_PLAIN) return;
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        // Moonlight stops here when the decoder reports FEATURE_LowLatency. The Xiaomi MiTV-MOOQ0 decoder reports it,
        // yet side by side (3160x1080) it was slower with only this key: 163-167 ms, 50 ms decoding (2026-10-14),
        // against 80-92 ms with the vendor key and realtime priority in 0.8.4, so level 0 always adds them.
        if (level >= LEVEL_STANDARD) return;
        f.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("amlogic")) {
            f.setInteger("vendor.low-latency.enable", 1);
        } else if (n.contains("mtk") || n.contains("mediatek") || n.startsWith("omx.ms.") || n.contains("amazon")) {
            f.setInteger("vdec-lowlatency", 1); // MediaTek, MStar (Xiaomi MiTV-MOOQ0 plays with it) and Fire OS
        } else if (n.contains("qcom") || n.contains("qti")) {
            f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
            f.setInteger("vendor.qti-ext-dec-picture-order.enable", 1);
        } else if (n.contains("exynos")) {
            f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1);
        }
    }

    private static boolean hasLowLatencyFeature(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            return info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
        } catch (Exception e) {
            return false;
        }
    }

    private static volatile String maxSize;

    /**
     * Largest frame "WxH" the hardware HEVC decoder takes (widest width, tallest height at that width), reported to
     * the sender so it scales bigger screens down instead of sending a stream this TV cannot decode; "" if unknown.
     */
    static String maxSize() {
        if (maxSize != null) return maxSize;
        String r = "";
        for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (ci.isEncoder() || ci.getName().startsWith("OMX.google.") || ci.getName().startsWith("c2.android.")) continue;
            for (String t : ci.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) continue;
                try {
                    MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(t).getVideoCapabilities();
                    int w = vc.getSupportedWidths().getUpper();
                    r = w + "x" + vc.getSupportedHeightsFor(w).getUpper();
                } catch (Exception ignored) {
                }
                if (!r.isEmpty()) break;
            }
            if (!r.isEmpty()) break;
        }
        Log.i(TAG, "decoder limit " + (r.isEmpty() ? "unknown" : r) + ", max pixels " + maxPixels());
        return maxSize = r;
    }

    private static volatile long maxPixels = -1;

    /**
     * Largest frame area (luma samples) of the highest HEVC Main level the hardware decoder lists, from the H.265
     * level table; 0 if unknown. Level 4.1, the usual limit of 1080p-only devices, allows 2,228,224: less than
     * 3160x1080 (3,412,800) or a 1080x2340 phone screen, which a box limit alone does not catch.
     */
    static long maxPixels() {
        if (maxPixels >= 0) return maxPixels;
        int best = 0;
        for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (ci.isEncoder() || ci.getName().startsWith("OMX.google.") || ci.getName().startsWith("c2.android.")) continue;
            for (String t : ci.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) continue;
                try {
                    for (MediaCodecInfo.CodecProfileLevel pl : ci.getCapabilitiesForType(t).profileLevels) {
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) best = Math.max(best, pl.level);
                    }
                } catch (Exception ignored) {
                }
            }
            if (best > 0) break;
        }
        // level constants are one bit per level and tier, in increasing order
        long r;
        if (best == 0) r = 0;
        else if (best <= MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3) r = 552_960;
        else if (best <= MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31) r = 983_040;
        else if (best <= MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41) r = 2_228_224;
        else if (best <= MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52) r = 8_912_896;
        else r = 35_651_584;
        return maxPixels = r;
    }

    /** Hardware decoder that reports low latency first, then any hardware one, then software. */
    private static MediaCodecInfo pickDecoder(int w, int h) {
        MediaCodecInfo hw = null, sw = null;
        for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (ci.isEncoder()) continue;
            for (String t : ci.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) continue;
                try {
                    MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(t).getVideoCapabilities();
                    if (vc == null || !vc.isSizeSupported(w, h)) continue;
                } catch (Exception e) {
                    continue;
                }
                boolean soft = ci.getName().startsWith("OMX.google.") || ci.getName().startsWith("c2.android.");
                if (soft) {
                    if (sw == null) sw = ci;
                } else if (hasLowLatencyFeature(ci)) {
                    return ci;
                } else if (hw == null) {
                    hw = ci;
                }
            }
        }
        return hw != null ? hw : sw;
    }

    private final MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(MediaCodec c, int index) {
            synchronized (lock) {
                freeInputs.add(index);
                feed();
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec c, int index, MediaCodec.BufferInfo info) {
            if (!running) return;
            synchronized (lock) {
                if (inFlight > 0) inFlight--;
                lastOutputAt = System.currentTimeMillis();
                long now = System.nanoTime();
                Long q = queuedAt.remove(info.presentationTimeUs);
                if (q != null) decodeTime.add((now - q) / 1e6);
                if (outputAt.size() > 64) outputAt.clear();
                outputAt.put(info.presentationTimeUs, now);
                feed();
            }
            try {
                // show now. A timed release (releaseOutputBuffer(index, System.nanoTime())) cut the meter from ~85 to ~44 ms but
                // not the real delay: camera on Thor + TV gave 133 ms before and 150-167 ms after (Xiaomi MiTV-MOOQ0,
                // measured 2026-10-12), so frames only waited further down the TV's video path, out of the meter's sight
                c.releaseOutputBuffer(index, true);
            } catch (Exception e) {
                return;
            }
            shown++;
            listener.onFrameShown(info.presentationTimeUs);
        }

        @Override
        public void onError(MediaCodec c, MediaCodec.CodecException e) {
            Log.w(TAG, "decoder error: " + e);
            failed = true; // stalled() reports it; the receiver then recreates the decoder
        }

        @Override
        public void onOutputFormatChanged(MediaCodec c, MediaFormat format) {
        }
    };

    /** One video frame (fMP4 fragment) from the network. */
    void frame(byte[] b, int off, int len, boolean key) {
        long t = Mp4.tfdt(b, off, len);
        int[] m = Mp4.mdat(b, off, len);
        if (t < 0 || m == null) return;
        boolean askKey = false;
        synchronized (lock) {
            if (waitKey && !key) {
                dropped++;
                return;
            }
            waitKey = false;
            queue.add(new Frame(Mp4.annexB(b, m[0], m[1], key ? csd : null), t, key));
            if (queue.size() > maxQueue) maxQueue = queue.size();
            // A decoder hiccup leaves a backlog that never shrinks on its own (it then decodes exactly as fast as
            // frames arrive) - that is pure added delay. Backlog held for 0.5 s or a huge one: jump to a fresh keyframe.
            long now = System.currentTimeMillis();
            if (queue.size() > backlogFrames) {
                if (backlogSince == 0) backlogSince = now;
            } else {
                backlogSince = 0;
            }
            if (queue.size() > MAX_QUEUE || (backlogSince != 0 && now - backlogSince > backlogMs)) {
                dropped += queue.size();
                queue.clear();
                waitKey = true;
                askKey = true;
                backlogSince = 0;
                catchUps++;
            } else {
                feed();
            }
        }
        if (askKey) listener.onNeedKeyframe();
    }

    /** Current limit of frames inside the decoder (0 = none). */
    int cap() {
        return maxInFlight > 0 ? Math.min(MAX_CAP, maxInFlight + capBoost) : 0;
    }

    /**
     * Frames go in but nothing comes out for 1.5 s, or the decoder reported an error: the receiver then recreates it
     * at the next setup level and asks for a keyframe.
     */
    boolean stalled() {
        long now = System.currentTimeMillis();
        return failed || (lastInputAt != 0 && now - lastInputAt < 1000 && now - lastOutputAt > 1500) || slideshow(now);
    }

    private long rateAt;
    private int rateIn, rateOut, slowWindows;
    private volatile int queuedTotal;

    // Frames go in at ~60 per second but fewer than 20 come out, for two 2-second windows in a row: the decoder
    // runs but far too slowly, the "slideshow" KEY_LOW_LATENCY gave c2.amlogic HEVC on Android TV 14
    // (moonlight-android #1584). Counts as a stall, so the next setup level drops the low-latency keys.
    private boolean slideshow(long now) {
        if (rateAt == 0) {
            rateAt = now;
            rateIn = queuedTotal;
            rateOut = shown;
            return false;
        }
        if (now - rateAt < 2000) return false;
        double secs = (now - rateAt) / 1000.0, in = (queuedTotal - rateIn) / secs, out = (shown - rateOut) / secs;
        rateAt = now;
        rateIn = queuedTotal;
        rateOut = shown;
        slowWindows = in >= 45 && out < 20 ? slowWindows + 1 : 0;
        return slowWindows >= 2;
    }

    // under lock
    private void feed() {
        int cap = cap();
        long now = System.currentTimeMillis();
        // Decoder silent for 200 ms with the limit reached. Once in a while that is a hiccup; 3 times within a second
        // means it holds more frames than allowed before giving one back, and with a fixed limit it got one frame per
        // 200 ms, a nearly frozen picture. Then the limit goes up, and back down after 10 s without such a silence:
        // raising it on every hiccup kept 4 frames in the Xiaomi MiTV-MOOQ0 decoder, 210 ms instead of ~90 (2026-10-14).
        if (cap > 0 && inFlight >= cap && now - Math.max(lastOutputAt, lastSilenceAt) > 200) {
            lastSilenceAt = now;
            silences.add(now);
            while (now - silences.peekFirst() > 1000) silences.poll();
            if (silences.size() >= 3 && cap < MAX_CAP) {
                capBoost++;
                silences.clear();
                Log.i(TAG, "decoder " + name + " keeps holding " + inFlight + " frames without output, limit now " + cap());
            }
            inFlight = 0;
            cap = cap();
        } else if (capBoost > 0 && now - lastSilenceAt > 10_000) {
            capBoost--;
            lastSilenceAt = now;
            Log.i(TAG, "decoder " + name + " no longer holds frames, limit now " + cap());
            cap = cap();
        }
        while (running && !freeInputs.isEmpty() && !queue.isEmpty() && (cap <= 0 || inFlight < cap)) {
            int idx = freeInputs.poll();
            Frame f = queue.poll();
            try {
                ByteBuffer in = codec.getInputBuffer(idx);
                in.clear();
                in.put(f.data);
                codec.queueInputBuffer(idx, 0, f.data.length, f.pts, f.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                inFlight++;
                queuedTotal++;
                lastInputAt = System.currentTimeMillis();
                long nowNs = System.nanoTime();
                waitTime.add((nowNs - f.arrivedNs) / 1e6);
                if (queuedAt.size() > 64) queuedAt.clear();
                queuedAt.put(f.pts, nowNs);
            } catch (Exception e) {
                Log.w(TAG, "queue: " + e);
            }
        }
    }

    private void onRendered(long ptsUs, long renderNs) {
        if (!running) return;
        renderCallbacks = true;
        rendered++;
        Long o;
        synchronized (lock) {
            o = outputAt.remove(ptsUs);
        }
        if (o != null) renderTime.add((renderNs - o) / 1e6);
        listener.onFrameRendered(ptsUs, renderNs);
    }

    void release() {
        running = false;
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        try {
            codec.release();
        } catch (Exception ignored) {
        }
        thread.quitSafely();
    }
}
