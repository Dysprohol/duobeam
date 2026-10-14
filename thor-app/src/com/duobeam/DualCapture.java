package com.duobeam;

import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.opengl.GLUtils;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.display.VirtualDisplay;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mirrors several displays (e.g. 0 = top, 4 = bottom screen) into separate OES textures and composes
 * them into one frame at a constant frame rate, rendered onto the encoder's input surface.
 */
final class DualCapture {

    private static final class Source {
        final int displayId;
        DisplayInfo info;
        Size size;
        Rect dst; // in output frame pixels, y from top; null = not drawn
        int tex;
        SurfaceTexture st;
        Surface surface;
        IBinder scDisplay;
        VirtualDisplay vd;
        final float[] mtx = new float[16];
        int pending; // buffers announced since the last tick; only the newest is drawn, the rest are skipped
        boolean hasFrame;

        Source(int displayId) {
            this.displayId = displayId;
        }
    }

    private final Source[] sources;
    private final String layout;
    private final int fps;
    private Size outSize;

    private HandlerThread thread;
    private Handler handler;
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;
    private int program, posLoc, tcLoc, texLoc, mtxLoc;
    private FloatBuffer vbuf, tbuf;
    private volatile boolean running;
    private long startUptime, frameNo;

    // render time -> time the newest drawn source buffer was produced (SurfaceTexture timestamp, System.nanoTime base),
    // so the latency meter starts when the picture appears on the device instead of at the next render tick
    private final long[] ringRender = new long[128], ringSource = new long[128];
    private int ringPos;
    final Stats latch = new Stats(); // source buffer produced -> drawn into the output frame
    volatile int skippedBuffers, badTimestamps;

    DualCapture(int[] displayIds, String layout, int fps, Limits limits) {
        this.limits = limits;
        sources = new Source[displayIds.length];
        for (int i = 0; i < displayIds.length; i++) sources[i] = new Source(displayIds[i]);
        this.layout = layout;
        this.fps = fps;
    }

    /** Queries display sizes and computes the layout; returns the output frame size. */
    Size prepare() {
        for (Source s : sources) {
            s.info = ServiceManager.getDisplayManager().getDisplayInfo(s.displayId);
            if (s.info == null) throw new IllegalArgumentException("no display " + s.displayId);
            s.size = s.info.getSize();
            Ln.i("display " + s.displayId + ": " + s.size.getWidth() + "x" + s.size.getHeight() + " layerStack " + s.info.getLayerStack());
        }
        Source a = sources[0], b = sources.length > 1 ? sources[1] : null;
        int aw = a.size.getWidth(), ah = a.size.getHeight();
        if (b == null || "top".equals(layout)) {
            outSize = new Size(aw, ah);
            a.dst = new Rect(0, 0, aw, ah);
            if (b != null) b.dst = null;
        } else {
            int bw = b.size.getWidth(), bh = b.size.getHeight();
            switch (layout) {
                case "bottom":
                    outSize = new Size(bw, bh);
                    a.dst = null;
                    b.dst = new Rect(0, 0, bw, bh);
                    break;
                case "stack": // bottom below top, horizontally centered
                    outSize = new Size(Math.max(aw, bw), ah + bh);
                    a.dst = new Rect((outSize.getWidth() - aw) / 2, 0, (outSize.getWidth() - aw) / 2 + aw, ah);
                    b.dst = new Rect((outSize.getWidth() - bw) / 2, ah, (outSize.getWidth() - bw) / 2 + bw, ah + bh);
                    break;
                case "pip": { // top fullscreen, bottom inset in the lower-right corner at 1/3 height
                    outSize = new Size(aw, ah);
                    a.dst = new Rect(0, 0, aw, ah);
                    int ph = ah / 3, pw = ph * bw / bh, m = 24;
                    b.dst = new Rect(aw - pw - m, ah - ph - m, aw - m, ah - m);
                    break;
                }
                default: { // "side": side by side at native resolution, vertically centered
                    int h = Math.max(ah, bh);
                    outSize = new Size(aw + bw, h);
                    a.dst = new Rect(0, (h - ah) / 2, aw, (h - ah) / 2 + ah);
                    b.dst = new Rect(aw, (h - bh) / 2, aw + bw, (h - bh) / 2 + bh);
                    break;
                }
            }
        }
        fit(sources);
        Ln.i("layout " + layout + " -> " + outSize.getWidth() + "x" + outSize.getHeight());
        return outSize;
    }

    /** True when a captured display no longer has the size it had in prepare(), e.g. a phone was rotated. */
    boolean displaysChanged() {
        for (Source s : sources) {
            DisplayInfo i = ServiceManager.getDisplayManager().getDisplayInfo(s.displayId);
            if (i != null && !i.getSize().equals(s.size)) return true;
        }
        return false;
    }

