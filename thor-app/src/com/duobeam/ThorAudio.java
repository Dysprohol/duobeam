package com.duobeam;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Captures device playback audio (audio policy approach as in scrcpy --audio-source=playback, Apache-2.0),
 * encodes it to AAC-LC, Opus or raw PCM at 48 kHz stereo, and emits frames tagged with their sample position.
 * keepOnDevice = true: audio also plays on the device (LOOP_BACK_RENDER); false: TV only (LOOP_BACK).
 * The timeline is continuous: gaps in captured data are filled with silence and excess data is dropped,
 * so audio does not drift from video and the TV player never stalls waiting for audio.
 */
final class ThorAudio {

    static final int RATE = 48000, CHANNELS = 2;
    private static final int BLOCK = 1024; // samples per input block
    private static final int BLOCK_BYTES = BLOCK * CHANNELS * 2;

    /** Samples per output frame: AAC 1024, Opus 960 (20 ms). */
    static int frameSamples(boolean opus) {
        return opus ? 960 : 1024;
    }

    interface Sink {
        /** Raw AAC frame (no ADTS), Opus packet or PCM block; pos = index of its first sample since capture start. */
        void frame(byte[] aac, long pos);
    }

    // capture is active only while a TV is connected (attach/detach): TV-only routing mutes the device,
    // so without a receiver audio must stay on the device
    private final boolean keepOnDevice;
    private volatile AudioRecord rec;
    private Object policy;
    private final long startNs;
    final boolean opus, pcm;
    private MediaCodec enc;
    private Sink sink;
    private Thread thread;
    private volatile boolean stop;

    private ThorAudio(boolean keepOnDevice, String codec) {
        this.keepOnDevice = keepOnDevice;
        this.opus = "opus".equals(codec);
        this.pcm = "pcm".equals(codec);
        this.startNs = System.nanoTime();
    }

    /** System.nanoTime corresponding to sample 0. */
    long startNs() {
        return startNs;
    }

    /**
     * Starts capture, or returns null if it is unavailable (the stream then runs without audio).
     * Capture stays on until detach(): each routing change causes a short audio gap on the device.
     * codec: "aac", "opus" or "pcm".
     */
    static ThorAudio open(boolean keepOnDevice, String codec) {
        ThorAudio a = new ThorAudio(keepOnDevice, codec);
        return a.attach() ? a : null;
    }

    /** Starts capture (TV connected). */
    synchronized boolean attach() {
        if (rec != null) return true;
        try {
            Object[] pr = createRecord(keepOnDevice);
            AudioRecord r = (AudioRecord) pr[0];
            policy = pr[1];
            r.startRecording();
            rec = r;
            Ln.i("audio: capture started" + (keepOnDevice ? " (TV and device)" : " (TV only)"));
            return true;
        } catch (Throwable e) {
            Ln.w("audio capture unavailable: " + e);
            unregister();
            return false;
        }
    }

    /** Stops capture and returns audio to the device (no TV / stream ended). */
    synchronized void detach() {
        AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
        if (policy != null) {
            unregister();
            Ln.i("audio: capture stopped, audio back on device");
        }
    }

    // the audio policy must be unregistered, otherwise device audio stays rerouted to nowhere
    private void unregister() {
        Object p = policy;
        policy = null;
        if (p == null) return;
        try {
            Method m = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", p.getClass());
            m.setAccessible(true);
            m.invoke(null, p);
        } catch (Throwable e) {
            try {
                AudioManager am = (AudioManager) FakeContext.get().getSystemService(Context.AUDIO_SERVICE);
                Method m = AudioManager.class.getMethod("unregisterAudioPolicy", p.getClass());
                m.invoke(am, p);
            } catch (Throwable e2) {
                Ln.w("audio policy unregister: " + e2);
            }
        }
    }

