package com.duobeam;

import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.os.IBinder;

import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Blanks the device's panels (index 0 = display 0 / top, index 1 = second built-in screen / bottom) while streaming.
 * Only the backlight is turned off (brightness -1), not the panel: powering the panel off also suspends the
 * touch controller, whereas this keeps touch and gamepad input working. Touch on a blanked screen can optionally
 * be blocked; three quick taps then turn that screen back on. Panels are restored on stop or sender exit.
 */
final class ScreenPower {

    static final int TOP = 1, BOTTOM = 2; // mask bits: which panels are blanked
    private static final int[] DISPLAYS = Screens.ids();

    private static volatile int offMask;
    private static final Map<Integer, InputStream> touchStreams = new HashMap<>();
    // fds of opened touch devices; closing the fd releases the EVIOCGRAB
    private static final Map<Integer, FileDescriptor> touchFds = new HashMap<>();
    private static final Map<Integer, Boolean> touchGrabbed = new HashMap<>();
    private static volatile boolean allowTouch;
    private static final float BRIGHTNESS_OFF = -1f;
    private static Thread keeper;

    private ScreenPower() {
    }

    static int mask() {
        return offMask;
    }

    private static IBinder token(int displayId) {
        DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (info == null || info.getUniqueId() == null || !info.getUniqueId().startsWith("local:")) return null;
        long phys = Long.parseLong(info.getUniqueId().substring("local:".length()));
        return SurfaceControl.getPhysicalDisplayToken(phys);
    }

    private static boolean setBrightness(int index, float b) throws Exception {
        IBinder t = token(DISPLAYS[index]);
        if (t == null) return false;
        Object ok = Class.forName("android.view.SurfaceControl").getMethod("setDisplayBrightness", IBinder.class, float.class).invoke(null, t, b);
        return Boolean.TRUE.equals(ok);
    }

    /** Brightness the system currently wants on this display (slider / auto-brightness). */
    private static float systemBrightness(int index) {
        try {
            Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dm = g.getMethod("getInstance").invoke(null);
            float b = (Float) g.getMethod("getBrightness", int.class).invoke(dm, DISPLAYS[index]);
            if (b >= 0f && b <= 1f) return b;
        } catch (Throwable e) {
            Ln.w("brightness " + index + ": " + e);
        }
        return 0.5f;
    }

    private static boolean power(int index, boolean on) {
        try {
            boolean ok = setBrightness(index, on ? systemBrightness(index) : BRIGHTNESS_OFF);
            Ln.i("screen " + (index == 0 ? "top" : "bottom") + (on ? " on" : " blanked") + " ok=" + ok);
            return ok;
        } catch (Throwable e) {
            Ln.w("power " + index + ": " + e);
            return false;
        }
    }

    /** The system (auto-brightness, slider) may restore brightness on its own, so re-blank every second. */
    private static synchronized void keepDark() {
        if (keeper != null && keeper.isAlive()) return;
        keeper = new Thread(() -> {
            while (offMask != 0) {
                try {
                    Thread.sleep(1000);
                    // device put to sleep with the power button: screens and touch must work normally after wake-up
                    if (!deviceAwake()) {
                        Ln.i("device asleep, restoring screens and touch");
                        restoreAll();
                        break;
                    }
                    synchronized (ScreenPower.class) { // don't re-blank a screen that apply() just turned on
                        for (int i = 0; i < DISPLAYS.length; i++) if ((offMask & (1 << i)) != 0) setBrightness(i, BRIGHTNESS_OFF);
                    }
                } catch (Throwable e) {
                    Ln.w("keepDark: " + e);
                }
            }
        }, "DuoBeamDark");
        keeper.setDaemon(true);
        keeper.start();
    }

    /**
     * Sets the mask of blanked panels (0 = both on).
     * touch = true: a blanked screen still delivers touch to the game (wake it from the sidebar / app).
     * touch = false: touch on a blanked screen is blocked; three quick taps turn it on without reaching the game.
     */
    static synchronized void apply(int mask, boolean touch) {
        allowTouch = touch;
        for (int i = 0; i < DISPLAYS.length; i++) {
            int bit = 1 << i;
            boolean wantOff = (mask & bit) != 0, isOff = (offMask & bit) != 0;
            if (wantOff != isOff && power(i, !wantOff)) offMask = wantOff ? offMask | bit : offMask & ~bit;
            // blanked screen: always listen for the triple tap; grab the device too if touch is not allowed
            if ((offMask & bit) != 0) watchTouch(i, !allowTouch);
            else releaseTouch(i);
        }
        if (offMask != 0) keepDark();
    }

    static void apply(int mask) {
        apply(mask, allowTouch);
    }

    static boolean touchAllowed() {
        return allowTouch;
    }

    private static boolean deviceAwake() {
        try {
            return com.genymobile.scrcpy.wrappers.ServiceManager.getPowerManager().isScreenOn(0);
        } catch (Throwable e) {
            return true; // unknown: assume awake and change nothing
        }
    }

