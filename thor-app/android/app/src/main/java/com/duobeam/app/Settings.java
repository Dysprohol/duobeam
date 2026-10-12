package com.duobeam.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Sender settings shared by the main UI, notification, overlay and tile. */
final class Settings {

    // {sender layout code, L text key}
    static final String[][] LAYOUTS = {
            {"side", "lay_side"}, {"stack", "lay_stack"}, {"pip", "lay_pip"}, {"top", "lay_top"}, {"bottom", "lay_bottom"}};
    static final int[] BITRATES = {6, 12, 20};
    static final int[] FPS = {60, 30};
    /** Audio: 0 local only (not streamed), 1 receiver only, 2 receiver and local. */
    static final String[] AUDIO = {"off", "tv", "both"};
    static final String[] ACODEC = {"aac", "opus"};
    static final int DEFAULT_PORT = 8201;

    private final SharedPreferences p;

    Settings(Context c) {
        p = c.getApplicationContext().getSharedPreferences("duobeam", Context.MODE_PRIVATE);
        // Migrate saved addresses from the legacy receiver port 8101 to the current default.
        String tv = p.getString("tv", null);
        if (tv != null && tv.endsWith(":8101")) p.edit().putString("tv", tv.replace(":8101", ":" + DEFAULT_PORT)).apply();
    }

    String tv() { return p.getString("tv", ""); } // no default; set via discovery or manual entry
    int layout() { return clamp(p.getInt("layout", 0), LAYOUTS.length); }
    int bitrate() { return clamp(p.getInt("bitrate", 1), BITRATES.length); }
    int fps() { return clamp(p.getInt("fps", 0), FPS.length); }
    int audio() { return clamp(p.getInt("audio", 1), AUDIO.length); }
    void setAudio(int v) { p.edit().putInt("audio", v).apply(); }
    int acodec() { return clamp(p.getInt("acodec", 0), ACODEC.length); }
    boolean wifiLock() { return p.getBoolean("wifilock", true); }
    boolean autostart() { return p.getBoolean("autostart", false); }
    boolean overlay() { return p.getBoolean("overlay", false); }
    boolean bootStart() { return p.getBoolean("bootstart", false); }
    /** Bitmask of local displays blanked while streaming: 1 top, 2 bottom, 3 both. */
    int screensOff() { return p.getInt("screens_off", 0) & 3; }
    void setScreensOff(int v) { p.edit().putInt("screens_off", v & 3).apply(); }
    /** Whether a blanked display still accepts touch input. Off by default; a triple tap wakes it either way. */
    boolean screenTouch() { return p.getBoolean("screen_touch", false); }
    void setScreenTouch(boolean v) { p.edit().putBoolean("screen_touch", v).apply(); }
    String screensArg() { return screensOff() + " " + (screenTouch() ? 1 : 0); }
    String lang() { return p.getString("lang", L.EN); }

    /** Pairing setup in progress: keeps the code field visible for 15 min after "Set up", across app restarts. */
    boolean setupInProgress() { return System.currentTimeMillis() - p.getLong("setup_started", 0) < 15 * 60_000L; }
    void setSetupInProgress(boolean v) { p.edit().putLong("setup_started", v ? System.currentTimeMillis() : 0).apply(); }
    void setLang(String v) { p.edit().putString("lang", v).apply(); }

    /** Random daemon control token, generated once. */
    String token() {
        String t = p.getString("token", null);
        if (t == null) {
            byte[] b = new byte[18];
            new java.security.SecureRandom().nextBytes(b);
            t = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            p.edit().putString("token", t).apply();
        }
        return t;
    }

    void setTv(String v) { p.edit().putString("tv", v).apply(); }
    /** IPs of previously used receivers; discovery also scans their subnets (e.g. a receiver on another VLAN). */
    java.util.Set<String> knownReceivers() {
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(p.getString("known_rx", "").split(",")));
    }
    void addKnownReceiver(String ip) {
        java.util.Set<String> s = knownReceivers();
        s.remove("");
        s.add(ip);
        p.edit().putString("known_rx", String.join(",", s)).apply();
    }
    void setLayout(int v) { p.edit().putInt("layout", clamp(v, LAYOUTS.length)).apply(); }
    void setBitrate(int v) { p.edit().putInt("bitrate", v).apply(); }
    void setFps(int v) { p.edit().putInt("fps", v).apply(); }
    void setWifiLock(boolean v) { p.edit().putBoolean("wifilock", v).apply(); }
    void setAutostart(boolean v) { p.edit().putBoolean("autostart", v).apply(); }
    void setOverlay(boolean v) { p.edit().putBoolean("overlay", v).apply(); }
    void setBootStart(boolean v) { p.edit().putBoolean("bootstart", v).apply(); }

    String layoutName() { return L.s(LAYOUTS[layout()][1]); }

    private static int clamp(int v, int n) {
        return ((v % n) + n) % n;
    }

    /** True on dual-screen devices like the Thor; single-screen handhelds get a simpler UI. */
    static boolean twoScreens(Context c) {
        android.hardware.display.DisplayManager dm = c.getSystemService(android.hardware.display.DisplayManager.class);
        return dm != null && dm.getDisplays().length >= 2;
    }

    String buildArgs() {
        String layout = LAYOUTS[layout()][0];
        String addr = tv().trim();
        if (!addr.contains(":")) addr += ":" + DEFAULT_PORT;
        // the sender picks the actual displays; on a single-screen device "both" is just that screen
        String displays = "top".equals(layout) ? "top" : "both";
        if ("bottom".equals(layout)) {
            displays = "bottom";
            layout = "top"; // single source, treated as the "top" display by the layout
        }
        return "tv=" + addr + " displays=" + displays + " layout=" + layout + " bitrate=" + BITRATES[bitrate()] * 1_000_000
                + " fps=" + FPS[fps()] + " iframe=2 wifilock=" + (wifiLock() ? "on" : "off") + " audio=" + AUDIO[audio()] + " acodec=" + ACODEC[acodec()];
    }
}
