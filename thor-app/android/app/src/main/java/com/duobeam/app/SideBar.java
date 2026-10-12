package com.duobeam.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Overlay edge tab on the right side of the secondary (bottom) display, plus an in-game control panel.
 * The tab and the panel are separate windows: the tab never shifts, the panel opens beside it,
 * stays within the screen (scrolling if needed) and closes on any outside touch.
 */
final class SideBar {

    private static final int C_TEXT = 0xFFEDE4FF, C_MUTED = 0xFFBFB0E6, C_PINK = 0xFFFF7AD9, C_RED = 0xFFFF7A9A;

    private final Context base;
    private final StreamClient client;
    private Context wc;
    private WindowManager wm;
    private View tab;
    private WindowManager.LayoutParams tabLp;
    private ScrollView panelWin;
    private LinearLayout panel;
    private long panelClosedAt;
    private String panelSig = "";
    private TextView statusView;

    SideBar(Context base, StreamClient client) {
        this.base = base;
        this.client = client;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, base.getResources().getDisplayMetrics());
    }

    private static GradientDrawable round(int color, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(2, stroke);
        return g;
    }

    /** Shows or hides the tab per settings and refreshes the panel if it is open. */
    void update() {
        boolean want = client.settings().overlay() && Settings.canDrawOverlays(base);
        if (!want) {
            remove();
            return;
        }
        if (tab == null) createTab();
        if (panelWin == null) return;
        if (!panelSig().equals(panelSig)) { // options or language changed: rebuild and resize the panel
            fillPanel();
            positionPanel();
        } else if (statusView != null) { // status only: update in place so in-flight taps aren't lost
            String tvl = client.tvLine();
            statusView.setText(client.humanStatus() + (tvl.isEmpty() ? "" : "\n" + tvl));
            statusView.setTextColor(client.tvOk() ? 0xFF7CF0B0 : C_MUTED);
        }
    }

    private String panelSig() {
        com.duobeam.app.Settings s = client.settings();
        return L.lang() + s.layout() + "/" + s.audio() + "/" + client.screensNow() + "/" + s.screenTouch();
    }

    void remove() {
        closePanel(false);
        if (tab != null && wm != null) {
            try {
                wm.removeView(tab);
            } catch (Exception ignored) {
            }
        }
        tab = null;
    }

    // ---- edge tab ----

    private void createTab() {
        // prefer the secondary (bottom) display so the game on the main display stays uncovered
        DisplayManager dm = base.getSystemService(DisplayManager.class);
        Display target = null;
        for (Display d : dm.getDisplays()) if (d.getDisplayId() != Display.DEFAULT_DISPLAY) target = d;
        if (target == null) target = dm.getDisplay(Display.DEFAULT_DISPLAY);
        wc = base.createDisplayContext(target).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        wm = wc.getSystemService(WindowManager.class);

        tab = new View(wc);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xEEFF7AD9, 0xEE8A3FFC});
        float r = dp(10);
        g.setCornerRadii(new float[] {r, r, 0, 0, 0, 0, r, r}); // round only the screen-facing corners
        tab.setBackground(g);
        tabLp = new WindowManager.LayoutParams(dp(14), dp(96), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        tabLp.gravity = Gravity.TOP | Gravity.END;
        tabLp.x = 0;
        tabLp.y = dp(120);
        tab.setOnTouchListener(new View.OnTouchListener() {
            float sy;
            int oy;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sy = e.getRawY(); oy = tabLp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: // vertical drag only, clamped to the screen
                        float dy = e.getRawY() - sy;
                        if (Math.abs(dy) > dp(6)) moved = true;
                        tabLp.y = Math.max(0, Math.min(screenHeight() - tabLp.height, oy + (int) dy));
                        wm.updateViewLayout(tab, tabLp);
                        if (panelWin != null) positionPanel();
                        return true;
                    case MotionEvent.ACTION_UP:
                        // tap toggles the panel; ignore it right after an outside touch closed the panel, to avoid reopening
                        if (!moved) {
                            if (panelWin != null) closePanel(true);
                            else if (System.currentTimeMillis() - panelClosedAt > 300) openPanel();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(tab, tabLp);
    }

    private int screenHeight() {
        Rect b = wm.getCurrentWindowMetrics().getBounds();
        return b.height();
    }

    // ---- panel (separate window beside the tab) ----

    private void openPanel() {
        panel = new LinearLayout(wc);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        panelWin = new ScrollView(wc);
        panelWin.setBackground(round(0xF0140A30, dp(16), 0x88C8A0FF));
        panelWin.setVerticalScrollBarEnabled(true);
        panelWin.addView(panel);
        fillPanel();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        panelWin.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) closePanel(true); // outside touch closes the panel
            return false;
        });
        panelWin.setLayoutParams(lp);
        positionPanel(lp);
        wm.addView(panelWin, lp);
        panelWin.setAlpha(0f);
        panelWin.setTranslationX(dp(30));
        panelWin.animate().alpha(1f).translationX(0).setDuration(180).start();
    }

    private void positionPanel() {
        if (panelWin == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) panelWin.getLayoutParams();
        positionPanel(lp);
        wm.updateViewLayout(panelWin, lp);
    }

    /** Places the panel beside the tab, vertically centred on it and clamped to the screen; too tall means it scrolls. */
    private void positionPanel(WindowManager.LayoutParams lp) {
        int screenH = screenHeight(), margin = dp(10);
        panel.measure(View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.min(panel.getMeasuredHeight() + dp(4), screenH - 2 * margin);
        lp.height = h;
        lp.x = tabLp.width + dp(8);
        lp.y = Math.max(margin, Math.min(screenH - h - margin, tabLp.y + tabLp.height / 2 - h / 2));
    }

    private void closePanel(boolean animate) {
        if (panelWin == null) return;
        final ScrollView w = panelWin;
        panelWin = null;
        panel = null;
        panelClosedAt = System.currentTimeMillis();
        Runnable rm = () -> {
            try {
                wm.removeView(w);
            } catch (Exception ignored) {
            }
        };
        if (animate) w.animate().alpha(0f).translationX(dp(30)).setDuration(150).withEndAction(rm).start();
        else rm.run();
    }

    // ---- panel content ----

    private TextView label(String s, int size, int color, boolean bold) {
        TextView t = new TextView(wc);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void section(String title) {
        TextView t = label(title, 12, C_MUTED, true);
        t.setPadding(0, dp(12), 0, dp(6));
        panel.addView(t);
    }

    /** Selectable chips, two per row, with the selected one highlighted. */
    private void chips(String[] labels, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(wc);
                row.setOrientation(LinearLayout.HORIZONTAL);
                panel.addView(row);
            }
            final int idx = i;
            TextView c = label(labels[i], 13, i == selected ? 0xFFFFFFFF : C_TEXT, i == selected);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(6), dp(10), dp(6), dp(10));
            if (i == selected) {
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFF8A3FFC, 0xFFFF7AD9});
                g.setCornerRadius(dp(12));
                c.setBackground(g);
            } else {
                c.setBackground(round(0x33FFFFFF, dp(12), 0x44C8A0FF));
            }
            c.setOnClickListener(v -> onPick.accept(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i % 2 == 0 ? 0 : dp(4), dp(3), i % 2 == 0 ? dp(4) : 0, dp(3));
            row.addView(c, lp);
        }
        if (labels.length % 2 == 1 && labels.length > 1 && row != null) { // pad the last row so a lone chip keeps half width
            View spacer = new View(wc);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.leftMargin = dp(4);
            row.addView(spacer, lp);
        }
    }

    private void action(LinearLayout row, String text, int color, View.OnClickListener l) {
        TextView b = label(text, 13, color, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(10), dp(4), dp(10));
        b.setBackground(round(0x22FFFFFF, dp(12), 0x33C8A0FF));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        row.addView(b, lp);
    }

    private void fillPanel() {
        if (panel == null) return;
        panel.removeAllViews();
        panelSig = panelSig();
        // header: name + status
        panel.addView(label("DuoBeam", 16, 0xFFFFFFFF, true));
        String tvl = client.tvLine();
        TextView st = label(client.humanStatus() + (tvl.isEmpty() ? "" : "\n" + tvl), 12, client.tvOk() ? 0xFF7CF0B0 : C_MUTED, false);
        st.setPadding(0, dp(2), 0, 0);
        panel.addView(st);
        statusView = st;

        boolean two = com.duobeam.app.Settings.twoScreens(wc);
        if (two) {
            section(L.s("card_layout"));
            String[] lay = new String[com.duobeam.app.Settings.LAYOUTS.length];
            for (int i = 0; i < lay.length; i++) lay[i] = L.s(com.duobeam.app.Settings.LAYOUTS[i][1]);
            chips(lay, client.settings().layout(), idx -> {
                client.setLayoutAndApply(idx);
                closePanel(true);
            });
        }

        section(L.s("card_audio"));
        chips(new String[] {L.s("audio_off"), L.s("audio_tv"), L.s("audio_both")}, client.settings().audio(), a -> {
            client.setAudioAndApply(a);
            closePanel(true);
        });

        if (two) {
            section(L.s("card_screens"));
            chips(new String[] {L.s("scr_both_on"), L.s("scr_top_off"), L.s("scr_bottom_off"), L.s("scr_both_off")}, client.screensNow(), m -> {
                client.setScreens(m);
                closePanel(true);
            });
        } else {
            section(L.s("card_screen"));
            chips(new String[] {L.s("scr_on"), L.s("scr_off")}, client.screensNow() == 0 ? 0 : 1, m -> {
                client.setScreens(m == 0 ? 0 : 3);
                closePanel(true);
            });
        }
        boolean touch = client.settings().screenTouch();
        chips(new String[] {L.s("scr_touch_short") + ": " + L.s(touch ? "on" : "off")}, touch ? 0 : -1, x -> {
            client.setScreenTouch(!client.settings().screenTouch());
            fillPanel();
            positionPanel();
        });
        TextView hint = label(L.s(touch ? "ov_touch_on" : "ov_touch_off"), 11, C_MUTED, false);
        hint.setPadding(0, dp(4), 0, 0);
        panel.addView(hint);

        LinearLayout actions = new LinearLayout(wc);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        panel.addView(actions);
        action(actions, "■ " + L.s("stop"), C_RED, v -> {
            closePanel(true);
            client.stop();
        });
        action(actions, L.s("ov_off_short"), C_MUTED, v -> {
            remove();
            client.setOverlay(false);
        });
    }
}