    /**
     * What the receiver reported in /status (0 = not reported): its decoder's largest width and height, the largest
     * frame area its HEVC level allows, and the screen resolution it shows the picture at.
     */
    static final class Limits {
        final int maxW, maxH, screenW, screenH;
        final long maxPixels;

        Limits(int maxW, int maxH, long maxPixels, int screenW, int screenH) {
            this.maxW = maxW;
            this.maxH = maxH;
            this.maxPixels = maxPixels;
            this.screenW = screenW;
            this.screenH = screenH;
        }
    }

    // Samsung's low-latency player rejects a 1080x2340 portrait phone screen ("resolution is invalid", QE43Q67C,
    // 2026-10-12) while 3160x1080 plays. The frame is the smallest of: the composed screens, the receiver's decoder
    // box, its HEVC level's area (level 4.1 = 2,228,224 pixels, less than 3160x1080), the TV screen it is shown on,
    // and the long-side cap the sender sets after an encoder refused a size. Aspect is kept, never scaled up.
    private final Limits limits;
    private int align = 2, longSideCap;

    /** Size step the encoder needs (MediaCodecInfo.VideoCapabilities width/height alignment). */
    void setAlignment(int a) {
        align = Math.max(2, a);
    }

    /** Long-side limit after the encoder refused a size; 0 = none. Takes effect on the next prepare(). */
    void setLongSideCap(int px) {
        longSideCap = px;
    }

    private void fit(Source[] all) {
        double w = outSize.getWidth(), h = outSize.getHeight(), f = 1;
        Limits l = limits;
        if (l.maxW > 0) f = Math.min(f, l.maxW / w);
        if (l.maxH > 0) f = Math.min(f, l.maxH / h);
        if (l.maxPixels > 0) f = Math.min(f, Math.sqrt(l.maxPixels / (w * h)));
        if (l.screenW > 0 && l.screenH > 0) f = Math.min(f, Math.min(l.screenW / w, l.screenH / h)); // more is never seen
        if (longSideCap > 0) f = Math.min(f, longSideCap / Math.max(w, h));
        if (f >= 1) return;
        // scaled sizes in steps of at least 8: Amlogic decoders repeat edge lines when the height is not a multiple of 8
        int a = Math.max(align, 8);
        int nw = (int) (w * f) / a * a, nh = (int) (h * f) / a * a;
        double fx = nw / w, fy = nh / h;
        for (Source s : all) {
            if (s.dst == null) continue;
            s.dst = new Rect((int) Math.round(s.dst.left * fx), (int) Math.round(s.dst.top * fy),
                    (int) Math.round(s.dst.right * fx), (int) Math.round(s.dst.bottom * fy));
        }
        Ln.i("frame " + outSize.getWidth() + "x" + outSize.getHeight() + " scaled to " + nw + "x" + nh + " (receiver decoder "
                + l.maxW + "x" + l.maxH + ", max pixels " + l.maxPixels + ", screen " + l.screenW + "x" + l.screenH
                + (longSideCap > 0 ? ", long side cap " + longSideCap : "") + ")");
        outSize = new Size(nw, nh);
    }

