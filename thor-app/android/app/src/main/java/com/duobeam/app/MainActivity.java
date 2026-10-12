package com.duobeam.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Sender control panel: start/stop, receiver selection, layout, quality, audio, one-time setup and options. */
public class MainActivity extends Activity implements StreamClient.Listener {

    private static final int C_TEXT = 0xFFF3ECFF, C_MUTED = 0xFFBFB0E6, C_PINK = 0xFFFF7AD9, C_CARD = 0xB0140A30, C_OK = 0xFF7CF0B0;
    static final String EXTRA_PAIRING = "pairing";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StreamClient client;
    private Settings settings;
    private ScrollView scroll;
    private TextView statusText, setupText, pairLive, tvInfo;
    private EditText tvAddr, codeInput, portInput;
    // the bottom screen is landscape, where Android would otherwise cover the whole app with a full-screen keyboard
    private static final int NO_FULLSCREEN_IME = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
    private RadioGroup layoutGroup, bitrateGroup, fpsGroup, screensGroup, audioGroup;
    private boolean twoScreens = true;
    private TextView overlayPerm;
    private CheckBox wifiLock, autostart, overlay, bootStart, touchBox;
    private Button setupBtn, retryBtn;
    private LinearLayout setupCard, pairBox, checklist, foundList, portRow;
    private TextView pairWhere;
    private String checklistSig = "";
    private boolean autostarted;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            maybeAutostart(); // after a cold start the sender isn't running yet, so onChanged may never fire
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        client = StreamClient.get(this);
        settings = client.settings();
        L.load(this);
        build();
        client.addListener(this);
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        handler.post(poll);
        if (AdbLauncher.isPaired(this) && !pairRequested) settings.setSetupInProgress(false); // already paired: no pairing UI
        showPairingIfRequested(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showPairingIfRequested(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // side bar enabled but overlay permission denied: don't report it as enabled
        if (settings.overlay() && !android.provider.Settings.canDrawOverlays(this)) client.setOverlay(false);
        updateOverlayPerm();
    }

    private void openOverlaySettings() {
        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void updateOverlayPerm() {
        if (overlayPerm != null) overlayPerm.setVisibility(android.provider.Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onPause() {
        // don't come back with the address field focused and the keyboard popping up
        if (tvAddr != null && getCurrentFocus() == tvAddr) leaveField(tvAddr);
        super.onPause();
    }

    private void maybeAutostart() {
        if (!autostarted && settings.autostart() && client.ready() && !client.isStreaming() && !client.status().startsWith("STARTING")
                && !settings.tv().trim().isEmpty()) {
            autostarted = true;
            client.start();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        client.removeListener(this);
        PairingReceiver.ui = null;
        super.onDestroy(); // streaming continues: the sender runs in its own process
    }

    private void build() {
        twoScreens = Settings.twoScreens(this);
        setContentView(buildUi());
        refresh();
    }

    /** On a single screen any blanked mask is shown as "screen off". */
    private int shownScreens(int mask) {
        return twoScreens || mask == 0 ? mask : 3;
    }

    @Override
    public void onChanged() {
        refresh();
        maybeAutostart();
        if (screensGroup != null) { // screen state may change from the side bar, triple-tap or the sender
            int want = shownScreens(client.isStreaming() ? client.screensNow() : settings.screensOff());
            if (want >= 0 && want < screensGroup.getChildCount() && indexOf(screensGroup) != want) {
                ((RadioButton) screensGroup.getChildAt(want)).setChecked(true);
            }
        }
        if (touchBox != null && touchBox.isChecked() != settings.screenTouch()) touchBox.setChecked(settings.screenTouch());
        if (layoutGroup != null && indexOf(layoutGroup) != settings.layout()) {
            ((RadioButton) layoutGroup.getChildAt(settings.layout())).setChecked(true); // changed from the overlay/notification
        }
        if (audioGroup != null && indexOf(audioGroup) != settings.audio()) {
            ((RadioButton) audioGroup.getChildAt(settings.audio())).setChecked(true); // changed from the side bar
        }
        if (overlay != null && overlay.isChecked() != settings.overlay()) overlay.setChecked(settings.overlay()); // side bar toggled from the panel/notification
    }

    private void refresh() {
        if (statusText == null) return;
        String tvl = client.tvLine();
        statusText.setText(client.humanStatus() + (tvl.isEmpty() ? "" : "\n" + tvl));
        statusText.setTextColor(client.isStreaming() ? (client.tvOk() ? C_OK : 0xFFFFC27A) : C_MUTED);
        boolean paired = AdbLauncher.isPaired(this), done = client.setupDone();
        if (done) {
            setupText.setText(L.s("setup_done"));
            setupText.setTextColor(C_OK);
        } else if (paired) {
            String ge = client.grantError();
            setupText.setText(client.granting() ? L.s("setup_grant") : ge != null ? L.f("setup_grant_fail", ge) : L.s("setup_grant"));
            setupText.setTextColor(C_MUTED);
        } else {
            setupText.setText(L.s("setup_need"));
            setupText.setTextColor(C_MUTED);
        }
        setupBtn.setVisibility(View.GONE);
        retryBtn.setVisibility(View.GONE);
        bootStart.setEnabled(done);
        buildChecklist(paired, done);
        boolean wantBox = pairRequested || (!paired && settings.setupInProgress());
        if (wantBox && pairBox.getVisibility() != View.VISIBLE) {
            pairBox.setVisibility(View.VISIBLE); // pairing in progress: restore the box even when reopened from the launcher
            if (PairingReceiver.port() > 0 && portInput.getText().length() == 0) portInput.setText(String.valueOf(PairingReceiver.port()));
        } else if (!wantBox && pairBox.getVisibility() == View.VISIBLE) {
            pairBox.setVisibility(View.GONE);
        }
    }

    /** Setup checklist: shows which steps are done and an action button for each remaining one. */
    private void buildChecklist(boolean paired, boolean done) {
        boolean dev = AdbLauncher.devOptionsOn(this), wifi = AdbLauncher.wirelessDebuggingOn(this);
        String sig = dev + "" + wifi + paired + done + client.granting() + L.lang();
        if (sig.equals(checklistSig)) return;
        checklistSig = sig;
        checklist.removeAllViews();
        View.OnClickListener openDev = v -> openAwayFromMe(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        checkRow(dev || done, L.s("chk_dev"), done && !dev ? L.s("chk_auto") : L.s("chk_dev_fix"),
                L.s("chk_dev_btn"), v -> openAwayFromMe(new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)), L.s("chk_wifi_btn"), openDev);
        checkRow(wifi || done, L.s("chk_wifi"), done && !wifi ? L.s("chk_auto") : L.s("chk_wifi_fix"),
                L.s("chk_wifi_btn"), openDev, null, null);
        checkRow(paired, L.s("chk_pair"), L.s("setup_need"), L.s("setup_btn"), v -> startSetup(), L.s("pair_again"), v -> startSetup());
        View.OnClickListener grant = paired && !client.granting() ? v -> client.grantAsync() : null;
        String grantLabel = paired ? (client.granting() ? L.s("working") : L.s("setup_retry")) : null;
        checkRow(done, L.s("chk_perm"), null, grantLabel, grant, null, null);
    }

    private void checkRow(boolean ok, String label, String hint, String btn, View.OnClickListener action,
                          String okBtn, View.OnClickListener okAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView t = text((ok ? "✔  " : "✖  ") + label, 15, ok ? C_OK : 0xFFFF9BB5);
        row.addView(t);
        if (!ok && hint != null) row.addView(text(hint, 13, C_MUTED));
        else if (ok && hint != null && hint.equals(L.s("chk_auto"))) row.addView(text(hint, 12, C_MUTED));
        if (!ok && btn != null && action != null) {
            Button b = button(btn, 0xFF5B2DB3);
            b.setOnClickListener(action);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            row.addView(b, lp);
        } else if (!ok && btn != null) {
            row.addView(text(btn, 13, C_MUTED));
        } else if (ok && okBtn != null && okAction != null) {
            // completed steps can still be redone manually
            TextView m = text("\u21BB  " + okBtn, 13, C_PINK);
            m.setPadding(dp(22), dp(2), 0, dp(2));
            m.setOnClickListener(okAction);
            row.addView(m);
        }
        checklist.addView(row);
    }

    private int myDisplayId() {
        Display d = getDisplay();
        return d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY;
    }

    /** A display other than the one hosting this activity, or null if there is only one. */
    private Display displayAwayFromMe() {
        int mine = myDisplayId();
        for (Display d : getSystemService(DisplayManager.class).getDisplays()) if (d.getDisplayId() != mine) return d;
        return null;
    }

    /** Opens settings on a display other than ours so they never cover the app. */
    private void openAwayFromMe(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Display d = displayAwayFromMe();
        if (d != null) showWhere(d.getDisplayId());
        try {
            startActivity(i, d != null ? ActivityOptions.makeBasic().setLaunchDisplayId(d.getDisplayId()).toBundle() : null);
        } catch (Exception e) {
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    private void save() {
        settings.setTv(tvAddr.getText().toString().trim());
        settings.setLayout(indexOf(layoutGroup));
        settings.setBitrate(indexOf(bitrateGroup));
        settings.setFps(indexOf(fpsGroup));
        settings.setWifiLock(wifiLock.isChecked());
        settings.setAutostart(autostart.isChecked());
        settings.setBootStart(bootStart.isChecked());
    }

    // ---- setup: system settings on the bottom display, DuoBeam with the code field on the top ----

    private Display otherDisplay(boolean wantDefault) {
        for (Display d : getSystemService(DisplayManager.class).getDisplays()) {
            if ((d.getDisplayId() == Display.DEFAULT_DISPLAY) == wantDefault) return d;
        }
        return null;
    }

    private boolean settingsPending;

    /**
     * Dual-screen setup: the app (with the code field) goes to the top display, Developer options to the bottom.
     * The app moves first and only then opens settings on the other display, so the windows never overlap.
     * On a single display settings open normally; the code is entered from the notification or back in the app.
     */
    private boolean pairRequested; // Set up / Pair again pressed during this panel session

    private void startSetup() {
        pairRequested = true;
        settings.setSetupInProgress(true);
        PairingReceiver.begin(this);
        showPairBox();
        Display top = otherDisplay(true), bottom = otherDisplay(false);
        settingsPending = true;
        if (top != null && bottom != null && myDisplayId() != top.getDisplayId()) {
            // app is on the bottom display: move to the top first and open settings only after the system confirms
            // the display change (onConfigurationChanged); otherwise the two launches race and the windows overlap
            movedToTop = false;
            Intent me = new Intent(this, MainActivity.class).putExtra(EXTRA_PAIRING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(me, ActivityOptions.makeBasic().setLaunchDisplayId(top.getDisplayId()).toBundle());
            handler.postDelayed(this::openSettingsIfPending, 2000); // fallback if the move never completes: open wherever the app isn't
        } else {
            openSettingsIfPending();
        }
    }

    private boolean movedToTop;

    private void openSettingsIfPending() {
        if (!settingsPending) return;
        settingsPending = false;
        Intent dev = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        Display top = otherDisplay(true), bottom = otherDisplay(false);
        if (top != null && bottom != null && (movedToTop || myDisplayId() == top.getDisplayId())) {
            openOnDisplay(dev, bottom.getDisplayId()); // explicit placement: app on top, settings on bottom
        } else {
            openAwayFromMe(dev);
        }
        showPairBox();
    }

    private void showWhere(int settingsDisplay) {
        if (pairWhere == null) return;
        String set = settingsDisplay == Display.DEFAULT_DISPLAY ? L.s("scr_top") : L.s("scr_bottom");
        String me = myDisplayId() == Display.DEFAULT_DISPLAY ? L.s("scr_top") : L.s("scr_bottom");
        pairWhere.setText(L.f("pair_where", set, me));
        pairWhere.setVisibility(View.VISIBLE);
    }

    private void openOnDisplay(Intent i, int displayId) {
        showWhere(displayId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        try {
            startActivity(i, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
        } catch (Exception e) {
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c); // display move handled without recreation, so the code field keeps its state
        Display top = otherDisplay(true);
        if (settingsPending && top != null && myDisplayId() == top.getDisplayId()) {
            movedToTop = true;
            handler.postDelayed(this::openSettingsIfPending, 250);
        }
    }

    private void showPairingIfRequested(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_PAIRING, false) || PairingReceiver.active()) showPairBox();
    }

    private void showPairBox() {
        if (pairBox == null) return;
        pairBox.setVisibility(View.VISIBLE);
        if (PairingReceiver.port() > 0 && portInput.getText().length() == 0) portInput.setText(String.valueOf(PairingReceiver.port()));
        scroll.post(() -> scroll.smoothScrollTo(0, setupCard.getTop()));
        codeInput.requestFocus();
        codeInput.post(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(codeInput, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    // ---- receiver discovery: GET /hello on :8202 across local subnets and subnets of known receivers ----

    private void findTv() {
        tvInfo.setText(L.s("finding"));
        foundList.removeAllViews();
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        new Thread(() -> {
            Set<String> prefixes = new LinkedHashSet<>();
            // subnets of previously used receivers (e.g. a TV on a separate VLAN) plus the current address
            Set<String> known = settings.knownReceivers();
            known.add(settings.tv().split(":")[0]);
            for (String saved : known) {
                if (!saved.isEmpty() && saved.chars().filter(ch -> ch == '.').count() == 3) {
                    prefixes.add(saved.substring(0, saved.lastIndexOf('.') + 1));
                }
            }
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address) {
                            String ip = a.getHostAddress();
                            prefixes.add(ip.substring(0, ip.lastIndexOf('.') + 1));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            List<String> found = Collections.synchronizedList(new ArrayList<>());
            Set<String> seen = Collections.synchronizedSet(new java.util.HashSet<>());
            // 1) known receivers first, with a long timeout: TV Wi-Fi power saving can delay connects noticeably
            ExecutorService quick = Executors.newFixedThreadPool(8);
            for (String k : known) {
                if (k.isEmpty()) continue;
                quick.execute(() -> {
                    String info = hello(k, 2500);
                    if (info != null && seen.add(k)) {
                        found.add(k + info);
                        handler.post(() -> addFoundRow(k + info));
                    }
                });
            }
            quick.shutdown();
            try {
                quick.awaitTermination(4, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            // 2) full subnet sweep
            ExecutorService pool = Executors.newFixedThreadPool(48);
            for (String p : prefixes) {
                for (int i = 1; i < 255; i++) {
                    final String ip = p + i;
                    final int total = prefixes.size() * 254;
                    pool.execute(() -> {
                        String info = seen.contains(ip) ? null : hello(ip, 1500);
                        if (info != null && seen.add(ip)) {
                            found.add(ip + info);
                            handler.post(() -> addFoundRow(ip + info)); // list immediately, don't wait for the sweep to finish
                        }
                        int d = done.incrementAndGet();
                        if (d % 25 == 0) handler.post(() -> tvInfo.setText(L.f("finding_n", Math.min(99, d * 100 / total))));
                    });
                }
            }
            pool.shutdown();
            try {
                pool.awaitTermination(40, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            handler.post(() -> showFound(found));
        }).start();
    }

    private static String hello(String ip, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":8202/hello").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[512];
                int n = in.read(b);
                String s = new String(b, 0, Math.max(0, n), "UTF-8");
                if (!s.contains("DuoBeam")) return null;
                return s.contains("\"busy\":true") ? "  " + L.s("found_busy") : "";
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void showFound(List<String> found) {
        if (found.isEmpty()) {
            tvInfo.setText(L.s("found_none"));
            return;
        }
        if (found.size() == 1) { // single receiver: select it automatically (it stays in the list, marked)
            String ip = found.get(0).split(" ")[0];
            pickTv(ip);
            tvInfo.setText(L.f("found_auto", ip));
            return;
        }
        tvInfo.setText(L.s("found_pick"));
    }

    private void addFoundRow(String item) {
        String ip = item.split(" ")[0];
        TextView row = text(rowLabel(item, ip), 16, C_TEXT);
        row.setTag(item);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        row.setBackground(round(0x66000000, dp(10), 0x55C8A0FF));
        row.setOnClickListener(v -> {
            pickTv(ip);
            tvInfo.setText(L.f("tv_selected", ip));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        foundList.addView(row, lp);
    }

    private String rowLabel(String item, String ip) {
        boolean chosen = settings.tv().split(":")[0].equals(ip);
        return (chosen ? "\u2714  " : "\u25B6  ") + item;
    }

    private void pickTv(String ip) {
        tvAddr.setText(ip + ":" + Settings.DEFAULT_PORT); // persisted by the TextWatcher
        settings.addKnownReceiver(ip);
        for (int i = 0; i < foundList.getChildCount(); i++) { // keep the list, just re-mark the selected entry
            View r = foundList.getChildAt(i);
            if (r instanceof TextView && r.getTag() instanceof String) {
                String item = (String) r.getTag();
                ((TextView) r).setText(rowLabel(item, item.split(" ")[0]));
            }
        }
        if (client.isStreaming()) client.start(); // while streaming, switch to the new receiver immediately
    }

    // ---- UI ----

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutTransition(smooth()); // animate children appearing/disappearing
        c.setPadding(dp(16), dp(12), dp(16), dp(14));
        c.setBackground(round(C_CARD, dp(16), 0x55C8A0FF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(c, lp);
        TextView t = text(title, 16, C_PINK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        return c;
    }

    private android.animation.LayoutTransition smooth() {
        android.animation.LayoutTransition t = new android.animation.LayoutTransition();
        t.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        t.setDuration(220);
        return t;
    }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private RadioGroup radios(LinearLayout parent, String[] labels, int selected, boolean horizontal) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(horizontal ? RadioGroup.HORIZONTAL : RadioGroup.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setTextSize(15);
            b.setTextColor(C_TEXT);
            b.setButtonTintList(ColorStateList.valueOf(C_PINK));
            b.setPadding(dp(2), dp(4), dp(14), dp(4));
            g.addView(b);
            if (i == selected) b.setChecked(true);
        }
        parent.addView(g);
        return g;
    }

    private CheckBox check(LinearLayout parent, String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(C_TEXT);
        c.setTextSize(14);
        c.setButtonTintList(ColorStateList.valueOf(C_PINK));
        c.setChecked(on);
        parent.addView(c);
        return c;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(16);
        b.setBackground(round(color, dp(22), 0x66FFFFFF));
        b.setPadding(dp(22), dp(8), dp(22), dp(8));
        return b;
    }

    private static int indexOf(RadioGroup g) {
        int id = g.getCheckedRadioButtonId();
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).getId() == id) return i;
        return 0;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutTransition(smooth());
        root.setPadding(dp(18), dp(14), dp(18), dp(24));

        // header: logo, name | language toggle + version
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("DuoBeam", 26, C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        TextView flag = text(L.PL.equals(L.lang()) ? "🇵🇱 PL" : "🇬🇧 EN", 18, C_TEXT);
        flag.setPadding(dp(10), dp(4), dp(10), dp(4));
        flag.setBackground(round(0x66000000, dp(14), 0x55C8A0FF));
        flag.setOnClickListener(v -> {
            save();
            L.set(this, L.PL.equals(L.lang()) ? L.EN : L.PL);
            build();
            client.refreshBackend(); // re-render side bar and notification in the new language
        });
        right.addView(flag);
        TextView ver = text("v" + BuildConfig.VERSION_NAME, 12, C_MUTED);
        ver.setGravity(Gravity.END);
        right.addView(ver);
        head.addView(right);
        root.addView(head);

        // stream
        LinearLayout ctl = card(root, L.s("card_stream"));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(8), 0, dp(6));
        Button start = button(L.s("start"), 0xFF8A3FFC);
        start.setOnClickListener(v -> {
            save();
            client.start();
        });
        Button stop = button(L.s("stop"), 0xFF4A2A7A);
        stop.setOnClickListener(v -> client.stop());
        buttons.addView(start);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(10);
        buttons.addView(stop, sp);
        ctl.addView(buttons);
        statusText = text("", 14, C_MUTED);
        ctl.addView(statusText);

        // receiver (TV)
        LinearLayout tv = card(root, L.s("card_tv"));
        tvInfo = text(L.s("tv_hint"), 13, C_MUTED);
        tv.addView(tvInfo);
        LinearLayout tvRow = new LinearLayout(this);
        tvRow.setGravity(Gravity.CENTER_VERTICAL);
        tvAddr = new EditText(this);
        tvAddr.setId(R.id.receiver_address);
        tvAddr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        tvAddr.setTextColor(C_TEXT);
        tvAddr.setText(settings.tv());
        tvAddr.addTextChangedListener(new android.text.TextWatcher() { // persist immediately; the tile, side bar and notification read it
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                settings.setTv(e.toString().trim());
                // typed by hand (not picked from the search): the old search result no longer applies
                if (tvAddr.hasFocus() && foundList != null && foundList.getChildCount() > 0) {
                    foundList.removeAllViews();
                    tvInfo.setText(L.s("tv_hint"));
                }
            }
        });
        tvAddr.setImeOptions(EditorInfo.IME_ACTION_DONE | NO_FULLSCREEN_IME);
        tvAddr.setOnEditorActionListener((v, action, ev) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            leaveField(v);
            return true;
        });
        tvRow.addView(tvAddr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button find = button(L.s("find_tv"), 0xFF5B2DB3);
        find.setOnClickListener(v -> findTv());
        tvRow.addView(find);
        tv.addView(tvRow);
        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        foundList.setLayoutTransition(smooth());
        tv.addView(foundList);

        // layout
        String[] layoutLabels = new String[Settings.LAYOUTS.length];
        for (int i = 0; i < layoutLabels.length; i++) layoutLabels[i] = L.s(Settings.LAYOUTS[i][1]);
        LinearLayout layoutCard = card(root, L.s("card_layout"));
        layoutGroup = radios(layoutCard, layoutLabels, settings.layout(), false);
        if (!twoScreens) layoutCard.setVisibility(View.GONE); // one screen: nothing to arrange
        layoutGroup.setOnCheckedChangeListener((g, id) -> {
            if (indexOf(g) != settings.layout()) client.setLayoutAndApply(indexOf(g)); // applies immediately, also mid-stream
        });

        // quality
        LinearLayout q = card(root, L.s("card_quality"));
        bitrateGroup = radios(q, new String[] {L.s("q_low"), L.s("q_normal"), L.s("q_high")}, settings.bitrate(), true);
        fpsGroup = radios(q, new String[] {L.s("fps_60"), L.s("fps_30")}, settings.fps(), true);
        // saved immediately; while streaming, the sender restarts with the new value
        bitrateGroup.setOnCheckedChangeListener((g, id) -> {
            if (indexOf(g) == settings.bitrate()) return;
            settings.setBitrate(indexOf(g));
            if (client.isStreaming()) client.start();
        });
        fpsGroup.setOnCheckedChangeListener((g, id) -> {
            if (indexOf(g) == settings.fps()) return;
            settings.setFps(indexOf(g));
            if (client.isStreaming()) client.start();
        });

        // audio
        LinearLayout snd = card(root, L.s("card_audio"));
        audioGroup = radios(snd, new String[] {L.s("audio_off"), L.s("audio_tv"), L.s("audio_both")}, settings.audio(), false);
        audioGroup.setOnCheckedChangeListener((g, id) -> {
            if (indexOf(g) != settings.audio()) client.setAudioAndApply(indexOf(g)); // applies immediately, also mid-stream
        });
        snd.addView(text(L.s("audio_hint"), 12, C_MUTED));

        // one-time setup
        setupCard = card(root, L.s("card_setup"));
        setupText = text("", 14, C_MUTED);
        setupCard.addView(setupText);
        checklist = new LinearLayout(this);
        checklist.setOrientation(LinearLayout.VERTICAL);
        checklist.setLayoutTransition(smooth());
        setupCard.addView(checklist);
        setupBtn = button(L.s("setup_btn"), 0xFF8A3FFC);
        setupBtn.setOnClickListener(v -> startSetup());
        setupCard.addView(setupBtn);
        retryBtn = button(L.s("setup_retry"), 0xFF5B2DB3);
        retryBtn.setOnClickListener(v -> client.grantAsync());
        setupCard.addView(retryBtn);

        pairBox = new LinearLayout(this);
        pairBox.setOrientation(LinearLayout.VERTICAL);
        pairBox.setLayoutTransition(smooth());
        pairBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        pairBox.setBackground(round(0x66000000, dp(12), 0xAAFF7AD9));
        pairBox.setVisibility(View.GONE);
        pairBox.addView(text(L.s("setup_steps"), 15, C_TEXT));
        pairBox.addView(text(L.s("pair_what"), 12, C_MUTED));
        pairLive = text(L.s("pair_searching"), 13, C_PINK);
        pairBox.addView(pairLive);
        pairWhere = text("", 12, C_MUTED);
        pairWhere.setVisibility(View.GONE);
        pairBox.addView(pairWhere);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        codeInput = new EditText(this);
        codeInput.setId(R.id.pair_code);
        codeInput.setHint(L.s("pair_code"));
        codeInput.setHintTextColor(C_MUTED);
        codeInput.setTextColor(C_TEXT);
        codeInput.setTextSize(20);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setImeOptions(NO_FULLSCREEN_IME);
        row.addView(codeInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        portInput = new EditText(this);
        portInput.setId(R.id.pair_port);
        portInput.setHint("port");
        portInput.setHintTextColor(C_MUTED);
        portInput.setTextColor(C_MUTED);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setImeOptions(NO_FULLSCREEN_IME);
        Button go = button(L.s("pair_btn"), 0xFF8A3FFC);
        row.addView(go);
        pairBox.addView(row);
        // the port is detected automatically; the manual field is shown only on request
        portRow = new LinearLayout(this);
        portRow.setGravity(Gravity.CENTER_VERTICAL);
        portRow.setVisibility(View.GONE);
        portRow.addView(text("port:", 13, C_MUTED));
        portRow.addView(portInput, new LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT));
        pairBox.addView(portRow);
        TextView portLink = text(L.s("pair_port_link"), 12, C_PINK);
        portLink.setOnClickListener(v -> portRow.setVisibility(View.VISIBLE));
        pairBox.addView(portLink);
        pairBox.addView(text(L.s("pair_notif"), 12, C_MUTED));
        TextView cancel = text("\u2715  " + L.s("cancel"), 13, C_PINK);
        cancel.setPadding(0, dp(6), 0, 0);
        cancel.setOnClickListener(v -> {
            settings.setSetupInProgress(false);
            pairRequested = false;
            PairingReceiver.end(this);
            PairingReceiver.clearNotification(this);
            pairBox.setVisibility(View.GONE);
        });
        pairBox.addView(cancel);
        setupCard.addView(pairBox);
        PairingReceiver.ui = (msg, p, done) -> {
            pairLive.setText(msg);
            if (p > 0 && portInput.getText().length() == 0) portInput.setText(String.valueOf(p));
            if (done) {
                codeInput.setText("");
                pairRequested = false;
                handler.postDelayed(() -> pairBox.setVisibility(View.GONE), 4000);
            }
            refresh();
        };
        go.setOnClickListener(v -> {
            int p;
            try {
                p = Integer.parseInt(portInput.getText().toString().trim());
            } catch (NumberFormatException e) {
                p = PairingReceiver.port();
            }
            PairingReceiver.pairAsync(this, p, codeInput.getText().toString());
        });
        bootStart = check(setupCard, L.s("boot_start"), settings.bootStart());
        bootStart.setOnCheckedChangeListener((b, on) -> settings.setBootStart(on));

        // device screens while streaming
        LinearLayout scr = card(root, L.s(twoScreens ? "card_screens" : "card_screen"));
        screensGroup = radios(scr, twoScreens
                        ? new String[] {L.s("scr_both_on"), L.s("scr_top_off"), L.s("scr_bottom_off"), L.s("scr_both_off")}
                        : new String[] {L.s("scr_on"), "", "", L.s("scr_off")},
                shownScreens(settings.screensOff()), false);
        if (!twoScreens) { // keep the indexes as masks, just hide the per-screen options
            screensGroup.getChildAt(1).setVisibility(View.GONE);
            screensGroup.getChildAt(2).setVisibility(View.GONE);
        }
        // click listeners, not check-change: re-selecting the same option still applies it, and syncing from the side bar sends nothing
        for (int i = 0; i < screensGroup.getChildCount(); i++) {
            final int mask = i;
            screensGroup.getChildAt(i).setOnClickListener(v -> client.setScreens(mask));
        }
        touchBox = check(scr, L.s("scr_touch"), settings.screenTouch());
        touchBox.setOnCheckedChangeListener((b, on) -> {
            if (on != settings.screenTouch()) client.setScreenTouch(on);
        });
        scr.addView(text(L.s("scr_hint"), 12, C_MUTED));

        // options
        LinearLayout opt = card(root, L.s("card_options"));
        wifiLock = check(opt, L.s("opt_wifi"), settings.wifiLock());
        wifiLock.setOnCheckedChangeListener((b, on) -> {
            if (on == settings.wifiLock()) return;
            settings.setWifiLock(on);
            if (client.isStreaming()) client.start();
        });
        autostart = check(opt, L.s("opt_autostart"), settings.autostart());
        autostart.setOnCheckedChangeListener((b, on) -> settings.setAutostart(on));
        overlay = check(opt, L.s("opt_overlay"), settings.overlay());
        overlay.setOnCheckedChangeListener((b, on) -> {
            if (on && !android.provider.Settings.canDrawOverlays(this)) openOverlaySettings();
            if (on != settings.overlay()) client.setOverlay(on);
            if (client.isStreaming()) ControlService.ensureRunning(this);
        });
        // shortcut to the permission the side bar needs, shown only while it is missing
        overlayPerm = text("\u21bb  " + L.s("opt_overlay_perm"), 13, C_PINK);
        overlayPerm.setPadding(dp(36), 0, 0, dp(6));
        overlayPerm.setOnClickListener(v -> openOverlaySettings());
        opt.addView(overlayPerm);
        updateOverlayPerm();
        opt.addView(text(L.s("opt_tile"), 12, C_MUTED));

        scroll = new ScrollView(this) {
            // only scroll by itself to keep a text field in view; status lines changing height must not move the page
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return findFocus() instanceof EditText ? super.computeScrollDeltaToGetChildRectOnScreen(r) : 0;
            }
        };
        scroll.setBackgroundResource(R.drawable.bg);
        // the page itself takes focus, so the address field doesn't grab it (and the keyboard) on start
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.addView(root);
        return scroll;
    }

    /** Tapping anywhere outside a text field closes the keyboard and leaves the field. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            if (f instanceof EditText) {
                Rect r = new Rect();
                f.getGlobalVisibleRect(r);
                if (!r.contains((int) e.getRawX(), (int) e.getRawY())) leaveField(f);
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private void leaveField(View f) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        f.clearFocus();
        if (scroll != null) scroll.getChildAt(0).requestFocus();
    }
}
