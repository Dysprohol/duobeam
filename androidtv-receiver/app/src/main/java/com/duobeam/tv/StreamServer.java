package com.duobeam.tv;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Same network contract as the Samsung receiver:
 *   TCP :8201 - the sender pushes frames [len u32][type u8][payload]
 *     0 codec line + '\n' + fMP4 init, 1 keyframe, 2 frame, 4 switching layout, 5 audio, 6 frame timestamp, 9 clock reply
 *     (we send 8 = clock ping on the same socket, once a second)
 *   HTTP :8202 - /hello ("Find receiver" in the handheld app) and /status (is the stream arriving, which player)
 */
final class StreamServer {

    interface Listener {
        void onMessage(int type, byte[] payload);

        void onSourceChanged(boolean connected);
    }

    static final int IN_PORT = 8201, HTTP_PORT = 8202;
    private static final String TAG = "DuoBeamTV";
    private final Listener listener;
    private final String version;
    private volatile boolean running;
    private ServerSocket ingest, http;
    private volatile Socket source;
    private volatile DataOutputStream sourceOut;
    volatile long frames;
    volatile String extraStatus = ""; // latency meter fields appended to /status (JSON members with a leading comma)
    volatile String problem = ""; // what failed after the automatic fixes ("decoder"), shown by the handheld app
    // clock sync with the sender: offset = senderTime - tvTime, from the lowest-RTT ping of the last 15
    private final ArrayDeque<double[]> clock = new ArrayDeque<>();
    volatile double clockOffset = Double.NaN, clockRtt = Double.NaN;

    StreamServer(Listener listener, String version) {
        this.listener = listener;
        this.version = version;
    }

    void start() {
        running = true;
        new Thread(this::ingestLoop, "DuoBeamTV-ingest").start();
        new Thread(this::httpLoop, "DuoBeamTV-http").start();
        new Thread(this::pingLoop, "DuoBeamTV-ping").start();
    }

    void stop() {
        running = false;
        closeQuietly(ingest);
        closeQuietly(http);
        Socket s = source;
        if (s != null) closeQuietly(s);
    }

    boolean connected() {
        return source != null;
    }

    private void ingestLoop() {
        try (ServerSocket ss = new ServerSocket(IN_PORT)) {
            ss.setReuseAddress(true);
            ingest = ss;
            while (running) {
                Socket s = ss.accept();
                Socket old = source;
                if (old != null) closeQuietly(old); // newest sender wins (e.g. the handheld restarted the stream)
                new Thread(() -> serve(s), "DuoBeamTV-source").start();
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "ingest: " + e);
        }
    }

    private void serve(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(1 << 20);
            source = s;
            sourceOut = new DataOutputStream(s.getOutputStream());
            synchronized (clock) {
                clock.clear();
                clockOffset = Double.NaN;
            }
            listener.onSourceChanged(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 18));
            while (running) {
                int len = in.readInt();
                if (len <= 0 || len > 64 << 20) throw new IOException("bad frame length " + len);
                int type = in.readUnsignedByte();
                byte[] p = new byte[len - 1];
                in.readFully(p);
                if (type == 9 && p.length == 16) {
                    onClockReply(p);
                    continue;
                }
                if (type == 1 || type == 2) frames++;
                listener.onMessage(type, p);
            }
        } catch (IOException e) {
            // sender gone
        } finally {
            closeQuietly(s);
            if (source == s) {
                source = null;
                sourceOut = null;
                listener.onSourceChanged(false);
            }
        }
    }

    private void pingLoop() {
        while (running) {
            DataOutputStream o = sourceOut;
            if (o != null) {
                try {
                    synchronized (o) {
                        o.writeInt(9);
                        o.writeByte(8);
                        o.writeDouble(System.currentTimeMillis());
                        o.flush();
                    }
                } catch (IOException ignored) {
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Type 10: ask the sender for a keyframe now. */
    void requestKeyframe() {
        DataOutputStream o = sourceOut;
        if (o == null) return;
        try {
            synchronized (o) {
                o.writeInt(1);
                o.writeByte(10);
                o.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private void onClockReply(byte[] p) {
        ByteBuffer bb = ByteBuffer.wrap(p);
        double t0 = bb.getDouble();
        long thor = bb.getLong();
        double now = System.currentTimeMillis(), rtt = now - t0;
        synchronized (clock) {
            clock.addLast(new double[] {rtt, thor - (t0 + now) / 2});
            if (clock.size() > 15) clock.removeFirst();
            double[] best = null;
            for (double[] c : clock) if (best == null || c[0] < best[0]) best = c;
            clockRtt = best[0];
            clockOffset = best[1];
        }
    }

    private void httpLoop() {
        try (ServerSocket ss = new ServerSocket(HTTP_PORT)) {
            ss.setReuseAddress(true);
            http = ss;
            while (running) {
                Socket c = ss.accept();
                new Thread(() -> answer(c), "DuoBeamTV-http1").start();
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "http: " + e);
        }
    }

    private void answer(Socket c) {
        try {
            c.setSoTimeout(2000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line = r.readLine();
            String path = line != null && line.split(" ").length > 1 ? line.split(" ")[1] : "/";
            String body;
            int code = 200;
            boolean busy = source != null;
            // "player":"android" -> the handheld app sends audio as PCM (no decoder delay)
            if (path.startsWith("/hello")) {
                body = "{\"app\":\"DuoBeam\",\"role\":\"receiver\",\"version\":\"" + version + "\",\"platform\":\"androidtv\",\"ingestPort\":"
                        + IN_PORT + ",\"busy\":" + busy + ",\"player\":\"android\",\"maxVideo\":\"" + VideoOut.maxSize()
                        + "\",\"maxPixels\":" + VideoOut.maxPixels() + "}";
            } else if (path.startsWith("/status")) {
                // maxVideo early: the handheld app reads only the first 1 KB
                body = "{\"player\":\"android\",\"version\":\"" + version + "\",\"maxVideo\":\"" + VideoOut.maxSize() + "\",\"maxPixels\":" + VideoOut.maxPixels()
                        // no "screen": Android TV display modes report the UI resolution (1920x1080 on the Xiaomi
                        // MiTV-MOOQ0, an HDR set), not what the video layer of a 4K panel shows
                        + ",\"problem\":\"" + problem + "\",\"src\":" + (busy ? 1 : 0) + ",\"frames\":" + frames + extraStatus + "}";
            } else {
                code = 404;
                body = "{\"error\":\"not found\"}";
            }
            byte[] b = body.getBytes("UTF-8");
            OutputStream o = c.getOutputStream();
            o.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                    + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            o.write(b);
            o.flush();
        } catch (IOException ignored) {
        } finally {
            closeQuietly(c);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }
}