    void start(Surface encoderSurface) throws Exception {
        thread = new HandlerThread("DuoBeamGL");
        thread.start();
        handler = new Handler(thread.getLooper());
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            try {
                initGl(encoderSurface);
                for (Source s : sources) if (s.dst != null) initSource(s);
            } catch (Exception e) {
                error.set(e);
            }
            done.countDown();
        });
        done.await();
        if (error.get() != null) throw error.get();
        running = true;
        startUptime = SystemClock.uptimeMillis();
        frameNo = 0;
        handler.post(this::tick);
    }

    private void initGl(Surface out) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new IllegalStateException("eglInitialize");
        int[] attribs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0);
        if (num[0] <= 0) throw new IllegalStateException("eglChooseConfig");
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], out, new int[] {EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) throw new IllegalStateException("eglMakeCurrent");

        String vs = "#version 100\nattribute vec4 pos;\nattribute vec4 tc;\nuniform mat4 mtx;\nvarying vec2 v;\n"
                + "void main() { gl_Position = pos; v = (mtx * tc).xy; }";
        String fs = "#version 100\n#extension GL_OES_EGL_image_external : require\nprecision mediump float;\n"
                + "uniform samplerExternalOES tex;\nvarying vec2 v;\nvoid main() { gl_FragColor = texture2D(tex, v); }";
        program = GLUtils.createProgram(vs, fs);
        if (program == 0) throw new IllegalStateException("createProgram");
        posLoc = GLES20.glGetAttribLocation(program, "pos");
        tcLoc = GLES20.glGetAttribLocation(program, "tc");
        texLoc = GLES20.glGetUniformLocation(program, "tex");
        mtxLoc = GLES20.glGetUniformLocation(program, "mtx");
        vbuf = GLUtils.createFloatBuffer(new float[] {-1, -1, 1, -1, -1, 1, 1, 1});
        tbuf = GLUtils.createFloatBuffer(new float[] {0, 0, 1, 0, 0, 1, 1, 1});
    }

    private void initSource(Source s) throws Exception {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        s.tex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        int w = s.size.getWidth(), h = s.size.getHeight();
        s.st = new SurfaceTexture(s.tex);
        s.st.setDefaultBufferSize(w, h);
        s.st.setOnFrameAvailableListener(st -> s.pending++, handler);
        s.surface = new Surface(s.st);
        try {
            s.vd = ServiceManager.getDisplayManager().createVirtualDisplay("duobeam-" + s.displayId, w, h, s.displayId, s.surface);
            Ln.i("display " + s.displayId + ": DisplayManager API");
        } catch (Exception dmError) {
            s.scDisplay = SurfaceControl.createDisplay("duobeam-" + s.displayId, false);
            SurfaceControl.openTransaction();
            try {
                SurfaceControl.setDisplaySurface(s.scDisplay, s.surface);
                SurfaceControl.setDisplayProjection(s.scDisplay, 0, new Rect(0, 0, w, h), new Rect(0, 0, w, h));
                SurfaceControl.setDisplayLayerStack(s.scDisplay, s.info.getLayerStack());
            } finally {
                SurfaceControl.closeTransaction();
            }
            Ln.i("display " + s.displayId + ": SurfaceControl API");
        }
    }

    /** Renders one output frame every 1/fps s whether or not the sources changed, keeping an even timeline for MSE on the TV. */
    private void tick() {
        if (!running) return;
        int ow = outSize.getWidth(), oh = outSize.getHeight();
        GLES20.glViewport(0, 0, ow, oh);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glEnableVertexAttribArray(tcLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vbuf);
        GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 0, tbuf);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(texLoc, 0);
        long newestSource = 0;
        for (Source s : sources) {
            if (s.dst == null) continue;
            if (s.pending > 0) {
                skippedBuffers += s.pending - 1;
                s.pending = 0;
                s.st.updateTexImage();
                s.st.getTransformMatrix(s.mtx);
                s.hasFrame = true;
                newestSource = Math.max(newestSource, s.st.getTimestamp());
            }
            if (!s.hasFrame) continue;
            // GL viewport origin is bottom-left
            GLES20.glViewport(s.dst.left, oh - s.dst.bottom, s.dst.width(), s.dst.height());
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.tex);
            GLES20.glUniformMatrix4fv(mtxLoc, 1, false, s.mtx, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        long renderNs = System.nanoTime();
        if (newestSource > 0) {
            long d = renderNs - newestSource;
            if (d < 0 || d > 1_000_000_000L) { // not on the System.nanoTime clock: keep the render time
                badTimestamps++;
                newestSource = 0;
            } else {
                latch.add(d / 1000);
            }
        }
        synchronized (ringRender) {
            ringRender[ringPos] = renderNs / 1000;
            ringSource[ringPos] = newestSource;
            ringPos = (ringPos + 1) % ringRender.length;
        }
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, renderNs);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        frameNo++;
        long next = startUptime + frameNo * 1000 / fps;
        long now = SystemClock.uptimeMillis();
        if (next < now - 100) { // fell behind (e.g. device slept): resync instead of rendering a burst of frames
            startUptime = now;
            frameNo = 0;
            next = now;
        }
        handler.postAtTime(this::tick, next);
    }

    /** System.nanoTime when the newest source buffer in the frame rendered at renderUs was produced; 0 = repeated frame or unknown. */
    long sourceTimeNs(long renderUs) {
        synchronized (ringRender) {
            for (int i = 0; i < ringRender.length; i++) if (ringRender[i] == renderUs) return ringSource[i];
        }
        return 0;
    }

    void stop() {
        running = false;
        if (handler == null) return;
        CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            for (Source s : sources) {
                if (s.vd != null) s.vd.release();
                if (s.scDisplay != null) SurfaceControl.destroyDisplay(s.scDisplay);
                if (s.st != null) s.st.release();
                if (s.surface != null) s.surface.release();
            }
            if (eglDisplay != null) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
            done.countDown();
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread.quitSafely();
    }
}