    private static Object[] createRecord(boolean keepOnDevice) throws Exception {
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
        ruleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(ruleBuilder, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null));
        int matchUsage = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
        Method addMixRule = ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
        // games usually play as GAME or MEDIA (scrcpy only captures MEDIA)
        for (int usage : new int[] {AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN}) {
            addMixRule.invoke(ruleBuilder, matchUsage, new AudioAttributes.Builder().setUsage(usage).build());
        }
        Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
        AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, fmt);
        String flag = keepOnDevice ? "ROUTE_FLAG_LOOP_BACK_RENDER" : "ROUTE_FLAG_LOOP_BACK";
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, mixClass.getField(flag).getInt(null));
        Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(FakeContext.get());
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
        Object policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);
        Method register = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyClass);
        register.setAccessible(true);
        int result = (int) register.invoke(null, policy);
        if (result != 0) throw new IllegalStateException("registerAudioPolicy " + result);
        AudioRecord r = (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
        return new Object[] {r, policy};
    }

    void start(final Sink sink) throws Exception {
        this.sink = sink;
        if (pcm) { // raw samples, no encoder delay
            thread = new Thread(() -> loop(sink), "DuoBeamAudio");
            thread.start();
            Ln.i("audio: PCM S16LE " + RATE + " Hz");
            return;
        }
        String mime = opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
        MediaFormat f = MediaFormat.createAudioFormat(mime, RATE, CHANNELS);
        if (!opus) f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, opus ? 128_000 : 160_000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCK_BYTES);
        enc = MediaCodec.createEncoderByType(mime);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        thread = new Thread(() -> loop(sink), "DuoBeamAudio");
        thread.start();
        Ln.i("audio: " + enc.getName() + (opus ? " Opus " : " AAC-LC ") + RATE + " Hz");
    }

    private void loop(Sink sink) {
        byte[] pending = new byte[BLOCK_BYTES * 16];
        int have = 0;
        byte[] silence = new byte[BLOCK_BYTES];
        long fed = 0, out = 0, dropped = 0, filled = 0, lastLog = System.nanoTime();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!stop) {
                int r = 0;
                synchronized (this) { // non-blocking read; prevents detach() from releasing the recorder mid-read
                    AudioRecord cur = rec;
                    if (cur != null) r = cur.read(pending, have, pending.length - have, AudioRecord.READ_NON_BLOCKING);
                }
                if (r > 0) have += r;
                long due = (System.nanoTime() - startNs) * RATE / 1_000_000_000L; // samples expected by now according to the clock

                // captured data: feed full blocks to the encoder; drop blocks too far ahead of the clock (drift / burst)
                while (have >= BLOCK_BYTES) {
                    if (fed > due + 8 * BLOCK) {
                        dropped++;
                    } else if (!feed(pending, fed)) {
                        break; // encoder busy, retry next iteration
                    } else {
                        fed += BLOCK;
                    }
                    System.arraycopy(pending, BLOCK_BYTES, pending, 0, have - BLOCK_BYTES);
                    have -= BLOCK_BYTES;
                }
                // no data (e.g. nothing playing): pad with silence so the timeline keeps moving.
                // Only on a real gap (>~210 ms): capture delivers audio in bursts, so shorter gaps are just a late
                // burst and inserting silence there would make the audio choppy.
                if (fed < due - 10 * BLOCK && have < BLOCK_BYTES) {
                    while (fed < due - 2 * BLOCK) {
                        if (!feed(silence, fed)) break;
                        fed += BLOCK;
                        filled++;
                    }
                }

                int idx;
                while (enc != null && (idx = enc.dequeueOutputBuffer(info, 0)) >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        ByteBuffer bb = enc.getOutputBuffer(idx);
                        sink.frame(Fmp4.toArray(bb, info.offset, info.size), out * frameSamples(opus));
                        out++;
                    }
                    enc.releaseOutputBuffer(idx, false);
                }
                if (System.nanoTime() - lastLog > 30_000_000_000L) {
                    lastLog = System.nanoTime();
                    Ln.i("audio: frames " + (pcm ? pcmOut : out) + ", silence " + filled + ", dropped " + dropped);
                }
                Thread.sleep(4);
            }
        } catch (Throwable e) {
            if (!stop) Ln.w("audio loop: " + e);
        }
    }

    private long pcmOut;

    private boolean feed(byte[] block, long pos) {
        if (pcm) {
            byte[] copy = new byte[BLOCK_BYTES];
            System.arraycopy(block, 0, copy, 0, BLOCK_BYTES);
            sink.frame(copy, pos);
            pcmOut++;
            return true;
        }
        int idx = enc.dequeueInputBuffer(0);
        if (idx < 0) return false;
        ByteBuffer in = enc.getInputBuffer(idx);
        in.clear();
        in.put(block, 0, BLOCK_BYTES);
        enc.queueInputBuffer(idx, 0, BLOCK_BYTES, pos * 1_000_000L / RATE, 0);
        return true;
    }

    void stop() {
        stop = true;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        detach();
        if (enc != null) {
            try {
                enc.stop();
                enc.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
