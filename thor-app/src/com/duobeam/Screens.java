package com.duobeam;

import com.genymobile.scrcpy.util.Ln;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The device's own screens: display 0 first, then a second built-in panel if there is one (the Thor's bottom screen).
 * External and virtual displays are ignored, so single-screen handhelds work too.
 */
final class Screens {

    private static final int TYPE_INTERNAL = 1; // android.view.Display.TYPE_INTERNAL (hidden)
    // AYN Thor touchscreens from /proc/bus/input/devices: fts_ts = top, fts_ts_3 = bottom
    private static final String[] THOR_TOUCH = {"fts_ts", "fts_ts_3"};

    private static int[] ids;

    private Screens() {
    }

    static synchronized int[] ids() {
        if (ids == null) {
            ids = detect();
            StringBuilder sb = new StringBuilder();
            for (int id : ids) sb.append(' ').append(id);
            Ln.i("built-in screens:" + sb);
        }
        return ids;
    }

    private static int[] detect() {
        List<Integer> found = new ArrayList<>();
        found.add(0);
        try {
            Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dm = g.getMethod("getInstance").invoke(null);
            int[] all;
            try {
                all = (int[]) g.getMethod("getDisplayIds").invoke(dm);
            } catch (NoSuchMethodException e) { // Android 14+
                all = (int[]) g.getMethod("getDisplayIds", boolean.class).invoke(dm, false);
            }
            for (int id : all) {
                if (id == 0 || found.size() == 2) continue;
                Object info = g.getMethod("getDisplayInfo", int.class).invoke(dm, id);
                if (info != null && info.getClass().getField("type").getInt(info) == TYPE_INTERNAL) found.add(id);
            }
        } catch (Throwable e) {
            Ln.w("screen detection: " + e + ", using display 0 only");
        }
        int[] out = new int[found.size()];
        for (int i = 0; i < out.length; i++) out[i] = found.get(i);
        return out;
    }

    /**
     * Touchscreen of screen #index as /dev/input/eventN, or null if unknown.
     * Known names on the Thor; on a single-screen device, the only direct-touch input device.
     */
    static String touchDevice(int index) {
        List<String[]> all = new ArrayList<>(), touch = new ArrayList<>(); // {name, /dev/input/eventN}
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            String line, name = null, event = null;
            boolean direct = false, multitouch = false;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (event != null) all.add(new String[] {name, event});
                    if (event != null && direct && multitouch) touch.add(new String[] {name, event});
                    name = event = null;
                    direct = multitouch = false;
                } else if (line.startsWith("N: Name=")) {
                    name = line.substring(8).replace("\"", "").trim();
                } else if (line.startsWith("H: Handlers=")) {
                    for (String h : line.substring(12).trim().split(" ")) if (h.startsWith("event")) event = "/dev/input/" + h;
                } else if (line.startsWith("B: PROP=")) {
                    direct = (parseHex(line.substring(8).trim()) & 0x2) != 0; // INPUT_PROP_DIRECT
                } else if (line.startsWith("B: ABS=")) {
                    String[] words = line.substring(7).trim().split(" ");
                    long low = parseHex(words[words.length - 1]); // lowest 64 bits come last
                    multitouch = (low & (1L << 0x35)) != 0; // ABS_MT_POSITION_X
                }
            }
            if (event != null) all.add(new String[] {name, event});
            if (event != null && direct && multitouch) touch.add(new String[] {name, event});
        } catch (Exception e) {
            Ln.w("input devices: " + e);
            return null;
        }
        if (index < THOR_TOUCH.length) {
            for (String[] t : all) if (THOR_TOUCH[index].equals(t[0])) return t[1];
        }
        // other devices: only map touch when there is no doubt which screen it belongs to
        if (ids().length == 1 && index == 0 && touch.size() == 1) return touch.get(0)[1];
        return null;
    }

    private static long parseHex(String s) {
        try {
            return Long.parseUnsignedLong(s, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
