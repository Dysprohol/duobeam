package com.duobeam.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import java.util.concurrent.LinkedBlockingDeque;

/**
 * Game sound as raw PCM (S16LE, 48 kHz, stereo) - no decoder delay. A small queue (about 64 ms) evens out Wi-Fi jitter;
 * if it grows too long the oldest blocks are dropped so sound stays close to the picture.
 */
final class AudioOut {

    private static final int RATE = 48000, PREFILL = 3, MAX = 12;
    private final AudioTrack track;
    private final LinkedBlockingDeque<byte[]> queue = new LinkedBlockingDeque<>();
    private final Thread thread;
    private volatile boolean running = true;
    volatile int underruns, drops;

    AudioOut() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(min, RATE * 4 / 20)) // ~50 ms
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        track = b.build();
        thread = new Thread(this::loop, "DuoBeamTV-audio");
        thread.start();
    }

    void block(byte[] pcm) {
        queue.offer(pcm);
        if (queue.size() > MAX) {
            while (queue.size() > PREFILL) queue.poll();
            drops++;
        }
    }

    private void loop() {
        boolean playing = false;
        try {
            while (running) {
                if (!playing) {
                    if (queue.size() < PREFILL) {
                        Thread.sleep(5);
                        continue;
                    }
                    track.play();
                    playing = true;
                }
                byte[] pcm = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (pcm == null) { // nothing came - refill before playing on
                    underruns++;
                    track.pause();
                    track.flush();
                    playing = false;
                    continue;
                }
                track.write(pcm, 0, pcm.length);
            }
        } catch (InterruptedException ignored) {
        }
    }

    void release() {
        running = false;
        thread.interrupt();
        try {
            thread.join(300);
        } catch (InterruptedException ignored) {
        }
        try {
            track.stop();
        } catch (Exception ignored) {
        }
        track.release();
    }
}