    /** Turns all panels back on (stop, sender exit, app heartbeat lost). */
    static void restoreAll() {
        apply(0);
    }

    // ---- blanked-screen touch blocking (exclusive input device grab) + triple-tap wake ----

    private static final int EVIOCGRAB = 0x40044590; // _IOW('E', 0x90, int)

    /** ioctl(EVIOCGRAB, non-zero) makes this fd the only receiver of the device's events; closing the fd releases it. */
    private static boolean grab(FileDescriptor fd) {
        try {
            Object os = Class.forName("libcore.io.Libcore").getField("os").get(null);
            for (Method m : os.getClass().getMethods()) {
                if (!m.getName().equals("ioctlInt")) continue;
                if (m.getParameterTypes().length == 2) {
                    m.invoke(os, fd, EVIOCGRAB);
                } else if (m.getParameterTypes().length == 3) {
                    m.invoke(os, fd, EVIOCGRAB, Class.forName("android.system.Int32Ref").getConstructor(int.class).newInstance(1));
                } else {
                    continue;
                }
                return true;
            }
        } catch (Throwable e) {
            Ln.w("grab: " + e);
        }
        return false;
    }

    private static synchronized void releaseTouch(int index) {
        InputStream in = touchStreams.remove(index);
        FileDescriptor fd = touchFds.remove(index);
        touchGrabbed.remove(index);
        if (in == null && fd == null) return;
        // On Android, FileInputStream(fd).close() does not close a descriptor it didn't open, so the grab would persist.
        // IoBridge.closeAndSignalBlockedThreads really closes it and wakes the thread blocked in read().
        boolean closed = false;
        if (fd != null) {
            try {
                Class.forName("libcore.io.IoBridge").getMethod("closeAndSignalBlockedThreads", FileDescriptor.class).invoke(null, fd);
                closed = true;
            } catch (Throwable e) {
                try {
                    Os.close(fd);
                    closed = true;
                } catch (Throwable e2) {
                    Ln.w("touch release: " + e2);
                }
            }
        }
        try {
            if (in != null) in.close();
        } catch (Exception ignored) {
        }
        Ln.i("touch " + (index == 0 ? "top" : "bottom") + " released (fd closed=" + closed + ")");
    }

    /** Listens for touch on a blanked screen (triple tap = turn on); grab = touch does not reach the game. */
    private static synchronized void watchTouch(final int index, boolean grab) {
        Boolean was = touchGrabbed.get(index);
        if (was != null && was == grab) return;
        if (was != null) releaseTouch(index); // mode changed: reopen
        String dev = Screens.touchDevice(index);
        if (dev == null) {
            Ln.w("touch for screen " + index + " unknown; the power button still brings the screen back");
            return;
        }
        final InputStream in;
        try {
            FileDescriptor fd = Os.open(dev, OsConstants.O_RDONLY, 0);
            in = new FileInputStream(fd);
            touchFds.put(index, fd);
            touchGrabbed.put(index, grab);
            String who = index == 0 ? "top" : "bottom";
            if (grab) Ln.i("touch " + who + " grabbed ok=" + grab(fd));
            else Ln.i("touch " + who + " passed through, listening for triple tap");
        } catch (Exception e) {
            Ln.w("touch " + dev + ": " + e);
            return;
        }
        touchStreams.put(index, in);
        Thread t = new Thread(() -> {
            // struct input_event (64-bit): 16 B time, 2 B type, 2 B code, 4 B value
            byte[] ev = new byte[24];
            long[] taps = new long[3];
            int tapCount = 0;
            boolean wake = false;
            try {
                while (true) {
                    int n = 0;
                    while (n < 24) {
                        int r = in.read(ev, n, 24 - n);
                        if (r < 0) return;
                        n += r;
                    }
                    int type = (ev[16] & 0xFF) | (ev[17] & 0xFF) << 8, code = (ev[18] & 0xFF) | (ev[19] & 0xFF) << 8;
                    int value = (ev[20] & 0xFF) | (ev[21] & 0xFF) << 8 | (ev[22] & 0xFF) << 16 | (ev[23] & 0xFF) << 24;
                    if (type != 1 || code != 0x14a) continue; // BTN_TOUCH only
                    long now = System.currentTimeMillis();
                    if (value == 1) {
                        taps[tapCount++ % 3] = now;
                        if (tapCount >= 3 && now - taps[tapCount % 3] < 1200) wake = true; // 3 taps within 1.2 s
                    } else if (value == 0 && wake) {
                        // wait for finger-up so the system never sees half a touch sequence
                        Ln.i("triple tap on blanked screen, turning on");
                        apply(offMask & ~(1 << index));
                        return;
                    }
                }
            } catch (Exception e) {
                // expected when releaseTouch closes the fd
            }
        }, "DuoBeamTouch" + index);
        t.setDaemon(true);
        t.start();
    }
}
