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
    private int inFlight;
    private long lastOutputAt;
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

    private VideoOut(MediaCodec codec, HandlerThread thread, int width, int height, byte[] csd, Listener l) {
        this.codec = codec;
        this.thread = thread;
        this.width = width;
        this.height = height;
        this.csd = csd;
        this.listener = l;
    }

    /** Null if no decoder on this device can handle the size (message for the user in error[0]). */
    static VideoOut create(Mp4.Init init, Surface surface, Listener l, String[] error) {
        String name = pickDecoder(init.width, init.height);
        if (name == null) {
            error[0] = "no HEVC decoder for " + init.width + "x" + init.height;
            return null;
        }
        HandlerThread ht = new HandlerThread("DuoBeamTV-video");
        ht.start();
        try {
            MediaCodec c = MediaCodec.createByCodecName(name);
            VideoOut v = new VideoOut(c, ht, init.width, init.height, init.csd, l);
            c.setCallback(v.callback, new Handler(ht.getLooper()));
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, init.width, init.height);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(init.csd));
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
            f.setInteger("vdec-lowlatency", 1); // some vendor decoders (Amlogic/MTK) read this
            c.configure(f, surface, null, 0);
            c.setOnFrameRenderedListener((codec, pts, renderNs) -> v.onRendered(pts, renderNs), new Handler(ht.getLooper()));
            c.start();
            Log.i(TAG, "decoder " + name + " " + init.width + "x" + init.height);
            return v;
        } catch (Exception e) {
            error[0] = "decoder: " + e;
            Log.w(TAG, "decoder", e);
            ht.quitSafely();
            return null;
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
        Log.i(TAG, "decoder limit " + (r.isEmpty() ? "unknown" : r));
        return maxSize = r;
    }

    private static String pickDecoder(int w, int h) {
        String fallback = null;
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
                boolean sw = ci.getName().startsWith("OMX.google.") || ci.getName().startsWith("c2.android.");
                if (!sw) return ci.getName(); // hardware first
                if (fallback == null) fallback = ci.getName();
            }
        }
        return fallback;
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

    // under lock
    private void feed() {
        int cap = maxInFlight;
        // safety: decoder silent for a while (e.g. it swallowed a frame) - don't stay blocked on the count
        if (cap > 0 && inFlight >= cap && System.currentTimeMillis() - lastOutputAt > 200) inFlight = 0;
        while (running && !freeInputs.isEmpty() && !queue.isEmpty() && (cap <= 0 || inFlight < cap)) {
            int idx = freeInputs.poll();
            Frame f = queue.poll();
            try {
                ByteBuffer in = codec.getInputBuffer(idx);
                in.clear();
                in.put(f.data);
                codec.queueInputBuffer(idx, 0, f.data.length, f.pts, f.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                inFlight++;
                long now = System.nanoTime();
                waitTime.add((now - f.arrivedNs) / 1e6);
                if (queuedAt.size() > 64) queuedAt.clear();
                queuedAt.put(f.pts, now);
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
